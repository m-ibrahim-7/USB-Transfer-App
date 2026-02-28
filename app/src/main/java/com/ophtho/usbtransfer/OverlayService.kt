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
 * OverlayService v6 - NO FOREGROUND SERVICE
 * 
 * CRITICAL: This version removes ALL startForeground() calls.
 * The overlay bubble itself keeps the process alive.
 * This eliminates the SecurityException crashes completely.
 */
class OverlayService : Service() {

    companion object {
        private const val TAG = "OverlayService"
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
        addOverlayBubble()
        isRunning = true
        sendBroadcast(Intent(ACTION_SERVICE_ALIVE))
        Log.d(TAG, "Service created - bubble active")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Return START_STICKY so Android restarts service if killed
        // But note: we DON'T call startForeground() anymore
        return START_STICKY
    }

    override fun onDestroy() {
        removeOverlayBubble()
        isRunning = false
        Log.d(TAG, "Service destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun addOverlayBubble() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        
        // Create bubble view
        val frame = FrameLayout(this)
        val tv = TextView(this).apply {
            text = "📋"
            textSize = 24f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(220, 0, 137, 123)) // Teal with opacity
                setStroke(3, Color.WHITE)
            }
        }
        
        frame.addView(tv, FrameLayout.LayoutParams(160, 160))
        
        // Window parameters
        val params = WindowManager.LayoutParams(
            160, 160,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or 
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 20
            y = 300
        }
        
        // Set click listener
        frame.setOnClickListener { captureAndSend() }
        
        // Add drag functionality
        frame.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    // Store initial position
                    params.x = (params.x - event.rawX).toInt()
                    params.y = (params.y - event.rawY).toInt()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    // Update position
                    params.x = (event.rawX + params.x).toInt()
                    params.y = (event.rawY + params.y).toInt()
                    windowManager?.updateViewLayout(v, params)
                    true
                }
                else -> false
            }
        }
        
        bubbleView = frame
        windowManager?.addView(bubbleView, params)
    }

    private fun captureAndSend() {
        try {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            
            // Check if clipboard has content
            val clip = cm.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
            
            if (clip.isEmpty()) {
                Toast.makeText(this, "Clipboard empty", Toast.LENGTH_SHORT).show()
                return
            }

            // Send in background thread
            Thread {
                try {
                    val chunks = ClipboardTransmitter.send(clip)
                    
                    // Notify UI
                    val intent = Intent(ACTION_CLIP_SENT).apply {
                        putExtra(EXTRA_PREVIEW, clip.take(20))
                        putExtra(EXTRA_CHUNKS, chunks)
                    }
                    sendBroadcast(intent)
                    
                    // Show toast on main thread
                    mainHandler.post {
                        Toast.makeText(this@OverlayService, 
                            "✓ Sent $chunks chunks", Toast.LENGTH_SHORT).show()
                    }
                    
                    Log.d(TAG, "Sent $chunks chunks: ${clip.take(20)}...")
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Transmission failed", e)
                    mainHandler.post {
                        Toast.makeText(this@OverlayService, 
                            "❌ Send failed", Toast.LENGTH_SHORT).show()
                    }
                }
            }.start()
            
        } catch (e: Exception) { 
            Log.e(TAG, "Capture error", e)
        }
    }

    private fun removeOverlayBubble() {
        try {
            bubbleView?.let { 
                windowManager?.removeView(it)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error removing bubble", e)
        }
        bubbleView = null
    }
}