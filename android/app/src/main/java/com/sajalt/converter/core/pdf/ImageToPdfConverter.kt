package com.sajalt.converter.core.pdf

import android.content.Context
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.net.Uri
import com.sajalt.converter.core.image.BitmapUtils
import java.io.OutputStream

/**
 * Converts a user-ordered list of images into a single PDF, one image per page, using Android's
 * built-in [PdfDocument] (per the spec: "Use Android PdfDocument where feasible" for this
 * feature specifically). Each page's physical size is derived directly from that image's own
 * (EXIF-corrected) pixel dimensions, so the image always fills its page exactly with no
 * letterboxing and no aspect-ratio distortion. Pixel data itself is never resampled — see
 * [BitmapUtils.drawFullQuality] for exactly how that is guaranteed even for very large images.
 */
object ImageToPdfConverter {

    /** 72/96 = a 96-dpi assumption mapping source pixels to PDF points (a physical page size).
     *  This only affects the page's stated physical dimensions in inches/cm — it has no effect
     *  on pixel data, which is always embedded at full source resolution regardless. */
    private const val POINTS_PER_PIXEL = 0.75f

    /** Practical PDF page-size ceiling (~200 inches), per common PDF viewer/spec guidance. Only
     *  ever shrinks the PHYSICAL page size for an extreme-resolution image; pixel data is still
     *  embedded in full — see the comment on [POINTS_PER_PIXEL] above. */
    private const val MAX_PAGE_DIMENSION_POINTS = 14_400f

    fun convert(context: Context, imageUris: List<Uri>, outputStream: OutputStream) {
        require(imageUris.isNotEmpty()) { "At least one image is required" }

        val document = PdfDocument()
        try {
            imageUris.forEachIndexed { index, uri ->
                val dims = BitmapUtils.readDimensions(context, uri)
                val (pageWidthPt, pageHeightPt) = pageSizePoints(dims.displayedWidth, dims.displayedHeight)

                val pageInfo = PdfDocument.PageInfo.Builder(
                    Math.round(pageWidthPt),
                    Math.round(pageHeightPt),
                    index + 1
                ).create()

                val page = document.startPage(pageInfo)
                val destRect = RectF(0f, 0f, pageWidthPt, pageHeightPt)
                BitmapUtils.drawFullQuality(context, uri, page.canvas, destRect)
                document.finishPage(page) // must happen before any bitmap used above is recycled — see BitmapUtils class doc
            }
            document.writeTo(outputStream)
        } finally {
            document.close()
        }
    }

    private fun pageSizePoints(displayedWidth: Int, displayedHeight: Int): Pair<Float, Float> {
        var w = displayedWidth * POINTS_PER_PIXEL
        var h = displayedHeight * POINTS_PER_PIXEL
        val maxDim = maxOf(w, h)
        if (maxDim > MAX_PAGE_DIMENSION_POINTS) {
            val factor = MAX_PAGE_DIMENSION_POINTS / maxDim
            w *= factor
            h *= factor
        }
        return w.coerceAtLeast(1f) to h.coerceAtLeast(1f)
    }
}
