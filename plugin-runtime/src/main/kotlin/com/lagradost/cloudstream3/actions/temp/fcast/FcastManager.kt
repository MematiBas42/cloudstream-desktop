// @PortSource(file = "upstream/app/src/main/java/com/lagradost/cloudstream3/actions/temp/fcast/FcastManager.kt", upstreamCommit = "caeec18")
package com.lagradost.cloudstream3.actions.temp.fcast

import android.content.Context
import android.os.Build
import android.util.Log
import com.lagradost.cloudstream3.mvvm.safe
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.common.logging.AppLogger
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.ServerSocket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor

class NsdServiceInfo {
    var serviceName: String = ""
    var serviceType: String = ""
    var port: Int = 0
    var host: InetAddress? = null
    var hostAddresses: List<InetAddress> = emptyList()
    var lastSeenMs: Long = System.currentTimeMillis()
    var attributes: Map<String, String> = emptyMap()

    override fun toString(): String {
        return "NsdServiceInfo(serviceName='$serviceName', serviceType='$serviceType', port=$port, host=$host, hostAddresses=$hostAddresses)"
    }
}

class NsdManager {
    interface DiscoveryListener {
        fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int)
        fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int)
        fun onDiscoveryStarted(serviceType: String?)
        fun onDiscoveryStopped(serviceType: String?)
        fun onServiceFound(serviceInfo: NsdServiceInfo?)
        fun onServiceLost(serviceInfo: NsdServiceInfo?)
    }

    interface RegistrationListener {
        fun onServiceRegistered(serviceInfo: NsdServiceInfo)
        fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int)
        fun onServiceUnregistered(serviceInfo: NsdServiceInfo)
        fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int)
    }

    interface ResolveListener {
        fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int)
        fun onServiceResolved(serviceInfo: NsdServiceInfo?)
    }

    interface ServiceInfoCallback {
        fun onServiceInfoCallbackRegistrationFailed(errorCode: Int)
        fun onServiceUpdated(serviceInfo: NsdServiceInfo)
        fun onServiceLost()
        fun onServiceInfoCallbackUnregistered()
    }

    companion object {
        const val PROTOCOL_DNS_SD = 1
        const val MDNS_MULTICAST_IP = "224.0.0.251"
        const val MDNS_PORT = 5353
        const val DEFAULT_STALE_TIMEOUT_MS = 15_000L
    }

    private var activeDiscoveryListener: DiscoveryListener? = null
    private var activeRegistrationListener: RegistrationListener? = null
    private var registeredServiceInfo: NsdServiceInfo? = null
    private var discoverySocket: DatagramSocket? = null
    private var receiverServer: ServerSocket? = null
    @Volatile
    private var isDiscovering = false
    private var discoveryThread: Thread? = null
    internal val discoveredServices = ConcurrentHashMap<String, NsdServiceInfo>()
    private val activeServiceCallbacks = ConcurrentHashMap<String, ServiceInfoCallback>()

    fun registerService(
        serviceInfo: NsdServiceInfo,
        protocolType: Int,
        listener: RegistrationListener
    ) {
        activeRegistrationListener = listener
        registeredServiceInfo = serviceInfo
        try {
            receiverServer = ServerSocket(serviceInfo.port)
            listener.onServiceRegistered(serviceInfo)
            AppLogger.i("NsdManager", "Registered service ${serviceInfo.serviceName} on port ${serviceInfo.port}")

            // Announce service via mDNS multicast (TTL = 120s)
            sendMdnsAnnouncement(serviceInfo, ttlSeconds = 120)
        } catch (e: Exception) {
            AppLogger.e("NsdManager", "Failed to register service ${serviceInfo.serviceName}", e)
            listener.onRegistrationFailed(serviceInfo, -1)
        }
    }

    fun unregisterService(listener: RegistrationListener) {
        try {
            val service = registeredServiceInfo
            if (service != null) {
                // Broadcast mDNS goodbye packet (TTL = 0) so remote peers immediately evict this device
                sendMdnsAnnouncement(service, ttlSeconds = 0)
                registeredServiceInfo = null
            }
            receiverServer?.close()
            receiverServer = null
            listener.onServiceUnregistered(NsdServiceInfo())
            AppLogger.i("NsdManager", "Unregistered service and broadcast mDNS goodbye")
        } catch (e: Exception) {
            AppLogger.e("NsdManager", "Failed to unregister service", e)
            listener.onUnregistrationFailed(NsdServiceInfo(), -1)
        }
    }

    fun discoverServices(
        serviceType: String,
        protocolType: Int,
        listener: DiscoveryListener
    ) {
        activeDiscoveryListener = listener
        listener.onDiscoveryStarted(serviceType)
        try {
            stopDiscoveryInternal()
            discoveredServices.clear()

            val socket = try {
                MulticastSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(MDNS_PORT))
                    try {
                        joinGroup(InetAddress.getByName(MDNS_MULTICAST_IP))
                    } catch (_: Throwable) {}
                    soTimeout = 2000
                }
            } catch (_: Throwable) {
                DatagramSocket().apply {
                    broadcast = true
                    soTimeout = 2000
                }
            }

            discoverySocket = socket
            isDiscovering = true

            // Send initial discovery query
            sendMdnsQuery(socket, serviceType)

            // Start asynchronous receive and stale eviction loop
            discoveryThread = Thread({
                var lastQueryTime = System.currentTimeMillis()
                val buffer = ByteArray(8192)

                while (isDiscovering && !socket.isClosed) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                        parseMdnsResponse(
                            data = packet.data,
                            length = packet.length,
                            senderAddress = packet.address,
                            onServiceFound = { info ->
                                discoveredServices[info.serviceName] = info
                                val callback = activeServiceCallbacks[info.serviceName]
                                if (callback != null) {
                                    callback.onServiceUpdated(info)
                                } else {
                                    listener.onServiceFound(info)
                                }
                            },
                            onServiceLost = { info ->
                                discoveredServices.remove(info.serviceName)
                                val callback = activeServiceCallbacks[info.serviceName]
                                callback?.onServiceLost()
                                listener.onServiceLost(info)
                            }
                        )
                    } catch (_: SocketTimeoutException) {
                        // Timeout on receive -> evict stale devices
                        evictStaleDevices(DEFAULT_STALE_TIMEOUT_MS, listener)

                        // Refresh query periodically (every 12 seconds)
                        val now = System.currentTimeMillis()
                        if (now - lastQueryTime >= 12_000L) {
                            sendMdnsQuery(socket, serviceType)
                            lastQueryTime = now
                        }
                    } catch (_: SocketException) {
                        break
                    } catch (e: Throwable) {
                        AppLogger.w("NsdManager", "mDNS receive loop error: ${e.message}")
                    }
                }
            }, "mDNS-Discovery-Worker").apply {
                isDaemon = true
                start()
            }

            AppLogger.d("NsdManager", "Started mDNS discovery worker for $serviceType")
        } catch (e: Exception) {
            AppLogger.e("NsdManager", "Discovery failed for $serviceType", e)
            listener.onStartDiscoveryFailed(serviceType, -1)
        }
    }

    fun stopServiceDiscovery(listener: DiscoveryListener) {
        try {
            stopDiscoveryInternal()

            // Evict all discovered services on discovery stop so offline devices do not linger
            val iterator = discoveredServices.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                iterator.remove()
                activeServiceCallbacks[entry.key]?.onServiceLost()
                listener.onServiceLost(entry.value)
            }

            listener.onDiscoveryStopped("_fcast._tcp")
            AppLogger.i("NsdManager", "Stopped mDNS discovery and evicted active devices")
        } catch (e: Exception) {
            AppLogger.e("NsdManager", "Failed to stop discovery", e)
            listener.onStopDiscoveryFailed("_fcast._tcp", -1)
        }
    }

    private fun stopDiscoveryInternal() {
        isDiscovering = false
        try {
            discoverySocket?.close()
        } catch (_: Throwable) {}
        discoverySocket = null
        discoveryThread?.interrupt()
        discoveryThread = null
    }

    fun evictStaleDevices(
        staleTimeoutMs: Long = DEFAULT_STALE_TIMEOUT_MS,
        listener: DiscoveryListener? = activeDiscoveryListener
    ) {
        val now = System.currentTimeMillis()
        val iterator = discoveredServices.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val serviceInfo = entry.value
            if (now - serviceInfo.lastSeenMs > staleTimeoutMs) {
                iterator.remove()
                AppLogger.d("NsdManager", "Evicting stale mDNS device: ${serviceInfo.serviceName}")
                activeServiceCallbacks[serviceInfo.serviceName]?.onServiceLost()
                listener?.onServiceLost(serviceInfo)
            }
        }
    }

    fun resolveService(serviceInfo: NsdServiceInfo, listener: ResolveListener) {
        if (serviceInfo.host != null) {
            listener.onServiceResolved(serviceInfo)
        } else {
            listener.onResolveFailed(serviceInfo, -1)
        }
    }

    fun registerServiceInfoCallback(
        serviceInfo: NsdServiceInfo,
        executor: Executor,
        callback: ServiceInfoCallback
    ) {
        activeServiceCallbacks[serviceInfo.serviceName] = callback
        executor.execute {
            callback.onServiceUpdated(serviceInfo)
        }
    }

    fun unregisterServiceInfoCallback(callback: ServiceInfoCallback) {
        activeServiceCallbacks.values.remove(callback)
        callback.onServiceInfoCallbackUnregistered()
    }

    private fun sendMdnsQuery(socket: DatagramSocket, serviceType: String) {
        try {
            val queryBytes = buildMdnsQuery(serviceType)
            val mdnsAddress = InetAddress.getByName(MDNS_MULTICAST_IP)
            val packet = DatagramPacket(queryBytes, queryBytes.size, mdnsAddress, MDNS_PORT)
            socket.send(packet)
            AppLogger.d("NsdManager", "Sent mDNS discovery query for $serviceType")
        } catch (e: Throwable) {
            AppLogger.w("NsdManager", "Failed to send mDNS query for $serviceType: ${e.message}")
        }
    }

    fun sendMdnsAnnouncement(serviceInfo: NsdServiceInfo, ttlSeconds: Int) {
        try {
            val bytes = buildMdnsResponse(
                serviceName = serviceInfo.serviceName,
                serviceType = if (serviceInfo.serviceType.isNotBlank()) serviceInfo.serviceType else "_fcast._tcp",
                port = if (serviceInfo.port > 0) serviceInfo.port else FcastManager.TCP_PORT,
                hostAddress = serviceInfo.host ?: InetAddress.getLocalHost(),
                ttlSeconds = ttlSeconds
            )
            DatagramSocket().use { socket ->
                val mdnsAddress = InetAddress.getByName(MDNS_MULTICAST_IP)
                val packet = DatagramPacket(bytes, bytes.size, mdnsAddress, MDNS_PORT)
                socket.send(packet)
            }
        } catch (e: Throwable) {
            AppLogger.w("NsdManager", "Failed to send mDNS announcement (ttl=$ttlSeconds): ${e.message}")
        }
    }

    fun buildMdnsQuery(serviceType: String): ByteArray {
        val fqdn = if (serviceType.endsWith(".local")) serviceType else "$serviceType.local"
        val buffer = ByteArrayOutputStream()
        val dos = DataOutputStream(buffer)

        // Header: ID=0, Flags=0, Questions=1, Answer=0, Auth=0, Add=0
        dos.writeShort(0x0000)
        dos.writeShort(0x0000)
        dos.writeShort(0x0001)
        dos.writeShort(0x0000)
        dos.writeShort(0x0000)
        dos.writeShort(0x0000)

        // Question: QNAME
        writeDnsName(dos, fqdn)

        // QTYPE = PTR (12), QCLASS = IN with unicast-response QU bit (0x8001)
        dos.writeShort(12)
        dos.writeShort(0x8001.toInt())

        return buffer.toByteArray()
    }

    fun buildMdnsResponse(
        serviceName: String,
        serviceType: String,
        port: Int,
        hostAddress: InetAddress,
        ttlSeconds: Int
    ): ByteArray {
        val buffer = ByteArrayOutputStream()
        val dos = DataOutputStream(buffer)

        // Header: ID=0, Flags=0x8400 (Response, Authoritative), Questions=0, Answers=2, Auth=0, Add=1
        dos.writeShort(0x0000)
        dos.writeShort(0x8400.toInt())
        dos.writeShort(0x0000)
        dos.writeShort(0x0002) // PTR + SRV answers
        dos.writeShort(0x0000)
        dos.writeShort(0x0001) // A record additional

        val serviceFqdn = if (serviceType.endsWith(".local")) serviceType else "$serviceType.local"
        val instanceFqdn = "$serviceName.$serviceFqdn"
        val hostFqdn = "${serviceName.replace(" ", "-")}.local"

        // Answer 1: PTR record (serviceType -> instanceFqdn)
        writeDnsName(dos, serviceFqdn)
        dos.writeShort(12) // Type PTR
        dos.writeShort(0x8001.toInt()) // Class IN + Flush
        dos.writeInt(ttlSeconds)
        val targetBuf = ByteArrayOutputStream()
        val targetDos = DataOutputStream(targetBuf)
        writeDnsName(targetDos, instanceFqdn)
        val targetBytes = targetBuf.toByteArray()
        dos.writeShort(targetBytes.size)
        dos.write(targetBytes)

        // Answer 2: SRV record (instanceFqdn -> port, hostFqdn)
        writeDnsName(dos, instanceFqdn)
        dos.writeShort(33) // Type SRV
        dos.writeShort(0x8001.toInt()) // Class IN + Flush
        dos.writeInt(ttlSeconds)
        val srvBuf = ByteArrayOutputStream()
        val srvDos = DataOutputStream(srvBuf)
        srvDos.writeShort(0) // Priority
        srvDos.writeShort(0) // Weight
        srvDos.writeShort(port)
        writeDnsName(srvDos, hostFqdn)
        val srvBytes = srvBuf.toByteArray()
        dos.writeShort(srvBytes.size)
        dos.write(srvBytes)

        // Additional 1: A record (hostFqdn -> IPv4)
        writeDnsName(dos, hostFqdn)
        dos.writeShort(1) // Type A
        dos.writeShort(0x8001.toInt()) // Class IN + Flush
        dos.writeInt(ttlSeconds)
        val ipBytes = hostAddress.address
        val ipv4 = if (ipBytes.size == 4) ipBytes else byteArrayOf(127, 0, 0, 1)
        dos.writeShort(ipv4.size)
        dos.write(ipv4)

        return buffer.toByteArray()
    }

    fun parseMdnsResponse(
        data: ByteArray,
        length: Int,
        senderAddress: InetAddress,
        onServiceFound: (NsdServiceInfo) -> Unit,
        onServiceLost: (NsdServiceInfo) -> Unit
    ) {
        if (length < 12) return

        val qdCount = ((data[4].toInt() and 0xFF) shl 8) or (data[5].toInt() and 0xFF)
        val anCount = ((data[6].toInt() and 0xFF) shl 8) or (data[7].toInt() and 0xFF)
        val nsCount = ((data[8].toInt() and 0xFF) shl 8) or (data[9].toInt() and 0xFF)
        val arCount = ((data[10].toInt() and 0xFF) shl 8) or (data[11].toInt() and 0xFF)
        val totalRecords = anCount + nsCount + arCount

        if (totalRecords == 0) return

        var offset = 12

        // Skip questions
        for (i in 0 until qdCount) {
            if (offset >= length) return
            val (_, nextOffset) = readDnsName(data, offset, length)
            offset = nextOffset + 4
            if (offset > length) return
        }

        var foundServiceName: String? = null
        var foundPort: Int = FcastManager.TCP_PORT
        var foundHost: InetAddress = senderAddress
        var foundTtl: Long = 120L

        // Parse Resource Records
        for (i in 0 until totalRecords) {
            if (offset >= length) break
            val (recordName, afterNameOffset) = readDnsName(data, offset, length)
            offset = afterNameOffset
            if (offset + 10 > length) break

            val type = ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
            val ttl = (((data[offset + 4].toLong() and 0xFF) shl 24) or
                    ((data[offset + 5].toLong() and 0xFF) shl 16) or
                    ((data[offset + 6].toLong() and 0xFF) shl 8) or
                    (data[offset + 7].toLong() and 0xFF))
            val rdLength = ((data[offset + 8].toInt() and 0xFF) shl 8) or (data[offset + 9].toInt() and 0xFF)
            offset += 10

            if (offset + rdLength > length) break
            val rdataOffset = offset

            when (type) {
                12 -> { // PTR
                    val (ptrTarget, _) = readDnsName(data, rdataOffset, length)
                    if (recordName.contains("_fcast._tcp", ignoreCase = true) || ptrTarget.contains("_fcast._tcp", ignoreCase = true)) {
                        val rawInstance = if (ptrTarget.contains("._fcast._tcp", ignoreCase = true)) {
                            ptrTarget.substringBefore("._fcast._tcp")
                        } else if (recordName.contains("._fcast._tcp", ignoreCase = true)) {
                            recordName.substringBefore("._fcast._tcp")
                        } else {
                            ptrTarget
                        }
                        foundServiceName = rawInstance.trim('.')
                        foundTtl = ttl
                    }
                }
                33 -> { // SRV
                    if (recordName.contains("_fcast._tcp", ignoreCase = true)) {
                        val rawInstance = recordName.substringBefore("._fcast._tcp")
                        foundServiceName = rawInstance.trim('.')
                        foundTtl = ttl
                        if (rdLength >= 6) {
                            foundPort = ((data[rdataOffset + 4].toInt() and 0xFF) shl 8) or (data[rdataOffset + 5].toInt() and 0xFF)
                        }
                    }
                }
                1 -> { // A (IPv4)
                    if (rdLength == 4) {
                        try {
                            val ipBytes = data.copyOfRange(rdataOffset, rdataOffset + 4)
                            foundHost = InetAddress.getByAddress(ipBytes)
                        } catch (_: Throwable) {}
                    }
                }
            }

            offset += rdLength
        }

        if (foundServiceName != null && foundServiceName.isNotBlank()) {
            val info = NsdServiceInfo().apply {
                this.serviceName = foundServiceName
                this.serviceType = "_fcast._tcp"
                this.port = foundPort
                this.host = foundHost
                this.hostAddresses = listOf(foundHost)
                this.lastSeenMs = System.currentTimeMillis()
            }

            if (foundTtl == 0L) {
                AppLogger.i("NsdManager", "Received mDNS goodbye packet for ${info.serviceName}")
                onServiceLost(info)
            } else {
                AppLogger.d("NsdManager", "Parsed mDNS service: ${info.serviceName} at ${foundHost.hostAddress}:$foundPort (TTL=$foundTtl)")
                onServiceFound(info)
            }
        }
    }

    private fun writeDnsName(dos: DataOutputStream, fqdn: String) {
        val normalized = if (fqdn.endsWith(".local")) fqdn else "$fqdn.local"
        val parts = normalized.trim('.').split('.')
        for (part in parts) {
            val bytes = part.toByteArray(Charsets.UTF_8)
            dos.writeByte(bytes.size)
            dos.write(bytes)
        }
        dos.writeByte(0x00)
    }

    fun readDnsName(buffer: ByteArray, startOffset: Int, maxLen: Int): Pair<String, Int> {
        var pos = startOffset
        val parts = mutableListOf<String>()
        var jumped = false
        var nextOffset = startOffset
        var jumpsCount = 0

        while (pos < maxLen && jumpsCount < 10) {
            val len = buffer[pos].toInt() and 0xFF
            if (len == 0) {
                if (!jumped) {
                    nextOffset = pos + 1
                }
                break
            }

            if ((len and 0xC0) == 0xC0) {
                // DNS pointer
                if (pos + 1 >= maxLen) break
                val pointerOffset = ((len and 0x3F) shl 8) or (buffer[pos + 1].toInt() and 0xFF)
                if (!jumped) {
                    nextOffset = pos + 2
                    jumped = true
                }
                pos = pointerOffset
                jumpsCount++
                continue
            }

            pos++
            if (pos + len > maxLen) break
            val label = String(buffer, pos, len, Charsets.UTF_8)
            parts.add(label)
            pos += len
            if (!jumped) {
                nextOffset = pos
            }
        }

        if (!jumped && pos < maxLen && (buffer[pos].toInt() and 0xFF) == 0) {
            nextOffset = pos + 1
        }

        return Pair(parts.joinToString("."), nextOffset)
    }
}

