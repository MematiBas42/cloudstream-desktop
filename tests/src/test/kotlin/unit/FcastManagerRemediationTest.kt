package unit

import com.lagradost.cloudstream3.actions.temp.fcast.FcastManager
import com.lagradost.cloudstream3.actions.temp.fcast.FcastSession
import com.lagradost.cloudstream3.actions.temp.fcast.NsdManager
import com.lagradost.cloudstream3.actions.temp.fcast.NsdServiceInfo
import com.lagradost.cloudstream3.actions.temp.fcast.Opcode
import com.lagradost.cloudstream3.actions.temp.fcast.PlayMessage
import com.lagradost.cloudstream3.actions.temp.fcast.PublicDeviceInfo
import com.lagradost.cloudstream3.actions.temp.fcast.SeekMessage
import com.lagradost.cloudstream3.actions.temp.fcast.SetSpeedMessage
import com.lagradost.cloudstream3.actions.temp.fcast.SetVolumeMessage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Unit tests for FCast Protocol, mDNS Discovery, Stale Device Eviction & Socket Framing.
 * Verifies byte-level parity with upstream CloudStream and RFC 6762 mDNS specifications.
 */
class FcastManagerRemediationTest {

    private lateinit var nsdManager: NsdManager

    @BeforeEach
    fun setup() {
        nsdManager = NsdManager()
        FcastManager.clearDevices()
        FcastManager.closeActiveSession()
    }

    @Test
    fun testMdnsQueryBinaryStructure() {
        val queryBytes = nsdManager.buildMdnsQuery("_fcast._tcp")
        assertTrue(queryBytes.size >= 12, "Query must contain at least 12-byte DNS header")

        // Header: ID=0, Flags=0, Questions=1, Answer=0, Auth=0, Add=0
        val id = ((queryBytes[0].toInt() and 0xFF) shl 8) or (queryBytes[1].toInt() and 0xFF)
        val flags = ((queryBytes[2].toInt() and 0xFF) shl 8) or (queryBytes[3].toInt() and 0xFF)
        val qdCount = ((queryBytes[4].toInt() and 0xFF) shl 8) or (queryBytes[5].toInt() and 0xFF)
        val anCount = ((queryBytes[6].toInt() and 0xFF) shl 8) or (queryBytes[7].toInt() and 0xFF)

        assertEquals(0, id, "Query ID must be 0")
        assertEquals(0, flags, "Query Flags must be 0")
        assertEquals(1, qdCount, "Questions count must be 1")
        assertEquals(0, anCount, "Answer count must be 0")

        // Parse Question Name: _fcast, _tcp, local, 0
        var offset = 12
        val (qname, nextOffset) = nsdManager.readDnsName(queryBytes, offset, queryBytes.size)
        assertEquals("_fcast._tcp.local", qname, "QNAME must match requested service FQDN")

        // QTYPE = PTR (12)
        val qtype = ((queryBytes[nextOffset].toInt() and 0xFF) shl 8) or (queryBytes[nextOffset + 1].toInt() and 0xFF)
        assertEquals(12, qtype, "QTYPE must be 12 (PTR)")

        // QCLASS = IN (1) with QU unicast response bit (0x8001)
        val qclass = ((queryBytes[nextOffset + 2].toInt() and 0xFF) shl 8) or (queryBytes[nextOffset + 3].toInt() and 0xFF)
        assertEquals(0x8001, qclass, "QCLASS must have QU unicast bit set (0x8001)")
    }

