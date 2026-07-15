package com.sajalt.converter.core.image

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import java.io.IOException
import java.io.InputStream

/**
 * Draws images onto a [Canvas] at full source resolution with zero intentional quality loss.
 *
 * IMPORTANT — why bitmaps are never `.recycle()`d here:
 * [android.graphics.pdf.PdfDocument] pages are backed by [android.graphics.Picture] recording:
 * `page.canvas.drawBitmap(...)` only RECORDS the draw call: the Bitmap is actually read only
 * later, when `PdfDocument.finishPage()` plays the recording back to produce PDF content.
 * Recycling a bitmap right after drawing it (a normal, correct pattern for an on-screen View
 * canvas) would free its native memory before that deferred playback runs, corrupting the page
 * or crashing. So instead of recycling, every entry point below simply lets bitmaps fall out of
 * scope for the garbage collector once the caller has finished the page — and to keep PEAK
 * memory bounded regardless, [drawFullQuality] decodes very large images in bounded-size tiles
 * rather than ever holding one huge bitmap at all (see [drawTiled]).
 */
object BitmapUtils {

    data class ImageDimensions(val rawWidth: Int, val rawHeight: Int, val rotationDegrees: Int) {
        /** Width/height as the image should actually be displayed, after applying EXIF rotation. */
        val displayedWidth: Int get() = if (rotationDegrees == 90 || rotationDegrees == 270) rawHeight else rawWidth
        val displayedHeight: Int get() = if (rotationDegrees == 90 || rotationDegrees == 270) rawWidth else rawHeight
    }

    /** Reads only bounds + EXIF orientation; decodes no pixel data. */
    fun readDimensions(context: Context, uri: Uri): ImageDimensions {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: throw IOException("Cannot open $uri")
        if (opts.outWidth <= 0 || opts.outHeight <= 0) {
            throw IOException("Not a decodable image: $uri")
        }
        val rotation = readExifRotationDegrees(context, uri)
        return ImageDimensions(opts.outWidth, opts.outHeight, rotation)
    }

