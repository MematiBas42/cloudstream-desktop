// @PortSource(file = "upstream/app/src/main/java/com/lagradost/cloudstream3/utils/ConsistentLiveData.kt", upstreamCommit = "caeec18")
package com.lagradost.cloudstream3.utils

import com.lagradost.cloudstream3.annotation.PlatformQuarantine
import com.lagradost.cloudstream3.annotation.QuarantineStatus
import com.lagradost.cloudstream3.mvvm.Resource

/**
 * Android LiveData Thread-Safety Wrapper
 *
 * Quarantined because Android LiveData is an Android Architecture Component tied to Android lifecycle.
 * As specified in CLAUDE.md Section 2.2 and Section 5, desktop Linux replaces LiveData with
 * Kotlin Coroutines StateFlow across all ViewModels and repository streams.
 */
@PlatformQuarantine(
    reason = "Android LiveData thread-safety wrapper is mobile-specific; desktop Linux uses Kotlin Coroutines StateFlow across all ViewModels",
    upstreamRef = "upstream/app/src/main/java/com/lagradost/cloudstream3/utils/ConsistentLiveData.kt",
    status = QuarantineStatus.NOT_APPLICABLE_DESKTOP
)
open class ConsistentLiveData<T>(initValue: T? = null) {
    @Volatile
    private var internalValue: T? = initValue

    open var value: T?
        get() = internalValue
        set(value) {
            internalValue = value
        }

    val postedValue: T?
        get() = internalValue

    open fun postValue(value: T?) {
        internalValue = value
    }
}

/**
 * Atomic resource livedata wrapper for compatibility
 */
@PlatformQuarantine(
    reason = "Android LiveData is mobile-specific; desktop Linux uses StateFlow<Resource<T>>",
    upstreamRef = "upstream/app/src/main/java/com/lagradost/cloudstream3/utils/ConsistentLiveData.kt",
    status = QuarantineStatus.NOT_APPLICABLE_DESKTOP
)
class ResourceLiveData<T>(initValue: Resource<T>? = null) : ConsistentLiveData<Resource<T>>(initValue) {
    var success: T?
        get() = when (val output = this.value) {
            is Resource.Success<T> -> output.value
            else -> null
        }
        set(value) {
            this.postValue(value?.let { Resource.Success(it) })
        }
}
