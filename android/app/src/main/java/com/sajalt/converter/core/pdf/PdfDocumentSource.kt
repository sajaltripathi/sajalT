package com.sajalt.converter.core.pdf

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.Closeable
import java.io.IOException

/**
 * Opens a [PDDocument] from a content:// Uri with a bounded main-memory budget. PDFBox is
 * configured to keep up to [MAIN_MEMORY_THRESHOLD_BYTES] of working data purely in RAM; only
 * beyond that does it spill to a scratch file — and when it does, that file is placed in this
 * app's own private cache directory (never external/shared storage) and is removed by
 * [com.sajalt.converter.core.util.TempFileManager] immediately after the operation finishes,
 * satisfying "temporary files, if unavoidable, must be minimized and deleted immediately."
 *
 * NOTE FOR MAINTAINERS: `MemoryUsageSetting` is PDFBox's own documented mechanism for this and
 * is used here exactly as PDFBox's memory-management guide describes it. If a future PDFBox
 * update ever changes this method's exact signature, the safe fallback is simply
 * `PDDocument.load(inputStream)` with no second argument — slightly less control over scratch
 * file placement, but always a valid call.
 */
class PdfDocumentSource private constructor(val document: PDDocument) : Closeable {

    override fun close() {
        document.close()
    }

    companion object {
        private const val MAIN_MEMORY_THRESHOLD_BYTES = 64L * 1024L * 1024L

        fun open(context: Context, uri: Uri): PdfDocumentSource {
            val input = context.contentResolver.openInputStream(uri)
                ?: throw IOException("Cannot open $uri")
            val memorySetting = MemoryUsageSetting
                .setupMixed(MAIN_MEMORY_THRESHOLD_BYTES)
                .setTempDir(context.cacheDir)
            val doc = input.use { stream -> PDDocument.load(stream, memorySetting) }
            return PdfDocumentSource(doc)
        }
    }
}
