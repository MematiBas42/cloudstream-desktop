// @PortSource(file = "upstream/app/src/main/java/com/lagradost/cloudstream3/utils/ImageUtil.kt", upstreamCommit = "caeec18")
package com.lagradost.cloudstream3.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import java.awt.image.BufferedImage

typealias UiImage = com.lagradost.cloudstream3.ui.UiImage

fun getImageFromDrawable(context: Context, drawableRes: Int): UiImage? =
    com.lagradost.cloudstream3.ui.getImageFromDrawable(context, drawableRes)

fun drawableToBitmap(drawable: Drawable): Bitmap? =
    com.lagradost.cloudstream3.ui.drawableToBitmap(drawable)

fun drawableToBufferedImage(drawable: Drawable): BufferedImage? =
    com.lagradost.cloudstream3.ui.drawableToBufferedImage(drawable)

fun UiImage.toBufferedImage(): BufferedImage? =
    (this as com.lagradost.cloudstream3.ui.UiImage).toBufferedImage()

fun UiImage.toBitmap(): Bitmap? =
    (this as com.lagradost.cloudstream3.ui.UiImage).toBitmap()

fun UiImage.toSkiaImage(): Any? =
    (this as com.lagradost.cloudstream3.ui.UiImage).toSkiaImage()
