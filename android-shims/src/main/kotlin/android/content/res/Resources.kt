package android.content.res

import android.content.Context
import java.util.Locale

open class Resources(private val context: Context? = null) {
    open val configuration: Configuration = Configuration()

    companion object {
        private val systemResources = Resources(null)
        @JvmStatic
        fun getSystem(): Resources = systemResources
    }

    open fun getQuantityString(id: Int, quantity: Int): String {
        val isTr = Locale.getDefault().language == "tr"
        return when (id) {
            301 -> if (isTr) "%d aktif indirme" else if (quantity == 1) "%d active download" else "%d active downloads"
            302 -> if (isTr) "%d kuyrukta bekleyen indirme" else if (quantity == 1) "%d download queued" else "%d downloads queued"
            303 -> if (isTr) "%d bölüm" else if (quantity == 1) "%d episode" else "%d episodes"
            else -> if (isTr) "%d bölüm" else if (quantity == 1) "%d episode" else "%d episodes"
        }
    }

    open fun getQuantityString(id: Int, quantity: Int, vararg formatArgs: Any?): String {
        val base = getQuantityString(id, quantity)
        val args = if (formatArgs.isNotEmpty()) formatArgs else arrayOf(quantity)
        return String.format(Locale.US, base, *args)
    }

    open fun getString(id: Int): String = context?.getString(id) ?: "res_$id"

    open fun getString(id: Int, vararg formatArgs: Any?): String =
        context?.getString(id, *formatArgs) ?: "res_$id"

    open fun getIdentifier(name: String, defType: String, defPackage: String): Int = 0
}
