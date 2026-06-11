package com.renameapk.pdfzip.reader.data.repository

import android.net.Uri
import com.renameapk.pdfzip.reader.data.db.AnnotationEntity
import com.renameapk.pdfzip.reader.data.db.BookmarkEntity
import com.renameapk.pdfzip.reader.data.db.RecentPdfEntity
import com.renameapk.pdfzip.reader.domain.AnnotationType
import com.renameapk.pdfzip.reader.domain.PdfDocumentInfo
import com.renameapk.pdfzip.reader.domain.ReaderAnnotation
import com.renameapk.pdfzip.reader.domain.ReaderBookmark
import com.renameapk.pdfzip.reader.util.JsonCodecs

fun RecentPdfEntity.toDocumentInfo(): PdfDocumentInfo =
    PdfDocumentInfo(
        uri = Uri.parse(uriString),
        displayName = displayName,
        sizeBytes = sizeBytes,
        pageCount = pageCount,
        lastPage = lastPage,
        lastZoom = lastZoom,
        isFavorite = isFavorite,
    )

fun BookmarkEntity.toBookmark(): ReaderBookmark =
    ReaderBookmark(
        id = id,
        pageIndex = pageIndex,
        title = title,
        createdAt = createdAt,
    )

fun AnnotationEntity.toAnnotation(): ReaderAnnotation =
    ReaderAnnotation(
        id = id,
        pageIndex = pageIndex,
        type = runCatching { AnnotationType.valueOf(type) }.getOrDefault(AnnotationType.Highlight),
        color = color,
        alpha = alpha,
        text = text,
        note = note,
        rects = JsonCodecs.decodeRects(rects),
        inkPoints = JsonCodecs.decodePoints(points),
        createdAt = createdAt,
    )

