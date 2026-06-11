package com.renameapk.pdfzip.reader.domain

import android.net.Uri

enum class ScrollAxis {
    Vertical,
    Horizontal,
}

enum class PageLayoutMode {
    Continuous,
    SinglePage,
}

enum class AnnotationTool {
    None,
    Highlight,
    Underline,
    Ink,
    Note,
}

enum class AnnotationType {
    Highlight,
    Underline,
    Ink,
    Note,
}

data class ReaderSettings(
    val scrollAxis: ScrollAxis = ScrollAxis.Vertical,
    val pageLayoutMode: PageLayoutMode = PageLayoutMode.Continuous,
    val darkMode: Boolean = false,
    val fullscreen: Boolean = true,
)

data class PdfDocumentInfo(
    val uri: Uri,
    val displayName: String,
    val sizeBytes: Long?,
    val pageCount: Int,
    val lastPage: Int = 0,
    val lastZoom: Float = 1f,
    val isFavorite: Boolean = false,
)

data class PdfPageInfo(
    val pageIndex: Int,
    val widthPoints: Int,
    val heightPoints: Int,
    val rotation: Int = 0,
) {
    val aspectRatio: Float
        get() = if (heightPoints <= 0) 0.707f else widthPoints.toFloat() / heightPoints.toFloat()
}

data class PdfRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    fun normalized(pageWidth: Float, pageHeight: Float): PdfRect =
        PdfRect(
            left = (left / pageWidth).coerceIn(0f, 1f),
            top = (top / pageHeight).coerceIn(0f, 1f),
            right = (right / pageWidth).coerceIn(0f, 1f),
            bottom = (bottom / pageHeight).coerceIn(0f, 1f),
        )
}

data class PdfPoint(
    val x: Float,
    val y: Float,
)

data class PdfInkStroke(
    val points: List<PdfPoint>,
    val color: Int,
    val width: Float,
)

data class SearchHit(
    val id: String,
    val pageIndex: Int,
    val startIndex: Int,
    val length: Int,
    val preview: String,
    val rects: List<PdfRect>,
)

data class TextSelection(
    val pageIndex: Int,
    val text: String,
    val startIndex: Int,
    val length: Int,
    val rects: List<PdfRect>,
)

data class TocEntry(
    val title: String,
    val pageIndex: Int,
    val level: Int,
)

data class ReaderBookmark(
    val id: Long,
    val pageIndex: Int,
    val title: String,
    val createdAt: Long,
)

data class ReaderAnnotation(
    val id: Long,
    val pageIndex: Int,
    val type: AnnotationType,
    val color: Int,
    val alpha: Float,
    val text: String?,
    val note: String?,
    val rects: List<PdfRect>,
    val inkPoints: List<PdfPoint>,
    val createdAt: Long,
)

