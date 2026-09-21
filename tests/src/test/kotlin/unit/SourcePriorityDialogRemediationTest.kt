package unit

import android.app.Activity
import android.content.Context
import com.lagradost.cloudstream3.ui.player.source_priority.LinkSource
import com.lagradost.cloudstream3.ui.player.source_priority.ProfileSettings
import com.lagradost.cloudstream3.ui.player.source_priority.QualityDataHelper
import com.lagradost.cloudstream3.ui.player.source_priority.QualityDataHelper.QualityProfileType
import com.lagradost.cloudstream3.ui.player.source_priority.QualityProfileDialog
import com.lagradost.cloudstream3.ui.player.source_priority.SourcePriority
import com.lagradost.cloudstream3.ui.player.source_priority.SourcePriorityDialog
import com.lagradost.cloudstream3.ui.player.source_priority.SourceProfileSettingsDialog
import com.lagradost.cloudstream3.utils.Qualities
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Concrete parity and remediation test suite for [SourcePriorityDialog], [QualityProfileDialog],
 * and [SourceProfileSettingsDialog] (Cluster C26).
 *
 * Verifies:
 * 1. [SourcePriority] data class properties, priority score mutability, and copy semantics.
 * 2. Exact upstream constructor and class signatures for [SourcePriorityDialog] and [QualityProfileDialog].
 * 3. Sorting algorithms for source links and video qualities in descending priority order.
 * 4. Deduplication of mirror source links by name.
 * 5. Atomic persistence of modified source and quality priorities to [QualityDataHelper].
 * 6. Profile naming contract: blank/whitespace clearing vs trimmed custom name storage.
 * 7. [QualityProfileDialog] constructors (overloads for in-player selection vs standalone settings).
 * 8. Asynchronous retrieval of all distinct sources across profiles via [QualityProfileDialog.getAllDefaultSources].
 * 9. Active profile selection and callback invocation on apply.
 * 10. Default profile type configuration enforcing unique constraints (WiFi, Mobile Data, Download).
 * 11. [SourceProfileSettingsDialog] boolean toggle persistence for HideErrorSources and HideNegativeSources.
 * 12. Lifecycle state management, show/dismiss events, and headless environment execution safety.
 */
class SourcePriorityDialogRemediationTest {

    private lateinit var context: Context
    private lateinit var activity: Activity

    @BeforeEach
    fun setUp() {
        context = Context()
        activity = Activity()
    }

    @Test
    fun `test SourcePriority data class equality mutability and copy semantics`() {
        val item1 = SourcePriority("DATA1", "GogoAnime", 5)
        assertEquals("DATA1", item1.data)
        assertEquals("GogoAnime", item1.name)
        assertEquals(5, item1.priority)

        // Mutability check
        item1.priority++
        assertEquals(6, item1.priority)

        item1.priority--
        assertEquals(5, item1.priority)

        // Copy semantics
        val item2 = item1.copy(priority = 10)
        assertEquals(10, item2.priority)
        assertEquals("GogoAnime", item2.name)

        // Generic type safety with Qualities
        val qualityItem = SourcePriority(Qualities.P1080, "1080p", 8)
        assertEquals(Qualities.P1080, qualityItem.data)
        assertEquals("1080p", qualityItem.name)
        assertEquals(8, qualityItem.priority)
    }

    @Test
    fun `test SourcePriorityDialog initialization with exact constructor signatures`() {
        val profiles = QualityDataHelper.getProfiles()
        val profile = profiles.first()

        val links = listOf(
            LinkSource("StreamTape"),
            LinkSource("VidCloud"),
            LinkSource("MixDrop")
        )

        val updatedInvoked = AtomicBoolean(false)
        val dialog = SourcePriorityDialog(
            ctx = context,
            themeRes = 0,
            links = links,
            profile = profile,
            updatedCallback = { updatedInvoked.set(true) }
        )

        assertEquals(context, dialog.ctx)
        assertEquals(0, dialog.themeRes)
        assertEquals(profile.id, dialog.profileId)
        assertEquals(links, dialog.links)
        assertFalse(dialog.isShowing())

        dialog.show()
        assertTrue(dialog.isShowing())

        dialog.dismiss()
        assertFalse(dialog.isShowing())
    }

