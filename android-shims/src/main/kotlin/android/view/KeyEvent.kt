package android.view

open class KeyEvent(
    val action: Int = ACTION_DOWN,
    val keyCode: Int = KEYCODE_UNKNOWN
) {
    companion object {
        const val ACTION_DOWN: Int = 0
        const val ACTION_UP: Int = 1
        const val ACTION_MULTIPLE: Int = 2

        const val KEYCODE_UNKNOWN: Int = 0
        const val KEYCODE_DPAD_UP: Int = 19
        const val KEYCODE_DPAD_DOWN: Int = 20
        const val KEYCODE_DPAD_LEFT: Int = 21
        const val KEYCODE_DPAD_RIGHT: Int = 22
        const val KEYCODE_DPAD_CENTER: Int = 23
        const val KEYCODE_VOLUME_UP: Int = 24
        const val KEYCODE_VOLUME_DOWN: Int = 25
        const val KEYCODE_SPACE: Int = 62
        const val KEYCODE_ENTER: Int = 66
        const val KEYCODE_MEDIA_PLAY_PAUSE: Int = 85
        const val KEYCODE_MEDIA_STOP: Int = 86
        const val KEYCODE_MEDIA_NEXT: Int = 87
        const val KEYCODE_MEDIA_PREVIOUS: Int = 88
        const val KEYCODE_MEDIA_REWIND: Int = 89
        const val KEYCODE_MEDIA_FAST_FORWARD: Int = 90
        const val KEYCODE_MUTE: Int = 91
        const val KEYCODE_MEDIA_PLAY: Int = 126
        const val KEYCODE_MEDIA_PAUSE: Int = 127
        const val KEYCODE_MEDIA_RECORD: Int = 130
        const val KEYCODE_VOLUME_MUTE: Int = 164
        const val KEYCODE_MEDIA_SKIP_FORWARD: Int = 272
        const val KEYCODE_MEDIA_SKIP_BACKWARD: Int = 273
        const val KEYCODE_MEDIA_STEP: Int = 274

        fun isMediaKey(keyCode: Int): Boolean = when (keyCode) {
            KEYCODE_MEDIA_PLAY,
            KEYCODE_MEDIA_PAUSE,
            KEYCODE_MEDIA_PLAY_PAUSE,
            KEYCODE_MEDIA_STOP,
            KEYCODE_MEDIA_NEXT,
            KEYCODE_MEDIA_PREVIOUS,
            KEYCODE_MEDIA_REWIND,
            KEYCODE_MEDIA_FAST_FORWARD,
            KEYCODE_MEDIA_SKIP_FORWARD,
            KEYCODE_MEDIA_SKIP_BACKWARD,
            KEYCODE_MEDIA_STEP,
            KEYCODE_MEDIA_RECORD,
            KEYCODE_MUTE,
            KEYCODE_VOLUME_MUTE -> true
            else -> false
        }

        fun keyCodeToString(keyCode: Int): String = when (keyCode) {
            KEYCODE_DPAD_UP -> "KEYCODE_DPAD_UP"
            KEYCODE_DPAD_DOWN -> "KEYCODE_DPAD_DOWN"
            KEYCODE_DPAD_LEFT -> "KEYCODE_DPAD_LEFT"
            KEYCODE_DPAD_RIGHT -> "KEYCODE_DPAD_RIGHT"
            KEYCODE_DPAD_CENTER -> "KEYCODE_DPAD_CENTER"
            KEYCODE_ENTER -> "KEYCODE_ENTER"
            KEYCODE_SPACE -> "KEYCODE_SPACE"
            KEYCODE_MEDIA_PLAY_PAUSE -> "KEYCODE_MEDIA_PLAY_PAUSE"
            KEYCODE_MEDIA_STOP -> "KEYCODE_MEDIA_STOP"
            KEYCODE_MEDIA_NEXT -> "KEYCODE_MEDIA_NEXT"
            KEYCODE_MEDIA_PREVIOUS -> "KEYCODE_MEDIA_PREVIOUS"
            KEYCODE_MEDIA_REWIND -> "KEYCODE_MEDIA_REWIND"
            KEYCODE_MEDIA_FAST_FORWARD -> "KEYCODE_MEDIA_FAST_FORWARD"
            KEYCODE_MEDIA_PLAY -> "KEYCODE_MEDIA_PLAY"
            KEYCODE_MEDIA_PAUSE -> "KEYCODE_MEDIA_PAUSE"
            KEYCODE_MEDIA_RECORD -> "KEYCODE_MEDIA_RECORD"
            KEYCODE_MEDIA_SKIP_FORWARD -> "KEYCODE_MEDIA_SKIP_FORWARD"
            KEYCODE_MEDIA_SKIP_BACKWARD -> "KEYCODE_MEDIA_SKIP_BACKWARD"
            KEYCODE_MEDIA_STEP -> "KEYCODE_MEDIA_STEP"
            KEYCODE_MUTE -> "KEYCODE_MUTE"
            KEYCODE_VOLUME_MUTE -> "KEYCODE_VOLUME_MUTE"
            KEYCODE_VOLUME_UP -> "KEYCODE_VOLUME_UP"
            KEYCODE_VOLUME_DOWN -> "KEYCODE_VOLUME_DOWN"
            else -> "KEYCODE_$keyCode"
        }
    }
}
