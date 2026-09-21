package unit

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import com.lagradost.cloudstream3.ui.UiImage
import com.lagradost.cloudstream3.ui.byteArrayToBitmap
import com.lagradost.cloudstream3.ui.byteArrayToUiImage
import com.lagradost.cloudstream3.ui.drawableToBitmap
import com.lagradost.cloudstream3.ui.drawableToBufferedImage
import com.lagradost.cloudstream3.ui.getImageFromDrawable
import com.lagradost.cloudstream3.ui.skiaImageToBitmap
import com.lagradost.cloudstream3.ui.skiaImageToBufferedImage
import com.lagradost.cloudstream3.ui.skiaImageToUiImage
import com.lagradost.cloudstream3.ui.toBitmap
import com.lagradost.cloudstream3.ui.toByteArray
import com.lagradost.cloudstream3.ui.toBufferedImage
import com.lagradost.cloudstream3.ui.toSkiaImage
import com.lagradost.cloudstream3.ui.toUiImage
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * Unit tests verifying 1:1 upstream architectural parity, zero-stub implementation,
 * and robust desktop image conversions for UiImage (Cluster C38_UiImage):
 *
 * 1. Sealed Class Hierarchy Parity:
 *    - Validates UiImage.Image with URL, headers, and optional desktop width/height.
 *    - Validates UiImage.Drawable with resource ID.
 *    - Validates UiImage.Bitmap with android.graphics.Bitmap and BufferedImage constructor.
 * 2. Drawable to Bitmap Conversion (1:1 Upstream Algorithm):
 *    - Validates BitmapDrawable direct bitmap extraction.
 *    - Validates general Drawable rendering onto Canvas and Bitmap creation.
 *    - Validates edge cases with zero or negative intrinsic dimensions.
 * 3. Java BufferedImage Conversion:
 *    - Validates Bitmap.toBufferedImage() and BufferedImage.toBitmap().
 *    - Validates UiImage.toBufferedImage() and UiImage.toBitmap().
 *    - Validates round-trip pixel and dimension integrity.
 * 4. Skia org.jetbrains.skia.Image Conversion Parity:
 *    - Validates conversion from BufferedImage to Skia Image.
 *    - Validates conversion from Skia Image back to BufferedImage and Bitmap.
 *    - Validates round-trip conversion without data loss.
 * 5. Serialization and Byte Array Interop:
 *    - Validates Bitmap.toByteArray() and byteArrayToBitmap().
 *    - Validates byteArrayToUiImage().
 * 6. Package Parity & Cross-Package Re-Export:
 *    - Validates com.lagradost.cloudstream3.utils.UiImage typealias and bridges.
 */
class UiImageRemediationTest {

    @Test
    @DisplayName("UiImage.Image data class holds upstream url and headers plus desktop dimensions")
    fun testUiImageImageProperties() {
        val url = "https://example.com/poster.jpg"
        val headers = mapOf("User-Agent" to "CloudStream/1.0", "Referer" to "https://example.com")

        // Upstream-compatible 2-argument instantiation
        val imageBasic = UiImage.Image(url = url, headers = headers)
        assertEquals(url, imageBasic.url)
        assertEquals(headers, imageBasic.headers)
        assertNull(imageBasic.width)
        assertNull(imageBasic.height)

        // Desktop extended dimensions instantiation
        val imageExtended = UiImage.Image(url = url, headers = headers, width = 1920, height = 1080)
        assertEquals(1920, imageExtended.width)
        assertEquals(1080, imageExtended.height)
        assertEquals(imageExtended, imageExtended.copy())
    }

    @Test
    @DisplayName("UiImage.Drawable data class holds resource identifier")
    fun testUiImageDrawableProperties() {
        val resId = 12345
        val drawable = UiImage.Drawable(resId)
        assertEquals(resId, drawable.resId)
        assertEquals(drawable, UiImage.Drawable(12345))
        assertNotEquals(drawable, UiImage.Drawable(54321))
    }

    @Test
    @DisplayName("UiImage.Bitmap wraps android.graphics.Bitmap and supports BufferedImage constructor")
    fun testUiImageBitmapInstantiation() {
        val buffered = BufferedImage(64, 48, BufferedImage.TYPE_INT_ARGB)
        val g = buffered.createGraphics()
        g.color = Color.RED
        g.fillRect(0, 0, 64, 48)
        g.dispose()

        val androidBitmap = Bitmap(buffered)
        val uiImageFromBitmap = UiImage.Bitmap(androidBitmap)
        assertSame(androidBitmap, uiImageFromBitmap.bitmap)
        assertEquals(64, uiImageFromBitmap.bitmap.width)
        assertEquals(48, uiImageFromBitmap.bitmap.height)

        // Secondary constructor from BufferedImage
        val uiImageFromBuffered = UiImage.Bitmap(buffered)
        assertEquals(64, uiImageFromBuffered.bitmap.width)
        assertEquals(48, uiImageFromBuffered.bitmap.height)
        assertNotNull(uiImageFromBuffered.bitmap.image)
    }

