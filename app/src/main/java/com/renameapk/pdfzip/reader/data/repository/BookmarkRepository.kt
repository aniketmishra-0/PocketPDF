package com.renameapk.pdfzip.reader.data.repository

import android.net.Uri
import com.renameapk.pdfzip.reader.data.db.BookmarkDao
import com.renameapk.pdfzip.reader.data.db.BookmarkEntity
import com.renameapk.pdfzip.reader.domain.ReaderBookmark
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BookmarkRepository @Inject constructor(
    private val bookmarkDao: BookmarkDao,
) {
    fun observeBookmarks(uri: Uri): Flow<List<ReaderBookmark>> =
        bookmarkDao.observeBookmarks(uri.toString()).map { entities -> entities.map { it.toBookmark() } }

    suspend fun toggleBookmark(uri: Uri, pageIndex: Int): Boolean {
        val uriString = uri.toString()
        val existing = bookmarkDao.find(uriString, pageIndex)
        return if (existing == null) {
            bookmarkDao.insert(
                BookmarkEntity(
                    uriString = uriString,
                    pageIndex = pageIndex,
                    title = "Page ${pageIndex + 1}",
                    createdAt = System.currentTimeMillis(),
                ),
            )
            true
        } else {
            bookmarkDao.delete(uriString, pageIndex)
            false
        }
    }
}

