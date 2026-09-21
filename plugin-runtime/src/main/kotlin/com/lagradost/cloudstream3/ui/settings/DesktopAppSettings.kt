// @PortSource(file = "upstream/shared/src/commonMain/kotlin/com/lagradost/cloudstream4/DevicePreferenceStore.kt", upstreamCommit = "caeec18")
package com.lagradost.cloudstream3.ui.settings

import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.AllLanguagesName
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.SearchQuality
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.annotation.PlatformQuarantine
import com.lagradost.cloudstream3.annotation.QuarantineStatus
import com.lagradost.cloudstream3.utils.USER_PINNED_PROVIDERS
import com.lagradost.common.storage.DesktopDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

/**
 * Common preference store contract matching upstream CloudStream4 / Mihon preference interface.
 */
interface PreferenceStore {
    fun getString(key: String, defaultValue: String = ""): PreferenceData<String>
    fun getLong(key: String, defaultValue: Long = 0L): PreferenceData<Long>
    fun getInt(key: String, defaultValue: Int = 0): PreferenceData<Int>
    fun getFloat(key: String, defaultValue: Float = 0f): PreferenceData<Float>
    fun getBoolean(key: String, defaultValue: Boolean = false): PreferenceData<Boolean>
    fun getStringSet(key: String, defaultValue: Set<String> = emptySet()): PreferenceData<Set<String>>
    fun <T> getObjectFromString(
        key: String,
        defaultValue: T,
        serializer: (T) -> String,
        deserializer: (String) -> T,
    ): PreferenceData<T>
    fun <T> getObjectFromInt(
        key: String,
        defaultValue: T,
        serializer: (T) -> Int,
        deserializer: (Int) -> T,
    ): PreferenceData<T>
    fun <T> getObjectSetFromStringSet(
        key: String,
        defaultValue: Set<T>,
        serializer: (T) -> String,
        deserializer: (String) -> T?,
    ): PreferenceData<Set<T>>
    fun getAll(): Map<String, *>
}

fun PreferenceStore.getLongArray(
    key: String,
    defaultValue: List<Long>,
): PreferenceData<List<Long>> {
    return getObjectFromString(
        key = key,
        defaultValue = defaultValue,
        serializer = { it.joinToString(",") },
        deserializer = { it.split(",").mapNotNull { l -> l.trim().toLongOrNull() } },
    )
}

inline fun <reified T : Enum<T>> PreferenceStore.getEnum(
    key: String,
    defaultValue: T,
): PreferenceData<T> {
    return getObjectFromString(
        key = key,
        defaultValue = defaultValue,
        serializer = { it.name },
        deserializer = {
            try {
                enumValueOf(it)
            } catch (e: IllegalArgumentException) {
                defaultValue
            }
        },
    )
}

inline fun <reified T : Enum<T>> PreferenceStore.getEnumSet(
    key: String,
    defaultValue: Set<T>,
): PreferenceData<Set<T>> {
    return getObjectSetFromStringSet(
        key = key,
        defaultValue = defaultValue,
        serializer = { it.name },
        deserializer = {
            try {
                enumValueOf<T>(it)
            } catch (_: IllegalArgumentException) {
                null
            }
        },
    )
}

interface PreferenceData<T> {
    fun key(): String
    fun get(): T
    fun set(value: T)
    fun isSet(): Boolean
    fun delete()
    fun defaultValue(): T
    fun changes(): Flow<T>
    fun stateIn(scope: CoroutineScope): StateFlow<T>

    companion object {
        fun isPrivate(key: String): Boolean = key.startsWith(PRIVATE_PREFIX)
        fun privateKey(key: String): String = "$PRIVATE_PREFIX$key"
        fun isAppState(key: String): Boolean = key.startsWith(APP_STATE_PREFIX)
        fun appStateKey(key: String): String = "$APP_STATE_PREFIX$key"

        private const val APP_STATE_PREFIX = "__APP_STATE_"
        private const val PRIVATE_PREFIX = "__PRIVATE_"
    }
}

inline fun <reified T, R : T> PreferenceData<T>.getAndSet(crossinline block: (T) -> R) = set(
    block(get()),
)

operator fun <T> PreferenceData<Set<T>>.plusAssign(item: T) {
    set(get() + item)
}

operator fun <T> PreferenceData<Set<T>>.plusAssign(items: Iterable<T>) {
    set(get() + items)
}

