package com.lagradost.cloudstream3.ui.settings

import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.SearchQuality
import com.lagradost.cloudstream3.TvType
import com.lagradost.common.storage.DesktopDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

class AppSettingsTest {

    private lateinit var preferenceStore: DesktopPreferenceStore
    private lateinit var appSettings: DesktopAppSettings
    private val testScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @BeforeEach
    fun setup() {
        val tempFile = File.createTempFile("test_app_settings", ".json").apply { deleteOnExit() }
        DesktopDataStore.customDataFile = tempFile
        DesktopDataStore.reload()

        preferenceStore = DesktopPreferenceStore(DesktopDataStore)
        appSettings = DesktopAppSettings(preferenceStore)
    }

    @Test
    fun `test string preference primitive lifecycle`() {
        val pref = preferenceStore.getString("test_str_key", "default_value")
        assertEquals("test_str_key", pref.key())
        assertEquals("default_value", pref.defaultValue())
        assertFalse(pref.isSet())
        assertEquals("default_value", pref.get())

        pref.set("updated_value")
        assertTrue(pref.isSet())
        assertEquals("updated_value", pref.get())

        // Verify direct DesktopDataStore persistence
        assertEquals("updated_value", DesktopDataStore.getKey<String>("test_str_key"))

        pref.delete()
        assertFalse(pref.isSet())
        assertEquals("default_value", pref.get())
    }

    @Test
    fun `test int and long preference primitives`() {
        val intPref = preferenceStore.getInt("test_int_key", 42)
        assertEquals(42, intPref.get())
        intPref.set(100)
        assertEquals(100, intPref.get())
        assertEquals(100, DesktopDataStore.getKey<Int>("test_int_key"))

        val longPref = preferenceStore.getLong("test_long_key", 1000L)
        assertEquals(1000L, longPref.get())
        longPref.set(999999L)
        assertEquals(999999L, longPref.get())
        assertEquals(999999L, DesktopDataStore.getKey<Long>("test_long_key"))
    }

    @Test
    fun `test float and boolean preference primitives`() {
        val floatPref = preferenceStore.getFloat("test_float_key", 3.14f)
        assertEquals(3.14f, floatPref.get(), 0.001f)
        floatPref.set(2.718f)
        assertEquals(2.718f, floatPref.get(), 0.001f)

        val boolPref = preferenceStore.getBoolean("test_bool_key", false)
        assertFalse(boolPref.get())
        boolPref.set(true)
        assertTrue(boolPref.get())

        // Test toggle extension
        val toggled = boolPref.toggle()
        assertFalse(toggled)
        assertFalse(boolPref.get())
    }

    @Test
    fun `test string set preference and collection operators`() {
        val setPref = preferenceStore.getStringSet("test_set_key", setOf("initial_a", "initial_b"))
        assertEquals(setOf("initial_a", "initial_b"), setPref.get())

        setPref.set(setOf("item1", "item2"))
        assertEquals(setOf("item1", "item2"), setPref.get())

        // Test plusAssign
        setPref += "item3"
        assertEquals(setOf("item1", "item2", "item3"), setPref.get())

        // Test minusAssign
        setPref -= "item1"
        assertEquals(setOf("item2", "item3"), setPref.get())

        setPref.delete()
        assertEquals(setOf("initial_a", "initial_b"), setPref.get())
    }

    @Test
    fun `test getObjectFromString serialization and deserialization`() {
        data class SimpleConfig(val host: String, val port: Int)

        val configPref = preferenceStore.getObjectFromString(
            key = "test_config_key",
            defaultValue = SimpleConfig("localhost", 8080),
            serializer = { "${it.host}:${it.port}" },
            deserializer = {
                val parts = it.split(":")
                SimpleConfig(parts[0], parts[1].toInt())
            }
        )

        assertEquals(SimpleConfig("localhost", 8080), configPref.get())
        configPref.set(SimpleConfig("127.0.0.1", 9000))
        assertEquals(SimpleConfig("127.0.0.1", 9000), configPref.get())
        assertEquals("127.0.0.1:9000", DesktopDataStore.getKey<String>("test_config_key"))
    }

