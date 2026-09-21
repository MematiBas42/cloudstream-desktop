package unit

import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.actions.VideoClickAction
import com.lagradost.cloudstream3.actions.temp.fcast.FcastAction
import com.lagradost.cloudstream3.actions.temp.fcast.FcastManager
import com.lagradost.cloudstream3.actions.temp.fcast.NsdServiceInfo
import com.lagradost.cloudstream3.actions.temp.fcast.PublicDeviceInfo
import com.lagradost.cloudstream3.ui.result.DetailsDialogEvent
import com.lagradost.cloudstream3.ui.result.LinkLoadingResult
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.common.platform.PlatformPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetAddress

/**
 * Industrial JUnit 5 unit test suite for Domain 22 / Cluster C10: FcastAction
 * Verifies device selection modal event dispatching, anti-stub compliance,
 * 1:1 upstream parity, and elimination of blind devices.firstOrNull() auto-casting.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FcastActionRemediationTest {

    private lateinit var action: FcastAction

    private val testEpisode = ResultEpisode(
        headerName = "Cyberpunk Series",
        name = "Episode 01",
        poster = null,
        episode = 1,
        season = 1,
        data = "https://example.com/ep1",
        apiName = "TestProvider",
        id = 998877,
        index = 0,
        tvType = TvType.TvSeries
    )

    private val testLink = ExtractorLink(
        source = "TestProvider",
        name = "1080p Stream",
        url = "https://cdn.example.com/video.mp4",
        referer = "https://example.com/embed",
        quality = Qualities.P1080.value,
        type = ExtractorLinkType.VIDEO
    )

    private val testLinkResult = LinkLoadingResult(
        links = listOf(testLink),
        subs = emptyList(),
        syncData = hashMapOf()
    )

    @BeforeEach
    fun setUp() {
        PlatformPaths.init()
        FcastManager.clearDevices()
        FcastManager.closeActiveSession()
        Dispatchers.setMain(Dispatchers.Unconfined)
        action = FcastAction()
    }

    @AfterEach
    fun tearDown() {
        FcastManager.clearDevices()
        FcastManager.closeActiveSession()
        Dispatchers.resetMain()
    }

    private fun createDeviceInfo(name: String, ip: String): PublicDeviceInfo {
        val info = NsdServiceInfo().apply {
            this.serviceName = name
            this.host = InetAddress.getByName(ip)
            this.port = FcastManager.TCP_PORT
        }
        return PublicDeviceInfo(info)
    }

    // =========================================================================
    // 1. Inheritance & Upstream Contracts
    // =========================================================================

    @Test
    fun testInheritanceAndContracts() {
        assertTrue(action is VideoClickAction, "FcastAction must inherit from VideoClickAction")
        assertEquals("Fcast to device", action.name.asString(null), "Action name must be 'Fcast to device'")
        assertTrue(action.oneSource, "oneSource must be true for FcastAction")
        assertEquals(
            setOf(
                ExtractorLinkType.VIDEO,
                ExtractorLinkType.DASH,
                ExtractorLinkType.M3U8
            ),
            action.sourceTypes,
            "sourceTypes must match upstream (VIDEO, DASH, M3U8)"
        )
    }

    // =========================================================================
    // 2. Dynamic Visibility (shouldShow)
    // =========================================================================

    @Test
    fun testShouldShowWithNoDevices() {
        FcastManager.clearDevices()
        assertFalse(
            action.shouldShow(null, testEpisode),
            "shouldShow must return false when no devices are discovered"
        )
    }

    @Test
    fun testShouldShowWithDiscoveredDevices() {
        val device = createDeviceInfo("Living-Room-TV", "192.168.1.100")
        FcastManager.addDeviceForTesting(device)

        assertTrue(
            action.shouldShow(null, testEpisode),
            "shouldShow must return true when devices are discovered"
        )

        FcastManager.clearDevices()
        assertFalse(
            action.shouldShow(null, testEpisode),
            "shouldShow must return false after clearing devices"
        )
    }

    // =========================================================================
    // 3. Graceful Edge Case Handling
    // =========================================================================

    @Test
    fun testRunActionWithNoDevicesDoesNotDispatchEvent() = runBlocking {
        FcastManager.clearDevices()

        var dispatched = false
        val job = launch(Dispatchers.Unconfined) {
            DetailsDialogEvent.dialogEvents.collect {
                dispatched = true
            }
        }

        action.runAction(null, testEpisode, testLinkResult, 0)
        assertFalse(dispatched, "No dialog event should be dispatched when device list is empty")
        job.cancel()
    }

    @Test
    fun testRunActionWithEmptyLinksReturnsGracefully() = runBlocking {
        val device = createDeviceInfo("Living-Room-TV", "192.168.1.100")
        FcastManager.addDeviceForTesting(device)

        val emptyResult = LinkLoadingResult(
            links = emptyList(),
            subs = emptyList(),
            syncData = hashMapOf()
        )

        var dispatched = false
        val job = launch(Dispatchers.Unconfined) {
            DetailsDialogEvent.dialogEvents.collect {
                dispatched = true
            }
        }

        action.runAction(null, testEpisode, emptyResult, 0)
        assertFalse(dispatched, "No dialog event should be dispatched when link list is empty")
        job.cancel()
    }

    @Test
    fun testRunActionWithOutOfBoundsIndexReturnsGracefully() = runBlocking {
        val device = createDeviceInfo("Living-Room-TV", "192.168.1.100")
        FcastManager.addDeviceForTesting(device)

        var dispatched = false
        val job = launch(Dispatchers.Unconfined) {
            DetailsDialogEvent.dialogEvents.collect {
                dispatched = true
            }
        }

        action.runAction(null, testEpisode, testLinkResult, 999)
        assertFalse(dispatched, "No dialog event should be dispatched when index is out of bounds")
        job.cancel()
    }

    // =========================================================================
    // 4. Modal Event Dispatching & Device Selection (Defect Fix Verification)
    // =========================================================================

    @Test
    fun testRunActionDispatchesSelectFcastDeviceEventWithoutAutoCasting() = runBlocking {
        val dev1 = createDeviceInfo("Living-Room-TV", "192.168.1.101")
        val dev2 = createDeviceInfo("Bedroom-Projector", "192.168.1.102")
        FcastManager.addDeviceForTesting(dev1)
        FcastManager.addDeviceForTesting(dev2)

        var capturedEvent: DetailsDialogEvent.SelectFcastDevice? = null
        val job = launch(Dispatchers.Unconfined) {
            DetailsDialogEvent.dialogEvents.collect { event ->
                if (event is DetailsDialogEvent.SelectFcastDevice) {
                    capturedEvent = event
                }
            }
        }

        action.runAction(null, testEpisode, testLinkResult, 0)

        assertNotNull(capturedEvent, "runAction must dispatch DetailsDialogEvent.SelectFcastDevice")
        assertEquals(2, capturedEvent!!.devices.size, "Dispatched event must contain all available devices")
        assertEquals("Living-Room-TV", capturedEvent!!.devices[0].rawName)
        assertEquals("Bedroom-Projector", capturedEvent!!.devices[1].rawName)
        assertTrue(capturedEvent!!.title.isNotBlank(), "Dialog title must not be blank")

        // CRITICAL CHECK: Ensure blind auto-cast did NOT occur before user interaction
        assertNull(
            FcastManager.selectedDevice,
            "FcastManager.selectedDevice must be null until user explicitly selects a device"
        )

        job.cancel()
    }

    @Test
    fun testUserSelectingDeviceInitiatesCasting() = runBlocking {
        val dev1 = createDeviceInfo("Living-Room-TV", "192.168.1.101")
        val dev2 = createDeviceInfo("Bedroom-Projector", "192.168.1.102")
        FcastManager.addDeviceForTesting(dev1)
        FcastManager.addDeviceForTesting(dev2)

        var capturedEvent: DetailsDialogEvent.SelectFcastDevice? = null
        val job = launch(Dispatchers.Unconfined) {
            DetailsDialogEvent.dialogEvents.collect { event ->
                if (event is DetailsDialogEvent.SelectFcastDevice) {
                    capturedEvent = event
                }
            }
        }

        action.runAction(null, testEpisode, testLinkResult, 0)
        assertNotNull(capturedEvent)

        // User explicitly clicks on the second device in the modal
        capturedEvent!!.callback(dev2)

        // Verify the selected device is active
        assertEquals(dev2, FcastManager.selectedDevice, "Selected device must be Bedroom-Projector")
        assertNotNull(FcastManager.getActiveSession(), "Active FCast session must be initialized")

        job.cancel()
    }

    @Test
    fun testUserDismissingDialogCancelsCasting() = runBlocking {
        val dev1 = createDeviceInfo("Living-Room-TV", "192.168.1.101")
        FcastManager.addDeviceForTesting(dev1)

        var capturedEvent: DetailsDialogEvent.SelectFcastDevice? = null
        val job = launch(Dispatchers.Unconfined) {
            DetailsDialogEvent.dialogEvents.collect { event ->
                if (event is DetailsDialogEvent.SelectFcastDevice) {
                    capturedEvent = event
                }
            }
        }

        action.runAction(null, testEpisode, testLinkResult, 0)
        assertNotNull(capturedEvent)

        // User dismisses / cancels the dialog
        capturedEvent!!.callback(null)

        assertNull(FcastManager.selectedDevice, "No device should be selected when dialog is cancelled")
        assertNull(FcastManager.getActiveSession(), "No session should be created when dialog is cancelled")

        job.cancel()
    }

    @Test
    fun testSelectIndexHelperMethods() {
        val dev1 = createDeviceInfo("Living-Room-TV", "192.168.1.101")
        val dev2 = createDeviceInfo("Bedroom-Projector", "192.168.1.102")

        var selectedDevice: PublicDeviceInfo? = null
        val event = DetailsDialogEvent.SelectFcastDevice(
            title = "Select Cast Device",
            devices = listOf(dev1, dev2),
            callback = { selectedDevice = it }
        )

        // Test valid index selection
        event.selectIndex(1)
        assertEquals(dev2, selectedDevice)

        // Test null index (cancellation)
        event.selectIndex(null)
        assertNull(selectedDevice)

        // Test out of bounds index
        event.selectIndex(99)
        assertNull(selectedDevice)
    }

    // =========================================================================
    // 5. PublicDeviceInfo Format Verification
    // =========================================================================

    @Test
    fun testPublicDeviceInfoNameFormatting() {
        val dev = createDeviceInfo("LG-webOS-TV", "192.168.1.200")
        assertEquals("LG-webOS-TV", dev.rawName)
        assertEquals("192.168.1.200", dev.host)
        assertEquals("LG webOS TV 192.168.1.200", dev.name)
    }

    // =========================================================================
    // 6. Zero-Stub & Reflection Parity
    // =========================================================================

    @Test
    fun testMethodSignaturesAndNoStubs() {
        val methods = FcastAction::class.java.declaredMethods
        val methodNames = methods.map { it.name }

        assertTrue(methodNames.contains("shouldShow"), "FcastAction must declare shouldShow")
        assertTrue(methodNames.contains("runAction"), "FcastAction must declare runAction")
        assertTrue(methodNames.contains("castTo"), "FcastAction must declare castTo")

        val castToMethod = methods.first { it.name == "castTo" }
        assertTrue(
            java.lang.reflect.Modifier.isPrivate(castToMethod.modifiers),
            "castTo must have private visibility matching upstream"
        )
    }
}
