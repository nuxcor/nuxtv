package com.agoro.tv.data

data class M3uEntry(
    val title: String,
    val url: String,
    val attrs: Map<String, String>,
) {
    val group: String? get() = attrs["group-title"]?.takeIf { it.isNotBlank() }
    val logo: String? get() = attrs["tvg-logo"]?.takeIf { it.isNotBlank() }
    val tvgId: String? get() = attrs["tvg-id"]?.takeIf { it.isNotBlank() }
    val tvgName: String? get() = attrs["tvg-name"]?.takeIf { it.isNotBlank() }
}

data class M3uResult(
    val entries: List<M3uEntry>,
    val tvgUrl: String?,
    val sawHeader: Boolean,
)

object M3uParser {

    private val attrRegex = Regex("""([\w.-]+)="([^"]*)"""")

    /** XMLTV guide URL advertised in the playlist header (url-tvg / x-tvg-url). */
    fun tvgUrl(text: String): String? {
        val header = text.lineSequence().firstOrNull { it.trimStart().startsWith("#EXTM3U") } ?: return null
        val attrs = attrRegex.findAll(header)
            .associate { it.groupValues[1].lowercase() to it.groupValues[2] }
        return (attrs["url-tvg"] ?: attrs["x-tvg-url"])
            ?.split(",", " ")?.firstOrNull { it.isNotBlank() }?.trim()
    }

    fun parse(text: String): List<M3uEntry> = parseLines(text.lineSequence()).entries

    /** Streaming variant: never needs the whole playlist in memory at once. */
    fun parseLines(lines: Sequence<String>): M3uResult {
        val entries = mutableListOf<M3uEntry>()
        var pendingExtinf: String? = null
        var pendingGroup: String? = null
        var tvg: String? = null
        var sawHeader = false

        for (raw in lines) {
            val line = raw.trim().trimStart('\uFEFF').trim()
            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXTM3U") -> {
                    sawHeader = true
                    if (tvg == null) {
                        val attrs = attrRegex.findAll(line)
                            .associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                        tvg = (attrs["url-tvg"] ?: attrs["x-tvg-url"])
                            ?.split(",", " ")?.firstOrNull { it.isNotBlank() }?.trim()
                    }
                }
                line.startsWith("#EXTINF", ignoreCase = true) -> pendingExtinf = line
                line.startsWith("#EXTGRP:", ignoreCase = true) ->
                    pendingGroup = line.substringAfter(":").trim().takeIf { it.isNotBlank() }
                line.startsWith("#") -> Unit
                else -> {
                    val extinf = pendingExtinf
                    if (extinf != null) {
                        val attrs = attrRegex.findAll(extinf)
                            .associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                            .toMutableMap()
                        if (pendingGroup != null && attrs["group-title"].isNullOrBlank()) {
                            attrs["group-title"] = pendingGroup
                        }
                        val title = titleOf(extinf).ifBlank { attrs["tvg-name"] ?: "Unknown" }
                        entries += M3uEntry(title = title, url = line, attrs = attrs)
                    }
                    pendingExtinf = null
                }
            }
        }
        return M3uResult(entries = entries, tvgUrl = tvg, sawHeader = sawHeader)
    }

    /** The display title is everything after the first comma that isn't inside quotes. */
    private fun titleOf(extinfLine: String): String {
        var inQuotes = false
        for (i in extinfLine.indices) {
            when (extinfLine[i]) {
                '"' -> inQuotes = !inQuotes
                ',' -> if (!inQuotes) return extinfLine.substring(i + 1).trim()
            }
        }
        return ""
    }
}
