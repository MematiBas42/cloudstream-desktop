package android.util

import java.util.Base64 as JavaBase64

object Base64 {
    const val DEFAULT: Int = 0
    const val NO_PADDING: Int = 1
    const val NO_WRAP: Int = 2
    const val CRLF: Int = 4
    const val URL_SAFE: Int = 8
    const val NO_CLOSE: Int = 16

    @JvmStatic
    fun decode(str: String, flags: Int): ByteArray {
        val cleaned = str.trim()
        return if ((flags and URL_SAFE) != 0) {
            try {
                JavaBase64.getUrlDecoder().decode(cleaned)
            } catch (e: IllegalArgumentException) {
                JavaBase64.getDecoder().decode(cleaned)
            }
        } else {
            try {
                JavaBase64.getDecoder().decode(cleaned)
            } catch (e: IllegalArgumentException) {
                JavaBase64.getUrlDecoder().decode(cleaned)
            }
        }
    }

    @JvmStatic
    fun decode(input: ByteArray, flags: Int): ByteArray {
        return if ((flags and URL_SAFE) != 0) {
            try {
                JavaBase64.getUrlDecoder().decode(input)
            } catch (e: IllegalArgumentException) {
                JavaBase64.getDecoder().decode(input)
            }
        } else {
            try {
                JavaBase64.getDecoder().decode(input)
            } catch (e: IllegalArgumentException) {
                JavaBase64.getUrlDecoder().decode(input)
            }
        }
    }

    @JvmStatic
    fun decode(input: ByteArray, offset: Int, len: Int, flags: Int): ByteArray {
        val slice = if (offset == 0 && len == input.size) input else input.copyOfRange(offset, offset + len)
        return decode(slice, flags)
    }

    @JvmStatic
    fun encode(input: ByteArray, flags: Int): ByteArray {
        val urlSafe = (flags and URL_SAFE) != 0
        val noPadding = (flags and NO_PADDING) != 0
        var encoder = if (urlSafe) JavaBase64.getUrlEncoder() else JavaBase64.getEncoder()
        if (noPadding) {
            encoder = encoder.withoutPadding()
        }
        return encoder.encode(input)
    }

    @JvmStatic
    fun encode(input: ByteArray, offset: Int, len: Int, flags: Int): ByteArray {
        val slice = if (offset == 0 && len == input.size) input else input.copyOfRange(offset, offset + len)
        return encode(slice, flags)
    }

    @JvmStatic
    fun encodeToString(input: ByteArray, flags: Int): String {
        val urlSafe = (flags and URL_SAFE) != 0
        val noPadding = (flags and NO_PADDING) != 0
        var encoder = if (urlSafe) JavaBase64.getUrlEncoder() else JavaBase64.getEncoder()
        if (noPadding) {
            encoder = encoder.withoutPadding()
        }
        return encoder.encodeToString(input)
    }

    @JvmStatic
    fun encodeToString(input: ByteArray, offset: Int, len: Int, flags: Int): String {
        val slice = if (offset == 0 && len == input.size) input else input.copyOfRange(offset, offset + len)
        return encodeToString(slice, flags)
    }
}
