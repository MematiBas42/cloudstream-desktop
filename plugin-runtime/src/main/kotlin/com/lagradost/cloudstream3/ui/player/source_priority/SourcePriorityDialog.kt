// @PortSource(file = "upstream/app/src/main/java/com/lagradost/cloudstream3/ui/player/source_priority/SourcePriorityDialog.kt", upstreamCommit = "caeec18")
package com.lagradost.cloudstream3.ui.player.source_priority

import android.app.Dialog
import android.content.Context
import androidx.annotation.StyleRes
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.UIHelper.dismissSafe
import com.lagradost.cloudstream3.utils.txt

/**
 * Source and Quality Priority Adjustment Dialog.
 *
 * Provides concrete priority sorting, editing, and persistence for source links
 * and video qualities associated with a specific [QualityDataHelper.QualityProfile].
 */
class SourcePriorityDialog(
    val ctx: Context,
    @StyleRes val themeRes: Int = 0,
    val links: List<LinkSource>,
    val profile: QualityDataHelper.QualityProfile,
    /**
     * Notify that the profile overview should be updated, for example if the name has been updated
     * Should not be called excessively.
     **/
    val updatedCallback: () -> Unit
) : Dialog(ctx, themeRes) {

    val profileId: Int get() = profile.id

    var currentSources: List<SourcePriority<Nothing?>> = emptyList()
        private set

    var currentQualities: List<SourcePriority<Qualities>> = emptyList()
        private set

    var currentProfileName: String = ""
        private set

    var onShowListener: ((SourcePriorityDialog) -> Unit)? = null

    init {
        currentSources = getSortedSources()
        currentQualities = getSortedQualities()
        currentProfileName = getProfileName()
    }

    /**
     * Computes distinct source links sorted in descending order of their configured priority.
     */
    fun getSortedSources(): List<SourcePriority<Nothing?>> {
        return links.map { link ->
            SourcePriority(
                null,
                link.source,
                QualityDataHelper.getSourcePriority(profile.id, link.source)
            )
        }.distinctBy { it.name }.sortedBy { -it.priority }
    }

    /**
     * Computes all supported video qualities sorted in descending order of their configured priority.
     */
    fun getSortedQualities(): List<SourcePriority<Qualities>> {
        return Qualities.entries.mapNotNull {
            SourcePriority(
                it,
                Qualities.getStringByIntFull(it.value).ifBlank { return@mapNotNull null },
                QualityDataHelper.getQualityPriority(profile.id, it)
            )
        }.sortedBy { -it.priority }
    }

    /**
     * Resolves the user-configured profile name or localized default string.
     */
    fun getProfileName(): String {
        return QualityDataHelper.getProfileName(profile.id).asString(ctx)
    }

    /**
     * Resolves the hint text (e.g. "Profile 1") for the profile name editor.
     */
    fun getProfileHint(): String {
        return txt(R.string.profile_number, profile.id).asString(ctx)
    }

    /**
     * Persists updated source priorities, quality priorities, and profile name to [QualityDataHelper]
     * and notifies [updatedCallback].
     */
    fun save(
        savedProfileName: String?,
        sources: List<SourcePriority<Nothing?>> = currentSources,
        qualities: List<SourcePriority<Qualities>> = currentQualities
    ) {
        qualities.forEach {
            QualityDataHelper.setQualityPriority(profile.id, it.data, it.priority)
        }

        sources.forEach {
            QualityDataHelper.setSourcePriority(profile.id, it.name, it.priority)
        }

        currentQualities = qualities.sortedBy { -it.priority }
        currentSources = sources.sortedBy { -it.priority }

        if (savedProfileName.isNullOrBlank()) {
            QualityDataHelper.setProfileName(profile.id, null)
        } else {
            QualityDataHelper.setProfileName(profile.id, savedProfileName.trim())
        }
        currentProfileName = getProfileName()

        updatedCallback.invoke()
    }

    /**
     * Opens the associated [SourceProfileSettingsDialog] for boolean settings (HideErrorSources, HideNegativeSources).
     */
    fun openSettings(): SourceProfileSettingsDialog {
        val dialog = SourceProfileSettingsDialog(ctx, themeRes, profile.id)
        dialog.show()
        return dialog
    }

    /**
     * Closes the dialog safely.
     */
    fun close() {
        dismissSafe()
    }

    override fun show() {
        super.show()
        currentSources = getSortedSources()
        currentQualities = getSortedQualities()
        currentProfileName = getProfileName()
        onShowListener?.invoke(this)
    }

    override fun dismiss() {
        super.dismiss()
    }
}