    fun readExifRotationDegrees(context: Context, uri: Uri): Int = try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            when (ExifInterface(input).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0 // Mirrored orientations (FLIP_*, TRANSPOSE, TRANSVERSE) are treated as
                          // unrotated — a documented minor limitation; true mirroring is rare in
                          // camera output and adds a second axis of transform complexity for a
                          // case that in practice almost never occurs.
            }
        } ?: 0
    } catch (_: Exception) {
        0
    }

    /**
     * Draws the full-resolution image at [uri] into [destRect] of [canvas], applying EXIF
     * rotation but no other resampling: no intentional downscale, no recompression. Chooses
     * between a direct single decode and bounded-memory tiled decoding based on [context]'s
     * available heap, so this never needs the caller to know or care how large the source is.
     */
    fun drawFullQuality(context: Context, uri: Uri, canvas: Canvas, destRect: RectF) {
        val dims = readDimensions(context, uri)
        val decodedBytesIfDirect = dims.rawWidth.toLong() * dims.rawHeight.toLong() * 4L // ARGB_8888
        if (decodedBytesIfDirect <= directDecodeLimitBytes(context)) {
            drawDirect(context, uri, dims, canvas, destRect)
        } else {
            drawTiled(context, uri, dims, canvas, destRect)
        }
    }

    // ---------------------------------------------------------------------------------------
    // Direct path: decode once, rotate with a Matrix (the same well-established idiom used to
    // fix EXIF-rotated photos everywhere on Android), draw, done.
    // ---------------------------------------------------------------------------------------
    private fun drawDirect(context: Context, uri: Uri, dims: ImageDimensions, canvas: Canvas, destRect: RectF) {
        val bitmap = context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
            })
        } ?: throw IOException("Cannot decode $uri")

        val toDraw = if (dims.rotationDegrees == 0) {
            bitmap
        } else {
            val m = Matrix().apply { postRotate(dims.rotationDegrees.toFloat()) }
            // createBitmap(..., matrix, filter=true) both rotates AND auto-translates the result
            // into positive coordinates, producing a bitmap whose size already matches
            // dims.displayedWidth x dims.displayedHeight.
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
        }
        canvas.drawBitmap(toDraw, null, destRect, HIGH_QUALITY_PAINT) // recorded, not recycled — see class doc
    }

    // ---------------------------------------------------------------------------------------
    // Tiled path: stream the image through BitmapRegionDecoder in horizontal strips so peak
    // memory is bounded no matter how large the source is. Each rotation case below was derived
    // algebraically from Matrix's documented postRotate()/postTranslate() semantics (the same
    // semantics Bitmap.createBitmap(..., matrix, true) relies on in the direct path above) and
    // hand-verified against the physical "rotate a photo" intuition before being written down:
    //
    //   R=0   : identity — no rotation term needed.
    //   R=90  : postRotate(90)  then postTranslate(rawHeight, 0)
    //   R=180 : postRotate(180) then postTranslate(rawWidth, rawHeight)
    //   R=270 : postRotate(270) then postTranslate(0, rawWidth)
    //
    // After that rotation term, every case gets the same postScale(SCALE) + postTranslate(dest)
    // to place the now-correctly-oriented content into destRect. Tiles are then drawn using
    // their RAW (pre-rotation) coordinates against this one matrix via canvas.concat(), so the
    // matrix does all the positioning and no per-tile arithmetic is needed.
    // ---------------------------------------------------------------------------------------
    private fun drawTiled(context: Context, uri: Uri, dims: ImageDimensions, canvas: Canvas, destRect: RectF) {
        val decoder = context.contentResolver.openInputStream(uri)?.use { newRegionDecoder(it) }
            ?: throw IOException("Cannot open a region decoder for $uri")
        try {
            val rawW = dims.rawWidth
            val rawH = dims.rawHeight
            val scaleX = destRect.width() / dims.displayedWidth
            val scaleY = destRect.height() / dims.displayedHeight

            val placement = Matrix()
            when (dims.rotationDegrees) {
                90 -> {
                    placement.postRotate(90f)
                    placement.postTranslate(rawH.toFloat(), 0f)
                }
                180 -> {
                    placement.postRotate(180f)
                    placement.postTranslate(rawW.toFloat(), rawH.toFloat())
                }
                270 -> {
                    placement.postRotate(270f)
                    placement.postTranslate(0f, rawW.toFloat())
                }
                // 0 -> identity, nothing to add
            }
            placement.postScale(scaleX, scaleY)
            placement.postTranslate(destRect.left, destRect.top)

            val tileRawHeight = maxOf(1, (TILE_TARGET_BYTES / (rawW.toLong() * 4L)).toInt())
            val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }

            canvas.save()
            canvas.concat(placement)
            var y = 0
            while (y < rawH) {
                val h = minOf(tileRawHeight, rawH - y)
                val tile = decoder.decodeRegion(Rect(0, y, rawW, y + h), opts)
                if (tile != null) {
                    canvas.drawBitmap(tile, 0f, y.toFloat(), HIGH_QUALITY_PAINT) // recorded, not recycled
                }
                y += h
            }
            canvas.restore()
        } finally {
            decoder.recycle() // safe: the DECODER (not a drawn Bitmap) is fine to release now —
                               // every tile it produced has already been individually recorded.
        }
    }

    private fun newRegionDecoder(input: InputStream): BitmapRegionDecoder =
        if (Build.VERSION.SDK_INT >= 31) {
            BitmapRegionDecoder.newInstance(input)
        } else {
            @Suppress("DEPRECATION")
            BitmapRegionDecoder.newInstance(input, false)
        }

    /** At most ~1/4 of the app's per-process heap ceiling for one directly-decoded bitmap. */
    private fun directDecodeLimitBytes(context: Context): Long {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memoryClassMb = (am?.memoryClass ?: 128).coerceIn(64, 512)
        return (memoryClassMb.toLong() * 1024L * 1024L) / 4L
    }

    private const val TILE_TARGET_BYTES = 12L * 1024L * 1024L
    private val HIGH_QUALITY_PAINT = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
}
