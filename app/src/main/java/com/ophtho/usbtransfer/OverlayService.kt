package com.ophtho.usbtransfer

import android.animation.ObjectAnimator
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
import androidx.core.app.NotificationCompat

class OverlayService : Service() {

    companion object {
        private const val TAG = "OverlayService"
        private const val CHANNEL_ID = "bubble_feedback_channel"
        private const val NOTIF_ID = 1001
        @Volatile var isRunning = false

        const val ACTION_CLIP_SENT = "com.ophtho.usbtransfer.CLIP_SENT"
        const val ACTION_SERVICE_ALIVE = "com.ophtho.usbtransfer.SERVICE_ALIVE"
        const val EXTRA_PREVIEW = "extra_preview"
        const val EXTRA_CHUNKS = "extra_chunks"
        const val EXTRA_TIMESTAMP = "extra_timestamp"
    }

    private var windowManager: WindowManager? = null
    private var bubbleView: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var tvBubble: TextView? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var notificationManager: NotificationManager? = null
    
    // For drag handling
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
        addOverlayBubble()
        isRunning = true
        sendBroadcast(Intent(ACTION_SERVICE_ALIVE))
        Log.d(TAG, "Service created - bubble active")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        removeOverlayBubble()
        isRunning = false
        sendBroadcast(Intent(ACTION_SERVICE_ALIVE))
        Log.d(TAG, "Service destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Bubble Feedback",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows bubble send status"
                setSound(null, null)
                enableLights(false)
                enableVibration(false)
            }
            notificationManager?.createNotificationChannel(channel)
        }
    }

    private fun addOverlayBubble() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        
        val frame = FrameLayout(this)
        
        // Create a more prominent bubble with shadow and better styling
        tvBubble = TextView(this).apply {
            text = "📋"
            textSize = 32f  // Larger text
            gravity = Gravity.CENTER
            
            // Enhanced background with shadow
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(230, 0, 137, 123))  // More opaque
                setStroke(4, Color.WHITE)  // Thicker border
            }
            
            // Add elevation for shadow (Android 5+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                elevation = 8f
            }
        }
        
        // Larger bubble size (200x200 instead of 160x160)
        val bubbleSize = 200
        frame.addView(tvBubble, FrameLayout.LayoutParams(bubbleSize, bubbleSize))
        
        params = WindowManager.LayoutParams(
            bubbleSize, bubbleSize,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 300
        }
        
        // Touch handling
        frame.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params?.x ?: 0
                    initialY = params?.y ?: 0
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val newX = initialX + (event.rawX - initialTouchX).toInt()
                    val newY = initialY + (event.rawY - initialTouchY).toInt()
                    
                    params?.x = newX
                    params?.y = newY
                    windowManager?.updateViewLayout(v, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val deltaX = (event.rawX - initialTouchX).toInt()
                    val deltaY = (event.rawY - initialTouchY).toInt()
                    
                    if (Math.abs(deltaX) < 10 && Math.abs(deltaY) < 10) {
                        captureAndSend()
                    }
                    true
                }
                else -> false
            }
        }
        
        bubbleView = frame
        windowManager?.addView(bubbleView, params)
        
        // Add a subtle entrance animation
        tvBubble?.alpha = 0f
        tvBubble?.animate()
            ?.alpha(1f)
            ?.scaleX(1.2f)
            ?.scaleY(1.2f)
            ?.setDuration(500)
            ?.withEndAction {
                tvBubble?.animate()
                    ?.scaleX(1f)
                    ?.scaleY(1f)
                    ?.setDuration(300)
                    ?.start()
            }
            ?.start()
    }

    private fun captureAndSend() {
        try {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            val clip = cm.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
            
            if (clip.isEmpty()) {
                showFeedback("📋", "Clipboard is empty", isError = true)
                return
            }

            showFeedback("⏳", "Sending...", isError = false)

            Thread {
                try {
                    val chunks = ClipboardTransmitter.send(clip)
                    val preview = clip.take(20) + if (clip.length > 20) "…" else ""
                    
                    showFeedback("✓", "Sent: $preview", isError = false)
                    
                    val intent = Intent(ACTION_CLIP_SENT).apply {
                        putExtra(EXTRA_PREVIEW, preview)
                        putExtra(EXTRA_CHUNKS, chunks)
                        putExtra(EXTRA_TIMESTAMP, System.currentTimeMillis())
                    }
                    sendBroadcast(intent)
                    
                    Log.d(TAG, "Sent $chunks chunks: $preview")
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Transmission failed", e)
                    showFeedback("✗", "Send failed", isError = true)
                }
            }.start()
            
        } catch (e: Exception) { 
            Log.e(TAG, "Capture error", e)
            showFeedback("⚠️", "Error", isError = true)
        }
    }

    private fun showFeedback(emoji: String, message: String, isError: Boolean) {
        mainHandler.post {
            // Animate bubble
            tvBubble?.apply {
                val originalText = this.text.toString()
                val originalColor = (background as? GradientDrawable)?.color ?: Color.argb(230, 0, 137, 123)
                
                // Change bubble
                this.text = emoji
                (background as? GradientDrawable)?.setColor(
                    if (isError) Color.argb(230, 244, 67, 54) // Red
                    else Color.argb(230, 76, 175, 80) // Green
                )
                
                // Pulsing animation
                this.animate()
                    .scaleX(1.3f)
                    .scaleY(1.3f)
                    .setDuration(200)
                    .withEndAction {
                        this.animate()
                            .scaleX(1.0f)
                            .scaleY(1.0f)
                            .setDuration(200)
                            .withEndAction {
                                mainHandler.postDelayed({
                                    this.text = originalText
                                    (background as? GradientDrawable)?.setColor(originalColor)
                                }, 1500)
                            }
                            .start()
                    }
                    .start()
            }
            
            // Show notification
            try {
                val notification = NotificationCompat.Builder(this@OverlayService, CHANNEL_ID)
                    .setContentTitle("Ophtho Bridge")
                    .setContentText(message)
                    .setSmallIcon(if (isError) android.R.drawable.stat_notify_error else android.R.drawable.stat_sys_upload_done)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setTimeoutAfter(3000)
                    .build()
                
                notificationManager?.notify((System.currentTimeMillis() % 10000).toInt(), notification)
            } catch (e: Exception) {
                Log.e(TAG, "Notification failed: ${e.message}")
            }
        }
    }

    private fun removeOverlayBubble() {
        try {
            bubbleView?.let { 
                // Fade out animation
                tvBubble?.animate()
                    ?.alpha(0f)
                    ?.scaleX(0.5f)
                    ?.scaleY(0.5f)
                    ?.setDuration(300)
                    ?.withEndAction {
                        windowManager?.removeView(it)
                    }
                    ?.start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error removing bubble", e)
        }
        bubbleView = null
    }
}