operator fun <T> PreferenceData<Set<T>>.minusAssign(item: T) {
    set(get() - item)
}

fun PreferenceData<Boolean>.toggle(): Boolean {
    set(!get())
    return get()
}

/**
 * Concrete PreferenceData implementation backed directly by DesktopDataStore atomic persistence.
 */
class DesktopPrimitivePreference<T>(
    private val key: String,
    private val defaultValue: T,
    private val keyFlow: MutableSharedFlow<String?>,
    private val reader: () -> T,
    private val writer: (T) -> Unit,
) : PreferenceData<T> {
    override fun key(): String = key
    override fun defaultValue(): T = defaultValue
    override fun isSet(): Boolean = DesktopDataStore.containsKey(key)

    override fun get(): T = try {
        reader()
    } catch (e: Exception) {
        defaultValue
    }

    override fun set(value: T) {
        writer(value)
        keyFlow.tryEmit(key)
    }

    override fun delete() {
        DesktopDataStore.removeKey(key)
        keyFlow.tryEmit(key)
    }

    override fun changes(): Flow<T> {
        return keyFlow
            .filter { it == key || it == null }
            .onStart { emit("ignition") }
            .map { get() }
            .conflate()
    }

    override fun stateIn(scope: CoroutineScope): StateFlow<T> {
        return changes().stateIn(scope, SharingStarted.Eagerly, get())
    }
}

/**
 * DesktopDataStore-backed atomic PreferenceStore implementation.
 */
