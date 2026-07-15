package com.sajalt.converter.ui

import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.sajalt.converter.R
import com.sajalt.converter.core.pdf.ImageToPdfConverter
import com.sajalt.converter.core.util.TempFileManager
import com.sajalt.converter.databinding.ActivityImageToPdfBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

class ImageToPdfActivity : AppCompatActivity() {

    private lateinit var binding: ActivityImageToPdfBinding
    private lateinit var adapter: ImagePageAdapter

    // A single picker launched by one button, supporting single or multiple selection in the
    // same system dialog — exactly what the spec asks for ("one button ... same picker flow").
    private val pickImages = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) adapter.addItems(uris)
    }

    private val createPdfDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) runConversion(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityImageToPdfBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = ImagePageAdapter(this) { size -> onListSizeChanged(size) }
        binding.recyclerPages.layoutManager = LinearLayoutManager(this)
        binding.recyclerPages.adapter = adapter

        binding.btnSelectImages.setOnClickListener { pickImages.launch(arrayOf("image/*")) }
        binding.btnClearSelection.setOnClickListener { adapter.clear(); hideStatus() }
        binding.btnConvert.setOnClickListener {
            hideStatus()
            createPdfDocument.launch(getString(R.string.default_filename_pdf))
        }

        onListSizeChanged(0)
    }

    private fun onListSizeChanged(size: Int) {
        binding.tvEmptyState.visibility = if (size == 0) View.VISIBLE else View.GONE
        binding.recyclerPages.visibility = if (size == 0) View.GONE else View.VISIBLE
        binding.tvPageCount.visibility = if (size == 0) View.GONE else View.VISIBLE
        binding.tvPageCount.text = getString(R.string.imgtopdf_page_count_format, size)
        binding.btnConvert.isEnabled = size > 0
    }

    private fun runConversion(outputUri: Uri) {
        val images = adapter.currentItems()
        if (images.isEmpty()) {
            showError(getString(R.string.error_no_pages))
            return
        }
        setBusy(true)
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(outputUri)?.use { out ->
                        ImageToPdfConverter.convert(this@ImageToPdfActivity, images, out)
                    } ?: throw IOException(getString(R.string.error_write_failed))
                }
                showSuccess(getString(R.string.status_success_pdf_saved))
            } catch (e: OutOfMemoryError) {
                showError(getString(R.string.error_out_of_memory))
            } catch (e: Exception) {
                showError(e.message ?: getString(R.string.error_generic))
            } finally {
                setBusy(false)
                TempFileManager.wipeCacheDirectory(this@ImageToPdfActivity)
            }
        }
    }

    private fun setBusy(busy: Boolean) {
        binding.btnConvert.isEnabled = !busy && adapter.itemCount > 0
        binding.btnSelectImages.isEnabled = !busy
        binding.btnClearSelection.isEnabled = !busy
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
