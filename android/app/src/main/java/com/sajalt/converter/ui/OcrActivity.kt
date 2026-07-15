package com.sajalt.converter.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.sajalt.converter.R
import com.sajalt.converter.core.ocr.OcrEngine
import com.sajalt.converter.core.pdf.PdfPageRasterizer
import com.sajalt.converter.core.util.SafUtils
import com.sajalt.converter.core.util.TempFileManager
import com.sajalt.converter.databinding.ActivityOcrBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

class OcrActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOcrBinding
    private var selectedSource: Uri? = null
    private var selectedIsPdf: Boolean = false

    private val pickLanguageData = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) installLanguageData(uri)
    }

    private val pickSource = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onSourcePicked(uri)
    }

    private val saveText = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) {
            try {
                SafUtils.writeText(this, uri, binding.etRecognizedText.text?.toString().orEmpty())
                showSuccess(getString(R.string.status_success_text_saved))
            } catch (e: Exception) {
                showError(e.message ?: getString(R.string.error_write_failed))
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOcrBinding.inflate(layoutInflater)
        setContentView(binding.root)

        refreshLanguageDataStatus()

        binding.btnLanguageData.setOnClickListener {
            if (OcrEngine.hasLanguageData(filesDir)) {
                OcrEngine.removeLanguageData(filesDir)
                refreshLanguageDataStatus()
            } else {
                pickLanguageData.launch(arrayOf("*/*"))
            }
        }

        binding.btnSelectSource.setOnClickListener {
            pickSource.launch(arrayOf("image/*", "application/pdf"))
        }

        binding.btnRunOcr.setOnClickListener { runOcr() }
        binding.btnCopyText.setOnClickListener { copyText() }
        binding.btnSaveText.setOnClickListener { saveText.launch(getString(R.string.default_filename_txt)) }
    }

    private fun onSourcePicked(uri: Uri) {
        selectedSource = uri
        selectedIsPdf = contentResolver.getType(uri) == "application/pdf"
        binding.pageNumberLayout.visibility = if (selectedIsPdf) View.VISIBLE else View.GONE
        binding.tvSelectedFile.text = SafUtils.displayNameOf(this, uri) ?: uri.lastPathSegment.orEmpty()
        binding.btnRunOcr.isEnabled = OcrEngine.hasLanguageData(filesDir)
        hideStatus()
    }

    private fun installLanguageData(uri: Uri) {
        setBusy(true)
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { input ->
                        OcrEngine.installLanguageDataFromStream(input, filesDir)
                    } ?: throw IOException(getString(R.string.error_read_failed))
                }
                refreshLanguageDataStatus()
                hideStatus()
            } catch (e: Exception) {
                showError(e.message ?: getString(R.string.error_generic))
            } finally {
                setBusy(false)
            }
        }
    }

    private fun refreshLanguageDataStatus() {
        val ready = OcrEngine.hasLanguageData(filesDir)
        binding.tvLanguageDataStatus.text = getString(
            R.string.ocr_language_data_status_format,
            if (ready) getString(R.string.ocr_language_data_ready, "eng") else getString(R.string.ocr_language_data_none)
        )
        binding.btnLanguageData.text = getString(
            if (ready) R.string.btn_remove_language_data else R.string.btn_select_language_data
        )
        binding.btnRunOcr.isEnabled = ready && selectedSource != null
    }

    private fun runOcr() {
        val source = selectedSource
        if (source == null) {
            showError(getString(R.string.error_no_file_selected))
            return
        }
        if (!OcrEngine.hasLanguageData(filesDir)) {
            showError(getString(R.string.error_no_language_data))
            return
        }
        setBusy(true)
        lifecycleScope.launch {
            try {
                val text = withContext(Dispatchers.IO) {
                    val bitmap = loadBitmapForOcr(source)
                    OcrEngine.recognize(bitmap, filesDir)
                }
                binding.etRecognizedText.setText(text)
                val hasText = text.isNotBlank()
                binding.btnCopyText.isEnabled = hasText
                binding.btnSaveText.isEnabled = hasText
                hideStatus()
            } catch (e: OutOfMemoryError) {
                showError(getString(R.string.error_out_of_memory))
            } catch (e: Exception) {
                showError(e.message ?: getString(R.string.error_generic))
            } finally {
                setBusy(false)
                TempFileManager.wipeCacheDirectory(this@OcrActivity)
            }
        }
    }

    private fun loadBitmapForOcr(uri: Uri): Bitmap {
        return if (selectedIsPdf) {
            val pageNumberOneIndexed = binding.etPageNumber.text?.toString()?.toIntOrNull()?.coerceAtLeast(1) ?: 1
            PdfPageRasterizer.renderPage(this, uri, pageNumberOneIndexed - 1, dpi = 250f)
        } else {
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                ?: throw IOException(getString(R.string.error_read_failed))
        }
    }

    private fun copyText() {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("Recognized text", binding.etRecognizedText.text))
        showSuccess(getString(R.string.status_copied))
    }

    private fun setBusy(busy: Boolean) {
        binding.btnRunOcr.isEnabled = !busy && selectedSource != null && OcrEngine.hasLanguageData(filesDir)
        binding.btnSelectSource.isEnabled = !busy
        binding.btnLanguageData.isEnabled = !busy
        if (busy) {
            binding.tvStatus.visibility = View.VISIBLE
            binding.tvStatus.text = getString(R.string.ocr_processing)
            binding.tvStatus.setTextColor(getColor(R.color.on_surface_muted))
        }
    }

    private fun showSuccess(message: String) {
        binding.tvStatus.visibility = View.VISIBLE
        binding.tvStatus.text = message
        binding.tvStatus.setTextColor(getColor(R.color.success))
    }

    private fun showError(message: String) {
        binding.tvStatus.visibility = View.VISIBLE
        binding.tvStatus.text = message
        binding.tvStatus.setTextColor(getColor(R.color.error))
    }

    private fun hideStatus() {
        binding.tvStatus.visibility = View.GONE
    }
}
