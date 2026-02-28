package com.ophtho.usbtransfer

import android.app.*
import android.content.*
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.provider.Settings
import android.util.Log
import android.view.*
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast

/**
 * OverlayService  v5
 * ══════════════════════════════════════════════════════════════════════════════
 *
 * KEY CHANGE vs v4
 * ─────────────────
 * startForeground() has been REMOVED entirely.
 *
 * WHY IT'S SAFE TO REMOVE IT
 * ────────────────────────────
 * A foreground service requires android.permission.FOREGROUND_SERVICE in the
 * manifest.  If that line is missing or the APK was built without it, the
 * service crashes the instant onCreate() calls startForeground() — exactly
 * what the crash log shows (all 15 crashes are identical, line 62).
 *
 * More importantly: we don't NEED a foreground service here.
 * The overlay window itself (TYPE_APPLICATION_OVERLAY) keeps the process
 * alive as long as it's visible.  The OS will not kill a process that owns
 * a visible system window.  startForeground() + notification was only needed
 * for the old polling-service approach (v2).  The bubble architecture doesn't
 * poll anything — it only acts when tapped.
 *
 * The persistent notification is also removed (it was only there to satisfy
 * the foreground service requirement).  Cleaner for the doctor.
 *
 * ALSO CONFIRMED WORKING FROM THE CRASH LOG
 * ───────────────────────────────────────────
 * At 18:02:57 in the crash log we can see:
 *   SESSION_START|MM6GADZE|CHUNKS=1|ENC=PLAIN
 *   CHUNK|MM6GADZE|1/1|hello
 *   SESSION_END|MM6GADZE
 * The transmission chain (clipboard → logcat → PC) is fully working.
 * This fix is the only remaining issue.
 */
class OverlayService : Service() {

