package com.renameapk.pdfzip.reader.di

import android.content.Context
import androidx.room.Room
import com.renameapk.pdfzip.reader.data.db.AnnotationDao
import com.renameapk.pdfzip.reader.data.db.BookmarkDao
import com.renameapk.pdfzip.reader.data.db.ReaderDatabase
import com.renameapk.pdfzip.reader.data.db.RecentPdfDao
import com.renameapk.pdfzip.reader.data.db.TextIndexDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.legere.pdfiumandroid.suspend.PdfiumCoreKt
import kotlinx.coroutines.Dispatchers
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ReaderModule {
    @Provides
    @Singleton
    fun provideReaderDatabase(@ApplicationContext context: Context): ReaderDatabase =
        Room.databaseBuilder(context, ReaderDatabase::class.java, "pocket_pdf_reader.db")
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideRecentPdfDao(database: ReaderDatabase): RecentPdfDao = database.recentPdfDao()

    @Provides
    fun provideBookmarkDao(database: ReaderDatabase): BookmarkDao = database.bookmarkDao()

    @Provides
    fun provideAnnotationDao(database: ReaderDatabase): AnnotationDao = database.annotationDao()

    @Provides
    fun provideTextIndexDao(database: ReaderDatabase): TextIndexDao = database.textIndexDao()

    @Provides
    @Singleton
    fun providePdfiumCore(): PdfiumCoreKt = PdfiumCoreKt(Dispatchers.IO)
}

