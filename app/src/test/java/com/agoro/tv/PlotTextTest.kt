package com.agoro.tv

import com.agoro.tv.data.PlotText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The synopses here are copied out of the panel's own `get_series` dump,
 * where 297 of 8,598 series carry both languages in the one field.
 */
class PlotTextTest {

    private val english =
        "A dangerously charming, intensely obsessive young man goes to extreme " +
            "measures to insert himself into the lives of those he is transfixed by."
    private val arabic =
        "يتخذ شاب وسيم وجذاب يعمل في مكتبة لأسلوبه الخاص في الحياة، من أجل اقتحام حياة اولئك الذين يقع في حبهم"

    @Test
    fun `the translation is dropped and the English kept`() {
        assertEquals(english, PlotText.preferred("$english\r\n$arabic"))
    }

    /** The same field on the movie catalogue is not consistent about the order. */
    @Test
    fun `the English is kept when it comes second`() {
        assertEquals(english, PlotText.preferred("$arabic\r\n$english"))
    }

    /** The sentence keeps its own full stop; only the join is trimmed away. */
    @Test
    fun `the kept half is not trimmed into`() {
        val one = "When the disappearance of a young girl grips the city of Baltimore."
        assertEquals(one, PlotText.preferred("$one \r\n — $arabic"))
    }

    /**
     * A synopsis written only in Arabic is what the provider has for that
     * title. Shown as it came — the rule is "don't print it twice", not
     * "don't print Arabic".
     */
    @Test
    fun `a synopsis in one language is untouched`() {
        assertEquals(arabic, PlotText.preferred(arabic))
        assertEquals(english, PlotText.preferred(english))
    }

    @Test
    fun `nothing in, nothing out`() {
        assertNull(PlotText.preferred(null))
        assertEquals("", PlotText.preferred(""))
    }

    /**
     * A fragment is not a synopsis. Cutting here would lose more than the
     * duplication costs, so the text is left whole.
     */
    @Test
    fun `a short Latin fragment does not become the whole synopsis`() {
        val raw = "(US)\r\n$arabic"
        assertEquals(raw, PlotText.preferred(raw))
    }
}
