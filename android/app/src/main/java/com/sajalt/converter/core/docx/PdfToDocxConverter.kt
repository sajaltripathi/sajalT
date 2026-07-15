package com.sajalt.converter.core.docx

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.sajalt.converter.core.pdf.PdfDocumentSource
import com.sajalt.converter.core.pdf.PdfTextExtractor
import com.tom_roush.pdfbox.rendering.PDFRenderer
import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * Converts a PDF to an approximated, editable DOCX. PDF is fundamentally a page-layout format
 * with no semantic structure (no real concept of "heading" or "table" — only text positioned at
 * coordinates), so everything here is a documented heuristic, not a lossless recovery:
 *
 *  - **Headings**: a line whose font size is well above that page-set's own median is treated as
 *    a heading (larger the gap, higher the heading level). This is the same visual cue a human
 *    skimming the PDF would use, and works well on typically-formatted documents; it will both
 *    miss headings that are only distinguished by boldness (not size) and can false-positive on
 *    large body text used for emphasis.
 *  - **Tables**: a run of 2+ consecutive lines that each split into the same number of
 *    "columns" (on runs of 2+ spaces or a tab — PDFTextStripper generally preserves inter-column
 *    gaps as literal whitespace) is treated as a table. This catches simple, consistently
 *    space-aligned tables; it will not catch borderless tables with irregular spacing, and does
 *    not attempt to detect merged cells (a PDF table's merges are a visual/geometric property
 *    that is materially harder to recover than a DOCX table's explicit `gridSpan`/`vMerge` tags).
 *  - **Scanned pages**: a page with very little extractable text is assumed to be a scanned
 *    image and is embedded as a full-page picture rather than guessing at text that was never
 *    really there — this is a direct implementation of the spec's own required "preserve scanned
 *    pages as images" behavior for exactly the case where OCR was not requested.
 *
 * See README "Known limitations" for the complete, honest accounting of what this does not
 * attempt to recover (multi-column layouts, headers/footers, footnotes, embedded objects, and
 * tracked changes/comments, none of which exist as recoverable metadata in a plain PDF at all).
 */
object PdfToDocxConverter {

    private const val SCANNED_PAGE_MIN_CHARS = 20
    private const val SCANNED_PAGE_RENDER_DPI = 150f
    private const val SCANNED_PAGE_JPEG_QUALITY = 85

    fun convert(context: Context, uri: Uri, outputStream: OutputStream) {
        PdfDocumentSource.open(context, uri).use { source ->
            val document = source.document
            val pageCount = document.numberOfPages
            val pages = PdfTextExtractor.extract(document)
            val renderer = PDFRenderer(document)
            val medianFontSize = estimateMedianFontSize(pages)

            val blocks = mutableListOf<DocxBlock>()
            for (pageIndex in 0 until pageCount) {
                val page = pages.getOrNull(pageIndex)
                val totalChars = page?.lines?.sumOf { it.text.length } ?: 0

                if (page == null || totalChars < SCANNED_PAGE_MIN_CHARS) {
                    val bitmap = renderer.renderImageWithDPI(pageIndex, SCANNED_PAGE_RENDER_DPI)
                    blocks.add(DocxBlock.Image(bitmapToDocxImage(bitmap)))
                } else {
                    blocks.addAll(buildBlocksForPage(page, medianFontSize))
                }
                if (pageIndex < pageCount - 1) {
                    blocks.add(DocxBlock.Paragraph(ParagraphStyle.NORMAL, emptyList())) // small gap between pages
                }
            }
            DocxWriter.write(DocxDocument(blocks), outputStream)
        }
    }

    private fun estimateMedianFontSize(pages: List<PdfTextExtractor.PageResult>): Float {
        val sizes = pages.flatMap { it.lines }.map { it.fontSizePt }.filter { it > 0f }.sorted()
        return if (sizes.isEmpty()) 11f else sizes[sizes.size / 2]
    }

    private fun buildBlocksForPage(page: PdfTextExtractor.PageResult, medianFontSize: Float): List<DocxBlock> {
        val result = mutableListOf<DocxBlock>()
        val tableRuns = detectTableRuns(page.lines)
        var i = 0
        while (i < page.lines.size) {
            val run = tableRuns[i]
            if (run != null) {
                result.add(buildTableBlock(page.lines.subList(i, run.endExclusive)))
                i = run.endExclusive
            } else {
                result.add(lineToParagraph(page.lines[i], medianFontSize))
                i++
            }
        }
        return result
    }

    private fun lineToParagraph(line: PdfTextExtractor.TextLine, medianFontSize: Float): DocxBlock.Paragraph {
        val style = when {
            medianFontSize <= 0f -> ParagraphStyle.NORMAL
            line.fontSizePt >= medianFontSize * 1.8f -> ParagraphStyle.TITLE
            line.fontSizePt >= medianFontSize * 1.4f -> ParagraphStyle.HEADING1
            line.fontSizePt >= medianFontSize * 1.2f -> ParagraphStyle.HEADING2
            else -> ParagraphStyle.NORMAL
        }
        return DocxBlock.Paragraph(style, listOf(DocxRun(line.text)))
    }

    private class TableRun(val endExclusive: Int)

    private fun detectTableRuns(lines: List<PdfTextExtractor.TextLine>): Array<TableRun?> {
        val result = arrayOfNulls<TableRun>(lines.size)
        var i = 0
        while (i < lines.size) {
            val cellCount = splitIntoCells(lines[i].text).size
            if (cellCount >= 2) {
                var j = i + 1
                while (j < lines.size && splitIntoCells(lines[j].text).size == cellCount) j++
                if (j - i >= 2) { // require 2+ consistent rows before calling it a table, not a lone line
                    val run = TableRun(j)
                    for (k in i until j) result[k] = run
                    i = j
                    continue
                }
            }
            i++
        }
        return result
    }

    private fun buildTableBlock(lines: List<PdfTextExtractor.TextLine>): DocxBlock.Table {
        val rows = lines.map { line ->
            val cells = splitIntoCells(line.text).map { cellText ->
                TableCell(listOf(DocxBlock.Paragraph(ParagraphStyle.NORMAL, listOf(DocxRun(cellText)))))
            }
            TableRow(cells)
        }
        return DocxBlock.Table(rows)
    }

    private fun splitIntoCells(text: String): List<String> =
        text.split(Regex(" {2,}|\t")).map { it.trim() }.filter { it.isNotEmpty() }

    private fun bitmapToDocxImage(bitmap: Bitmap): DocxImage {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, SCANNED_PAGE_JPEG_QUALITY, out)
        return DocxImage(out.toByteArray())
    }
}
