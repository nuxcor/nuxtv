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
