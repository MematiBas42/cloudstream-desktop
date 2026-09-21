package android.media.audiofx

open class LoudnessEnhancer(val audioSessionId: Int) {
    private var _targetGain: Int = 0
    var enabled: Boolean = false

    open fun setTargetGain(gainmB: Int) { _targetGain = gainmB }
    open fun getTargetGain(): Int = _targetGain

    open fun release() { enabled = false }
}
