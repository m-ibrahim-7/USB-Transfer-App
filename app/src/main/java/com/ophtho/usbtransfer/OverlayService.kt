package com.ophtho.usbtransfer

import android.app.*
import android.content.*
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.util.Log
import android.view.*
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast

/**
 * OverlayService v6 (Stable)
 * Restores Foreground Notification to prevent OS "RemoteServiceException" crashes.
 */
class OverlayService : Service() {

    companion object {
        private const val TAG = "OverlayService"
        private const val CHANNEL_ID = "bridge_service_channel"
        private const val NOTIF_ID = 101
        @Volatile var isRunning = false
    }

    private var windowManager: WindowManager? = null
    private var bubbleView: View? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // Immediate start to satisfy Android's 5-second rule
        startForeground(NOTIF_ID, buildNotification())
        addOverlayBubble()
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Ensures notification stays alive if service restarts
        startForeground(NOTIF_ID, buildNotification())
        return START_STICKY
    }

    override fun onDestroy() {
        removeOverlayBubble()
        isRunning = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun addOverlayBubble() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        
        val frame = FrameLayout(this)
        val tv = TextView(this).apply {
            text = "📋"
            textSize = 24f
            gravity = Gravity.CENTER
            val shape = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(220, 0, 137, 123)) // Teal
                setStroke(3, Color.WHITE)
            }
            background = shape
        }
        
        frame.addView(tv, FrameLayout.LayoutParams(160, 160))

        val params = WindowManager.LayoutParams(
            160, 160,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 20
            y = 300
        }

        frame.setOnClickListener { captureAndSend() }
        
        bubbleView = frame
        windowManager?.addView(bubbleView, params)
    }

    private fun captureAndSend() {
        try {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            val clip = cm.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
            
            if (clip.isEmpty()) {
                Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
                return
            }

            Thread {
                try {
                    val chunks = ClipboardTransmitter.send(clip)
                    mainHandler.post {
                        Toast.makeText(this, "✓ Sent $chunks chunks to PC", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Transmission failed", e)
                }
            }.start()

        } catch (e: Exception) {
            Log.e(TAG, "Clipboard access error", e)
        }
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Ophtho Bridge Running")
            .setContentText("Tap the floating bubble to send data")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Bridge Service", NotificationManager.IMPORTANCE_LOW)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun removeOverlayBubble() {
        bubbleView?.let { windowManager?.removeView(it) }
        bubbleView = null
    }
}