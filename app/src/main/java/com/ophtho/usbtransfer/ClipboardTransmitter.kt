package com.ophtho.usbtransfer

import android.util.Base64
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket

/**
 * ClipboardTransmitter v4.0
 * Handles text processing, shorthand replacement, and TCP socket transmission.
 * Transport: USB Tethering TCP (replaces Logcat).
 */
object ClipboardTransmitter {

    private const val CHUNK_SIZE = 3_000   // safe for plain text
    private const val B64_CHUNK  = 2_000   // conservative for Base64
    private const val PIPE_ESC   = "\u2016" // ‖ replaces literal |
    private const val CONNECT_TIMEOUT_MS = 2_000

    // PC IP and port — updated at runtime by MainActivity
    @Volatile var pcIp: String = "192.168.42.129"
    @Volatile var pcPort: Int  = 9000

    private val SHORTHAND = listOf(
        "best corrected visual acuity"      to "BCVA",
        "optical coherence tomography"      to "OCT",
        "age-related macular degeneration"  to "AMD",
        "posterior vitreous detachment"     to "PVD",
        "retinal detachment"                to "RD",
        "intraocular pressure"              to "IOP",
        "visual acuity"                     to "VA",
        "ejection fraction"                 to "EF"
    )

    /**
     * Transmit [rawText] to the PC via TCP socket.
     * The socket is opened, used, and closed within a single try-with-resources block.
     * @return Number of CHUNK lines emitted.
     * @throws java.io.IOException on connection/write failure (caller must handle).
     */
    fun send(rawText: String): Int {
        val processed = applyShorthand(rawText)
        val needsB64  = !processed.isAsciiSafe()
        return if (needsB64) sendBase64(processed) else sendPlain(processed)
    }

    /**
     * Attempt a lightweight "ping" to verify PC reachability.
     * @return true if TCP handshake succeeded within timeout.
     */
    fun ping(): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(pcIp, pcPort), CONNECT_TIMEOUT_MS)
                // Connected — send a minimal heartbeat line so the server can ignore it
                val writer = OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8)
                writer.write("PING\n")
                writer.flush()
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ──────────────────────────────────────────────────────────────────────────

    private fun sendPlain(text: String): Int {
        val escaped = text.replace("|", PIPE_ESC)
        val chunks  = escaped.chunked(CHUNK_SIZE)
        val total   = chunks.size
        val sid     = sessionId()

        transmitLines(buildList {
            add("SESSION_START|$sid|CHUNKS=$total|ENC=PLAIN")
            chunks.forEachIndexed { i, chunk -> add("CHUNK|$sid|${i + 1}/$total|$chunk") }
            add("SESSION_END|$sid")
        })

        return total
    }

    private fun sendBase64(text: String): Int {
        val encoded = Base64.encodeToString(
            text.toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP
        )
        val chunks = encoded.chunked(B64_CHUNK)
        val total  = chunks.size
        val sid    = sessionId()

        transmitLines(buildList {
            add("SESSION_START|$sid|CHUNKS=$total|ENC=B64")
            chunks.forEachIndexed { i, chunk -> add("CHUNK|$sid|${i + 1}/$total|$chunk") }
            add("SESSION_END|$sid")
        })

        return total
    }

    /**
     * Open a single TCP connection, write all [lines] as UTF-8, then close.
     * socket.use {} guarantees closure even on exception.
     */
    private fun transmitLines(lines: List<String>) {
        Socket().use { socket ->
            socket.soTimeout = CONNECT_TIMEOUT_MS
            socket.connect(InetSocketAddress(pcIp, pcPort), CONNECT_TIMEOUT_MS)

            val writer = OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8)
            for (line in lines) {
                writer.write(line)
                writer.write("\n")
            }
            writer.flush()
            // socket closed automatically by .use {}
        }
    }

    private fun applyShorthand(text: String): String {
        var result = text
        SHORTHAND.forEach { (long, short) ->
            result = result.replace(long, short, ignoreCase = true)
        }
        return result
    }

    private fun String.isAsciiSafe(): Boolean =
        all { it.code in 32..126 }

    private fun sessionId(): String =
        System.currentTimeMillis().toString(36).uppercase(java.util.Locale.ROOT)
}