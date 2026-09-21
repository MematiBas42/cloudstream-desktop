package unit

import com.lagradost.player.api.PlayerState
import com.lagradost.player.ipc.MpvIpcClient
import com.lagradost.player.ipc.MpvJsonRpcProtocol
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Unit tests for MPV JSON-RPC Protocol and IPC Client message parsing.
 * Feeds raw JSON-RPC event strings directly and verifies accurate PlayerState mutations.
 */
class MpvIpcProtocolTest {

    @Test
    fun testParseRawJsonRpcEvents() {
        // 1. time-pos event
        val timePosJson = """{"event":"property-change","id":1,"name":"time-pos","data":125.75}"""
        val timePosEvent = MpvJsonRpcProtocol.parseMessage(timePosJson)
        assertNotNull(timePosEvent)
        assertEquals("property-change", timePosEvent!!.event)
        assertEquals("time-pos", timePosEvent.name)
        assertEquals(125.75, MpvJsonRpcProtocol.parseDouble(timePosEvent.data))

        // 2. duration event
        val durationJson = """{"event":"property-change","id":2,"name":"duration","data":7200.0}"""
        val durationEvent = MpvJsonRpcProtocol.parseMessage(durationJson)
        assertNotNull(durationEvent)
        assertEquals("property-change", durationEvent!!.event)
        assertEquals("duration", durationEvent.name)
        assertEquals(7200.0, MpvJsonRpcProtocol.parseDouble(durationEvent.data))

        // 3. pause event (true and false)
        val pauseTrueJson = """{"event":"property-change","id":3,"name":"pause","data":true}"""
        val pauseTrueEvent = MpvJsonRpcProtocol.parseMessage(pauseTrueJson)
        assertNotNull(pauseTrueEvent)
        assertEquals(true, MpvJsonRpcProtocol.parseBoolean(pauseTrueEvent!!.data))

        val pauseFalseJson = """{"event":"property-change","id":3,"name":"pause","data":false}"""
        val pauseFalseEvent = MpvJsonRpcProtocol.parseMessage(pauseFalseJson)
        assertNotNull(pauseFalseEvent)
        assertEquals(false, MpvJsonRpcProtocol.parseBoolean(pauseFalseEvent!!.data))

        // 4. track-list event
        val trackListJson = """
        {
            "event": "property-change",
            "id": 5,
            "name": "track-list",
            "data": [
                {"id": 1, "type": "video", "selected": true},
                {"id": 2, "type": "audio", "title": "English 5.1", "lang": "eng", "selected": true},
                {"id": 3, "type": "sub", "title": "English SDH", "lang": "eng", "selected": false},
                {"id": 4, "type": "sub", "title": "Spanish", "lang": "spa", "selected": true}
            ]
        }
        """.trimIndent()
        val trackListEvent = MpvJsonRpcProtocol.parseMessage(trackListJson)
        assertNotNull(trackListEvent)
        assertEquals("track-list", trackListEvent!!.name)

        val tracks = MpvJsonRpcProtocol.parseTrackList(trackListEvent.data)
        assertEquals(4, tracks.size)
        assertEquals(1, tracks[0].id)
        assertEquals("video", tracks[0].type)
        assertTrue(tracks[0].selected)

        assertEquals(2, tracks[1].id)
        assertEquals("audio", tracks[1].type)
        assertEquals("English 5.1", tracks[1].title)
        assertEquals("eng", tracks[1].lang)
        assertTrue(tracks[1].selected)

        assertEquals(3, tracks[2].id)
        assertEquals("sub", tracks[2].type)
        assertEquals("English SDH", tracks[2].title)
        assertFalse(tracks[2].selected)

        assertEquals(4, tracks[3].id)
        assertEquals("sub", tracks[3].type)
        assertEquals("Spanish", tracks[3].title)
        assertTrue(tracks[3].selected)

        // 5. Edge cases: empty, whitespace, and corrupt json
        assertNull(MpvJsonRpcProtocol.parseMessage(""))
        assertNull(MpvJsonRpcProtocol.parseMessage("   "))
        assertNull(MpvJsonRpcProtocol.parseMessage("NOT_A_VALID_JSON{:::"))
    }

