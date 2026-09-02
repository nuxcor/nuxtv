package com.agoro.tv.data

/**
 * Moving this provider's traffic onto TLS.
 *
 * Xtream puts the credentials IN THE URL — the query string for
 * `player_api.php`, and the PATH for every stream, `/live/user/pass/id.ts`.
 * Over plain http that is the username and password in clear to everyone
 * between the box and the panel, re-sent on every channel change. The
 * credentials are the serious part: this line allows one connection, so
 * anyone who reads them off the wire can lock the viewer out of their own
 * service.
 *
 * The app defaulted a bare host to `http://` ([XtreamClient.normalize]) and
 * every install since has been in the clear. This panel has answered over TLS
 * the whole time — a valid certificate, and `player_api.php` and `/live/`
 * both responding on 443, checked 2026-09-02.
 *
 * What TLS fixes: the credentials, the channel, the content. What it does not
 * fix: the hostname, which is still in the DNS lookup and the TLS handshake,
 * so the ISP still knows which provider this is. Only a VPN hides that, and
 * it moves the trust rather than removing it.
 *
 * Pure, and separate from the probe that uses it, so the string handling can
 * be tested without a network.
 */

/** The https form of an http url, or null when there is nothing to upgrade. */
fun httpsCandidate(url: String): String? =
    if (url.startsWith("http://", ignoreCase = true)) {
        "https://" + url.substring("http://".length)
    } else {
        null
    }

/**
 * The http form of an https url, or null when it is already plain.
 *
 * The player's last rung. A stream that fails on TLS mid-session — a
 * certificate that expired an hour ago, a middlebox — should cost the viewer
 * their privacy, not their picture; the alternative is a black screen on a
 * feed that would play.
 */
fun httpFallback(url: String): String? =
    if (url.startsWith("https://", ignoreCase = true)) {
        "http://" + url.substring("https://".length)
    } else {
        null
    }
