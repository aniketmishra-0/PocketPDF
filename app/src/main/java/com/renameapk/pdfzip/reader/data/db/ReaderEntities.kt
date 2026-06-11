package com.renameapk.pdfzip.reader.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "recent_pdfs")
data class RecentPdfEntity(
    @PrimaryKey val uriString: String,
    val displayName: String,
    val sizeBytes: Long?,
    val pageCount: Int,
    val lastPage: Int,
    val lastZoom: Float,
    val isFavorite: Boolean,
    val addedAt: Long,
    val lastOpenedAt: Long,
)

@Entity(
    tableName = "bookmarks",
    indices = [Index(value = ["uriString", "pageIndex"], unique = true)],
)
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uriString: String,
    val pageIndex: Int,
    val title: String,
    val createdAt: Long,
)

@Entity(
    tableName = "annotations",
    indices = [Index(value = ["uriString", "pageIndex"])],
)
data class AnnotationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uriString: String,
    val pageIndex: Int,
    val type: String,
    val color: Int,
    val alpha: Float,
    val text: String?,
    val note: String?,
    val rects: String?,
    val points: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "page_text_index",
    primaryKeys = ["uriString", "pageIndex"],
    indices = [Index(value = ["uriString"])],
)
data class TextIndexEntity(
    val uriString: String,
    val pageIndex: Int,
    val text: String,
    val indexedAt: Long,
)

