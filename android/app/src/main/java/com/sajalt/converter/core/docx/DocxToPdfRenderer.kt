package com.sajalt.converter.core.docx

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import java.io.OutputStream

/**
 * Renders a [DocxDocument] to PDF using Android's own [StaticLayout]/[TextPaint] text layout
 * (the same engine every Android [android.widget.TextView] uses) rather than a hand-rolled line
 * wrapper — line breaking, hyphenless wrapping, and font metrics are exactly as well-tested as
 * anything else that draws text on Android.
 *
 * Layout policy (see README "Known limitations" for the full accounting): A4 pages, fixed
 * margins; paragraphs and table rows are kept whole and moved to the next page rather than being
 * split mid-block (an unusually long single paragraph may extend past the bottom margin on its
 * page rather than being cut across two — a deliberate simplicity trade-off, not a crash risk);
 * table cell content is flattened to styled text (bold/italic/underline/strikethrough survive;
 * a nested table or image inside a cell does not — see [buildCellSpannable]); vertically merged
 * cells render as blank continuation cells rather than a single spanning border.
 */
object DocxToPdfRenderer {

    private const val PAGE_WIDTH = 595f  // A4 at 72pt/inch
    private const val PAGE_HEIGHT = 842f
    private const val MARGIN = 48f
    private val CONTENT_WIDTH = PAGE_WIDTH - 2 * MARGIN

    private const val SIZE_TITLE = 24f
    private const val SIZE_H1 = 19f
    private const val SIZE_H2 = 15f
    private const val SIZE_H3 = 12.5f
    private const val SIZE_NORMAL = 11f
    private const val SIZE_CELL = 10f
    private const val LINE_SPACING_MULT = 1.15f
    private const val SPACING_AFTER_BLOCK = 8f
    private const val BLANK_LINE_HEIGHT = SIZE_NORMAL * LINE_SPACING_MULT

    private val IMAGE_PAINT = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    fun render(doc: DocxDocument, outputStream: OutputStream) {
        val pdf = PdfDocument()
        try {
            val state = RenderState(pdf)
            for (block in doc.blocks) {
                when (block) {
                    is DocxBlock.Paragraph -> renderParagraph(state, block)
                    is DocxBlock.Image -> renderImage(state, block)
                    is DocxBlock.Table -> renderTable(state, block)
                    is DocxBlock.Comment -> Unit // top-level Comment blocks are never produced by
                                                  // DocxParser (comments live in doc.comments and
                                                  // are rendered together below); nothing to do.
                }
            }
            renderComments(state, doc.comments)
            state.finish()
            pdf.writeTo(outputStream)
        } finally {
            pdf.close()
        }
    }

    /** Encapsulates the current page/canvas/cursor so drawing code never touches PdfDocument
     *  page lifecycle directly — every mutation goes through [ensureSpace] and [startNewPage]. */
    private class RenderState(private val pdf: PdfDocument) {
        var pageNumber = 1
            private set
        private var page: PdfDocument.Page = pdf.startPage(pageInfo(pageNumber))
        var canvas: android.graphics.Canvas = page.canvas
            private set
        var y: Float = MARGIN

        fun ensureSpace(height: Float) {
            if (y + height > PAGE_HEIGHT - MARGIN) startNewPage()
        }

        fun startNewPage() {
            pdf.finishPage(page)
            pageNumber++
            page = pdf.startPage(pageInfo(pageNumber))
            canvas = page.canvas
            y = MARGIN
        }

        fun finish() {
            pdf.finishPage(page)
        }

        private fun pageInfo(num: Int) =
            PdfDocument.PageInfo.Builder(PAGE_WIDTH.toInt(), PAGE_HEIGHT.toInt(), num).create()
    }

    // ---------------------------------------------------------------------------------------
    // Paragraphs
    // ---------------------------------------------------------------------------------------