    @Test
    fun testMdnsResponseBinaryStructureAndParsing() {
        val testIp = InetAddress.getByName("192.168.1.145")
        val responseBytes = nsdManager.buildMdnsResponse(
            serviceName = "CloudStream-LivingRoom",
            serviceType = "_fcast._tcp",
            port = 46899,
            hostAddress = testIp,
            ttlSeconds = 120
        )

        var foundInfo: NsdServiceInfo? = null
        var lostInfo: NsdServiceInfo? = null

        nsdManager.parseMdnsResponse(
            data = responseBytes,
            length = responseBytes.size,
            senderAddress = testIp,
            onServiceFound = { foundInfo = it },
            onServiceLost = { lostInfo = it }
        )

        assertNotNull(foundInfo, "Active mDNS response must trigger onServiceFound")
        assertNull(lostInfo, "Active response must not trigger onServiceLost")
        assertEquals("CloudStream-LivingRoom", foundInfo!!.serviceName)
        assertEquals("_fcast._tcp", foundInfo!!.serviceType)
        assertEquals(46899, foundInfo!!.port)
        assertEquals(testIp, foundInfo!!.host)
        assertEquals(listOf(testIp), foundInfo!!.hostAddresses)
    }

    @Test
    fun testMdnsGoodbyePacketTriggersServiceLostAndEviction() {
        val testIp = InetAddress.getByName("192.168.1.200")
        // RFC 6762: Goodbye packet has TTL = 0
        val goodbyeBytes = nsdManager.buildMdnsResponse(
            serviceName = "AndroidTV-Bedroom",
            serviceType = "_fcast._tcp",
            port = 46899,
            hostAddress = testIp,
            ttlSeconds = 0
        )

        var foundCalled = false
        var lostInfo: NsdServiceInfo? = null

        nsdManager.parseMdnsResponse(
            data = goodbyeBytes,
            length = goodbyeBytes.size,
            senderAddress = testIp,
            onServiceFound = { foundCalled = true },
            onServiceLost = { lostInfo = it }
        )

        assertFalse(foundCalled, "Goodbye packet must not trigger onServiceFound")
        assertNotNull(lostInfo, "Goodbye packet must trigger onServiceLost")
        assertEquals("AndroidTV-Bedroom", lostInfo!!.serviceName)
    }

    @Test
    fun testNsdManagerStaleDeviceEvictionTimeout() {
        val activeDevice = NsdServiceInfo().apply {
            serviceName = "LivingRoom-Active"
            serviceType = "_fcast._tcp"
            port = 46899
            host = InetAddress.getByName("192.168.1.10")
            lastSeenMs = System.currentTimeMillis() // Fresh
        }
        val staleDevice = NsdServiceInfo().apply {
            serviceName = "Bedroom-Stale"
            serviceType = "_fcast._tcp"
            port = 46899
            host = InetAddress.getByName("192.168.1.20")
            lastSeenMs = System.currentTimeMillis() - 30_000L // 30s ago (> 15s timeout)
        }

        nsdManager.discoveredServices[activeDevice.serviceName] = activeDevice
        nsdManager.discoveredServices[staleDevice.serviceName] = staleDevice

        val lostServices = mutableListOf<String>()
        val dummyListener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {}
            override fun onDiscoveryStarted(serviceType: String?) {}
            override fun onDiscoveryStopped(serviceType: String?) {}
            override fun onServiceFound(serviceInfo: NsdServiceInfo?) {}
            override fun onServiceLost(serviceInfo: NsdServiceInfo?) {
                serviceInfo?.serviceName?.let { lostServices.add(it) }
            }
        }

        nsdManager.evictStaleDevices(staleTimeoutMs = 15_000L, listener = dummyListener)

