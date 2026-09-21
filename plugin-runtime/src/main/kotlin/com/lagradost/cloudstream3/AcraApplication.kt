// @PortSource(file = "upstream/app/src/main/java/com/lagradost/cloudstream3/AcraApplication.kt", upstreamCommit = "caeec18")
package com.lagradost.cloudstream3

import android.content.Context
import com.lagradost.common.logging.AppLogger

/**
 * Deprecated alias for CloudStreamApp for backwards compatibility with plugins.
 * Use CloudStreamApp instead.
 */
@Deprecated(
    message = "AcraApplication is deprecated, use CloudStreamApp instead",
    replaceWith = ReplaceWith("com.lagradost.cloudstream3.CloudStreamApp"),
    level = DeprecationLevel.ERROR
)
open class AcraApplication {

    open fun onCreate() {
        CloudStreamApp().onCreate()
    }

    companion object {

        /**
         * Desktop initialization hook for crash reporting and uncaught exception handler parity.
         * Configures Thread.setDefaultUncaughtExceptionHandler and initializes CloudStreamApp.
         */
        fun init(context: Context? = null) {
            if (context != null) {
                CloudStreamApp.context = context
            }
            CloudStreamApp().onCreate()
            AppLogger.i("AcraApplication", "AcraApplication desktop initialization completed.")
        }

        fun initializeExceptionHandler(context: Context? = null) {
            init(context)
        }

        @Deprecated(
            message = "AcraApplication is deprecated, use CloudStreamApp instead",
            replaceWith = ReplaceWith("com.lagradost.cloudstream3.CloudStreamApp.context"),
            level = DeprecationLevel.ERROR
        )
        val context get() = CloudStreamApp.context

        @Deprecated(
            message = "AcraApplication is deprecated, use CloudStreamApp instead",
            replaceWith = ReplaceWith("com.lagradost.cloudstream3.CloudStreamApp.removeKeys(folder)"),
            level = DeprecationLevel.ERROR
        )
        fun removeKeys(folder: String): Int? =
            CloudStreamApp.removeKeys(folder)

        @Deprecated(
            message = "AcraApplication is deprecated, use CloudStreamApp instead",
            replaceWith = ReplaceWith("com.lagradost.cloudstream3.CloudStreamApp.removeKey(path)"),
            level = DeprecationLevel.ERROR
        )
        fun removeKey(path: String) =
            CloudStreamApp.removeKey(path)

        @Deprecated(
            message = "AcraApplication is deprecated, use CloudStreamApp instead",
            replaceWith = ReplaceWith("com.lagradost.cloudstream3.CloudStreamApp.removeKey(folder, path)"),
            level = DeprecationLevel.ERROR
        )
        fun removeKey(folder: String, path: String) =
            CloudStreamApp.removeKey(folder, path)

        @Deprecated(
            message = "AcraApplication is deprecated, use CloudStreamApp instead",
            replaceWith = ReplaceWith("com.lagradost.cloudstream3.CloudStreamApp.setKey(path, value)"),
            level = DeprecationLevel.ERROR
        )
        fun <T> setKey(path: String, value: T) =
            CloudStreamApp.setKey(path, value)

        @Deprecated(
            message = "AcraApplication is deprecated, use CloudStreamApp instead",
            replaceWith = ReplaceWith("com.lagradost.cloudstream3.CloudStreamApp.setKey(folder, path, value)"),
            level = DeprecationLevel.ERROR
        )
        fun <T> setKey(folder: String, path: String, value: T) =
            CloudStreamApp.setKey(folder, path, value)

        @Deprecated(
            message = "AcraApplication is deprecated, use CloudStreamApp instead",
            replaceWith = ReplaceWith("com.lagradost.cloudstream3.CloudStreamApp.setKeyRaw(path, value)"),
            level = DeprecationLevel.ERROR
        )
        fun <T> setKeyRaw(path: String, value: T) =
            CloudStreamApp.setKeyRaw(path, value)

        @Deprecated(
            message = "AcraApplication is deprecated, use CloudStreamApp instead",
            replaceWith = ReplaceWith("com.lagradost.cloudstream3.CloudStreamApp.setKeyRaw(folder, path, value)"),
            level = DeprecationLevel.ERROR
        )
        fun <T> setKeyRaw(folder: String, path: String, value: T) =
            CloudStreamApp.setKeyRaw(folder, path, value)

        @Deprecated(
            message = "AcraApplication is deprecated, use CloudStreamApp instead",
            replaceWith = ReplaceWith("com.lagradost.cloudstream3.CloudStreamApp.getKey(path, defVal)"),
            level = DeprecationLevel.ERROR
        )
        inline fun <reified T : Any> getKey(path: String, defVal: T?): T? =
            CloudStreamApp.getKey(path, defVal)

        @Deprecated(
            message = "AcraApplication is deprecated, use CloudStreamApp instead",
            replaceWith = ReplaceWith("com.lagradost.cloudstream3.CloudStreamApp.getKey(path)"),
            level = DeprecationLevel.ERROR
        )
        inline fun <reified T : Any> getKey(path: String): T? =
            CloudStreamApp.getKey(path)

        @Deprecated(
            message = "AcraApplication is deprecated, use CloudStreamApp instead",
            replaceWith = ReplaceWith("com.lagradost.cloudstream3.CloudStreamApp.getKey(folder, path)"),
            level = DeprecationLevel.ERROR
        )
        inline fun <reified T : Any> getKey(folder: String, path: String): T? =
            CloudStreamApp.getKey(folder, path)

        @Deprecated(
            message = "AcraApplication is deprecated, use CloudStreamApp instead",
            replaceWith = ReplaceWith("com.lagradost.cloudstream3.CloudStreamApp.getKey(folder, path, defVal)"),
            level = DeprecationLevel.ERROR
        )
        inline fun <reified T : Any> getKey(folder: String, path: String, defVal: T?): T? =
            CloudStreamApp.getKey(folder, path, defVal)
    }
}
