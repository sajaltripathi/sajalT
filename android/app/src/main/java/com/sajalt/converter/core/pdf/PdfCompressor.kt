package com.sajalt.converter.core.pdf

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import java.io.ByteArrayOutputStream

data class PdfCompressionResult(
    val bytes: ByteArray,
    val achievedSizeBytes: Long,
    val targetSizeBytes: Long,
    val reachedTarget: Boolean,
    val imageCount: Int
)

/**
 * Compresses a PDF toward a target size by recompressing its embedded raster images (almost
 * always the dominant contributor to PDF file size) and, if that alone is not enough, also
 * reducing their pixel resolution. Text and vector content are left untouched — PDFBox's own
 * re-serialization of the document is the only other size reduction applied to them.
 *
 * A crucial correctness detail: every quality/resolution attempt below re-encodes from a cached
 * copy of each image's ORIGINAL decoded bitmap, never from a previously-recompressed version.
 * Re-encoding an already-lossy JPEG at a different quality on each search iteration would stack
 * generation loss and make the binary search's results meaningless.
 */
object PdfCompressor {

    private const val MIN_IMAGE_QUALITY = 0.05f
    private const val MAX_IMAGE_QUALITY = 0.92f
    private const val QUALITY_STEP_FLOOR = 0.03f
    private const val MAX_QUALITY_ITERATIONS = 7
    private const val MIN_IMAGE_SCALE = 0.15f
    private const val SCALE_STEP = 0.85f

    private class ImageSlot(val resources: PDResources, val name: COSName, val original: Bitmap)

    fun compress(context: Context, uri: Uri, targetBytes: Long): PdfCompressionResult {
        require(targetBytes > 0) { "targetBytes must be positive" }

        PdfDocumentSource.open(context, uri).use { source ->
            val document = source.document
            val slots = collectImageSlots(document)

            if (slots.isEmpty()) {
                // Nothing to recompress. PDFBox's own re-serialization can still reclaim some
                // space from inefficient source encoding; beyond that, a text/vector-only PDF
                // has little further room without rasterizing pages, which this app does not do
                // (that would silently turn selectable text into an image — a much bigger
                // fidelity trade-off than compressing photos, and out of scope here; see README).
                val bytes = saveToBytes(document)
                return PdfCompressionResult(bytes, bytes.size.toLong(), targetBytes, bytes.size.toLong() <= targetBytes, 0)
            }

            try {
                applyQuality(document, slots, MIN_IMAGE_QUALITY, scale = 1f)
                val atMinQualityFullRes = saveToBytes(document)

                if (atMinQualityFullRes.size.toLong() > targetBytes) {
                    // Quality reduction alone cannot reach the target; downscale resolution too.
                    val downscaled = downscaleAndRecompress(document, slots, targetBytes)
                    return PdfCompressionResult(
                        downscaled, downscaled.size.toLong(), targetBytes,
                        downscaled.size.toLong() <= targetBytes, slots.size
                    )
                }

                // Target is reachable at full resolution: binary-search for the best quality
                // that still fits, maximizing quality ("minimal perceptible quality loss").
                var best = atMinQualityFullRes
                var lo = MIN_IMAGE_QUALITY
                var hi = MAX_IMAGE_QUALITY
                var iterations = 0
                while (hi - lo > QUALITY_STEP_FLOOR && iterations < MAX_QUALITY_ITERATIONS) {
                    val mid = (lo + hi) / 2f
                    applyQuality(document, slots, mid, scale = 1f)
                    val data = saveToBytes(document)
                    if (data.size.toLong() <= targetBytes) {
                        best = data
                        lo = mid
                    } else {
                        hi = mid
                    }
                    iterations++
                }
                return PdfCompressionResult(best, best.size.toLong(), targetBytes, best.size.toLong() <= targetBytes, slots.size)
            } finally {
                slots.forEach { if (!it.original.isRecycled) it.original.recycle() }
            }
        }
    }

    private fun downscaleAndRecompress(document: PDDocument, slots: List<ImageSlot>, targetBytes: Long): ByteArray {
        var scale = SCALE_STEP
        var last: ByteArray? = null
        while (scale >= MIN_IMAGE_SCALE) {
            applyQuality(document, slots, MIN_IMAGE_QUALITY, scale)
            val data = saveToBytes(document)
            last = data
            if (data.size.toLong() <= targetBytes) return data
            scale *= SCALE_STEP
        }
        return last ?: saveToBytes(document)
    }

    private fun collectImageSlots(document: PDDocument): List<ImageSlot> {
        val slots = mutableListOf<ImageSlot>()
        for (page in document.pages) {
            val resources = page.resources ?: continue
            for (name in resources.xObjectNames.toList()) { // snapshot before any mutation
                val xObject = resources.getXObject(name)
                if (xObject is PDImageXObject) {
                    slots.add(ImageSlot(resources, name, xObject.image))
                }
            }
        }
        return slots
    }

    private fun applyQuality(document: PDDocument, slots: List<ImageSlot>, quality: Float, scale: Float) {
        for (slot in slots) {
            val toEncode = if (scale < 1f) {
                val w = maxOf(1, (slot.original.width * scale).toInt())
                val h = maxOf(1, (slot.original.height * scale).toInt())
                Bitmap.createScaledBitmap(slot.original, w, h, true)
            } else {
                slot.original
            }
            val newXObject = JPEGFactory.createFromImage(document, toEncode, quality)
            slot.resources.put(slot.name, newXObject)
            if (toEncode !== slot.original) toEncode.recycle() // safe: PDFBox's JPEGFactory encodes synchronously, unlike PdfDocument's deferred canvas
        }
    }

    private fun saveToBytes(document: PDDocument): ByteArray {
        val out = ByteArrayOutputStream()
        document.save(out)
        return out.toByteArray()
    }
}