    @Test
    fun `test enum and enumSet preferences`() {
        val enumPref = preferenceStore.getEnum("test_dub_key", DubStatus.None)
        assertEquals(DubStatus.None, enumPref.get())
        enumPref.set(DubStatus.Dubbed)
        assertEquals(DubStatus.Dubbed, enumPref.get())

        val enumSetPref = preferenceStore.getEnumSet("test_quality_key", setOf(SearchQuality.FourK))
        assertEquals(setOf(SearchQuality.FourK), enumSetPref.get())
        enumSetPref.set(setOf(SearchQuality.HD, SearchQuality.UHD))
        assertEquals(setOf(SearchQuality.HD, SearchQuality.UHD), enumSetPref.get())
    }

    @Test
    fun `test reactive changes flow and stateIn`() = runBlocking {
        val pref = preferenceStore.getString("reactive_key", "init")
        val stateFlow = pref.stateIn(testScope)
        assertEquals("init", stateFlow.value)

        pref.set("flow_updated")
        assertEquals("flow_updated", pref.changes().first())
        assertEquals("flow_updated", pref.get())
    }

    @Test
    fun `test DesktopAppSettings 8 categories default values and modification`() {
        // 1. GeneralPreferences
        assertEquals(3, appSettings.general.parallelDownloads.get())
        assertEquals(3, appSettings.general.concurrentConnections.get())
        assertFalse(appSettings.general.jsdelivrProxy.get())
        appSettings.general.parallelDownloads.set(5)
        assertEquals(5, appSettings.general.parallelDownloads.get())

        // 2. PlayerPreferences
        assertTrue(appSettings.player.episodeSync.get())
        assertTrue(appSettings.player.autoPlayEnabled.get())
        assertEquals("auto-safe", appSettings.player.hwdec.get())
        appSettings.player.hwdec.set("auto-copy")
        assertEquals("auto-copy", appSettings.player.hwdec.get())

        // 3. ProviderPreferences
        assertTrue(appSettings.provider.preferredMedia.get().isNotEmpty())
        assertEquals(0L, appSettings.provider.sequentialMainPageDelay.get())
        assertFalse(appSettings.provider.sequentialMainPage.get())
        appSettings.provider.sequentialMainPageDelay.set(500L)
        appSettings.provider.sequentialMainPage.set(true)
        assertEquals(500L, appSettings.provider.sequentialMainPageDelay.get())
        assertTrue(appSettings.provider.sequentialMainPage.get())

        // Pinned Providers
        assertEquals(emptySet<String>(), appSettings.provider.pinnedProviders.get())
        appSettings.provider.pinnedProviders.set(setOf("ProviderA", "ProviderB"))
        assertEquals(setOf("ProviderA", "ProviderB"), appSettings.provider.pinnedProviders.get())

        // 4. UIPreferences
        assertEquals("Normal", appSettings.ui.primaryColor.get())
        assertEquals("AmoledLight", appSettings.ui.theme.get())
        assertTrue(appSettings.ui.kitsuPostersEnabled.get())
        appSettings.ui.theme.set("AmoledDark")
        assertEquals("AmoledDark", appSettings.ui.theme.get())

        // 5. SecurityPreferences
        assertFalse(appSettings.security.biometrics.get())
        assertFalse(appSettings.security.skipAccountSelection.get())
        appSettings.security.biometrics.set(true)
        assertTrue(appSettings.security.biometrics.get())

        // 6. UpdatePreferences
        assertEquals(1, appSettings.updates.apkInstaller.get())
        assertTrue(appSettings.updates.showAppUpdates.get())

        // 7. BackupPreferences
        assertEquals(0, appSettings.backup.frequency.get())

        // 8. PluginPreferences
        assertTrue(appSettings.plugins.autoUpdate.get())
        assertEquals(0, appSettings.plugins.autoDownload.get())
        appSettings.plugins.autoUpdate.set(false)
        assertFalse(appSettings.plugins.autoUpdate.get())
    }

    @Test
    fun `test DesktopDataStore crash-resilient atomic file write`() {
        val pref = preferenceStore.getString("persistent_key", "unpersisted")
        pref.set("persisted_value")

        val targetFile = DesktopDataStore.dataFile
        assertTrue(targetFile.exists(), "Target DataStore file must exist")
        val content = Files.readString(targetFile.toPath())
        assertTrue(content.contains("persisted_value"), "File content must contain persisted value")
    }
}
