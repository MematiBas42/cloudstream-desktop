package com.lagradost.common.logging

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.PrintStream
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Thread-safe centralized logging utility wrapping SLF4J and stdout/stderr.
 *
 * Formats all messages according to the standard specification:
 * "[HH:mm:ss.SSS] [LEVEL] [Thread] Tag: Message"
 */
object AppLogger {
    private val slf4j: Logger = LoggerFactory.getLogger("CloudStream")
    private val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

    enum class Level(val label: String) {
        DEBUG("DEBUG"),
        INFO("INFO"),
        WARN("WARN"),
        ERROR("ERROR")
    }

    private fun isIgnorableException(t: Throwable?): Boolean {
        if (t == null) return false
        val name = t::class.qualifiedName ?: ""
        if (name.contains("CancellationException", ignoreCase = true)) return true
        if (name.contains("ForgottenCoroutineScopeException", ignoreCase = true)) return true
        if (t.message?.contains("StandaloneCoroutine was cancelled", ignoreCase = true) == true) return true
        return false
    }

    @Synchronized
    fun log(level: Level, tag: String, message: String, t: Throwable? = null) {
        if (isIgnorableException(t)) return

        val timestamp = LocalTime.now().format(timeFormatter)
        val threadName = Thread.currentThread().name
        val formatted = "[$timestamp] [${level.label}] [$threadName] $tag: $message"

        val targetStream: PrintStream = when (level) {
            Level.WARN, Level.ERROR -> System.err
            else -> System.out
        }

        targetStream.println(formatted)
        if (t != null) {
            t.printStackTrace(targetStream)
        }

        // Mirror automatically into SystemDiagnostics event bus
        val organ = when {
            tag.contains("MPV", ignoreCase = true) || tag.contains("Player", ignoreCase = true) -> DiagnosticOrgan.PLAYER
            tag.contains("OkHttp", ignoreCase = true) || tag.contains("Http", ignoreCase = true) -> DiagnosticOrgan.NETWORK
            tag.contains("Coil", ignoreCase = true) || tag.contains("Image", ignoreCase = true) -> DiagnosticOrgan.IMAGE
            tag.contains("Plugin", ignoreCase = true) || tag.contains("Extension", ignoreCase = true) || tag.contains("DEX", ignoreCase = true) -> DiagnosticOrgan.PLUGIN
            tag.contains("Store", ignoreCase = true) || tag.contains("Storage", ignoreCase = true) || tag.contains("History", ignoreCase = true) -> DiagnosticOrgan.STORAGE
            level == Level.ERROR && (t != null) -> DiagnosticOrgan.CRASH
            else -> DiagnosticOrgan.SYSTEM
        }
        SystemDiagnostics.record(
            organ = organ,
            level = level,
            tag = tag,
            message = message,
            error = t?.let { "${it::class.simpleName}: ${it.message}" },
        )

        when (level) {
            Level.DEBUG -> {
                if (slf4j.isDebugEnabled) {
                    if (t != null) slf4j.debug("[$tag] $message", t) else slf4j.debug("[$tag] $message")
                }
            }
            Level.INFO -> {
                if (slf4j.isInfoEnabled) {
                    if (t != null) slf4j.info("[$tag] $message", t) else slf4j.info("[$tag] $message")
                }
            }
            Level.WARN -> {
                if (slf4j.isWarnEnabled) {
                    if (t != null) slf4j.warn("[$tag] $message", t) else slf4j.warn("[$tag] $message")
                }
            }
            Level.ERROR -> {
                if (slf4j.isErrorEnabled) {
                    if (t != null) slf4j.error("[$tag] $message", t) else slf4j.error("[$tag] $message")
                }
            }
        }
    }

    // --- Verbose (v) ---
    fun v(tag: String, message: String) = log(Level.DEBUG, tag, message)
    fun v(message: String) = log(Level.DEBUG, "App", message)
    fun v(tag: String, message: String, t: Throwable?) = log(Level.DEBUG, tag, message, t)
    fun v(message: String, t: Throwable?) = log(Level.DEBUG, "App", message, t)

    // --- Debug (d) ---
    fun d(tag: String, message: String) = log(Level.DEBUG, tag, message)
    fun d(message: String) = log(Level.DEBUG, "App", message)
    fun d(tag: String, message: String, t: Throwable?) = log(Level.DEBUG, tag, message, t)
    fun d(message: String, t: Throwable?) = log(Level.DEBUG, "App", message, t)

    // --- Info (i) ---
    fun i(tag: String, message: String) = log(Level.INFO, tag, message)
    fun i(message: String) = log(Level.INFO, "App", message)
    fun i(tag: String, message: String, t: Throwable?) = log(Level.INFO, tag, message, t)
    fun i(message: String, t: Throwable?) = log(Level.INFO, "App", message, t)

    // --- Warn (w) ---
    fun w(tag: String, message: String, t: Throwable? = null) = log(Level.WARN, tag, message, t)
    fun w(message: String, t: Throwable? = null) = log(Level.WARN, "App", message, t)

    // --- Error (e) ---
    fun e(tag: String, message: String, t: Throwable? = null) = log(Level.ERROR, tag, message, t)
    fun e(message: String, t: Throwable? = null) = log(Level.ERROR, "App", message, t)
}