    @Test
    fun `test SourcePriorityDialog getSortedSources extracts distinct sources sorted by priority descending`() {
        val profileId = 2
        val profile = QualityDataHelper.getProfiles().first { it.id == profileId }

        // Setup distinct priorities in QualityDataHelper
        QualityDataHelper.setSourcePriority(profileId, "HostLow", 1)
        QualityDataHelper.setSourcePriority(profileId, "HostHigh", 10)
        QualityDataHelper.setSourcePriority(profileId, "HostMid", 5)

        val links = listOf(
            LinkSource("HostLow"),
            LinkSource("HostHigh"),
            LinkSource("HostMid"),
            LinkSource("HostLow") // Duplicate to test deduplication
        )

        val dialog = SourcePriorityDialog(
            ctx = context,
            links = links,
            profile = profile,
            updatedCallback = {}
        )

        val sorted = dialog.getSortedSources()
        assertEquals(3, sorted.size, "Duplicate sources must be deduplicated by name")
        assertEquals("HostHigh", sorted[0].name)
        assertEquals(10, sorted[0].priority)
        assertEquals("HostMid", sorted[1].name)
        assertEquals(5, sorted[1].priority)
        assertEquals("HostLow", sorted[2].name)
        assertEquals(1, sorted[2].priority)
    }

    @Test
    fun `test SourcePriorityDialog getSortedQualities maps all Qualities entries sorted by priority descending`() {
        val profileId = 3
        val profile = QualityDataHelper.getProfiles().first { it.id == profileId }

        // Configure custom priorities for specific qualities
        QualityDataHelper.setQualityPriority(profileId, Qualities.P2160, 20)
        QualityDataHelper.setQualityPriority(profileId, Qualities.P1080, 15)
        QualityDataHelper.setQualityPriority(profileId, Qualities.P360, 2)

        val dialog = SourcePriorityDialog(
            ctx = context,
            links = emptyList(),
            profile = profile,
            updatedCallback = {}
        )

        val qualities = dialog.getSortedQualities()
        assertTrue(qualities.isNotEmpty(), "Qualities list must not be empty")

        // First item should have the highest configured priority
        val firstItem = qualities.first()
        assertTrue(firstItem.priority >= 15, "Top quality should have highest priority")

        // Verify descending sort order across the entire list
        for (i in 0 until qualities.size - 1) {
            assertTrue(
                qualities[i].priority >= qualities[i + 1].priority,
                "Qualities must be sorted in descending priority: ${qualities[i].priority} >= ${qualities[i + 1].priority}"
            )
        }
    }

    @Test
    fun `test SourcePriorityDialog save persists modified priorities to QualityDataHelper`() {
        val profileId = 4
        val profile = QualityDataHelper.getProfiles().first { it.id == profileId }

        val links = listOf(LinkSource("AlphaSource"), LinkSource("BetaSource"))
        val callbackInvoked = AtomicBoolean(false)

        val dialog = SourcePriorityDialog(
            ctx = context,
            links = links,
            profile = profile,
            updatedCallback = { callbackInvoked.set(true) }
        )

        val modifiedSources = listOf(
            SourcePriority<Nothing?>(null, "AlphaSource", 12),
            SourcePriority<Nothing?>(null, "BetaSource", 7)
        )

        val modifiedQualities = listOf(
            SourcePriority(Qualities.P1080, "1080p", 25),
            SourcePriority(Qualities.P720, "720p", 18)
        )

        dialog.save(
            savedProfileName = "Custom HD Profile",
            sources = modifiedSources,
            qualities = modifiedQualities
        )

        // Verify callback was fired
        assertTrue(callbackInvoked.get(), "updatedCallback must be invoked on save")

        // Verify priorities persisted to QualityDataHelper
        assertEquals(12, QualityDataHelper.getSourcePriority(profileId, "AlphaSource"))
        assertEquals(7, QualityDataHelper.getSourcePriority(profileId, "BetaSource"))
        assertEquals(25, QualityDataHelper.getQualityPriority(profileId, Qualities.P1080))
        assertEquals(18, QualityDataHelper.getQualityPriority(profileId, Qualities.P720))

        // Verify profile name persisted
        assertEquals("Custom HD Profile", dialog.getProfileName())
    }

