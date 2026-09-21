package android.app

import android.util.Rational

class PictureInPictureParams internal constructor(
    val aspectRatio: Rational?,
    val actions: List<RemoteAction>?,
    val isAutoEnterEnabled: Boolean,
    val isSeamlessResizeEnabled: Boolean
) {
    class Builder {
        private var aspectRatio: Rational? = null
        private var actions: List<RemoteAction>? = null
        private var autoEnterEnabled: Boolean = false
        private var seamlessResizeEnabled: Boolean = false

        fun setAspectRatio(aspectRatio: Rational?): Builder = apply {
            this.aspectRatio = aspectRatio
        }

        fun setActions(actions: List<RemoteAction>?): Builder = apply {
            this.actions = actions
        }

        fun setAutoEnterEnabled(autoEnterEnabled: Boolean): Builder = apply {
            this.autoEnterEnabled = autoEnterEnabled
        }

        fun setSeamlessResizeEnabled(seamlessResizeEnabled: Boolean): Builder = apply {
            this.seamlessResizeEnabled = seamlessResizeEnabled
        }

        fun build(): PictureInPictureParams {
            return PictureInPictureParams(aspectRatio, actions, autoEnterEnabled, seamlessResizeEnabled)
        }
    }
}
