package unit

import android.graphics.Bitmap
import com.lagradost.player.preview.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.image.BufferedImage

class PreviewGeneratorTest {

    @Test
    fun testImageParamsCalculation() {
        val defaultParams = ImageParams.DEFAULT
        assertEquals(200, defaultParams.width)
        assertEquals(320, defaultParams.height)

        val params16by9 = ImageParams.new16by9(1920)
        assertEquals(480, params16by9.width) // 1920 / 4
        assertEquals(270, params16by9.height) // (1920 * 9) / 64

        val smallParams = ImageParams.new16by9(50)
        assertEquals(defaultParams, smallParams)
    }

    @Test
    fun testNoPreviewGeneratorContract() {
        val emptyGen = IPreviewGenerator.empty()
        assertFalse(emptyGen.hasPreview())
        assertNull(emptyGen.getPreviewImage(0.5f))
        assertEquals(0L, emptyGen.durationMs)
        assertEquals(0, emptyGen.loadedImages)
        emptyGen.release()
    }

    @Test
    fun testBitmapScaleExtension() {
        val srcImg = BufferedImage(100, 100, BufferedImage.TYPE_INT_ARGB)
        val g = srcImg.createGraphics()
        g.color = Color.RED
        g.fillRect(0, 0, 100, 100)
        g.dispose()

        val bitmap = Bitmap(srcImg)
        assertEquals(100, bitmap.width)
        assertEquals(100, bitmap.height)

        val scaled = bitmap.scale(50, 50)
        assertEquals(50, scaled.width)
        assertEquals(50, scaled.height)
        scaled.recycle()
    }

    @Test
    fun testWebVttPreviewGeneratorParsing() {
        val vttContent = """
            WEBVTT

            00:00:00.000 --> 00:00:10.000
            thumbnails.jpg#xywh=0,0,160,90

            00:00:10.000 --> 00:00:20.000
            thumbnails.jpg#xywh=160,0,160,90

            00:00:20.000 --> 00:00:30.000
            thumbnails.jpg#xywh=0,90,160,90
        """.trimIndent()

        val vttGen = WebVttPreviewGenerator(ImageParams(160, 90))
        vttGen.parseVttContent(vttContent, "https://example.com/media/")

        assertTrue(vttGen.hasPreview())
        assertEquals(30000L, vttGen.durationMs)
    }

    @Test
    fun testPreviewGeneratorSwitchingAndCaching() {
        val previewGen = PreviewGenerator()
        assertFalse(previewGen.hasPreview())
        assertNull(previewGen.getPreviewImage(0.25f))

        previewGen.params = ImageParams(320, 180)
        assertEquals(320, previewGen.params.width)
        assertEquals(180, previewGen.params.height)

        // Clear without cache
        previewGen.clear(keepCache = false)
        assertFalse(previewGen.hasPreview())

        // Release
        previewGen.release()
        assertFalse(previewGen.hasPreview())
    }
}
