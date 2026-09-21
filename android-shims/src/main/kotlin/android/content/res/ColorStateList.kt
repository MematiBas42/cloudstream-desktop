package android.content.res

import java.io.Serializable

/**
 * Pure JVM implementation of Android's ColorStateList.
 * Supports static valueOf factory, state set evaluation, and alpha manipulation.
 */
class ColorStateList(
    private val states: Array<IntArray>,
    private val colors: IntArray
) : Serializable {

    fun getDefaultColor(): Int = if (colors.isNotEmpty()) colors[0] else 0

    val defaultColor: Int
        @JvmName("defaultColorProp")
        get() = getDefaultColor()

    fun isStateful(): Boolean = states.size > 1

    val isStateful: Boolean
        @JvmName("isStatefulProp")
        get() = isStateful()

    fun getColorForState(stateSet: IntArray?, defaultColor: Int): Int {
        if (stateSet == null) return this.getDefaultColor()
        for (i in states.indices) {
            val stateSpec = states[i]
            if (stateSpecMatch(stateSpec, stateSet)) {
                return colors[i]
            }
        }
        return defaultColor
    }

    fun withAlpha(alpha: Int): ColorStateList {
        val clampedAlpha = alpha.coerceIn(0, 255)
        val newColors = IntArray(colors.size)
        for (i in colors.indices) {
            val c = colors[i]
            newColors[i] = (c and 0x00FFFFFF) or (clampedAlpha shl 24)
        }
        return ColorStateList(states, newColors)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ColorStateList) return false
        return states.contentDeepEquals(other.states) && colors.contentEquals(other.colors)
    }

    override fun hashCode(): Int {
        var result = states.contentDeepHashCode()
        result = 31 * result + colors.contentHashCode()
        return result
    }

    override fun toString(): String {
        return "ColorStateList(defaultColor=#${Integer.toHexString(getDefaultColor())})"
    }

    companion object {
        private const val serialVersionUID = 1L
        private val EMPTY_STATES = arrayOf(IntArray(0))

        @JvmStatic
        fun valueOf(color: Int): ColorStateList {
            return ColorStateList(EMPTY_STATES, intArrayOf(color))
        }

        private fun stateSpecMatch(stateSpec: IntArray, stateSet: IntArray): Boolean {
            if (stateSpec.isEmpty()) return true
            for (spec in stateSpec) {
                val mustHave = spec > 0
                val targetState = if (mustHave) spec else -spec
                var found = false
                for (state in stateSet) {
                    if (state == targetState) {
                        found = true
                        break
                    }
                }
                if (mustHave != found) return false
            }
            return true
        }
    }
}
