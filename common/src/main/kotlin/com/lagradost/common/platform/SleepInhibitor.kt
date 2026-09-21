package com.lagradost.common.platform

import com.lagradost.common.logging.AppLogger
import java.util.concurrent.TimeUnit

/**
 * Cross-platform sleep inhibitor interface to prevent system suspension/idling
 * while active media playback, downloads, or critical background tasks are executing.
 */
interface SleepInhibitor {
    val isInhibited: Boolean
    fun acquire(): Boolean
    fun release(): Boolean

    companion object {
        fun createDefault(): SleepInhibitor {
            return when (PlatformPaths.currentOS) {
                PlatformPaths.OS.LINUX -> SystemdSleepInhibitor()
                PlatformPaths.OS.WINDOWS -> WindowsSleepInhibitor()
                PlatformPaths.OS.MACOS -> MacOsSleepInhibitor()
                PlatformPaths.OS.UNKNOWN -> NoOpSleepInhibitor()
            }
        }
    }
}

/**
 * Linux sleep inhibitor utilizing systemd-inhibit to block sleep/idle during active media playback or downloads.
 */
class SystemdSleepInhibitor(
    var commandProvider: () -> List<String> = defaultCommandProvider
) : SleepInhibitor {

    companion object {
        private const val TAG = "SystemdSleepInhibitor"

        val defaultCommandProvider: () -> List<String> = {
            listOf(
                "systemd-inhibit",
                "--what=idle:sleep",
                "--who=CloudStream",
                "--why=Active download",
                "--mode=block",
                "sleep",
                "infinity"
            )
        }
    }

    private val lock = Any()

    @Volatile
    private var process: Process? = null

    @Volatile
    override var isInhibited: Boolean = false
        private set

    override fun acquire(): Boolean {
        synchronized(lock) {
            if (process != null && process?.isAlive == true) {
                isInhibited = true
                return true
            }

            return try {
                val cmd = commandProvider()
                val p = ProcessBuilder(cmd)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()

                if (p.isAlive) {
                    process = p
                    isInhibited = true
                    AppLogger.i(TAG, "systemd-inhibit sleep inhibitor acquired successfully (PID: ${p.pid()})")
                    true
                } else {
                    val exitCode = try { p.exitValue() } catch (e: Throwable) {
                        AppLogger.w(TAG, "Could not read process exit value: ${e.message}")
                        -1
                    }
                    AppLogger.w(TAG, "systemd-inhibit process exited immediately with code: $exitCode")
                    process = null
                    isInhibited = false
                    false
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "systemd-inhibit is not available on this system: ${e.message}")
                process = null
                isInhibited = false
                false
            }
        }
    }

    override fun release(): Boolean {
        synchronized(lock) {
            val p = process
            process = null
            isInhibited = false
            if (p != null) {
                try {
                    if (p.isAlive) {
                        p.destroy()
                        if (!p.waitFor(1, TimeUnit.SECONDS)) {
                            p.destroyForcibly()
                        }
                        AppLogger.i(TAG, "systemd-inhibit sleep inhibitor released.")
                    }
                    return true
                } catch (e: Exception) {
                    AppLogger.w(TAG, "Error terminating systemd-inhibit process: ${e.message}")
                    try {
                        p.destroyForcibly()
                    } catch (t: Throwable) {
                        AppLogger.w(TAG, "destroyForcibly failed: ${t.message}")
                    }
                    return false
                }
            }
            return true
        }
    }
}

/**
 * Windows sleep inhibitor implementing Win32 SetThreadExecutionState.
 * Prevents system and display idle sleep during active playback and downloads.
 * Supports in-process JNA/Kernel32 invocation if available, with a persistent PowerShell process fallback.
 */
