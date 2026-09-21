package android.util

import com.lagradost.common.logging.AppLogger
import java.io.PrintWriter
import java.io.StringWriter

object Log {
    const val VERBOSE: Int = 2
    const val DEBUG: Int = 3
    const val INFO: Int = 4
    const val WARN: Int = 5
    const val ERROR: Int = 6
    const val ASSERT: Int = 7

    @JvmStatic
    fun v(tag: String, msg: String): Int {
        AppLogger.d(tag, msg)
        return 0
    }

    @JvmStatic
    fun v(tag: String, msg: String, tr: Throwable?): Int {
        AppLogger.d(tag, if (tr != null) "$msg\n${getStackTraceString(tr)}" else msg)
        return 0
    }

    @JvmStatic
    fun d(tag: String, msg: String): Int {
        AppLogger.d(tag, msg)
        return 0
    }

    @JvmStatic
    fun d(tag: String, msg: String, tr: Throwable?): Int {
        AppLogger.d(tag, if (tr != null) "$msg\n${getStackTraceString(tr)}" else msg)
        return 0
    }

    @JvmStatic
    fun i(tag: String, msg: String): Int {
        AppLogger.i(tag, msg)
        return 0
    }

    @JvmStatic
    fun i(tag: String, msg: String, tr: Throwable?): Int {
        AppLogger.i(tag, if (tr != null) "$msg\n${getStackTraceString(tr)}" else msg)
        return 0
    }

    @JvmStatic
    fun w(tag: String, msg: String): Int {
        AppLogger.w(tag, msg)
        return 0
    }

    @JvmStatic
    fun w(tag: String, msg: String, tr: Throwable?): Int {
        AppLogger.w(tag, msg, tr)
        return 0
    }

    @JvmStatic
    fun w(tag: String, tr: Throwable?): Int {
        AppLogger.w(tag, "", tr)
        return 0
    }

    @JvmStatic
    fun e(tag: String, msg: String): Int {
        AppLogger.e(tag, msg)
        return 0
    }

    @JvmStatic
    fun e(tag: String, msg: String, tr: Throwable?): Int {
        AppLogger.e(tag, msg, tr)
        return 0
    }

    @JvmStatic
    fun isLoggable(tag: String?, level: Int): Boolean = true

    @JvmStatic
    fun getStackTraceString(tr: Throwable?): String {
        if (tr == null) return ""
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        tr.printStackTrace(pw)
        pw.flush()
        return sw.toString()
    }
}
