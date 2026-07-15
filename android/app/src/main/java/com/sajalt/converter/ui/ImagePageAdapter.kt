package com.sajalt.converter.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.sajalt.converter.core.util.SafUtils
import com.sajalt.converter.databinding.ItemImagePageBinding

/**
 * Owns the ordered list of picked images for Image-to-PDF. The activity never mutates a list
 * directly — it calls [addItems]/[removeAt]/[clear], and reads the current order back via
 * [currentItems] only when the user taps Convert.
 */
class ImagePageAdapter(
    private val context: Context,
    private val onListChanged: (newSize: Int) -> Unit
) : RecyclerView.Adapter<ImagePageAdapter.ViewHolder>() {

    private val items = mutableListOf<Uri>()

    fun currentItems(): List<Uri> = items.toList()

    fun addItems(newItems: List<Uri>) {
        val start = items.size
        items.addAll(newItems)
        notifyItemRangeInserted(start, newItems.size)
        onListChanged(items.size)
    }

    fun clear() {
        val size = items.size
        items.clear()
        notifyItemRangeRemoved(0, size)
        onListChanged(items.size)
    }

    fun removeAt(position: Int) {
        if (position !in items.indices) return
        items.removeAt(position)
        notifyItemRemoved(position)
        if (position < items.size) notifyItemRangeChanged(position, items.size - position) // page numbers below shift up by one
        onListChanged(items.size)
    }

    fun moveUp(position: Int) {
        if (position <= 0 || position >= items.size) return
        swap(position, position - 1)
    }

    fun moveDown(position: Int) {
        if (position < 0 || position >= items.size - 1) return
        swap(position, position + 1)
    }

    private fun swap(a: Int, b: Int) {
        val tmp = items[a]
        items[a] = items[b]
        items[b] = tmp
        notifyItemChanged(a)
        notifyItemChanged(b)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemImagePageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(items[position], position, items.size)

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(private val binding: ItemImagePageBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(uri: Uri, position: Int, total: Int) {
            binding.tvPageNumber.text = (position + 1).toString()
            binding.tvFileName.text = SafUtils.displayNameOf(context, uri) ?: uri.lastPathSegment.orEmpty()
            binding.ivThumbnail.setImageBitmap(decodeThumbnail(uri))

            binding.btnMoveUp.isEnabled = position > 0
            binding.btnMoveUp.alpha = if (position > 0) 1f else 0.3f
            binding.btnMoveDown.isEnabled = position < total - 1
            binding.btnMoveDown.alpha = if (position < total - 1) 1f else 0.3f

            binding.btnMoveUp.setOnClickListener { moveUp(bindingAdapterPosition) }
            binding.btnMoveDown.setOnClickListener { moveDown(bindingAdapterPosition) }
            binding.btnRemove.setOnClickListener { removeAt(bindingAdapterPosition) }
        }

        // Thumbnail only: a small, memory-cheap preview. The actual PDF conversion re-reads the
        // original Uri at full resolution — this downsampled copy is never used for output.
        private fun decodeThumbnail(uri: Uri): Bitmap? = try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            val targetPx = 200
            if (bounds.outWidth > 0) {
                while (bounds.outWidth / (sample * 2) >= targetPx) sample *= 2
            }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            }
        } catch (_: Exception) {
            null
        }
    }
}
