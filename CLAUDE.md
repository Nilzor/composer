# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What This Project Does

**Composer** is a reactive Android instrumentation test runner — a modern replacement for Square's Spoon. It runs Android UI tests across multiple devices/emulators in parallel, reactively pulls test artifacts (screenshots, files) after each test, captures logcat output, and generates JUnit XML + HTML reports.

## Build Commands

```bash
# Build the project
./gradlew build

# Build a fat JAR with all dependencies bundled
./gradlew shadowJar

# Run all tests
./gradlew test

# Run a single test class
./gradlew test --tests "com.gojuno.composer.ApkSpec"

# Clean
./gradlew clean
```

### HTML Report (React UI)
```bash
cd html-report
npm install
npm run build
```

The built HTML report assets are embedded into the Kotlin jar at build time.

## Architecture

### Modules
- **`composer/`** — Kotlin application (main module)
- **`html-report/`** — React-based test report viewer

### Core Execution Flow (`Main.kt`)
1. Parse CLI args (JCommander, `Args.kt`)
2. Extract test metadata from APK — package name, test runner, test methods via `aapt` + LinkedIn DexParser (`Apk.kt`)
3. Discover ADB-connected devices, optionally filtered by pattern/list
4. For each device in parallel:
   - Install app APK and test APK
   - Run `adb shell am instrument` and parse output reactively (`Instrumentation.kt`)
   - Pull screenshots/files from device after each test
   - Capture logcat (full run + per-test)
5. Generate JUnit4 XML (`JUnitReport.kt`) and HTML report (`html/`)

### Key Design Decisions
- **RxJava 1.x** is used throughout for reactive/async orchestration — expect `Observable`, `Single`, `Completable` chains everywhere
- **ADB is invoked directly** via shell commands (not ddmlib) to avoid ddmlib reliability issues
- Test sharding: when `--shard` is set, the test list is split across devices by index

### Key Files
| File | Role |
|------|------|
| `Main.kt` | Entry point; top-level orchestration |
| `Args.kt` | All CLI argument definitions |
| `Apk.kt` | APK metadata parsing (aapt + DexParser) |
| `Instrumentation.kt` | Real-time instrumentation output parser |
| `TestRun.kt` | Per-device test execution and artifact collection |
| `JUnitReport.kt` | JUnit XML report generation |
| `html/HtmlReport.kt` | HTML report orchestration |
| `dependencies.gradle` | Centralized dependency versions |

## Test Framework

Tests use **Spek** (Kotlin specification framework) running on JUnit Platform. Test files live in `composer/src/test/kotlin/` and follow the `describe { it { } }` DSL style.

## Running the Tool

```bash
java -jar composer.jar \
  --apk app.apk \
  --test-apk app-tests.apk \
  [--output-directory output/] \
  [--shard] \
  [--devices emulator-5554,emulator-5556] \
  [--test-runner com.example.TestRunner] \
  [--instrumentation-arguments key1 value1 key2 value2] \
  [--external-log-url http://ci-server/logs/{serial}]
```

Exit code `0` = all tests passed; exit code `1` = failures, no devices found, or parse errors.
