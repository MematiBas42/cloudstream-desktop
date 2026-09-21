package unit

import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.desktop.settings.SettingsGeneral
import com.lagradost.cloudstream3.desktop.settings.appLanguages
import com.lagradost.cloudstream3.desktop.settings.getCurrentLocale
import com.lagradost.cloudstream3.desktop.settings.nameNextToFlagEmoji
import com.lagradost.cloudstream3.desktop.ui.models.CustomSite as ModelCustomSite
import com.lagradost.cloudstream3.utils.USER_PROVIDER_API
import com.lagradost.common.storage.DesktopDataStore
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Locale

/**
 * Concrete parity and wire contract test suite for [SettingsGeneral] and [SettingsGeneral.CustomSite] (Cluster C32).
 *
 * Verifies:
 * 1. Wire contract parity: Upstream Android JSON schema with primary key "parentClassName".
 * 2. Alias tolerance: Legacy desktop JSON schema with "parentJavaClass" (@JsonAlias, @JsonNames).
 * 3. Jackson serialization omits duplicate @get:JsonIgnore parentJavaClass property.
 * 4. Kotlinx serialization bidirectional parity and @JsonNames support.
 * 5. Typealias parity between com.lagradost.cloudstream3.desktop.ui.models.CustomSite and SettingsGeneral.CustomSite.
 * 6. DesktopDataStore persistence roundtrip for USER_PROVIDER_API.
 * 7. SettingsGeneral companion object lifecycle methods (getCurrent, addSite, deleteSites).
 * 8. Language and locale list alphabetical sorting, emoji formatting, and fallback logic.
 * 9. Download path preference setting and getDownloadDirs validation.
 */
class SettingsGeneralRemediationTest {

    private val jsonKotlinx = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    @BeforeEach
    fun setUp() {
        DesktopDataStore.removeKey(USER_PROVIDER_API)
        DesktopDataStore.removeKey("download_path_key")
        DesktopDataStore.removeKey("download_path_key_visual")
    }

    @Test
    fun testCustomSiteJacksonSerializationWireContract() {
        val site = SettingsGeneral.CustomSite(
            parentClassName = "SuperStreamProvider",
            name = "SuperStream Mirror 1",
            url = "https://superstream-mirror.to",
            lang = "en"
        )

        val json = DesktopDataStore.mapper.writeValueAsString(site)

        // Must serialize parentClassName as primary JSON property (1:1 upstream wire contract)
        assertTrue(json.contains("\"parentClassName\":\"SuperStreamProvider\""), "JSON should contain parentClassName")
        assertTrue(json.contains("\"name\":\"SuperStream Mirror 1\""), "JSON should contain name")
        assertTrue(json.contains("\"url\":\"https://superstream-mirror.to\""), "JSON should contain url")
        assertTrue(json.contains("\"lang\":\"en\""), "JSON should contain lang")

        // Must NOT serialize duplicate parentJavaClass property due to @get:JsonIgnore
        assertFalse(json.contains("\"parentJavaClass\""), "JSON should not contain ignored parentJavaClass getter")
    }

    @Test
    fun testCustomSiteJacksonDeserializationWithParentClassName() {
        // Upstream Android JSON format
        val upstreamJson = """
            {
                "parentClassName": "FlixerProvider",
                "name": "My Flixer",
                "url": "https://flixer-custom.io",
                "lang": "tr"
            }
        """.trimIndent()

        val site: SettingsGeneral.CustomSite = DesktopDataStore.mapper.readValue(upstreamJson)

        assertEquals("FlixerProvider", site.parentClassName)
        assertEquals("FlixerProvider", site.parentJavaClass, "Backward compatibility accessor should return parentClassName")
        assertEquals("My Flixer", site.name)
        assertEquals("https://flixer-custom.io", site.url)
        assertEquals("tr", site.lang)
    }

    @Test
    fun testCustomSiteJacksonDeserializationWithLegacyParentJavaClassAlias() {
        // Legacy desktop JSON format with parentJavaClass
        val legacyJson = """
            {
                "parentJavaClass": "SflixProvider",
                "name": "Sflix Fast",
                "url": "https://sflix-fast.to",
                "lang": "en"
            }
        """.trimIndent()

        val site: SettingsGeneral.CustomSite = DesktopDataStore.mapper.readValue(legacyJson)

        assertEquals("SflixProvider", site.parentClassName, "@JsonAlias should map parentJavaClass to parentClassName")
        assertEquals("SflixProvider", site.parentJavaClass)
        assertEquals("SflixFast", site.name.replace(" ", ""))
        assertEquals("https://sflix-fast.to", site.url)
        assertEquals("en", site.lang)
    }

    @Test
    fun testCustomSiteJacksonIgnoreUnknownProperties() {
        val jsonWithExtra = """
            {
                "parentClassName": "ZoroProvider",
                "name": "Zoro Anime",
                "url": "https://aniwatch.to",
                "lang": "en",
                "extraMeta": 42,
                "nestedObject": { "key": "val" },
                "unknownList": [1, 2, 3]
            }
        """.trimIndent()

        val site: SettingsGeneral.CustomSite = DesktopDataStore.mapper.readValue(jsonWithExtra)

        assertEquals("ZoroProvider", site.parentClassName)
        assertEquals("Zoro Anime", site.name)
        assertEquals("https://aniwatch.to", site.url)
        assertEquals("en", site.lang)
    }

