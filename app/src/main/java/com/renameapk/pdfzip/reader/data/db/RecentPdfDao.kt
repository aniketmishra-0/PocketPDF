package com.renameapk.pdfzip.reader.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface RecentPdfDao {
    @Query("SELECT * FROM recent_pdfs ORDER BY isFavorite DESC, lastOpenedAt DESC")
    fun observeRecentPdfs(): Flow<List<RecentPdfEntity>>

    @Query("SELECT * FROM recent_pdfs WHERE uriString = :uriString LIMIT 1")
    suspend fun get(uriString: String): RecentPdfEntity?

    @Upsert
    suspend fun upsert(entity: RecentPdfEntity)

    @Query("UPDATE recent_pdfs SET lastPage = :lastPage, lastZoom = :lastZoom, lastOpenedAt = :openedAt WHERE uriString = :uriString")
    suspend fun updateProgress(uriString: String, lastPage: Int, lastZoom: Float, openedAt: Long)

    @Query("UPDATE recent_pdfs SET isFavorite = :favorite WHERE uriString = :uriString")
    suspend fun setFavorite(uriString: String, favorite: Boolean)

    @Query("UPDATE recent_pdfs SET displayName = :displayName, uriString = :newUriString WHERE uriString = :oldUriString")
    suspend fun rename(oldUriString: String, newUriString: String, displayName: String)

    @Delete
    suspend fun delete(entity: RecentPdfEntity)

    @Query("DELETE FROM recent_pdfs WHERE uriString = :uriString")
    suspend fun deleteByUri(uriString: String)
}

