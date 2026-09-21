package android.util

data class Rational(val numerator: Int, val denominator: Int) : Number(), Comparable<Rational> {
    @Suppress("DEPRECATION")
    override fun toByte(): Byte = (numerator.toDouble() / denominator.toDouble()).toInt().toByte()
    @Suppress("DEPRECATION")
    override fun toChar(): Char = (numerator.toDouble() / denominator.toDouble()).toInt().toChar()
    override fun toDouble(): Double = numerator.toDouble() / denominator.toDouble()
    override fun toFloat(): Float = numerator.toFloat() / denominator.toFloat()
    override fun toInt(): Int = (numerator.toDouble() / denominator.toDouble()).toInt()
    override fun toLong(): Long = (numerator.toDouble() / denominator.toDouble()).toLong()
    @Suppress("DEPRECATION")
    override fun toShort(): Short = (numerator.toDouble() / denominator.toDouble()).toInt().toShort()

    override fun compareTo(other: Rational): Int {
        return (numerator.toLong() * other.denominator).compareTo(other.numerator.toLong() * denominator)
    }

    override fun toString(): String = "$numerator/$denominator"
}