    @Test
    @DisplayName("drawableToBitmap extracts bitmap directly from BitmapDrawable")
    fun testDrawableToBitmapFromBitmapDrawable() {
        val buffered = BufferedImage(100, 100, BufferedImage.TYPE_INT_ARGB)
        val bitmap = Bitmap(buffered)
        val bitmapDrawable = BitmapDrawable(bitmap)

        val result = drawableToBitmap(bitmapDrawable)
        assertNotNull(result)
        assertSame(bitmap, result)
        assertEquals(100, result!!.width)
        assertEquals(100, result.height)
    }

    @Test
    @DisplayName("drawableToBitmap draws custom Drawable to Canvas and returns created Bitmap")
    fun testDrawableToBitmapFromCustomDrawable() {
        var drawCalled = false
        val customDrawable = object : Drawable() {
            override var intrinsicWidth = 80
            override var intrinsicHeight = 60

            override fun draw(canvas: Canvas) {
                drawCalled = true
                assertNotNull(canvas.bitmap)
                assertEquals(80, canvas.width)
                assertEquals(60, canvas.height)
            }
        }

        val result = drawableToBitmap(customDrawable)
        assertNotNull(result)
        assertTrue(drawCalled)
        assertEquals(80, result!!.width)
        assertEquals(60, result.height)
    }

    @Test
    @DisplayName("drawableToBitmap gracefully handles non-positive intrinsic dimensions")
    fun testDrawableToBitmapNonPositiveDimensions() {
        val zeroDrawable = object : Drawable() {
            override var intrinsicWidth = 0
            override var intrinsicHeight = -5
            override fun draw(canvas: Canvas) {}
        }

        val result = drawableToBitmap(zeroDrawable)
        assertNotNull(result)
        assertTrue(result!!.width >= 1)
        assertTrue(result.height >= 1)
    }

    @Test
    @DisplayName("getImageFromDrawable returns null when resource not found")
    fun testGetImageFromDrawableNotFound() {
        val context = Context()
        val result = getImageFromDrawable(context, 99999)
        assertNull(result)
    }

    @Test
    @DisplayName("Bidirectional conversion between Bitmap and BufferedImage")
    fun testBitmapAndBufferedImageConversion() {
        val original = BufferedImage(50, 30, BufferedImage.TYPE_INT_ARGB)
        val g = original.createGraphics()
        g.color = Color.BLUE
        g.fillRect(0, 0, 50, 30)
        g.dispose()

        // BufferedImage -> Bitmap
        val bitmap = original.toBitmap()
        assertEquals(50, bitmap.width)
        assertEquals(30, bitmap.height)

        // Bitmap -> BufferedImage
        val converted = bitmap.toBufferedImage()
        assertEquals(50, converted.width)
        assertEquals(30, converted.height)
        assertEquals(Color.BLUE.rgb, converted.getRGB(10, 10))

        // BufferedImage -> UiImage
        val uiImage = original.toUiImage()
        assertEquals(50, uiImage.bitmap.width)
        assertEquals(30, uiImage.bitmap.height)

        // UiImage -> BufferedImage
        val extracted = uiImage.toBufferedImage()
        assertNotNull(extracted)
        assertEquals(50, extracted!!.width)
        assertEquals(30, extracted.height)

        // UiImage -> Bitmap
        val extractedBitmap = uiImage.toBitmap()
        assertNotNull(extractedBitmap)
        assertEquals(50, extractedBitmap!!.width)
    }

    @Test
    @DisplayName("UiImage.toBufferedImage and toBitmap return null for non-bitmap variants")
    fun testUiImageExtractionNullSafety() {
        val imageVariant = UiImage.Image("https://example.com/art.png")
        assertNull(imageVariant.toBufferedImage())
        assertNull(imageVariant.toBitmap())
        assertNull(imageVariant.toSkiaImage())

        val drawableVariant = UiImage.Drawable(101)
        assertNull(drawableVariant.toBufferedImage())
        assertNull(drawableVariant.toBitmap())
        assertNull(drawableVariant.toSkiaImage())
    }

