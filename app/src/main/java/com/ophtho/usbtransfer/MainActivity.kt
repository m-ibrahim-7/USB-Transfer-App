package com.ophtho.usbtransfer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.ophtho.usbtransfer.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.*

/**
 * MainActivity
 * ─────────────
 * • Starts DataBridgeService as a foreground service on launch.
 * • Listens for STATUS broadcasts from the service and updates the UI.
 * • Handles Share Intents from other apps.
 * • Shows "Retry" button when the PC is not connected.
 *
 * File location: app/src/main/java/com/ophtho/usbtransfer/MainActivity.kt
 * (Replace the existing file entirely.)
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var isLogVisible = true
    private var isConnected = false
    private var pendingText: String? = null   // held while PC disconnected

    // ── Broadcast receiver for service status ─────────────────────────────────
    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val msg = intent.getStringExtra(DataBridgeService.EXTRA_STATUS_MSG) ?: return
            isConnected = intent.getBooleanExtra(DataBridgeService.EXTRA_CONNECTED, false)
            updateConnectionIndicator()
            logEvent(msg)
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        startDataBridgeService()
        setupButtons()
        updateConnectionIndicator()
        logEvent("App started – TCP server launching on port ${DataBridgeService.PORT}")

        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(DataBridgeService.BROADCAST_STATUS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(statusReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(statusReceiver)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    // ── UI Setup ──────────────────────────────────────────────────────────────

    private fun setupButtons() {
        // Manual send
        binding.btnSend.setOnClickListener {
            val text = binding.editNote.text.toString().trim()
            if (text.isNotEmpty()) sendOrQueue(text)
            else logEvent("Error: nothing to send")
        }

        // Retry button (visible when no PC connection)
        binding.btnRetry.setOnClickListener {
            val text = pendingText ?: binding.editNote.text.toString().trim()
            if (text.isNotEmpty()) {
                sendOrQueue(text)
            } else {
                logEvent("Nothing queued to retry")
            }
        }

        // Toggle debug log
        binding.btnToggleLog.setOnClickListener {
            isLogVisible = !isLogVisible
            binding.logContainer.visibility = if (isLogVisible) View.VISIBLE else View.GONE
            binding.btnToggleLog.text = if (isLogVisible) "Hide Debug" else "Show Debug"
        }
    }

    // ── Intent handling ───────────────────────────────────────────────────────

    private fun handleIntent(intent: Intent) {
        if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
            binding.editNote.setText(sharedText)
            sendOrQueue(sharedText)
            intent.removeExtra(Intent.EXTRA_TEXT)
            intent.action = null
        }
    }

    // ── Send logic ────────────────────────────────────────────────────────────

    private fun sendOrQueue(text: String) {
        // Always pass to service; it queues internally if PC not connected
        val svcIntent = Intent(this, DataBridgeService::class.java).apply {
            action = DataBridgeService.ACTION_SEND
            putExtra(DataBridgeService.EXTRA_TEXT, text)
        }
        startService(svcIntent)

        if (!isConnected) {
            pendingText = text
            binding.btnRetry.visibility = View.VISIBLE
            logEvent("PC not connected – message queued, Retry available")
            Toast.makeText(this, "Queued – will send when PC connects", Toast.LENGTH_SHORT).show()
        } else {
            pendingText = null
            binding.btnRetry.visibility = View.GONE
            logEvent("Sent ${text.length} chars to service")
        }

        // Update "Last Message" label
        binding.tvLastMessage.text = text.take(120) + if (text.length > 120) "…" else ""
    }

    // ── Service start ─────────────────────────────────────────────────────────

    private fun startDataBridgeService() {
        val intent = Intent(this, DataBridgeService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    // ── UI helpers ────────────────────────────────────────────────────────────

    private fun updateConnectionIndicator() {
        binding.tvConnectionStatus.text = if (isConnected) "● PC Connected" else "● Waiting for PC"
        binding.tvConnectionStatus.setTextColor(
            getColor(if (isConnected) R.color.status_green else R.color.status_red)
        )
    }

    private fun logEvent(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val fullMessage = "[$timestamp] $message"
        runOnUiThread {
            binding.logTextView.append("\n$fullMessage")
        }
    }
}
