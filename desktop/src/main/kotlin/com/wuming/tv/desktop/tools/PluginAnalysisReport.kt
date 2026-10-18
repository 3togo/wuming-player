package com.wuming.tv.desktop.tools

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Generates a human-readable Markdown report from the plugin analysis,
 * with specific recommendations for improving OpenDrive robustness.
 */
object PluginAnalysisReport {

    fun generate(report: PluginAnalyzer.AnalysisReport, outputDir: Path): Path {
        val sb = StringBuilder()
        val now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))

        sb.appendLine("# Plugin Analysis Report")
        sb.appendLine()
        sb.appendLine("Generated: $now")
        sb.appendLine()
        sb.appendLine("## Summary")
        sb.appendLine()
        sb.appendLine("| Metric | Value |")
        sb.appendLine("|---|---|")
        sb.appendLine("| Configurations analyzed | ${report.configs.size} |")
        sb.appendLine("| Total sites | ${report.totalSites} |")
        sb.appendLine("| Cloud provider sites | ${report.totalCloudProviderSites} |")
        sb.appendLine("| JAR artifacts analyzed | ${report.jars.size} |")
        sb.appendLine("| OpenDrive findings | ${report.openDriveFindings.size} |")
        sb.appendLine()

        // Cloud provider distribution
        sb.appendLine("## Cloud Provider Distribution")
        sb.appendLine()
        sb.appendLine("| Provider | Sites |")
        sb.appendLine("|---|---|")
        report.cloudProviderDistribution.forEach { (provider, count) ->
            sb.appendLine("| ${provider.displayName} | $count |")
        }
        sb.appendLine()

        // Site type distribution
        sb.appendLine("## Site Type Distribution")
        sb.appendLine()
        sb.appendLine("| Type | Count | Description |")
        sb.appendLine("|---|---|---|")
        val typeDescriptions = mapOf(
            0 to "Direct HTTP (MacCMS)",
            1 to "JAR Spider",
            3 to "JavaScript",
            4 to "Remote Spider HTTP",
        )
        report.typeDistribution.forEach { (type, count) ->
            sb.appendLine("| $type | $count | ${typeDescriptions[type] ?: "Unknown"} |")
        }
        sb.appendLine()

        // Runtime distribution
        sb.appendLine("## Runtime Distribution")
        sb.appendLine()
        sb.appendLine("| Runtime | Sites |")
        sb.appendLine("|---|---|")
        report.runtimeDistribution.forEach { (runtime, count) ->
            sb.appendLine("| $runtime | $count |")
        }
        sb.appendLine()

        // Header patterns
        sb.appendLine("## Common Header Patterns")
        sb.appendLine()
        val commonHeaders = report.commonHeaderPatterns.filter { it.value >= 2 }
        if (commonHeaders.isEmpty()) {
            sb.appendLine("No significant header patterns detected across configurations.")
        } else {
            sb.appendLine("| Header | Occurrences |")
            sb.appendLine("|---|---|")
            commonHeaders.forEach { (header, count) ->
                sb.appendLine("| `$header` | $count |")
            }
        }
        sb.appendLine()

        // Ext patterns
        sb.appendLine("## Extension (ext) Patterns")
        sb.appendLine()
        sb.appendLine("| Type | Count |")
        sb.appendLine("|---|---|")
        report.commonExtPatterns.forEach { (type, count) ->
            sb.appendLine("| $type | $count |")
        }
        sb.appendLine()

        // JAR summary
        sb.appendLine("## JAR Artifact Summary")
        sb.appendLine()
        val js = report.jarSummary
        sb.appendLine("| Metric | Value |")
        sb.appendLine("|---|---|")
        sb.appendLine("| Total JARs | ${js.totalJars} |")
        sb.appendLine("| Average classes per JAR | ${"%.1f".format(js.avgClassCount)} |")
        sb.appendLine("| Total Spider classes | ${js.totalSpiderClasses} |")
        sb.appendLine("| JARs with cloud-related classes | ${js.jarsWithCloudClasses} |")
        sb.appendLine("| JARs using OkHttp | ${js.jarsUsingOkHttp} |")
        sb.appendLine("| JARs using Gson | ${js.jarsUsingGson} |")
        sb.appendLine("| JARs with embedded JS engine | ${js.jarsUsingJsEngine} |")
        sb.appendLine()

        // Per-config details
        sb.appendLine("## Per-Configuration Details")
        sb.appendLine()
        report.configs.forEach { config ->
            sb.appendLine("### ${config.sourceName}")
            sb.appendLine()
            sb.appendLine("- Total sites: ${config.totalSites}")
            sb.appendLine("- Cloud provider sites: ${config.cloudProviderDistribution.values.sum()}")
            if (config.cloudProviderDistribution.isNotEmpty()) {
                sb.appendLine("- Cloud providers: ${config.cloudProviderDistribution.entries.joinToString { "${it.key.displayName}(${it.value})" }}")
            }
            if (config.rootSpider.isNotBlank()) {
                sb.appendLine("- Root spider: `${config.rootSpider.take(80)}`")
            }
            sb.appendLine()
        }

        // JAR details
        if (report.jars.isNotEmpty()) {
            sb.appendLine("## JAR Artifact Details")
            sb.appendLine()
            sb.appendLine("| File | Classes | Spider Classes | Cloud Classes | OkHttp | Gson | JS Engine |")
            sb.appendLine("|---|---|---|---|---|---|---|")
            report.jars.forEach { jar ->
                sb.appendLine(
                    "| ${jar.fileName.take(30)} | ${jar.classCount} | ${jar.spiderClasses.size} | " +
                        "${jar.cloudRelatedClasses.size} | ${if (jar.usesOkHttp) "Y" else "-"} | " +
                        "${if (jar.usesGson) "Y" else "-"} | ${if (jar.usesJsEngine) "Y" else "-"} |",
                )
            }
            sb.appendLine()
        }

        // OpenDrive findings
        sb.appendLine("## OpenDrive Robustness Findings")
        sb.appendLine()
        if (report.openDriveFindings.isEmpty()) {
            sb.appendLine("No specific findings for OpenDrive improvement.")
        } else {
            report.openDriveFindings.forEach { finding ->
                sb.appendLine("### [${finding.severity}] ${finding.title}")
                sb.appendLine()
                sb.appendLine("**Category:** ${finding.category}")
                sb.appendLine()
                sb.appendLine(finding.description)
                sb.appendLine()
                sb.appendLine("**Recommendation:** ${finding.recommendation}")
                if (finding.affectedProviders.isNotEmpty()) {
                    sb.appendLine()
                    sb.appendLine("**Affected providers:** ${finding.affectedProviders.joinToString()}")
                }
                sb.appendLine()
            }
        }

        // Actionable recommendations
        sb.appendLine("## Actionable Recommendations for OpenDrive")
        sb.appendLine()
        val highFindings = report.openDriveFindings.filter { it.severity == "HIGH" }
        val mediumFindings = report.openDriveFindings.filter { it.severity == "MEDIUM" }
        val lowFindings = report.openDriveFindings.filter { it.severity == "LOW" }
        val infoFindings = report.openDriveFindings.filter { it.severity == "INFO" }

        if (highFindings.isNotEmpty()) {
            sb.appendLine("### High Priority")
            sb.appendLine()
            highFindings.forEachIndexed { index, finding ->
                sb.appendLine("${index + 1}. **${finding.title}**")
                sb.appendLine("   - ${finding.recommendation}")
            }
            sb.appendLine()
        }

        if (mediumFindings.isNotEmpty()) {
            sb.appendLine("### Medium Priority")
            sb.appendLine()
            mediumFindings.forEachIndexed { index, finding ->
                sb.appendLine("${index + 1}. **${finding.title}**")
                sb.appendLine("   - ${finding.recommendation}")
            }
            sb.appendLine()
        }

        if (lowFindings.isNotEmpty()) {
            sb.appendLine("### Low Priority")
            sb.appendLine()
            lowFindings.forEachIndexed { index, finding ->
                sb.appendLine("${index + 1}. **${finding.title}**")
                sb.appendLine("   - ${finding.recommendation}")
            }
            sb.appendLine()
        }

        if (infoFindings.isNotEmpty()) {
            sb.appendLine("### Informational")
            sb.appendLine()
            infoFindings.forEachIndexed { index, finding ->
                sb.appendLine("${index + 1}. **${finding.title}**")
                sb.appendLine("   - ${finding.recommendation}")
            }
            sb.appendLine()
        }

        val reportFile = outputDir.resolve("plugin_analysis_report.md")
        Files.writeString(reportFile, sb.toString(), StandardCharsets.UTF_8)
        println("Markdown report: $reportFile")
        return reportFile
    }
}
