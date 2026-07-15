package com.sajalt.converter.core.pdf

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.tom_roush.pdfbox.rendering.PDFRenderer

object PdfPageRasterizer {

    fun pageCount(context: Context, uri: Uri): Int =
        PdfDocumentSource.open(context, uri).use { it.document.numberOfPages }

    /** Renders one 0-indexed page to a Bitmap at approximately [dpi]. */
    fun renderPage(context: Context, uri: Uri, pageIndex: Int, dpi: Float = 200f): Bitmap =
        PdfDocumentSource.open(context, uri).use { source ->
            PDFRenderer(source.document).renderImageWithDPI(pageIndex, dpi)
        }
}
