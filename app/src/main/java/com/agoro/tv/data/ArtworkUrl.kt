package com.agoro.tv.data

/**
 * Repairs the artwork URLs the panel hands out before anything tries to draw
 * them.
 *
 * A catalogue this size is mostly pictures, and the provider's own metadata is
 * where three quarters of the missing and ugly ones come from. Counted on this
 * panel's dumps (28,974 movies, 8,598 series):
 *
 *  * **7,924 movies and 759 series images point at a host that is gone.**
 *    `cmc.exchange-cdn.com:8080` refuses the connection outright — not a 404, a
 *    refusal — so a quarter of the movie library drew nothing at all while the
 *    loader sat waiting. Every one of those paths ends in a TMDB image hash,
 *    which is where the mirror had copied them from, so the original is one
 *    substitution away.
 *  * **2,202 series covers are `w154`** — a 154-pixel thumbnail, asked to fill
 *    a poster card that is a good deal wider than that on a 4K panel. They came
 *    out soft to the point of looking like the wrong image.
 *  * **1,735 are `original`**, which on TMDB means up to 2000px of JPEG per
 *    tile. A rail of those is tens of megabytes of bitmap on a box with 2GB of
 *    RAM to its name.
 *
 * All three are one rewrite: TMDB serves every rung off the same path, so the
 * size segment is ours to choose. The caller says which SHAPE it wants — a
 * poster is 2:3 and a backdrop is 16:9 — because nothing in the URL can tell
 * them apart, and asking for a poster crop of a backdrop is its own wrong
 * picture.
 *
 * Anything this does not recognise is returned exactly as it came. The panel
 * serves plenty of perfectly good artwork from its own hosts and from
 * photo-tmdb.com, and a normaliser that got clever with those would be
 * inventing a problem.
 */
object ArtworkUrl {

    /**
     * The mirror that stopped answering. Matched with or without its port, and
     * as the whole host rather than a substring — a bare `contains` would catch
     * anything that happened to embed the name in a path.
     */
    private val deadHosts = setOf("cmc.exchange-cdn.com", "cmc.exchange-cdn.com:8080")

    /** TMDB's own hosts, the only ones whose size segment means anything. */
    private val tmdbHosts = setOf("image.tmdb.org", "www.themoviedb.org", "themoviedb.org")

    /**
     * The mirror that draws on its posters.
     *
     * Every image this host serves carries a "4K UltraHD" banner across the
     * top and a gold "8K" badge over the artwork — sometimes two of them —
     * and often a service logo the real poster does not have. Eight covers
     * sampled at random out of the 696 it serves on this panel: eight badged.
     * It is not an occasional bad file, it is what the mirror is for.
     *
     * The claims are also not true of the stream behind them. The panel sells
     * the same title at several rungs and hands the top one this artwork
     * whatever it actually is; nothing on the box can play 8K in any case.
     *
     * Marked rather than dropped. A badged poster still shows the viewer what
     * the title is, so it stays on screen until something better is found —
     * a clean copy of the same title on another of the panel's own rungs, or
     * TMDB's. See ui/screens/BorrowedArt.kt and HomeRows.foldVariants.
     *
     * Paths here are the mirror's own (`stalker_portal/screenshots/171/…`),
     * not TMDB hashes, so unlike [deadHosts] there is nothing to recover from
     * the URL itself — the replacement has to come from somewhere else.
     */
    private val doctoredHosts = setOf("photo-tmdb.com", "photo-tmdb.com:8080")

    /** True for artwork that arrives with quality badges painted onto it. */
    fun isDoctored(url: String?): Boolean {
        val raw = url?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        return hostOf(raw)?.lowercase() in doctoredHosts
    }

    /**
     * A TMDB image id: their base-62 hash and an extension, nothing else.
     *
     * Twenty characters minimum, which every real one comfortably clears and
     * no human-named file does. The dead mirror wrote its paths with a doubled
     * slash — `/images/movies//<hash>.jpg` — so the separator is matched
     * rather than assumed.
     */
    private val tmdbHash = Regex("""/+([A-Za-z0-9]{20,}\.(?:jpg|jpeg|png))$""", RegexOption.IGNORE_CASE)

    /** `https://image.tmdb.org/t/p/<size>/<hash>.jpg`, split at the size. */
    private val tmdbPath = Regex("""^(https?://[^/]+)/t/p/([^/]+)/(.+)$""", RegexOption.IGNORE_CASE)

    /**
     * The rung a poster is fetched at. TMDB's 2:3 smart crop, which is the
     * shape every poster card in this app draws.
     */
    private const val POSTER_SIZE = "w600_and_h900_bestv2"

    /** The rung a backdrop is fetched at: wide, and short of `original`. */
    private const val BACKDROP_SIZE = "w1280"

    /**
     * The rung an episode still is fetched at — TMDB's own still width.
     *
     * 16:9 and small, because that is the shape and size a still is ever
     * drawn at: a thumbnail beside an episode's text, never a hero. Sending
     * these through [poster] instead is not a size mistake but a SHAPE one —
     * `w600_and_h900_bestv2` is a 2:3 smart crop, so a 16:9 still came back
     * as its centre column with both sides cut off, and the row then cropped
     * that portrait back to 16:9 to fit. What reached the screen was the
     * middle third of the frame at four times its intended magnification, on
     * every episode of every series. It also cost 122KB a row where this
     * costs 23KB, forty rows to a season, on a box with 2GB of RAM.
     */
    private const val STILL_SIZE = "w300"

    private const val TMDB = "https://image.tmdb.org/t/p"

    /** A poster — 2:3, the shape of a movie or series tile. */
    fun poster(url: String?): String? = repair(url, POSTER_SIZE)

    /** A backdrop — 16:9, the shape of a hero. */
    fun backdrop(url: String?): String? = repair(url, BACKDROP_SIZE)

    /** An episode still — 16:9, and thumbnail-sized. */
    fun still(url: String?): String? = repair(url, STILL_SIZE)

    private fun repair(url: String?, size: String): String? {
        val raw = url?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        // Lowercased once. Hostnames are case-insensitive and panels are not
        // consistent about them; comparing the raw host against one lowercase
        // set and a lowercased host against the other let "CMC.Exchange-CDN.com"
        // through both branches unrepaired.
        val host = hostOf(raw)?.lowercase() ?: return raw
        if (host in deadHosts) {
            // The hash is all that survives a host that is gone. Without one
            // there is nothing to point anywhere, and null is the honest
            // answer: a card with no artwork draws its fallback immediately,
            // where a URL that will never resolve leaves it blank behind a
            // loader that never finishes.
            val hash = tmdbHash.find(raw)?.groupValues?.get(1) ?: return null
            return "$TMDB/$size/$hash"
        }
        if (host in tmdbHosts) {
            val m = tmdbPath.find(raw) ?: return raw
            val (_, had, rest) = m.destructured
            return if (had.equals(size, ignoreCase = true)) raw else "$TMDB/$size/$rest"
        }
        return raw
    }

    private fun hostOf(url: String): String? {
        val after = url.substringAfter("://", "")
        if (after.isEmpty()) return null
        return after.substringBefore('/').substringBefore('?').takeIf { it.isNotEmpty() }
    }
}
