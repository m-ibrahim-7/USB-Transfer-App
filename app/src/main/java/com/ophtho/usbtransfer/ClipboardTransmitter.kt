package com.ophtho.usbtransfer

import android.util.Base64
import android.util.Log

/**
 * ClipboardTransmitter v3.1
 * Handles text processing, shorthand replacement, and chunked Logcat transmission.
 */
object ClipboardTransmitter {

    private const val TAG        = "CLINIC_DATA"
    private const val CHUNK_SIZE = 3_000   // safe for plain text
    private const val B64_CHUNK  = 2_000   // conservative for Base64
    private const val PIPE_ESC   = "\u2016" // ‖ replaces literal |

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
     * Transmit [rawText] over Logcat.
     * @return Number of CHUNK lines emitted.
     */
    fun send(rawText: String): Int {
        val processed = applyShorthand(rawText)
        val needsB64  = !processed.isAsciiSafe()

        return if (needsB64) sendBase64(processed) else sendPlain(processed)
    }

    private fun sendPlain(text: String): Int {
        val escaped = text.replace("|", PIPE_ESC)
        val chunks  = escaped.chunked(CHUNK_SIZE)
        val total   = chunks.size
        val sid     = sessionId()

        Log.d(TAG, "SESSION_START|$sid|CHUNKS=$total|ENC=PLAIN")
        chunks.forEachIndexed { i, chunk ->
            Log.d(TAG, "CHUNK|$sid|${i + 1}/$total|$chunk")
        }
        Log.d(TAG, "SESSION_END|$sid")

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

        Log.d(TAG, "SESSION_START|$sid|CHUNKS=$total|ENC=B64")
        chunks.forEachIndexed { i, chunk ->
            Log.d(TAG, "CHUNK|$sid|${i + 1}/$total|$chunk")
        }
        Log.d(TAG, "SESSION_END|$sid")

        return total
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