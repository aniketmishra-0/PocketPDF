package com.renameapk.pdfzip.reader.data.repository

import android.net.Uri
import com.renameapk.pdfzip.reader.data.db.RecentPdfDao
import com.renameapk.pdfzip.reader.data.db.RecentPdfEntity
import com.renameapk.pdfzip.reader.domain.PdfDocumentInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RecentPdfRepository @Inject constructor(
    private val recentPdfDao: RecentPdfDao,
) {
    fun observeRecentPdfs(): Flow<List<PdfDocumentInfo>> =
        recentPdfDao.observeRecentPdfs().map { entities -> entities.map { it.toDocumentInfo() } }

    suspend fun get(uri: Uri): PdfDocumentInfo? =
        recentPdfDao.get(uri.toString())?.toDocumentInfo()

    suspend fun upsertOpened(
        uri: Uri,
        displayName: String,
        sizeBytes: Long?,
        pageCount: Int,
    ) {
        val now = System.currentTimeMillis()
        val existing = recentPdfDao.get(uri.toString())
        recentPdfDao.upsert(
            RecentPdfEntity(
                uriString = uri.toString(),
                displayName = displayName,
                sizeBytes = sizeBytes,
                pageCount = pageCount,
                lastPage = existing?.lastPage ?: 0,
                lastZoom = existing?.lastZoom ?: 1f,
                isFavorite = existing?.isFavorite ?: false,
                addedAt = existing?.addedAt ?: now,
                lastOpenedAt = now,
            ),
        )
    }

    suspend fun updateProgress(uri: Uri, pageIndex: Int, zoom: Float) {
        recentPdfDao.updateProgress(
            uriString = uri.toString(),
            lastPage = pageIndex.coerceAtLeast(0),
            lastZoom = zoom.coerceAtLeast(1f),
            openedAt = System.currentTimeMillis(),
        )
    }

    suspend fun toggleFavorite(uri: Uri) {
        val existing = recentPdfDao.get(uri.toString()) ?: return
        recentPdfDao.setFavorite(uri.toString(), !existing.isFavorite)
    }

    suspend fun rename(oldUri: Uri, newUri: Uri, displayName: String) {
        recentPdfDao.rename(oldUri.toString(), newUri.toString(), displayName)
    }

    suspend fun remove(uri: Uri) {
        recentPdfDao.deleteByUri(uri.toString())
    }
}

