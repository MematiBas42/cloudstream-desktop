// @PortSource(file = "upstream/app/src/main/java/com/lagradost/cloudstream3/utils/ImageUtil.kt", upstreamCommit = "caeec18")
package com.lagradost.cloudstream3.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/// Type safe any image, because THIS IS NOT PYTHON
sealed class UiImage {
    data class Image(
        val url: String,
        val headers: Map<String, String>? = null,
        val width: Int? = null,
        val height: Int? = null,
    ) : UiImage()

    data class Drawable(@DrawableRes val resId: Int) : UiImage()

    data class Bitmap(val bitmap: android.graphics.Bitmap) : UiImage() {
        constructor(bufferedImage: BufferedImage) : this(android.graphics.Bitmap(bufferedImage))
    }
}

/**
 * Resolves a drawable resource into a type-safe [UiImage] (as a [UiImage.Bitmap]).
 * Matches upstream getImageFromDrawable semantics on desktop runtime.
 */
fun getImageFromDrawable(context: Context, drawableRes: Int): UiImage? {
    val drawable = ContextCompat.getDrawable(context, drawableRes) ?: return null
    val bitmap = drawableToBitmap(drawable) ?: return null
    return UiImage.Bitmap(bitmap)
}

/**
 * Converts an Android [Drawable] to a desktop [Bitmap].
 * Matches upstream drawableToBitmap algorithm byte-to-byte using Canvas and createBitmap.
 */
fun drawableToBitmap(drawable: Drawable): Bitmap? {
    return when (drawable) {
        is BitmapDrawable -> drawable.bitmap
        else -> {
            val width = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 1
            val height = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 1
            val bitmap = createBitmap(width, height)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            bitmap
        }
    }
}

/**
 * Direct conversion from [Drawable] to Java [BufferedImage].
 */
fun drawableToBufferedImage(drawable: Drawable): BufferedImage? {
    return drawableToBitmap(drawable)?.image
}

/**
 * Converts a [Bitmap] to a standard Java [BufferedImage].
 */
fun Bitmap.toBufferedImage(): BufferedImage {
    return this.image ?: BufferedImage(
        this.width.coerceAtLeast(1),
        this.height.coerceAtLeast(1),
        BufferedImage.TYPE_INT_ARGB
    )
}

/**
 * Converts a Java [BufferedImage] to an Android-compatible [Bitmap].
 */
fun BufferedImage.toBitmap(): Bitmap {
    return Bitmap(this)
}

/**
 * Wraps a Java [BufferedImage] into a [UiImage.Bitmap].
 */
fun BufferedImage.toUiImage(): UiImage.Bitmap {
    return UiImage.Bitmap(this)
}

/**
 * Extracts a Java [BufferedImage] from a [UiImage] if available.
 */
fun UiImage.toBufferedImage(): BufferedImage? {
    return when (this) {
        is UiImage.Bitmap -> this.bitmap.image ?: this.bitmap.toBufferedImage()
        is UiImage.Drawable -> null
        is UiImage.Image -> null
    }
}

/**
 * Extracts an Android [Bitmap] from a [UiImage] if available.
 */
fun UiImage.toBitmap(): Bitmap? {
    return when (this) {
        is UiImage.Bitmap -> this.bitmap
        is UiImage.Drawable -> null
        is UiImage.Image -> null
    }
}

/**
 * Converts a [UiImage] to a Skiko / Skia `org.jetbrains.skia.Image` instance via raster byte encoding.
 * Returns null if the underlying image is unavailable or if Skia runtime is not present on the classpath.
 */
fun UiImage.toSkiaImage(): Any? {
    return when (this) {
        is UiImage.Bitmap -> this.bitmap.toSkiaImage()
        else -> null
    }
}

/**
 * Converts a [Bitmap] to a Skiko / Skia `org.jetbrains.skia.Image`.
 */
fun Bitmap.toSkiaImage(): Any? {
    val img = this.toBufferedImage()
    return bufferedImageToSkiaImage(img)
}

/**
 * Converts a [BufferedImage] to a Skiko / Skia `org.jetbrains.skia.Image`.
 */
fun BufferedImage.toSkiaImage(): Any? {
    return bufferedImageToSkiaImage(this)
}

/**
 * Encodes a Java [BufferedImage] to PNG byte buffer and decodes via `org.jetbrains.skia.Image.makeFromEncoded`.
 */
fun bufferedImageToSkiaImage(bufferedImage: BufferedImage): Any? {
    return try {
        val baos = ByteArrayOutputStream()
        ImageIO.write(bufferedImage, "png", baos)
        val bytes = baos.toByteArray()
        if (bytes.isEmpty()) return null
        val skiaClass = Class.forName("org.jetbrains.skia.Image")
        val method = skiaClass.getMethod("makeFromEncoded", ByteArray::class.java)
        method.invoke(null, bytes)
    } catch (_: Throwable) {
        null
    }
}

/**
 * Converts a Skiko / Skia `org.jetbrains.skia.Image` back into a Java [BufferedImage].
 */
fun skiaImageToBufferedImage(skiaImage: Any): BufferedImage? {
    return try {
        val encodeToDataMethod = skiaImage.javaClass.getMethod("encodeToData")
        val data = encodeToDataMethod.invoke(skiaImage) ?: return null
        val bytesMethod = data.javaClass.getMethod("getBytes")
        val bytes = bytesMethod.invoke(data) as? ByteArray ?: return null
        if (bytes.isEmpty()) return null
        val bais = ByteArrayInputStream(bytes)
        ImageIO.read(bais)
    } catch (_: Throwable) {
        null
    }
}

/**
 * Converts a Skiko / Skia `org.jetbrains.skia.Image` into an Android [Bitmap].
 */
fun skiaImageToBitmap(skiaImage: Any): Bitmap? {
    val bImg = skiaImageToBufferedImage(skiaImage) ?: return null
    return Bitmap(bImg)
}

/**
 * Converts a Skiko / Skia `org.jetbrains.skia.Image` into a [UiImage.Bitmap].
 */
fun skiaImageToUiImage(skiaImage: Any): UiImage.Bitmap? {
    val bitmap = skiaImageToBitmap(skiaImage) ?: return null
    return UiImage.Bitmap(bitmap)
}

/**
 * Serializes a [Bitmap] to an encoded byte array (PNG/JPEG).
 */
fun Bitmap.toByteArray(format: String = "png"): ByteArray? {
    val img = this.image ?: this.toBufferedImage()
    return try {
        val baos = ByteArrayOutputStream()
        ImageIO.write(img, format, baos)
        baos.toByteArray()
    } catch (_: Throwable) {
        null
    }
}

/**
 * Deserializes an encoded byte array into an Android [Bitmap].
 */
fun byteArrayToBitmap(bytes: ByteArray): Bitmap? {
    if (bytes.isEmpty()) return null
    return try {
        val bais = ByteArrayInputStream(bytes)
        val img = ImageIO.read(bais) ?: return null
        Bitmap(img)
    } catch (_: Throwable) {
        null
    }
}

/**
 * Deserializes an encoded byte array into a [UiImage.Bitmap].
 */
fun byteArrayToUiImage(bytes: ByteArray): UiImage.Bitmap? {
    val bitmap = byteArrayToBitmap(bytes) ?: return null
    return UiImage.Bitmap(bitmap)
}
