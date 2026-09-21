package integration

import com.lagradost.common.account.AccountManagerDesktop
import com.lagradost.common.account.AvatarPresets
import com.lagradost.common.account.PinSecurity
import com.lagradost.common.account.currentAccount
import com.lagradost.common.platform.PlatformPaths
import com.lagradost.common.storage.DesktopDataStore
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * Anti-mock integration test suite for Domain 11: Multi-Profile & Account Manager with PIN Security.
 * Executes against real physical filesystem I/O, real JSON serialization, and real PBKDF2 cryptography.
 */
class AccountPersistenceRealIoTest {

    private lateinit var tempDir: Path
    private lateinit var testDataFile: File

    @BeforeEach
    fun setUp() {
        PlatformPaths.init()
        tempDir = Files.createTempDirectory("cs_account_test_${UUID.randomUUID()}")
        testDataFile = tempDir.resolve("datastore.json").toFile()
        DesktopDataStore.customDataFile = testDataFile
        DesktopDataStore.reload()
        AccountManagerDesktop.reset()
    }

    @AfterEach
    fun tearDown() {
        DesktopDataStore.customDataFile = null
        DesktopDataStore.reload()
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun testDefaultAccountInitialization() {
        // Invariant 1: Exactly 1 default account exists on startup
        val accounts = AccountManagerDesktop.getAccounts()
        assertEquals(1, accounts.size, "Must have exactly 1 default account on clean init")

        val defaultAccount = accounts.first()
        assertEquals(0, defaultAccount.keyIndex, "Default account keyIndex must be 0")
        assertEquals("Default Account", defaultAccount.name)
        assertEquals(0, defaultAccount.imageIndex)
        assertNull(defaultAccount.pinHash, "Default account must not have a PIN hash")
        assertNull(defaultAccount.pinSalt, "Default account must not have a PIN salt")
        assertFalse(defaultAccount.isLocked, "Default account cannot be locked")
        assertTrue(defaultAccount.isDefaultAccount)

        // Invariant 2: Active account is bound to keyIndex 0
        assertEquals(defaultAccount, AccountManagerDesktop.getCurrentAccount())
        assertEquals("0", AccountManagerDesktop.currentAccount)
        assertEquals("0", DesktopDataStore.currentAccount)

        // Invariant 3: Setting PIN on default account (keyIndex 0) is forbidden
        assertThrows<IllegalArgumentException> {
            AccountManagerDesktop.setPin(0, "1234")
        }

        // Invariant 4: Physical disk assertion
        assertTrue(testDataFile.exists(), "datastore.json must be physically created on disk")
        val diskContent = testDataFile.readText()
        assertTrue(diskContent.contains("Default Account"), "Default account must be written to disk")
        assertTrue(diskContent.contains("data_store_helper/account"), "Account directory key must exist on disk")
    }

    @Test
    fun testSecondaryAccountCreationAndSequentialMonotonicity() {
        // Create secondary account
        val account1 = AccountManagerDesktop.createAccount("Living Room", 2)
        assertEquals(1, account1.keyIndex, "First secondary account must receive keyIndex 1")
        assertEquals("Living Room", account1.name)
        assertEquals(2, account1.imageIndex)
        assertNull(account1.pinHash)
        assertFalse(account1.isLocked)
        assertFalse(account1.isDefaultAccount)

        // Create third account
        val account2 = AccountManagerDesktop.createAccount("Kids Room", 5)
        assertEquals(2, account2.keyIndex, "Next account must receive sequentially monotonically increasing keyIndex 2")
        assertEquals("Kids Room", account2.name)
        assertEquals(5, account2.imageIndex)

        // Check listing
        val allAccounts = AccountManagerDesktop.getAccounts()
        assertEquals(3, allAccounts.size)
        assertEquals(listOf(0, 1, 2), allAccounts.map { it.keyIndex })

        // Physical disk verification
        val diskContent = testDataFile.readText()
        assertTrue(diskContent.contains("Living Room"), "Living Room profile must be on disk")
        assertTrue(diskContent.contains("Kids Room"), "Kids Room profile must be on disk")
    }

    @Test
    fun testProfileSwitchingAndHierarchicalBinding() {
        val account1 = AccountManagerDesktop.createAccount("Guest User", 3)
        assertEquals(1, account1.keyIndex)

        // Switch to Account 1
        AccountManagerDesktop.switchAccount(1)
        assertEquals(1, AccountManagerDesktop.getCurrentAccount().keyIndex)
        assertEquals("Guest User", AccountManagerDesktop.getCurrentAccount().name)
        assertEquals("1", AccountManagerDesktop.currentAccount)
        assertEquals("1", DesktopDataStore.currentAccount)

        // Store data scoped under active account
        DesktopDataStore.setKey("${DesktopDataStore.currentAccount}/resume_watching", mapOf("ep_101" to 42000L))
        DesktopDataStore.setKey("${DesktopDataStore.currentAccount}/bookmarks", listOf("show_xyz"))

        // Store data for account 0 directly
        DesktopDataStore.setKey("0/resume_watching", mapOf("ep_999" to 10000L))

        // Assert isolation in memory
        val acc1Resume = DesktopDataStore.getKey<Map<String, Long>>("1/resume_watching")
        assertNotNull(acc1Resume)
        assertEquals(42000L, acc1Resume?.get("ep_101"))

        val acc0Resume = DesktopDataStore.getKey<Map<String, Long>>("0/resume_watching")
        assertNotNull(acc0Resume)
        assertEquals(10000L, acc0Resume?.get("ep_999"))

        // Switch back to Account 0
        AccountManagerDesktop.switchAccount(0)
        assertEquals("0", AccountManagerDesktop.currentAccount)
        assertEquals("0", DesktopDataStore.currentAccount)
        assertEquals(0, AccountManagerDesktop.getCurrentAccount().keyIndex)

        // Physical disk assertion
        val diskContent = testDataFile.readText()
        assertTrue(diskContent.contains("1/resume_watching"), "Account 1 resume data must be in datastore file")
        assertTrue(diskContent.contains("0/resume_watching"), "Account 0 resume data must be in datastore file")
    }

    @Test
    fun testPinSecurityPBKDF2HashingAndVerification() {
        val account = AccountManagerDesktop.createAccount("Vault Profile", 1)
        val keyIndex = account.keyIndex

        // Initially unlocked
        assertFalse(AccountManagerDesktop.getCurrentAccount().isPinLocked)
        assertTrue(AccountManagerDesktop.verifyPin(keyIndex, "1234"), "Unlocked account returns true on verify")

        // Set PBKDF2 PIN (10,000 iterations, 16-byte random salt)
        val pin = "7890"
        assertTrue(AccountManagerDesktop.setPin(keyIndex, pin))

        val updatedAccount = AccountManagerDesktop.getAccounts().first { it.keyIndex == keyIndex }
        assertTrue(updatedAccount.isLocked)
        assertTrue(updatedAccount.isPinLocked)

        // Validate cryptographic parameters
        val pinHash = updatedAccount.pinHash
        val pinSalt = updatedAccount.pinSalt
        assertNotNull(pinHash, "pinHash must not be null")
        assertNotNull(pinSalt, "pinSalt must not be null")

        val saltBytes = PinSecurity.decodeBytes(pinSalt!!)
        assertEquals(PinSecurity.SALT_LENGTH_BYTES, saltBytes.size, "Salt must be exactly 16 bytes")
        assertEquals(32, pinSalt.length, "Hex-encoded 16-byte salt must be 32 characters")

        val hashBytes = PinSecurity.decodeBytes(pinHash!!)
        assertEquals(32, hashBytes.size, "Hash must be 256 bits (32 bytes)")
        assertEquals(64, pinHash.length, "Hex-encoded 32-byte hash must be 64 characters")

        // Verification checks
        assertTrue(AccountManagerDesktop.verifyPin(keyIndex, "7890"), "Correct PIN must verify successfully")
        assertFalse(AccountManagerDesktop.verifyPin(keyIndex, "0000"), "Incorrect PIN must fail verification")
        assertFalse(AccountManagerDesktop.verifyPin(keyIndex, "789"), "Truncated PIN must fail verification")
        assertFalse(AccountManagerDesktop.verifyPin(keyIndex, "78901"), "Extended PIN must fail verification")
        assertFalse(AccountManagerDesktop.verifyPin(keyIndex, ""), "Empty PIN must fail verification")

        // Remove PIN with incorrect challenge
        assertFalse(AccountManagerDesktop.removePin(keyIndex, "9999"), "Removing with wrong PIN must fail")
        val stillLocked = AccountManagerDesktop.getAccounts().first { it.keyIndex == keyIndex }
        assertTrue(stillLocked.isLocked)

        // Remove PIN with correct challenge
        assertTrue(AccountManagerDesktop.removePin(keyIndex, "7890"), "Removing with correct PIN must succeed")
        val unlockedAccount = AccountManagerDesktop.getAccounts().first { it.keyIndex == keyIndex }
        assertFalse(unlockedAccount.isLocked)
        assertNull(unlockedAccount.pinHash)
        assertNull(unlockedAccount.pinSalt)

        // Clean removal without challenge overload
        AccountManagerDesktop.setPin(keyIndex, "1111")
        assertTrue(AccountManagerDesktop.getAccounts().first { it.keyIndex == keyIndex }.isLocked)
        assertTrue(AccountManagerDesktop.removePin(keyIndex))
        assertFalse(AccountManagerDesktop.getAccounts().first { it.keyIndex == keyIndex }.isLocked)
    }

    @Test
    fun testAccountDeletionAndRecursiveDataPurgingWithPhysicalDiskAssertion() {
        // Setup: Create account 1 and account 2
        val acc1 = AccountManagerDesktop.createAccount("To Be Deleted", 4)
        val acc2 = AccountManagerDesktop.createAccount("Survivor Account", 6)
        assertEquals(1, acc1.keyIndex)
        assertEquals(2, acc2.keyIndex)

        // Switch to account 1 and populate multiple namespaced keys
        AccountManagerDesktop.switchAccount(acc1.keyIndex)
        assertEquals("1", AccountManagerDesktop.currentAccount)

        DesktopDataStore.setKey("1/resume_watching", mapOf("show_alpha" to 5000L))
        DesktopDataStore.setKey("1/watch_state_data", mapOf("show_alpha" to "Watching"))
        DesktopDataStore.setKey("1/video_pos_dur", mapOf("vid_alpha" to 250000L))
        DesktopDataStore.setKey("1/sub/deep/record", "nested_val_1")

        // Populate survivor and default account keys
        DesktopDataStore.setKey("0/resume_watching", mapOf("show_root" to 1000L))
        DesktopDataStore.setKey("2/resume_watching", mapOf("show_beta" to 9000L))

        // Verify keys exist in memory
        assertEquals(4, DesktopDataStore.getKeys("1").size)
        assertTrue(DesktopDataStore.containsKey("1/resume_watching"))
        assertTrue(DesktopDataStore.containsKey("0/resume_watching"))
        assertTrue(DesktopDataStore.containsKey("2/resume_watching"))

        // Physical disk assertion before deletion
        val preDeleteDisk = testDataFile.readText()
        assertTrue(preDeleteDisk.contains("1/resume_watching"), "Datastore on disk must have Account 1 data")
        assertTrue(preDeleteDisk.contains("To Be Deleted"), "Datastore on disk must have Account 1 profile")
        assertTrue(preDeleteDisk.contains("0/resume_watching"), "Datastore on disk must have Account 0 data")
        assertTrue(preDeleteDisk.contains("2/resume_watching"), "Datastore on disk must have Account 2 data")

        // Action: Delete account 1
        val deleteResult = AccountManagerDesktop.deleteAccount(acc1.keyIndex)
        assertTrue(deleteResult, "deleteAccount must return true")

        // Invariant 1: Account 1 is removed from account directory
        val currentAccounts = AccountManagerDesktop.getAccounts()
        assertEquals(2, currentAccounts.size)
        assertFalse(currentAccounts.any { it.keyIndex == 1 })
        assertTrue(currentAccounts.any { it.keyIndex == 0 })
        assertTrue(currentAccounts.any { it.keyIndex == 2 })

        // Invariant 2: Active account was Account 1, must failsafe-switch to Account 0
        assertEquals("0", AccountManagerDesktop.currentAccount)
        assertEquals("0", DesktopDataStore.currentAccount)
        assertEquals(0, AccountManagerDesktop.getCurrentAccount().keyIndex)

        // Invariant 3: All Account 1 keys are eradicated from memory
        assertEquals(0, DesktopDataStore.getKeys("1").size, "DesktopDataStore.getKeys('1') must be empty")
        assertNull(DesktopDataStore.getKey<Map<String, Long>>("1/resume_watching"))
        assertNull(DesktopDataStore.getKey<Map<String, String>>("1/watch_state_data"))
        assertNull(DesktopDataStore.getKey<Map<String, Long>>("1/video_pos_dur"))
        assertNull(DesktopDataStore.getKey<String>("1/sub/deep/record"))

        // Invariant 4: Account 0 and Account 2 data are strictly preserved
        assertNotNull(DesktopDataStore.getKey<Map<String, Long>>("0/resume_watching"))
        assertNotNull(DesktopDataStore.getKey<Map<String, Long>>("2/resume_watching"))

        // Invariant 5: Physical disk verification after deletion
        val postDeleteDisk = testDataFile.readText()
        assertFalse(postDeleteDisk.contains("1/resume_watching"), "Physical disk datastore MUST NOT contain 1/resume_watching")
        assertFalse(postDeleteDisk.contains("1/watch_state_data"), "Physical disk datastore MUST NOT contain 1/watch_state_data")
        assertFalse(postDeleteDisk.contains("1/video_pos_dur"), "Physical disk datastore MUST NOT contain 1/video_pos_dur")
        assertFalse(postDeleteDisk.contains("1/sub/deep/record"), "Physical disk datastore MUST NOT contain 1/sub/deep/record")
        assertFalse(postDeleteDisk.contains("To Be Deleted"), "Physical disk datastore MUST NOT contain deleted profile name")
        assertTrue(postDeleteDisk.contains("0/resume_watching"), "Physical disk datastore MUST contain Account 0 data")
        assertTrue(postDeleteDisk.contains("2/resume_watching"), "Physical disk datastore MUST contain Account 2 data")

        // Invariant 6: Clean reload from disk proves persistent state
        DesktopDataStore.reload()
        AccountManagerDesktop.init()

        assertEquals(2, AccountManagerDesktop.getAccounts().size)
        assertEquals(0, DesktopDataStore.getKeys("1").size)
        assertNotNull(DesktopDataStore.getKey<Map<String, Long>>("0/resume_watching"))
        assertNotNull(DesktopDataStore.getKey<Map<String, Long>>("2/resume_watching"))
    }

    @Test
    fun testDefaultAccountCannotBeDeleted() {
        assertThrows<IllegalArgumentException> {
            AccountManagerDesktop.deleteAccount(0)
        }
    }

    @Test
    fun testAvatarPresetsContract() {
        assertEquals(10, AvatarPresets.COUNT)
        assertEquals(10, AvatarPresets.PRESETS.size)
        assertEquals("profile_bg_1", AvatarPresets.getPreset(0))
        assertEquals("profile_bg_2", AvatarPresets.getPreset(1))
        assertEquals("profile_bg_10", AvatarPresets.getPreset(9))

        // Out of bounds fallback
        assertEquals("profile_bg_1", AvatarPresets.getPreset(10))
        assertEquals("profile_bg_1", AvatarPresets.getPreset(-1))
    }
}
