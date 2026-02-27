package com.ophtho.usbtransfer

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.ophtho.usbtransfer.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var isLogVisible = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        logEvent("App Started")

        // Handle Share Intent
        handleIntent(intent)

        // Manual send button
        binding.btnSend.setOnClickListener {
            val textToSend = binding.editNote.text.toString()
            if (textToSend.isNotEmpty()) {
                sendToPC(textToSend)
            } else {
                logEvent("Error: Nothing to send")
            }
        }

        // Toggle Debug Window
        binding.btnToggleLog.setOnClickListener {
            isLogVisible = !isLogVisible
            binding.logContainer.visibility = if (isLogVisible) View.VISIBLE else View.GONE
            binding.btnToggleLog.text = if (isLogVisible) "Hide Debug" else "Show Debug"
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            sharedText?.let {
                binding.editNote.setText(it)
                sendToPC(it)
                intent.removeExtra(Intent.EXTRA_TEXT)
                intent.action = null
            }
        }
    }

    private fun sendToPC(content: String) {
        // Medical shorthand replacements
        val modifiedText = content
            .replace("ejection fraction", "EF", ignoreCase = true)
            .replace("Intraocular Pressure", "IOP", ignoreCase = true)

        // LOGCAT TRANSMISSION: Bypasses UsbFfs socket issues
        android.util.Log.d("CLINIC_DATA", "START_DATA|$modifiedText|END_DATA")
        
        logEvent("Data shouted to Logcat...")
        Toast.makeText(this, "Sent to PC", Toast.LENGTH_SHORT).show()
    }

    private fun logEvent(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val fullMessage = "[$timestamp] $message"
        runOnUiThread {
            binding.logTextView.append("\n$fullMessage")
        }
    }
}