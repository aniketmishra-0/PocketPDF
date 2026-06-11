package com.renameapk.pdfzip.reader.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks WHERE uriString = :uriString ORDER BY pageIndex ASC")
    fun observeBookmarks(uriString: String): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmarks WHERE uriString = :uriString AND pageIndex = :pageIndex LIMIT 1")
    suspend fun find(uriString: String, pageIndex: Int): BookmarkEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: BookmarkEntity): Long

    @Query("DELETE FROM bookmarks WHERE uriString = :uriString AND pageIndex = :pageIndex")
    suspend fun delete(uriString: String, pageIndex: Int)

    @Query("DELETE FROM bookmarks WHERE uriString = :uriString")
    suspend fun deleteAll(uriString: String)
}