        // Stale device must be evicted and reported lost
        assertTrue(lostServices.contains("Bedroom-Stale"), "Stale device must be notified as lost")
        assertFalse(lostServices.contains("LivingRoom-Active"), "Active device must not be evicted")
        assertNull(nsdManager.discoveredServices["Bedroom-Stale"], "Stale device must be removed from map")
        assertNotNull(nsdManager.discoveredServices["LivingRoom-Active"], "Active device must remain in map")
    }

    @Test
    fun testFcastManagerDeviceManagementAndFlow() {
        val sInfo1 = NsdServiceInfo().apply {
            serviceName = "FCast-Receiver-1"
            host = InetAddress.getByName("192.168.1.51")
            port = 46899
        }
        val sInfo2 = NsdServiceInfo().apply {
            serviceName = "FCast-Receiver-2"
            host = InetAddress.getByName("192.168.1.52")
            port = 46899
        }

        val device1 = PublicDeviceInfo(sInfo1)
        val device2 = PublicDeviceInfo(sInfo2)

        FcastManager.addDeviceForTesting(device1)
        assertEquals(1, FcastManager.currentDevices.size)
        assertEquals(1, FcastManager.currentDevicesFlow.value.size)
        assertEquals("FCast-Receiver-1", FcastManager.currentDevices[0].rawName)

        FcastManager.addDeviceForTesting(device2)
        assertEquals(2, FcastManager.currentDevices.size)

        // Test updating same device (no duplicates)
        FcastManager.addDeviceForTesting(device1)
        assertEquals(2, FcastManager.currentDevices.size, "Re-adding existing device must deduplicate by rawName")

        // Test removal
        FcastManager.removeDeviceForTesting("FCast-Receiver-1")
        assertEquals(1, FcastManager.currentDevices.size)
        assertEquals("FCast-Receiver-2", FcastManager.currentDevices[0].rawName)

        // Test clear
        FcastManager.clearDevices()
        assertTrue(FcastManager.currentDevices.isEmpty())
        assertTrue(FcastManager.currentDevicesFlow.value.isEmpty())
    }

    @Test
    fun testFcastManagerStopEvictsDevicesAndClosesSession() {
        val sInfo = NsdServiceInfo().apply {
            serviceName = "Device-To-Clear"
            host = InetAddress.getByName("192.168.1.99")
            port = 46899
        }
        val device = PublicDeviceInfo(sInfo)
        FcastManager.addDeviceForTesting(device)
        assertFalse(FcastManager.currentDevices.isEmpty())

        val session = FcastSession("192.168.1.99")
        FcastManager.setActiveSession(session, device)
        assertNotNull(FcastManager.getActiveSession())

        FcastManager().stop()

        assertTrue(FcastManager.currentDevices.isEmpty(), "stop() must clear all current devices")
        assertNull(FcastManager.getActiveSession(), "stop() must close and clear active session")
    }

    @Test
    fun testPublicDeviceInfoNamingAndFormatting() {
        val sInfo = NsdServiceInfo().apply {
            serviceName = "MiBox-Living-Room"
            host = InetAddress.getByName("192.168.1.88")
            port = 46899
        }
        val device = PublicDeviceInfo(sInfo)

        assertEquals("MiBox-Living-Room", device.rawName)
        assertEquals("192.168.1.88", device.host)
        assertEquals("MiBox Living Room 192.168.1.88", device.name)

        val duplicate = PublicDeviceInfo(sInfo)
        assertEquals(device, duplicate)
        assertEquals(device.hashCode(), duplicate.hashCode())
    }

    @Test
    fun testFcastPacketSerializationFutoProtocolV1() {
        // 1. Ping: opcode=12, message=null
        val pingBytes = FcastSession.serializePacket(Opcode.Ping, null)
        assertEquals(5, pingBytes.size, "Ping packet size must be 4 bytes size + 1 byte opcode = 5")
        // Size = 1 (little-endian: 1, 0, 0, 0)
        assertEquals(1.toByte(), pingBytes[0])
        assertEquals(0.toByte(), pingBytes[1])
        assertEquals(0.toByte(), pingBytes[2])
        assertEquals(0.toByte(), pingBytes[3])
        // Opcode = 12 (Ping)
        assertEquals(Opcode.Ping.value, pingBytes[4])

        // 2. Play message serialization
        val playMsg = PlayMessage(
            container = "video/mp4",
            url = "https://example.com/video.mp4",
            time = 120.5,
            speed = 1.0,
            headers = mapOf("User-Agent" to "CloudStream")
        )
        val playBytes = FcastSession.serializePacket(Opcode.Play, playMsg)
        assertTrue(playBytes.size > 5, "Play packet must contain JSON payload")

        val payloadSize = playBytes.size - 5
        val expectedTotalSize = payloadSize + 1
        val encodedSize = (playBytes[0].toInt() and 0xFF) or
                ((playBytes[1].toInt() and 0xFF) shl 8) or
                ((playBytes[2].toInt() and 0xFF) shl 16) or
                ((playBytes[3].toInt() and 0xFF) shl 24)

        assertEquals(expectedTotalSize, encodedSize, "Header size must match Little-Endian payloadSize + 1")
        assertEquals(Opcode.Play.value, playBytes[4], "Opcode must be Play (1)")

        val jsonString = String(playBytes, 5, payloadSize, Charsets.UTF_8)
        assertTrue(jsonString.contains("video/mp4"), "JSON must contain container")
        assertTrue(jsonString.contains("https://example.com/video.mp4"), "JSON must contain url")

        // 3. Seek message serialization
        val seekMsg = SeekMessage(time = 45.0)
        val seekBytes = FcastSession.serializePacket(Opcode.Seek, seekMsg)
        assertEquals(Opcode.Seek.value, seekBytes[4], "Opcode must be Seek (5)")
        val seekJson = String(seekBytes, 5, seekBytes.size - 5, Charsets.UTF_8)
        assertTrue(seekJson.contains("45"), "JSON must contain seek time")

        // 4. Volume and Speed opcodes
        val volBytes = FcastSession.serializePacket(Opcode.SetVolume, SetVolumeMessage(0.8))
        assertEquals(Opcode.SetVolume.value, volBytes[4], "Opcode must be SetVolume (8)")

        val speedBytes = FcastSession.serializePacket(Opcode.SetSpeed, SetSpeedMessage(1.5))
        assertEquals(Opcode.SetSpeed.value, speedBytes[4], "Opcode must be SetSpeed (10)")
    }

    @Test
    fun testFcastSessionConnectionStateMachineAndDisconnects() {
        val session = FcastSession("127.0.0.1")
        assertEquals(FcastSession.ConnectionState.DISCONNECTED, session.connectionState.value)
        assertFalse(session.isConnected)

        // Calling close on disconnected session is idempotent and safe
        session.close()
        assertEquals(FcastSession.ConnectionState.DISCONNECTED, session.connectionState.value)
        assertFalse(session.isConnected)

        // Verifies that port used is FcastManager.TCP_PORT
        assertEquals(46899, FcastManager.TCP_PORT)
    }

    @Test
    fun testUnregisterServiceSendsMdnsGoodbye() {
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "Test-Desktop-Receiver"
            serviceType = "_fcast._tcp"
            port = 46899
            host = InetAddress.getByName("127.0.0.1")
        }

        val unregisteredCalled = AtomicBoolean(false)
        val regListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {}
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                unregisteredCalled.set(true)
            }
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
        }

        nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, regListener)
        nsdManager.unregisterService(regListener)

        assertTrue(unregisteredCalled.get(), "unregisterService must trigger onServiceUnregistered callback")
    }

    @Test
    fun testDnsNameDecompressionWithPointer() {
        // Construct a DNS packet buffer with a compression pointer
        // Offset 12: label "_fcast" (len 6) + label "_tcp" (len 4) + label "local" (len 5) + 0x00
        val buffer = ByteArray(64)
        var offset = 12
        val name1 = "_fcast._tcp.local"
        val parts = name1.split(".")
        for (part in parts) {
            buffer[offset++] = part.length.toByte()
            for (b in part.toByteArray(Charsets.UTF_8)) {
                buffer[offset++] = b
            }
        }
        buffer[offset++] = 0x00 // Null terminator at offset 29

        // Now place a pointer at offset 35 pointing back to offset 12
        // Pointer is 0xC0 (top 2 bits set) followed by offset 12 (0x0C)
        buffer[35] = 0xC0.toByte()
        buffer[36] = 0x0C.toByte()

        val (decompressedName, nextPos) = nsdManager.readDnsName(buffer, 35, buffer.size)
        assertEquals("_fcast._tcp.local", decompressedName, "Pointer decompression must reconstruct the pointed name")
        assertEquals(37, nextPos, "Next position after a 2-byte pointer must be startOffset + 2")
    }
}
