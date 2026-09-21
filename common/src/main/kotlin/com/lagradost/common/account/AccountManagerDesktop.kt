package com.lagradost.common.account

import com.fasterxml.jackson.core.type.TypeReference
import com.lagradost.common.logging.AppLogger
import com.lagradost.common.storage.DesktopDataStore
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.reflect.KProperty

@Volatile
private var _desktopDataStoreCurrentAccount: String = "0"

/**
 * Hierarchical account binding extension property on DesktopDataStore.
 * Namespaces storage keys under "$currentAccount/$folder/$path".
 */
var DesktopDataStore.currentAccount: String
    get() = _desktopDataStoreCurrentAccount
    set(value) {
        _desktopDataStoreCurrentAccount = value
    }

/**
 * Central coordinator for multi-profile management, active account lifecycle,
 * PBKDF2 PIN security, and reactive cache invalidation cascades.
 */
object AccountManagerDesktop {
    const val TAG = "data_store_helper"
    const val ACCOUNTS_KEY = "$TAG/account"
    const val SELECTED_KEY_INDEX_KEY = "$TAG/account_key_index"
    const val USER_HOMEPAGE_KEY = "home_api_used"

    private val lock = Any()

    // --- Reactive State Flows ---
    private val _accountsFlow = MutableStateFlow<List<Account>>(emptyList())
    val accountsFlow: StateFlow<List<Account>> = _accountsFlow.asStateFlow()

    private val _activeAccountFlow = MutableStateFlow<Account>(createDefaultAccount())
    val activeAccountFlow: StateFlow<Account> = _activeAccountFlow.asStateFlow()

    private val _selectedAccountNumberFlow = MutableStateFlow(0)
    val selectedAccountNumberFlow: StateFlow<Int> = _selectedAccountNumberFlow.asStateFlow()

    // --- Invalidation Event Streams ---
    private val _reloadHomeFlow = MutableSharedFlow<Boolean>(extraBufferCapacity = 1)
    val reloadHomeFlow: SharedFlow<Boolean> = _reloadHomeFlow.asSharedFlow()

    private val _reloadLibraryFlow = MutableSharedFlow<Boolean>(extraBufferCapacity = 1)
    val reloadLibraryFlow: SharedFlow<Boolean> = _reloadLibraryFlow.asSharedFlow()

    private val _reloadWatchHistoryFlow = MutableSharedFlow<Boolean>(extraBufferCapacity = 1)
    val reloadWatchHistoryFlow: SharedFlow<Boolean> = _reloadWatchHistoryFlow.asSharedFlow()

    val currentAccount: String
        get() = DesktopDataStore.currentAccount

    var currentHomePage: String?
        get() = DesktopDataStore.getKey<String>("$currentAccount/$USER_HOMEPAGE_KEY")
        set(value) {
            val key = "$currentAccount/$USER_HOMEPAGE_KEY"
            if (value == null) {
                DesktopDataStore.removeKey(key)
            } else {
                DesktopDataStore.setKey(key, value)
            }
        }

    init {
        loadAccountsFromStorage()
    }

    /**
     * Initializes or reloads accounts from DesktopDataStore.
     */
    fun init() {
        synchronized(lock) {
            loadAccountsFromStorage()
            AppLogger.i(
                "AccountManagerDesktop",
                "Initialized multi-profile account manager. Active profile: ${_activeAccountFlow.value.name} (Key: $currentAccount)"
            )
        }
    }

    /**
     * Resets in-memory state and reloads from DesktopDataStore.
     */
    fun reset() {
        synchronized(lock) {
            _accountsFlow.value = emptyList()
            _activeAccountFlow.value = createDefaultAccount()
            DesktopDataStore.currentAccount = "0"
            _selectedAccountNumberFlow.value = 0
            loadAccountsFromStorage()
        }
    }

    fun createDefaultAccount(): Account {
        return Account(
            keyIndex = 0,
            name = "Default Account",
            imageIndex = 0,
            pinHash = null,
            pinSalt = null,
            isLocked = false
        )
    }