    @Test
    fun `test SourcePriorityDialog save handles blank profile name by clearing custom name`() {
        val profileId = 5
        val profile = QualityDataHelper.getProfiles().first { it.id == profileId }

        val dialog = SourcePriorityDialog(
            ctx = context,
            links = emptyList(),
            profile = profile,
            updatedCallback = {}
        )

        // Set initial name
        dialog.save(savedProfileName = "Temporary Name")
        assertEquals("Temporary Name", dialog.getProfileName())

        // Save blank name -> should clear custom name and revert to default
        dialog.save(savedProfileName = "   ")
        val resolvedName = dialog.getProfileName()
        assertTrue(
            resolvedName.contains(profileId.toString()),
            "Clearing name should revert to default Profile $profileId string"
        )
    }

    @Test
    fun `test SourcePriorityDialog openSettings launches SourceProfileSettingsDialog`() {
        val profile = QualityDataHelper.getProfiles().first()
        val dialog = SourcePriorityDialog(
            ctx = context,
            links = emptyList(),
            profile = profile,
            updatedCallback = {}
        )

        val settingsDialog = dialog.openSettings()
        assertNotNull(settingsDialog)
        assertEquals(profile.id, settingsDialog.profile)
        assertTrue(settingsDialog.isShowing())

        settingsDialog.dismiss()
        assertFalse(settingsDialog.isShowing())
    }

    @Test
    fun `test QualityProfileDialog constructor overloads parity`() {
        val links = listOf(LinkSource("TestMirror"))
        val callbackInvoked = AtomicBoolean(false)

        // 1. Overload with selection callback
        val dialogWithSelection = QualityProfileDialog(
            activity = activity,
            themeRes = 0,
            links = links,
            usedProfile = 1,
            profileSelectionCallback = { callbackInvoked.set(true) }
        )
        assertTrue(dialogWithSelection.useProfileSelection)
        assertEquals(1, dialogWithSelection.usedProfile)
        assertEquals(1, dialogWithSelection.getCurrentProfile()?.id)

        // 2. Standalone overload without callback
        val dialogStandalone = QualityProfileDialog(
            activity = activity,
            themeRes = 0,
            links = links
        )
        assertFalse(dialogStandalone.useProfileSelection)
        assertNull(dialogStandalone.usedProfile)
    }

    @Test
    fun `test QualityProfileDialog getAllDefaultSources runs asynchronously via ioWork`() = runBlocking {
        // Set some sources in QualityDataHelper
        QualityDataHelper.setSourcePriority(1, "AsyncSource1", 5)
        QualityDataHelper.setSourcePriority(2, "AsyncSource2", 3)

        val defaultSources = QualityProfileDialog.getAllDefaultSources()
        assertNotNull(defaultSources)
        val sourceNames = defaultSources.map { it.source }
        assertTrue(sourceNames.contains("AsyncSource1"))
        assertTrue(sourceNames.contains("AsyncSource2"))
    }

    @Test
    fun `test QualityProfileDialog profile selection and applySelection execution`() {
        var selectedResult: QualityDataHelper.QualityProfile? = null

        val dialog = QualityProfileDialog(
            activity = activity,
            themeRes = 0,
            links = emptyList(),
            usedProfile = 1,
            profileSelectionCallback = { selectedResult = it }
        )

        val profiles = dialog.getProfilesList()
        val targetProfile = profiles.first { it.id == 2 }

        dialog.selectProfile(targetProfile)
        assertEquals(targetProfile, dialog.getCurrentProfile())

        dialog.show()
        assertTrue(dialog.isShowing())

        dialog.applySelection()
        assertEquals(targetProfile, selectedResult, "Selected profile must be passed to callback")
        assertFalse(dialog.isShowing(), "Dialog must dismiss upon apply")
    }

