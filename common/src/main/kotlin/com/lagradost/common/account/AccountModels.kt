package com.lagradost.common.account

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Domain representation of a user profile with PIN security metadata.
 * Fully compatible with upstream CloudStream JSON schema.
 */
data class Account(
    @param:JsonProperty("keyIndex") val keyIndex: Int,
    @param:JsonProperty("name") val name: String,
    @param:JsonProperty("imageIndex") val imageIndex: Int,
    @param:JsonProperty("pinHash") val pinHash: String? = null,
    @param:JsonProperty("pinSalt") val pinSalt: String? = null,
    @param:JsonProperty("isLocked") val isLocked: Boolean = false
) {
    @get:JsonIgnore
    val isDefaultAccount: Boolean
        get() = keyIndex == 0

    @get:JsonIgnore
    val isPinLocked: Boolean
        get() = isLocked || !pinHash.isNullOrBlank()

    @get:JsonIgnore
    val avatarPreset: String
        get() = AvatarPresets.getPreset(imageIndex)
}

/**
 * Canonical profile avatar image presets (profile_bg_1 to profile_bg_10).
 */
object AvatarPresets {
    const val COUNT = 10

    const val PROFILE_BG_1 = "profile_bg_1"
    const val PROFILE_BG_2 = "profile_bg_2"
    const val PROFILE_BG_3 = "profile_bg_3"
    const val PROFILE_BG_4 = "profile_bg_4"
    const val PROFILE_BG_5 = "profile_bg_5"
    const val PROFILE_BG_6 = "profile_bg_6"
    const val PROFILE_BG_7 = "profile_bg_7"
    const val PROFILE_BG_8 = "profile_bg_8"
    const val PROFILE_BG_9 = "profile_bg_9"
    const val PROFILE_BG_10 = "profile_bg_10"

    val PRESETS: List<String> = listOf(
        PROFILE_BG_1,
        PROFILE_BG_2,
        PROFILE_BG_3,
        PROFILE_BG_4,
        PROFILE_BG_5,
        PROFILE_BG_6,
        PROFILE_BG_7,
        PROFILE_BG_8,
        PROFILE_BG_9,
        PROFILE_BG_10
    )

    fun getPreset(index: Int): String {
        return if (index in PRESETS.indices) PRESETS[index] else PRESETS[0]
    }

    fun getAssetPath(index: Int): String = getPreset(index)
}

typealias ProfileAvatars = AvatarPresets

/**
 * Industrial-grade PIN security engine supporting PBKDF2-HMAC-SHA256
 * cryptographic hashing with 10,000 iterations and 16-byte random salt.
 */
object PinSecurity {
    const val ALGORITHM = "PBKDF2WithHmacSHA256"
    const val ITERATIONS = 10_000
    const val KEY_LENGTH_BITS = 256
    const val SALT_LENGTH_BYTES = 16

    private val secureRandom = SecureRandom()

    fun generateSalt(): ByteArray {
        val salt = ByteArray(SALT_LENGTH_BYTES)
        secureRandom.nextBytes(salt)
        return salt
    }

    fun hashPin(
        pin: String,
        salt: ByteArray,
        iterations: Int = ITERATIONS,
        keyLengthBits: Int = KEY_LENGTH_BITS
    ): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, keyLengthBits)
        val factory = SecretKeyFactory.getInstance(ALGORITHM)
        return factory.generateSecret(spec).encoded
    }

    fun verifyPin(
        enteredPin: String,
        saltBytes: ByteArray,
        expectedHashBytes: ByteArray,
        iterations: Int = ITERATIONS
    ): Boolean {
        return try {
            val actualHash = hashPin(enteredPin, saltBytes, iterations, expectedHashBytes.size * 8)
            MessageDigest.isEqual(expectedHashBytes, actualHash)
        } catch (_: Exception) {
            false
        }
    }

    fun bytesToHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    fun hexToBytes(hex: String): ByteArray {
        val clean = hex.trim()
        val len = clean.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(clean[i], 16) shl 4) + Character.digit(clean[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }

    fun decodeBytes(str: String): ByteArray {
        val clean = str.trim()
        return if (clean.length % 2 == 0 && clean.all { it in "0123456789abcdefABCDEF" }) {
            hexToBytes(clean)
        } else {
            Base64.getDecoder().decode(clean)
        }
    }
}
