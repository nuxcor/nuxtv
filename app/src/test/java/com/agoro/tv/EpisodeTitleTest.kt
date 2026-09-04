package com.agoro.tv

import com.agoro.tv.data.EpisodeTitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The titles here are the panel's own, off the series page as it was
 * photographed on the box.
 */
class EpisodeTitleTest {

    /** The defect, exactly as it reached the screen. */
    @Test
    fun `the show name and the address come off`() {
        assertEquals(
            "Did you know Seahorses are fish?",
            EpisodeTitle.clean(
                "Lady in the Lake - S01E01 - Did you know Seahorses are fish?",
                "Lady in the Lake",
            ),
        )
    }

    @Test
    fun `a title that is only the show and its address is left unnamed`() {
        assertNull(EpisodeTitle.clean("Breaking Bad S02E05", "Breaking Bad"))
    }

    @Test
    fun `the address is taken off with no show name to go on`() {
        assertEquals("Pilot", EpisodeTitle.clean("S01E01 - Pilot", null))
        assertEquals("Pilot", EpisodeTitle.clean("1x01 Pilot", null))
        assertEquals("Pilot", EpisodeTitle.clean("Season 1 Episode 1: Pilot", null))
        assertEquals("Pilot", EpisodeTitle.clean("S1 E1. Pilot", null))
    }

    /** Punctuation drifts between the catalogue's name and the episode's. */
    @Test
    fun `the show name is matched through different punctuation`() {
        assertEquals(
            "The Bicameral Mind",
            EpisodeTitle.clean("Marvel's Agents of S.H.I.E.L.D. - The Bicameral Mind",
                "Marvels Agents of SHIELD"),
        )
    }

    /** The name carries a year in the catalogue; the episodes never do. */
    @Test
    fun `a year on the show name does not stop the match`() {
        assertEquals(
            "Homecoming",
            EpisodeTitle.clean("Ransom Canyon - S01E03 - Homecoming", "Ransom Canyon (2025)"),
        )
    }

    /**
     * Photographed on the box 2026-09-04: every row of "Lead Children" read
     * "1. (2026) (PL) - - Episode 1". The catalogue pass had cleaned the
     * country off the SHOW's name and nothing had cleaned the episodes', so
     * the name match stopped at "Lead Children" and handed the row the rest of
     * its own show's name back.
     *
     * The provider named no episode here, so the honest answer is none: the
     * row numbers itself "Episode 1" like the shows whose panels say nothing.
     */
    @Test
    fun `the catalogue's own decoration does not become the episode's name`() {
        assertNull(
            EpisodeTitle.clean(
                "Lead Children (2026) (PL) - S01E01 - Episode 1",
                "Lead Children (2026)",
            ),
        )
    }

    /** The same decoration in front of a title the provider DID supply. */
    @Test
    fun `a real name survives the decoration in front of it`() {
        assertEquals(
            "Homecoming",
            EpisodeTitle.clean(
                "Ransom Canyon (2025) (US) - S01E03 - Homecoming",
                "Ransom Canyon (2025)",
            ),
        )
    }

    /**
     * Both halves of the tail come off the name being compared. 4,035 of this
     * panel's 8,598 series are named this way, and a source the manifest does
     * not cover reaches [EpisodeTitle] with the country still on.
     */
    @Test
    fun `a year and a country on the show name both come off`() {
        assertEquals(
            "Homecoming",
            EpisodeTitle.clean("Ransom Canyon - S01E03 - Homecoming", "Ransom Canyon (2025) (US)"),
        )
    }

    /** An address cut from the MIDDLE leaves two separators against each other. */
    @Test
    fun `separators stranded by a cut-out address collapse`() {
        assertEquals("Homecoming - Extended", EpisodeTitle.clean("Homecoming - S01E03 - Extended", null))
    }

    /** ...and an ellipsis is not two stranded separators. */
    @Test
    fun `an ellipsis in a real title is left alone`() {
        assertEquals("Wait... What?", EpisodeTitle.clean("Wait... What?", null))
        assertEquals("Then - Now", EpisodeTitle.clean("Then - Now", null))
    }

    /** Only at the head: a title that ENDS on the show's name is naming something. */
    @Test
    fun `the show name is not stripped from the middle of a title`() {
        assertEquals(
            "The Trouble with Fargo",
            EpisodeTitle.clean("The Trouble with Fargo", "Fargo"),
        )
    }

    /** A pilot titled after its own show keeps the title rather than going blank. */
    @Test
    fun `a title that is only the show name survives`() {
        assertEquals("Chernobyl", EpisodeTitle.clean("Chernobyl", "Chernobyl"))
    }

    @Test
    fun `a provider that named nothing gets no name`() {
        assertNull(EpisodeTitle.clean("Episode 12", null))
        assertNull(EpisodeTitle.clean("E4", null))
        assertNull(EpisodeTitle.clean("7", null))
        assertNull(EpisodeTitle.clean("  -  ", null))
    }

    /**
     * The stripper is anchored so it cannot bite into a real title. Each of
     * these looks like an address and is not one.
     */
    @Test
    fun `numbers inside a real title are left alone`() {
        assertEquals("1920x1080 and Other Lies", EpisodeTitle.clean("1920x1080 and Other Lies", null))
        assertEquals("Se7en Sinners", EpisodeTitle.clean("Se7en Sinners", null))
        assertEquals("Apollo 13", EpisodeTitle.clean("Apollo 13", null))
        assertEquals("Part 2: The Reckoning", EpisodeTitle.clean("Part 2: The Reckoning", null))
    }

    @Test
    fun `a row numbers itself when the provider named nothing`() {
        assertEquals("Episode 4", EpisodeTitle.numbered("", 4))
        assertEquals("4. Felina", EpisodeTitle.numbered("Felina", 4))
        // An unnumbered episode is not called "0."
        assertEquals("Felina", EpisodeTitle.numbered("Felina", 0))
        assertEquals("Episode", EpisodeTitle.numbered("", 0))
    }

    @Test
    fun `the player subtitle names what it has`() {
        assertEquals("Felina", EpisodeTitle.display("Felina", 4))
        assertEquals("Episode 4", EpisodeTitle.display("", 4))
    }
}
