// @PortSource(file = "upstream/app/src/main/java/com/lagradost/cloudstream3/utils/IDisposable.kt", upstreamCommit = "caeec18")
package com.lagradost.cloudstream3.utils

interface IDisposable {
    fun dispose()
}

object IDisposableHelper {
    fun <T : IDisposable> using(disposeObject: T, work: (T) -> Unit) {
        work.invoke(disposeObject)
        disposeObject.dispose()
    }
}
