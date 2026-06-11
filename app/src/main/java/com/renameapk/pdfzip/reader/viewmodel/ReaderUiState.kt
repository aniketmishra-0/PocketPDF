package com.renameapk.pdfzip.reader.viewmodel

import com.renameapk.pdfzip.reader.domain.AnnotationTool
import com.renameapk.pdfzip.reader.domain.PdfDocumentInfo
import com.renameapk.pdfzip.reader.domain.PdfPageInfo
import com.renameapk.pdfzip.reader.domain.ReaderAnnotation
import com.renameapk.pdfzip.reader.domain.ReaderBookmark
import com.renameapk.pdfzip.reader.domain.ReaderSettings
import com.renameapk.pdfzip.reader.domain.SearchHit
import com.renameapk.pdfzip.reader.domain.TextSelection
import com.renameapk.pdfzip.reader.domain.TocEntry

data class ReaderUiState(
    val document: PdfDocumentInfo? = null,
    val pageInfos: List<PdfPageInfo> = emptyList(),
    val currentPage: Int = 0,
    val zoom: Float = 1f,
    val isLoading: Boolean = false,
    val loadingMessage: String = "",
    val errorMessage: String? = null,
    val passwordRequired: Boolean = false,
    val passwordUri: String? = null,
    val settings: ReaderSettings = ReaderSettings(),
    val chromeVisible: Boolean = true,
    val thumbnailSidebarVisible: Boolean = false,
    val searchPanelVisible: Boolean = false,
    val searchQuery: String = "",
    val searchHits: List<SearchHit> = emptyList(),
    val activeSearchIndex: Int = -1,
    val isIndexing: Boolean = false,
    val indexedPages: Int = 0,
    val selectedText: TextSelection? = null,
    val selectedTool: AnnotationTool = AnnotationTool.None,
    val annotations: List<ReaderAnnotation> = emptyList(),
    val bookmarks: List<ReaderBookmark> = emptyList(),
    val toc: List<TocEntry> = emptyList(),
) {
    val pageCount: Int
        get() = document?.pageCount ?: pageInfos.size

    val activeSearchHit: SearchHit?
        get() = searchHits.getOrNull(activeSearchIndex)
}

