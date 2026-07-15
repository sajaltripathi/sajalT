package com.sajalt.converter.core.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.IOException

data class CompressionResult(
    val bytes: ByteArray,
    val achievedSizeBytes: Long,
    val targetSizeBytes: Long,
    val reachedTarget: Boolean,
    val hadTransparency: Boolean,
    val finalWidth: Int,
    val finalHeight: Int
)

/**
 * Compresses an arbitrary image toward a target byte size.
 *
 * Strategy, in order:
 *  1. Binary-search JPEG quality [MIN_QUALITY, MAX_QUALITY] at the ORIGINAL resolution, looking
 *     for the highest quality whose output still fits under the target — this is "minimal
 *     perceptible quality loss" for as long as quality reduction alone can reach the target.
 *  2. If even the lowest allowed quality still exceeds the target at the current resolution,
 *     progressively downscale (each step to 85% of the previous linear size, down to a 15%
 *     floor) and repeat step 1 at the new resolution. Very small targets on very large/detailed
 *     images genuinely cannot be reached by quality reduction alone — that is a real limit of
 *     JPEG, not a bug — so resolution reduction is the documented, expected fallback here (this
 *     is the compression feature; it is explicitly allowed, indeed required, to reduce quality
 *     to hit a target size — this is a different feature from Image-to-PDF, which must NOT).
 *  3. The best (smallest) result seen across all attempts is always returned, even if the exact
 *     target could not be reached — "as close as possible ... must not exceed target size where
 *     technically possible" is honored as a best-effort guarantee, not an absolute one.
 *
 * Output is always JPEG, since reliably hitting a small target with a lossless format is not
 * generally possible. Any alpha channel in the source is flattened onto a white background
 * first (JPEG has no alpha) rather than left to whatever undefined RGB values sat under
 * transparent pixels.
 */
object ImageCompressor {

    private const val MIN_QUALITY = 2
    private const val MAX_QUALITY = 97
    private const val MAX_QUALITY_SEARCH_ITERATIONS = 8
    private const val MIN_SCALE_FACTOR = 0.15
    private const val SCALE_STEP = 0.85

    fun compress(context: Context, uri: Uri, targetBytes: Long): CompressionResult {
        require(targetBytes > 0) { "targetBytes must be positive" }

        val dims = BitmapUtils.readDimensions(context, uri)
        val decoded = decodeRotated(context, uri, dims)
        val hadTransparency = decoded.hasAlpha()
        val flattened = flattenToWhiteIfNeeded(decoded)
        if (flattened !== decoded) decoded.recycle()

        var currentBitmap = flattened
        var scale = 1.0
        var bestBytes: ByteArray? = null
        var bestSize = Long.MAX_VALUE
        var bestWidth = currentBitmap.width
        var bestHeight = currentBitmap.height

        try {
            while (true) {
                val (data, _) = searchBestQualityUnderTarget(currentBitmap, targetBytes)
                if (data.size.toLong() < bestSize) {
                    bestBytes = data
                    bestSize = data.size.toLong()
                    bestWidth = currentBitmap.width
                    bestHeight = currentBitmap.height
                }
                val fitsTarget = data.size.toLong() <= targetBytes
                if (fitsTarget || scale <= MIN_SCALE_FACTOR) break

                scale *= SCALE_STEP
                val newW = maxOf(1, (currentBitmap.width * SCALE_STEP).toInt())
                val newH = maxOf(1, (currentBitmap.height * SCALE_STEP).toInt())
                val scaledBitmap = Bitmap.createScaledBitmap(currentBitmap, newW, newH, true)
                if (currentBitmap !== flattened) currentBitmap.recycle() // safe: plain CPU-side bitmap, no PdfDocument involved
                currentBitmap = scaledBitmap
            }
        } finally {
            if (!currentBitmap.isRecycled) currentBitmap.recycle()
            if (flattened !== currentBitmap && !flattened.isRecycled) flattened.recycle()
        }

        val finalBytes = bestBytes ?: throw IllegalStateException("Compression produced no output for $uri")
        return CompressionResult(
            bytes = finalBytes,
            achievedSizeBytes = finalBytes.size.toLong(),
            targetSizeBytes = targetBytes,
            reachedTarget = finalBytes.size.toLong() <= targetBytes,
            hadTransparency = hadTransparency,
            finalWidth = bestWidth,
            finalHeight = bestHeight
        )
    }

    /** Highest JPEG quality in range whose encoded size is <= targetBytes, or the smallest
     *  achievable (MIN_QUALITY) if even that does not fit. */
    private fun searchBestQualityUnderTarget(bitmap: Bitmap, targetBytes: Long): Pair<ByteArray, Int> {
        val atMinQuality = compressAt(bitmap, MIN_QUALITY)
        if (atMinQuality.size.toLong() > targetBytes) {
            // Quality reduction alone cannot reach the target at this resolution; this is the
            // smallest this resolution can produce, signalling the caller to downscale further.
            return atMinQuality to MIN_QUALITY
        }

        var lo = MIN_QUALITY
        var hi = MAX_QUALITY
        var best = atMinQuality to MIN_QUALITY
        var iterations = 0
        while (lo <= hi && iterations < MAX_QUALITY_SEARCH_ITERATIONS) {
            val mid = (lo + hi) / 2
            val data = compressAt(bitmap, mid)
            if (data.size.toLong() <= targetBytes) {
                best = data to mid
                lo = mid + 1 // this quality fits; try to do even better
            } else {
                hi = mid - 1 // too big; back off
            }
            iterations++
        }
        return best
    }

    private fun compressAt(bitmap: Bitmap, quality: Int): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return out.toByteArray()
    }

    private fun decodeRotated(context: Context, uri: Uri, dims: BitmapUtils.ImageDimensions): Bitmap {
        val raw = context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
            })
        } ?: throw IOException("Cannot decode $uri")
        if (dims.rotationDegrees == 0) return raw
        val m = Matrix().apply { postRotate(dims.rotationDegrees.toFloat()) }
        val rotated = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
        if (rotated !== raw) raw.recycle() // safe: immediate CPU-side transform, not a PdfDocument draw call
        return rotated
    }

    private fun flattenToWhiteIfNeeded(bitmap: Bitmap): Bitmap {
        if (!bitmap.hasAlpha()) return bitmap
        val flattened = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(flattened)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(bitmap, 0f, 0f, null)
        return flattened
    }
}
