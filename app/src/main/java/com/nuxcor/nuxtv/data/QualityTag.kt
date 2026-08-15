package com.nuxcor.nuxtv.data

/** Extracts an advertised video quality tag from a raw stream name. */
object QualityTag {

    private val fourK = Regex("""(?i)\b(4k|uhd|2160p?)\b""")
    private val fhd = Regex("""(?i)\b(fhd|full\s?hd|1080p?)\b""")
    private val hd = Regex("""(?i)\b(hd|720p?)\b""")
    private val sd = Regex("""(?i)\b(sd|480p?|576p?)\b""")

    fun of(rawName: String): String? = when {
        fourK.containsMatchIn(rawName) -> "4K"
        fhd.containsMatchIn(rawName) -> "FHD"
        hd.containsMatchIn(rawName) -> "HD"
        sd.containsMatchIn(rawName) -> "SD"
        else -> null
    }

    /** Higher = better; unknown quality ranks below SD. */
    fun rank(quality: String?): Int = when (quality) {
        "4K" -> 4
        "FHD" -> 3
        "HD" -> 2
        "SD" -> 1
        else -> 0
    }

    private val allTags = Regex("""(?i)\b(4k|uhd|fhd|full\s?hd|hd|sd|1080p?|720p?|2160p?|480p?|576p?)\b""")

    /** Channel name with quality tokens removed, for duplicate grouping. */
    fun baseName(name: String): String =
        name.replace(allTags, " ").replace(Regex("""\s{2,}"""), " ").trim()

    /**
     * Collapses duplicate channels (same base name) down to the best-quality
     * variant, keeping the original order of first appearance.
     */
    fun mergeBestQuality(channels: List<LiveChannel>): List<LiveChannel> {
        val best = LinkedHashMap<String, LiveChannel>()
        for (channel in channels) {
            val key = baseName(channel.name).lowercase()
            val current = best[key]
            if (current == null || rank(channel.quality) > rank(current.quality)) {
                best[key] = channel
            }
        }
        return best.values.toList()
    }

    /** Label for an actual decoded resolution, e.g. 1920x1080 → "1080p FHD". */
    fun ofResolution(width: Int, height: Int): String? {
        if (height <= 0) return null
        val tier = when {
            height >= 2000 -> "4K"
            height >= 1000 -> "FHD"
            height >= 700 -> "HD"
            else -> "SD"
        }
        return "${height}p $tier"
    }
}
