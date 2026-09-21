// @PortSource(file = "upstream/app/src/main/java/com/lagradost/cloudstream3/actions/temp/fcast/FcastSession.kt", upstreamCommit = "caeec18")
package com.lagradost.cloudstream3.actions.temp.fcast

import android.util.Log
import androidx.annotation.WorkerThread
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.safefile.closeQuietly
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.DataOutputStream
import java.net.Socket
import kotlin.jvm.Throws

class FcastSession(private val hostAddress: String) : AutoCloseable {
    val tag = "FcastSession"

    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        ERROR
    }

    private var socket: Socket? = null
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()
    val isConnected: Boolean get() = _connectionState.value == ConnectionState.CONNECTED && socket?.isConnected == true && socket?.isClosed == false

    @Throws
    @WorkerThread
    fun open(): Socket {
        _connectionState.value = ConnectionState.CONNECTING
        try {
            val socket = Socket(hostAddress, FcastManager.TCP_PORT)
            this.socket = socket
            _connectionState.value = ConnectionState.CONNECTED
            return socket
        } catch (e: Throwable) {
            _connectionState.value = ConnectionState.ERROR
            throw e
        }
    }

    override fun close() {
        socket?.closeQuietly()
        socket = null
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    @Throws
    private fun acquireSocket(): Socket {
        val s = socket
        return if (s != null && s.isConnected && !s.isClosed) {
            s
        } else {
            close()
            open()
        }
    }

    fun ping() {
        sendMessage(Opcode.Ping, null)
    }

    fun play(message: PlayMessage) {
        sendMessage(Opcode.Play, message)
    }

    fun pause() {
        sendMessage(Opcode.Pause, null)
    }

    fun resume() {
        sendMessage(Opcode.Resume, null)
    }

    fun stop() {
        sendMessage(Opcode.Stop, null)
    }

    fun seek(time: Double) {
        sendMessage(Opcode.Seek, SeekMessage(time))
    }

    fun setSpeed(speed: Double) {
        sendMessage(Opcode.SetSpeed, SetSpeedMessage(speed))
    }

    fun setVolume(volume: Double) {
        sendMessage(Opcode.SetVolume, SetVolumeMessage(volume))
    }

    fun sendVersion() {
        sendMessage(Opcode.Version, null)
    }

    fun <T> sendMessage(opcode: Opcode, message: T) {
        ioSafe {
            try {
                val socket = acquireSocket()
                val outputStream = DataOutputStream(socket.getOutputStream())

                val packetBytes = serializePacket(opcode, message)

                Log.d(tag, "Sending message with size: ${packetBytes.size}, opcode: $opcode")
                outputStream.write(packetBytes)
                outputStream.flush()
            } catch (t: Throwable) {
                Log.e(tag, "Failed to send message: ${t.message}", t)
                _connectionState.value = ConnectionState.ERROR
                close()
                throw t
            }
        }
    }

    companion object {
        fun <T> serializePacket(opcode: Opcode, message: T): ByteArray {
            val json = message?.toJson()
            val content = json?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)

            // Little endian starting from 1
            // https://gitlab.com/futo-org/fcast/-/wikis/Protocol-version-1
            val size = content.size + 1

            val sizeArray = ByteArray(4) { num ->
                ((size shr (8 * num)) and 0xff).toByte()
            }

            val buffer = ByteArray(4 + 1 + content.size)
            System.arraycopy(sizeArray, 0, buffer, 0, 4)
            buffer[4] = opcode.value
            System.arraycopy(content, 0, buffer, 5, content.size)
            return buffer
        }
    }
}
