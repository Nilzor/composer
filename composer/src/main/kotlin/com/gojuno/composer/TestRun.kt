package com.gojuno.composer

import com.gojuno.commander.android.*
import com.gojuno.commander.os.Notification
import com.gojuno.commander.os.nanosToHumanReadableTime
import com.gojuno.commander.os.process
import rx.Observable
import rx.Single
import rx.schedulers.Schedulers
import java.io.File
import java.util.concurrent.TimeUnit

data class AdbDeviceTestRun(
        val adbDevice: AdbDevice,
        val tests: List<AdbDeviceTest>,
        val passedCount: Int,
        val ignoredCount: Int,
        val failedCount: Int,
        val durationNanos: Long,
        val timestampMillis: Long,
        val logcat: File,
        val instrumentationOutput: File
)

data class AdbDeviceTest(
        val adbDevice: AdbDevice,
        val className: String,
        val testName: String,
        val status: Status,
        val durationNanos: Long,
        val logcat: File,
        val files: List<File>,
        val screenshots: List<File>
) {
    sealed class Status {
        object Passed : Status()
        data class Ignored(val stacktrace: String) : Status()
        data class Failed(val stacktrace: String) : Status()
    }
}

fun AdbDevice.runTests(
        testPackageName: String,
        testRunnerClass: String,
        instrumentationArguments: String,
        outputDir: File,
        verboseOutput: Boolean,
        keepOutput: Boolean,
        useTestServices: Boolean,
        screenshotFolderOnDevice: String
): Single<AdbDeviceTestRun> {

    val adbDevice = this
    val logsDir = File(File(outputDir, "logs"), adbDevice.pathSafeId)
    val instrumentationOutputFile = File(logsDir, "instrumentation.output")
    val commandPrefix = if (useTestServices) {
        "CLASSPATH=$(pm path androidx.test.services) app_process / androidx.test.services.shellexecutor.ShellMain "
    } else ""

    val runTests = process(
            commandAndArgs = listOf(
                    adb,
                    "-s", adbDevice.id,
                    "shell", "${commandPrefix}am instrument -w -r $instrumentationArguments $testPackageName/$testRunnerClass"
            ),
            timeout = null,
            redirectOutputTo = instrumentationOutputFile,
            keepOutputOnExit = keepOutput
    ).share()

    adbDevice.log("Will pull screenshots from device folder $screenshotFolderOnDevice")
    @Suppress("destructure")
    val runningTests = runTests
            .ofType(Notification.Start::class.java)
            .flatMap { readInstrumentationOutput(it.output) }
            .asTests()
            .doOnNext { test ->
                val status = when (test.status) {
                    is InstrumentationTest.Status.Passed -> "passed"
                    is InstrumentationTest.Status.Ignored -> "ignored"
                    is InstrumentationTest.Status.Failed -> "failed"
                }

                adbDevice.log(
                        "Test ${test.index}/${test.total} $status in " +
                        "${test.durationNanos.nanosToHumanReadableTime()}: " +
                        "${test.className}.${test.testName}"
                )
            }
            .flatMap { test ->
                pullTestFiles(adbDevice, test, outputDir, verboseOutput, screenshotFolderOnDevice)
                        .toObservable()
                        .subscribeOn(Schedulers.io())
                        .map { pulledFiles -> test to pulledFiles }
            }
            .toList()

    val adbDeviceTestRun = Observable
            .zip(
                    Observable.fromCallable { System.nanoTime() },
                    runningTests,
                    { time, tests -> time to tests }
            )
            .map { (startTimeNanos, testsWithPulledFiles) ->
                val tests = testsWithPulledFiles.map { it.first }

                AdbDeviceTestRun(
                        adbDevice = adbDevice,
                        tests = testsWithPulledFiles.map { (test, pulledFiles) ->
                            AdbDeviceTest(
                                    adbDevice = adbDevice,
                                    className = test.className,
                                    testName = test.testName,
                                    status = when (test.status) {
                                        is InstrumentationTest.Status.Passed -> AdbDeviceTest.Status.Passed
                                        is InstrumentationTest.Status.Ignored -> AdbDeviceTest.Status.Ignored(test.status.stacktrace)
                                        is InstrumentationTest.Status.Failed -> AdbDeviceTest.Status.Failed(test.status.stacktrace)
                                    },
                                    durationNanos = test.durationNanos,
                                    logcat = logcatFileForTest(logsDir, test.className, test.testName),
                                    files = pulledFiles.files.sortedBy { it.name },
                                    screenshots = pulledFiles.screenshots.sortedBy { it.name }
                            )
                        },
                        passedCount = tests.count { it.status is InstrumentationTest.Status.Passed },
                        ignoredCount = tests.count { it.status is InstrumentationTest.Status.Ignored },
                        failedCount = tests.count { it.status is InstrumentationTest.Status.Failed },
                        durationNanos = System.nanoTime() - startTimeNanos,
                        timestampMillis = System.currentTimeMillis(),
                        logcat = logcatFileForDevice(logsDir),
                        instrumentationOutput = instrumentationOutputFile
                )
            }

    val testRunFinish = runTests.ofType(Notification.Exit::class.java).cache()

    // Clearing logcat before starting listening will reduce the delay for receiving relevant lines to a minimum
    // There will still be a delay so there is a possibility that we might not get test-specific log lines
    // stored to disk. Solution for that would be to wait explicitly for log lines from TestRunner before
    // killing the log listener and completing the test run
    var adbLogcatProcess: Process? = null
    val clearAndSaveLogcat = clearLogcat(adbDevice)
        .flatMap { saveLogcat(adbDevice, logsDir) }
        .doOnNext {
            log("Logcat parsing process started with PID ${it.pid()}")
            adbLogcatProcess = it
        }

    return Observable
            .zip(adbDeviceTestRun, clearAndSaveLogcat, testRunFinish) { suite, adbProcess, _ ->
                suite to adbProcess
            }
            .doOnSubscribe { adbDevice.log("Starting tests...") }
            .doOnNext { (testRun, adbProcess) ->
                adbDevice.log(
                        "Test run finished, " +
                        "${testRun.passedCount} passed, " +
                        "${testRun.failedCount} failed, took " +
                        "${testRun.durationNanos.nanosToHumanReadableTime()}."
                )
                log("Stopping ADB logcat listener - PID ${adbProcess.pid()}")
                adbProcess.destroy()
            }
            .map { (testRun, _) -> testRun }
            .doOnError {
                adbDevice.log("Error during tests run: $it")
                try {
                    adbLogcatProcess?.let { adbProc ->
                        log("Killing ADB process with PID ${adbProc.pid()}")
                        adbProc.destroy()
                    } ?: log("No ADB process to kill")
                } catch (ex: Exception) { }
            }
            .toSingle()
}

