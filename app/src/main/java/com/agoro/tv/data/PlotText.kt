package com.agoro.tv.data

/**
 * Picks one language out of a synopsis the panel wrote in two.
 *
 * 297 of this panel's 8,598 series ship the English overview and its Arabic
 * translation in one `plot` field, separated by a CRLF — the provider serves
 * both audiences from one catalogue and leaves the client to choose. Drawn
 * whole, the second copy is text nobody on this box reads taking the room the
 * first copy needed: on the series page it filled four of the synopsis's five
 * lines and pushed the cast, the director and the Play button down the page.
 *
 * Only ever a choice between what is already there. Nothing is translated,
 * summarised or reordered, and a synopsis written in one language — including
 * one written only in Arabic — is returned exactly as it came. The rule is
 * "don't print the same thing twice", not "don't print Arabic".
 */
object PlotText {

    /**
     * A letter in a script this UI is not written in. Arabic is the one this
     * panel actually sends; Hebrew, Cyrillic, Greek, CJK and Devanagari are
     * here because the same providers resell each other's catalogues and the
     * cost of listing them is nothing.
     */
    private val foreignLetter = Regex(
        "[\\u0400-\\u04FF\\u0370-\\u03FF\\u0590-\\u05FF\\u0600-\\u06FF\\u0750-\\u077F" +
            "\\u0900-\\u097F\\u3040-\\u30FF\\u4E00-\\u9FFF\\uAC00-\\uD7AF]"
    )

    private val latinLetter = Regex("[A-Za-z]")

    /**
     * Below this, a run is a fragment rather than a synopsis — a stray
     * "(US)", a credit, the tail of a sentence — and cutting the text there
     * would lose more than the duplication costs.
     */
    private const val ENOUGH_LETTERS = 25

    /**
     * The half of [raw] this UI can read, or [raw] unchanged.
     *
     * Both orders are handled: the panel writes English first, but the same
     * field on its movie catalogue is not consistent about it, and a rule
     * that only ever kept the head would have thrown away the English half of
     * the ones written the other way round.
     */
    fun preferred(raw: String?): String? {
        val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return raw
        val first = foreignLetter.find(text) ?: return text
        // Nothing to choose between: the whole synopsis is in one script, and
        // it is not this one. Shown as it came — an Arabic plot is what the
        // provider has for that title, and no plot at all is worse.
        if (latinLetter.findAll(text).count() < ENOUGH_LETTERS) return text

        val head = text.substring(0, first.range.first).trim(*EDGES)
        if (latinLetter.findAll(head).count() >= ENOUGH_LETTERS) return head

        val last = foreignLetter.findAll(text).last()
        val tail = text.substring(last.range.last + 1).trim(*EDGES)
        if (latinLetter.findAll(tail).count() >= ENOUGH_LETTERS) return tail

        // Interleaved, or split somewhere neither end recovers from. Left
        // whole: a synopsis cut in the middle of its own sentence reads as a
        // rendering fault, which is worse than one that is merely too long.
        return text
    }

    /**
     * Whitespace and the punctuation a dropped half leaves stranded.
     *
     * No full stop: the English run ends on one, and trimming it would take
     * the sentence's own punctuation off every synopsis this touches.
     */
    private val EDGES = charArrayOf(
        ' ', '\t', '\n', '\r', '-', '–', '—', ':', '|', '/',
    )
}
