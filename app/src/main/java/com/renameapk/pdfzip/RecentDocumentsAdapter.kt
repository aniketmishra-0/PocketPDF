package com.renameapk.pdfzip

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.renameapk.pdfzip.databinding.ItemRecentDocumentBinding
import kotlinx.coroutines.*
import java.lang.ref.WeakReference

class RecentDocumentsAdapter(
    private val onDocumentTapped: (ReaderLibraryStore.RecentDocument) -> Unit
) : RecyclerView.Adapter<RecentDocumentsAdapter.RecentDocumentViewHolder>() {

    private val documents = mutableListOf<ReaderLibraryStore.RecentDocument>()
    private val adapterScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    fun submitDocuments(newDocuments: List<ReaderLibraryStore.RecentDocument>) {
        documents.clear()
        documents.addAll(newDocuments)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecentDocumentViewHolder {
        val binding = ItemRecentDocumentBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return RecentDocumentViewHolder(binding)
    }

    override fun onBindViewHolder(holder: RecentDocumentViewHolder, position: Int) {
        holder.bind(documents[position])
    }

    override fun getItemCount(): Int = documents.size

    fun onDestroy() {
        adapterScope.cancel()
    }

    inner class RecentDocumentViewHolder(
        private val binding: ItemRecentDocumentBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private var renderJob: Job? = null

        fun bind(document: ReaderLibraryStore.RecentDocument) {
            val context = binding.root.context
            binding.recentDocumentName.text = document.displayName
            
            val sizeBytes = LocalPdfStore.queryFileSize(context, Uri.parse(document.uriString))
            val sizeStr = sizeBytes?.let { android.text.format.Formatter.formatShortFileSize(context, it) } ?: ""
            binding.recentDocumentMeta.text = if (sizeStr.isNotEmpty()) {
                "${document.pageCount} pgs • $sizeStr"
            } else {
                "${document.pageCount} pages"
            }

            binding.root.setOnClickListener {
                onDocumentTapped(document)
            }

            renderJob?.cancel()
            binding.recentDocumentProgress.isVisible = true
            binding.recentDocumentCover.setImageResource(R.drawable.bg_document_sheet)

            val viewRef = WeakReference(binding.recentDocumentCover)
            val progressRef = WeakReference(binding.recentDocumentProgress)
            
            renderJob = adapterScope.launch {
                val bitmap = withContext(Dispatchers.IO) {
                    try {
                        val uri = Uri.parse(document.uriString)
                        val file = LocalPdfStore.requireLocalFile(context, uri)
                        if (file.exists() && file.length() > 0) {
                            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                                PdfRenderer(pfd).use { renderer ->
                                    if (renderer.pageCount > 0) {
                                        renderer.openPage(0).use { page ->
                                            val scale = 0.25f
                                            val width = (page.width * scale).toInt().coerceAtLeast(100)
                                            val height = (page.height * scale).toInt().coerceAtLeast(120)
                                            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                                            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                            bmp
                                        }
                                    } else null
                                }
                            }
                        } else null
                    } catch (e: Exception) {
                        e.printStackTrace()
                        null
                    }
                }
                
                val cover = viewRef.get()
                val progress = progressRef.get()
                if (bitmap != null && cover != null) {
                    cover.setImageBitmap(bitmap)
                }
                progress?.isVisible = false
            }
        }
    }
}