class DesktopPreferenceStore(
    private val dataStore: DesktopDataStore = DesktopDataStore
) : PreferenceStore {

    companion object {
        internal val keyFlow: MutableSharedFlow<String?> = MutableSharedFlow(extraBufferCapacity = 64)
    }

    override fun getString(key: String, defaultValue: String): PreferenceData<String> {
        return DesktopPrimitivePreference(
            key = key,
            defaultValue = defaultValue,
            keyFlow = keyFlow,
            reader = {
                val json = dataStore.getRaw(key) ?: return@DesktopPrimitivePreference defaultValue
                try {
                    dataStore.mapper.readValue<String>(json)
                } catch (_: Exception) {
                    json
                }
            },
            writer = { value -> dataStore.setKey(key, value) }
        )
    }

    override fun getLong(key: String, defaultValue: Long): PreferenceData<Long> {
        return DesktopPrimitivePreference(
            key = key,
            defaultValue = defaultValue,
            keyFlow = keyFlow,
            reader = {
                val json = dataStore.getRaw(key) ?: return@DesktopPrimitivePreference defaultValue
                try {
                    dataStore.mapper.readValue<Long>(json)
                } catch (_: Exception) {
                    json.removeSurrounding("\"").toLongOrNull() ?: defaultValue
                }
            },
            writer = { value -> dataStore.setKey(key, value) }
        )
    }

    override fun getInt(key: String, defaultValue: Int): PreferenceData<Int> {
        return DesktopPrimitivePreference(
            key = key,
            defaultValue = defaultValue,
            keyFlow = keyFlow,
            reader = {
                val json = dataStore.getRaw(key) ?: return@DesktopPrimitivePreference defaultValue
                try {
                    dataStore.mapper.readValue<Int>(json)
                } catch (_: Exception) {
                    json.removeSurrounding("\"").toIntOrNull() ?: defaultValue
                }
            },
            writer = { value -> dataStore.setKey(key, value) }
        )
    }

    override fun getFloat(key: String, defaultValue: Float): PreferenceData<Float> {
        return DesktopPrimitivePreference(
            key = key,
            defaultValue = defaultValue,
            keyFlow = keyFlow,
            reader = {
                val json = dataStore.getRaw(key) ?: return@DesktopPrimitivePreference defaultValue
                try {
                    dataStore.mapper.readValue<Float>(json)
                } catch (_: Exception) {
                    json.removeSurrounding("\"").toFloatOrNull() ?: defaultValue
                }
            },
            writer = { value -> dataStore.setKey(key, value) }
        )
    }

    override fun getBoolean(key: String, defaultValue: Boolean): PreferenceData<Boolean> {
        return DesktopPrimitivePreference(
            key = key,
            defaultValue = defaultValue,
            keyFlow = keyFlow,
            reader = {
                val json = dataStore.getRaw(key) ?: return@DesktopPrimitivePreference defaultValue
                try {
                    dataStore.mapper.readValue<Boolean>(json)
                } catch (_: Exception) {
                    json.removeSurrounding("\"").toBooleanStrictOrNull() ?: defaultValue
                }
            },
            writer = { value -> dataStore.setKey(key, value) }
        )
    }

    override fun getStringSet(key: String, defaultValue: Set<String>): PreferenceData<Set<String>> {
        return DesktopPrimitivePreference(
            key = key,
            defaultValue = defaultValue,
            keyFlow = keyFlow,
            reader = {
                val json = dataStore.getRaw(key) ?: return@DesktopPrimitivePreference defaultValue
                try {
                    dataStore.mapper.readValue<Set<String>>(json)
                } catch (_: Exception) {
                    try {
                        dataStore.mapper.readValue<List<String>>(json).toSet()
                    } catch (_: Exception) {
                        defaultValue
                    }
                }
            },
            writer = { value -> dataStore.setKey(key, value) }
        )
    }

    override fun <T> getObjectFromString(
        key: String,
        defaultValue: T,
        serializer: (T) -> String,
        deserializer: (String) -> T,
    ): PreferenceData<T> {
        return DesktopPrimitivePreference(
            key = key,
            defaultValue = defaultValue,
            keyFlow = keyFlow,
            reader = {
                val json = dataStore.getRaw(key) ?: return@DesktopPrimitivePreference defaultValue
                try {
                    val rawStr = try {
                        dataStore.mapper.readValue<String>(json)
                    } catch (_: Exception) {
                        json
                    }
                    deserializer(rawStr)
                } catch (_: Exception) {
                    defaultValue
                }
            },
            writer = { value -> dataStore.setKey(key, serializer(value)) }
        )
    }

    override fun <T> getObjectFromInt(
        key: String,
        defaultValue: T,
        serializer: (T) -> Int,
        deserializer: (Int) -> T,
    ): PreferenceData<T> {
        return DesktopPrimitivePreference(
            key = key,
            defaultValue = defaultValue,
            keyFlow = keyFlow,
            reader = {
                val json = dataStore.getRaw(key) ?: return@DesktopPrimitivePreference defaultValue
                try {
                    val intVal = try {
                        dataStore.mapper.readValue<Int>(json)
                    } catch (_: Exception) {
                        json.removeSurrounding("\"").toIntOrNull()
                    } ?: return@DesktopPrimitivePreference defaultValue
                    deserializer(intVal)
                } catch (_: Exception) {
                    defaultValue
                }
            },
            writer = { value -> dataStore.setKey(key, serializer(value)) }
        )
    }

    override fun <T> getObjectSetFromStringSet(
        key: String,
        defaultValue: Set<T>,
        serializer: (T) -> String,
        deserializer: (String) -> T?,
    ): PreferenceData<Set<T>> {
        return DesktopPrimitivePreference(
            key = key,
            defaultValue = defaultValue,
            keyFlow = keyFlow,
            reader = {
                val json = dataStore.getRaw(key) ?: return@DesktopPrimitivePreference defaultValue
                try {
                    val rawSet = try {
                        dataStore.mapper.readValue<Set<String>>(json)
                    } catch (_: Exception) {
                        dataStore.mapper.readValue<List<String>>(json).toSet()
                    }
                    rawSet.mapNotNull(deserializer).toSet()
                } catch (_: Exception) {
                    defaultValue
                }
            },
            writer = { value -> dataStore.setKey(key, value.map(serializer).toSet()) }
        )
    }

    override fun getAll(): Map<String, *> {
        return dataStore.getAll()
    }
}

/**
 * Desktop AppSettings container, providing typed access to all 8 setting categories:
 * general, player, provider, ui, security, updates, backup, and plugins.
 */
open class DesktopAppSettings(
    val preferences: PreferenceStore = DesktopPreferenceStore()
) {
    constructor(context: Any?) : this(DesktopPreferenceStore())
    val general = GeneralPreferences(preferences)
    val player = PlayerPreferences(preferences)
    val provider = ProviderPreferences(preferences)
    val ui = UIPreferences(preferences)
    val security = SecurityPreferences(preferences)
    val updates = UpdatePreferences(preferences)
    val backup = BackupPreferences(preferences)
    val plugins = PluginPreferences(preferences)

    companion object : DesktopAppSettings(DesktopPreferenceStore()) {
        fun getInstance(): DesktopAppSettings = this
    }
}

