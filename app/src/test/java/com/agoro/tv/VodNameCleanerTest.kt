package com.agoro.tv

import com.agoro.tv.data.CatalogueManifest
import com.agoro.tv.data.ManifestCuration
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The provider prefixes every VOD title with the bundle it shipped in —
 * "4K-NF - ", "EN - ", "DSC+ - ". 8,584 of its 8,598 series carry one. The
 * rules live in the manifest so they change without a release, which also
 * means nothing in the app pins their shape; this does.
 */
class VodNameCleanerTest {

    // The rules as the shipped manifest carries them.
    private val cleaner = ManifestCuration.VodNameCleaner(
        CatalogueManifest.VodNameRules(
            stripPrefix = """^[A-Z0-9+]{1,5}\s*-\s*""",
            repeat = 3,
            stripQuality = """\b(4K|8K|UHD|FHD|HD|SD|HEVC|H265|3840P|2160P|1080P|720P|MULTI|MULTISUB|SUBS?|VIP|DUAL|DUBBED)\b""",
            stripCountry = """\s*\((?:[A-Z]{2})\)\s*$""",
        )
    )

    @Test
    fun `the bundle prefix comes off, including the stacked form`() {
        // "4K-NF - " is two prefixes, which is what strip_prefix_repeat is for.
        assertEquals("Mating Season (2026)", cleaner.clean("4K-NF - Mating Season (2026) (US)"))
        assertEquals("Ransom Canyon (2025)", cleaner.clean("NF - Ransom Canyon (2025) (US)"))
        assertEquals("Thing (2025)", cleaner.clean("4K-AMZ - Thing (2025) (US)"))
    }

    @Test
    fun `prefixes carrying a plus survive the character class`() {
        // D+, A+, P+, DSC+ are real bundles on this panel; a class that missed
        // '+' would leave the whole prefix standing.
        assertEquals("Some Show (2024)", cleaner.clean("DSC+ - Some Show (2024) (GB)"))
        assertEquals("Show (2022)", cleaner.clean("A+ - Show (2022) (US)"))
    }

    @Test
    fun `an episode title loses the prefix and keeps its own name`() {
        // The case episodes actually arrive in: the prefix leads, the episode's
        // own name follows, and only the prefix is noise.
        assertEquals("S01E01 - Pilot", cleaner.clean("4K-NF - S01E01 - Pilot"))
        assertEquals("Episode 3", cleaner.clean("EN - Episode 3"))
    }

    @Test
    fun `a title with no prefix is left exactly as it is`() {
        assertEquals("Ransom Canyon (2025)", cleaner.clean("Ransom Canyon (2025)"))
        assertEquals("Pilot", cleaner.clean("Pilot"))
    }

    @Test
    fun `a lowercase leader is not mistaken for a bundle code`() {
        // strip_prefix is upper-case only on purpose: "abc - def" is a title
        // with a dash in it, not a bundle called abc.
        assertEquals("abc - def", cleaner.clean("abc - def"))
    }
}
