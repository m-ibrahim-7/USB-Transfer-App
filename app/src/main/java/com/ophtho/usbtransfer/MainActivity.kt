package com.ophtho.usbtransfer

import android.Manifest
import android.animation.ObjectAnimator
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.ophtho.usbtransfer.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var isLogVisible = false
    private var lastSendTime = 0L

    // Permission request for notifications (Android 13+)
    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            logEvent("✓ Notification permission granted")
            Toast.makeText(this, "Notifications enabled", Toast.LENGTH_SHORT).show()
        } else {
            logEvent("⚠️ Notification permission denied")
            showPermissionRationale("notifications")
        }
    }

    // Permission request for overlay (handled by Settings intent)
    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // Check if permission was granted after returning from settings
        if (Settings.canDrawOverlays(this)) {
            logEvent("✓ Overlay permission granted")
            startService(Intent(this, OverlayService::class.java))
        } else {
            logEvent("⚠️ Overlay permission still denied")
            showPermissionRationale("overlay")
        }
    }

    private val serviceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                OverlayService.ACTION_CLIP_SENT -> {
                    val preview = intent.getStringExtra(OverlayService.EXTRA_PREVIEW) ?: ""
                    val chunks = intent.getIntExtra(OverlayService.EXTRA_CHUNKS, 1)
                    val timestamp = intent.getLongExtra(OverlayService.EXTRA_TIMESTAMP, 0)
                    
                    if (timestamp > lastSendTime) {
                        lastSendTime = timestamp
                        onDataSent(preview, chunks)
                    }
                }
                OverlayService.ACTION_SERVICE_ALIVE -> {
                    runOnUiThread { syncServiceUI() }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        registerServiceReceiver()
        setupClickListeners()
        
        // Check permissions on start
        checkAllPermissions()
    }

    private fun setupClickListeners() {
        binding.btnToggleService.setOnClickListener {
            if (OverlayService.isRunning) {
                stopService(Intent(this, OverlayService::class.java))
                OverlayService.isRunning = false
                syncServiceUI()
                logEvent("Service stopped")
            } else {
                checkAllPermissionsAndStart()
            }
        }

        binding.btnSend.setOnClickListener {
            val text = binding.editNote.text.toString().trim()
            if (text.isNotEmpty()) {
                Thread { 
                    val chunks = ClipboardTransmitter.send(text)
                    runOnUiThread {
                        onDataSent(text.take(20) + if (text.length > 20) "…" else "", chunks)
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

    private fun checkAllPermissions() {
        val missingPermissions = mutableListOf<String>()
        
        // Check overlay permission
        if (!Settings.canDrawOverlays(this)) {
            missingPermissions.add("Draw over other apps")
        }
        
        // Check notification permission (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) 
                != PackageManager.PERMISSION_GRANTED) {
                missingPermissions.add("Notifications")
            }
        }
        
        if (missingPermissions.isNotEmpty()) {
            showInitialPermissionsDialog(missingPermissions)
        }
    }

    private fun showInitialPermissionsDialog(missingPermissions: List<String>) {
        AlertDialog.Builder(this)
            .setTitle("Permissions Required")
            .setMessage(
                "Ophtho Bridge needs these permissions to work:\n\n" +
                missingPermissions.joinToString("\n• ") { "• $it" } + "\n\n" +
                "Would you like to grant them now?"
            )
            .setPositiveButton("Grant Permissions") { _, _ ->
                checkAllPermissionsAndStart()
            }
            .setNegativeButton("Later") { _, _ ->
                logEvent("⚠️ Permissions postponed")
                Toast.makeText(this, "App may not function properly", Toast.LENGTH_LONG).show()
            }
            .setCancelable(false)
            .show()
    }

    private fun showPermissionRationale(permissionType: String) {
        AlertDialog.Builder(this)
            .setTitle("$permissionType Permission Required")
            .setMessage(
                when (permissionType) {
                    "overlay" -> "The overlay permission allows the floating bubble to appear over other apps. This is essential for quickly capturing notes from Rhazes AI."
                    "notifications" -> "Notifications show you when text has been successfully sent to the PC. Without this, you won't get visual confirmation."
                    else -> "This permission is required for the app to function properly."
                }
            )
            .setPositiveButton("Grant Again") { _, _ ->
                checkAllPermissionsAndStart()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun checkAllPermissionsAndStart() {
        // Check overlay permission first
        if (!Settings.canDrawOverlays(this)) {
            showOverlayPermissionDialog()
            return
        }
        
        // Then check notification permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) 
                != PackageManager.PERMISSION_GRANTED) {
                requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        
        // All permissions granted, start service
        startService(Intent(this, OverlayService::class.java))
        binding.root.postDelayed({ syncServiceUI() }, 100)
    }

    private fun showOverlayPermissionDialog() {
        AlertDialog.Builder(this)
            .setTitle("Enable Overlay Permission")
            .setMessage(
                "To show the floating bubble, Ophtho Bridge needs permission to draw over other apps.\n\n" +
                "You'll be taken to Settings where you need to toggle on 'Allow display over other apps'."
            )
            .setPositiveButton("Go to Settings") { _, _ ->
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                overlayPermissionLauncher.launch(intent)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun onDataSent(preview: String, chunks: Int) {
        pulseStatusDot()
        
        // Flash the header
        val originalText = binding.tvTitle.text
        binding.tvTitle.text = "✓ Sent: $preview"
        binding.tvTitle.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
        binding.root.postDelayed({
            binding.tvTitle.text = originalText
            binding.tvTitle.setTextColor(ContextCompat.getColor(this, android.R.color.black))
        }, 2000)
        
        // Add to log
        logEvent("📤 Bubble sent: $chunks chunks ($preview)")
    }

    override fun onResume() {
        super.onResume()
        syncServiceUI()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(serviceReceiver)
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
        ObjectAnimator.ofFloat(binding.statusDot, "scaleX", 1f, 1.5f, 1f)
            .setDuration(300)
            .start()
        ObjectAnimator.ofFloat(binding.statusDot, "scaleY", 1f, 1.5f, 1f)
            .setDuration(300)
            .start()
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