class PluginPreferences(preferences: PreferenceStore) {
    val autoUpdate = preferences.getBoolean("auto_update_plugins", true)
    val autoDownload = preferences.getInt("auto_download_plugins_key2", 0)
}

class BackupPreferences(preferences: PreferenceStore) {
    val frequency = preferences.getInt("automatic_backup_key", 0)
    val path = preferences.getString("backup_path_key")
    val visualPath = preferences.getString("backup_dir_key")
}

class UpdatePreferences(preferences: PreferenceStore) {
    @PlatformQuarantine(
        reason = "Android PackageInstaller selection (legacy vs modern) is Android-specific; Linux desktop updates use native packaging/distribution",
        upstreamRef = "upstream/app/src/main/res/values/strings.xml:apk_installer_key",
        status = QuarantineStatus.NOT_APPLICABLE_DESKTOP
    )
    val apkInstaller = preferences.getInt("apk_installer_key", 1)
    val showAppUpdates = preferences.getBoolean("auto_update", true)
}

class SecurityPreferences(preferences: PreferenceStore) {
    @PlatformQuarantine(
        reason = "Mobile biometric hardware (fingerprint/face unlock) is Android-specific; Linux desktop uses PinSecurity",
        upstreamRef = "upstream/app/src/main/java/com/lagradost/cloudstream3/utils/BiometricAuthenticator.kt",
        status = QuarantineStatus.NOT_APPLICABLE_DESKTOP
    )
    val biometrics = preferences.getBoolean(
        "biometric_key", false
    )
    val skipAccountSelection = preferences.getBoolean(
        "skip_startup_account_select_key", false
    )
}

class UIPreferences(preferences: PreferenceStore) {
    val primaryColor = preferences.getString(
        "primary_color_key", "Normal"
    )
    val theme = preferences.getString(
        "app_theme_key", "AmoledLight"
    )
    val layout = preferences.getInt(
        "app_layout_key", -1
    )
    val bottomTitle = preferences.getBoolean(
        "bottom_title_key", true
    )
    val advancedSearch = preferences.getBoolean(
        "advanced_search", true
    )
    val searchSuggestions = preferences.getBoolean(
        "search_suggestions_enabled", true
    )
    val kitsuPostersEnabled = preferences.getBoolean(
        "show_kitsu_posters_key", true
    )
    val trailersEnabled = preferences.getBoolean(
        "show_trailers_key", true
    )
    val castEnabled = preferences.getBoolean(
        "show_cast_in_details_key", true
    )
    val fillersEnabled = preferences.getBoolean(
        "show_fillers_key", false
    )
    val showMetadataOverlay = preferences.getBoolean(
        "show_player_metadata_key", true
    )
    @PlatformQuarantine(
        reason = "Overscan compensation is CRT/Android TV display-specific; Linux desktop handles window boundaries via window manager/display server",
        upstreamRef = "upstream/app/src/main/res/values/strings.xml:overscan_key",
        status = QuarantineStatus.NOT_APPLICABLE_DESKTOP
    )
    val overscanDp = preferences.getInt(
        "overscan_key", 0
    )
    val posterSize = preferences.getInt(
        "poster_size_key", 0
    )
    val posterShowHd = preferences.getBoolean(
        "show_hd_key", true
    )
    val posterShowDub = preferences.getBoolean(
        "show_dub_key", true
    )
    val posterShowSub = preferences.getBoolean(
        "show_sub_key", true
    )
    val posterShowRating = preferences.getBoolean(
        "show_rating_key", true
    )
    val posterShowTitle = preferences.getBoolean(
        "show_title_key", true
    )
    val posterShowEpisode = preferences.getBoolean(
        "show_episode_text_key", true
    )
    @PlatformQuarantine(
        reason = "Android TV status bar clock is TV-specific; desktop UI relies on system tray/desktop panel clock",
        upstreamRef = "upstream/app/src/main/res/values/strings.xml:tv_layout_clock_key",
        status = QuarantineStatus.NOT_APPLICABLE_DESKTOP
    )
    val showClock = preferences.getBoolean(
        "tv_layout_clock_key", false
    )

    val randomButtonEnabled = preferences.getBoolean(
        "random_button_key", false
    )

    val confirmExit = preferences.getInt(
        "confirm_exit_key", -1
    )

