package com.ophtho.usbtransfer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue

/**
 * DataBridgeService
 * ─────────────────
 * Runs as a FOREGROUND service so Android never kills the socket.
 * Listens on TCP port 12345 for the Python PC client (via `adb forward`).
 * Framing: 4-byte big-endian length prefix + UTF-8 payload.
 *
 * File location: app/src/main/java/com/ophtho/usbtransfer/DataBridgeService.kt
 */
class DataBridgeService : Service() {

    // ── Constants ─────────────────────────────────────────────────────────────
    companion object {
        const val PORT = 12345
        const val CHANNEL_ID = "DataBridgeChannel"
        const val NOTIF_ID = 1
        const val TAG = "DataBridgeService"

        // Intent actions
        const val ACTION_SEND = "com.ophtho.usbtransfer.ACTION_SEND"
        const val EXTRA_TEXT = "extra_text"

        // Broadcast back to MainActivity
        const val BROADCAST_STATUS = "com.ophtho.usbtransfer.STATUS"
        const val EXTRA_STATUS_MSG = "status_msg"
        const val EXTRA_CONNECTED = "connected"
    }

    // ── Binder (optional – for bound-service use) ─────────────────────────────
    inner class LocalBinder : Binder() {
        fun getService(): DataBridgeService = this@DataBridgeService
    }
    private val binder = LocalBinder()
    override fun onBind(intent: Intent): IBinder = binder

    // ── State ─────────────────────────────────────────────────────────────────
    private val sendQueue = LinkedBlockingQueue<String>(64)
    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var outputStream: OutputStream? = null
    private var isConnected = false

    @Volatile private var running = true

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("Waiting for PC connection…"))
        Thread(::serverLoop, "DataBridge-Server").start()
        Thread(::sendLoop, "DataBridge-Sender").start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_SEND) {
            val text = intent.getStringExtra(EXTRA_TEXT) ?: return START_STICKY
            enqueue(text)
        }
        return START_STICKY   // restart if killed
    }

    override fun onDestroy() {
        running = false
        serverSocket?.close()
        clientSocket?.close()
        super.onDestroy()
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun enqueue(text: String) {
        if (!sendQueue.offer(text)) {
            Log.w(TAG, "Queue full – dropping oldest message")
            sendQueue.poll()
            sendQueue.offer(text)
        }
        broadcast("Queued: ${text.take(40)}…", isConnected)
    }

    // ── Server loop ───────────────────────────────────────────────────────────

    private fun serverLoop() {
        while (running) {
            try {
                serverSocket = ServerSocket(PORT).also { ss ->
                    ss.reuseAddress = true
                    Log.i(TAG, "Listening on port $PORT")
                    broadcast("Listening on port $PORT", false)

                    val client = ss.accept()   // blocks until PC connects
                    clientSocket = client
                    outputStream = client.getOutputStream()
                    isConnected = true
                    updateNotification("PC Connected ✓")
                    broadcast("PC Connected", true)
                    Log.i(TAG, "PC client connected: ${client.remoteSocketAddress}")

                    // Keep alive: read drain (PC sends nothing, but detect close)
                    val input = client.getInputStream()
                    val buf = ByteArray(64)
                    while (running && client.isConnected) {
                        val n = input.read(buf)   // blocks; -1 = closed
                        if (n < 0) break
                    }
                }
            } catch (e: Exception) {
                if (running) Log.w(TAG, "Server socket error: ${e.message}")
            } finally {
                isConnected = false
                outputStream = null
                clientSocket?.close()
                clientSocket = null
                serverSocket?.close()
                serverSocket = null
                broadcast("PC Disconnected – waiting…", false)
                updateNotification("Waiting for PC connection…")
                Thread.sleep(1000)
            }
        }
    }

    // ── Send loop ─────────────────────────────────────────────────────────────

    private fun sendLoop() {
        while (running) {
            val text = sendQueue.take()   // blocks

            // Apply medical shorthand (mirrors MainActivity logic)
            val payload = text
                .replace("ejection fraction", "EF", ignoreCase = true)
                .replace("Intraocular Pressure", "IOP", ignoreCase = true)

            val bytes = payload.toByteArray(Charsets.UTF_8)
            val frame = ByteBuffer.allocate(4 + bytes.size).apply {
                putInt(bytes.size)      // big-endian length prefix
                put(bytes)
            }.array()

            val out = outputStream
            if (out == null) {
                // No client yet – re-queue
                sendQueue.put(text)
                broadcast("Not connected – message queued (${sendQueue.size})", false)
                Thread.sleep(2000)
                continue
            }

            try {
                out.write(frame)
                out.flush()
                broadcast("Sent: ${payload.take(50)}", true)
                Log.d(TAG, "Sent ${bytes.size} bytes")
            } catch (e: Exception) {
                Log.w(TAG, "Send failed: ${e.message}")
                // Re-queue for next connection
                sendQueue.put(text)
                broadcast("Send failed – queued for retry", false)
            }
        }
    }

    // ── Notification helpers ──────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "DataBridge Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Keeps the USB data bridge alive" }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("DataBridge")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .setOngoing(true)
            .build()

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    private fun broadcast(msg: String, connected: Boolean) {
        sendBroadcast(Intent(BROADCAST_STATUS).apply {
            putExtra(EXTRA_STATUS_MSG, msg)
            putExtra(EXTRA_CONNECTED, connected)
            setPackage(packageName)
        })
    }
}
