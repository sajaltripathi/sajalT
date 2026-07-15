package com.sajalt.converter.ui

import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.sajalt.converter.R
import com.sajalt.converter.core.image.ImageCompressor
import com.sajalt.converter.core.util.SafUtils
import com.sajalt.converter.core.util.SizeParser
import com.sajalt.converter.core.util.TempFileManager
import com.sajalt.converter.databinding.ActivityImageCompressBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

class ImageCompressActivity : AppCompatActivity() {

    private lateinit var binding: ActivityImageCompressBinding
    private var selectedImage: Uri? = null
    private var pendingTargetBytes: Long = PRESET_100KB_BYTES

    private val pickImage = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            selectedImage = uri
            binding.tvSelectedFile.text = SafUtils.displayNameOf(this, uri) ?: uri.lastPathSegment.orEmpty()
            binding.ivPreview.visibility = View.VISIBLE
            binding.ivPreview.setImageURI(uri)
            SafUtils.sizeOf(this, uri)?.let { size ->
                binding.tvOriginalSize.visibility = View.VISIBLE
                binding.tvOriginalSize.text = getString(R.string.compress_original_size_format, SizeParser.formatBytes(size))
            }
            binding.btnCompress.isEnabled = true
            hideStatus()
        }
    }

    private val createJpegDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("image/jpeg")) { uri ->
        if (uri != null) runCompression(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityImageCompressBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnSelectImage.setOnClickListener { pickImage.launch(arrayOf("image/*")) }

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
            createJpegDocument.launch(getString(R.string.default_filename_jpg))
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
        val source = selectedImage
        if (source == null) {
            showError(getString(R.string.error_no_file_selected))
            return
        }
        setBusy(true)
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val compressed = ImageCompressor.compress(this@ImageCompressActivity, source, pendingTargetBytes)
                    contentResolver.openOutputStream(outputUri)?.use { out -> out.write(compressed.bytes) }
                        ?: throw IOException(getString(R.string.error_write_failed))
                    compressed
                }
                val resultText = getString(
                    R.string.compress_result_format,
                    SizeParser.formatBytes(result.achievedSizeBytes),
                    SizeParser.formatBytes(result.targetSizeBytes)
                )
                val warningParts = buildList {
                    if (!result.reachedTarget) add(getString(R.string.compress_could_not_reach_target))
                    if (result.hadTransparency) add(getString(R.string.compress_alpha_warning))
                }
                if (warningParts.isEmpty()) {
                    showSuccess(resultText)
                } else {
                    showWarning((listOf(resultText) + warningParts).joinToString("\n"))
                }
            } catch (e: OutOfMemoryError) {
                showError(getString(R.string.error_out_of_memory))
            } catch (e: Exception) {
                showError(e.message ?: getString(R.string.error_generic))
            } finally {
                setBusy(false)
                TempFileManager.wipeCacheDirectory(this@ImageCompressActivity)
            }
        }
    }

    private fun setBusy(busy: Boolean) {
        binding.btnCompress.isEnabled = !busy && selectedImage != null
        binding.btnSelectImage.isEnabled = !busy
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
