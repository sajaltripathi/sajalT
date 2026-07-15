package com.sajalt.converter.core.docx

import android.graphics.BitmapFactory
import com.sajalt.converter.core.util.XmlUtils
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Serializes a [DocxDocument] to a real, valid .docx file — the mirror image of [DocxParser]:
 * same in-memory model, opposite direction. Used by [PdfToDocxConverter]. Builds every XML part
 * by hand with [XmlUtils.escape] applied to all text content, rather than depending on a
 * third-party OOXML writer library, for the same reason [DocxParser] avoids one (see its class
 * doc): this is a small, fixed, well-understood set of parts, and hand-writing them keeps the
 * whole read/write path auditable in one codebase with no additional dependency.
 */
object DocxWriter {

    fun write(doc: DocxDocument, outputStream: OutputStream) {
        val images = collectImagesInOrder(doc)
        val relIds = mutableMapOf<DocxImage, String>()
        val fileNames = mutableMapOf<DocxImage, String>()
        images.forEachIndexed { index, img ->
            relIds[img] = "rId${100 + index}"
            fileNames[img] = "image${index + 1}.${detectImageExtension(img.bytes)}"
        }

        val documentXml = buildDocumentXml(doc, relIds)

        ZipOutputStream(outputStream).use { zip ->
            writeText(zip, "[Content_Types].xml", buildContentTypesXml(images, fileNames))
            writeText(zip, "_rels/.rels", ROOT_RELS_XML)
            writeText(zip, "docProps/core.xml", CORE_PROPS_XML)
            writeText(zip, "docProps/app.xml", APP_PROPS_XML)
            writeText(zip, "word/styles.xml", STYLES_XML)
            writeText(zip, "word/document.xml", documentXml)
            if (images.isNotEmpty()) {
                writeText(zip, "word/_rels/document.xml.rels", buildDocumentRelsXml(images, relIds, fileNames))
                for (img in images) writeBytes(zip, "word/media/${fileNames[img]}", img.bytes)
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // word/document.xml
    // ---------------------------------------------------------------------------------------

    private fun buildDocumentXml(doc: DocxDocument, relIds: Map<DocxImage, String>): String {
        val body = StringBuilder()
        for (block in doc.blocks) appendBlock(body, block, relIds)
        if (doc.comments.isNotEmpty()) appendCommentsSection(body, doc.comments)

        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture"><w:body>$body<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440" w:header="708" w:footer="708" w:gutter="0"/></w:sectPr></w:body></w:document>"""
    }

    private fun appendBlock(sb: StringBuilder, block: DocxBlock, relIds: Map<DocxImage, String>) {
        when (block) {
            is DocxBlock.Paragraph -> appendParagraph(sb, block)
            is DocxBlock.Image -> appendImageParagraph(sb, block, relIds)
            is DocxBlock.Table -> appendTable(sb, block, relIds)
            is DocxBlock.Comment -> Unit // handled once, in appendCommentsSection
        }
    }

    private fun styleIdFor(style: ParagraphStyle): String? = when (style) {
        ParagraphStyle.TITLE -> "Title"
        ParagraphStyle.HEADING1 -> "Heading1"
        ParagraphStyle.HEADING2 -> "Heading2"
        ParagraphStyle.HEADING3 -> "Heading3"
        ParagraphStyle.NORMAL -> null
    }

    private fun appendParagraph(sb: StringBuilder, p: DocxBlock.Paragraph) {
        sb.append("<w:p><w:pPr>")
        styleIdFor(p.style)?.let { sb.append("<w:pStyle w:val=\"").append(it).append("\"/>") }
        sb.append("</w:pPr>")
        if (p.listLevel >= 1) {
            // A literal bullet character rather than a true numbering-definition reference: see
            // README "Known limitations" — this avoids depending on a word/numbering.xml part
            // that would need its own careful construction for comparatively little fidelity gain.
            sb.append("<w:r><w:t xml:space=\"preserve\">")
                .append("    ".repeat(p.listLevel - 1))
                .append("\u2022 ")
                .append("</w:t></w:r>")
        }
        for (run in p.runs) appendRun(sb, run)
        sb.append("</w:p>")
    }

    private fun appendRun(sb: StringBuilder, run: DocxRun) {
        sb.append("<w:r>")
        val hasProps = run.bold || run.italic || run.underline || run.strikethrough || run.colorHex != null
        if (hasProps) {
            sb.append("<w:rPr>")
            if (run.bold) sb.append("<w:b/>")
            if (run.italic) sb.append("<w:i/>")
            if (run.underline) sb.append("<w:u w:val=\"single\"/>")
            if (run.strikethrough) sb.append("<w:strike/>")
            run.colorHex?.let { sb.append("<w:color w:val=\"").append(it).append("\"/>") }
            sb.append("</w:rPr>")
        }
        sb.append("<w:t xml:space=\"preserve\">").append(XmlUtils.escape(run.text)).append("</w:t></w:r>")
    }

    private var imageDocPrCounter = 0

    private fun appendImageParagraph(sb: StringBuilder, block: DocxBlock.Image, relIds: Map<DocxImage, String>) {
        val relId = relIds[block.image] ?: return
        val (w, h) = decodePixelSize(block.image.bytes) ?: (400 to 300)
        val maxEmuWidth = 5_486_400L // ~6 inches: fits inside a Normal-margin A4/Letter page
        var emuW = w.toLong() * 9_525L // 9525 EMU per pixel at a 96 dpi assumption
        var emuH = h.toLong() * 9_525L
        if (emuW > maxEmuWidth) {
            val ratio = maxEmuWidth.toDouble() / emuW
            emuW = maxEmuWidth
            emuH = (emuH * ratio).toLong()
        }
        val id = ++imageDocPrCounter
        sb.append("<w:p><w:r><w:drawing>")
        sb.append("<wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\">")
        sb.append("<wp:extent cx=\"").append(emuW).append("\" cy=\"").append(emuH).append("\"/>")
        sb.append("<wp:docPr id=\"").append(id).append("\" name=\"Picture ").append(id).append("\"/>")
        sb.append("<a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">")
        sb.append("<pic:pic><pic:nvPicPr><pic:cNvPr id=\"").append(id).append("\" name=\"Picture ").append(id).append("\"/><pic:cNvPicPr/></pic:nvPicPr>")
        sb.append("<pic:blipFill><a:blip r:embed=\"").append(relId).append("\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>")
        sb.append("<pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"").append(emuW).append("\" cy=\"").append(emuH).append("\"/></a:xfrm>")
        sb.append("<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr>")
        sb.append("</pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>")
    }

    private fun appendTable(sb: StringBuilder, table: DocxBlock.Table, relIds: Map<DocxImage, String>) {
        if (table.rows.isEmpty()) return
        sb.append("<w:tbl><w:tblPr><w:tblBorders>")
        for (edge in listOf("top", "left", "bottom", "right", "insideH", "insideV")) {
            sb.append("<w:").append(edge).append(" w:val=\"single\" w:sz=\"4\" w:color=\"D6DEDC\"/>")
        }
        sb.append("</w:tblBorders></w:tblPr>")
        for (row in table.rows) {
            sb.append("<w:tr>")
            for (cell in row.cells) {
                sb.append("<w:tc><w:tcPr>")
                if (cell.gridSpan > 1) sb.append("<w:gridSpan w:val=\"").append(cell.gridSpan).append("\"/>")
                if (cell.isVerticalMergeContinuation) sb.append("<w:vMerge/>")
                sb.append("</w:tcPr>")
                if (cell.blocks.isEmpty()) sb.append("<w:p/>") else cell.blocks.forEach { appendBlock(sb, it, relIds) }
                sb.append("</w:tc>")
            }
            sb.append("</w:tr>")
        }
        sb.append("</w:tbl><w:p/>") // a paragraph must follow a table at body level per the OOXML schema
    }

    private fun appendCommentsSection(sb: StringBuilder, comments: List<DocxBlock.Comment>) {
        sb.append("<w:p><w:pPr><w:pStyle w:val=\"Heading1\"/></w:pPr><w:r><w:t>Comments</w:t></w:r></w:p>")
        for (c in comments) {
            sb.append("<w:p><w:r><w:rPr><w:b/></w:rPr><w:t xml:space=\"preserve\">")
                .append(XmlUtils.escape(c.author)).append(": </w:t></w:r>")
                .append("<w:r><w:t xml:space=\"preserve\">").append(XmlUtils.escape(c.text)).append("</w:t></w:r></w:p>")
        }
    }

    // ---------------------------------------------------------------------------------------
    // Images: collection, extension sniffing, pixel-size probing
    // ---------------------------------------------------------------------------------------

    private fun collectImagesInOrder(doc: DocxDocument): List<DocxImage> {
        val result = mutableListOf<DocxImage>()
        fun visit(block: DocxBlock) {
            when (block) {
                is DocxBlock.Image -> result.add(block.image)
                is DocxBlock.Table -> block.rows.forEach { row -> row.cells.forEach { cell -> cell.blocks.forEach(::visit) } }
                else -> Unit
            }
        }
        doc.blocks.forEach(::visit)
        return result
    }

    private fun detectImageExtension(bytes: ByteArray): String = when {
        bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() -> "png"
        bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "jpeg"
        bytes.size >= 6 && bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() -> "gif"
        bytes.size >= 2 && bytes[0] == 'B'.code.toByte() && bytes[1] == 'M'.code.toByte() -> "bmp"
        else -> "png"
    }

    private fun decodePixelSize(bytes: ByteArray): Pair<Int, Int>? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        return if (opts.outWidth > 0 && opts.outHeight > 0) opts.outWidth to opts.outHeight else null
    }

    // ---------------------------------------------------------------------------------------
    // Fixed / near-fixed parts
    // ---------------------------------------------------------------------------------------

    private fun buildContentTypesXml(images: List<DocxImage>, fileNames: Map<DocxImage, String>): String {
        val extensions = images.mapNotNull { fileNames[it]?.substringAfterLast('.') }.toSet()
        val sb = StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
        sb.append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">")
        sb.append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>")
        sb.append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>")
        for (ext in extensions) {
            val contentType = when (ext) {
                "jpeg", "jpg" -> "image/jpeg"
                "gif" -> "image/gif"
                "bmp" -> "image/bmp"
                else -> "image/png"
            }
            sb.append("<Default Extension=\"").append(ext).append("\" ContentType=\"").append(contentType).append("\"/>")
        }
        sb.append("<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>")
        sb.append("<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/>")
        sb.append("<Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/>")
        sb.append("<Override PartName=\"/docProps/app.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.extended-properties+xml\"/>")
        sb.append("</Types>")
        return sb.toString()
    }

    private fun buildDocumentRelsXml(images: List<DocxImage>, relIds: Map<DocxImage, String>, fileNames: Map<DocxImage, String>): String {
        val sb = StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
        sb.append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">")
        for (img in images) {
            sb.append("<Relationship Id=\"").append(relIds[img]).append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"media/").append(fileNames[img]).append("\"/>")
        }
        sb.append("</Relationships>")
        return sb.toString()
    }

    private fun writeText(zip: ZipOutputStream, name: String, content: String) = writeBytes(zip, name, content.toByteArray(Charsets.UTF_8))

    private fun writeBytes(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(bytes)
        zip.closeEntry()
    }

    private val ROOT_RELS_XML = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/><Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties" Target="docProps/app.xml"/></Relationships>"""

    private val CORE_PROPS_XML = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:creator>sajalT</dc:creator><cp:lastModifiedBy>sajalT</cp:lastModifiedBy></cp:coreProperties>"""

    private val APP_PROPS_XML = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/extended-properties"><Application>sajalT</Application></Properties>"""

    // Point sizes here (w:sz is in half-points) are chosen to visually match the point sizes
    // DocxToPdfRenderer uses for the same style names, so a document that round-trips through
    // both converters looks visually consistent.
    private val STYLES_XML = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
<w:docDefaults><w:rPrDefault><w:rPr><w:sz w:val="22"/></w:rPr></w:rPrDefault></w:docDefaults>
<w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/></w:style>
<w:style w:type="paragraph" w:styleId="Title"><w:name w:val="Title"/><w:basedOn w:val="Normal"/><w:rPr><w:b/><w:sz w:val="48"/></w:rPr></w:style>
<w:style w:type="paragraph" w:styleId="Heading1"><w:name w:val="heading 1"/><w:basedOn w:val="Normal"/><w:rPr><w:b/><w:sz w:val="38"/></w:rPr></w:style>
<w:style w:type="paragraph" w:styleId="Heading2"><w:name w:val="heading 2"/><w:basedOn w:val="Normal"/><w:rPr><w:b/><w:sz w:val="30"/></w:rPr></w:style>
<w:style w:type="paragraph" w:styleId="Heading3"><w:name w:val="heading 3"/><w:basedOn w:val="Normal"/><w:rPr><w:b/><w:sz w:val="26"/></w:rPr></w:style>
</w:styles>"""
}
