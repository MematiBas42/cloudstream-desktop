// @PortSource(file = "upstream/app/src/main/java/com/lagradost/cloudstream3/ui/result/ResultViewModel2.kt", upstreamCommit = "caeec18")
package com.lagradost.cloudstream3.ui.result

import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.actions.temp.fcast.PublicDeviceInfo
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

sealed class DetailsDialogEvent {
    data class DuplicateWarning(
        val titleRes: Int = R.string.duplicate_title,
        val message: String,
        val replaceMessageRes: Int,
        val duplicateIds: List<Int?>,
        val callback: (shouldContinue: Boolean, duplicateIds: List<Int?>) -> Unit,
    ) : DetailsDialogEvent()

    data class SelectPopupDialog(
        val popup: SelectPopup,
    ) : DetailsDialogEvent()

    data class SelectFcastDevice(
        val title: String,
        val devices: List<PublicDeviceInfo>,
        val callback: (device: PublicDeviceInfo?) -> Unit,
    ) : DetailsDialogEvent() {
        fun selectIndex(index: Int?) {
            callback(index?.let { devices.getOrNull(it) })
        }
    }

    companion object {
        private val _dialogEvents = MutableSharedFlow<DetailsDialogEvent>(extraBufferCapacity = 16)
        val dialogEvents: SharedFlow<DetailsDialogEvent> = _dialogEvents.asSharedFlow()

        fun postEvent(event: DetailsDialogEvent): Boolean {
            return _dialogEvents.tryEmit(event)
        }

        suspend fun emitEvent(event: DetailsDialogEvent) {
            _dialogEvents.emit(event)
        }
    }
}
