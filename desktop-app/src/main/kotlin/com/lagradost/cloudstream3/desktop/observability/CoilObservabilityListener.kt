package com.lagradost.cloudstream3.desktop.observability

import coil3.EventListener
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import com.lagradost.common.logging.AppLogger
import com.lagradost.common.logging.DiagnosticOrgan
import com.lagradost.common.logging.SystemDiagnostics

/**
 * Global Coil 3 image lifecycle event listener.
 * Automatically tracks memory/disk cache hits, network image requests, and fetch failures.
 */
class CoilObservabilityListener : EventListener() {
    private var startNs = 0L

    override fun onStart(request: ImageRequest) {
        startNs = System.nanoTime()
        val dataStr = request.data?.toString() ?: "null"
        SystemDiagnostics.record(
            organ = DiagnosticOrgan.IMAGE,
            level = AppLogger.Level.DEBUG,
            tag = "Coil",
            message = "REQUEST: $dataStr",
        )
    }

    override fun onSuccess(request: ImageRequest, result: SuccessResult) {
        val durationMs = (System.nanoTime() - startNs) / 1_000_000
        val dataStr = request.data?.toString() ?: "null"
        val source = result.dataSource.name

        SystemDiagnostics.record(
            organ = DiagnosticOrgan.IMAGE,
            level = AppLogger.Level.INFO,
            tag = "Coil",
            message = "SUCCESS ($source, ${durationMs}ms): $dataStr",
        )
    }

    override fun onError(request: ImageRequest, result: ErrorResult) {
        val durationMs = (System.nanoTime() - startNs) / 1_000_000
        val dataStr = request.data?.toString() ?: "null"
        val throwable = result.throwable

        SystemDiagnostics.record(
            organ = DiagnosticOrgan.IMAGE,
            level = AppLogger.Level.WARN,
            tag = "Coil",
            message = "FAILED (${durationMs}ms): $dataStr",
            error = "${throwable::class.simpleName}: ${throwable.message}",
        )
    }

    class Factory : EventListener.Factory {
        override fun create(request: ImageRequest): EventListener {
            return CoilObservabilityListener()
        }
    }
}
