package com.renameapk.pdfzip.reader.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface TextIndexDao {
    @Query("SELECT COUNT(*) FROM page_text_index WHERE uriString = :uriString")
    fun observeIndexedPageCount(uriString: String): Flow<Int>

    @Query("SELECT * FROM page_text_index WHERE uriString = :uriString AND pageIndex = :pageIndex LIMIT 1")
    suspend fun getPageText(uriString: String, pageIndex: Int): TextIndexEntity?

    @Query("SELECT * FROM page_text_index WHERE uriString = :uriString AND text LIKE '%' || :query || '%' ORDER BY pageIndex ASC")
    suspend fun search(uriString: String, query: String): List<TextIndexEntity>

    @Upsert
    suspend fun upsert(entity: TextIndexEntity)

    @Query("DELETE FROM page_text_index WHERE uriString = :uriString")
    suspend fun clear(uriString: String)
}

