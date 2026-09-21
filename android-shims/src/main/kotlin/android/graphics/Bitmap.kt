package android.graphics

import java.awt.image.BufferedImage
import java.io.File
import java.io.OutputStream
import javax.imageio.ImageIO

open class Bitmap(
    open val image: BufferedImage? = null,
    open val file: File? = null,
    open val filePath: String? = file?.absolutePath
) {
    open val width: Int get() = image?.width ?: 0
    open val height: Int get() = image?.height ?: 0

    enum class Config {
        ALPHA_8,
        RGB_565,
        ARGB_4444,
        ARGB_8888,
        RGBA_F16,
        HARDWARE
    }

    enum class CompressFormat {
        JPEG,
        PNG,
        WEBP
    }

    open fun compress(format: CompressFormat, quality: Int, stream: OutputStream): Boolean {
        val img = image ?: return false
        val formatName = when (format) {
            CompressFormat.JPEG -> "jpg"
            CompressFormat.PNG -> "png"
            CompressFormat.WEBP -> "webp"
        }
        return try {
            ImageIO.write(img, formatName, stream)
        } catch (_: Throwable) {
            false
        }
    }

    companion object {
        fun createBitmap(width: Int, height: Int, config: Any? = null): Bitmap {
            val img = BufferedImage(width.coerceAtLeast(1), height.coerceAtLeast(1), BufferedImage.TYPE_INT_ARGB)
            return Bitmap(img)
        }
    }
}

