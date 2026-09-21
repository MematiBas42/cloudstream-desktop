package com.lagradost.player.ipc

import com.lagradost.common.logging.AppLogger
import com.lagradost.player.api.PlayerState
import com.lagradost.player.api.TrackInfo
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Native Java 16+ Unix Domain Socket IPC Client for MPV.
 * Communicates directly with MPV over AF_UNIX sockets with zero JNI/C overhead.
 */
class MpvIpcClient(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    @Volatile
    private var channel: SocketChannel? = null

    private var readJob: Job? = null
    private val writeLock = Any()
    private val onDisconnectListeners = CopyOnWriteArrayList<() -> Unit>()

    val isConnected: Boolean
        get() = channel?.isOpen == true

    fun addOnDisconnectListener(listener: () -> Unit) {
        onDisconnectListeners.add(listener)
    }

    fun removeOnDisconnectListener(listener: () -> Unit) {
        onDisconnectListeners.remove(listener)
    }

    /**
     * Connects to the MPV Unix Domain Socket at [socketPath].
     * Retries automatically while MPV initializes and binds the socket.
     * Supports Linux and Windows 10/11 x64 Unix Domain Sockets (AF_UNIX).
     */
    suspend fun connect(
        socketPath: Path,
        maxRetries: Int = 60,
        retryDelayMs: Long = 50,
    ): Boolean = withContext(Dispatchers.IO) {
        val normalized = socketPath.toAbsolutePath().normalize()
        val address = try {
            UnixDomainSocketAddress.of(normalized)
        } catch (e: Exception) {
            AppLogger.w("MpvIpcClient failed to resolve UnixDomainSocketAddress for $normalized: ${e.message}")
            return@withContext false
        }
        var connected = false
        var attempts = 0

        while (!connected && attempts < maxRetries && isActive) {
            attempts++
            var ch: SocketChannel? = null
            try {
                ch = SocketChannel.open(StandardProtocolFamily.UNIX)
                ch.connect(address)
                ch.configureBlocking(false)
                channel = ch
                connected = true
                AppLogger.i("MpvIpcClient connected to Unix domain socket: $normalized (attempt $attempts)")
            } catch (_: Exception) {
                runCatching { ch?.close() }
                delay(retryDelayMs)
            }
        }

        if (connected) {
            startReadingLoop()
            initializeSubscriptions()
        } else {
            AppLogger.w("MpvIpcClient failed to connect to socket $normalized after $attempts attempts")
        }
        connected
    }

    suspend fun connect(socketPath: String): Boolean = connect(resolveSocketPath(socketPath))
    suspend fun connect(socketFile: File): Boolean = connect(socketFile.toPath().toAbsolutePath().normalize())

    companion object {
        /**
         * Resolves and normalizes a socket path across Linux and Windows platforms.
         * Handles backslashes, forward slashes, and relative components cleanly.
         */
        fun resolveSocketPath(rawPath: String): Path {
            return File(rawPath).toPath().toAbsolutePath().normalize()
        }

        /**
         * Creates a [UnixDomainSocketAddress] from a given path after absolute normalization.
         */
        fun createSocketAddress(path: Path): UnixDomainSocketAddress {
            return UnixDomainSocketAddress.of(path.toAbsolutePath().normalize())
        }

        /**
         * Creates a [UnixDomainSocketAddress] from a raw path string after normalization.
         */
        fun createSocketAddress(rawPath: String): UnixDomainSocketAddress {
            return createSocketAddress(resolveSocketPath(rawPath))
        }
    }

    /**
     * Subscribes to key playback properties via MPV JSON-RPC observe_property.
     */
    fun initializeSubscriptions() {
        sendCommand(MpvJsonRpcProtocol.buildObservePropertyCommand(MpvJsonRpcProtocol.OBSERVE_ID_TIME_POS, MpvJsonRpcProtocol.PROP_TIME_POS))
        sendCommand(MpvJsonRpcProtocol.buildObservePropertyCommand(MpvJsonRpcProtocol.OBSERVE_ID_DURATION, MpvJsonRpcProtocol.PROP_DURATION))
        sendCommand(MpvJsonRpcProtocol.buildObservePropertyCommand(MpvJsonRpcProtocol.OBSERVE_ID_PAUSE, MpvJsonRpcProtocol.PROP_PAUSE))
        sendCommand(MpvJsonRpcProtocol.buildObservePropertyCommand(MpvJsonRpcProtocol.OBSERVE_ID_EOF_REACHED, MpvJsonRpcProtocol.PROP_EOF_REACHED))
        sendCommand(MpvJsonRpcProtocol.buildObservePropertyCommand(MpvJsonRpcProtocol.OBSERVE_ID_TRACK_LIST, MpvJsonRpcProtocol.PROP_TRACK_LIST))
        sendCommand(MpvJsonRpcProtocol.buildObservePropertyCommand(MpvJsonRpcProtocol.OBSERVE_ID_SUB_DELAY, MpvJsonRpcProtocol.PROP_SUB_DELAY))
        sendCommand(MpvJsonRpcProtocol.buildObservePropertyCommand(MpvJsonRpcProtocol.OBSERVE_ID_AID, MpvJsonRpcProtocol.PROP_AID))
        sendCommand(MpvJsonRpcProtocol.buildObservePropertyCommand(MpvJsonRpcProtocol.OBSERVE_ID_SID, MpvJsonRpcProtocol.PROP_SID))
        sendCommand(MpvJsonRpcProtocol.buildObservePropertyCommand(MpvJsonRpcProtocol.OBSERVE_ID_VIDEO_PARAMS_ASPECT, MpvJsonRpcProtocol.PROP_VIDEO_PARAMS_ASPECT))
        sendCommand(MpvJsonRpcProtocol.buildObservePropertyCommand(MpvJsonRpcProtocol.OBSERVE_ID_DWIDTH, MpvJsonRpcProtocol.PROP_DWIDTH))
        sendCommand(MpvJsonRpcProtocol.buildObservePropertyCommand(MpvJsonRpcProtocol.OBSERVE_ID_DHEIGHT, MpvJsonRpcProtocol.PROP_DHEIGHT))
        sendCommand(MpvJsonRpcProtocol.buildObservePropertyCommand(MpvJsonRpcProtocol.OBSERVE_ID_SUB_TEXT, MpvJsonRpcProtocol.PROP_SUB_TEXT))
    }

    private fun startReadingLoop() {
        val ch = channel ?: return
        readJob?.cancel()
        readJob = scope.launch(Dispatchers.IO) {
            val buffer = ByteBuffer.allocate(8192)
            val sb = StringBuilder()
            try {
                while (isActive && ch.isOpen) {
                    buffer.clear()
                    val bytesRead = ch.read(buffer)
                    if (bytesRead == -1) {
                        AppLogger.i("MpvIpcClient socket closed by remote MPV process (EOF)")
                        break
                    }
                    if (bytesRead > 0) {
                        buffer.flip()
                        val chunk = StandardCharsets.UTF_8.decode(buffer).toString()
                        sb.append(chunk)
                        while (true) {
                            val newlineIdx = sb.indexOf('\n')
                            if (newlineIdx == -1) break
                            val line = sb.substring(0, newlineIdx).trim()
                            sb.delete(0, newlineIdx + 1)
                            if (line.isNotEmpty()) {
                                processIpcLine(line)
                            }
                        }
                    } else {
                        // Non-blocking read returned 0 bytes: yield to avoid pegging CPU
                        delay(15)
                    }
                }
            } catch (e: Exception) {
                if (isActive && ch.isOpen) {
                    AppLogger.w("MpvIpcClient read loop encountered error: ${e.message}")
                }
            } finally {
                _state.update { it.copy(isPlaying = false, isFinished = true) }
                runCatching { ch.close() }
                channel = null
                onDisconnectListeners.forEach { runCatching { it.invoke() } }
            }
        }
    }

    fun processIpcLine(line: String) {
        val event = MpvJsonRpcProtocol.parseMessage(line) ?: return
        _state.update { current ->
            MpvJsonRpcProtocol.applyEvent(current, event)
        }
    }

    @Volatile
    var lastSentCommand: String? = null
        private set

    @Volatile
    var lastSentCommandList: List<Any>? = null
        private set

    fun sendCommand(jsonLine: String): Boolean {
        lastSentCommand = jsonLine.trim()
        val ch = channel ?: return false
        if (!ch.isOpen) return false
        return try {
            val payload = if (jsonLine.endsWith("\n")) jsonLine else "$jsonLine\n"
            val bytes = payload.toByteArray(StandardCharsets.UTF_8)
            val buf = ByteBuffer.wrap(bytes)
            synchronized(writeLock) {
                while (buf.hasRemaining()) {
                    ch.write(buf)
                }
            }
            true
        } catch (e: Exception) {
            AppLogger.w("MpvIpcClient failed to send command: ${e.message}")
            false
        }
    }

    fun sendCommand(command: List<Any>, requestId: Int? = null): Boolean {
        lastSentCommandList = command
        return sendCommand(MpvJsonRpcProtocol.formatCommand(command, requestId))
    }

    fun pause() {
        sendCommand(MpvJsonRpcProtocol.buildSetPropertyCommand(MpvJsonRpcProtocol.PROP_PAUSE, true))
        _state.update { it.copy(isPaused = true, isPlaying = false) }
    }

    fun play() {
        sendCommand(MpvJsonRpcProtocol.buildSetPropertyCommand(MpvJsonRpcProtocol.PROP_PAUSE, false))
        _state.update { it.copy(isPaused = false, isPlaying = !it.isFinished) }
    }

    fun resume() = play()

    fun togglePause() {
        val targetPause = !_state.value.isPaused
        sendCommand(MpvJsonRpcProtocol.buildSetPropertyCommand(MpvJsonRpcProtocol.PROP_PAUSE, targetPause))
        _state.update { it.copy(isPaused = targetPause, isPlaying = !targetPause && !it.isFinished) }
    }

    fun seek(posSec: Double, mode: String = "absolute") {
        sendCommand(MpvJsonRpcProtocol.buildSeekCommand(posSec, mode))
    }

    fun selectSubtitle(trackId: Int) {
        val value: Any = if (trackId <= 0) "no" else trackId
        sendCommand(MpvJsonRpcProtocol.buildSetPropertyCommand(MpvJsonRpcProtocol.PROP_SID, value))
    }

    fun selectAudio(trackId: Int) {
        val value: Any = if (trackId <= 0) "no" else trackId
        sendCommand(MpvJsonRpcProtocol.buildSetPropertyCommand(MpvJsonRpcProtocol.PROP_AID, value))
    }

    fun setSubtitleDelay(delaySec: Double) {
        val clamped = com.lagradost.player.tracks.MpvTrackManager.clampSubtitleDelay(delaySec)
        sendCommand(MpvJsonRpcProtocol.buildSetPropertyCommand(MpvJsonRpcProtocol.PROP_SUB_DELAY, clamped))
        _state.update { it.copy(subtitleDelaySec = clamped) }
    }

    fun adjustSubtitleDelay(deltaSec: Double) {
        val current = _state.value.subtitleDelaySec
        setSubtitleDelay(current + deltaSec)
    }

    fun resetSubtitleDelay() {
        setSubtitleDelay(0.0)
    }

    fun adjustVolume(delta: Double) {
        sendCommand(listOf("add", MpvJsonRpcProtocol.PROP_VOLUME, delta))
    }

    fun setVolume(volume: Double) {
        sendCommand(MpvJsonRpcProtocol.buildSetPropertyCommand(MpvJsonRpcProtocol.PROP_VOLUME, volume.coerceIn(0.0, 100.0)))
    }

    fun cycle(property: String) {
        sendCommand(MpvJsonRpcProtocol.buildCycleCommand(property))
    }

    fun cycleSubtitle() = cycle("sub")
    fun cycleAudio() = cycle("audio")

    fun quit() {
        sendCommand(MpvJsonRpcProtocol.buildQuitCommand())
    }

    fun resetState(initialUrl: String? = null) {
        _state.value = PlayerState(
            isPlaying = initialUrl != null,
            currentUrl = initialUrl,
        )
    }

    fun close() {
        readJob?.cancel()
        readJob = null
        try {
            channel?.close()
        } catch (e: Exception) {
            AppLogger.d("MpvIpcClient", "Channel close error: ${e.message}")
        }
        channel = null
    }

    fun disconnect() = close()
}