    private fun renderParagraph(state: RenderState, paragraph: DocxBlock.Paragraph) {
        val spannable = buildParagraphSpannable(paragraph)
        if (spannable.isBlank()) {
            state.ensureSpace(BLANK_LINE_HEIGHT)
            state.y += BLANK_LINE_HEIGHT
            return
        }
        val paint = textPaintFor(paragraph.style)
        val layout = StaticLayout.Builder
            .obtain(spannable, 0, spannable.length, paint, CONTENT_WIDTH.toInt())
            .setLineSpacing(0f, LINE_SPACING_MULT)
            .setIncludePad(false)
            .build()
        val height = layout.height.toFloat()
        state.ensureSpace(height)
        state.canvas.save()
        state.canvas.translate(MARGIN, state.y)
        layout.draw(state.canvas)
        state.canvas.restore()
        state.y += height + SPACING_AFTER_BLOCK
    }

    private fun buildParagraphSpannable(paragraph: DocxBlock.Paragraph): SpannableStringBuilder {
        val sb = SpannableStringBuilder()
        if (paragraph.listLevel >= 1) {
            sb.append("    ".repeat(paragraph.listLevel - 1))
            sb.append("•  ")
        }
        for (run in paragraph.runs) appendRunWithSpans(sb, run)
        return sb
    }

