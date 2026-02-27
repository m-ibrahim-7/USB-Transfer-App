package com.ophtho.usbtransfer

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.ophtho.usbtransfer.databinding.ActivityMainBinding
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.*
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        logEvent("App Started")

        // Initial check for shared text when app is first opened
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
    }

    /**
     * This ensures that if the app is already open, sharing a new note 
     * from another app will still trigger the transfer.
     */
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
                
                // Clear the intent data so rotating the screen doesn't re-send the same note
                intent.removeExtra(Intent.EXTRA_TEXT)
                intent.action = null
            }
        }
    }

    private fun sendToPC(content: String) {
        logEvent("Preparing to send...")
        thread {
            var socket: Socket? = null
            try {
                // Medical shorthand replacements
                val modifiedText = content
                    .replace("ejection fraction", "EF", ignoreCase = true)
                    .replace("Intraocular Pressure", "IOP", ignoreCase = true)

                logEvent("Connecting to 127.0.0.1:38300...")

                socket = Socket()
                // 2 second timeout - crucial for hospital Wi-Fi/USB stability
                socket.connect(InetSocketAddress("127.0.0.1", 38300), 2000)
                
                // Use UTF-8 explicitly to ensure medical symbols transfer correctly
                val writer = PrintWriter(socket.getOutputStream().bufferedWriter(Charsets.UTF_8), true)
                writer.println(modifiedText)
                writer.flush() 
                
                logEvent("Success! Data sent.")

                runOnUiThread {
                    Toast.makeText(this, "Sent: $modifiedText", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                logEvent("FAILED: ${e.localizedMessage}")
                runOnUiThread {
                    Toast.makeText(this, "Connection Failed", Toast.LENGTH_LONG).show()
                }
            } finally {
                try {
                    socket?.close()
                } catch (e: Exception) {
                    // Ignore close errors
                }
            }
        }
    }

    private fun logEvent(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val fullMessage = "[$timestamp] $message"
        
        android.util.Log.d("USB_TRANSFER", fullMessage)
        
        runOnUiThread {
            binding.logTextView.append("\n$fullMessage")
            // Optional: Auto-scroll if you have a ScrollView wrapping your logTextView
            // binding.logScrollView.fullScroll(View.FOCUS_DOWN)
        }
    }
}