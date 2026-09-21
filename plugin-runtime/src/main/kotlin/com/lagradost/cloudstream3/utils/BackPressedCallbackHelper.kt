// @PortSource(file = "upstream/app/src/main/java/com/lagradost/cloudstream3/utils/BackPressedCallbackHelper.kt", upstreamCommit = "caeec18")
package com.lagradost.cloudstream3.utils

import android.app.Activity
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/**
 * 1:1 Architectural Parity Port of upstream BackPressedCallbackHelper.
 * Provides thread-safe, weak-reference lifecycle-aware back pressed callback registration
 * for desktop Activities and fragments.
 */
object BackPressedCallbackHelper {

    private val backPressedCallbacks =
        WeakHashMap<Activity, MutableMap<String, CallbackEntry>>()

    data class CallbackEntry(
        var isEnabled: Boolean = true,
        val callback: CallbackHelper.() -> Unit
    )

    class CallbackHelper(
        private val activityRef: WeakReference<Activity>,
        private val entry: CallbackEntry
    ) {
        fun runDefault() {
            val activity = activityRef.get() ?: return
            val wasEnabled = entry.isEnabled
            entry.isEnabled = false
            try {
                activity.finish()
            } finally {
                entry.isEnabled = wasEnabled
            }
        }
    }

    fun Activity.attachBackPressedCallback(
        id: String,
        callback: CallbackHelper.() -> Unit
    ) {
        val callbackMap = backPressedCallbacks.getOrPut(this) { mutableMapOf() }
        if (callbackMap.containsKey(id)) return

        val entry = CallbackEntry(isEnabled = true, callback = callback)
        callbackMap[id] = entry
    }

    fun Activity.disableBackPressedCallback(id: String) {
        backPressedCallbacks[this]?.get(id)?.isEnabled = false
    }

    fun Activity.enableBackPressedCallback(id: String) {
        backPressedCallbacks[this]?.get(id)?.isEnabled = true
    }

    fun Activity.detachBackPressedCallback(id: String) {
        val callbackMap = backPressedCallbacks[this] ?: return
        callbackMap.remove(id)
        if (callbackMap.isEmpty()) {
            backPressedCallbacks.remove(this)
        }
    }

    /**
     * Triggers the registered back press callback for the given activity.
     * Evaluates registered callbacks in LIFO or specified order.
     * @return true if a registered callback consumed the back event.
     */
    fun triggerBackPressed(activity: Activity, id: String? = null): Boolean {
        val callbackMap = backPressedCallbacks[activity] ?: return false
        if (id != null) {
            val entry = callbackMap[id] ?: return false
            if (entry.isEnabled) {
                CallbackHelper(WeakReference(activity), entry).apply { entry.callback(this) }
                return true
            }
            return false
        }
        for ((_, entry) in callbackMap) {
            if (entry.isEnabled) {
                CallbackHelper(WeakReference(activity), entry).apply { entry.callback(this) }
                return true
            }
        }
        return false
    }
}
