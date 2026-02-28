package com.ophtho.usbtransfer

import android.app.*
import android.content.*
import android.graphics.PixelFormat
import android.os.*
import android.util.Log
import android.view.*
import android.widget.ImageButton
import android.widget.Toast

/**
 * OverlayService  —  The "Overlay Bubble" solution
 * ══════════════════════════════════════════════════════════════════════════════
 *
 * WHY THIS SOLVES THE ANDROID 12+ CLIPBOARD WALL
 * ─────────────────────────────────────────────────
 * Android 12+ blocks getPrimaryClip() unless the reading app is either:
 *   (a) the focused window, or
 *   (b) the default input method (keyboard).
 *
 * A SYSTEM_ALERT_WINDOW overlay (TYPE_APPLICATION_OVERLAY) is a real window
 * owned by this app's process.  The instant the user taps it, Android grants
 * INPUT_FOCUS to that window for the duration of the tap — satisfying condition
 * (a) legally, without any hacks.
 *
 * WORKFLOW
 * ─────────
 *  1. User reads a note in Rhazes AI and taps "Copy".
 *  2. User taps the small floating bubble (always visible on top of Rhazes AI).
 *  3. The tap gives our window INPUT_FOCUS for ~1 second.
 *  4. We immediately call getPrimaryClip() — OS grants it.
 *  5. We transmit via Logcat and release focus.
 *  6. User never leaves Rhazes AI.
 *
 * NO POLLING.  NO WAKE LOCK.  NO BATTERY DRAMA.
 */
class OverlayService : Service() {

    companion object {
        const val ACTION_CLIP_SENT     = "com.ophtho.usbtransfer.CLIP_SENT"
        const val ACTION_SERVICE_ALIVE = "com.ophtho.usbtransfer.SERVICE_ALIVE"
        const val EXTRA_PREVIEW        = "preview"
        const val EXTRA_CHUNKS         = "chunks"

        @Volatile var isRunning = false
            private set

        private const val CHANNEL_ID = "overlay_channel"
        private const val NOTIF_ID   = 2001
        private const val TAG        = "OverlayService"
    }

    private var windowManager: WindowManager? = null
    private var overlayView: View?            = null

    // ── Lifecycle ──────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())
        addOverlayBubble()
        isRunning = true
        Log.d(TAG, "OverlayService started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        START_STICKY   // OS restarts if killed; bubble reappears automatically

    override fun onDestroy() {
        removeOverlayBubble()
        isRunning = false
        Log.d(TAG, "OverlayService destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Overlay creation ───────────────────────────────────────────────────

    private fun addOverlayBubble() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        overlayView = LayoutInflater.from(this)
            .inflate(R.layout.overlay_bubble, null)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // NOT_FOCUSABLE is OFF: we NEED focus so Android grants clipboard access
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 16   // dp offset from right edge
            y = 200  // dp offset from top — user can drag to reposition
        }

        overlayView!!.let { view ->
            // ── Tap: read clipboard (legal because we now have window focus) ──
            view.findViewById<ImageButton>(R.id.btnOverlaySend).setOnClickListener {
                captureAndSend()
            }

            // ── Drag: let the user reposition the bubble ─────────────────────
            var dX = 0f; var dY = 0f
            view.setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        dX = params.x - event.rawX
                        dY = params.y - event.rawY
                        false  // let click events through
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = (event.rawX + dX).toInt()
                        params.y = (event.rawY + dY).toInt()
                        windowManager!!.updateViewLayout(view, params)
                        true
                    }
                    else -> false
                }
            }
        }

        windowManager!!.addView(overlayView, params)
    }

    private fun removeOverlayBubble() {
        overlayView?.let { windowManager?.removeView(it) }
        overlayView = null
    }

    // ── Core: focus-gated clipboard read ──────────────────────────────────

    /**
     * Called on the main thread immediately after the tap (we still hold focus).
     * On Android 12+, getPrimaryClip() is only granted to the focused window.
     * Since the user just tapped OUR overlay, we ARE the focused window right now.
     */
    private fun captureAndSend() {
        val cm   = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip

        if (clip == null || clip.itemCount == 0) {
            Toast.makeText(this, "Clipboard is empty — copy first!", Toast.LENGTH_SHORT).show()
            Log.w(TAG, "Clipboard empty at tap time")
            return
        }

        val text = clip.getItemAt(0)
            .coerceToText(this)
            .toString()
            .trim()

        if (text.isEmpty()) {
            Toast.makeText(this, "Nothing on clipboard", Toast.LENGTH_SHORT).show()
            return
        }

        // Transmit on a background thread so the UI tap response is instant
        Thread {
            val chunks = ClipboardTransmitter.send(text)
            val preview = text.take(40).replace('\n', ' ')

            // Notify MainActivity
            sendBroadcast(
                Intent(ACTION_CLIP_SENT).setPackage(packageName)
                    .putExtra(EXTRA_PREVIEW, preview)
                    .putExtra(EXTRA_CHUNKS, chunks)
            )

            // Brief visual feedback on the bubble itself
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(this, "✓ Sent to PC", Toast.LENGTH_SHORT).show()
            }

            Log.d(TAG, "Sent: $chunks chunk(s) — \"$preview\"")
        }.start()
    }

    // ── Notification ───────────────────────────────────────────────────────

    private fun buildNotification(): Notification {
        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Ophtho Bridge — Bubble Active")
            .setContentText("Tap the bubble after copying a note")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentIntent(tapIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        NotificationChannel(
            CHANNEL_ID,
            "Ophtho Bridge Overlay",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps the floating capture bubble alive"
            getSystemService(NotificationManager::class.java).createNotificationChannel(this)
        }
    }
}
