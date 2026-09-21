// @PortSource(file = "upstream/app/src/main/java/com/lagradost/cloudstream3/ui/result/EpisodeAdapter.kt", upstreamCommit = "caeec18")
package com.lagradost.cloudstream3.ui.result

import android.content.Context
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.actions.AlwaysAskAction
import com.lagradost.cloudstream3.actions.VideoClickActionHolder
import com.lagradost.cloudstream3.actions.temp.MpvPackage
import com.lagradost.cloudstream3.actions.temp.VlcPackage
import com.lagradost.common.storage.DesktopDataStore

const val TV_EP_SIZE = 400

class EpisodeAdapter {
    companion object {
        const val HAS_POSTER: Int = 0
        const val HAS_NO_POSTER: Int = 1

        fun getPlayerAction(context: Context): Int {
            val settingsManager = PreferenceManager.getDefaultSharedPreferences(context)
            val playerPref = DesktopDataStore.getKey<String>("player_default_key")
                ?: settingsManager.getString("player_default_key", null)

            val resolvedPref = when (playerPref) {
                "internal", "", null -> null
                "ask" -> AlwaysAskAction().uniqueId()
                "external" -> {
                    if (VlcPackage.isBinaryInPath("mpv")) {
                        MpvPackage().uniqueId()
                    } else if (VlcPackage.isBinaryInPath("vlc")) {
                        VlcPackage().uniqueId()
                    } else {
                        null
                    }
                }
                else -> playerPref
            }

            return VideoClickActionHolder.uniqueIdToId(resolvedPref) ?: ACTION_PLAY_EPISODE_IN_PLAYER
        }
    }
}
