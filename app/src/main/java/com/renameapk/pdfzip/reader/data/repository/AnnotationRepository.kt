package com.renameapk.pdfzip.reader.data.repository

import android.graphics.Color
import android.net.Uri
import com.renameapk.pdfzip.reader.data.db.AnnotationDao
import com.renameapk.pdfzip.reader.data.db.AnnotationEntity
import com.renameapk.pdfzip.reader.domain.AnnotationType
import com.renameapk.pdfzip.reader.domain.PdfPoint
import com.renameapk.pdfzip.reader.domain.PdfRect
import com.renameapk.pdfzip.reader.domain.ReaderAnnotation
import com.renameapk.pdfzip.reader.domain.TextSelection
import com.renameapk.pdfzip.reader.util.JsonCodecs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AnnotationRepository @Inject constructor(
    private val annotationDao: AnnotationDao,
) {
    fun observeAnnotations(uri: Uri): Flow<List<ReaderAnnotation>> =
        annotationDao.observeAnnotations(uri.toString()).map { entities -> entities.map { it.toAnnotation() } }

    suspend fun addTextMarkup(
        uri: Uri,
        selection: TextSelection,
        type: AnnotationType,
        color: Int = if (type == AnnotationType.Underline) Color.rgb(33, 150, 243) else Color.rgb(255, 214, 10),
    ): Long =
        insertAnnotation(
            uri = uri,
            pageIndex = selection.pageIndex,
            type = type,
            color = color,
            alpha = if (type == AnnotationType.Highlight) 0.38f else 0.9f,
            text = selection.text,
            note = null,
            rects = selection.rects,
            points = emptyList(),
        )

    suspend fun addNote(
        uri: Uri,
        pageIndex: Int,
        rect: PdfRect,
        note: String,
    ): Long =
        insertAnnotation(
            uri = uri,
            pageIndex = pageIndex,
            type = AnnotationType.Note,
            color = Color.rgb(255, 171, 0),
            alpha = 1f,
            text = null,
            note = note,
            rects = listOf(rect),
            points = emptyList(),
        )

    suspend fun addInkStroke(
        uri: Uri,
        pageIndex: Int,
        points: List<PdfPoint>,
        color: Int = Color.rgb(211, 47, 47),
        width: Float = 3f,
    ): Long =
        insertAnnotation(
            uri = uri,
            pageIndex = pageIndex,
            type = AnnotationType.Ink,
            color = color,
            alpha = width,
            text = null,
            note = null,
            rects = emptyList(),
            points = points,
        )

    suspend fun delete(id: Long) {
        annotationDao.delete(id)
    }

    private suspend fun insertAnnotation(
        uri: Uri,
        pageIndex: Int,
        type: AnnotationType,
        color: Int,
        alpha: Float,
        text: String?,
        note: String?,
        rects: List<PdfRect>,
        points: List<PdfPoint>,
    ): Long {
        val now = System.currentTimeMillis()
        return annotationDao.insert(
            AnnotationEntity(
                uriString = uri.toString(),
                pageIndex = pageIndex,
                type = type.name,
                color = color,
                alpha = alpha,
                text = text,
                note = note,
                rects = JsonCodecs.encodeRects(rects),
                points = JsonCodecs.encodePoints(points),
                createdAt = now,
                updatedAt = now,
            ),
        )
    }
}

