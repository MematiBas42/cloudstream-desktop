package androidx.navigation

class NavOptions(
    val popUpToId: Int = 0,
    val isPopUpToInclusive: Boolean = false,
    val isPopUpToSaveState: Boolean = false,
) {
    class Builder {
        private var popUpToId: Int = 0
        private var isPopUpToInclusive: Boolean = false
        private var isPopUpToSaveState: Boolean = false

        fun setPopUpTo(destinationId: Int, inclusive: Boolean, saveState: Boolean = false): Builder {
            this.popUpToId = destinationId
            this.isPopUpToInclusive = inclusive
            this.isPopUpToSaveState = saveState
            return this
        }

        fun build(): NavOptions = NavOptions(popUpToId, isPopUpToInclusive, isPopUpToSaveState)
    }
}
