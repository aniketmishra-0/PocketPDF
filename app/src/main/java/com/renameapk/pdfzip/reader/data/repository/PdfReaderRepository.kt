package com.renameapk.pdfzip.reader.data.repository

import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import com.renameapk.pdfzip.reader.domain.PdfDocumentInfo
import com.renameapk.pdfzip.reader.domain.PdfPageInfo
import com.renameapk.pdfzip.reader.domain.PdfPoint
import com.renameapk.pdfzip.reader.domain.TextSelection
import com.renameapk.pdfzip.reader.domain.TocEntry
import com.renameapk.pdfzip.reader.pdfium.PdfBitmapCache
import com.renameapk.pdfzip.reader.pdfium.PdfiumDocumentManager
import com.renameapk.pdfzip.reader.pdfium.PdfiumDocumentSession
import com.renameapk.pdfzip.reader.pdfium.TileKey
import com.renameapk.pdfzip.reader.pdfium.TilePlanner
import com.renameapk.pdfzip.reader.pdfium.TileRenderRequest
import com.renameapk.pdfzip.reader.util.UriMetadataReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PdfReaderRepository @Inject constructor(
    private val documentManager: PdfiumDocumentManager,
    private val bitmapCache: PdfBitmapCache,
    private val metadataReader: UriMetadataReader,
    private val recentPdfRepository: RecentPdfRepository,
    private val searchRepository: SearchRepository,
    private val fileActionRepository: FileActionRepository,
) {
    private var activeSession: PdfiumDocumentSession? = null

    val session: PdfiumDocumentSession?
        get() = activeSession

    suspend fun open(uri: Uri, password: String? = null): PdfDocumentInfo = withContext(Dispatchers.IO) {
        fileActionRepository.persistReadPermission(uri)
        close()
        bitmapCache.clear()
        val displayName = metadataReader.displayName(uri)
        val sizeBytes = metadataReader.sizeBytes(uri)
        val session = documentManager.open(uri, displayName, sizeBytes, password)
        activeSession = session
        val existing = recentPdfRepository.get(uri)
        recentPdfRepository.upsertOpened(uri, displayName, sizeBytes, session.pageCount)
        PdfDocumentInfo(
            uri = uri,
            displayName = displayName,
            sizeBytes = sizeBytes,
            pageCount = session.pageCount,
            lastPage = existing?.lastPage ?: 0,
            lastZoom = existing?.lastZoom ?: 1f,
            isFavorite = existing?.isFavorite ?: false,
        )
    }

    suspend fun close() {
        withContext(Dispatchers.IO) {
            runCatching { activeSession?.close() }
            activeSession = null
            bitmapCache.clear()
        }
    }

    suspend fun pageInfo(pageIndex: Int): PdfPageInfo =
        requireSession().loadPageInfo(pageIndex)

    suspend fun loadInitialPageInfos(limit: Int = 12): List<PdfPageInfo> {
        val session = requireSession()
        val safeLimit = minOf(limit, session.pageCount)
        return (0 until safeLimit).map { session.loadPageInfo(it) }
    }

    suspend fun loadAllPageInfos(onPageLoaded: suspend (PdfPageInfo) -> Unit) {
        val session = requireSession()
        for (pageIndex in 0 until session.pageCount) {
            onPageLoaded(session.loadPageInfo(pageIndex))
        }
    }

    suspend fun tableOfContents(): List<TocEntry> =
        requireSession().loadTableOfContents()

    suspend fun selectWord(pageIndex: Int, normalizedPoint: PdfPoint): TextSelection? {
        val session = requireSession()
        val indexedText = searchRepository.indexedText(session.uri, pageIndex)
        return session.selectWordAt(pageIndex, normalizedPoint, indexedText)
    }

    suspend fun renderVisibleTiles(
        pageIndex: Int,
        pageWidthPx: Int,
        pageHeightPx: Int,
        visibleRect: Rect,
        zoom: Float,
        darkMode: Boolean,
    ): List<Pair<TileKey, Bitmap>> = withContext(Dispatchers.IO) {
        val session = requireSession()
        val requests = TilePlanner.requestsForVisibleRect(
            documentKey = session.uri.toString(),
            pageIndex = pageIndex,
            pageWidthPx = pageWidthPx,
            pageHeightPx = pageHeightPx,
            visibleRect = visibleRect,
            zoom = zoom,
            darkMode = darkMode,
        )
        requests.mapNotNull { request ->
            val cached = bitmapCache.getTile(request.key)
            if (cached != null) {
                request.key to cached
            } else {
                val rendered = session.renderTile(request)
                if (rendered == null) {
                    bitmapCache.trimAggressively()
                    null
                } else {
                    bitmapCache.putTile(request.key, rendered)
                    request.key to rendered
                }
            }
        }
    }

    suspend fun renderThumbnail(pageIndex: Int, widthPx: Int = 168): Bitmap? = withContext(Dispatchers.IO) {
        val session = requireSession()
        bitmapCache.getThumbnail(session.uri.toString(), pageIndex)?.let { return@withContext it }
        val info = session.loadPageInfo(pageIndex)
        val heightPx = (widthPx / info.aspectRatio).toInt().coerceAtLeast(widthPx)
        val key = TileKey(
            documentKey = "${session.uri}:thumb",
            pageIndex = pageIndex,
            zoomBucket = 100,
            pageWidthPx = widthPx,
            pageHeightPx = heightPx,
            left = 0,
            top = 0,
            width = widthPx,
            height = heightPx,
        )
        val request = TileRenderRequest(
            key = key,
            pageIndex = pageIndex,
            pageWidthPx = widthPx,
            pageHeightPx = heightPx,
            left = 0,
            top = 0,
            width = widthPx,
            height = heightPx,
            darkMode = false,
        )
        session.renderTile(request)?.also { bitmapCache.putThumbnail(session.uri.toString(), pageIndex, it) }
    }

    suspend fun updateProgress(pageIndex: Int, zoom: Float) {
        val session = activeSession ?: return
        recentPdfRepository.updateProgress(session.uri, pageIndex, zoom)
    }

    private fun requireSession(): PdfiumDocumentSession =
        checkNotNull(activeSession) { "No PDF document is currently open" }
}

