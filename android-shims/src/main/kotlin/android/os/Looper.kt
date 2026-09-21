package android.os

class Looper private constructor(private val thread: Thread = Thread.currentThread()) {
    fun getThread(): Thread = thread

    companion object {
        private val MAIN_LOOPER = Looper(Thread.currentThread())

        @JvmStatic
        fun getMainLooper(): Looper = MAIN_LOOPER

        @JvmStatic
        fun myLooper(): Looper = MAIN_LOOPER

        @JvmStatic
        fun prepare() {}

        @JvmStatic
        fun loop() {}
    }
}
