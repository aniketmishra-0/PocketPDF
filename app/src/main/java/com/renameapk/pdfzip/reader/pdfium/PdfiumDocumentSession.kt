package com.renameapk.pdfzip.reader.pdfium

import android.graphics.RectF
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.renameapk.pdfzip.reader.domain.PdfPageInfo
import com.renameapk.pdfzip.reader.domain.PdfPoint
import com.renameapk.pdfzip.reader.domain.PdfRect
import com.renameapk.pdfzip.reader.domain.TextSelection
import com.renameapk.pdfzip.reader.domain.TocEntry
import io.legere.pdfiumandroid.suspend.PdfDocumentKt
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min

class PdfiumDocumentSession(
    val uri: Uri,
    val displayName: String,
    val sizeBytes: Long?,
    private val fileDescriptor: ParcelFileDescriptor,
    private val document: PdfDocumentKt,
    val pageCount: Int,
) : Closeable {
    private val pageInfoCache = ConcurrentHashMap<Int, PdfPageInfo>()

    suspend fun getPageInfo(pageIndex: Int): PdfPageInfo =
        pageInfoCache.getOrPut(pageIndex) {
            error("Page info must be loaded with loadPageInfo")
        }

    suspend fun loadPageInfo(pageIndex: Int): PdfPageInfo {
        pageInfoCache[pageIndex]?.let { return it }
        val page = document.openPage(pageIndex)
        return try {
            PdfPageInfo(
                pageIndex = pageIndex,
                widthPoints = page.getPageWidthPoint().coerceAtLeast(1),
                heightPoints = page.getPageHeightPoint().coerceAtLeast(1),
                rotation = page.getPageRotation().coerceAtLeast(0),
            ).also { pageInfoCache[pageIndex] = it }
        } finally {
            page.safeClose()
        }
    }

    suspend fun loadTableOfContents(): List<TocEntry> {
        val entries = mutableListOf<TocEntry>()
        fun flatten(bookmarks: List<io.legere.pdfiumandroid.PdfDocument.Bookmark>, level: Int) {
            bookmarks.forEach { bookmark ->
                val page = bookmark.pageIdx.toInt().coerceIn(0, (pageCount - 1).coerceAtLeast(0))
                val title = bookmark.title?.takeIf { it.isNotBlank() } ?: "Page ${page + 1}"
                entries += TocEntry(title = title, pageIndex = page, level = level)
                flatten(bookmark.children, level + 1)
            }
        }
        flatten(document.getTableOfContents(), 0)
        return entries
    }

    suspend fun extractPageText(pageIndex: Int): String {
        val page = document.openPage(pageIndex)
        val textPage = page.openTextPage()
        return try {
            val count = textPage.textPageCountChars().coerceAtLeast(0)
            textPage.textPageGetText(0, count).orEmpty()
                .replace('\u0000', ' ')
                .trim()
        } finally {
            textPage.safeClose()
            page.safeClose()
        }
    }

    suspend fun textRects(pageIndex: Int, startIndex: Int, length: Int): List<PdfRect> {
        if (length <= 0) return emptyList()
        val pageInfo = loadPageInfo(pageIndex)
        val page = document.openPage(pageIndex)
        val textPage = page.openTextPage()
        return try {
            val rectCount = textPage.textPageCountRects(startIndex, length).coerceAtLeast(0)
            (0 until rectCount).mapNotNull { rectIndex ->
                textPage.textPageGetRect(rectIndex)?.toNormalizedPdfRect(pageInfo)
            }
        } finally {
            textPage.safeClose()
            page.safeClose()
        }
    }

    suspend fun selectWordAt(
        pageIndex: Int,
        normalizedPoint: PdfPoint,
        indexedText: String?,
    ): TextSelection? {
        val pageInfo = loadPageInfo(pageIndex)
        val page = document.openPage(pageIndex)
        val textPage = page.openTextPage()
        return try {
            val text = indexedText?.takeIf { it.isNotBlank() } ?: extractPageText(pageIndex)
            if (text.isBlank()) return null
            val pageX = normalizedPoint.x.coerceIn(0f, 1f) * pageInfo.widthPoints
            val pageY = (1f - normalizedPoint.y.coerceIn(0f, 1f)) * pageInfo.heightPoints
            val charIndex = textPage.textPageGetCharIndexAtPos(
                pageX.toDouble(),
                pageY.toDouble(),
                pageInfo.widthPoints * 0.02,
                pageInfo.heightPoints * 0.02,
            )
            if (charIndex !in text.indices) return null
            val range = expandSelectionToWord(text, charIndex)
            val rects = textRects(pageIndex, range.first, range.last - range.first)
            TextSelection(
                pageIndex = pageIndex,
                text = text.substring(range.first, range.last).trim(),
                startIndex = range.first,
                length = range.last - range.first,
                rects = rects,
            ).takeIf { it.text.isNotBlank() }
        } finally {
            textPage.safeClose()
            page.safeClose()
        }
    }

    internal suspend fun renderTile(request: TileRenderRequest): android.graphics.Bitmap? {
        val page = document.openPage(request.pageIndex)
        return try {
            PdfTileRenderer.renderPageTile(page, request)
        } finally {
            page.safeClose()
        }
    }

    override fun close() {
        runCatching { document.safeClose() }
        runCatching { fileDescriptor.close() }
    }

    private fun RectF.toNormalizedPdfRect(pageInfo: PdfPageInfo): PdfRect {
        val pageWidth = pageInfo.widthPoints.toFloat().coerceAtLeast(1f)
        val pageHeight = pageInfo.heightPoints.toFloat().coerceAtLeast(1f)
        val normalizedLeft = (left / pageWidth).coerceIn(0f, 1f)
        val normalizedRight = (right / pageWidth).coerceIn(0f, 1f)
        val normalizedTop = (1f - (top / pageHeight)).coerceIn(0f, 1f)
        val normalizedBottom = (1f - (bottom / pageHeight)).coerceIn(0f, 1f)
        return PdfRect(
            left = min(normalizedLeft, normalizedRight),
            top = min(normalizedTop, normalizedBottom),
            right = max(normalizedLeft, normalizedRight),
            bottom = max(normalizedTop, normalizedBottom),
        )
    }

    private fun expandSelectionToWord(text: String, index: Int): IntRange {
        var start = index
        var end = index + 1
        while (start > 0 && text[start - 1].isLetterOrDigit()) start--
        while (end < text.length && text[end].isLetterOrDigit()) end++
        if (start == end) end = (start + 1).coerceAtMost(text.length)
        return start until end
    }
}

