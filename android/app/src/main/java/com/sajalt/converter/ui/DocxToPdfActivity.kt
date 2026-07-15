package com.sajalt.converter.ui

import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.sajalt.converter.R
import com.sajalt.converter.core.docx.DocxFormatException
import com.sajalt.converter.core.docx.DocxParser
import com.sajalt.converter.core.docx.DocxToPdfRenderer
import com.sajalt.converter.core.util.SafUtils
import com.sajalt.converter.core.util.TempFileManager
import com.sajalt.converter.databinding.ActivityDocxToPdfBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

class DocxToPdfActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDocxToPdfBinding
    private var selectedDocx: Uri? = null

    private val pickDocx = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            selectedDocx = uri
            binding.tvSelectedFile.text = SafUtils.displayNameOf(this, uri) ?: uri.lastPathSegment.orEmpty()
            binding.btnConvert.isEnabled = true
            hideStatus()
        }
    }

    private val createPdfDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) runConversion(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDocxToPdfBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnSelectDocx.setOnClickListener {
            pickDocx.launch(arrayOf("application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
        }
        binding.btnConvert.setOnClickListener {
            hideStatus()
            createPdfDocument.launch(getString(R.string.default_filename_pdf))
        }
    }

    private fun runConversion(outputUri: Uri) {
        val source = selectedDocx
        if (source == null) {
            showError(getString(R.string.error_no_file_selected))
            return
        }
        setBusy(true)
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val doc = DocxParser.parse(this@DocxToPdfActivity, source)
                    contentResolver.openOutputStream(outputUri)?.use { out ->
                        DocxToPdfRenderer.render(doc, out)
                    } ?: throw IOException(getString(R.string.error_write_failed))
                }
                showSuccess(getString(R.string.status_success_pdf_saved))
            } catch (e: DocxFormatException) {
                showError(getString(R.string.error_unsupported_docx))
            } catch (e: OutOfMemoryError) {
                showError(getString(R.string.error_out_of_memory))
            } catch (e: Exception) {
                showError(e.message ?: getString(R.string.error_generic))
            } finally {
                setBusy(false)
                TempFileManager.wipeCacheDirectory(this@DocxToPdfActivity)
            }
        }
    }

    private fun setBusy(busy: Boolean) {
        binding.btnConvert.isEnabled = !busy && selectedDocx != null
        binding.btnSelectDocx.isEnabled = !busy
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
