// @PortSource(file = "upstream/app/src/main/java/com/lagradost/cloudstream3/actions/temp/fcast/FcastAction.kt", upstreamCommit = "caeec18")
package com.lagradost.cloudstream3.actions.temp.fcast

import android.content.Context
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getActivity
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.actions.VideoClickAction
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.ui.result.DetailsDialogEvent
import com.lagradost.cloudstream3.ui.result.LinkLoadingResult
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.utils.DataStoreHelper.getViewPos
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.txt
import com.lagradost.common.logging.AppLogger
import java.util.concurrent.Callable

class FcastAction : VideoClickAction() {
    override val name = txt("Fcast to device")

    override val oneSource = true

    override val sourceTypes = setOf(
        ExtractorLinkType.VIDEO,
        ExtractorLinkType.DASH,
        ExtractorLinkType.M3U8
    )

    override fun shouldShow(context: Context?, video: ResultEpisode?): Boolean = FcastManager.currentDevices.isNotEmpty()

    override suspend fun runAction(
        context: Context?,
        video: ResultEpisode,
        result: LinkLoadingResult,
        index: Int?
    ) {
        val link = result.links.getOrNull(index ?: 0) ?: return
        val devices = FcastManager.currentDevices.toList()

        if (devices.isEmpty()) {
            AppLogger.w("FcastAction", "No FCast devices available")
            showToast(context?.getActivity(), "No FCast devices found on network")
            return
        }

        uiThread(Callable {
            val title = txt(R.string.player_settings_select_cast_device).asStringNull(context)
                ?: "Select Cast Device"
            val event = DetailsDialogEvent.SelectFcastDevice(
                title = title,
                devices = devices
            ) { selectedDevice ->
                if (selectedDevice != null) {
                    val position = getViewPos(video.id)?.position
                    castTo(selectedDevice, link, position)
                    showToast(context?.getActivity(), "Casting to ${selectedDevice.name}")
                }
            }
            DetailsDialogEvent.postEvent(event)
        })
    }

    private fun castTo(device: PublicDeviceInfo?, link: ExtractorLink, position: Long?) {
        val host = device?.host ?: run {
            AppLogger.w("FcastAction", "Cannot cast: device host is null")
            showToast(null, "FCast device address unavailable")
            return
        }

        try {
            val session = FcastSession(host)
            FcastManager.setActiveSession(session, device)
            session.sendMessage(
                Opcode.Play,
                PlayMessage(
                    container = link.type.getMimeType(),
                    url = link.url,
                    content = null,
                    time = position?.let { it / 1000.0 },
                    speed = 1.0,
                    headers = mapOf(
                        "referer" to link.referer,
                        "user-agent" to USER_AGENT
                    ) + link.headers
                )
            )
            AppLogger.i("FcastAction", "Successfully initiated cast to $host for link: ${link.name}")
        } catch (e: Throwable) {
            logError(e)
            AppLogger.e("FcastAction", "Failed to cast to $host: ${e.message}", e)
            showToast(null, "Failed to cast to ${device.name}")
        }
    }
}
