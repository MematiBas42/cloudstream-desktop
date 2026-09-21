package android.media

open class AudioManager {
    private var streamVolume: Int = 10
    private val maxVolume: Int = 15

    open fun getStreamVolume(streamType: Int): Int = streamVolume
    open fun getStreamMaxVolume(streamType: Int): Int = maxVolume
    open fun setStreamVolume(streamType: Int, index: Int, flags: Int) {
        streamVolume = index.coerceIn(0, maxVolume)
    }

    companion object {
        const val STREAM_MUSIC = 3
        const val ERROR = -1
    }
}