    val filterQuality = preferences.getEnumSet<SearchQuality>(
        "pref_filter_search_quality_key2", emptySet()
    )
}

class ProviderPreferences(preferences: PreferenceStore) {
    companion object {
        val defaultPreferredMedia: Set<String> =
            TvType.entries.filter { it != TvType.NSFW }.map { it.ordinal.toString() }.toSet()
        val defaultDub: Set<String> = DubStatus.entries.map { it.name }.toSet()
    }

    val preferredMedia = preferences.getStringSet(
        "prefer_media_type_key_2", defaultPreferredMedia
    )

    val extensionLanguages = preferences.getStringSet(
        "provider_lang_key", setOf(AllLanguagesName)
    )

    val displayDubSub = preferences.getStringSet(
        "display_sub_key", defaultDub
    )

    val pinnedProviders = preferences.getStringSet(
        USER_PINNED_PROVIDERS, emptySet()
    )

    val sequentialMainPageDelay = preferences.getLong(
        "sequential_main_page_delay", 0L
    )

    val sequentialMainPage = preferences.getBoolean(
        "sequential_main_page", false
    )
}

class PlayerPreferences(preferences: PreferenceStore) {
    val episodeSync = preferences.getBoolean("episode_sync_enabled_key", true)
    val defaultPlayer = preferences.getString("player_default_key", "")
    val limitPlayerTitle = preferences.getInt("prefer_limit_title_key", 0)
    val hidePlayerControlNames = preferences.getBoolean("hide_player_control_names_key", false)
    val showName = preferences.getBoolean("show_name", true)
    val showResolution = preferences.getBoolean("show_resolution", true)
    val showMediaInfo = preferences.getBoolean("show_media_info", false)
    @PlatformQuarantine(
        reason = "Android Activity Picture-in-Picture mode is Android-specific; Linux desktop uses window manager floating rules or compact detached player",
        upstreamRef = "upstream/app/src/main/java/com/lagradost/cloudstream3/ui/player/PlayerPipHelper.kt",
        status = QuarantineStatus.NOT_APPLICABLE_DESKTOP
    )
    val pipEnabled = preferences.getBoolean("pip_enabled_key", true)
    val resizeEnabled = preferences.getBoolean("player_resize_enabled_key", true)
    val speedEnabled = preferences.getBoolean("playback_speed_enabled_key", false)
    val tiktokEnabled = preferences.getBoolean("speedup_key", false)
    val autoPlayEnabled = preferences.getBoolean("autoplay_next_key", true)
    val startPaused = preferences.getBoolean("start_paused_key", false)
    val skipOpEnabled = preferences.getBoolean("enable_skip_op_from_database", true)
    val autoSkipIntro = preferences.getBoolean("auto_skip_intro", false)
    @PlatformQuarantine(
        reason = "Screen auto-rotation is mobile-specific; desktop display orientation is managed by X11/Wayland display server",
        upstreamRef = "upstream/app/src/main/res/values/strings.xml:auto_rotate_video_key",
        status = QuarantineStatus.NOT_APPLICABLE_DESKTOP
    )
    val autoRotateEnabled = preferences.getBoolean("auto_rotate_video_key", true)

    @PlatformQuarantine(
        reason = "Manual rotation button is mobile-specific; desktop display orientation is managed by window manager",
        upstreamRef = "upstream/app/src/main/res/values/strings.xml:rotate_video_key",
        status = QuarantineStatus.NOT_APPLICABLE_DESKTOP
    )
    val rotateButtonEnabled = preferences.getBoolean("rotate_video_key", false)

    val previewBarEnabled = preferences.getBoolean("preview_seekbar_key", true)
    val softwareDecoding = preferences.getInt("software_decoding_key2", -1)

    @PlatformQuarantine(
        reason = "Mobile screen backlight override is mobile-specific; desktop monitors handle brightness via hardware or DDC/CI",
        upstreamRef = "upstream/app/src/main/res/values/strings.xml:extra_brightness_key",
        status = QuarantineStatus.NOT_APPLICABLE_DESKTOP
    )
    val extraBrightnessEnabled = preferences.getBoolean("extra_brightness_key", false)

