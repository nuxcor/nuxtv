package com.agoro.tv.data

import android.os.Handler
import android.os.Looper
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import kotlin.concurrent.thread

/**
 * Phone-assisted sign-in: a deliberately tiny HTTP server the TV runs only
 * while the onboarding chooser is on screen. The TV shows a QR of the URL;
 * the phone opens a dark-styled form, the viewer types the provider login on
 * a real keyboard, and the submitted credentials land back here.
 *
 * Scope and safety, in order of importance:
 * - LAN only, ephemeral port, and the path carries a random token — a request
 *   without it gets 404. Nothing is ever sent off-device by this class.
 * - Runs on a daemon thread; [stop] closes the socket and the accept loop
 *   dies with it. The chooser's DisposableEffect owns that lifecycle.
 * - No TLS: this is a same-room, same-network, seconds-long exchange, the
 *   same trade every TV "activate on your phone" flow makes.
 */
class PairingServer(
    /**
     * The server a provider-branded build already knows (PROVIDER_HOST), or
     * null. Non-null drops the URL field from the phone form the same way the
     * TV's own form drops it: the viewer signs in with a username and
     * password and is never asked for an address they have no way to know.
     */
    private val defaultServer: String? = null,
    private val onSubmit: (name: String, server: String, username: String, password: String) -> Unit,
) {
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var running = false
    private val token = (100_000..999_999).random().toString()

    // Lazy so the class constructs on the JVM: unit tests exercise the form
    // HTML, and an eager Handler needs an Android Looper they don't have.
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    /** The URL to encode in the QR, or null when there is no LAN address. */
    var url: String? = null
        private set

    fun start(): String? {
        if (running) return url
        val ip = localIpv4() ?: return null
        val socket = runCatching { ServerSocket(0) }.getOrNull() ?: return null
        serverSocket = socket
        running = true
        url = "http://${ip}:${socket.localPort}/t/$token"
        thread(isDaemon = true, name = "pairing-server") {
            while (running) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                runCatching { handle(client) }
                runCatching { client.close() }
            }
        }
        return url
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    private fun handle(client: Socket) {
        client.soTimeout = 5_000
        val input = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
        val requestLine = input.readLine() ?: return
        val parts = requestLine.split(" ")
        if (parts.size < 2) return
        val (method, path) = parts[0] to parts[1]

        var contentLength = 0
        while (true) {
            val header = input.readLine() ?: break
            if (header.isBlank()) break
            if (header.startsWith("Content-Length:", ignoreCase = true)) {
                contentLength = header.substringAfter(':').trim().toIntOrNull() ?: 0
            }
        }

        val out = client.getOutputStream()
        fun respond(status: String, body: String) {
            val bytes = body.toByteArray(Charsets.UTF_8)
            out.write(
                ("HTTP/1.1 $status\r\n" +
                    "Content-Type: text/html; charset=utf-8\r\n" +
                    "Content-Length: ${bytes.size}\r\n" +
                    "Connection: close\r\n\r\n").toByteArray(Charsets.UTF_8)
            )
            out.write(bytes)
            out.flush()
        }

        if (!path.startsWith("/t/$token")) {
            respond("404 Not Found", "<html><body>Not found</body></html>")
            return
        }
        when (method) {
            "GET" -> respond("200 OK", formHtml())
            "POST" -> {
                // Cap what we'll read: credentials, not uploads.
                val size = contentLength.coerceIn(0, 16_384)
                val buffer = CharArray(size)
                var read = 0
                while (read < size) {
                    val n = input.read(buffer, read, size - read)
                    if (n <= 0) break
                    read += n
                }
                val fields = String(buffer, 0, read)
                    .split('&')
                    .mapNotNull { pair ->
                        val idx = pair.indexOf('=')
                        if (idx <= 0) return@mapNotNull null
                        pair.take(idx) to URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
                    }
                    .toMap()
                val server = fields["server"]?.trim().orEmpty()
                    .ifBlank { defaultServer.orEmpty() }
                val username = fields["username"]?.trim().orEmpty()
                val password = fields["password"].orEmpty()
                if (server.isBlank() || username.isBlank() || password.isBlank()) {
                    respond(
                        "200 OK",
                        formHtml(
                            error = if (defaultServer.isNullOrBlank()) {
                                "All three fields are required."
                            } else {
                                "Both fields are required."
                            }
                        ),
                    )
                } else {
                    // Name left blank on purpose, matching the TV's own form:
                    // it was an optional field nobody filled in, and the app
                    // names the playlist for itself.
                    mainHandler.post { onSubmit("", server, username, password) }
                    respond("200 OK", sentHtml())
                }
            }
            else -> respond("405 Method Not Allowed", "<html><body>Not allowed</body></html>")
        }
    }

    private fun localIpv4(): String? =
        runCatching {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.asSequence() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }
                ?.hostAddress
        }.getOrNull()

    // The phone-side page: dark, thumb-sized inputs, zero dependencies.
    // Internal so tests can hold the page to the same rule as the TV form:
    // a branded build never asks for a URL the viewer has no way to know.
    internal fun formHtml(error: String? = null) = """
        <!doctype html><html><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <title>Sign in to Agorɔ</title><style>
        body{background:#0B0A09;color:#EEEEED;font-family:-apple-system,Roboto,sans-serif;
             margin:0;padding:28px 20px;display:flex;justify-content:center}
        .card{width:100%;max-width:420px}
        h1{font-size:22px;margin:0 0 4px;color:#D99A2E}
        p{color:#B4B1AB;font-size:14px;margin:0 0 20px}
        label{display:block;font-size:13px;color:#B4B1AB;margin:14px 0 6px}
        input{width:100%;box-sizing:border-box;background:#1B1917;color:#EEEEED;
              border:1px solid #3C3A35;border-radius:10px;padding:13px 14px;font-size:16px}
        input:focus{outline:none;border-color:#D99A2E}
        button{width:100%;margin-top:22px;background:#D99A2E;color:#1E1503;border:none;
               border-radius:10px;padding:14px;font-size:16px;font-weight:600}
        .err{color:#FF6B6B;font-size:14px;margin:12px 0 0}
        </style></head><body><div class="card">
        <h1>Agorɔ</h1><p>Enter the login your IPTV provider gave you.</p>
        <form method="post">
        ${
        if (defaultServer.isNullOrBlank()) """
        <label>Server URL</label>
        <input name="server" autocomplete="off" autocapitalize="none" inputmode="url"
               placeholder="http://example.com:8080" required>""" else ""
        }
        <label>Username</label>
        <input name="username" autocomplete="username" autocapitalize="none" required>
        <label>Password</label>
        <input name="password" type="password" autocomplete="current-password" required>
        ${error?.let { "<div class=\"err\">$it</div>" } ?: ""}
        <button type="submit">Send to TV</button>
        </form></div></body></html>
    """.trimIndent()

    private fun sentHtml() = """
        <!doctype html><html><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <title>Sent</title><style>
        body{background:#0B0A09;color:#EEEEED;font-family:-apple-system,Roboto,sans-serif;
             margin:0;padding:60px 24px;text-align:center}
        h1{color:#D99A2E;font-size:24px}p{color:#B4B1AB;font-size:15px}
        a{color:#D99A2E}
        </style></head><body>
        <h1>Sent to your TV</h1>
        <p>Look at the TV screen — it's connecting now.</p>
        <p><a href="javascript:history.back()">&#8592; Change the details</a></p>
        </body></html>
    """.trimIndent()
}
