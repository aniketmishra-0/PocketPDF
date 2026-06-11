package com.renameapk.pdfzip.reader.viewmodel

import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.renameapk.pdfzip.reader.data.repository.FileActionRepository
import com.renameapk.pdfzip.reader.data.repository.RecentPdfRepository
import com.renameapk.pdfzip.reader.domain.PdfDocumentInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LibraryUiState(
    val recentPdfs: List<PdfDocumentInfo> = emptyList(),
)

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val recentPdfRepository: RecentPdfRepository,
    private val fileActionRepository: FileActionRepository,
) : ViewModel() {
    val uiState: StateFlow<LibraryUiState> =
        recentPdfRepository.observeRecentPdfs()
            .map { LibraryUiState(recentPdfs = it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    fun toggleFavorite(uri: Uri) {
        viewModelScope.launch {
            recentPdfRepository.toggleFavorite(uri)
        }
    }

    fun delete(uri: Uri) {
        viewModelScope.launch {
            fileActionRepository.delete(uri)
        }
    }

    fun shareIntent(document: PdfDocumentInfo): Intent =
        fileActionRepository.shareIntent(document.uri, document.displayName)
}

