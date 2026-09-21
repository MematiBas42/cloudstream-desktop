// @PortSource(file = "upstream/app/src/main/java/com/lagradost/cloudstream3/ui/home/HomeParentItemAdapter.kt", upstreamCommit = "caeec18")
package com.lagradost.cloudstream3.ui.home

import com.lagradost.cloudstream3.LoadResponse

class LoadClickCallback(
    val action: Int = 0,
    val view: Any? = null,
    val position: Int = 0,
    val response: LoadResponse
)