    companion object {
        const val ACTION_CLIP_SENT     = "com.ophtho.usbtransfer.CLIP_SENT"
        const val ACTION_SERVICE_ALIVE = "com.ophtho.usbtransfer.SERVICE_ALIVE"
        const val EXTRA_PREVIEW        = "preview"
        const val EXTRA_CHUNKS         = "chunks"

        @Volatile var isRunning = false
            private set

        private const val TAG          = "OverlayService"
        private const val FOCUS_DELAY  = 120L   // ms — focus transfer grace period
        private const val DRAG_SLOP    = 10f    // px — tap vs drag threshold
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var bubbleView: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null

    // ── Lifecycle ──────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        // NO startForeground() — plain Service is sufficient for an overlay window
        isRunning = true
        addOverlayBubble()
        Log.d(TAG, "OverlayService v5 started (no foreground service required)")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        START_STICKY

    override fun onDestroy() {
        removeOverlayBubble()
        isRunning = false
        Log.d(TAG, "OverlayService destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Build bubble in code — zero XML / resource dependencies ──────────

    private fun addOverlayBubble() {
        if (!Settings.canDrawOverlays(this)) {
            Log.e(TAG, "SYSTEM_ALERT_WINDOW not granted — stopping")
            Toast.makeText(
                this,
                "Permission needed: Settings → Apps → Ophtho Bridge → Display over other apps → ON",
                Toast.LENGTH_LONG
            ).show()
            stopSelf()
            return
        }

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val dp    = resources.displayMetrics.density
        val sizePx = (62 * dp).toInt()

        // Circular teal button drawn entirely in code
        val circle = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.argb(220, 0, 137, 123))          // 86% teal-700
            setStroke((2 * dp).toInt(), Color.argb(160, 255, 255, 255))
        }

        val label = TextView(this).apply {
            text             = "📋"
            textSize         = 26f
            gravity          = Gravity.CENTER
            background       = circle
            layoutParams     = FrameLayout.LayoutParams(sizePx, sizePx)
            contentDescription = "Send clipboard to PC"
        }

        val frame = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(sizePx, sizePx)
            addView(label)
        }

        val params = WindowManager.LayoutParams(
            sizePx, sizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = (12 * dp).toInt()
            y = (180 * dp).toInt()
        }

        // Touch: small movement = tap → send; large movement = drag → reposition
        var downX = 0f; var downY = 0f; var dragging = false

        frame.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY; dragging = false; false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging &&
                        (Math.abs(event.rawX - downX) > DRAG_SLOP ||
                         Math.abs(event.rawY - downY) > DRAG_SLOP)) {
                        dragging = true
                    }
                    if (dragging) {
                        params.x = (params.x - (event.rawX - downX)).toInt()
                        params.y = (params.y + (event.rawY - downY)).toInt()
                        downX = event.rawX; downY = event.rawY
                        try { windowManager?.updateViewLayout(frame, params) }
                        catch (e: Exception) { /* layout race, ignore */ }
                        true
                    } else false
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging) {
                        // Brief delay lets the OS complete the focus transfer
                        mainHandler.postDelayed({ captureAndSend() }, FOCUS_DELAY)
                    }
                    false
                }
                else -> false
            }
        }

        bubbleView   = frame
        bubbleParams = params

        try {
            windowManager!!.addView(frame, params)
            Log.d(TAG, "Bubble added to WindowManager at y=${params.y}")
        } catch (e: WindowManager.BadTokenException) {
            Log.e(TAG, "BadTokenException on addView: ${e.message}")
            Toast.makeText(this,
                "Overlay permission problem — disable and re-enable in Settings",
                Toast.LENGTH_LONG).show()
            isRunning = false
            stopSelf()
        } catch (e: Exception) {
            Log.e(TAG, "addView failed: ${e.message}")
            stopSelf()
        }
    }

    private fun removeOverlayBubble() {
        try { bubbleView?.let { windowManager?.removeView(it) } }
        catch (e: Exception) { /* already removed */ }
        bubbleView = null
    }

    // ── Clipboard capture — called 120 ms after tap ────────────────────────

    private fun captureAndSend() {
        try {
            val cm   = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = cm.primaryClip

            if (clip == null || clip.itemCount == 0) {
                Toast.makeText(this, "Clipboard empty — Copy something first", Toast.LENGTH_SHORT).show()
                Log.w(TAG, "Clipboard empty at tap time")
                return
            }

            val text = clip.getItemAt(0).coerceToText(this).toString().trim()

            if (text.isEmpty()) {
                Toast.makeText(this, "Clipboard text is blank", Toast.LENGTH_SHORT).show()
                return
            }

            Log.d(TAG, "Clipboard captured: ${text.length} chars")
            setBubbleState(sending = true)

            Thread {
                try {
                    val chunks  = ClipboardTransmitter.send(text)
                    val preview = text.take(40).replace('\n', ' ')

                    sendBroadcast(
                        Intent(ACTION_CLIP_SENT).setPackage(packageName)
                            .putExtra(EXTRA_PREVIEW, preview)
                            .putExtra(EXTRA_CHUNKS, chunks)
                    )

                    mainHandler.post {
                        Toast.makeText(this, "✓ Sent to PC ($chunks chunk(s))", Toast.LENGTH_SHORT).show()
                        setBubbleState(sending = false)
                    }

                    Log.d(TAG, "Transmitted $chunks chunk(s): \"$preview\"")

                } catch (e: Exception) {
                    Log.e(TAG, "Transmission error: ${e.message}")
                    mainHandler.post {
                        Toast.makeText(this, "Send error: ${e.message}", Toast.LENGTH_SHORT).show()
                        setBubbleState(sending = false)
                    }
                }
            }.start()

        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException on clipboard: ${e.message}")
            Toast.makeText(this,
                "Clipboard denied — tap bubble again immediately after Copy",
                Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error: ${e.message}")
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // ── Visual feedback ────────────────────────────────────────────────────

    private fun setBubbleState(sending: Boolean) {
        (bubbleView as? FrameLayout)?.let { frame ->
            val tv  = frame.getChildAt(0) as? TextView ?: return
            val bg  = tv.background as? GradientDrawable ?: return
            tv.text = if (sending) "⏳" else "📋"
            bg.setColor(if (sending)
                Color.argb(220, 255, 143, 0)   // amber while sending
            else
                Color.argb(220, 0, 137, 123))  // teal when idle
        }
    }
}
