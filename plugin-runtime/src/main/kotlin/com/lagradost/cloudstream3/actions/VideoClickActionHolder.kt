// @PortSource(file = "upstream/app/src/main/java/com/lagradost/cloudstream3/actions/VideoClickAction.kt", upstreamCommit = "caeec18")
package com.lagradost.cloudstream3.actions

import android.app.Activity
import android.content.Context
import com.lagradost.api.Log
import com.lagradost.cloudstream3.actions.temp.CopyClipboardAction
import com.lagradost.cloudstream3.actions.temp.MpvExPackage
import com.lagradost.cloudstream3.actions.temp.MpvKtPackage
import com.lagradost.cloudstream3.actions.temp.MpvKtPreviewPackage
import com.lagradost.cloudstream3.actions.temp.MpvPackage
import com.lagradost.cloudstream3.actions.temp.MpvRxPackage
import com.lagradost.cloudstream3.actions.temp.MpvYTDLPackage
import com.lagradost.cloudstream3.actions.temp.PlayInBrowserAction
import com.lagradost.cloudstream3.actions.temp.PlayMirrorAction
import com.lagradost.cloudstream3.actions.temp.ViewM3U8Action
import com.lagradost.cloudstream3.actions.temp.VlcNightlyPackage
import com.lagradost.cloudstream3.actions.temp.VlcPackage
import com.lagradost.cloudstream3.actions.temp.fcast.FcastAction
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.utils.Coroutines.atomicListOf
import com.lagradost.cloudstream3.utils.UiText

object VideoClickActionHolder {
    val allVideoClickActions = atomicListOf(
        // Default
        PlayInBrowserAction(),
        CopyClipboardAction(),
        ViewM3U8Action(),
        PlayMirrorAction(),
        // main support external apps
        VlcPackage(),
        MpvPackage(),
        MpvExPackage(),
        FcastAction(),
        // forks/backup apps
        VlcNightlyPackage(),
        MpvYTDLPackage(),
        MpvKtPackage(),
        MpvKtPreviewPackage(),
        MpvRxPackage(),
        // Always Ask option
        AlwaysAskAction(),
        // added by plugins
        // ...
    )

    init {
        Log.d("VideoClickActionHolder", "allVideoClickActions: ${allVideoClickActions.map { it.uniqueId() }}")
    }

    private const val ACTION_ID_OFFSET = 1000

    fun makeOptionMap(activity: Activity?, video: ResultEpisode): List<Pair<UiText, Int>> = allVideoClickActions
        // We need to have index before filtering
        .mapIndexed { id, it -> it to id + ACTION_ID_OFFSET }
        .filter { it.first.shouldShowSafe(activity, video) }
        .map { it.first.name to it.second }

    fun getActionById(id: Int): VideoClickAction? = allVideoClickActions.getOrNull(id - ACTION_ID_OFFSET)

    fun getByUniqueId(uniqueId: String): VideoClickAction? = allVideoClickActions.firstOrNull { it.uniqueId() == uniqueId }

    fun uniqueIdToId(uniqueId: String?): Int? {
        if (uniqueId == null) return null
        return allVideoClickActions
            .mapIndexed { id, it -> it to id + ACTION_ID_OFFSET }
            .firstOrNull { it.first.uniqueId() == uniqueId }
            ?.second
    }

    fun getPlayers(context: Context? = null) = allVideoClickActions.filter { it.isPlayer && it.shouldShowSafe(context, null) }
}
