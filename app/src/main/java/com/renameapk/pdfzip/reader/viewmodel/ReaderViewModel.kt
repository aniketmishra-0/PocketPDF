package com.renameapk.pdfzip.reader.viewmodel

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import androidx.compose.runtime.mutableStateMapOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.renameapk.pdfzip.reader.data.repository.AnnotationRepository
import com.renameapk.pdfzip.reader.data.repository.BookmarkRepository
import com.renameapk.pdfzip.reader.data.repository.FileActionRepository
import com.renameapk.pdfzip.reader.data.repository.PdfReaderRepository
import com.renameapk.pdfzip.reader.data.repository.SearchRepository
import com.renameapk.pdfzip.reader.domain.AnnotationTool
import com.renameapk.pdfzip.reader.domain.AnnotationType
import com.renameapk.pdfzip.reader.domain.PageLayoutMode
import com.renameapk.pdfzip.reader.domain.PdfPoint
import com.renameapk.pdfzip.reader.domain.PdfRect
import com.renameapk.pdfzip.reader.domain.ReaderSettings
import com.renameapk.pdfzip.reader.domain.ScrollAxis
import com.renameapk.pdfzip.reader.pdfium.TileKey
import dagger.hilt.android.lifecycle.HiltViewModel
import io.legere.pdfiumandroid.PdfPasswordException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ReaderViewModel @Inject constructor(
    private val pdfReaderRepository: PdfReaderRepository,
    private val searchRepository: SearchRepository,
    private val bookmarkRepository: BookmarkRepository,
    private val annotationRepository: AnnotationRepository,
    private val fileActionRepository: FileActionRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(ReaderUiState())
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

    val tileBitmaps = mutableStateMapOf<TileKey, Bitmap>()
    val thumbnailBitmaps = mutableStateMapOf<Int, Bitmap>()

    private val inFlightTiles = mutableSetOf<TileKey>()
    private val inFlightThumbnails = mutableSetOf<Int>()
    private var indexingJob: Job? = null
    private var searchJob: Job? = null
    private var bookmarkJob: Job? = null
    private var annotationJob: Job? = null

    fun open(uri: Uri, password: String? = null) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = true,
                    loadingMessage = "Opening PDF...",
                    errorMessage = null,
                    passwordRequired = false,
                    passwordUri = null,
                    selectedText = null,
                    searchHits = emptyList(),
                    activeSearchIndex = -1,
                )
            }
            try {
                val document = pdfReaderRepository.open(uri, password)
                val initialPages = pdfReaderRepository.loadInitialPageInfos()
                tileBitmaps.clear()
                thumbnailBitmaps.clear()
                _uiState.update {
                    it.copy(
                        document = document,
                        pageInfos = initialPages,
                        currentPage = document.lastPage.coerceIn(0, (document.pageCount - 1).coerceAtLeast(0)),
                        zoom = document.lastZoom.coerceIn(1f, 10f),
                        isLoading = false,
                        loadingMessage = "",
                    )
                }
                observeDocumentSideData(document.uri)
                loadRemainingPageInfo()
                loadTableOfContents()
                startIndexing()
                preloadAround(document.lastPage)
            } catch (password: PdfPasswordException) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        passwordRequired = true,
                        passwordUri = uri.toString(),
                        errorMessage = "This PDF is password protected.",
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = throwable.message ?: "Unable to open this PDF.",
                    )
                }
            }
        }
    }

    fun closeDocument() {
        viewModelScope.launch {
            pdfReaderRepository.close()
            indexingJob?.cancel()
            searchJob?.cancel()
            bookmarkJob?.cancel()
            annotationJob?.cancel()
            tileBitmaps.clear()
            thumbnailBitmaps.clear()
            _uiState.value = ReaderUiState()
        }
    }

    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null, passwordRequired = false, passwordUri = null) }
    }

    fun requestVisibleTiles(pageIndex: Int, pageWidthPx: Int, pageHeightPx: Int, visibleRect: Rect) {
        val state = _uiState.value
        if (state.document == null || pageWidthPx <= 0 || pageHeightPx <= 0) return
        viewModelScope.launch {
            val requests = pdfReaderRepository.renderVisibleTiles(
                pageIndex = pageIndex,
                pageWidthPx = pageWidthPx,
                pageHeightPx = pageHeightPx,
                visibleRect = visibleRect,
                zoom = state.zoom,
                darkMode = state.settings.darkMode,
            ).filter { (key, _) -> inFlightTiles.add(key) }

            requests.forEach { (key, bitmap) ->
                inFlightTiles.remove(key)
                tileBitmaps[key] = bitmap
            }
            trimVisibleTileState()
        }
    }

    fun requestThumbnail(pageIndex: Int) {
        if (!inFlightThumbnails.add(pageIndex)) return
        viewModelScope.launch {
            try {
                pdfReaderRepository.renderThumbnail(pageIndex)?.let { thumbnail ->
                    thumbnailBitmaps[pageIndex] = thumbnail
                }
            } finally {
                inFlightThumbnails.remove(pageIndex)
            }
        }
    }

    fun setCurrentPage(pageIndex: Int) {
        val pageCount = _uiState.value.pageCount
        val safePage = pageIndex.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        _uiState.update { it.copy(currentPage = safePage) }
        preloadAround(safePage)
        persistProgress()
    }

    fun nextPage() = setCurrentPage(_uiState.value.currentPage + 1)

    fun previousPage() = setCurrentPage(_uiState.value.currentPage - 1)

    fun setZoom(zoom: Float) {
        _uiState.update { it.copy(zoom = zoom.coerceIn(1f, 10f)) }
        persistProgress()
    }

    fun adjustZoom(delta: Float) {
        setZoom(_uiState.value.zoom + delta)
    }

    fun toggleDoubleTapZoom() {
        val current = _uiState.value.zoom
        setZoom(if (current < 2.2f) 3f else 1f)
    }

    fun toggleChrome() {
        _uiState.update { it.copy(chromeVisible = !it.chromeVisible) }
    }

    fun toggleThumbnailSidebar() {
        _uiState.update { it.copy(thumbnailSidebarVisible = !it.thumbnailSidebarVisible) }
    }

    fun toggleSearchPanel() {
        _uiState.update { it.copy(searchPanelVisible = !it.searchPanelVisible) }
    }

    fun setSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun runSearch() {
        val session = pdfReaderRepository.session ?: return
        val query = _uiState.value.searchQuery
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, loadingMessage = "Searching...") }
            val hits = searchRepository.search(session, query)
            _uiState.update {
                it.copy(
                    isLoading = false,
                    loadingMessage = "",
                    searchHits = hits,
                    activeSearchIndex = if (hits.isEmpty()) -1 else 0,
                    currentPage = hits.firstOrNull()?.pageIndex ?: it.currentPage,
                )
            }
        }
    }

    fun nextSearchHit() {
        val hits = _uiState.value.searchHits
        if (hits.isEmpty()) return
        val next = (_uiState.value.activeSearchIndex + 1).floorMod(hits.size)
        _uiState.update { it.copy(activeSearchIndex = next, currentPage = hits[next].pageIndex) }
    }

    fun previousSearchHit() {
        val hits = _uiState.value.searchHits
        if (hits.isEmpty()) return
        val previous = (_uiState.value.activeSearchIndex - 1).floorMod(hits.size)
        _uiState.update { it.copy(activeSearchIndex = previous, currentPage = hits[previous].pageIndex) }
    }

    fun selectTextAt(pageIndex: Int, normalizedPoint: PdfPoint) {
        viewModelScope.launch {
            val selection = pdfReaderRepository.selectWord(pageIndex, normalizedPoint)
            _uiState.update { it.copy(selectedText = selection) }
        }
    }

    fun clearSelection() {
        _uiState.update { it.copy(selectedText = null) }
    }

    fun setAnnotationTool(tool: AnnotationTool) {
        _uiState.update { it.copy(selectedTool = tool) }
    }

    fun addHighlightFromSelection() {
        addMarkupFromSelection(AnnotationType.Highlight)
    }

    fun addUnderlineFromSelection() {
        addMarkupFromSelection(AnnotationType.Underline)
    }

    fun addNote(pageIndex: Int, normalizedPoint: PdfPoint, note: String) {
        val uri = _uiState.value.document?.uri ?: return
        viewModelScope.launch {
            annotationRepository.addNote(
                uri = uri,
                pageIndex = pageIndex,
                rect = PdfRect(
                    left = normalizedPoint.x,
                    top = normalizedPoint.y,
                    right = (normalizedPoint.x + 0.04f).coerceAtMost(1f),
                    bottom = (normalizedPoint.y + 0.04f).coerceAtMost(1f),
                ),
                note = note,
            )
        }
    }

    fun addInkStroke(pageIndex: Int, points: List<PdfPoint>) {
        val uri = _uiState.value.document?.uri ?: return
        if (points.size < 2) return
        viewModelScope.launch {
            annotationRepository.addInkStroke(uri, pageIndex, points)
        }
    }

    fun deleteAnnotation(id: Long) {
        viewModelScope.launch {
            annotationRepository.delete(id)
        }
    }

    fun toggleBookmark() {
        val uri = _uiState.value.document?.uri ?: return
        val pageIndex = _uiState.value.currentPage
        viewModelScope.launch {
            bookmarkRepository.toggleBookmark(uri, pageIndex)
        }
    }

    fun updateSettings(settings: ReaderSettings) {
        _uiState.update { it.copy(settings = settings) }
        tileBitmaps.clear()
    }

    fun setScrollAxis(axis: ScrollAxis) {
        updateSettings(_uiState.value.settings.copy(scrollAxis = axis))
    }

    fun setPageLayoutMode(mode: PageLayoutMode) {
        updateSettings(_uiState.value.settings.copy(pageLayoutMode = mode))
    }

    fun toggleDarkMode() {
        val next = !_uiState.value.settings.darkMode
        updateSettings(_uiState.value.settings.copy(darkMode = next))
    }

    fun toggleFullscreen() {
        val next = !_uiState.value.settings.fullscreen
        updateSettings(_uiState.value.settings.copy(fullscreen = next))
    }

    fun shareCurrentIntent(): Intent? {
        val document = _uiState.value.document ?: return null
        return fileActionRepository.shareIntent(document.uri, document.displayName)
    }

    private fun addMarkupFromSelection(type: AnnotationType) {
        val uri = _uiState.value.document?.uri ?: return
        val selection = _uiState.value.selectedText ?: return
        viewModelScope.launch {
            annotationRepository.addTextMarkup(uri, selection, type)
            clearSelection()
        }
    }

    private fun observeDocumentSideData(uri: Uri) {
        bookmarkJob?.cancel()
        bookmarkJob = viewModelScope.launch {
            bookmarkRepository.observeBookmarks(uri).collect { bookmarks ->
                _uiState.update { it.copy(bookmarks = bookmarks) }
            }
        }
        annotationJob?.cancel()
        annotationJob = viewModelScope.launch {
            annotationRepository.observeAnnotations(uri).collect { annotations ->
                _uiState.update { it.copy(annotations = annotations) }
            }
        }
    }

    private fun loadRemainingPageInfo() {
        viewModelScope.launch {
            pdfReaderRepository.loadAllPageInfos { info ->
                _uiState.update { state ->
                    val byIndex = (state.pageInfos + info).distinctBy { it.pageIndex }.sortedBy { it.pageIndex }
                    state.copy(pageInfos = byIndex)
                }
            }
        }
    }

    private fun loadTableOfContents() {
        viewModelScope.launch {
            val toc = pdfReaderRepository.tableOfContents()
            _uiState.update { it.copy(toc = toc) }
        }
    }

    private fun startIndexing() {
        val session = pdfReaderRepository.session ?: return
        indexingJob?.cancel()
        indexingJob = viewModelScope.launch {
            _uiState.update { it.copy(isIndexing = true, indexedPages = 0) }
            try {
                searchRepository.ensureIndexed(session) { indexed, _ ->
                    _uiState.update { it.copy(indexedPages = indexed) }
                }
            } finally {
                _uiState.update { it.copy(isIndexing = false) }
            }
        }
    }

    private fun preloadAround(pageIndex: Int) {
        val pageCount = _uiState.value.pageCount
        ((pageIndex - 2)..(pageIndex + 2))
            .filter { it in 0 until pageCount }
            .forEach(::requestThumbnail)
    }

    private fun persistProgress() {
        val state = _uiState.value
        viewModelScope.launch {
            pdfReaderRepository.updateProgress(state.currentPage, state.zoom)
        }
    }

    private fun trimVisibleTileState() {
        val maxTilesInState = 360
        if (tileBitmaps.size <= maxTilesInState) return
        val currentPage = _uiState.value.currentPage
        val keysToRemove = tileBitmaps.keys
            .filter { kotlin.math.abs(it.pageIndex - currentPage) > 2 }
            .take(tileBitmaps.size - maxTilesInState)
        keysToRemove.forEach { tileBitmaps.remove(it) }
    }

    private fun Int.floorMod(size: Int): Int = ((this % size) + size) % size
}