class FcastManager {
    private var nsdManager: NsdManager? = null

    // Used for receiver
    private val registrationListenerTcp = DefaultRegistrationListener()
    private var activeDiscoveryListener: DefaultDiscoveryListener? = null

    private fun getDeviceName(): String {
        return try {
            val hostname = InetAddress.getLocalHost().hostName
            if (hostname.isNullOrBlank()) "Linux-PC" else "Linux-$hostname"
        } catch (e: Throwable) {
            Log.w("FcastManager", "Failed to resolve hostname: ${e.message}")
            "Linux-Desktop"
        }
    }

    /**
     * Start the fcast service
     * @param registerReceiver If true will register the app as a compatible fcast receiver for discovery in other app
     */
    fun init(context: Context, registerReceiver: Boolean): Job = ioSafe {
        nsdManager = NsdManager()
        val serviceType = "_fcast._tcp"

        if (registerReceiver) {
            val serviceName = "$APP_PREFIX-${getDeviceName()}"

            val serviceInfo = NsdServiceInfo().apply {
                this.serviceName = serviceName
                this.serviceType = serviceType
                this.port = TCP_PORT
            }

            nsdManager?.registerService(
                serviceInfo,
                NsdManager.PROTOCOL_DNS_SD,
                registrationListenerTcp
            )
        }

        val listener = DefaultDiscoveryListener()
        activeDiscoveryListener = listener
        nsdManager?.discoverServices(
            serviceType,
            NsdManager.PROTOCOL_DNS_SD,
            listener
        )
    }