    private fun loadAccountsFromStorage() {
        synchronized(lock) {
            val json = DesktopDataStore.cache[ACCOUNTS_KEY]
            var list: MutableList<Account> = if (!json.isNullOrBlank()) {
                try {
                    DesktopDataStore.mapper.readValue(json, object : TypeReference<List<Account>>() {}).toMutableList()
                } catch (e: Exception) {
                    AppLogger.e("AccountManagerDesktop", "Failed to deserialize accounts list", e)
                    mutableListOf()
                }
            } else {
                mutableListOf()
            }

            // Ensure keyIndex 0 (Default Account) is guaranteed to exist
            var defaultAcc = list.firstOrNull { it.keyIndex == 0 }
            if (defaultAcc == null) {
                defaultAcc = createDefaultAccount()
                list.add(0, defaultAcc)
                persistAccounts(list)
            } else if (defaultAcc.pinHash != null || defaultAcc.isLocked) {
                // Invariant: Default Account cannot have a lock PIN
                defaultAcc = defaultAcc.copy(pinHash = null, pinSalt = null, isLocked = false)
                val idx = list.indexOfFirst { it.keyIndex == 0 }
                list[idx] = defaultAcc
                persistAccounts(list)
            }

            _accountsFlow.value = list

            val savedIndex = DesktopDataStore.getKey<Int>(SELECTED_KEY_INDEX_KEY) ?: 0
            val active = list.firstOrNull { it.keyIndex == savedIndex } ?: defaultAcc
            _activeAccountFlow.value = active
            DesktopDataStore.currentAccount = active.keyIndex.toString()
        }
    }

    private fun persistAccounts(accounts: List<Account>) {
        DesktopDataStore.setKey(ACCOUNTS_KEY, accounts)
    }

    // =========================================================================
    // 1. Account Listing & Retrieval
    // =========================================================================

    /**
     * Returns the list of all configured user accounts.
     */
    fun getAccounts(): List<Account> {
        synchronized(lock) {
            if (_accountsFlow.value.isEmpty()) {
                loadAccountsFromStorage()
            }
            return _accountsFlow.value
        }
    }

    /**
     * Returns the currently active account.
     */
    fun getCurrentAccount(): Account {
        synchronized(lock) {
            if (_accountsFlow.value.isEmpty()) {
                loadAccountsFromStorage()
            }
            return _activeAccountFlow.value
        }
    }

    // =========================================================================
    // 2. Profile Switching & Hierarchical Binding
    // =========================================================================

    /**
     * Switches the active profile and updates DesktopDataStore.currentAccount hierarchical binding.
     */
    fun switchAccount(keyIndex: Int): Account {
        synchronized(lock) {
            val accounts = getAccounts()
            val target = accounts.firstOrNull { it.keyIndex == keyIndex }
                ?: throw IllegalArgumentException("Account with keyIndex $keyIndex not found")

            val oldHomePage = currentHomePage
            val previous = _activeAccountFlow.value

            DesktopDataStore.currentAccount = target.keyIndex.toString()
            _activeAccountFlow.value = target
            DesktopDataStore.setKey(SELECTED_KEY_INDEX_KEY, target.keyIndex)

            AppLogger.i(
                "AccountManagerDesktop",
                "Switched profile: '${previous.name}' -> '${target.name}' (Key: ${target.keyIndex})"
            )

            // Trigger invalidation flows
            _reloadWatchHistoryFlow.tryEmit(true)
            _reloadLibraryFlow.tryEmit(true)
            if (currentHomePage != oldHomePage) {
                _reloadHomeFlow.tryEmit(true)
            }

            _selectedAccountNumberFlow.value += 1
            return target
        }
    }

    fun setAccount(account: Account): Account = switchAccount(account.keyIndex)

    fun switchAccount(account: Account): Account = switchAccount(account.keyIndex)

    // =========================================================================
    // 3. Account Creation & Deletion
    // =========================================================================

    /**
     * Creates a new profile with sequential keyIndex allocation.
     */
    fun createAccount(name: String, imageIndex: Int): Account {
        return createAccount(name, imageIndex, null)
    }

    fun createAccount(name: String): Account {
        return createAccount(name, 0, null)
    }

