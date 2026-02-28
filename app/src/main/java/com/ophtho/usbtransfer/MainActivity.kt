package com.ophtho.usbtransfer

import android.content.*
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
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
                    val chunks  = intent.getIntExtra(OverlayService.EXTRA_CHUNKS, 1)
                    logEvent("✓ Sent: $chunks chunks ($preview...)")
                    pulseStatusDot()
                }
                OverlayService.ACTION_SERVICE_ALIVE -> syncServiceUI()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        registerServiceReceiver()

        binding.btnToggleService.setOnClickListener {
            if (OverlayService.isRunning) stopService(Intent(this, OverlayService::class.java))
            else checkPermissionThenStart()
            syncServiceUI()
        }

        binding.btnSend.setOnClickListener {
            val text = binding.editNote.text.toString().trim()
            if (text.isNotEmpty()) {
                Thread { ClipboardTransmitter.send(text) }.start()
                logEvent("Manual send")
            }
        }

        binding.btnToggleLog.setOnClickListener {
            isLogVisible = !isLogVisible
            binding.logContainer.visibility = if (isLogVisible) View.VISIBLE else View.GONE
            binding.btnToggleLog.text = if (isLogVisible) "Hide Debug" else "Show Debug"
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

    private fun checkPermissionThenStart() {
        if (Settings.canDrawOverlays(this)) {
            startForegroundService(Intent(this, OverlayService::class.java))
        } else {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
    }

    private fun syncServiceUI() {
        val running = OverlayService.isRunning
        binding.btnToggleService.text = if (running) "Stop Bubble" else "Start Bubble"
        binding.tvStatus.text = if (running) "Active" else "Idle"
        binding.statusDot.setBackgroundColor(if (running) Color.GREEN else Color.GRAY)
    }

    private fun pulseStatusDot() {
        binding.statusDot.setBackgroundColor(Color.CYAN)
        binding.root.postDelayed({ syncServiceUI() }, 1000)
    }

    private fun registerServiceReceiver() {
        val filter = IntentFilter().apply {
            addAction(OverlayService.ACTION_CLIP_SENT)
            addAction(OverlayService.ACTION_SERVICE_ALIVE)
        }
        registerReceiver(serviceReceiver, filter, RECEIVER_NOT_EXPORTED)
    }

    private fun logEvent(msg: String) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        runOnUiThread { binding.logTextView.append("\n[$ts] $msg") }
    }
}