class WindowsSleepInhibitor(
    val flags: Int = ES_CONTINUOUS or ES_SYSTEM_REQUIRED or ES_DISPLAY_REQUIRED,
    var commandProvider: () -> List<String> = defaultCommandProvider
) : SleepInhibitor {

    companion object {
        private const val TAG = "WindowsSleepInhibitor"

        const val ES_SYSTEM_REQUIRED = 0x00000001
        const val ES_DISPLAY_REQUIRED = 0x00000002
        const val ES_AWAYMODE_REQUIRED = 0x00000040
        const val ES_CONTINUOUS = 0x80000000.toInt()

        val defaultCommandProvider: () -> List<String> = {
            listOf(
                "powershell",
                "-NoProfile",
                "-NonInteractive",
                "-Command",
                "Add-Type -TypeDefinition 'using System; using System.Runtime.InteropServices; public class Win32 { [DllImport(\"kernel32.dll\")] public static extern uint SetThreadExecutionState(uint esFlags); }';" +
                    " [Win32]::SetThreadExecutionState(0x80000003);" +
                    " Start-Sleep -Seconds 86400"
            )
        }
    }

    private val lock = Any()

    @Volatile
    private var process: Process? = null

    @Volatile
    private var nativeInhibitionActive: Boolean = false

    @Volatile
    override var isInhibited: Boolean = false
        private set

    /**
     * Attempts native in-process SetThreadExecutionState invocation via JNA reflection.
     */
    private fun invokeNativeSetThreadExecutionState(flags: Int): Boolean {
        return try {
            val kernel32Class = Class.forName("com.sun.jna.platform.win32.Kernel32")
            val instanceField = kernel32Class.getField("INSTANCE")
            val kernel32 = instanceField.get(null)
            val method = kernel32Class.getMethod("SetThreadExecutionState", Int::class.javaPrimitiveType)
            val res = method.invoke(kernel32, flags) as? Int ?: 0
            if (res != 0) {
                AppLogger.i(TAG, "Windows SetThreadExecutionState via JNA Kernel32: flags=$flags, res=$res")
                true
            } else {
                false
            }
        } catch (_: Throwable) {
            try {
                val nativeLibClass = Class.forName("com.sun.jna.NativeLibrary")
                val getInstanceMethod = nativeLibClass.getMethod("getInstance", String::class.java)
                val kernel32Lib = getInstanceMethod.invoke(null, "kernel32")
                val getFunctionMethod = nativeLibClass.getMethod("getFunction", String::class.java)
                val func = getFunctionMethod.invoke(kernel32Lib, "SetThreadExecutionState")
                val funcClass = Class.forName("com.sun.jna.Function")
                val invokeMethod = funcClass.getMethod("invokeInt", Array<Any>::class.java)
                val res = invokeMethod.invoke(func, arrayOf<Any>(flags)) as? Int ?: 0
                if (res != 0) {
                    AppLogger.i(TAG, "Windows SetThreadExecutionState via JNA NativeLibrary: flags=$flags, res=$res")
                    true
                } else {
                    false
                }
            } catch (_: Throwable) {
                false
            }
        }
    }

    override fun acquire(): Boolean {
        synchronized(lock) {
            if (isInhibited) return true

            // 1. Attempt in-process Win32 API
            val nativeSuccess = invokeNativeSetThreadExecutionState(flags)
            if (nativeSuccess) {
                nativeInhibitionActive = true
                isInhibited = true
                return true
            }

            // 2. Fallback to persistent PowerShell process
            return try {
                val cmd = commandProvider()
                val p = ProcessBuilder(cmd)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()

                if (p.isAlive) {
                    process = p
                    isInhibited = true
                    AppLogger.i(TAG, "Windows SetThreadExecutionState process acquired (PID: ${p.pid()})")
                    true
                } else {
                    val exitCode = try { p.exitValue() } catch (e: Throwable) { -1 }
                    AppLogger.w(TAG, "Windows inhibitor process exited immediately: $exitCode")
                    process = null
                    isInhibited = false
                    false
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "Windows sleep inhibitor failed to start: ${e.message}")
                process = null
                isInhibited = false
                false
            }
        }
    }

    override fun release(): Boolean {
        synchronized(lock) {
            var success = true
            if (nativeInhibitionActive) {
                val released = invokeNativeSetThreadExecutionState(ES_CONTINUOUS)
                if (released) {
                    nativeInhibitionActive = false
                } else {
                    success = false
                }
            }

            val p = process
            process = null
            if (p != null) {
                try {
                    if (p.isAlive) {
                        p.destroy()
                        if (!p.waitFor(1, TimeUnit.SECONDS)) {
                            p.destroyForcibly()
                        }
                        AppLogger.i(TAG, "Windows sleep inhibitor process released.")
                    }
                } catch (e: Exception) {
                    AppLogger.w(TAG, "Error terminating Windows inhibitor process: ${e.message}")
                    try {
                        p.destroyForcibly()
                    } catch (t: Throwable) {
                        AppLogger.w(TAG, "destroyForcibly on Windows process failed: ${t.message}")
                    }
                    success = false
                }
            }

            isInhibited = false
            return success
        }
    }
}

/**
 * No-op sleep inhibitor used when platform does not support sleep inhibition,
 * or for headless/mock testing environments.
 */
class NoOpSleepInhibitor(val simulatedSuccess: Boolean = false) : SleepInhibitor {

    @Volatile
    override var isInhibited: Boolean = false
        private set

    override fun acquire(): Boolean {
        isInhibited = simulatedSuccess
        return simulatedSuccess
    }

    override fun release(): Boolean {
        isInhibited = false
        return true
    }
}

/**
 * macOS sleep inhibitor using caffeinate CLI tool.
 */
class MacOsSleepInhibitor(
    var commandProvider: () -> List<String> = defaultCommandProvider
) : SleepInhibitor {

    companion object {
        private const val TAG = "MacOsSleepInhibitor"

        val defaultCommandProvider: () -> List<String> = {
            listOf("caffeinate", "-i", "sleep", "infinity")
        }
    }

    private val lock = Any()

    @Volatile
    private var process: Process? = null

    @Volatile
    override var isInhibited: Boolean = false
        private set

    override fun acquire(): Boolean {
        synchronized(lock) {
            if (process != null && process?.isAlive == true) {
                isInhibited = true
                return true
            }

            return try {
                val cmd = commandProvider()
                val p = ProcessBuilder(cmd)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()

                if (p.isAlive) {
                    process = p
                    isInhibited = true
                    AppLogger.i(TAG, "macOS caffeinate sleep inhibitor acquired (PID: ${p.pid()})")
                    true
                } else {
                    process = null
                    isInhibited = false
                    false
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "caffeinate is not available on this system: ${e.message}")
                process = null
                isInhibited = false
                false
            }
        }
    }

    override fun release(): Boolean {
        synchronized(lock) {
            val p = process
            process = null
            isInhibited = false
            if (p != null) {
                try {
                    if (p.isAlive) {
                        p.destroy()
                        if (!p.waitFor(1, TimeUnit.SECONDS)) {
                            p.destroyForcibly()
                        }
                        AppLogger.i(TAG, "macOS caffeinate sleep inhibitor released.")
                    }
                    return true
                } catch (e: Exception) {
                    AppLogger.w(TAG, "Error terminating caffeinate process: ${e.message}")
                    try {
                        p.destroyForcibly()
                    } catch (t: Throwable) {
                        AppLogger.w(TAG, "destroyForcibly on caffeinate process failed: ${t.message}")
                    }
                    return false
                }
            }
            return true
        }
    }
}