    fun stop() {
        nsdManager?.unregisterService(registrationListenerTcp)
        activeDiscoveryListener?.let {
            nsdManager?.stopServiceDiscovery(it)
        }
        activeDiscoveryListener = null
        closeActiveSession()
        clearDevices()
    }

    inner class DefaultDiscoveryListener : NsdManager.DiscoveryListener {
        val tag = "DiscoveryListener"
        override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
            Log.d(tag, "Discovery failed: $serviceType, error code: $errorCode")
        }

        override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {
            Log.d(tag, "Stop discovery failed: $serviceType, error code: $errorCode")
        }

        override fun onDiscoveryStarted(serviceType: String?) {
            Log.d(tag, "Discovery started: $serviceType")
        }

        override fun onDiscoveryStopped(serviceType: String?) {
            Log.d(tag, "Discovery stopped: $serviceType")
        }

        override fun onServiceFound(serviceInfo: NsdServiceInfo?) {
            safe {
                if (serviceInfo == null) return@safe

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    nsdManager?.registerServiceInfoCallback(
                        serviceInfo,
                        Runnable::run,
                        object : NsdManager.ServiceInfoCallback {
                            override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                                Log.e(tag, "Service registration failed: $errorCode")
                            }

                            override fun onServiceUpdated(serviceInfo: NsdServiceInfo) {
                                Log.d(
                                    tag,
                                    "Service updated: ${serviceInfo.serviceName}," +
                                            "Net: ${serviceInfo.hostAddresses.firstOrNull()?.hostAddress}"
                                )
                                synchronized(_currentDevices) {
                                    _currentDevices.removeIf { it.rawName == serviceInfo.serviceName }
                                    _currentDevices.add(PublicDeviceInfo(serviceInfo))
                                    _currentDevicesFlow.value = _currentDevices.toList()
                                }
                            }

                            override fun onServiceLost() {
                                Log.d(tag, "Service lost: ${serviceInfo.serviceName},")
                                synchronized(_currentDevices) {
                                    _currentDevices.removeIf { it.rawName == serviceInfo.serviceName }
                                    _currentDevicesFlow.value = _currentDevices.toList()
                                }
                            }

                            override fun onServiceInfoCallbackUnregistered() {}
                        })
                } else {
                    nsdManager?.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(
                            serviceInfo: NsdServiceInfo?,
                            errorCode: Int
                        ) {
                            Log.d(tag, "Resolve failed for ${serviceInfo?.serviceName}")
                        }

                        override fun onServiceResolved(serviceInfo: NsdServiceInfo?) {
                            if (serviceInfo == null) return

                            synchronized(_currentDevices) {
                                _currentDevices.removeIf { it.rawName == serviceInfo.serviceName }
                                _currentDevices.add(PublicDeviceInfo(serviceInfo))
                                _currentDevicesFlow.value = _currentDevices.toList()
                            }

                            Log.d(
                                tag,
                                "Service found: ${serviceInfo.serviceName}, Net: ${serviceInfo.host?.hostAddress}"
                            )
                        }
                    })
                }
            }
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo?) {
            if (serviceInfo == null) return

            // May remove duplicates, but net and port is null here, preventing device specific identification
            synchronized(_currentDevices) {
                _currentDevices.removeAll {
                    it.rawName == serviceInfo.serviceName
                }
                _currentDevicesFlow.value = _currentDevices.toList()
            }

            Log.d(tag, "Service lost: ${serviceInfo.serviceName}")
        }
    }

    companion object {
        const val APP_PREFIX = "CloudStream"
        private val _currentDevices: MutableList<PublicDeviceInfo> = mutableListOf()
        private val _currentDevicesFlow = MutableStateFlow<List<PublicDeviceInfo>>(emptyList())
        val currentDevicesFlow: StateFlow<List<PublicDeviceInfo>> = _currentDevicesFlow.asStateFlow()
        val currentDevices: List<PublicDeviceInfo> get() = _currentDevicesFlow.value

        var currentSession: FcastSession? = null
            private set

        var selectedDevice: PublicDeviceInfo? = null
            private set

        fun setActiveSession(session: FcastSession?, device: PublicDeviceInfo? = null) {
            if (currentSession != session) {
                currentSession?.close()
            }
            currentSession = session
            selectedDevice = device
            AppLogger.i("FcastManager", "Active FCast session set: device=${device?.name}, session=$session")
        }

        fun getActiveSession(): FcastSession? = currentSession

        fun closeActiveSession() {
            currentSession?.close()
            currentSession = null
            selectedDevice = null
        }

        fun clearDevices() {
            synchronized(_currentDevices) {
                _currentDevices.clear()
                _currentDevicesFlow.value = emptyList()
            }
        }

        fun addDeviceForTesting(device: PublicDeviceInfo) {
            synchronized(_currentDevices) {
                _currentDevices.removeIf { it.rawName == device.rawName }
                _currentDevices.add(device)
                _currentDevicesFlow.value = _currentDevices.toList()
            }
        }

        fun removeDeviceForTesting(rawName: String) {
            synchronized(_currentDevices) {
                _currentDevices.removeAll { it.rawName == rawName }
                _currentDevicesFlow.value = _currentDevices.toList()
            }
        }

        class DefaultRegistrationListener : NsdManager.RegistrationListener {
            val tag = "DiscoveryService"
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                Log.d(tag, "Service registered: ${serviceInfo.serviceName}")
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(tag, "Service registration failed: errorCode=$errorCode")
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                Log.d(tag, "Service unregistered: ${serviceInfo.serviceName}")
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(tag, "Service unregistration failed: errorCode=$errorCode")
            }
        }

        const val TCP_PORT = 46899
    }
}

class PublicDeviceInfo(serviceInfo: NsdServiceInfo) {
    val rawName: String = serviceInfo.serviceName
    val host: String? = serviceInfo.hostAddresses.firstOrNull()?.hostAddress ?: serviceInfo.host?.hostAddress
    val name: String = rawName.replace("-", " ") + (host?.let { " $it" } ?: "")

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PublicDeviceInfo) return false
        return rawName == other.rawName && host == other.host
    }

    override fun hashCode(): Int {
        var result = rawName.hashCode()
        result = 31 * result + (host?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String = "PublicDeviceInfo(name='$name', rawName='$rawName', host=$host)"
}