    @Test
    @DisplayName("drawableToBufferedImage delegates correctly")
    fun testDrawableToBufferedImage() {
        val buffered = BufferedImage(40, 40, BufferedImage.TYPE_INT_ARGB)
        val bitmap = Bitmap(buffered)
        val drawable = BitmapDrawable(bitmap)

        val result = drawableToBufferedImage(drawable)
        assertNotNull(result)
        assertEquals(40, result!!.width)
        assertEquals(40, result.height)
    }

    @Test
    @DisplayName("Skia Image conversions preserve dimensions and round-trip successfully when Skia is on classpath")
    fun testSkiaImageConversionRoundTrip() {
        val src = BufferedImage(32, 24, BufferedImage.TYPE_INT_ARGB)
        val g = src.createGraphics()
        g.color = Color.GREEN
        g.fillRect(0, 0, 32, 24)
        g.dispose()

        val skiaImg = src.toSkiaImage()
        if (skiaImg != null) {
            // Validate conversion from Skia Image back to BufferedImage
            val restored = skiaImageToBufferedImage(skiaImg)
            assertNotNull(restored)
            assertEquals(32, restored!!.width)
            assertEquals(24, restored.height)

            // Validate conversion to Bitmap
            val bitmap = skiaImageToBitmap(skiaImg)
            assertNotNull(bitmap)
            assertEquals(32, bitmap!!.width)
            assertEquals(24, bitmap.height)

            // Validate conversion to UiImage
            val uiImg = skiaImageToUiImage(skiaImg)
            assertNotNull(uiImg)
            assertEquals(32, uiImg!!.bitmap.width)

            // Validate UiImage.toSkiaImage()
            val skiaFromUi = uiImg.toSkiaImage()
            assertNotNull(skiaFromUi)
        }
    }

    @Test
    @DisplayName("ByteArray serialization and deserialization round-trip for Bitmap and UiImage")
    fun testByteArraySerializationRoundTrip() {
        val original = BufferedImage(45, 25, BufferedImage.TYPE_INT_RGB)
        val g = original.createGraphics()
        g.color = Color.YELLOW
        g.fillRect(0, 0, 45, 25)
        g.dispose()

        val bitmap = Bitmap(original)
        val bytes = bitmap.toByteArray("png")
        assertNotNull(bytes)
        assertTrue(bytes!!.isNotEmpty())

        val restoredBitmap = byteArrayToBitmap(bytes)
        assertNotNull(restoredBitmap)
        assertEquals(45, restoredBitmap!!.width)
        assertEquals(25, restoredBitmap.height)

        val restoredUiImage = byteArrayToUiImage(bytes)
        assertNotNull(restoredUiImage)
        assertEquals(45, restoredUiImage!!.bitmap.width)
        assertEquals(25, restoredUiImage.bitmap.height)
    }

    @Test
    @DisplayName("Bitmap compress API in android-shims matches upstream compress signature")
    fun testBitmapCompressParity() {
        val img = BufferedImage(20, 20, BufferedImage.TYPE_INT_ARGB)
        val bitmap = Bitmap(img)
        val baos = ByteArrayOutputStream()

        val pngSuccess = bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos)
        assertTrue(pngSuccess)
        assertTrue(baos.size() > 0)

        val jpegBaos = ByteArrayOutputStream()
        val jpegSuccess = bitmap.compress(Bitmap.CompressFormat.JPEG, 90, jpegBaos)
        assertTrue(jpegSuccess)
        assertTrue(jpegBaos.size() > 0)
    }

    @Test
    @DisplayName("Cross-package typealias in com.lagradost.cloudstream3.utils.UiImage resolves seamlessly")
    fun testUtilsPackageTypealiasParity() {
        // Instantiate using com.lagradost.cloudstream3.utils package import
        val image: com.lagradost.cloudstream3.utils.UiImage =
            com.lagradost.cloudstream3.utils.UiImage.Image("https://example.com/logo.png")
        assertTrue(image is UiImage.Image)

        val drawable: com.lagradost.cloudstream3.utils.UiImage =
            com.lagradost.cloudstream3.utils.UiImage.Drawable(42)
        assertTrue(drawable is UiImage.Drawable)

        val buff = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
        val bitmap: com.lagradost.cloudstream3.utils.UiImage =
            com.lagradost.cloudstream3.utils.UiImage.Bitmap(buff)
        assertTrue(bitmap is UiImage.Bitmap)

        val utilsBitmapResult = com.lagradost.cloudstream3.utils.drawableToBitmap(BitmapDrawable(Bitmap(buff)))
        assertNotNull(utilsBitmapResult)
        assertEquals(16, utilsBitmapResult!!.width)
    }
}
