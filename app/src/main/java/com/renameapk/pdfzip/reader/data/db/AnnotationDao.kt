package com.renameapk.pdfzip.reader.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AnnotationDao {
    @Query("SELECT * FROM annotations WHERE uriString = :uriString ORDER BY pageIndex ASC, createdAt ASC")
    fun observeAnnotations(uriString: String): Flow<List<AnnotationEntity>>

    @Query("SELECT * FROM annotations WHERE uriString = :uriString AND pageIndex = :pageIndex ORDER BY createdAt ASC")
    fun observePageAnnotations(uriString: String, pageIndex: Int): Flow<List<AnnotationEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: AnnotationEntity): Long

    @Query("DELETE FROM annotations WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM annotations WHERE uriString = :uriString")
    suspend fun deleteAll(uriString: String)
}

