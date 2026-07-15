package com.sajalt.converter.core.docx

/** A run of text with consistent formatting within a paragraph. */
data class DocxRun(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strikethrough: Boolean = false,
    val isInsertion: Boolean = false, // tracked-change insertion (w:ins) — rendered underlined
    val isDeletion: Boolean = false,  // tracked-change deletion (w:del) — rendered struck through
    val colorHex: String? = null      // "RRGGBB", no leading '#', or null for default/automatic
)

enum class ParagraphStyle { TITLE, HEADING1, HEADING2, HEADING3, NORMAL }

/** Raw bytes of an embedded image; dimensions are decoded lazily by whoever draws it.
 *  Uses identity equality deliberately: [com.sajalt.converter.core.docx.DocxWriter] keys a
 *  Map<DocxImage, relationshipId> off of instances encountered during parsing/conversion, and
 *  each such instance should get its own word/media/ entry regardless of whether its bytes
 *  happen to be identical to another image's — structural equality would risk silently
 *  collapsing two distinct images together. */
data class DocxImage(val bytes: ByteArray) {
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

sealed class DocxBlock {
    data class Paragraph(
        val style: ParagraphStyle,
        val runs: List<DocxRun>,
        val listLevel: Int = 0 // 0 = not a list item; >=1 = list item at that indent depth
    ) : DocxBlock()

    data class Image(val image: DocxImage) : DocxBlock()

    data class Table(val rows: List<TableRow>) : DocxBlock()

    /** Emitted once for the whole document as a trailing "Comments" section, since Android's
     *  PdfDocument has no PDF-annotation API to attach true margin comments. */
    data class Comment(val author: String, val text: String) : DocxBlock()
}

data class TableCell(
    val blocks: List<DocxBlock>,
    val gridSpan: Int = 1,
    /** True if this cell continues a vertical merge from the cell above (w:vMerge without
     *  w:val="restart"); rendered as a blank cell rather than a true spanning border — see
     *  README "Known limitations". */
    val isVerticalMergeContinuation: Boolean = false
)

data class TableRow(val cells: List<TableCell>)

data class DocxDocument(
    val blocks: List<DocxBlock>,
    val comments: List<DocxBlock.Comment> = emptyList()
)

class DocxFormatException(message: String) : Exception(message)
