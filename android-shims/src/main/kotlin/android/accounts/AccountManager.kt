package android.accounts

import android.content.Context
import android.os.Bundle
import java.util.concurrent.ConcurrentHashMap

open class AccountManager(private val context: Context) {
    private val accountsMap = ConcurrentHashMap<Account, ConcurrentHashMap<String, String>>()

    open fun getAccounts(): Array<Account> {
        return accountsMap.keys.toTypedArray()
    }

    open fun getAccountsByType(type: String?): Array<Account> {
        if (type == null) return getAccounts()
        return accountsMap.keys.filter { it.type == type }.toTypedArray()
    }

    open fun addAccountExplicitly(account: Account, password: String?, userdata: Bundle?): Boolean {
        val data = ConcurrentHashMap<String, String>()
        if (password != null) data["password"] = password
        if (userdata != null) {
            for (k in userdata.keySet()) {
                userdata.getString(k)?.let { data[k] = it }
            }
        }
        return accountsMap.putIfAbsent(account, data) == null
    }

    open fun getPassword(account: Account): String? {
        return accountsMap[account]?.get("password")
    }

    open fun setPassword(account: Account, password: String?) {
        val map = accountsMap[account] ?: return
        if (password == null) map.remove("password") else map["password"] = password
    }

    open fun getUserData(account: Account, key: String): String? {
        return accountsMap[account]?.get(key)
    }

    open fun setUserData(account: Account, key: String, value: String?) {
        val map = accountsMap[account] ?: return
        if (value == null) map.remove(key) else map[key] = value
    }

    open fun invalidateAuthToken(accountType: String?, authToken: String?) {
        // No-op on desktop; tokens are managed via DesktopDataStore / Scrobbler
    }

    companion object {
        const val KEY_AUTHTOKEN = "authtoken"
        const val KEY_ACCOUNT_NAME = "authAccount"
        const val KEY_ACCOUNT_TYPE = "accountType"

        private val instances = ConcurrentHashMap<Context, AccountManager>()

        @JvmStatic
        fun get(context: Context): AccountManager {
            return instances.computeIfAbsent(context) { AccountManager(it) }
        }
    }
}
