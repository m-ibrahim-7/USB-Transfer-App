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
        @Volatile var isRunning = false

        const val ACTION_CLIP_SENT = "com.ophtho.usbtransfer.CLIP_SENT"
        const val ACTION_SERVICE_ALIVE = "com.ophtho.usbtransfer.SERVICE_ALIVE"
        const val EXTRA_PREVIEW = "extra_preview"
        const val EXTRA_CHUNKS = "extra_chunks"
    }

    private var windowManager: WindowManager? = null
    private var bubbleView: View? = null
    private var params: WindowManager.LayoutParams? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    
    // For drag handling
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f

    override fun onCreate() {
        super.onCreate()
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
                setColor(Color.argb(220, 0, 137, 123))
                setStroke(3, Color.WHITE)
            }
        }
        
        frame.addView(tv, FrameLayout.LayoutParams(160, 160))
        
        // Window parameters
        params = WindowManager.LayoutParams(
            160, 160,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100  // Initial position from left
            y = 300  // Initial position from top
        }
        
        // Set touch listener for dragging and clicking
        frame.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    // Store initial positions
                    initialX = params?.x ?: 0
                    initialY = params?.y ?: 0
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    // Calculate new position
                    val newX = initialX + (event.rawX - initialTouchX).toInt()
                    val newY = initialY + (event.rawY - initialTouchY).toInt()
                    
                    // Update layout
                    params?.x = newX
                    params?.y = newY
                    windowManager?.updateViewLayout(v, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    // Check if it was a click (minimal movement)
                    val deltaX = (event.rawX - initialTouchX).toInt()
                    val deltaY = (event.rawY - initialTouchY).toInt()
                    
                    if (Math.abs(deltaX) < 10 && Math.abs(deltaY) < 10) {
                        // It's a click, not a drag
                        captureAndSend()
                    }
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
                        Toast.makeText(this@OverlayService, 
                            "✓ Sent $chunks chunks", Toast.LENGTH_SHORT).show()
                    }
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Transmission failed", e)
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