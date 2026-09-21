package unit

import android.accounts.Account
import android.accounts.AccountManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.res.ColorStateList
import android.os.Bundle
import androidx.preference.PreferenceManager
import com.lagradost.common.platform.PlatformPaths
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * Anti-mock unit tests for Domain 23: Android Framework Shims & Native Platform Bridges.
 *
 * Verifies:
 * 1. ColorStateList: valueOf factory, default color, state set matching, and alpha transformation.
 * 2. AccountManager & Account: context singleton, account creation, password/userdata storage, querying.
 * 3. NotificationManager, NotificationChannel, Notification: channel lifecycle, notification building, dispatch, cancel.
 * 4. PreferenceManager: default SharedPreferences routing and atomic disk persistence.
 * 5. Context.getSystemService: resolution of NOTIFICATION_SERVICE and ACCOUNT_SERVICE.
 */
class AndroidFrameworkStubsTest {

    private lateinit var testTempDir: Path

    @BeforeEach
    fun setUp() {
        PlatformPaths.init()
        testTempDir = Files.createTempDirectory("cs_test_framework_stubs")
    }

    @AfterEach
    fun tearDown() {
        testTempDir.toFile().deleteRecursively()
    }

    // =========================================================================
    // 1. ColorStateList Tests
    // =========================================================================

    @Test
    fun testColorStateListValueOfAndDefaultColor() {
        val color = 0xFF336699.toInt()
        val csl = ColorStateList.valueOf(color)

        assertNotNull(csl)
        assertEquals(color, csl.defaultColor)
        assertEquals(color, csl.getDefaultColor())
        assertFalse(csl.isStateful)
        assertFalse(csl.isStateful())
        assertTrue(csl.toString().contains("ColorStateList"))
    }

    @Test
    fun testColorStateListGetColorForState() {
        val defaultColor = 0xFF112233.toInt()
        val csl = ColorStateList.valueOf(defaultColor)

        // Matching with empty or arbitrary state set on a single-color list returns defaultColor
        assertEquals(defaultColor, csl.getColorForState(intArrayOf(16842910), 0))
        assertEquals(defaultColor, csl.getColorForState(null, 0))

        // Custom multi-state ColorStateList
        val stateEnabled = 16842910
        val statePressed = 16842919
        val enabledColor = 0xFF00FF00.toInt()
        val pressedColor = 0xFFFF0000.toInt()
        val fallbackColor = 0xFF888888.toInt()

        val multiCsl = ColorStateList(
            arrayOf(
                intArrayOf(statePressed),
                intArrayOf(stateEnabled),
                intArrayOf()
            ),
            intArrayOf(pressedColor, enabledColor, fallbackColor)
        )

        assertTrue(multiCsl.isStateful)
        assertTrue(multiCsl.isStateful())
        assertEquals(pressedColor, multiCsl.defaultColor)

        // State matching verification
        assertEquals(pressedColor, multiCsl.getColorForState(intArrayOf(statePressed, stateEnabled), 0))
        assertEquals(enabledColor, multiCsl.getColorForState(intArrayOf(stateEnabled), 0))
        assertEquals(fallbackColor, multiCsl.getColorForState(intArrayOf(12345), 0))
        assertEquals(fallbackColor, multiCsl.getColorForState(intArrayOf(), 0))
        assertEquals(pressedColor, multiCsl.getColorForState(null, 0))
    }

    @Test
    fun testColorStateListWithAlpha() {
        val originalColor = 0xFF2196F3.toInt() // ARGB
        val csl = ColorStateList.valueOf(originalColor)

        val transparentCsl = csl.withAlpha(0)
        assertEquals(0x002196F3, transparentCsl.defaultColor)
        assertEquals(0x002196F3, transparentCsl.getDefaultColor())

        val semiTransparentCsl = csl.withAlpha(128)
        val expectedSemi = (originalColor and 0x00FFFFFF) or (128 shl 24)
        assertEquals(expectedSemi, semiTransparentCsl.defaultColor)

        val fullyOpaqueCsl = transparentCsl.withAlpha(255)
        assertEquals(originalColor, fullyOpaqueCsl.defaultColor)

        // Coerce out-of-bounds alpha
        val clampedHigh = csl.withAlpha(500)
        assertEquals(originalColor, clampedHigh.defaultColor)
    }

    @Test
    fun testColorStateListEqualityAndHashCode() {
        val csl1 = ColorStateList.valueOf(0xFFAABBCC.toInt())
        val csl2 = ColorStateList.valueOf(0xFFAABBCC.toInt())
        val csl3 = ColorStateList.valueOf(0xFF112233.toInt())

        assertEquals(csl1, csl2)
        assertEquals(csl1.hashCode(), csl2.hashCode())
        assertNotEquals(csl1, csl3)
    }

