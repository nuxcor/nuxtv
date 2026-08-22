package com.agoro.tv.data

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * What a failure says on a television.
 *
 * The app writes its own messages for the failures it understands ("Login
 * failed — check username and password") and those pass through. The rest
 * were raw exception text — `Unable to resolve host "srv.example.com": No
 * address associated with hostname`, `Failed to connect to /1.2.3.4:8080`,
 * a bare `timeout` — which is a stack trace with the stack removed, not a
 * sentence. Each network failure class gets the one sentence a viewer can
 * act on; the diagnostic still reaches logcat where it is written.
 */
fun Throwable.userMessage(fallback: String = "Something went wrong"): String = when (this) {
    is UnknownHostException -> "Can't reach the server — check the address and your network"
    is ConnectException -> "Can't connect to the server — check the address and port"
    is SocketTimeoutException -> "The server took too long to respond — try again"
    is SSLException -> "Secure connection failed — check the server address"
    is IOException -> message?.takeIf { it.isNotBlank() && !it.looksRaw() } ?: fallback
    else -> message?.takeIf { it.isNotBlank() && !it.looksRaw() } ?: fallback
}

/** Exception text that was never meant for a screen. */
private fun String.looksRaw(): Boolean =
    contains("Exception") || contains("://") || startsWith("Failed to connect") ||
        contains("resolve host") || equals("timeout", ignoreCase = true) ||
        contains("Connection reset") || contains("Broken pipe")
