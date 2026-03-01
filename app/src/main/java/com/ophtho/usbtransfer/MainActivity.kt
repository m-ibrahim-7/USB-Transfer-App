package com.ophtho.usbtransfer

import android.content.*
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.ophtho.usbtransfer.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var isLogVisible = false

    private val serviceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                OverlayService.ACTION_CLIP_SENT -> {
                    val preview = intent.getStringExtra(OverlayService.EXTRA_PREVIEW) ?: ""
                    val chunks = intent.getIntExtra(OverlayService.EXTRA_CHUNKS, 1)
                    logEvent("✓ Sent: $chunks chunks ($preview...)")
                    pulseStatusDot()
                }
                OverlayService.ACTION_SERVICE_ALIVE -> {
                    // Force UI update when service state changes
                    runOnUiThread {
                        syncServiceUI()
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        registerServiceReceiver()

        binding.btnToggleService.setOnClickListener {
            if (OverlayService.isRunning) {
                stopService(Intent(this, OverlayService::class.java))
                // Force immediate UI update
                OverlayService.isRunning = false
                syncServiceUI()
                logEvent("Service stopped")
            } else {
                checkOverlayPermissionThenStart()
            }
        }

        binding.btnSend.setOnClickListener {
            val text = binding.editNote.text.toString().trim()
            if (text.isNotEmpty()) {
                Thread { 
                    val chunks = ClipboardTransmitter.send(text)
                    runOnUiThread {
                        logEvent("✓ Manual: $chunks chunks sent")
                        Toast.makeText(this, "Sent $chunks chunks", Toast.LENGTH_SHORT).show()
                    }
                }.start()
                binding.editNote.text.clear()
            }
        }

        binding.btnToggleLog.setOnClickListener {
            isLogVisible = !isLogVisible
            binding.logContainer.visibility = if (isLogVisible) View.VISIBLE else View.GONE
            binding.btnToggleLog.text = if (isLogVisible) "Hide Debug" else "Show Debug"
        }

        binding.btnClearLog.setOnClickListener {
            binding.logTextView.text = ""
        }
    }

    override fun onResume() {
        super.onResume()
        syncServiceUI()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(serviceReceiver)
    }

    private fun checkOverlayPermissionThenStart() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Please grant overlay permission", Toast.LENGTH_LONG).show()
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
            return
        }

        startService(Intent(this, OverlayService::class.java))
        // Give service a moment to start, then update UI
        binding.root.postDelayed({
            syncServiceUI()
        }, 100)
    }

    private fun syncServiceUI() {
        val running = OverlayService.isRunning
        binding.btnToggleService.text = if (running) "Stop Bubble" else "Start Bubble"
        binding.tvStatus.text = if (running) "Active" else "Idle"
        
        val color = if (running) {
            ContextCompat.getColor(this, android.R.color.holo_green_dark)
        } else {
            ContextCompat.getColor(this, android.R.color.darker_gray)
        }
        binding.statusDot.setBackgroundColor(color)
    }

    private fun pulseStatusDot() {
        binding.statusDot.setBackgroundColor(
            ContextCompat.getColor(this, android.R.color.holo_blue_light)
        )
        binding.root.postDelayed({ syncServiceUI() }, 1000)
    }

    private fun registerServiceReceiver() {
        val filter = IntentFilter().apply {
            addAction(OverlayService.ACTION_CLIP_SENT)
            addAction(OverlayService.ACTION_SERVICE_ALIVE)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(serviceReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(serviceReceiver, filter)
        }
    }

    private fun logEvent(msg: String) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        runOnUiThread {
            binding.logTextView.append("\n[$ts] $msg")
            binding.logContainer.post {
                binding.logContainer.fullScroll(View.FOCUS_DOWN)
            }
        }
    }
}