    @Test
    fun `test QualityProfileDialog setDefaultProfileTypes enforces uniqueness across profiles`() {
        val dialog = QualityProfileDialog(
            activity = activity,
            themeRes = 0,
            links = emptyList()
        )

        // Initially ensure Profile 1 has WiFi and Profile 2 does not
        dialog.setDefaultProfileTypes(1, listOf(QualityProfileType.WiFi))
        val profile1 = QualityDataHelper.getProfiles().first { it.id == 1 }
        assertTrue(profile1.types.contains(QualityProfileType.WiFi))

        // Assign WiFi to Profile 2 -> must be stripped from Profile 1 because WiFi.unique == true
        dialog.setDefaultProfileTypes(2, listOf(QualityProfileType.WiFi, QualityProfileType.Data))

        val updatedProfile1 = QualityDataHelper.getProfiles().first { it.id == 1 }
        val updatedProfile2 = QualityDataHelper.getProfiles().first { it.id == 2 }

        assertFalse(
            updatedProfile1.types.contains(QualityProfileType.WiFi),
            "Unique WiFi type must be removed from previous profile"
        )
        assertTrue(
            updatedProfile2.types.contains(QualityProfileType.WiFi),
            "Profile 2 must now have WiFi"
        )
        assertTrue(
            updatedProfile2.types.contains(QualityProfileType.Data),
            "Profile 2 must have Data"
        )
    }

    @Test
    fun `test SourceProfileSettingsDialog get and set profile settings`() {
        val profileId = 6
        val settingsDialog = SourceProfileSettingsDialog(
            ctx = context,
            themeRes = 0,
            profile = profileId
        )

        // Test default reading
        val initialHideError = settingsDialog.hideErrorSources
        val initialHideNegative = settingsDialog.hideNegativeSources

        // Save modified values
        settingsDialog.save(hideErrorSources = true, hideNegativeSources = true)
        assertTrue(QualityDataHelper.getProfileSetting(profileId, ProfileSettings.HideErrorSources))
        assertTrue(QualityDataHelper.getProfileSetting(profileId, ProfileSettings.HideNegativeSources))

        // Save false values
        settingsDialog.save(hideErrorSources = false, hideNegativeSources = false)
        assertFalse(QualityDataHelper.getProfileSetting(profileId, ProfileSettings.HideErrorSources))
        assertFalse(QualityDataHelper.getProfileSetting(profileId, ProfileSettings.HideNegativeSources))
    }

    @Test
    fun `test Dialog show dismiss and onDismissListener lifecycle contract`() {
        val profile = QualityDataHelper.getProfiles().first()
        val dialog = SourcePriorityDialog(
            ctx = context,
            links = emptyList(),
            profile = profile,
            updatedCallback = {}
        )

        val dismissed = AtomicBoolean(false)
        dialog.setOnDismissListener {
            dismissed.set(true)
        }

        assertFalse(dialog.isShowing())
        dialog.show()
        assertTrue(dialog.isShowing())

        dialog.dismiss()
        assertFalse(dialog.isShowing())
        assertTrue(dismissed.get(), "OnDismissListener must be notified on dismiss")
    }

    @Test
    fun `test headless execution safety with zero UI dependencies in core engine`() {
        // Execute the entire priority calculation and persistence workflow without graphics environment
        val profile = QualityDataHelper.getProfiles().first()
        val links = (1..10).map { LinkSource("Source_$it") }

        val dialog = SourcePriorityDialog(
            ctx = context,
            links = links,
            profile = profile,
            updatedCallback = {}
        )

        dialog.show()
        val sources = dialog.getSortedSources()
        assertEquals(10, sources.size)

        dialog.save(savedProfileName = "Headless Profile")
        assertEquals("Headless Profile", dialog.getProfileName())
        dialog.dismiss()
    }
}