    private fun appendRunWithSpans(sb: SpannableStringBuilder, run: DocxRun) {
        val start = sb.length
        sb.append(run.text)
        val end = sb.length
        if (end == start) return
        if (run.bold) sb.setSpan(StyleSpan(Typeface.BOLD), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (run.italic) sb.setSpan(StyleSpan(Typeface.ITALIC), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (run.underline) sb.setSpan(UnderlineSpan(), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (run.strikethrough) sb.setSpan(StrikethroughSpan(), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        run.colorHex?.let { hex ->
            try {
                sb.setSpan(ForegroundColorSpan(Color.parseColor("#$hex")), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            } catch (_: IllegalArgumentException) {
                // Malformed color value from the source document; just skip coloring this run.
            }
        }
    }

    private fun textPaintFor(style: ParagraphStyle): TextPaint {
        val size = when (style) {
            ParagraphStyle.TITLE -> SIZE_TITLE
            ParagraphStyle.HEADING1 -> SIZE_H1
            ParagraphStyle.HEADING2 -> SIZE_H2
            ParagraphStyle.HEADING3 -> SIZE_H3
            ParagraphStyle.NORMAL -> SIZE_NORMAL
        }
        return TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            color = Color.BLACK
            isFakeBoldText = style != ParagraphStyle.NORMAL
        }
    }

    // ---------------------------------------------------------------------------------------
    // Images
    // ---------------------------------------------------------------------------------------

    private fun renderImage(state: RenderState, block: DocxBlock.Image) {
        val bytes = block.image.bytes
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val rawW = bounds.outWidth
        val rawH = bounds.outHeight
        if (rawW <= 0 || rawH <= 0) return // corrupt/unsupported embedded image data; skip gracefully

        val scale = minOf(1f, CONTENT_WIDTH / rawW.toFloat())
        val drawW = rawW * scale
        val drawH = rawH * scale

        val sampleSize = calculateInSampleSize(rawW, (drawW * 2).toInt().coerceAtLeast(1))
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
            inSampleSize = sampleSize
        }) ?: return

        state.ensureSpace(drawH)
        val destRect = RectF(MARGIN, state.y, MARGIN + drawW, state.y + drawH)
        state.canvas.drawBitmap(bitmap, null, destRect, IMAGE_PAINT) // recorded, not recycled — PdfDocument canvas defers to finishPage()
        state.y += drawH + SPACING_AFTER_BLOCK
    }

    /** Standard Android "load large bitmaps efficiently" doubling-based sample size calculation. */
    private fun calculateInSampleSize(rawWidth: Int, reqWidth: Int): Int {
        var inSampleSize = 1
        if (rawWidth > reqWidth) {
            val half = rawWidth / 2
            while (half / inSampleSize >= reqWidth) inSampleSize *= 2
        }
        return inSampleSize
    }

    // ---------------------------------------------------------------------------------------
    // Tables
    // ---------------------------------------------------------------------------------------

    private val BORDER_PAINT = Paint().apply {
        color = Color.rgb(0xD6, 0xDE, 0xDC)
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }
    private const val CELL_PADDING = 4f
    private const val MIN_ROW_HEIGHT = 18f

    private class CellLayout(val layout: StaticLayout?, val gridSpan: Int)

    private fun renderTable(state: RenderState, table: DocxBlock.Table) {
        if (table.rows.isEmpty()) return
        val columnCount = table.rows.maxOf { row -> row.cells.sumOf { it.gridSpan.coerceAtLeast(1) } }
        if (columnCount <= 0) return
        val colWidth = CONTENT_WIDTH / columnCount
        val cellPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = SIZE_CELL; color = Color.BLACK }

        for (row in table.rows) {
            val cellLayouts = row.cells.map { cell ->
                val span = cell.gridSpan.coerceAtLeast(1)
                if (cell.isVerticalMergeContinuation) {
                    CellLayout(null, span)
                } else {
                    val cellWidth = (colWidth * span - 2 * CELL_PADDING).coerceAtLeast(10f).toInt()
                    val text = buildCellSpannable(cell)
                    val layout = if (text.isNotBlank()) {
                        StaticLayout.Builder.obtain(text, 0, text.length, cellPaint, cellWidth)
                            .setLineSpacing(0f, LINE_SPACING_MULT)
                            .setIncludePad(false)
                            .build()
                    } else null
                    CellLayout(layout, span)
                }
            }

            val rowHeight = maxOf(
                MIN_ROW_HEIGHT,
                (cellLayouts.maxOfOrNull { it.layout?.height ?: 0 } ?: 0).toFloat() + 2 * CELL_PADDING
            )
            state.ensureSpace(rowHeight) // whole rows move together; not split mid-row across pages

            var x = MARGIN
            for (cl in cellLayouts) {
                val w = colWidth * cl.gridSpan
                state.canvas.drawRect(x, state.y, x + w, state.y + rowHeight, BORDER_PAINT)
                cl.layout?.let { layout ->
                    state.canvas.save()
                    state.canvas.translate(x + CELL_PADDING, state.y + CELL_PADDING)
                    layout.draw(state.canvas)
                    state.canvas.restore()
                }
                x += w
            }
            state.y += rowHeight
        }
        state.y += SPACING_AFTER_BLOCK
    }

    /** Flattens a cell's paragraphs into one styled block of text (bold/italic/underline/
     *  strikethrough preserved); a nested table or image inside a cell is not rendered — see
     *  class doc "Known limitations". */
    private fun buildCellSpannable(cell: TableCell): SpannableStringBuilder {
        val sb = SpannableStringBuilder()
        for (block in cell.blocks) {
            if (block is DocxBlock.Paragraph) {
                if (sb.isNotEmpty()) sb.append('\n')
                for (run in block.runs) appendRunWithSpans(sb, run)
            }
        }
        return sb
    }

    // ---------------------------------------------------------------------------------------
    // Comments (collected into a trailing section — see class doc for why)
    // ---------------------------------------------------------------------------------------

    private fun renderComments(state: RenderState, comments: List<DocxBlock.Comment>) {
        if (comments.isEmpty()) return
        state.startNewPage()

        val headingText = "Comments"
        val headingPaint = textPaintFor(ParagraphStyle.HEADING1)
        val headingLayout = StaticLayout.Builder
            .obtain(headingText, 0, headingText.length, headingPaint, CONTENT_WIDTH.toInt())
            .setIncludePad(false)
            .build()
        state.ensureSpace(headingLayout.height.toFloat())
        state.canvas.save()
        state.canvas.translate(MARGIN, state.y)
        headingLayout.draw(state.canvas)
        state.canvas.restore()
        state.y += headingLayout.height + SPACING_AFTER_BLOCK * 2

        val bodyPaint = textPaintFor(ParagraphStyle.NORMAL)
        for (comment in comments) {
            val sb = SpannableStringBuilder()
            sb.append(comment.author)
            sb.setSpan(StyleSpan(Typeface.BOLD), 0, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.append(": ")
            sb.append(comment.text)
            val layout = StaticLayout.Builder.obtain(sb, 0, sb.length, bodyPaint, CONTENT_WIDTH.toInt())
                .setLineSpacing(0f, LINE_SPACING_MULT)
                .setIncludePad(false)
                .build()
            state.ensureSpace(layout.height.toFloat())
            state.canvas.save()
            state.canvas.translate(MARGIN, state.y)
            layout.draw(state.canvas)
            state.canvas.restore()
            state.y += layout.height + SPACING_AFTER_BLOCK
        }
    }
}