    @Test
    fun testCustomSiteKotlinxSerializationParity() {
        val site = SettingsGeneral.CustomSite(
            parentClassName = "CinematicsProvider",
            name = "Cinematics HD",
            url = "https://cinematics.cc",
            lang = "es"
        )

        // Encode via kotlinx.serialization
        val encodedJson = jsonKotlinx.encodeToString(site)
        assertTrue(encodedJson.contains("\"parentClassName\":\"CinematicsProvider\""))

        // Decode upstream format via kotlinx.serialization
        val decodedUpstream: SettingsGeneral.CustomSite = jsonKotlinx.decodeFromString(encodedJson)
        assertEquals(site.parentClassName, decodedUpstream.parentClassName)
        assertEquals(site.name, decodedUpstream.name)
        assertEquals(site.url, decodedUpstream.url)
        assertEquals(site.lang, decodedUpstream.lang)

        // Decode legacy format with parentJavaClass via @JsonNames
        val legacyJson = """{"parentJavaClass":"CinematicsProvider","name":"Cinematics HD","url":"https://cinematics.cc","lang":"es"}"""
        val decodedLegacy: SettingsGeneral.CustomSite = jsonKotlinx.decodeFromString(legacyJson)
        assertEquals("CinematicsProvider", decodedLegacy.parentClassName)
        assertEquals("CinematicsProvider", decodedLegacy.parentJavaClass)
    }

    @Test
    fun testTypealiasParityWithUiModels() {
        // Construct via typealias
        val site: ModelCustomSite = ModelCustomSite(
            parentClassName = "VidSrcProvider",
            name = "VidSrc Mirror",
            url = "https://vidsrc.me",
            lang = "en"
        )

        // Assert type identity
        assertTrue(site is SettingsGeneral.CustomSite)
        assertEquals("VidSrcProvider", site.parentClassName)
        assertEquals("VidSrcProvider", site.parentJavaClass)
    }

    @Test
    fun testDataStoreArrayPersistenceAndRoundtrip() {
        val site1 = SettingsGeneral.CustomSite("ProviderA", "Mirror A", "https://a.com", "en")
        val site2 = SettingsGeneral.CustomSite("ProviderB", "Mirror B", "https://b.com", "tr")

        // Persist array into DesktopDataStore
        DesktopDataStore.setKey(USER_PROVIDER_API, arrayOf(site1, site2))

        val retrieved = SettingsGeneral.getCurrent()
        assertEquals(2, retrieved.size)
        assertEquals("ProviderA", retrieved[0].parentClassName)
        assertEquals("Mirror A", retrieved[0].name)
        assertEquals("ProviderB", retrieved[1].parentClassName)
        assertEquals("Mirror B", retrieved[1].name)
    }

    @Test
    fun testSettingsGeneralCompanionAddAndDeleteSites() {
        // Initially empty
        val initial = SettingsGeneral.getCurrent()
        assertTrue(initial.isEmpty())

        // Add site
        val site1 = SettingsGeneral.CustomSite("Provider1", "Site One", "https://one.com", "en")
        val afterAdd1 = SettingsGeneral.addSite(site1)
        assertEquals(1, afterAdd1.size)
        assertEquals("Provider1", afterAdd1[0].parentClassName)

        // Add second site
        val site2 = SettingsGeneral.CustomSite("Provider2", "Site Two", "https://two.com", "de")
        val afterAdd2 = SettingsGeneral.addSite(site2)
        assertEquals(2, afterAdd2.size)

        // Delete first site
        val afterDelete = SettingsGeneral.deleteSites(listOf(0))
        assertEquals(1, afterDelete.size)
        assertEquals("Provider2", afterDelete[0].parentClassName)

        // Verify DataStore reflected the deletion
        val stored = SettingsGeneral.getCurrent()
        assertEquals(1, stored.size)
        assertEquals("Provider2", stored[0].parentClassName)
    }

    @Test
    fun testLanguageAndLocaleParity() {
        // appLanguages must be non-empty and sorted alphabetically by display name
        assertTrue(appLanguages.isNotEmpty())
        for (i in 0 until appLanguages.size - 1) {
            val curr = appLanguages[i].first.lowercase(Locale.ROOT)
            val next = appLanguages[i + 1].first.lowercase(Locale.ROOT)
            assertTrue(curr <= next, "appLanguages must be sorted alphabetically: $curr <= $next")
        }

        // Verify essential languages are present
        val languageCodes = appLanguages.map { it.second }
        assertTrue(languageCodes.contains("en"))
        assertTrue(languageCodes.contains("tr"))
        assertTrue(languageCodes.contains("es"))
        assertTrue(languageCodes.contains("de"))
        assertTrue(languageCodes.contains("fr"))
        assertTrue(languageCodes.contains("ja"))
        assertTrue(languageCodes.contains("ko"))
        assertTrue(languageCodes.contains("zh"))

        // Verify nameNextToFlagEmoji formatting
        val englishPair = Pair("English", "en")
        val formattedEnglish = englishPair.nameNextToFlagEmoji()
        assertTrue(formattedEnglish.contains("English"))
        assertTrue(formattedEnglish.contains(" "))

        // Verify getCurrentLocale returns valid tag
        val localeTag = getCurrentLocale()
        assertTrue(localeTag.isNotBlank())
    }

    @Test
    fun testDownloadPathAndDirs() {
        val settings = SettingsGeneral()
        val dirs = settings.getDownloadDirs()
        assertNotNull(dirs)
        assertTrue(dirs.isNotEmpty(), "getDownloadDirs should contain at least default downloads dir")

        // Test pickDownloadPath
        SettingsGeneral.pickDownloadPath(null, "/custom/download/path")
        assertEquals("/custom/download/path", DesktopDataStore.getKey<String>("download_path_key"))
        assertEquals("/custom/download/path", DesktopDataStore.getKey<String>("download_path_key_visual"))

        val updatedDirs = settings.getDownloadDirs()
        assertTrue(updatedDirs.contains("/custom/download/path"))
    }
}
