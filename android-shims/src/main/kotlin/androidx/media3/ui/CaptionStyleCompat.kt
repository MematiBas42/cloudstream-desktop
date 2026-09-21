package androidx.media3.ui

class CaptionStyleCompat(
    val foregroundColor: Int = -1,
    val backgroundColor: Int = 0,
    val windowColor: Int = 0,
    val edgeType: Int = EDGE_TYPE_OUTLINE,
    val edgeColor: Int = -16777216,
    val typeface: Any? = null,
) {
    companion object {
        const val EDGE_TYPE_NONE = 0
        const val EDGE_TYPE_OUTLINE = 1
        const val EDGE_TYPE_DROP_SHADOW = 2
        const val EDGE_TYPE_RAISED = 3
        const val EDGE_TYPE_DEPRESSED = 4
        const val USE_TRACK_COLOR_SETTINGS = 1

        val DEFAULT = CaptionStyleCompat(
            foregroundColor = -1,
            backgroundColor = 0,
            windowColor = 0,
            edgeType = EDGE_TYPE_OUTLINE,
            edgeColor = -16777216,
            typeface = null
        )
    }

    @Target(AnnotationTarget.TYPE, AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.PROPERTY)
    @Retention(AnnotationRetention.SOURCE)
    annotation class EdgeType
}
