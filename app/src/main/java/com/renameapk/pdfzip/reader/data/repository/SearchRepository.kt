package com.renameapk.pdfzip.reader.data.repository

import android.net.Uri
import com.renameapk.pdfzip.reader.data.db.TextIndexDao
import com.renameapk.pdfzip.reader.data.db.TextIndexEntity
import com.renameapk.pdfzip.reader.domain.SearchHit
import com.renameapk.pdfzip.reader.pdfium.PdfiumDocumentSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SearchRepository @Inject constructor(
    private val textIndexDao: TextIndexDao,
) {
    fun observeIndexedPageCount(uri: Uri): Flow<Int> =
        textIndexDao.observeIndexedPageCount(uri.toString())

    suspend fun indexedText(uri: Uri, pageIndex: Int): String? =
        textIndexDao.getPageText(uri.toString(), pageIndex)?.text

    suspend fun ensureIndexed(
        session: PdfiumDocumentSession,
        onProgress: suspend (indexedPages: Int, pageCount: Int) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val uriString = session.uri.toString()
        var indexed = 0
        for (pageIndex in 0 until session.pageCount) {
            val existing = textIndexDao.getPageText(uriString, pageIndex)
            if (existing == null) {
                val text = try {
                    session.extractPageText(pageIndex)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: RuntimeException) {
                    ""
                }
                textIndexDao.upsert(
                    TextIndexEntity(
                        uriString = uriString,
                        pageIndex = pageIndex,
                        text = text,
                        indexedAt = System.currentTimeMillis(),
                    ),
                )
            }
            indexed++
            onProgress(indexed, session.pageCount)
        }
    }

    suspend fun search(
        session: PdfiumDocumentSession,
        query: String,
        limit: Int = 500,
    ): List<SearchHit> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.length < 2) return@withContext emptyList()
        val indexedPages = textIndexDao.search(session.uri.toString(), trimmed)
        val hits = mutableListOf<SearchHit>()
        indexedPages.forEach { page ->
            if (hits.size >= limit) return@forEach
            page.text.findAll(trimmed).take(limit - hits.size).forEach { range ->
                val length = range.last - range.first + 1
                val rects = session.textRects(page.pageIndex, range.first, length)
                hits += SearchHit(
                    id = "${page.pageIndex}:${range.first}:$length",
                    pageIndex = page.pageIndex,
                    startIndex = range.first,
                    length = length,
                    preview = page.text.previewAround(range.first, length),
                    rects = rects,
                )
            }
        }
        hits
    }

    private fun String.findAll(query: String): Sequence<IntRange> =
        sequence {
            var start = 0
            while (start < length) {
                val found = indexOf(query, startIndex = start, ignoreCase = true)
                if (found < 0) break
                yield(found until found + query.length)
                start = found + query.length
            }
        }

    private fun String.previewAround(start: Int, length: Int): String {
        val from = (start - 48).coerceAtLeast(0)
        val to = (start + length + 72).coerceAtMost(this.length)
        return substring(from, to).replace(Regex("\\s+"), " ").trim()
    }
}