    @Test
    fun testPlayerStatePureReducerStateTransitions() {
        var state = PlayerState(
            currentUrl = "https://cdn.cloudstream.test/master.m3u8",
            isPlaying = false,
            isPaused = false
        )

        // Step 1: Duration received (300.0 seconds)
        val durationEvent = MpvJsonRpcProtocol.parseMessage(
            """{"event":"property-change","name":"duration","data":300.0}"""
        )!!
        state = MpvJsonRpcProtocol.applyEvent(state, durationEvent)

        assertEquals(300.0, state.durationSec)
        assertEquals(300000L, state.duration)
        assertFalse(state.isCompleted)

        // Step 2: Time-pos update (50.0 seconds)
        val timePosEvent1 = MpvJsonRpcProtocol.parseMessage(
            """{"event":"property-change","name":"time-pos","data":50.0}"""
        )!!
        state = MpvJsonRpcProtocol.applyEvent(state, timePosEvent1)

        assertEquals(50.0, state.positionSec)
        assertEquals(50000L, state.position)
        assertTrue(state.isPlaying)
        assertFalse(state.isPaused)
        assertFalse(state.isCompleted)

        // Step 3: User pauses playback
        val pauseEvent = MpvJsonRpcProtocol.parseMessage(
            """{"event":"property-change","name":"pause","data":true}"""
        )!!
        state = MpvJsonRpcProtocol.applyEvent(state, pauseEvent)

        assertTrue(state.isPaused)
        assertFalse(state.isPlaying)
        assertEquals(50.0, state.positionSec)

        // Step 4: User unpauses playback
        val unpauseEvent = MpvJsonRpcProtocol.parseMessage(
            """{"event":"property-change","name":"pause","data":false}"""
        )!!
        state = MpvJsonRpcProtocol.applyEvent(state, unpauseEvent)

        assertFalse(state.isPaused)
        assertTrue(state.isPlaying)

        // Step 5: Advance playback past 90% threshold (275.0s / 300.0s = 91.6%)
        val nearEndEvent = MpvJsonRpcProtocol.parseMessage(
            """{"event":"property-change","name":"time-pos","data":275.0}"""
        )!!
        state = MpvJsonRpcProtocol.applyEvent(state, nearEndEvent)

        assertEquals(275.0, state.positionSec)
        assertEquals(275000L, state.position)
        assertTrue(state.isCompleted, "Playback at >=90% must mark isCompleted = true")

        // Step 6: EOF reached
        val eofEvent = MpvJsonRpcProtocol.parseMessage(
            """{"event":"property-change","name":"eof-reached","data":true}"""
        )!!
        state = MpvJsonRpcProtocol.applyEvent(state, eofEvent)

        assertTrue(state.isFinished)
        assertTrue(state.isCompleted)
        assertFalse(state.isPlaying)

        // Step 7: end-file event with reason "eof"
        val endFileEvent = MpvJsonRpcProtocol.parseMessage(
            """{"event":"end-file","reason":"eof"}"""
        )!!
        val endState = MpvJsonRpcProtocol.applyEvent(state, endFileEvent)

        assertTrue(endState.isFinished)
        assertTrue(endState.isCompleted)
        assertFalse(endState.isPlaying)
    }

