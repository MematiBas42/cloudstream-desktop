// @PortSource(file = "upstream/app/src/main/java/com/lagradost/cloudstream3/actions/AlwaysAskAction.kt", upstreamCommit = "caeec18")
package com.lagradost.cloudstream3.actions

import android.content.Context
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.ui.result.LinkLoadingResult
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.utils.txt

class AlwaysAskAction : VideoClickAction() {
    override val name = txt(R.string.player_settings_always_ask)
    override val isPlayer = true

    override fun shouldShow(context: Context?, video: ResultEpisode?): Boolean = video == null

    override suspend fun runAction(
        context: Context?,
        video: ResultEpisode,
        result: LinkLoadingResult,
        index: Int?
    ) {
        throw IllegalStateException("AlwaysAskAction is handled specially by the calling code and cannot be executed directly")
    }
}