    fun createAccount(name: String, pin: String?): Account {
        val accounts = getAccounts()
        val usedIndices = accounts.map { it.imageIndex }.toSet()
        val available = (0 until AvatarPresets.COUNT).filter { it !in usedIndices }
        val chosen = available.firstOrNull() ?: 0
        return createAccount(name, chosen, pin)
    }

    fun createAccount(name: String, imageIndex: Int, pin: String?): Account {
        synchronized(lock) {
            val accounts = getAccounts().toMutableList()
            val nextKeyIndex = (accounts.maxOfOrNull { it.keyIndex } ?: 0) + 1

            val safeImageIndex = if (imageIndex in 0 until AvatarPresets.COUNT) {
                imageIndex
            } else {
                imageIndex % AvatarPresets.COUNT
            }

            val (pinHash, pinSalt, isLocked) = if (!pin.isNullOrBlank()) {
                val saltBytes = PinSecurity.generateSalt()
                val hashBytes = PinSecurity.hashPin(pin, saltBytes, PinSecurity.ITERATIONS)
                Triple(
                    PinSecurity.bytesToHex(hashBytes),
                    PinSecurity.bytesToHex(saltBytes),
                    true
                )
            } else {
                Triple(null, null, false)
            }

            val newAccount = Account(
                keyIndex = nextKeyIndex,
                name = name.ifBlank { "Account $nextKeyIndex" },
                imageIndex = safeImageIndex,
                pinHash = pinHash,
                pinSalt = pinSalt,
                isLocked = isLocked
            )

            accounts.add(newAccount)
            persistAccounts(accounts)
            _accountsFlow.value = accounts

            AppLogger.i("AccountManagerDesktop", "Created account: '${newAccount.name}' (Key: $nextKeyIndex)")
            return newAccount
        }
    }

    /**
     * Updates an existing account's metadata.
     */
    fun updateAccount(account: Account): Account {
        synchronized(lock) {
            var sanitized = account
            if (sanitized.keyIndex == 0 && (sanitized.pinHash != null || sanitized.isLocked)) {
                sanitized = sanitized.copy(pinHash = null, pinSalt = null, isLocked = false)
            }

            val currentList = getAccounts().toMutableList()
            val index = currentList.indexOfFirst { it.keyIndex == sanitized.keyIndex }
            if (index != -1) {
                currentList[index] = sanitized
            } else {
                currentList.add(sanitized)
            }

            persistAccounts(currentList)
            _accountsFlow.value = currentList

            if (_activeAccountFlow.value.keyIndex == sanitized.keyIndex) {
                _activeAccountFlow.value = sanitized
            }
            return sanitized
        }
    }

    /**
     * Deletes an account and purges all namespaced data via DesktopDataStore.removeKeys("$keyIndex").
     */
    fun deleteAccount(keyIndex: Int): Boolean {
        require(keyIndex != 0) { "Default Account (keyIndex 0) cannot be deleted" }

        synchronized(lock) {
            val accounts = getAccounts().toMutableList()
            val account = accounts.firstOrNull { it.keyIndex == keyIndex } ?: return false

            // Purge all account data under "$keyIndex/"
            val purgedKeys = DesktopDataStore.removeKeys(keyIndex.toString())
            DesktopDataStore.removeKey(keyIndex.toString())
            AppLogger.i("AccountManagerDesktop", "Purged $purgedKeys keys for deleted account: ${account.name} (Key: $keyIndex)")

            accounts.removeIf { it.keyIndex == keyIndex }
            persistAccounts(accounts)
            _accountsFlow.value = accounts

            // If active account was deleted, fail-safe switch to Default Account (0)
            if (_activeAccountFlow.value.keyIndex == keyIndex) {
                val defaultAcc = accounts.firstOrNull { it.keyIndex == 0 } ?: createDefaultAccount()
                _activeAccountFlow.value = defaultAcc
                DesktopDataStore.currentAccount = defaultAcc.keyIndex.toString()
                DesktopDataStore.setKey(SELECTED_KEY_INDEX_KEY, defaultAcc.keyIndex)
                _reloadWatchHistoryFlow.tryEmit(true)
                _reloadLibraryFlow.tryEmit(true)
                _reloadHomeFlow.tryEmit(true)
            }

            _selectedAccountNumberFlow.value += 1
            return true
        }
    }

