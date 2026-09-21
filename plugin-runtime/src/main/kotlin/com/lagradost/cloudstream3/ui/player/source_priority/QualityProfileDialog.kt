// @PortSource(file = "upstream/app/src/main/java/com/lagradost/cloudstream3/ui/player/source_priority/QualityProfileDialog.kt", upstreamCommit = "caeec18")
package com.lagradost.cloudstream3.ui.player.source_priority

import android.app.Activity
import android.app.Dialog
import androidx.annotation.StyleRes
import com.lagradost.cloudstream3.ui.player.source_priority.QualityDataHelper.getAllSourcePriorityNames
import com.lagradost.cloudstream3.ui.player.source_priority.QualityDataHelper.getProfiles
import com.lagradost.cloudstream3.utils.Coroutines.ioWork
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.UIHelper.dismissSafe

/** Simplified ExtractorLink for the quality profile dialog */
data class LinkSource(
    val source: String
) {
    constructor(extractorLink: ExtractorLink) : this(extractorLink.source)
}

/**
 * Quality Profile Selection & Management Dialog.
 *
 * Facilitates selecting the active playback quality profile (e.g. WiFi, Mobile Data, Download),
 * configuring default profile types, and navigating to [SourcePriorityDialog] for detailed
 * priority adjustments.
 */
class QualityProfileDialog private constructor(
    val activity: Activity,
    @StyleRes val themeRes: Int,
    val links: List<LinkSource>,
    val usedProfile: Int?,
    val profileSelectionCallback: ((QualityDataHelper.QualityProfile) -> Unit)?,
    val useProfileSelection: Boolean
) : Dialog(activity, themeRes) {

    constructor(
        activity: Activity,
        @StyleRes themeRes: Int = 0,
        links: List<LinkSource>,
        usedProfile: Int,
        profileSelectionCallback: ((QualityDataHelper.QualityProfile) -> Unit),
    ) : this(activity, themeRes, links, usedProfile, profileSelectionCallback, true)

    constructor(
        activity: Activity,
        @StyleRes themeRes: Int = 0,
        links: List<LinkSource>
    ) : this(activity, themeRes, links, null, null, false)

    companion object {
        /**
         * Collects all distinct configured source names across all quality profiles.
         * Runs on IO dispatcher as this queries persistent storage.
         */
        suspend fun getAllDefaultSources(): List<LinkSource> = ioWork {
            getProfiles().flatMap {
                getAllSourcePriorityNames(it.id)
            }.distinct().map { LinkSource(it) }
        }
    }

    var selectedProfile: QualityDataHelper.QualityProfile? = null
        private set

    var onShowListener: ((QualityProfileDialog) -> Unit)? = null

    init {
        if (usedProfile != null) {
            selectedProfile = getProfilesList().firstOrNull { it.id == usedProfile }
        }
    }

    /**
     * Retrieves all available profiles from [QualityDataHelper].
     */
    fun getProfilesList(): List<QualityDataHelper.QualityProfile> {
        return getProfiles()
    }

    /**
     * Returns the currently selected profile.
     */
    fun getCurrentProfile(): QualityDataHelper.QualityProfile? {
        return selectedProfile
    }

    /**
     * Updates the currently selected profile.
     */
    fun selectProfile(profile: QualityDataHelper.QualityProfile) {
        selectedProfile = profile
    }

    /**
     * Updates the currently selected profile by index (0-based).
     */
    fun selectProfileByIndex(index: Int) {
        selectedProfile = getProfilesList().getOrNull(index)
    }

    /**
     * Confirms profile selection and invokes [profileSelectionCallback].
     */
    fun applySelection() {
        if (useProfileSelection) {
            selectedProfile?.let {
                profileSelectionCallback?.invoke(it)
                dismissSafe()
            }
        } else {
            dismissSafe()
        }
    }

    /**
     * Updates the default types (e.g. WiFi, Data, Download) for a given profile ID.
     * Enforces uniqueness for unique profile types by clearing them from existing profiles.
     */
    fun setDefaultProfileTypes(
        profileId: Int,
        pickedTypes: List<QualityDataHelper.QualityProfileType>
    ) {
        pickedTypes.forEach { pickedChoice ->
            if (pickedChoice.unique) {
                getProfiles().filter { it.types.contains(pickedChoice) }.forEach {
                    QualityDataHelper.removeQualityProfileType(it.id, pickedChoice)
                }
            }
            QualityDataHelper.addQualityProfileType(profileId, pickedChoice)
        }
        // Refresh selectedProfile reference with updated types
        if (selectedProfile?.id == profileId) {
            selectedProfile = getProfilesList().firstOrNull { it.id == profileId }
        }
    }

    /**
     * Spawns a [SourcePriorityDialog] to edit priorities and rename the specified [profile].
     */
    fun editProfile(
        profile: QualityDataHelper.QualityProfile,
        onUpdated: () -> Unit = {}
    ): SourcePriorityDialog {
        val dialog = SourcePriorityDialog(activity, themeRes, links, profile) {
            if (selectedProfile?.id == profile.id) {
                selectedProfile = getProfilesList().firstOrNull { it.id == profile.id }
            }
            onUpdated()
        }
        dialog.show()
        return dialog
    }

    override fun show() {
        super.show()
        if (usedProfile != null && selectedProfile == null) {
            selectedProfile = getProfilesList().firstOrNull { it.id == usedProfile }
        }
        onShowListener?.invoke(this)
    }

    override fun dismiss() {
        super.dismiss()
    }
}
