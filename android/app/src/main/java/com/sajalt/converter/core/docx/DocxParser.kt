package com.sajalt.converter.core.docx

import android.content.Context
import android.net.Uri
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.zip.ZipInputStream

/**
 * Parses a .docx (Office Open XML WordprocessingML) file into a [DocxDocument].
 *
 * Deliberately built on nothing but [ZipInputStream] and [XmlPullParser] — both built into every
 * Android device since API 1 — rather than a general-purpose OOXML library such as Apache POI.
 * POI's Android compatibility is genuinely uncertain (it references several `java.awt.*` classes
 * that plain Android does not provide) and pulls in a large schema jar for what is, underneath,
 * just XML inside a zip. Writing a small, purpose-built reader against exactly the OOXML tags
 * this app needs is more code than "just add POI" but removes that whole category of risk and
 * keeps every processing step auditable in this codebase.
 *
 * Scope (see README "Known limitations" for the full list): headings (Word's standard
 * Heading1-4/Title style IDs only — renamed/custom styles are not detected), paragraphs, basic
 * run formatting (bold/italic/underline/strikethrough/color), bulleted/numbered list indentation
 * (list markers themselves are rendered generically, not with the source document's exact
 * numbering scheme), inline images, tables including horizontal cell merge (`gridSpan`) and a
 * blank-cell approximation of vertical merge, tracked-change insertions/deletions (rendered as
 * underline/strikethrough), and comments (collected into a trailing "Comments" section, since
 * PdfDocument has no PDF-annotation API for true margin comments). Headers/footers, footnotes,
 * endnotes, and embedded OLE objects (e.g. an embedded Excel range) are not extracted.
 */
object DocxParser {

    fun parse(context: Context, uri: Uri): DocxDocument {
        val entries = readInterestingZipEntries(context, uri)
        val documentXml = entries["word/document.xml"]
            ?: throw DocxFormatException("Missing word/document.xml — this does not look like a valid .docx file")

        val relIdToMediaPath = entries["word/_rels/document.xml.rels"]
            ?.let { parseRelationships(it) }
            ?.mapValues { (_, target) -> normalizeMediaPath(target) }
            ?: emptyMap()

        val mediaBytes = entries.filterKeys { it.startsWith("word/media/") }
        val comments = entries["word/comments.xml"]?.let { parseComments(it) } ?: emptyList()

        val body = BodyParser(relIdToMediaPath, mediaBytes).parseDocumentXml(documentXml)
        return body.copy(comments = comments)
    }

    // -----------------------------------------------------------------------------------------
    // Zip / relationship / comments handling (flat, single-pass — no nested-element state needed)
    // -----------------------------------------------------------------------------------------

    private fun isInterestingEntry(name: String): Boolean =
        name == "word/document.xml" ||
            name == "word/_rels/document.xml.rels" ||
            name == "word/comments.xml" ||
            name.startsWith("word/media/")

    private fun readInterestingZipEntries(context: Context, uri: Uri): Map<String, ByteArray> {
        val result = mutableMapOf<String, ByteArray>()
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open $uri")
        input.use {
            ZipInputStream(BufferedInputStream(it)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val name = entry.name
                    if (!entry.isDirectory && isInterestingEntry(name)) {
                        result[name] = zip.readBytes()
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }
        if (!result.containsKey("word/document.xml")) {
            throw DocxFormatException("No word/document.xml found inside the archive — this does not look like a valid .docx file")
        }
        return result
    }

    private fun normalizeMediaPath(target: String): String {
        val cleaned = target.removePrefix("/")
        return if (cleaned.startsWith("media/")) "word/$cleaned" else "word/media/${cleaned.substringAfterLast('/')}"
    }

    private fun parseRelationships(xml: ByteArray): Map<String, String> {
        val map = mutableMapOf<String, String>()
        val parser = newPullParser(xml)
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "Relationship") {
                val id = parser.getAttributeValue(null, "Id")
                val target = parser.getAttributeValue(null, "Target")
                if (id != null && target != null) map[id] = target
            }
            event = parser.next()
        }
        return map
    }

