package com.renameapk.pdfzip.reader.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        RecentPdfEntity::class,
        BookmarkEntity::class,
        AnnotationEntity::class,
        TextIndexEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class ReaderDatabase : RoomDatabase() {
    abstract fun recentPdfDao(): RecentPdfDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun annotationDao(): AnnotationDao
    abstract fun textIndexDao(): TextIndexDao
}

