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

/**
 * MainActivity  v3
 * ══════════════════════════════════════════════════════════════════════════════
 * Primary responsibility: manage the OverlayService lifecycle.
 *
 * PERMISSION FLOW
 * ────────────────
 * The overlay requires SYSTEM_ALERT_WINDOW (also called "Draw over other apps").
 * This is a special permission the user must grant manually in Settings — it
 * cannot be granted at install time.  We check on every resume and show a
 * clear explanation dialog if it's missing.
 *
 * WORKFLOW FOR THE DOCTOR
 * ────────────────────────
 * 1. Open Ophtho Bridge once → grant "Draw over other apps" → tap Start.
 * 2. Switch to Rhazes AI — the bubble floats on top.
 * 3. Copy a note in Rhazes AI.
 * 4. Tap the bubble — done.  Note is on the PC within ~1 second.
 * 5. Never need to return to Bridge during clinic.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var isLogVisible = true

    // ── Broadcast receiver ─────────────────────────────────────────────────
    private val serviceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                OverlayService.ACTION_CLIP_SENT -> {
                    val preview = intent.getStringExtra(OverlayService.EXTRA_PREVIEW) ?: ""
                    val chunks  = intent.getIntExtra(OverlayService.EXTRA_CHUNKS, 1)
                    logEvent("✓ PC received: $chunks chunk(s) — \"$preview…\"")
                    pulseStatusDot()
                }
                OverlayService.ACTION_SERVICE_ALIVE -> {
                    logEvent("♥ Bubble service heartbeat OK")
                }
            }
        }
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        logEvent("Bridge v3 ready — tap Start, then switch to Rhazes AI")
        registerServiceReceiver()
        handleShareIntent(intent)   // future-proof Share path

        binding.btnToggleService.setOnClickListener {
            if (OverlayService.isRunning) stopOverlayService()
            else                          checkPermissionThenStart()
        }

        binding.btnSend.setOnClickListener {
            val text = binding.editNote.text.toString().trim()
            if (text.isNotEmpty()) {
                Thread { ClipboardTransmitter.send(text) }.start()
                logEvent("Manual send dispatched")
                Toast.makeText(this, "Sent to PC", Toast.LENGTH_SHORT).show()
            } else {
                logEvent("⚠ Text box empty")
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
        // If user just came back from granting the permission, auto-start
        if (!OverlayService.isRunning && Settings.canDrawOverlays(this)) {
            // Only auto-start if they clearly navigated away to grant permission
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(serviceReceiver)
    }

    // ── Permission ─────────────────────────────────────────────────────────

    private fun checkPermissionThenStart() {
        if (Settings.canDrawOverlays(this)) {
            startOverlayService()
        } else {
            showOverlayPermissionDialog()
        }
    }

    private fun showOverlayPermissionDialog() {
        android.app.AlertDialog.Builder(this)
            .setTitle("One Permission Needed")
            .setMessage(
                "To show the floating capture bubble over Rhazes AI, " +
                "Ophtho Bridge needs the \"Draw over other apps\" permission.\n\n" +
                "Tap OK → find \"Ophtho Bridge\" → enable the toggle."
            )
            .setPositiveButton("Open Settings") { _, _ ->
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── Service control ────────────────────────────────────────────────────

    private fun startOverlayService() {
        startForegroundService(Intent(this, OverlayService::class.java))
        logEvent("▶ Overlay bubble started — switch to Rhazes AI")
        syncServiceUI()
    }

    private fun stopOverlayService() {
        stopService(Intent(this, OverlayService::class.java))
        logEvent("■ Overlay bubble stopped")
        syncServiceUI()
    }

    private fun syncServiceUI() {
        val running = OverlayService.isRunning
        binding.btnToggleService.text = if (running) "■ Stop Bubble" else "▶ Start Bubble"
        binding.tvStatus.text = when {
            running                          -> "Bubble active — switch to Rhazes AI"
            !Settings.canDrawOverlays(this)  -> "Permission needed — tap Start"
            else                             -> "Idle — tap Start"
        }
        binding.statusDot.setBackgroundResource(
            if (running) R.drawable.dot_green else R.drawable.dot_grey
        )
    }

    private fun pulseStatusDot() {
        binding.statusDot.setBackgroundResource(R.drawable.dot_bright_green)
        binding.root.postDelayed(
            { binding.statusDot.setBackgroundResource(R.drawable.dot_green) },
            1_500
        )
    }

    // ── Share intent (future-proof) ────────────────────────────────────────

    private fun handleShareIntent(intent: Intent) {
        if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
            binding.editNote.setText(text)
            logEvent("Received via Share Sheet (${text.length} chars)")
            Thread { ClipboardTransmitter.send(text) }.start()
            intent.removeExtra(Intent.EXTRA_TEXT)
            intent.action = null
        }
    }

    // ── Receiver ───────────────────────────────────────────────────────────

    private fun registerServiceReceiver() {
        registerReceiver(
            serviceReceiver,
            IntentFilter().apply {
                addAction(OverlayService.ACTION_CLIP_SENT)
                addAction(OverlayService.ACTION_SERVICE_ALIVE)
            },
            RECEIVER_NOT_EXPORTED
        )
    }

    // ── Logging ────────────────────────────────────────────────────────────

    private fun logEvent(message: String) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        runOnUiThread { binding.logTextView.append("\n[$ts] $message") }
    }
}