    private fun parseComments(xml: ByteArray): List<DocxBlock.Comment> {
        val comments = mutableListOf<DocxBlock.Comment>()
        val parser = newPullParser(xml)
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "w:comment") {
                val author = parser.getAttributeValue(null, "w:author")?.takeIf { it.isNotBlank() } ?: "Unknown"
                val sb = StringBuilder()
                var inner = parser.next()
                while (!(inner == XmlPullParser.END_TAG && parser.name == "w:comment")) {
                    if (inner == XmlPullParser.TEXT) sb.append(parser.text)
                    if (inner == XmlPullParser.END_TAG && parser.name == "w:p" && sb.isNotEmpty() && sb.last() != '\n') {
                        sb.append('\n')
                    }
                    inner = parser.next()
                }
                val text = sb.toString().trim()
                if (text.isNotEmpty()) comments.add(DocxBlock.Comment(author, text))
            }
            event = parser.next()
        }
        return comments
    }

    private fun newPullParser(xml: ByteArray): XmlPullParser =
        XmlPullParserFactory.newInstance().newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(ByteArrayInputStream(xml), "UTF-8")
        }
}

/**
 * Recursive-descent walk over `word/document.xml`.
 *
 * INVARIANT every `parseXxx` method below maintains: called with [XmlPullParser] positioned at
 * the START_TAG of its element, it returns with the parser positioned at the matching END_TAG of
 * that SAME element (never past it). The one caller-visible loop pattern this enables, used
 * everywhere in this class, is:
 *
 * ```
 * var event = parser.next()
 * while (!(event == XmlPullParser.END_TAG && parser.name == "theTagWeStartedIn")) {
 *     if (event == XmlPullParser.START_TAG) { ... dispatch, each branch honors the invariant ... }
 *     event = parser.next()
 * }
 * ```
 *
 * Any element this class does not specifically care about is consumed via [skipElement] rather
 * than left unhandled — EXCEPT where a wrapper element might contain something we DO care about
 * arbitrarily deep inside it (see [parseDrawingForImage]), where skipping is deliberately avoided.
 */