data class PulledFiles(
        val files: List<File>,
        val screenshots: List<File>
)

private fun pullTestFiles(adbDevice: AdbDevice, test: InstrumentationTest, outputDir: File, verboseOutput: Boolean, screenshotFolderOnDevice: String): Single<PulledFiles> = Single
        .fromCallable {
            File(File(File(outputDir, "screenshots"), adbDevice.pathSafeId), test.className).apply { mkdirs() }
        }
        .flatMap { screenshotsFolderOnHostMachine ->
            val folderOnDevice = "$screenshotFolderOnDevice/${test.className}/${test.testName}"
            adbDevice
                    .pullFolder(
                            folderOnDevice = folderOnDevice,
                            folderOnHostMachine = screenshotsFolderOnHostMachine,
                            logErrors = verboseOutput
                    )
                    .map {
                        File(screenshotsFolderOnHostMachine, test.testName)
                    }
        }
        .map { screenshotsFolderOnHostMachine ->
            PulledFiles(
                    files = emptyList(), // TODO: Pull test files.
                    screenshots = screenshotsFolderOnHostMachine.let {
                        when (it.exists()) {
                            true -> it.listFiles().toList()
                            else -> emptyList()
                        }
                    }
            )
        }

internal fun String.parseTestClassAndName(): Pair<String, String>? {
    val index = indexOf("TestRunner")
    if (index < 0) return null

    val tokens = substring(index, length).split(':')
    if (tokens.size != 3) return null

    val startedOrFinished = tokens[1].trimStart()
    if (startedOrFinished == "started" || startedOrFinished == "finished") {
        return tokens[2].substringAfter("(").removeSuffix(")") to tokens[2].substringBefore("(").trim()
    }
    return null
}

/**
 * Emits a single Process as soon as Logcat parsing has started
 */
private fun saveLogcat(adbDevice: AdbDevice, logsDir: File): Observable<Process> {
    val dirAndFile = logsDir to logcatFileForDevice(logsDir)
    val fullLogcatFile = dirAndFile.second

    return adbDevice.redirectLogcatToFile(fullLogcatFile).toObservable().doOnNext {
        processLogCat(fullLogcatFile, logsDir)
    }
}

private fun processLogCat(fullLogcatFile: File, logsDir: File) {
    data class Result(val logcat: String = "", val startedTestClassAndName: Pair<String, String>? = null, val finishedTestClassAndName: Pair<String, String>? = null)

    tail(fullLogcatFile).scan(Result()) { previous, newline ->
        val logcat = when (previous.startedTestClassAndName != null && previous.finishedTestClassAndName != null) {
            true -> newline
            false -> "${previous.logcat}\n$newline" +
                    "¨"
        }

        // Implicitly expecting to see logs from `android.support.test.internal.runner.listener.LogRunListener`.
        // Was not able to find more reliable solution to capture logcat per test.
        val startedTest: Pair<String, String>? = newline.parseTestClassAndName()
        val finishedTest: Pair<String, String>? = newline.parseTestClassAndName()

        val res = Result(
            logcat = logcat,
            startedTestClassAndName = startedTest ?: previous.startedTestClassAndName,
            finishedTestClassAndName = finishedTest // Actual finished test should always overwrite previous.
        )
        if (res.startedTestClassAndName != null && res.startedTestClassAndName == res.finishedTestClassAndName) {
            logcatFileForTest(
                logsDir,
                className = res.startedTestClassAndName.first,
                testName = res.startedTestClassAndName.second
            )
                .apply { parentFile.mkdirs() }
                .writeText(res.logcat)
            res.startedTestClassAndName
        }
        res
    }.observeOn(Schedulers.computation())
        .subscribeOn(Schedulers.computation())
        .subscribe()
}

/** Clear logcat */
private fun clearLogcat(adbDevice: AdbDevice) =
    process(listOf(adb, "-s", adbDevice.id, "logcat", "-c"), timeout = 5 to TimeUnit.SECONDS, destroyOnUnsubscribe = true)
        .ofType(Notification.Exit::class.java)
        .doOnError { adbDevice.log("Error attempting to clear logcat for device") }
        .take(1)
        .doOnCompleted { adbDevice.log("Logcat cleared") }

private fun logcatFileForDevice(logsDir: File) = File(logsDir, "full.logcat")

private fun logcatFileForTest(logsDir: File, className: String, testName: String): File = File(File(logsDir, className), "$testName.logcat")
