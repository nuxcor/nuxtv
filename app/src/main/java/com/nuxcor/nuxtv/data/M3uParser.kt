package com.nuxcor.nuxtv.data

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

    fun parse(text: String): List<M3uEntry> {
        val entries = mutableListOf<M3uEntry>()
        var pendingExtinf: String? = null
        var pendingGroup: String? = null

        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.isEmpty() || line.startsWith("#EXTM3U") -> Unit
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
        return entries
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
