package com.agoro.tv

import com.agoro.tv.data.ArtworkUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Every URL here is copied from the provider's own dumps. The counts in the
 * comments are off those same dumps: 28,974 movies and 8,598 series.
 */
class ArtworkUrlTest {

    private val tmdb = "https://image.tmdb.org/t/p"

    /**
     * 7,924 movies — better than a quarter of the library — point at a mirror
     * that refuses the connection. The path it copied from TMDB is still on it.
     */
    @Test
    fun `the dead mirror is redirected to TMDB`() {
        assertEquals(
            "$tmdb/w600_and_h900_bestv2/tF0dhJfxzOjvkybJVfNPuuj5ClH.jpg",
            ArtworkUrl.poster(
                "http://cmc.exchange-cdn.com:8080/images/movies//tF0dhJfxzOjvkybJVfNPuuj5ClH.jpg"
            ),
        )
    }

    /** The same host without its port, which is how the series covers write it. */
    @Test
    fun `the dead mirror is recognised without its port`() {
        assertEquals(
            "$tmdb/w1280/nu6dcBfxr4VmOBj4k1S9r0r1MOW.jpg",
            ArtworkUrl.backdrop(
                "http://cmc.exchange-cdn.com/images/series/nu6dcBfxr4VmOBj4k1S9r0r1MOW.jpg"
            ),
        )
    }

    /**
     * A dead host with nothing recoverable on it is null, not a URL that will
     * never answer: a card with no artwork draws its fallback at once, where a
     * pending load leaves a hole behind a spinner.
     */
    @Test
    fun `a dead host with no recoverable id gives nothing`() {
        assertNull(ArtworkUrl.poster("http://cmc.exchange-cdn.com/images/movies/poster.jpg"))
    }

    /** 2,202 series covers arrive as 154-pixel thumbnails. */
    @Test
    fun `a thumbnail rung is asked for at poster size`() {
        assertEquals(
            "$tmdb/w600_and_h900_bestv2/8lI1p5cPqgXN2qrKZrmI3mhKBfs.jpg",
            ArtworkUrl.poster("https://image.tmdb.org/t/p/w154/8lI1p5cPqgXN2qrKZrmI3mhKBfs.jpg"),
        )
    }

    /** 1,735 are `original`, which is up to 2000px of JPEG on a 2GB box. */
    @Test
    fun `original is asked for at the rung the screen needs`() {
        assertEquals(
            "$tmdb/w1280/5f8eR8Oby9F5V7n3gmSkkaBCSq1.jpg",
            ArtworkUrl.backdrop("https://image.tmdb.org/t/p/original/5f8eR8Oby9F5V7n3gmSkkaBCSq1.jpg"),
        )
        assertEquals(
            "$tmdb/w600_and_h900_bestv2/5f8eR8Oby9F5V7n3gmSkkaBCSq1.jpg",
            ArtworkUrl.poster("https://image.tmdb.org/t/p/original/5f8eR8Oby9F5V7n3gmSkkaBCSq1.jpg"),
        )
    }

    /**
     * A poster asked for as a poster is left exactly as it is — no rewrite, so
     * the URL the loader already has cached stays the one it fetches.
     */
    @Test
    fun `the rung already asked for is left alone`() {
        val url = "$tmdb/w600_and_h900_bestv2/ek8e8txUyUwd2BNqj6lFEerJfbq.jpg"
        assertEquals(url, ArtworkUrl.poster(url))
    }

    /** The panel's own working hosts are none of this code's business. */
    @Test
    fun `other hosts pass through untouched`() {
        val stalker = "https://photo-tmdb.com/stalker_portal/screenshots/389/38839.jpg"
        assertEquals(stalker, ArtworkUrl.poster(stalker))
        val picon = "http://picons.cmshulk.com/logos/sky-sports-main-event.png"
        assertEquals(picon, ArtworkUrl.poster(picon))
    }

    /** Hostnames are case-insensitive and panels are not consistent about them. */
    @Test
    fun `the dead mirror is recognised whatever its case`() {
        assertEquals(
            "$tmdb/w600_and_h900_bestv2/tF0dhJfxzOjvkybJVfNPuuj5ClH.jpg",
            ArtworkUrl.poster(
                "http://CMC.Exchange-CDN.com:8080/images/movies//tF0dhJfxzOjvkybJVfNPuuj5ClH.jpg"
            ),
        )
        assertEquals(
            "$tmdb/w1280/5f8eR8Oby9F5V7n3gmSkkaBCSq1.jpg",
            ArtworkUrl.backdrop("https://Image.TMDB.org/t/p/original/5f8eR8Oby9F5V7n3gmSkkaBCSq1.jpg"),
        )
    }

    @Test
    fun `blank and absent give nothing`() {
        assertNull(ArtworkUrl.poster(null))
        assertNull(ArtworkUrl.poster("   "))
    }

    /** A relative or malformed value is handed back rather than mangled. */
    @Test
    fun `something that is not a URL is left as it is`() {
        assertEquals("images/local.png", ArtworkUrl.poster("images/local.png"))
    }

    /**
     * An episode still is 16:9, and the poster rung is a 2:3 SMART CROP —
     * TMDB returns the centre column with both sides cut off, and the row
     * then crops that portrait back to 16:9 to fit its own box. What reached
     * the screen was the middle third of the frame at four times its
     * intended magnification, on every episode of every series.
     */
    @Test
    fun `an episode still is fetched at the still rung, not the poster one`() {
        assertEquals(
            "$tmdb/w300/5f8eR8Oby9F5V7n3gmSkkaBCSq1.jpg",
            ArtworkUrl.still("$tmdb/original/5f8eR8Oby9F5V7n3gmSkkaBCSq1.jpg"),
        )
        assertEquals(
            "$tmdb/w300/5f8eR8Oby9F5V7n3gmSkkaBCSq1.jpg",
            ArtworkUrl.still("$tmdb/w600_and_h900_bestv2/5f8eR8Oby9F5V7n3gmSkkaBCSq1.jpg"),
        )
    }

    /** The dead mirror serves episode stills too. */
    @Test
    fun `a still on the dead mirror is recovered at the still rung`() {
        assertEquals(
            "$tmdb/w300/nu6dcBfxr4VmOBj4k1S9r0r1MOW.jpg",
            ArtworkUrl.still(
                "http://cmc.exchange-cdn.com:8080/images/series//nu6dcBfxr4VmOBj4k1S9r0r1MOW.jpg"
            ),
        )
    }
}