    @PlatformQuarantine(
        reason = "Touch screen swipe gestures are mobile-specific; desktop player uses mouse wheel and keyboard shortcuts",
        upstreamRef = "upstream/app/src/main/res/values/strings.xml:swipe_enabled_key",
        status = QuarantineStatus.NOT_APPLICABLE_DESKTOP
    )
    val swipeHorizontalEnabled = preferences.getBoolean("swipe_enabled_key", true)

    @PlatformQuarantine(
        reason = "Vertical volume/brightness touch swipe is mobile-specific; desktop player uses mouse wheel and arrow keys",
        upstreamRef = "upstream/app/src/main/res/values/strings.xml:swipe_vertical_enabled_key",
        status = QuarantineStatus.NOT_APPLICABLE_DESKTOP
    )
    val swipeVerticalEnabled = preferences.getBoolean("swipe_vertical_enabled_key", true)

    @PlatformQuarantine(
        reason = "Double-tap touch gestures are mobile-specific; desktop player uses Left/Right Arrow keys or mouse click",
        upstreamRef = "upstream/app/src/main/res/values/strings.xml:double_tap_enabled_key",
        status = QuarantineStatus.NOT_APPLICABLE_DESKTOP
    )
    val doubleTapToSeekEnabled = preferences.getBoolean("double_tap_enabled_key", false)

    @PlatformQuarantine(
        reason = "Double-tap pause touch gesture is mobile-specific; desktop player uses Space key",
        upstreamRef = "upstream/app/src/main/res/values/strings.xml:double_tap_pause_enabled_key",
        status = QuarantineStatus.NOT_APPLICABLE_DESKTOP
    )
    val doubleTapToPauseEnabled = preferences.getBoolean("double_tap_pause_enabled_key", false)

    val doubleTapTime = preferences.getInt("double_tap_seek_time_key2", 10)
    val bufferDiskMB = preferences.getInt("video_buffer_disk_key", 0)
    val bufferRamMB = preferences.getInt("video_buffer_size_key", 0)
    val bufferTimeSec = preferences.getInt("video_buffer_length_key", 0)

    @PlatformQuarantine(
        reason = "Android TV D-pad continuous seek timing is TV-specific; desktop player uses precise keyboard seek steps",
        upstreamRef = "upstream/app/src/main/res/values/strings.xml:android_tv_interface_on_seek_key",
        status = QuarantineStatus.NOT_APPLICABLE_DESKTOP
    )
    val tvSeekOnTime = preferences.getInt("android_tv_interface_on_seek_key", 10)

    @PlatformQuarantine(
        reason = "Android TV D-pad seek release timing is TV-specific; desktop player uses direct key events",
        upstreamRef = "upstream/app/src/main/res/values/strings.xml:android_tv_interface_off_seek_key",
        status = QuarantineStatus.NOT_APPLICABLE_DESKTOP
    )
    val tvSeekOffTime = preferences.getInt("android_tv_interface_off_seek_key", 10)

    val hwdec = preferences.getString("player_hwdec", "auto-safe")
    val vo = preferences.getString("player_vo", "gpu")
    val ytdlFormat = preferences.getString("player_ytdl_format", "bestvideo[height<=?1080]+bestaudio/best")
}

class GeneralPreferences(preferences: PreferenceStore) {
    val appLanguage = preferences.getString("app_language", "")
    val locale = preferences.getString("app_locale", "")
    val localeAudio = preferences.getString("locale_audio", "")
    val localeSub = preferences.getString("locale_sub", "")
    val bananas = preferences.getInt("benene_count", 0)
    val parallelDownloads = preferences.getInt("download_parallel_key", 3)
    val concurrentConnections = preferences.getInt("download_concurrent_key", 3)
    @PlatformQuarantine(
        reason = "Android battery optimization whitelist is mobile-specific; Linux desktop uses systemd-inhibit or runs unconstrained",
        upstreamRef = "upstream/app/src/main/java/com/lagradost/cloudstream3/utils/PowerManagerAPI.kt",
        status = QuarantineStatus.NOT_APPLICABLE_DESKTOP
    )
    val batterOptimization = preferences.getBoolean("battery_optimisation_key", false)
    val dns = preferences.getInt("dns_key", 0)
    val overrideSite = preferences.getString("override_site_key", "")
    val jsdelivrProxy = preferences.getBoolean("jsdelivr_proxy_key", false)
    val downloadPath = preferences.getString("download_path_key", "")
    val downloadPathVisual = preferences.getString("download_path_key_visual", "")
}
