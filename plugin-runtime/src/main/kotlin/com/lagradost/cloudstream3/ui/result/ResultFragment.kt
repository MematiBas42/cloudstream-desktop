// @PortSource(file = "upstream/app/src/main/java/com/lagradost/cloudstream3/ui/result/ResultFragment.kt", upstreamCommit = "caeec18")
package com.lagradost.cloudstream3.ui.result

import com.lagradost.cloudstream3.utils.Event

object ResultFragment {
    val updateUIEvent = Event<Int?>()

    fun updateUI(id: Int? = null) {
        updateUIEvent.invoke(id)
    }
}
