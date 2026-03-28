package com.example.blksthpathplan

import android.util.Log
import org.zeromq.SocketType
import org.zeromq.ZContext
import org.zeromq.ZMQ
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class ZmqManager(private val listener: ZmqListener) {
    private var context: ZContext? = null
    private var socket: ZMQ.Socket? = null
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private var isRunning = false

    fun connect(url: String) {
        executor.execute {
            try {
                disconnectInternal()
                context = ZContext()
                // Menggunakan REQ (Request-Reply) atau PUB/SUB tergantung kebutuhan. 
                // Biasanya untuk kontrol robot sederhana bisa pakai REQ atau PUB.
                // Di sini kita pakai PUB untuk mengirim data mission.
                socket = context?.createSocket(SocketType.PUB)
                socket?.connect(url)
                isRunning = true
                listener.onConnected()
                
                // Jika ingin menerima data (SUB), butuh loop di thread terpisah.
                // Untuk sekarang kita fokus ke pengiriman dulu.
            } catch (e: Exception) {
                listener.onError(e.message ?: "ZMQ Connection Error")
            }
        }
    }

    fun sendMessage(topic: String, message: String) {
        executor.execute {
            try {
                socket?.let {
                    it.sendMore(topic)
                    it.send(message)
                }
            } catch (e: Exception) {
                listener.onError("Send failed: ${e.message}")
            }
        }
    }

    fun disconnect() {
        executor.execute {
            disconnectInternal()
            listener.onDisconnected()
        }
    }

    private fun disconnectInternal() {
        isRunning = false
        try {
            socket?.close()
            context?.close()
        } catch (e: Exception) {
            Log.e("ZmqManager", "Error during disconnect", e)
        }
        socket = null
        context = null
    }

    interface ZmqListener {
        fun onConnected()
        fun onDisconnected()
        fun onMessageReceived(topic: String, message: String)
        fun onError(error: String)
    }
}
