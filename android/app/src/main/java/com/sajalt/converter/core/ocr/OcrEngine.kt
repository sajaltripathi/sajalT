package com.sajalt.converter.core.ocr

import android.graphics.Bitmap
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import java.io.InputStream

/**
 * Thin wrapper around Tesseract4Android (libtesseract via JNI). Every call here is synchronous,
 * local, in-process native code — there is no code path in this file, or anywhere it is called
 * from, that touches the network. Per the spec, language data may be bundled in
 * `assets/tessdata/` at build time OR supplied by the user at runtime via a system file picker
 * (see [installLanguageDataFromStream]); this app bundles none by default (a `.traineddata` file
 * is tens of megabytes and is not something to fabricate or fetch into this build), so a fresh
 * install prompts the user to pick one the first time OCR is used — see OcrActivity.
 */
object OcrEngine {

    /**
     * Runs OCR on [bitmap]. [ocrDataRoot] must be a real filesystem directory (not a content://
     * Uri) containing a `tessdata/` subfolder with `<language>.traineddata` — this is a
     * requirement of the native Tesseract engine itself, not a design choice of this app.
     */
    fun recognize(bitmap: Bitmap, ocrDataRoot: File, language: String = "eng"): String {
        require(hasLanguageData(ocrDataRoot, language)) {
            "No language data at ${File(File(ocrDataRoot, "tessdata"), "$language.traineddata")}"
        }
        val api = TessBaseAPI()
        try {
            val ok = api.init(ocrDataRoot.absolutePath, language)
            check(ok) { "Tesseract failed to initialize with language data at ${ocrDataRoot.absolutePath}" }
            api.setImage(bitmap)
            return api.getUTF8Text() ?: ""
        } finally {
            api.recycle()
        }
    }

    fun hasLanguageData(ocrDataRoot: File, language: String = "eng"): Boolean =
        File(File(ocrDataRoot, "tessdata"), "$language.traineddata").isFile

    /** Copies a user-picked `.traineddata` file (read via SAF) into [ocrDataRoot]/tessdata/ once,
     *  so OCR does not require re-picking the file on every use. This is model/engine data, not
     *  user document content, so caching it locally does not conflict with the "no persistent
     *  caching of user files" requirement — see README "OCR privacy details" for the distinction. */
    fun installLanguageDataFromStream(input: InputStream, ocrDataRoot: File, language: String = "eng") {
        val tessdataDir = File(ocrDataRoot, "tessdata")
        if (!tessdataDir.exists()) tessdataDir.mkdirs()
        File(tessdataDir, "$language.traineddata").outputStream().use { out -> input.copyTo(out) }
    }

    fun removeLanguageData(ocrDataRoot: File, language: String = "eng") {
        File(File(ocrDataRoot, "tessdata"), "$language.traineddata").delete()
    }
}
