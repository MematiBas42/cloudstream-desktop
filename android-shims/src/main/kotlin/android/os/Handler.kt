package android.os

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

open class Handler(val looper: Looper = Looper.getMainLooper()) {
    constructor(looper: Looper, callback: Callback?) : this(looper)

    private val pendingFutures = ConcurrentHashMap<Runnable, MutableList<ScheduledFuture<*>>>()

    open fun post(r: Runnable): Boolean {
        return postDelayed(r, 0L)
    }

    open fun postDelayed(r: Runnable, delayMillis: Long): Boolean {
        val wrapped = Runnable {
            try {
                r.run()
            } finally {
                val list = pendingFutures[r]
                if (list != null) {
                    synchronized(list) {
                        list.removeAll { it.isDone || it.isCancelled }
                        if (list.isEmpty()) {
                            pendingFutures.remove(r)
                        }
                    }
                }
            }
        }

        val future = sharedExecutor.schedule(wrapped, delayMillis.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
        val list = pendingFutures.computeIfAbsent(r) { mutableListOf() }
        synchronized(list) {
            list.add(future)
        }
        return true
    }

    open fun removeCallbacks(r: Runnable) {
        val list = pendingFutures.remove(r) ?: return
        synchronized(list) {
            for (future in list) {
                future.cancel(false)
            }
            list.clear()
        }
    }

    open fun removeCallbacksAndMessages(token: Any?) {
        for ((_, list) in pendingFutures) {
            synchronized(list) {
                for (future in list) {
                    future.cancel(false)
                }
                list.clear()
            }
        }
        pendingFutures.clear()
    }

    fun interface Callback {
        fun handleMessage(msg: Any?): Boolean
    }

    companion object {
        private val sharedExecutor: ScheduledExecutorService = Executors.newScheduledThreadPool(2) { runnable ->
            Thread(runnable, "android-shims-handler").apply {
                isDaemon = true
            }
        }
    }
}
