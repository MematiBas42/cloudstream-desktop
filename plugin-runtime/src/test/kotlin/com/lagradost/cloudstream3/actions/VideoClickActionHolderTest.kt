package com.lagradost.cloudstream3.actions

import android.content.Context
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.actions.temp.CopyClipboardAction
import com.lagradost.cloudstream3.actions.temp.MpvExPackage
import com.lagradost.cloudstream3.actions.temp.MpvPackage
import com.lagradost.cloudstream3.actions.temp.MpvYTDLPackage
import com.lagradost.cloudstream3.actions.temp.PlayInBrowserAction
import com.lagradost.cloudstream3.actions.temp.PlayMirrorAction
import com.lagradost.cloudstream3.actions.temp.ViewM3U8Action
import com.lagradost.cloudstream3.actions.temp.VlcNightlyPackage
import com.lagradost.cloudstream3.actions.temp.VlcPackage
import com.lagradost.cloudstream3.actions.temp.fcast.FcastAction
import com.lagradost.cloudstream3.ui.result.ACTION_PLAY_EPISODE_IN_PLAYER
import com.lagradost.cloudstream3.ui.result.EpisodeAdapter
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.common.storage.DesktopDataStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class VideoClickActionHolderTest {

    private val dummyEpisode = ResultEpisode(
        headerName = "Test Episode",
        name = "Episode 1",
        poster = null,
        episode = 1,
        season = 1,
        data = "https://example.com/ep1",
        id = 99999
    )

    private val context = Context()

    @BeforeEach
    fun setup() {
        DesktopDataStore.removeKey("player_default_key")
        PreferenceManager.getDefaultSharedPreferences(context).edit().remove("player_default_key").apply()
    }

    @Test
    fun testAllActionsRegisteredInOrder() {
        val actions = VideoClickActionHolder.allVideoClickActions
        assertTrue(actions.size >= 10, "Registry must contain at least 10 actions, was ${actions.size}")

        // Check relative upstream order
        val classes = actions.map { it::class.java.simpleName }
        val expectedSubset = listOf(
            PlayInBrowserAction::class.java.simpleName,
            CopyClipboardAction::class.java.simpleName,
            ViewM3U8Action::class.java.simpleName,
            PlayMirrorAction::class.java.simpleName,
            VlcPackage::class.java.simpleName,
            MpvPackage::class.java.simpleName,
            MpvExPackage::class.java.simpleName,
            FcastAction::class.java.simpleName,
            VlcNightlyPackage::class.java.simpleName,
            MpvYTDLPackage::class.java.simpleName,
            AlwaysAskAction::class.java.simpleName
        )

        var lastIndex = -1
        for (name in expectedSubset) {
            val idx = classes.indexOf(name)
            assertTrue(idx != -1, "Action $name must be present in registry")
            assertTrue(idx > lastIndex, "Action $name must maintain upstream order: $idx > $lastIndex")
            lastIndex = idx
        }
    }

    @Test
    fun testUniqueIdResolution() {
        val askAction = AlwaysAskAction()
        val id = VideoClickActionHolder.uniqueIdToId(askAction.uniqueId())
        assertNotNull(id, "uniqueIdToId must resolve AlwaysAskAction")
        assertTrue(id!! >= 1000, "Action ID must be >= 1000 offset")

        val action = VideoClickActionHolder.getActionById(id)
        assertNotNull(action, "getActionById must find action by ID")
        assertEquals(askAction.uniqueId(), action!!.uniqueId())

        val byUnique = VideoClickActionHolder.getByUniqueId(askAction.uniqueId())
        assertNotNull(byUnique)
        assertEquals(askAction.uniqueId(), byUnique!!.uniqueId())

        assertNull(VideoClickActionHolder.uniqueIdToId(null))
        assertNull(VideoClickActionHolder.uniqueIdToId("non_existent_unique_id"))
        assertNull(VideoClickActionHolder.getActionById(999))
    }

    @Test
    fun testGetPlayersIncludesPlayerActions() {
        val players = VideoClickActionHolder.getPlayers(context)
        assertTrue(players.isNotEmpty(), "getPlayers must not be empty")
        for (player in players) {
            assertTrue(player.isPlayer, "Every item returned by getPlayers must have isPlayer == true")
        }
        assertTrue(players.any { it is AlwaysAskAction }, "AlwaysAskAction must be in getPlayers when video == null")
    }

    @Test
    fun testMakeOptionMapIsNotEmptyForVideo() {
        val options = VideoClickActionHolder.makeOptionMap(null, dummyEpisode)
        assertTrue(options.isNotEmpty(), "makeOptionMap must return options for video episodes")
        // AlwaysAskAction should NOT be in episode click options because shouldShow(video != null) == false
        val askId = VideoClickActionHolder.uniqueIdToId(AlwaysAskAction().uniqueId())
        assertTrue(options.none { it.second == askId }, "AlwaysAskAction must not be in episode options")
    }

    @Test
    fun testEpisodeAdapterDefaultPlayerAction() {
        // 1. Default (no pref set) -> ACTION_PLAY_EPISODE_IN_PLAYER
        assertEquals(ACTION_PLAY_EPISODE_IN_PLAYER, EpisodeAdapter.getPlayerAction(context))

        // 2. "internal" or "" -> ACTION_PLAY_EPISODE_IN_PLAYER
        DesktopDataStore.setKey("player_default_key", "internal")
        assertEquals(ACTION_PLAY_EPISODE_IN_PLAYER, EpisodeAdapter.getPlayerAction(context))

        DesktopDataStore.setKey("player_default_key", "")
        assertEquals(ACTION_PLAY_EPISODE_IN_PLAYER, EpisodeAdapter.getPlayerAction(context))

        // 3. "ask" legacy alias -> AlwaysAskAction ID
        val askId = VideoClickActionHolder.uniqueIdToId(AlwaysAskAction().uniqueId())!!
        DesktopDataStore.setKey("player_default_key", "ask")
        assertEquals(askId, EpisodeAdapter.getPlayerAction(context))

        // 4. Exact uniqueId -> correct ID
        DesktopDataStore.setKey("player_default_key", AlwaysAskAction().uniqueId())
        assertEquals(askId, EpisodeAdapter.getPlayerAction(context))

        val vlcId = VideoClickActionHolder.uniqueIdToId(VlcPackage().uniqueId())!!
        DesktopDataStore.setKey("player_default_key", VlcPackage().uniqueId())
        assertEquals(vlcId, EpisodeAdapter.getPlayerAction(context))
    }

    @Test
    fun testEpisodeAdapterSharedPreferencesFallback() {
        // Verify fallback to SharedPreferences if DesktopDataStore has no key
        DesktopDataStore.removeKey("player_default_key")
        val askId = VideoClickActionHolder.uniqueIdToId(AlwaysAskAction().uniqueId())!!
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .putString("player_default_key", AlwaysAskAction().uniqueId())
            .apply()

        assertEquals(askId, EpisodeAdapter.getPlayerAction(context))
    }
}
