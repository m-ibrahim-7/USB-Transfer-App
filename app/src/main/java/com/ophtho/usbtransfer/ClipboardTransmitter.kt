package com.ophtho.usbtransfer

import android.util.Base64
import android.util.Log

/**
 * ClipboardTransmitter  v3
 * ══════════════════════════════════════════════════════════════════════════════
 *
 * IMPROVEMENTS OVER v2 (addressing the second-opinion critique)
 * ──────────────────────────────────────────────────────────────
 *
 * 1. SMART ENCODING — no unnecessary Base64 bloat
 *    v2 Base64-encoded the entire string unconditionally, adding 33% overhead.
 *    v3 checks first: if the text is pure ASCII (the vast majority of clinical
 *    notes in English), it sends it as plain UTF-8.  Only if non-ASCII chars
 *    are present (Arabic patient names, accented letters, special symbols) does
 *    it fall back to Base64.  The PC side detects the encoding mode from the
 *    SESSION_START line.
 *
 * 2. SAFE PIPE ESCAPING (plain-text path)
 *    The protocol delimiter is "|".  In plain-text mode we escape any literal
 *    "|" in the content as "‖" (U+2016 DOUBLE VERTICAL LINE) — a character
 *    that never appears in clinical notes — then unescape on the PC side.
 *
 * 3. RETURNS CHUNK COUNT
 *    send() returns the number of chunks emitted so the caller can display it.
 *
 * Protocol (v3)
 * ─────────────
 *   SESSION_START | <sid> | CHUNKS=<n> | ENC=<PLAIN|B64>
 *   CHUNK         | <sid> | <i>/<n>    | <payload>
 *   SESSION_END   | <sid>
 */
object ClipboardTransmitter {

    private const val TAG        = "CLINIC_DATA"
    private const val CHUNK_SIZE = 3_000   // safe for plain text (no Base64 overhead)
    private const val B64_CHUNK  = 2_000   // conservative when Base64 is needed
    private const val PIPE_ESC   = "\u2016" // ‖ replaces literal | in plain-text path

    /** Longest-first: prevents "visual acuity" matching inside "best corrected visual acuity". */
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
     * @return Number of CHUNK lines emitted (for UI display).
     */
    fun send(rawText: String): Int {
        val processed = applyShorthand(rawText)
        val needsB64  = !processed.isAsciiSafe()

        return if (needsB64) sendBase64(processed) else sendPlain(processed)
    }

    // ── Plain-text path (ASCII) ────────────────────────────────────────────

    private fun sendPlain(text: String): Int {
        // Escape any "|" so they don't break protocol framing
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

    // ── Base64 path (non-ASCII: Arabic names, accents, symbols) ──────────

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

    // ── Helpers ────────────────────────────────────────────────────────────

    private fun applyShorthand(text: String): String {
        var result = text
        SHORTHAND.forEach { (long, short) ->
            result = result.replace(long, short, ignoreCase = true)
        }
        return result
    }

    /** True if every code point is in the printable ASCII range and safe for Logcat. */
    private fun String.isAsciiSafe(): Boolean =
        all { it.code in 32..126 }

    /** Base-36 Unix timestamp — short, sortable, collision-resistant for a clinic day. */
    private fun sessionId(): String =
        System.currentTimeMillis().toString(36).uppercase(java.util.Locale.ROOT)
}
