// @PortSource(file = "upstream/app/src/main/java/com/lagradost/cloudstream3/ui/library/LibraryFragment.kt", upstreamCommit = "9feeaedee64b3efc150a3f05df18f955abe170d0")
package com.lagradost.cloudstream3.ui.library

import androidx.annotation.StringRes
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.R
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

const val LIBRARY_FOLDER = "library_folder"

enum class LibraryOpenerType(@param:StringRes val stringRes: Int) {
    Default(R.string.action_default),
    Provider(R.string.none),
    Browser(R.string.browser),
    Search(R.string.search),
    None(R.string.none),
}

/** Used to store how the user wants to open said poster */
@Serializable
data class LibraryOpener(
    @param:JsonProperty("openType") @SerialName("openType") val openType: LibraryOpenerType,
    @param:JsonProperty("providerData") @SerialName("providerData") val providerData: ProviderLibraryData?,
)

@Serializable
data class ProviderLibraryData(
    @param:JsonProperty("apiName") @SerialName("apiName") val apiName: String,
)