    // =========================================================================
    // 2. AccountManager & Account Tests
    // =========================================================================

    @Test
    fun testAccountDataClassBehavior() {
        val account1 = Account("user@example.com", "com.lagradost.cloudstream3")
        val account2 = Account("user@example.com", "com.lagradost.cloudstream3")
        val account3 = Account("other@example.com", "com.lagradost.cloudstream3")

        assertEquals("user@example.com", account1.name)
        assertEquals("com.lagradost.cloudstream3", account1.type)
        assertEquals(account1, account2)
        assertEquals(account1.hashCode(), account2.hashCode())
        assertNotEquals(account1, account3)
        assertTrue(account1.toString().contains("user@example.com"))
    }

    @Test
    fun testAccountManagerLifecycleAndStorage() {
        val context = Context()
        val am = AccountManager.get(context)
        assertNotNull(am)
        assertSame(am, AccountManager.get(context), "AccountManager.get(context) must return singleton per context")

        val account = Account("sync_user", "com.lagradost.cloudstream3.sync")
        val bundle = Bundle().apply {
            putString("api_key", "secret_key_12345")
            putString("server_url", "https://api.example.com")
        }

        // Explicit account addition
        assertTrue(am.addAccountExplicitly(account, "initial_password", bundle))
        // Duplicate account must return false
        assertFalse(am.addAccountExplicitly(account, "initial_password", null))

        // Password retrieval and update
        assertEquals("initial_password", am.getPassword(account))
        am.setPassword(account, "updated_password")
        assertEquals("updated_password", am.getPassword(account))
        am.setPassword(account, null)
        assertNull(am.getPassword(account))

        // UserData retrieval and update
        assertEquals("secret_key_12345", am.getUserData(account, "api_key"))
        assertEquals("https://api.example.com", am.getUserData(account, "server_url"))
        assertNull(am.getUserData(account, "non_existent"))

        am.setUserData(account, "custom_field", "custom_val")
        assertEquals("custom_val", am.getUserData(account, "custom_field"))
        am.setUserData(account, "custom_field", null)
        assertNull(am.getUserData(account, "custom_field"))

        // Query accounts
        val allAccounts = am.getAccounts()
        assertTrue(allAccounts.contains(account))

        val matchingAccounts = am.getAccountsByType("com.lagradost.cloudstream3.sync")
        assertEquals(1, matchingAccounts.size)
        assertEquals(account, matchingAccounts[0])

        val nonMatchingAccounts = am.getAccountsByType("com.other.type")
        assertEquals(0, nonMatchingAccounts.size)

        val nullTypeAccounts = am.getAccountsByType(null)
        assertEquals(allAccounts.size, nullTypeAccounts.size)

        // InvalidateAuthToken does not throw
        assertDoesNotThrow { am.invalidateAuthToken("com.lagradost.cloudstream3.sync", "token123") }
    }

    // =========================================================================
    // 3. NotificationManager, NotificationChannel & Notification Tests
    // =========================================================================

    @Test
    fun testNotificationChannelPropertiesAndConstants() {
        assertEquals(0, NotificationManager.IMPORTANCE_NONE)
        assertEquals(2, NotificationManager.IMPORTANCE_LOW)
        assertEquals(3, NotificationManager.IMPORTANCE_DEFAULT)
        assertEquals(4, NotificationManager.IMPORTANCE_HIGH)

        val channel = NotificationChannel("downloads", "Download Notifications", NotificationManager.IMPORTANCE_LOW)
        channel.description = "Shows progress for ongoing media downloads"
        channel.enableVibration = true
        channel.enableLights = true

        assertEquals("downloads", channel.id)
        assertEquals("Download Notifications", channel.name)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
        assertEquals("Shows progress for ongoing media downloads", channel.description)
        assertTrue(channel.enableVibration)
        assertTrue(channel.enableLights)

        val sameChannel = NotificationChannel("downloads", "Other Name", NotificationManager.IMPORTANCE_HIGH)
        assertEquals(channel, sameChannel)
        assertEquals(channel.hashCode(), sameChannel.hashCode())
    }

