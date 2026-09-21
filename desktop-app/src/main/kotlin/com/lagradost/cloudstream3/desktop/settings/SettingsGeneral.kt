// @PortSource(file = "upstream/app/src/main/java/com/lagradost/cloudstream3/ui/settings/SettingsGeneral.kt", upstreamCommit = "caeec18")
package com.lagradost.cloudstream3.desktop.settings

import android.content.Context
import android.net.Uri
import androidx.preference.PreferenceManager
import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.annotation.PlatformQuarantine
import com.lagradost.cloudstream3.annotation.QuarantineStatus
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.SubtitleHelper
import com.lagradost.cloudstream3.utils.USER_PROVIDER_API
import com.lagradost.common.platform.PlatformPaths
import com.lagradost.common.storage.DesktopDataStore
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames
import java.util.Locale

/**
 * Change local language settings in the app.
 */
fun getCurrentLocale(context: Context? = null): String {
    return try {
        Locale.getDefault().toLanguageTag().ifBlank { "en" }
    } catch (e: Throwable) {
        "en"
    }
}

/**
 * List of app supported languages.
 * Language code shall be an IETF BCP 47 conformant tag.
 *
 * See locales on:
 * https://github.com/unicode-org/cldr-json/blob/main/cldr-json/cldr-core/availableLocales.json
 * https://www.iana.org/assignments/language-subtag-registry/language-subtag-registry
 * https://android.googlesource.com/platform/frameworks/base/+/android-16.0.0_r2/core/res/res/values/locale_config.xml
 * https://iso639-3.sil.org/code_tables/639/data/all
 */
val appLanguages: List<Pair<String, String>> = arrayListOf(
    /* begin language list */
    Pair("Afrikaans", "af"),
    Pair("Azərbaycan dili", "az"),
    Pair("Bahasa Indonesia", "in"),
    Pair("Bahasa Melayu", "ms"),
    Pair("català", "ca"),
    Pair("Deutsch", "de"),
    Pair("English", "en"),
    Pair("Español", "es"),
    Pair("Esperanto", "eo"),
    Pair("Français", "fr"),
    Pair("Galego", "gl"),
    Pair("hrvatski", "hr"),
    Pair("Italiano", "it"),
    Pair("Latviešu valoda", "lv"),
    Pair("Lietuvių kalba", "lt"),
    Pair("Magyar", "hu"),
    Pair("Malti", "mt"),
    Pair("mmmm... monke", "qt"),
    Pair("Nederlands", "nl"),
    Pair("Norsk bokmål", "no"),
    Pair("Norsk nynorsk", "nn"),
    Pair("Polski", "pl"),
    Pair("Português", "pt"),
    Pair("Português (Brasil)", "pt-BR"),
    Pair("Română", "ro"),
    Pair("Shqip мова", "sq"),
    Pair("Slovenčina", "sk"),
    Pair("Soomaaliga", "so"),
    Pair("Svenska", "sv"),
    Pair("Tagalog", "tl"),
    Pair("Tiếng Việt", "vi"),
    Pair("Türkçe", "tr"),
    Pair("Wikang Filipino", "fil"),
    Pair("Čeština", "cs"),
    Pair("Ελληνικά", "el"),
    Pair("беларуская мова", "be"),
    Pair("български", "bg"),
    Pair("македонски", "mk"),
    Pair("русский", "ru"),
    Pair("українська", "uk"),
    Pair("עברית", "iw"),
    Pair("اردو", "ur"),
    Pair("العربية", "ar"),
    Pair("اللهجة النجدية", "ars"),
    Pair("عربي شامي", "apc"),
    Pair("فارسی", "fa"),
    Pair("کوردیی ناوەندی", "ckb"),
    Pair("नेपाली", "ne"),
    Pair("हिन्दी", "hi"),
    Pair("অসমীয়া", "as"),
    Pair("বাংলা", "bn"),
    Pair("ଓଡ଼ିଆ", "or"),
    Pair("தமிழ்", "ta"),
    Pair("ಕನ್ನಡ", "kn"),
    Pair("മലയാളം", "ml"),
    Pair("ພາສາລາව", "lo"),
    Pair("ဗမာစာ", "my"),
    Pair("ትግርኛ", "ti"),
    Pair("አማርኛ", "am"),
    Pair("中文", "zh"),
    Pair("日本語 (にほんご)", "ja"),
    Pair("正體中文(臺灣)", "zh-TW"),
    Pair("한국어", "ko"),
    /* end language list */
).sortedBy { it.first.lowercase(Locale.ROOT) }

fun Pair<String, String>.nameNextToFlagEmoji(): String {
    // fallback to [A][A] -> [?] question mark flag
    val flag = SubtitleHelper.getFlagFromIso(this.second) ?: "🇦🇦"
    return "$flag ${this.first}" //   non-breaking space
}

open class SettingsGeneral {

    @PlatformQuarantine(
        reason = "Android Fragment ViewBinding ve Toolbar kurulumu. Masaüstünde Compose TopAppBar/NavHost tarafından yönetilir.",
        upstreamRef = "upstream/app/src/main/java/com/lagradost/cloudstream3/ui/settings/SettingsGeneral.kt:152",
        status = QuarantineStatus.NOT_APPLICABLE_DESKTOP
    )
    open fun onViewCreated(view: Any? = null, savedInstanceState: Any? = null) {
        // Compose Desktop renders toolbar and padding declaratively
    }

