package com.lagradost.cloudstream3.annotation

/**
 * Platform Quarantine Annotation
 *
 * Used to explicitly mark upstream Android-specific components, hardware hooks,
 * or mobile dependencies that are either not applicable to Linux desktop,
 * require a desktop-native alternative, or are temporarily blocked by a dependency.
 *
 * Reference: CLAUDE.md Section 7 (The Quarantine Protocol).
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS, AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class PlatformQuarantine(
    val reason: String,
    val upstreamRef: String,
    val status: QuarantineStatus
)

enum class QuarantineStatus {
    NOT_APPLICABLE_DESKTOP,     // e.g. Biometric fingerprint, Battery optimization, Vibration
    NEEDS_DESKTOP_ALTERNATIVE,  // e.g. Mobile menus -> Desktop context menu / QuickList
    BLOCKED_BY_DEPENDENCY       // e.g. Blocked on another unported upstream class
}