    @Test
    fun testMpvIpcClientProcessIpcLine() {
        val client = MpvIpcClient()
        client.resetState("https://example.com/video.mp4")

        // Feed duration
        client.processIpcLine("""{"event":"property-change","name":"duration","data":120.0}""")
        assertEquals(120.0, client.state.value.durationSec)
        assertEquals(120000L, client.state.value.duration)

        // Feed time-pos
        client.processIpcLine("""{"event":"property-change","name":"time-pos","data":45.5}""")
        assertEquals(45.5, client.state.value.positionSec)
        assertEquals(45500L, client.state.value.position)
        assertTrue(client.state.value.isPlaying)

        // Feed pause
        client.processIpcLine("""{"event":"property-change","name":"pause","data":true}""")
        assertTrue(client.state.value.isPaused)
        assertFalse(client.state.value.isPlaying)

        // Feed unpause via simple event name
        client.processIpcLine("""{"event":"unpause"}""")
        assertFalse(client.state.value.isPaused)
        assertTrue(client.state.value.isPlaying)

        // Feed track list
        val trackJson = """{"event":"property-change","name":"track-list","data":[{"id":1,"type":"audio","title":"Stereo","lang":"eng","selected":true}]}"""
        client.processIpcLine(trackJson)
        assertEquals(1, client.state.value.tracks.size)
        assertEquals("Stereo", client.state.value.tracks[0].title)

        client.close()
    }

    @Test
    fun testCommandBuildersProduceValidJsonRpcPayloads() {
        // Observe property
        val observeCmd = MpvJsonRpcProtocol.buildObservePropertyCommand(1, "time-pos", 42)
        assertTrue(observeCmd.contains(""""command":["observe_property",1,"time-pos"]"""))
        assertTrue(observeCmd.contains(""""request_id":42"""))

        // Set property
        val setCmd = MpvJsonRpcProtocol.buildSetPropertyCommand("pause", true, 99)
        assertTrue(setCmd.contains(""""command":["set_property","pause",true]"""))
        assertTrue(setCmd.contains(""""request_id":99"""))

        // Seek
        val seekCmd = MpvJsonRpcProtocol.buildSeekCommand(150.0, "absolute")
        assertTrue(seekCmd.contains(""""command":["seek",150.0,"absolute"]"""))

        // Cycle
        val cycleCmd = MpvJsonRpcProtocol.buildCycleCommand("sub")
        assertTrue(cycleCmd.contains(""""command":["cycle","sub"]"""))

        // Quit
        val quitCmd = MpvJsonRpcProtocol.buildQuitCommand(1)
        assertTrue(quitCmd.contains(""""command":["quit"]"""))

        // Loadfile
        val loadCmd = MpvJsonRpcProtocol.buildLoadFileCommand("https://video.mp4")
        assertTrue(loadCmd.contains(""""command":["loadfile","https://video.mp4","replace"]"""))
    }

    @Test
    fun testAspectDimensionsAndSubTextEvents() {
        var state = PlayerState()

        // 1. video-params/aspect event
        val aspectEvent = MpvJsonRpcProtocol.parseMessage(
            """{"event":"property-change","id":9,"name":"video-params/aspect","data":1.777778}"""
        )!!
        state = MpvJsonRpcProtocol.applyEvent(state, aspectEvent)
        assertEquals(1.777778, state.videoAspect)

        // 2. dwidth event
        val dwidthEvent = MpvJsonRpcProtocol.parseMessage(
            """{"event":"property-change","id":10,"name":"dwidth","data":1920}"""
        )!!
        state = MpvJsonRpcProtocol.applyEvent(state, dwidthEvent)
        assertEquals(1920, state.dwidth)

        // 3. dheight event
        val dheightEvent = MpvJsonRpcProtocol.parseMessage(
            """{"event":"property-change","id":11,"name":"dheight","data":1080}"""
        )!!
        state = MpvJsonRpcProtocol.applyEvent(state, dheightEvent)
        assertEquals(1080, state.dheight)

        // 4. sub-text event
        val subTextEvent = MpvJsonRpcProtocol.parseMessage(
            """{"event":"property-change","id":12,"name":"sub-text","data":"Live subtitle line"}"""
        )!!
        state = MpvJsonRpcProtocol.applyEvent(state, subTextEvent)
        assertEquals("Live subtitle line", state.currentSubText)
    }
}