private class BodyParser(
    private val relIdToMediaPath: Map<String, String>,
    private val mediaBytes: Map<String, ByteArray>
) {

    fun parseDocumentXml(xml: ByteArray): DocxDocument {
        val parser = XmlPullParserFactory.newInstance().newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(ByteArrayInputStream(xml), "UTF-8")
        }
        var event = parser.eventType
        while (!(event == XmlPullParser.START_TAG && parser.name == "w:body")) {
            if (event == XmlPullParser.END_DOCUMENT) return DocxDocument(emptyList())
            event = parser.next()
        }

        val blocks = mutableListOf<DocxBlock>()
        event = parser.next()
        while (!(event == XmlPullParser.END_TAG && parser.name == "w:body")) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "w:p" -> parseParagraphBlocks(parser, blocks)
                    "w:tbl" -> blocks.add(parseTable(parser))
                    else -> parser.skipElement()
                }
            }
            event = parser.next()
        }
        return DocxDocument(blocks)
    }

    // Precondition: START_TAG "w:p". Postcondition: END_TAG "w:p". Appends 1+ blocks to [sink]
    // (usually exactly one Paragraph; an inline image splits it into Paragraph/Image/Paragraph).
    private fun parseParagraphBlocks(parser: XmlPullParser, sink: MutableList<DocxBlock>) {
        var style = ParagraphStyle.NORMAL
        var listLevel = 0
        var runs = mutableListOf<DocxRun>()

        var event = parser.next()
        while (!(event == XmlPullParser.END_TAG && parser.name == "w:p")) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "w:pPr" -> {
                        val props = parseParagraphProperties(parser)
                        style = props.first
                        if (props.second >= 0) listLevel = props.second + 1 // >=1 marks "is a list item"
                    }
                    "w:r" -> {
                        val result = parseRun(parser)
                        if (result.image != null) {
                            if (runs.isNotEmpty()) {
                                sink.add(DocxBlock.Paragraph(style, runs.toList(), listLevel))
                                runs = mutableListOf()
                            }
                            sink.add(DocxBlock.Image(result.image))
                        }
                        result.run?.let { runs.add(it) }
                    }
                    "w:ins" -> runs.addAll(parseTrackedChangeRuns(parser, isInsertion = true))
                    "w:del" -> runs.addAll(parseTrackedChangeRuns(parser, isInsertion = false))
                    "w:hyperlink" -> runs.addAll(parseHyperlinkRuns(parser))
                    else -> parser.skipElement()
                }
            }
            event = parser.next()
        }
        sink.add(DocxBlock.Paragraph(style, runs.toList(), listLevel)) // preserves blank lines too
    }

    // Precondition: START_TAG "w:pPr". Postcondition: END_TAG "w:pPr".
    private fun parseParagraphProperties(parser: XmlPullParser): Pair<ParagraphStyle, Int> {
        var style = ParagraphStyle.NORMAL
        var listLevel = -1
        var event = parser.next()
        while (!(event == XmlPullParser.END_TAG && parser.name == "w:pPr")) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "w:pStyle" -> {
                        style = mapStyleIdToParagraphStyle(parser.getAttributeValue(null, "w:val"))
                        parser.skipElement()
                    }
                    "w:numPr" -> listLevel = parseListLevel(parser)
                    else -> parser.skipElement()
                }
            }
            event = parser.next()
        }
        return style to listLevel
    }

    // Precondition: START_TAG "w:numPr". Postcondition: END_TAG "w:numPr".
    private fun parseListLevel(parser: XmlPullParser): Int {
        var level = 0
        var event = parser.next()
        while (!(event == XmlPullParser.END_TAG && parser.name == "w:numPr")) {
            if (event == XmlPullParser.START_TAG) {
                if (parser.name == "w:ilvl") {
                    level = parser.getAttributeValue(null, "w:val")?.toIntOrNull() ?: 0
                }
                parser.skipElement()
            }
            event = parser.next()
        }
        return level
    }

    private class RunResult(val run: DocxRun?, val image: DocxImage?)

    // Precondition: START_TAG "w:r". Postcondition: END_TAG "w:r".
    private fun parseRun(parser: XmlPullParser): RunResult {
        var bold = false
        var italic = false
        var underline = false
        var strikethrough = false
        var colorHex: String? = null
        val text = StringBuilder()
        var image: DocxImage? = null

        var event = parser.next()
        while (!(event == XmlPullParser.END_TAG && parser.name == "w:r")) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "w:rPr" -> {
                        val props = parseRunProperties(parser)
                        bold = props.bold; italic = props.italic; underline = props.underline
                        strikethrough = props.strikethrough; colorHex = props.colorHex
                    }
                    "w:t", "w:delText" -> text.append(readTextElement(parser))
                    "w:tab" -> { text.append('\t'); parser.skipElement() }
                    "w:br", "w:cr" -> { text.append('\n'); parser.skipElement() }
                    "w:drawing" -> image = parseDrawingForImage(parser)
                    else -> parser.skipElement()
                }
            }
            event = parser.next()
        }
        val run = if (text.isNotEmpty()) DocxRun(text.toString(), bold, italic, underline, strikethrough, colorHex = colorHex) else null
        return RunResult(run, image)
    }

    private class RunProps(
        var bold: Boolean = false,
        var italic: Boolean = false,
        var underline: Boolean = false,
        var strikethrough: Boolean = false,
        var colorHex: String? = null
    )

    // Precondition: START_TAG "w:rPr". Postcondition: END_TAG "w:rPr".
    private fun parseRunProperties(parser: XmlPullParser): RunProps {
        val props = RunProps()
        var event = parser.next()
        while (!(event == XmlPullParser.END_TAG && parser.name == "w:rPr")) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "w:b" -> props.bold = parser.getAttributeValue(null, "w:val").let { it != "false" && it != "0" }
                    "w:i" -> props.italic = parser.getAttributeValue(null, "w:val").let { it != "false" && it != "0" }
                    "w:strike" -> props.strikethrough = parser.getAttributeValue(null, "w:val").let { it != "false" && it != "0" }
                    "w:u" -> {
                        val v = parser.getAttributeValue(null, "w:val")
                        props.underline = v != null && v != "none"
                    }
                    "w:color" -> {
                        val v = parser.getAttributeValue(null, "w:val")
                        if (v != null && v != "auto" && v.matches(Regex("^[0-9A-Fa-f]{6}$"))) props.colorHex = v
                    }
                }
                parser.skipElement()
            }
            event = parser.next()
        }
        return props
    }

    // Precondition: START_TAG "w:t" or "w:delText". Postcondition: matching END_TAG.
    private fun readTextElement(parser: XmlPullParser): String {
        val tag = parser.name
        val sb = StringBuilder()
        var event = parser.next()
        while (!(event == XmlPullParser.END_TAG && parser.name == tag)) {
            if (event == XmlPullParser.TEXT) sb.append(parser.text)
            event = parser.next()
        }
        return sb.toString()
    }

    // Precondition: START_TAG "w:ins" or "w:del". Postcondition: matching END_TAG.
    private fun parseTrackedChangeRuns(parser: XmlPullParser, isInsertion: Boolean): List<DocxRun> {
        val wrapperTag = parser.name
        val results = mutableListOf<DocxRun>()
        var event = parser.next()
        while (!(event == XmlPullParser.END_TAG && parser.name == wrapperTag)) {
            if (event == XmlPullParser.START_TAG && parser.name == "w:r") {
                parseRun(parser).run?.let {
                    results.add(
                        it.copy(
                            isInsertion = isInsertion,
                            isDeletion = !isInsertion,
                            underline = if (isInsertion) true else it.underline,
                            strikethrough = if (!isInsertion) true else it.strikethrough
                        )
                    )
                }
            } else if (event == XmlPullParser.START_TAG) {
                parser.skipElement()
            }
            event = parser.next()
        }
        return results
    }

    // Precondition: START_TAG "w:hyperlink". Postcondition: END_TAG "w:hyperlink". The target
    // URL itself is not carried through — Android's PdfDocument has no link-annotation API — so
    // hyperlink text is preserved and styled, but is not clickable in the output PDF.
    private fun parseHyperlinkRuns(parser: XmlPullParser): List<DocxRun> {
        val results = mutableListOf<DocxRun>()
        var event = parser.next()
        while (!(event == XmlPullParser.END_TAG && parser.name == "w:hyperlink")) {
            if (event == XmlPullParser.START_TAG && parser.name == "w:r") {
                parseRun(parser).run?.let { results.add(it.copy(underline = true, colorHex = it.colorHex ?: "0563C1")) }
            } else if (event == XmlPullParser.START_TAG) {
                parser.skipElement()
            }
            event = parser.next()
        }
        return results
    }

    // Precondition: START_TAG "w:drawing". Postcondition: END_TAG "w:drawing". Deliberately does
    // NOT skip unrecognized children: the image reference (a:blip) can sit at different depths
    // depending on whether the drawing is inline (wp:inline) or floating (wp:anchor), so this
    // just walks every event inside <w:drawing> looking for a:blip rather than assuming one path.
    private fun parseDrawingForImage(parser: XmlPullParser): DocxImage? {
        var relId: String? = null
        var event = parser.next()
        while (!(event == XmlPullParser.END_TAG && parser.name == "w:drawing")) {
            if (event == XmlPullParser.START_TAG && parser.name == "a:blip") {
                relId = parser.getAttributeValue(null, "r:embed") ?: relId
            }
            event = parser.next()
        }
        val id = relId ?: return null
        val mediaPath = relIdToMediaPath[id] ?: return null
        val bytes = mediaBytes[mediaPath] ?: return null
        return DocxImage(bytes)
    }

    // Precondition: START_TAG "w:tbl". Postcondition: END_TAG "w:tbl".
    private fun parseTable(parser: XmlPullParser): DocxBlock.Table {
        val rows = mutableListOf<TableRow>()
        var event = parser.next()
        while (!(event == XmlPullParser.END_TAG && parser.name == "w:tbl")) {
            if (event == XmlPullParser.START_TAG && parser.name == "w:tr") {
                rows.add(parseTableRow(parser))
            } else if (event == XmlPullParser.START_TAG) {
                parser.skipElement() // w:tblPr / w:tblGrid: layout hints this renderer doesn't use
            }
            event = parser.next()
        }
        return DocxBlock.Table(rows)
    }

    // Precondition: START_TAG "w:tr". Postcondition: END_TAG "w:tr".
    private fun parseTableRow(parser: XmlPullParser): TableRow {
        val cells = mutableListOf<TableCell>()
        var event = parser.next()
        while (!(event == XmlPullParser.END_TAG && parser.name == "w:tr")) {
            if (event == XmlPullParser.START_TAG && parser.name == "w:tc") {
                cells.add(parseTableCell(parser))
            } else if (event == XmlPullParser.START_TAG) {
                parser.skipElement()
            }
            event = parser.next()
        }
        return TableRow(cells)
    }

    // Precondition: START_TAG "w:tc". Postcondition: END_TAG "w:tc".
    private fun parseTableCell(parser: XmlPullParser): TableCell {
        var gridSpan = 1
        var vMergeContinuation = false
        val blocks = mutableListOf<DocxBlock>()
        var event = parser.next()
        while (!(event == XmlPullParser.END_TAG && parser.name == "w:tc")) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "w:tcPr" -> {
                        val props = parseCellProperties(parser)
                        gridSpan = props.first
                        vMergeContinuation = props.second
                    }
                    "w:p" -> parseParagraphBlocks(parser, blocks)
                    "w:tbl" -> blocks.add(parseTable(parser))
                    else -> parser.skipElement()
                }
            }
            event = parser.next()
        }
        return TableCell(blocks, gridSpan, vMergeContinuation)
    }

    // Precondition: START_TAG "w:tcPr". Postcondition: END_TAG "w:tcPr".
    private fun parseCellProperties(parser: XmlPullParser): Pair<Int, Boolean> {
        var gridSpan = 1
        var vMergeContinuation = false
        var event = parser.next()
        while (!(event == XmlPullParser.END_TAG && parser.name == "w:tcPr")) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "w:gridSpan" -> gridSpan = parser.getAttributeValue(null, "w:val")?.toIntOrNull() ?: 1
                    "w:vMerge" -> {
                        val v = parser.getAttributeValue(null, "w:val")
                        vMergeContinuation = v == null || v == "continue"
                    }
                }
                parser.skipElement()
            }
            event = parser.next()
        }
        return gridSpan to vMergeContinuation
    }

    private fun mapStyleIdToParagraphStyle(styleId: String?): ParagraphStyle = when (styleId) {
        "Title" -> ParagraphStyle.TITLE
        "Heading1" -> ParagraphStyle.HEADING1
        "Heading2" -> ParagraphStyle.HEADING2
        "Heading3", "Heading4", "Heading5", "Heading6" -> ParagraphStyle.HEADING3
        else -> ParagraphStyle.NORMAL
    }
}

// Precondition: eventType == START_TAG. Postcondition: eventType == END_TAG of the SAME element.
private fun XmlPullParser.skipElement() {
    var depth = 1
    while (depth > 0) {
        when (next()) {
            XmlPullParser.START_TAG -> depth++
            XmlPullParser.END_TAG -> depth--
        }
    }
}
