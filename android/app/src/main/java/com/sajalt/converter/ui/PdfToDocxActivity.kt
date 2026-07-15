package com.sajalt.converter.ui

import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.sajalt.converter.R
import com.sajalt.converter.core.docx.PdfToDocxConverter
import com.sajalt.converter.core.util.SafUtils
import com.sajalt.converter.core.util.TempFileManager
import com.sajalt.converter.databinding.ActivityPdfToDocxBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

class PdfToDocxActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPdfToDocxBinding
    private var selectedPdf: Uri? = null

    private val pickPdf = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            selectedPdf = uri
            binding.tvSelectedFile.text = SafUtils.displayNameOf(this, uri) ?: uri.lastPathSegment.orEmpty()
            binding.btnConvert.isEnabled = true
            hideStatus()
        }
    }

    private val createDocxDocument = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.wordprocessingml.document")
    ) { uri ->
        if (uri != null) runConversion(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPdfToDocxBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnSelectPdf.setOnClickListener { pickPdf.launch(arrayOf("application/pdf")) }
        binding.btnConvert.setOnClickListener {
            hideStatus()
            createDocxDocument.launch(getString(R.string.default_filename_docx))
        }
    }

    private fun runConversion(outputUri: Uri) {
        val source = selectedPdf
        if (source == null) {
            showError(getString(R.string.error_no_file_selected))
            return
        }
        setBusy(true)
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(outputUri)?.use { out ->
                        PdfToDocxConverter.convert(this@PdfToDocxActivity, source, out)
                    } ?: throw IOException(getString(R.string.error_write_failed))
                }
                showSuccess(getString(R.string.status_success_docx_saved))
            } catch (e: OutOfMemoryError) {
                showError(getString(R.string.error_out_of_memory))
            } catch (e: Exception) {
                showError(e.message ?: getString(R.string.error_unsupported_pdf))
            } finally {
                setBusy(false)
                TempFileManager.wipeCacheDirectory(this@PdfToDocxActivity)
            }
        }
    }

    private fun setBusy(busy: Boolean) {
        binding.btnConvert.isEnabled = !busy && selectedPdf != null
        binding.btnSelectPdf.isEnabled = !busy
        if (busy) {
            binding.tvStatus.visibility = View.VISIBLE
            binding.tvStatus.text = getString(R.string.status_working)
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