    @OptIn(ExperimentalSerializationApi::class)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @Serializable
    data class CustomSite(
        @JsonProperty("parentClassName") @JsonAlias("parentJavaClass")
        @SerialName("parentClassName") @JsonNames("parentJavaClass")
        val parentClassName: String, // ::class.simpleName
        @JsonProperty("name") @SerialName("name") val name: String,
        @JsonProperty("url") @SerialName("url") val url: String,
        @JsonProperty("lang") @SerialName("lang") val lang: String = "en",
    ) {
        /**
         * Backward compatibility accessor for legacy desktop code expecting parentJavaClass.
         */
        @get:JsonIgnore
        val parentJavaClass: String
            get() = parentClassName
    }

    companion object {
        fun pickDownloadPath(uri: Uri?, path: String?, context: Context? = null) {
            if (uri == null && path == null) return

            val visual = path ?: uri?.toString() ?: return
            val prefUri = uri?.toString() ?: path
            val ctx = context ?: CloudStreamApp.context
            if (ctx != null) {
                try {
                    val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(ctx)
                    val editor = sharedPrefs.edit()
                    editor.putString(ctx.getString(R.string.download_path_key), prefUri)
                    editor.putString(ctx.getString(R.string.download_path_key_visual), visual)
                    editor.apply()
                } catch (e: Throwable) {
                    logError(e)
                }
            }

            DesktopDataStore.setKey("download_path_key", prefUri)
            DesktopDataStore.setKey("download_path_key_visual", visual)
            CloudStreamApp.setKey("download_path_key", prefUri)
            CloudStreamApp.setKey("download_path_key_visual", visual)
        }

        fun getCurrent(): MutableList<CustomSite> {
            return DesktopDataStore.getKey<Array<CustomSite>>(USER_PROVIDER_API)?.toMutableList()
                ?: CloudStreamApp.getKey<Array<CustomSite>>(USER_PROVIDER_API)?.toMutableList()
                ?: mutableListOf()
        }

        fun addSite(newSite: CustomSite): MutableList<CustomSite> {
            val current = getCurrent()
            current.add(newSite)
            DesktopDataStore.setKey(USER_PROVIDER_API, current.toTypedArray())
            CloudStreamApp.setKey(USER_PROVIDER_API, current.toTypedArray())
            MainActivity.afterPluginsLoadedEvent.invoke(false)
            return current
        }

        fun deleteSites(indexes: List<Int>): MutableList<CustomSite> {
            val current = getCurrent()
            val toRemove = indexes.mapNotNull { current.getOrNull(it) }
            current.removeAll(toRemove)
            DesktopDataStore.setKey(USER_PROVIDER_API, current.toTypedArray())
            CloudStreamApp.setKey(USER_PROVIDER_API, current.toTypedArray())
            MainActivity.afterPluginsLoadedEvent.invoke(false)
            return current
        }

        @PlatformQuarantine(
            reason = "Android AlertDialog ve XML AddSiteInputBinding. Masaüstünde Compose AddCustomSiteDialog ile ikame edilmiştir.",
            upstreamRef = "upstream/app/src/main/java/com/lagradost/cloudstream3/ui/settings/SettingsGeneral.kt:186",
            status = QuarantineStatus.NEEDS_DESKTOP_ALTERNATIVE
        )
        fun showAdd() {
            MainActivity.afterPluginsLoadedEvent.invoke(false)
        }

        @PlatformQuarantine(
            reason = "Android AlertDialog ve MultiChoiceDialog. Masaüstünde Compose SettingsGeneral ekranında liste üzerinden silinir.",
            upstreamRef = "upstream/app/src/main/java/com/lagradost/cloudstream3/ui/settings/SettingsGeneral.kt:235",
            status = QuarantineStatus.NEEDS_DESKTOP_ALTERNATIVE
        )
        fun showDelete() {
            MainActivity.afterPluginsLoadedEvent.invoke(false)
        }

        @PlatformQuarantine(
            reason = "Android AddRemoveSitesBinding dialog. Masaüstünde Compose SettingsGeneral ekranı doğrudan ekleme/silme butonları sunar.",
            upstreamRef = "upstream/app/src/main/java/com/lagradost/cloudstream3/ui/settings/SettingsGeneral.kt:248",
            status = QuarantineStatus.NEEDS_DESKTOP_ALTERNATIVE
        )
        fun showAddOrDelete() {
            MainActivity.afterPluginsLoadedEvent.invoke(false)
        }
    }

    val pathPicker: Any? = null

    @PlatformQuarantine(
        reason = "Android PreferenceFragmentCompat XML Tercih Bağlantısı. Masaüstünde Compose SettingsGeneral ekranı tarafından yönetilir.",
        upstreamRef = "upstream/app/src/main/java/com/lagradost/cloudstream3/ui/settings/SettingsGeneral.kt:277",
        status = QuarantineStatus.NEEDS_DESKTOP_ALTERNATIVE
    )
    open fun onCreatePreferences(savedInstanceState: Any? = null, rootKey: String? = null) {
        // Handled via Compose UI preferences
    }

    fun getDownloadDirs(context: Context? = null): List<String> {
        val defaultDir = PlatformPaths.downloadsDir.toAbsolutePath().toString()
        val customDir = DesktopDataStore.getKey<String>("download_path_key")
        return listOfNotNull(defaultDir, customDir).filter { it.isNotBlank() }.distinct()
    }
}
