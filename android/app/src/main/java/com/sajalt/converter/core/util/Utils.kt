package com.sajalt.converter.core.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.IOException

/**
 * Parses and formats human-readable size strings ("100 KB", "2.5 MB", "1 GB") used by the
 * custom target-size inputs on the two compression screens.
 */
object SizeParser {

    private val PATTERN = Regex("""^\s*([0-9]+(?:\.[0-9]+)?)\s*(B|KB|MB|GB)?\s*$""", RegexOption.IGNORE_CASE)

    /** Returns the parsed byte count, or null if [input] is not a valid size expression. */
    fun parseToBytes(input: String): Long? {
        val match = PATTERN.matchEntire(input.trim()) ?: return null
        val value = match.groupValues[1].toDoubleOrNull() ?: return null
        val unit = match.groupValues[2].ifBlank { "KB" }.uppercase()
        val multiplier = when (unit) {
            "B" -> 1L
            "KB" -> 1_024L
            "MB" -> 1_024L * 1_024L
            "GB" -> 1_024L * 1_024L * 1_024L
            else -> return null
        }
        val bytes = (value * multiplier).toLong()
        return bytes.takeIf { it > 0 }
    }

    fun formatBytes(bytes: Long): String {
        if (bytes < 1_024L) return "$bytes B"
        val kb = bytes / 1_024.0
        if (kb < 1_024.0) return String.format("%.1f KB", kb)
        val mb = kb / 1_024.0
        if (mb < 1_024.0) return String.format("%.1f MB", mb)
        val gb = mb / 1_024.0
        return String.format("%.2f GB", gb)
    }
}

/**
 * Small wrappers around [android.content.ContentResolver] for the Storage Access Framework
 * flows used everywhere in this app. Every read goes through [android.content.ContentResolver]
 * against a `content://` Uri the user picked via a system document picker — never a raw
 * filesystem path into shared/external storage.
 */
object SafUtils {

    fun displayNameOf(context: Context, uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }

    /** Reported size in bytes, if the document provider exposes one (not all do). */
    fun sizeOf(context: Context, uri: Uri): Long? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.SIZE)
            if (idx >= 0 && c.moveToFirst() && !c.isNull(idx)) c.getLong(idx) else null
        }

    /** Reads the entire document into memory. Only used for documents the user explicitly picked. */
    fun readAllBytes(context: Context, uri: Uri): ByteArray {
        context.contentResolver.openInputStream(uri)?.use { input -> return input.readBytes() }
        throw IOException("Could not open an input stream for $uri")
    }

    fun writeBytes(context: Context, uri: Uri, data: ByteArray) {
        context.contentResolver.openOutputStream(uri)?.use { output ->
            output.write(data)
            output.flush()
            return
        }
        throw IOException("Could not open an output stream for $uri")
    }

    fun writeText(context: Context, uri: Uri, text: String) = writeBytes(context, uri, text.toByteArray(Charsets.UTF_8))
}

/**
 * XML 1.0 escaping for hand-assembled OOXML documents (see core/docx/DocxWriter.kt). Text
 * extracted from an arbitrary PDF can legally contain `&`, `<`, `>`, quotes, or even stray
 * control characters that are outright illegal in XML 1.0 — all three are handled here so the
 * generated .docx is always well-formed, regardless of what the source PDF contained.
 */
object XmlUtils {

    fun escape(text: String): String {
        val sb = StringBuilder(text.length + 16)
        for (c in text) {
            when {
                c == '&' -> sb.append("&amp;")
                c == '<' -> sb.append("&lt;")
                c == '>' -> sb.append("&gt;")
                c == '"' -> sb.append("&quot;")
                c == '\'' -> sb.append("&apos;")
                c == '\t' || c == '\n' || c == '\r' -> sb.append(c)
                c.code < 0x20 -> { /* illegal in XML 1.0 outside tab/LF/CR — drop silently */ }
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }
}

/**
 * Centralizes cleanup of any unavoidable temporary/scratch data (see README "Temporary files"
 * for the full accounting of when this can happen — mainly PDFBox scratch spill files for very
 * large PDFs). Every conversion entry point runs its work through [withCacheCleanup] so the
 * app-private cache directory is wiped immediately after the operation completes, whether it
 * succeeded or threw. [SajalTApplication] additionally wipes it once on cold start as a
 * fallback for a prior run that was killed mid-operation.
 */
object TempFileManager {

    fun wipeCacheDirectory(context: Context) {
        try {
            context.cacheDir?.listFiles()?.forEach { it.deleteRecursively() }
        } catch (_: Exception) {
            // Best-effort cleanup; never let this crash the caller.
        }
    }

    inline fun <T> withCacheCleanup(context: Context, block: () -> T): T {
        try {
            return block()
        } finally {
            wipeCacheDirectory(context)
        }
    }
}
