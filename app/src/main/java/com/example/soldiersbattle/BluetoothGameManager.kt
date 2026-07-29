package com.example.soldiersbattle

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.os.Handler
import android.os.Looper
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Handles a single Bluetooth Classic (RFCOMM) connection between two phones.
 * One side calls startServer() (host), the other calls connectToDevice() (client).
 * Once connected, both sides can send/receive newline-terminated text messages
 * using the same simple protocol (see GameView.kt for the message format).
 */
class BluetoothGameManager(private val listener: Listener) {

    companion object {
        // Standard Serial Port Profile UUID - works for simple 2-way socket games.
        val APP_UUID: UUID = UUID.fromString("8ce255c0-200a-11e0-ac64-0800200c9a66")
    }

    interface Listener {
        fun onConnected(deviceName: String)
        fun onMessageReceived(message: String)
        fun onConnectionFailed(reason: String)
        fun onConnectionLost()
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private var acceptThread: AcceptThread? = null
    private var connectThread: ConnectThread? = null
    private var connectedThread: ConnectedThread? = null

    @Volatile private var isStopped = false

    // ---------------- Host (server) side ----------------
    @Synchronized
    fun startServer(adapter: BluetoothAdapter) {
        stop()
        isStopped = false
        acceptThread = AcceptThread(adapter)
        acceptThread?.start()
    }

    // ---------------- Client side ----------------
    @Synchronized
    fun connectToDevice(adapter: BluetoothAdapter, device: BluetoothDevice) {
        stop()
        isStopped = false
        connectThread = ConnectThread(device)
        connectThread?.start()
    }

    fun send(message: String) {
        connectedThread?.write((message + "\n").toByteArray())
    }

    @Synchronized
    fun stop() {
        isStopped = true
        acceptThread?.cancel()
        connectThread?.cancel()
        connectedThread?.cancel()
        acceptThread = null
        connectThread = null
        connectedThread = null
    }

    private fun manageConnectedSocket(socket: BluetoothSocket, remoteName: String) {
        connectedThread = ConnectedThread(socket)
        connectedThread?.start()
        mainHandler.post { listener.onConnected(remoteName) }
    }

    // Thread that listens for an incoming connection (host side).
    private inner class AcceptThread(adapter: BluetoothAdapter) : Thread() {
        private var serverSocket: BluetoothServerSocket? = try {
            adapter.listenUsingRfcommWithServiceRecord("SoldiersBattle", APP_UUID)
        } catch (e: SecurityException) {
            null
        } catch (e: IOException) {
            null
        }

        override fun run() {
            if (serverSocket == null) {
                mainHandler.post { listener.onConnectionFailed("تعذّر فتح مقبس البلوتوث (تحقق من الصلاحيات)") }
                return
            }
            try {
                val socket = serverSocket!!.accept() // blocks until a client connects
                if (!isStopped) {
                    val name = try { socket.remoteDevice.name ?: "الصديق" } catch (e: SecurityException) { "الصديق" }
                    manageConnectedSocket(socket, name)
                }
            } catch (e: IOException) {
                if (!isStopped) {
                    mainHandler.post { listener.onConnectionFailed("فشل قبول الاتصال: ${e.message}") }
                }
            } finally {
                try { serverSocket?.close() } catch (e: IOException) { }
            }
        }

        fun cancel() {
            try { serverSocket?.close() } catch (e: IOException) { }
        }
    }

    // Thread that connects out to a chosen paired device (client side).
    private inner class ConnectThread(private val device: BluetoothDevice) : Thread() {
        private var socket: BluetoothSocket? = try {
            device.createRfcommSocketToServiceRecord(APP_UUID)
        } catch (e: SecurityException) {
            null
        } catch (e: IOException) {
            null
        }

        override fun run() {
            if (socket == null) {
                mainHandler.post { listener.onConnectionFailed("تعذّر إنشاء الاتصال (تحقق من الصلاحيات)") }
                return
            }
            try {
                socket!!.connect()
                val name = try { device.name ?: "المضيف" } catch (e: SecurityException) { "المضيف" }
                manageConnectedSocket(socket!!, name)
            } catch (e: IOException) {
                mainHandler.post { listener.onConnectionFailed("فشل الاتصال بالمضيف: ${e.message}") }
                try { socket?.close() } catch (e2: IOException) { }
            }
        }

        fun cancel() {
            try { socket?.close() } catch (e: IOException) { }
        }
    }

    // Thread that continuously reads incoming messages once connected.
    private inner class ConnectedThread(private val socket: BluetoothSocket) : Thread() {
        private val input: InputStream? = try { socket.inputStream } catch (e: IOException) { null }
        private val output: OutputStream? = try { socket.outputStream } catch (e: IOException) { null }

        override fun run() {
            val buffer = ByteArray(1024)
            val lineBuilder = StringBuilder()
            while (!isStopped) {
                try {
                    val bytesRead = input?.read(buffer) ?: -1
                    if (bytesRead == -1) break
                    val chunk = String(buffer, 0, bytesRead)
                    for (ch in chunk) {
                        if (ch == '\n') {
                            val msg = lineBuilder.toString()
                            lineBuilder.clear()
                            if (msg.isNotEmpty()) {
                                mainHandler.post { listener.onMessageReceived(msg) }
                            }
                        } else {
                            lineBuilder.append(ch)
                        }
                    }
                } catch (e: IOException) {
                    if (!isStopped) mainHandler.post { listener.onConnectionLost() }
                    break
                }
            }
        }

        fun write(bytes: ByteArray) {
            try {
                output?.write(bytes)
            } catch (e: IOException) {
                mainHandler.post { listener.onConnectionLost() }
            }
        }

        fun cancel() {
            try { socket.close() } catch (e: IOException) { }
        }
    }
}
