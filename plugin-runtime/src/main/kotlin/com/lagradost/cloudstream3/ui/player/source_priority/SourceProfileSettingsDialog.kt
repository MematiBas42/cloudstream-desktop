// @PortSource(file = "upstream/app/src/main/java/com/lagradost/cloudstream3/ui/player/source_priority/SourceProfileSettingsDialog.kt", upstreamCommit = "caeec18")
package com.lagradost.cloudstream3.ui.player.source_priority

import android.app.Dialog
import android.content.Context
import androidx.annotation.StyleRes
import com.lagradost.cloudstream3.utils.UIHelper.dismissSafe

/**
 * Source Profile Boolean Settings Dialog.
 *
 * Configures profile-specific settings such as [ProfileSettings.HideErrorSources]
 * and [ProfileSettings.HideNegativeSources].
 */
class SourceProfileSettingsDialog(
    val ctx: Context,
    @StyleRes val themeRes: Int = 0,
    val profile: Int
) : Dialog(ctx, themeRes) {

    var hideErrorSources: Boolean = QualityDataHelper.getProfileSetting(profile, ProfileSettings.HideErrorSources)
    var hideNegativeSources: Boolean = QualityDataHelper.getProfileSetting(profile, ProfileSettings.HideNegativeSources)

    var onShowListener: ((SourceProfileSettingsDialog) -> Unit)? = null

    /**
     * Persists updated boolean settings to [QualityDataHelper] and dismisses the dialog.
     */
    fun save(
        hideErrorSources: Boolean = this.hideErrorSources,
        hideNegativeSources: Boolean = this.hideNegativeSources
    ) {
        this.hideErrorSources = hideErrorSources
        this.hideNegativeSources = hideNegativeSources
        QualityDataHelper.setProfileSetting(profile, ProfileSettings.HideErrorSources, hideErrorSources)
        QualityDataHelper.setProfileSetting(profile, ProfileSettings.HideNegativeSources, hideNegativeSources)
        dismissSafe()
    }

    /**
     * Closes the dialog without saving changes.
     */
    fun cancelSettings() {
        dismissSafe()
    }

    override fun show() {
        super.show()
        hideErrorSources = QualityDataHelper.getProfileSetting(profile, ProfileSettings.HideErrorSources)
        hideNegativeSources = QualityDataHelper.getProfileSetting(profile, ProfileSettings.HideNegativeSources)
        onShowListener?.invoke(this)
    }

    override fun dismiss() {
        super.dismiss()
    }
}
