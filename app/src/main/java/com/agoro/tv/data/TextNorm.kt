package com.agoro.tv.data

/**
 * Unicode normalization for names nobody typed by hand.
 *
 * Providers decorate stream names with modifier letters and superscript
 * digits — "ESPN ᵁᴴᴰ ³⁸⁴⁰ᴾ", "YAHOO SPORTS NETWORK ᴿᴬᵂ ⁶⁰ᶠᵖˢ". Unicode
 * classifies those glyphs as LETTERS (category Lm) and NUMBERS (category
 * No), so every ASCII-anchored cleanup rule in this package walked straight
 * past them and `Char.isLetterOrDigit` waved them into identity keys:
 * "ESPN ᴴᴰ", "ESPN ᵁᴴᴰ ³⁸⁴⁰ᴾ" and "ESPN HD" were three channels with three
 * keys, three guide lookups and no merge between them.
 *
 * NFD — what [EpgMatcher.fold] used to normalize with — only decomposes
 * accents. These glyphs carry COMPATIBILITY decompositions, so NFKD is what
 * folds them back into "UHD 3840P" and "RAW 60fps" where the existing
 * regexes can finally see them.
 */
object TextNorm {

    /**
     * The decoration blocks, deliberately narrow rather than all of Lm/No:
     * ²³¹, modifier small letters, the phonetic-extension capitals, and the
     * superscript block. Japanese prolonged-sound ー (U+30FC) and
     * the modifier apostrophe ʼ (U+02BC, "Hawaiʻi") are Lm too and belong to
     * real names, so a blanket `\p{Lm}` would quietly mangle them — hence
     * the two split ranges through the modifier block rather than one.
     */
    // Ends at U+207F, not U+209F: the block continues into SUBSCRIPTS, which
    // are not provider decoration. "H₂O TV" came out as "H O TV".
    private const val DECOR = "²³¹ʰ-ʸˠ-ˤᴬ-ᶿ\u2070-\u207F"

    /** A run of decoration, plus the spaces between adjacent runs. */
    private val decorRun = Regex("""[$DECOR]+(?:\s+[$DECOR]+)*""")

    private val multiSpace = Regex("""\s{2,}""")

    /**
     * Drops decorative superscript runs outright: "ESPN ᵁᴴᴰ ³⁸⁴⁰ᴾ" → "ESPN",
     * "YAHOO SPORTS NETWORK ᴿᴬᵂ" → "YAHOO SPORTS NETWORK".
     *
     * Dropping the run beats decomposing it and then matching "raw"/"60fps"
     * as words: the tokens only ever mean decoration when they arrive as
     * superscript, and a channel genuinely called "WWE RAW" writes it in
     * plain ASCII. Quality still survives the drop — [QualityTag.of] reads
     * the tier off [compat] before this runs.
     */
    fun stripDecoration(text: String): String {
        if (isPlain(text)) return text
        return text.replace(decorRun, " ").replace(multiSpace, " ").trim()
    }

    /**
     * NFKD with case preserved, for the one caller that must READ the
     * decoration rather than drop it.
     */
    fun compat(text: String): String {
        if (isPlain(text)) return text
        return java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKD)
    }

    /**
     * Cheap guard for the overwhelmingly common case. This runs per channel
     * across playlists in the thousands; without it every plain-ASCII name
     * pays for a Normalizer pass or a regex scan that can only hand it back
     * unchanged.
     */
    private fun isPlain(text: String): Boolean {
        for (c in text) if (c.code >= 0x80) return false
        return true
    }
}
