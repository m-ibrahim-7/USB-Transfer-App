package com.ophtho.usbtransfer

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.ophtho.usbtransfer.databinding.ActivityMainBinding
import java.io.PrintWriter
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.*
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // This connects the Kotlin code to your activity_main.xml layout
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        logEvent("App Started")

        // Handle text shared from other apps
        if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (sharedText != null) {
                sendToPC(sharedText)
            }
        }
    }

    private fun sendToPC(content: String) {
        logEvent("Preparing to send data...")
        thread {
            try {
                // Medical shorthand replacements
                var modifiedText = content.replace("ejection fraction", "EF", ignoreCase = true)
                modifiedText = modifiedText.replace("Intraocular Pressure", "IOP", ignoreCase = true)

                logEvent("Connecting to PC (127.0.0.1:38300)...")
                
                val socket = Socket("127.0.0.1", 38300)
                val writer = PrintWriter(socket.getOutputStream(), true)
                writer.println(modifiedText)
                socket.close()

                logEvent("Success! Data sent.")

                runOnUiThread {
                    Toast.makeText(this, "Sent: $modifiedText", Toast.LENGTH_SHORT).show()
                    // finishAndRemoveTask() // Optional: closes app after sending
                }
            } catch (e: Exception) {
                logEvent("FAILED: ${e.message}")
                runOnUiThread {
                    Toast.makeText(this, "Connection Failed", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun logEvent(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val fullMessage = "[$timestamp] $message"
        
        // Log to Android system
        android.util.Log.d("USB_TRANSFER", fullMessage)
        
        // Log to the phone screen
        runOnUiThread {
            binding.logTextView.append("\n$fullMessage")
        }
    }
}