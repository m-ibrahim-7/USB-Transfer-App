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

class OverlayService : Service() {

    companion object {
        private const val TAG = "OverlayService"
        private const val CHANNEL_ID = "bridge_service_channel"
        private const val NOTIF_ID = 101
        @Volatile var isRunning = false

        const val ACTION_CLIP_SENT = "com.ophtho.usbtransfer.CLIP_SENT"
        const val ACTION_SERVICE_ALIVE = "com.ophtho.usbtransfer.SERVICE_ALIVE"
        const val EXTRA_PREVIEW = "extra_preview"
        const val EXTRA_CHUNKS = "extra_chunks"
    }

    private var windowManager: WindowManager? = null
    private var bubbleView: View? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification()) // Immediate call
        addOverlayBubble()
        isRunning = true
        sendBroadcast(Intent(ACTION_SERVICE_ALIVE))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification()) // Redundant safety call
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
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(220, 0, 137, 123))
                setStroke(3, Color.WHITE)
            }
        }
        frame.addView(tv, FrameLayout.LayoutParams(160, 160))
        val params = WindowManager.LayoutParams(
            160, 160,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 20; y = 300
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
                Toast.makeText(this, "Clipboard empty", Toast.LENGTH_SHORT).show()
                return
            }

            Thread {
                try {
                    val chunks = ClipboardTransmitter.send(clip)
                    val intent = Intent(ACTION_CLIP_SENT).apply {
                        putExtra(EXTRA_PREVIEW, clip.take(20))
                        putExtra(EXTRA_CHUNKS, chunks)
                    }
                    sendBroadcast(intent)
                    mainHandler.post {
                        Toast.makeText(this, "✓ Sent $chunks chunks", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Fail", e)
                }
            }.start()
        } catch (e: Exception) { Log.e(TAG, "Error", e) }
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Ophtho Bridge")
            .setContentText("Bubble Active")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Bridge", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun removeOverlayBubble() {
        bubbleView?.let { windowManager?.removeView(it) }
        bubbleView = null
    }
}