    fun deleteAccount(account: Account): Boolean = deleteAccount(account.keyIndex)

    // =========================================================================
    // 4. PIN Security Management (PBKDF2-HMAC-SHA256, 10,000 iterations, 16-byte salt)
    // =========================================================================

    /**
     * Sets a PIN for an account using PBKDF2-HMAC-SHA256 with 10,000 iterations and 16-byte random salt.
     */
    fun setPin(keyIndex: Int, pin: String): Boolean {
        require(keyIndex != 0) { "Default Account (keyIndex 0) cannot have a PIN" }
        require(pin.isNotBlank()) { "PIN cannot be blank" }

        synchronized(lock) {
            val account = getAccounts().firstOrNull { it.keyIndex == keyIndex } ?: return false

            val saltBytes = PinSecurity.generateSalt()
            val hashBytes = PinSecurity.hashPin(pin, saltBytes, PinSecurity.ITERATIONS)

            val updated = account.copy(
                pinHash = PinSecurity.bytesToHex(hashBytes),
                pinSalt = PinSecurity.bytesToHex(saltBytes),
                isLocked = true
            )
            updateAccount(updated)
            return true
        }
    }

    /**
     * Verifies an entered PIN against the stored hash in constant time.
     */
    fun verifyPin(keyIndex: Int, pin: String): Boolean {
        synchronized(lock) {
            val account = getAccounts().firstOrNull { it.keyIndex == keyIndex } ?: return false
            val storedHash = account.pinHash ?: return true // Unlocked

            val storedSalt = account.pinSalt
            if (storedSalt != null) {
                val saltBytes = PinSecurity.decodeBytes(storedSalt)
                val expectedHashBytes = PinSecurity.decodeBytes(storedHash)
                return PinSecurity.verifyPin(pin, saltBytes, expectedHashBytes, PinSecurity.ITERATIONS)
            }

            // Legacy fallback if hash format is $pbkdf2$iterations$salt$hash
            if (storedHash.startsWith("\$pbkdf2\$")) {
                return try {
                    val parts = storedHash.split("$")
                    if (parts.size < 5) return false
                    val iterations = parts[2].toIntOrNull() ?: PinSecurity.ITERATIONS
                    val salt = PinSecurity.decodeBytes(parts[3])
                    val expectedHash = PinSecurity.decodeBytes(parts[4])
                    PinSecurity.verifyPin(pin, salt, expectedHash, iterations)
                } catch (_: Exception) {
                    false
                }
            }

            // Legacy plaintext fallback with constant-time equality check
            return try {
                java.security.MessageDigest.isEqual(
                    pin.toByteArray(Charsets.UTF_8),
                    storedHash.toByteArray(Charsets.UTF_8)
                )
            } catch (_: Exception) {
                false
            }
        }
    }

    /**
     * Removes the PIN lock from an account.
     * If pin is specified, it must verify before removal.
     */
    fun removePin(keyIndex: Int, pin: String? = null): Boolean {
        synchronized(lock) {
            val account = getAccounts().firstOrNull { it.keyIndex == keyIndex } ?: return false
            if (pin != null && account.pinHash != null) {
                if (!verifyPin(keyIndex, pin)) {
                    return false
                }
            }
            val updated = account.copy(pinHash = null, pinSalt = null, isLocked = false)
            updateAccount(updated)
            return true
        }
    }

    fun removePin(keyIndex: Int): Boolean = removePin(keyIndex, null)
}

/**
 * User preference property delegate scoping values under "$currentAccount/$key".
 */
class DesktopUserPreferenceDelegate<T : Any>(
    private val key: String,
    private val default: T,
    private val clazz: Class<T>
) {
    private val realKey: String
        get() = "${DesktopDataStore.currentAccount}/$key"

    operator fun getValue(thisRef: Any?, property: KProperty<*>): T {
        return DesktopDataStore.getKey(realKey, clazz) ?: default
    }

    operator fun setValue(thisRef: Any?, property: KProperty<*>, value: T?) {
        if (value == null) {
            DesktopDataStore.removeKey(realKey)
        } else {
            DesktopDataStore.setKey(realKey, value)
        }
    }
}
