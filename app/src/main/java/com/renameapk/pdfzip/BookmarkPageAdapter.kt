package com.renameapk.pdfzip

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.renameapk.pdfzip.databinding.ItemBookmarkPageBinding

class BookmarkPageAdapter(
    private val onBookmarkTapped: (Int) -> Unit
) : RecyclerView.Adapter<BookmarkPageAdapter.BookmarkPageViewHolder>() {

    private val pages = mutableListOf<Int>()

    fun submitPages(newPages: List<Int>) {
        pages.clear()
        pages.addAll(newPages.sorted())
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BookmarkPageViewHolder {
        val binding = ItemBookmarkPageBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return BookmarkPageViewHolder(binding)
    }

    override fun onBindViewHolder(holder: BookmarkPageViewHolder, position: Int) {
        holder.bind(pages[position])
    }

    override fun getItemCount(): Int = pages.size

    inner class BookmarkPageViewHolder(
        private val binding: ItemBookmarkPageBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(pageIndex: Int) {
            binding.bookmarkPageLabel.text = binding.root.context.getString(
                R.string.viewer_page_short,
                pageIndex + 1
            )
            binding.root.setOnClickListener {
                onBookmarkTapped(pageIndex)
            }
        }
    }
}
