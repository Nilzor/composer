package com.gojuno.composer

import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

data class DeviceTestResult(
    val deviceName: String,
    val className: String,
    val testName: String,
    val status: MarkdownTestStatus,
    val stacktrace: String = ""
)

enum class MarkdownTestStatus { PASSED, FAILED, SKIPPED }

fun generateMarkdownReport(xmlDir: File, outputFile: File, externalLogUrlTemplate: String, deviceAliasMap: Map<String, String> = emptyMap()) {
    val results = parseJunitXmlDir(xmlDir, deviceAliasMap)
    val markdown = buildMarkdown(results, externalLogUrlTemplate)
    outputFile.parentFile?.mkdirs()
    outputFile.writeText(markdown)
}

private fun resolveDeviceName(filenameStem: String, deviceAliasMap: Map<String, String>): String {
    // XML files are named by pathSafeId (colons replaced with underscores).
    // Try exact match first, then restore colons for devices like "192.168.1.1:5555".
    return deviceAliasMap[filenameStem]
        ?: deviceAliasMap[filenameStem.replace("_", ":")]
        ?: filenameStem
}

private fun parseJunitXmlDir(dir: File, deviceAliasMap: Map<String, String>): List<DeviceTestResult> =
    dir.listFiles { f -> f.extension == "xml" }
        ?.sortedBy { it.name }
        ?.flatMap { file ->
            val deviceName = resolveDeviceName(file.nameWithoutExtension, deviceAliasMap)
            parseJunitXml(file, deviceName)
        }
        ?: emptyList()

private fun parseJunitXml(file: File, deviceName: String): List<DeviceTestResult> {
    val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
    val testcases = doc.getElementsByTagName("testcase")
    val results = mutableListOf<DeviceTestResult>()

    for (i in 0 until testcases.length) {
        val tc = testcases.item(i) as Element
        val className = tc.getAttribute("classname")
        val testName = tc.getAttribute("name")

        val failureNodes = tc.getElementsByTagName("failure")
        val skippedNodes = tc.getElementsByTagName("skipped")

        when {
            failureNodes.length > 0 -> {
                val stacktrace = failureNodes.item(0).textContent?.trim() ?: ""
                results.add(DeviceTestResult(deviceName, className, testName, MarkdownTestStatus.FAILED, stacktrace))
            }
            skippedNodes.length > 0 ->
                results.add(DeviceTestResult(deviceName, className, testName, MarkdownTestStatus.SKIPPED))
            else ->
                results.add(DeviceTestResult(deviceName, className, testName, MarkdownTestStatus.PASSED))
        }
    }

    return results
}

private fun buildMarkdown(results: List<DeviceTestResult>, externalLogUrlTemplate: String): String {
    val sb = StringBuilder()
    sb.append("# Test Results\n")
    sb.append("\n")

    buildFailuresSection(results, sb)
    buildSummaryTable(results, externalLogUrlTemplate, sb)

    return sb.toString()
}

private fun buildSummaryTable(results: List<DeviceTestResult>, externalLogUrlTemplate: String, sb: StringBuilder) {
    sb.append("## Summary\n")
    sb.append("\n")

    val hasDatadog = externalLogUrlTemplate.isNotEmpty()
    val header = if (hasDatadog) "| Class | Test | Succeeded | Log | Status |\n" else "| Class | Test | Succeeded | Status |\n"
    val divider = if (hasDatadog) "|---|---|---|---|---|\n" else "|---|---|---|---|\n"
    sb.append(header)
    sb.append(divider)

    val grouped = results
        .filter { it.status != MarkdownTestStatus.SKIPPED }
        .groupBy { it.className to it.testName }
        .entries
        .sortedWith(compareBy({ it.key.first }, { it.key.second }))

    grouped.forEach { (classAndTest, deviceResults) ->
        val (fullClassName, testName) = classAndTest
        val simpleClassName = fullClassName.substringAfterLast('.')
        val passed = deviceResults.count { it.status == MarkdownTestStatus.PASSED }
        val total = deviceResults.size
        val statusSymbol = when {
            passed == total -> "✅"
            passed == 0 -> "❌"
            else -> "⚠️"
        }
        val logCell = if (hasDatadog) {
            val url = externalLogUrlTemplate
                .replace("[FullClassName]", fullClassName)
                .replace("[SimpleClassName]", simpleClassName)
                .replace("[TestName]", testName)
            " [Log]($url) |"
        } else ""

        sb.append("| $simpleClassName | $testName | $passed/$total |$logCell $statusSymbol |\n")
    }

    sb.append("\n")
}

private fun buildFailuresSection(results: List<DeviceTestResult>, sb: StringBuilder) {
    val failures = results.filter { it.status == MarkdownTestStatus.FAILED }
    if (failures.isEmpty()) return

    sb.append("## Failed Tests\n")
    sb.append("\n")
    sb.append("<table>\n")
    sb.append("<tr><th>Device</th><th>Class</th><th>Test</th></tr>\n")
    failures.forEach { result ->
        val simpleClassName = result.className.substringAfterLast('.')
        sb.append("<tr><td>${result.deviceName}</td><td>$simpleClassName</td><td>${result.testName}</td></tr>\n")
        if (result.stacktrace.isNotEmpty()) {
            sb.append("<tr><td colspan=\"3\"><details><summary>Stacktrace</summary><pre>")
            sb.append(result.stacktrace.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"))
            sb.append("</pre></details></td></tr>\n")
        }
    }
    sb.append("</table>\n")
    sb.append("\n")
}
