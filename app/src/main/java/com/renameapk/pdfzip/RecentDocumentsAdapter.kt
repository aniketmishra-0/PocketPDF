package com.renameapk.pdfzip

import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.renameapk.pdfzip.databinding.ItemRecentDocumentBinding
import kotlin.math.max

class RecentDocumentsAdapter(
    private val onDocumentTapped: (ReaderLibraryStore.RecentDocument) -> Unit
) : RecyclerView.Adapter<RecentDocumentsAdapter.RecentDocumentViewHolder>() {

    private val documents = mutableListOf<ReaderLibraryStore.RecentDocument>()

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

    inner class RecentDocumentViewHolder(
        private val binding: ItemRecentDocumentBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(document: ReaderLibraryStore.RecentDocument) {
            val context = binding.root.context
            val relativeTime = DateUtils.getRelativeTimeSpanString(
                document.lastOpenedAt,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS,
                DateUtils.FORMAT_ABBREV_RELATIVE
            ).toString()

            binding.recentDocumentName.text = document.displayName
            binding.recentDocumentMeta.text = context.getString(
                R.string.recent_document_meta,
                document.currentPageNumber,
                max(document.pageCount, 1),
                relativeTime
            )
            binding.resumeRecentButton.setOnClickListener {
                onDocumentTapped(document)
            }
            binding.root.setOnClickListener {
                onDocumentTapped(document)
            }
        }
    }
}
