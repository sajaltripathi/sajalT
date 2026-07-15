package com.sajalt.converter.ui

import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.sajalt.converter.R
import com.sajalt.converter.core.pdf.PdfCompressor
import com.sajalt.converter.core.util.SafUtils
import com.sajalt.converter.core.util.SizeParser
import com.sajalt.converter.core.util.TempFileManager
import com.sajalt.converter.databinding.ActivityPdfCompressBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

class PdfCompressActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPdfCompressBinding
    private var selectedPdf: Uri? = null
    private var pendingTargetBytes: Long = PRESET_100KB_BYTES

    private val pickPdf = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            selectedPdf = uri
            binding.tvSelectedFile.text = SafUtils.displayNameOf(this, uri) ?: uri.lastPathSegment.orEmpty()
            SafUtils.sizeOf(this, uri)?.let { size ->
                binding.tvOriginalSize.visibility = View.VISIBLE
                binding.tvOriginalSize.text = getString(R.string.compress_original_size_format, SizeParser.formatBytes(size))
            }
            binding.btnCompress.isEnabled = true
            hideStatus()
        }
    }

    private val createPdfDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) runCompression(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPdfCompressBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnSelectPdf.setOnClickListener { pickPdf.launch(arrayOf("application/pdf")) }

        binding.radioGroupTarget.setOnCheckedChangeListener { _, checkedId ->
            binding.customTargetLayout.visibility = if (checkedId == binding.radioCustom.id) View.VISIBLE else View.GONE
        }

        binding.btnCompress.setOnClickListener {
            hideStatus()
            val target = resolveTargetBytes()
            if (target == null) {
                showError(getString(R.string.error_invalid_target_size))
                return@setOnClickListener
            }
            pendingTargetBytes = target
            createPdfDocument.launch(getString(R.string.default_filename_pdf))
        }
    }

    private fun resolveTargetBytes(): Long? {
        return if (binding.radioGroupTarget.checkedRadioButtonId == binding.radioCustom.id) {
            SizeParser.parseToBytes(binding.etCustomTarget.text?.toString().orEmpty())
        } else {
            PRESET_100KB_BYTES
        }
    }

    private fun runCompression(outputUri: Uri) {
        val source = selectedPdf
        if (source == null) {
            showError(getString(R.string.error_no_file_selected))
            return
        }
        setBusy(true)
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val compressed = PdfCompressor.compress(this@PdfCompressActivity, source, pendingTargetBytes)
                    contentResolver.openOutputStream(outputUri)?.use { out -> out.write(compressed.bytes) }
                        ?: throw IOException(getString(R.string.error_write_failed))
                    compressed
                }
                val resultText = getString(
                    R.string.compress_result_format,
                    SizeParser.formatBytes(result.achievedSizeBytes),
                    SizeParser.formatBytes(result.targetSizeBytes)
                )
                if (result.reachedTarget) {
                    showSuccess(resultText)
                } else {
                    showWarning("$resultText\n${getString(R.string.compress_could_not_reach_target)}")
                }
            } catch (e: OutOfMemoryError) {
                showError(getString(R.string.error_out_of_memory))
            } catch (e: Exception) {
                showError(e.message ?: getString(R.string.error_unsupported_pdf))
            } finally {
                setBusy(false)
                TempFileManager.wipeCacheDirectory(this@PdfCompressActivity)
            }
        }
    }

    private fun setBusy(busy: Boolean) {
        binding.btnCompress.isEnabled = !busy && selectedPdf != null
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

    private fun showWarning(message: String) {
        binding.tvStatus.visibility = View.VISIBLE
        binding.tvStatus.text = message
        binding.tvStatus.setTextColor(getColor(R.color.warning))
    }

    private fun showError(message: String) {
        binding.tvStatus.visibility = View.VISIBLE
        binding.tvStatus.text = message
        binding.tvStatus.setTextColor(getColor(R.color.error))
    }

    private fun hideStatus() {
        binding.tvStatus.visibility = View.GONE
    }

    companion object {
        private const val PRESET_100KB_BYTES = 100L * 1024L
    }
}
