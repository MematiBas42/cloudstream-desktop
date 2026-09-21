package com.lagradost.player.impl.proxy

import com.lagradost.common.logging.AppLogger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class CencDecryptor(
    private val data: ByteArray,
    private val key: ByteArray?,
) {
    private var logOnce = false

    fun decrypt(): ByteArray {
        if (key == null || key.size != 16) return data
        return try {
            decryptInternal()
        } catch (e: Exception) {
            AppLogger.e("CENC decrypt failed: ${e.message}")
            data
        }
    }

    fun isPossiblyCencEncrypted(): Boolean {
        return hasMoofBox() && hasMdatBox()
    }

    private fun hasMoofBox(): Boolean {
        var offset = 0
        while (offset < data.size - 8) {
            val size = readU32(offset)
            if (size == 0 || size > data.size - offset) break
            if (data[offset + 4] == 'm'.code.toByte() &&
                data[offset + 5] == 'o'.code.toByte() &&
                data[offset + 6] == 'o'.code.toByte() &&
                data[offset + 7] == 'f'.code.toByte()
            ) return true
            offset += size
        }
        return false
    }

    private fun hasMdatBox(): Boolean {
        var offset = 0
        while (offset < data.size - 8) {
            val size = readU32(offset)
            if (size == 0 || size > data.size - offset) break
            if (data[offset + 4] == 'm'.code.toByte() &&
                data[offset + 5] == 'd'.code.toByte() &&
                data[offset + 6] == 'a'.code.toByte() &&
                data[offset + 7] == 't'.code.toByte()
            ) return true
            offset += size
        }
        return false
    }

    private fun decryptInternal(): ByteArray {
        val result = data.copyOf()
        var offset = 0
        val samples = mutableListOf<Int>()
        val crypto = mutableListOf<Pair<ByteArray, List<Pair<Int, Int>>>>()
        var mdatOffset = -1
        var mdatSize = 0

        while (offset < result.size - 8) {
            val boxSize = readU32(offset)
            if (boxSize == 0 || boxSize > result.size - offset) break
            val boxType = String(result, offset + 4, 4, Charsets.ISO_8859_1)

            when (boxType) {
                "moof" -> parseMoof(result, offset, boxSize, samples, crypto)
                "mdat" -> {
                    mdatOffset = offset
                    mdatSize = boxSize
                }
            }
            offset += boxSize
        }

        if (samples.isNotEmpty() && crypto.isNotEmpty() && mdatOffset >= 0) {
            decryptMdat(result, mdatOffset + 8, mdatSize - 8, samples, crypto)
            if (!logOnce) {
                logOnce = true
                AppLogger.i("CENC decrypt: ${samples.size} samples, ${crypto.size} crypto entries")
            }
        }

        return result
    }

    private fun parseMoof(
        data: ByteArray,
        offset: Int,
        size: Int,
        samples: MutableList<Int>,
        crypto: MutableList<Pair<ByteArray, List<Pair<Int, Int>>>>,
    ) {
        var pos = offset + 8
        val end = offset + size
        while (pos < end - 8) {
            val childSize = readU32(pos)
            if (childSize == 0 || childSize > end - pos) break
            if (String(data, pos + 4, 4, Charsets.ISO_8859_1) == "traf") {
                parseTraf(data, pos, childSize, samples, crypto)
            }
            pos += childSize
        }
    }

    private fun parseTraf(
        data: ByteArray,
        offset: Int,
        size: Int,
        samples: MutableList<Int>,
        crypto: MutableList<Pair<ByteArray, List<Pair<Int, Int>>>>,
    ) {
        var pos = offset + 8
        val end = offset + size
        while (pos < end - 8) {
            val childSize = readU32(pos)
            if (childSize == 0 || childSize > end - pos) break
            val childType = String(data, pos + 4, 4, Charsets.ISO_8859_1)
            when (childType) {
                "trun" -> samples.addAll(parseTrun(data, pos))
                "senc" -> crypto.addAll(parseSenc(data, pos))
            }
            pos += childSize
        }
    }

    private fun parseTrun(data: ByteArray, offset: Int): List<Int> {
        val flags = readU32(offset + 8) and 0xFFFFFF
        val sampleCount = readU32(offset + 12)
        var cursor = offset + 16

        if (flags and 0x01 != 0) cursor += 4
        if (flags and 0x04 != 0) cursor += 4

        val sizes = mutableListOf<Int>()
        for (i in 0 until sampleCount) {
            if (flags and 0x100 != 0) cursor += 4
            val sampleSize = if (flags and 0x200 != 0) {
                val s = readU32(cursor)
                cursor += 4
                s
            } else {
                0
            }
            if (flags and 0x400 != 0) cursor += 4
            if (flags and 0x800 != 0) cursor += 4
            sizes.add(sampleSize)
        }
        return sizes
    }

    private fun parseSenc(data: ByteArray, offset: Int): List<Pair<ByteArray, List<Pair<Int, Int>>>> {
        val flags = readU32(offset + 8) and 0xFFFFFF
        val sampleCount = readU32(offset + 12)
        val useSubsamples = (flags and 0x02) != 0
        var cursor = offset + 16

        val entries = mutableListOf<Pair<ByteArray, List<Pair<Int, Int>>>>()
        for (i in 0 until sampleCount) {
            if (cursor + 8 > data.size) break
            val iv = data.copyOfRange(cursor, cursor + 8)
            cursor += 8
            val subs = mutableListOf<Pair<Int, Int>>()
            if (useSubsamples) {
                if (cursor + 2 > data.size) break
                val subCount = readU16(cursor)
                cursor += 2
                for (j in 0 until subCount) {
                    if (cursor + 6 > data.size) break
                    val clearBytes = readU16(cursor)
                    val cipherBytes = readU32(cursor + 2)
                    cursor += 6
                    subs.add(clearBytes to cipherBytes)
                }
            }
            entries.add(iv to subs)
        }
        return entries
    }

    private fun decryptMdat(
        data: ByteArray,
        start: Int,
        total: Int,
        samples: List<Int>,
        crypto: List<Pair<ByteArray, List<Pair<Int, Int>>>>,
    ) {
        var cursor = start
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")

        for (i in 0 until minOf(samples.size, crypto.size)) {
            val sampleSize = samples[i]
            val (iv, subsamples) = crypto[i]

            if (cursor + sampleSize > data.size) {
                AppLogger.w("CENC: sample $i overflows mdat (off=$cursor, size=$sampleSize)")
                break
            }

            val fullIv = ByteArray(16)
            System.arraycopy(iv, 0, fullIv, 0, 8)

            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(fullIv))

            if (subsamples.isEmpty()) {
                val decrypted = cipher.doFinal(data, cursor, sampleSize)
                System.arraycopy(decrypted, 0, data, cursor, decrypted.size)
            } else {
                var localCursor = cursor
                for (sIdx in subsamples.indices) {
                    val (clearBytes, encryptedBytes) = subsamples[sIdx]
                    localCursor += clearBytes
                    if (encryptedBytes > 0 && localCursor + encryptedBytes <= data.size) {
                        val decrypted = if (sIdx == subsamples.lastIndex) {
                            cipher.doFinal(data, localCursor, encryptedBytes)
                        } else {
                            cipher.update(data, localCursor, encryptedBytes)
                        }
                        System.arraycopy(decrypted, 0, data, localCursor, decrypted.size)
                        localCursor += encryptedBytes
                    }
                }
            }

            cursor += sampleSize
        }
    }

    private fun readU32(offset: Int): Int {
        return ((data[offset].toInt() and 0xFF) shl 24) or
            ((data[offset + 1].toInt() and 0xFF) shl 16) or
            ((data[offset + 2].toInt() and 0xFF) shl 8) or
            (data[offset + 3].toInt() and 0xFF)
    }

    private fun readU16(offset: Int): Int {
        return ((data[offset].toInt() and 0xFF) shl 8) or
            (data[offset + 1].toInt() and 0xFF)
    }
}
