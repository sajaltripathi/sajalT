package com.sajalt.converter.core.pdf

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition

/**
 * Captures per-line text together with its position and font size on each page — PDFBox's plain
 * `getText()` gives back only a flat string, which is not enough to approximate headings, basic
 * tables, or reading order the way [com.sajalt.converter.core.docx.PdfToDocxConverter] needs to.
 *
 * This extends [PDFTextStripper] using its standard, documented extension point
 * (`writeString(text, textPositions)`, called once per line PDFTextStripper has already grouped
 * characters into) rather than reimplementing text layout from raw content-stream operators,
 * which would be a much larger undertaking with a much higher bug surface.
 */
class PdfTextExtractor : PDFTextStripper() {

    data class TextLine(val text: String, val x: Float, val y: Float, val fontSizePt: Float)
    data class PageResult(
        val pageIndex: Int,
        val widthPt: Float,
        val heightPt: Float,
        val lines: MutableList<TextLine> = mutableListOf()
    )

    val pages = mutableListOf<PageResult>()
    private var pageIndex = -1

    init {
        setSortByPosition(true) // reading order by position, not raw content-stream order
    }

    override fun startPage(page: PDPage) {
        pageIndex++
        val box = page.mediaBox
        pages.add(PageResult(pageIndex, box.width, box.height))
        super.startPage(page)
    }

    override fun writeString(text: String, textPositions: MutableList<TextPosition>) {
        if (text.isBlank() || pages.isEmpty() || textPositions.isEmpty()) return
        val first = textPositions[0]
        val fontSize = textPositions.maxOf { it.fontSizeInPt }
        pages.last().lines.add(TextLine(text, first.xDirAdj, first.yDirAdj, fontSize))
    }

    companion object {
        /** Runs extraction over the whole document and returns per-page structured results.
         *  PDFTextStripper defaults to processing every page (startPage=1, endPage=MAX) unless
         *  told otherwise, which is exactly what's wanted here. */
        fun extract(document: PDDocument): List<PageResult> {
            val extractor = PdfTextExtractor()
            extractor.getText(document) // return value (flat concatenated text) is unused; pages is what we want
            return extractor.pages
        }
    }
}