    @Test
    fun testNotificationBuilderAndManagerDispatch() {
        val context = Context()
        val nm = NotificationManager()

        // Channel creation and management
        val channel = NotificationChannel("updates", "App Updates", NotificationManager.IMPORTANCE_DEFAULT)
        nm.createNotificationChannel(channel)
        assertEquals(channel, nm.getNotificationChannel("updates"))
        assertEquals(1, nm.getNotificationChannels().size)

        // Notification building
        val notification = Notification.Builder(context, "updates")
            .setSmallIcon(1234)
            .setContentTitle("New Version Available")
            .setContentText("CloudStream Desktop v1.2 is ready to install")
            .setPriority(Notification.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setOngoing(false)
            .setProgress(100, 45, false)
            .setSubText("Update service")
            .setContentInfo("Info")
            .build()

        assertEquals(1234, notification.icon)
        assertEquals("New Version Available", notification.title)
        assertEquals("CloudStream Desktop v1.2 is ready to install", notification.text)
        assertEquals(Notification.PRIORITY_HIGH, notification.priority)
        assertEquals("updates", notification.channelId)

        // Dispatch notification
        nm.notify(1001, notification)
        assertEquals(notification, nm.getActiveNotification(1001))
        assertEquals(1, nm.getActiveNotifications().size)

        // Dispatch tagged notification
        val taggedNotification = Notification.Builder(context, "updates")
            .setContentTitle("Episode 2 downloaded")
            .build()
        nm.notify("episode_tag", 2002, taggedNotification)
        assertEquals(taggedNotification, nm.getActiveNotification("episode_tag", 2002))
        assertEquals(2, nm.getActiveNotifications().size)

        // Cancel specific notification
        nm.cancel(1001)
        assertNull(nm.getActiveNotification(1001))
        assertNotNull(nm.getActiveNotification("episode_tag", 2002))

        // Cancel tagged notification
        nm.cancel("episode_tag", 2002)
        assertNull(nm.getActiveNotification("episode_tag", 2002))
        assertEquals(0, nm.getActiveNotifications().size)

        // CancelAll
        nm.notify(3001, notification)
        nm.notify("tag", 3002, taggedNotification)
        assertEquals(2, nm.getActiveNotifications().size)
        nm.cancelAll()
        assertEquals(0, nm.getActiveNotifications().size)

        // Channel deletion
        nm.deleteNotificationChannel("updates")
        assertNull(nm.getNotificationChannel("updates"))
        assertTrue(nm.getNotificationChannels().isEmpty())

        assertTrue(nm.areNotificationsEnabled())
    }

    // =========================================================================
    // 4. PreferenceManager Tests
    // =========================================================================

    @Test
    fun testPreferenceManagerGetDefaultSharedPreferencesAndPersistence() {
        val context = Context()
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        assertNotNull(prefs)

        val uniqueKey = "pref_test_theme_${System.currentTimeMillis()}"
        val uniqueVal = "AmoledDark"

        assertTrue(
            prefs.edit()
                .putString(uniqueKey, uniqueVal)
                .putInt("test_volume", 85)
                .putBoolean("test_auto_play", true)
                .commit()
        )

        // Verify values from in-memory cache
        assertEquals(uniqueVal, prefs.getString(uniqueKey, null))
        assertEquals(85, prefs.getInt("test_volume", 0))
        assertTrue(prefs.getBoolean("test_auto_play", false))

        // Verify that PreferenceManager returns the same SharedPreferences instance on the same context
        val sameContextPrefs = PreferenceManager.getDefaultSharedPreferences(context)
        assertEquals(uniqueVal, sameContextPrefs.getString(uniqueKey, null))

        // Verify underlying file persistence in pluginPrefsDir
        val prefsFile = PlatformPaths.pluginPrefsDir.resolve("default_preferences.json").toFile()
        assertTrue(prefsFile.exists(), "default_preferences.json must exist on disk")
        assertTrue(prefsFile.length() > 0, "default_preferences.json must not be empty")

        // No-op setDefaultValues must execute without throwing
        assertDoesNotThrow {
            PreferenceManager.setDefaultValues(context, 101, false)
            PreferenceManager.setDefaultValues(context, "custom_prefs", Context.MODE_PRIVATE, 102, false)
        }
    }

    // =========================================================================
    // 5. Context.getSystemService Resolution Tests
    // =========================================================================

    @Test
    fun testContextGetSystemServiceResolution() {
        val context = Context()

        // NotificationManager resolution
        val notificationService = context.getSystemService(Context.NOTIFICATION_SERVICE)
        assertNotNull(notificationService)
        assertTrue(notificationService is NotificationManager)

        // Repeated calls return the same singleton instance
        val notificationServiceSecond = context.getSystemService(Context.NOTIFICATION_SERVICE)
        assertSame(notificationService, notificationServiceSecond)

        // AccountManager resolution
        val accountService = context.getSystemService(Context.ACCOUNT_SERVICE)
        assertNotNull(accountService)
        assertTrue(accountService is AccountManager)

        // Repeated calls return the same singleton instance
        val accountServiceSecond = context.getSystemService(Context.ACCOUNT_SERVICE)
        assertSame(accountService, accountServiceSecond)

        // AccountManager.get(context) must return the identical instance as context.getSystemService(ACCOUNT_SERVICE)
        assertSame(accountService, AccountManager.get(context))

        // Unknown service returns null
        assertNull(context.getSystemService("non_existent_service_name"))
        assertNull(context.getSystemService(""))
    }
}
