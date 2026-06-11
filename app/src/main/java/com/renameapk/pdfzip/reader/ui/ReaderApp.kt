package com.renameapk.pdfzip.reader.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.renameapk.pdfzip.MainActivity
import com.renameapk.pdfzip.PdfEditActivity
import com.renameapk.pdfzip.reader.domain.PdfDocumentInfo
import com.renameapk.pdfzip.reader.viewmodel.LibraryViewModel
import com.renameapk.pdfzip.reader.viewmodel.ReaderViewModel
import java.text.DateFormat

@Composable
fun ReaderApp(
    initialUri: Uri?,
    readerViewModel: ReaderViewModel,
    libraryViewModel: LibraryViewModel,
) {
    val readerState by readerViewModel.uiState.collectAsStateWithLifecycle()
    val libraryState by libraryViewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val openPdfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) readerViewModel.open(uri)
    }

    LaunchedEffect(initialUri) {
        if (initialUri != null && readerState.document?.uri != initialUri) {
            readerViewModel.open(initialUri)
        }
    }

    Box(Modifier.fillMaxSize()) {
        if (readerState.document == null) {
            LibraryScreen(
                recentPdfs = libraryState.recentPdfs,
                onOpenPicker = { openPdfLauncher.launch(arrayOf("application/pdf")) },
                onOpenRecent = readerViewModel::open,
                onToggleFavorite = libraryViewModel::toggleFavorite,
                onShare = { document ->
                    context.startActivity(Intent.createChooser(libraryViewModel.shareIntent(document), "Share PDF"))
                },
                onDelete = libraryViewModel::delete,
            )
        } else {
            PdfReaderScreen(
                state = readerState,
                tileBitmaps = readerViewModel.tileBitmaps,
                thumbnailBitmaps = readerViewModel.thumbnailBitmaps,
                onRequestTiles = readerViewModel::requestVisibleTiles,
                onRequestThumbnail = readerViewModel::requestThumbnail,
                onCurrentPageChanged = readerViewModel::setCurrentPage,
                onNextPage = readerViewModel::nextPage,
                onPreviousPage = readerViewModel::previousPage,
                onZoomChanged = readerViewModel::setZoom,
                onZoomDelta = readerViewModel::adjustZoom,
                onDoubleTapZoom = readerViewModel::toggleDoubleTapZoom,
                onToggleChrome = readerViewModel::toggleChrome,
                onToggleSidebar = readerViewModel::toggleThumbnailSidebar,
                onToggleSearch = readerViewModel::toggleSearchPanel,
                onSearchQueryChanged = readerViewModel::setSearchQuery,
                onRunSearch = readerViewModel::runSearch,
                onNextSearch = readerViewModel::nextSearchHit,
                onPreviousSearch = readerViewModel::previousSearchHit,
                onSelectText = readerViewModel::selectTextAt,
                onClearSelection = readerViewModel::clearSelection,
                onHighlightSelection = readerViewModel::addHighlightFromSelection,
                onUnderlineSelection = readerViewModel::addUnderlineFromSelection,
                onAddNote = readerViewModel::addNote,
                onAddInk = readerViewModel::addInkStroke,
                onDeleteAnnotation = readerViewModel::deleteAnnotation,
                onToolChanged = readerViewModel::setAnnotationTool,
                onToggleBookmark = readerViewModel::toggleBookmark,
                onToggleDarkMode = readerViewModel::toggleDarkMode,
                onToggleFullscreen = readerViewModel::toggleFullscreen,
                onScrollAxisChanged = readerViewModel::setScrollAxis,
                onPageLayoutChanged = readerViewModel::setPageLayoutMode,
                onClose = readerViewModel::closeDocument,
                onShare = {
                    readerViewModel.shareCurrentIntent()?.let { intent ->
                        context.startActivity(Intent.createChooser(intent, "Share PDF"))
                    }
                },
                onCompress = {
                    readerState.document?.let { document ->
                        context.startActivity(
                            MainActivity.createToolIntent(
                                context = context,
                                pdfUri = document.uri,
                                pdfName = document.displayName,
                                toolMode = MainActivity.TOOL_MODE_COMPRESS,
                            ),
                        )
                    }
                },
                onCreateZip = {
                    readerState.document?.let { document ->
                        context.startActivity(
                            MainActivity.createToolIntent(
                                context = context,
                                pdfUri = document.uri,
                                pdfName = document.displayName,
                                toolMode = MainActivity.TOOL_MODE_ZIP,
                            ),
                        )
                    }
                },
                onPreflight = {
                    readerState.document?.let { document ->
                        context.startActivity(
                            MainActivity.createToolIntent(
                                context = context,
                                pdfUri = document.uri,
                                pdfName = document.displayName,
                                toolMode = MainActivity.TOOL_MODE_PREFLIGHT,
                            ),
                        )
                    }
                },
                onEdit = {
                    readerState.document?.let { document ->
                        context.startActivity(
                            PdfEditActivity.createIntent(
                                context = context,
                                pdfUri = document.uri,
                                pdfName = document.displayName,
                            ),
                        )
                    }
                },
            )
        }

        if (readerState.isLoading) {
            LoadingOverlay(readerState.loadingMessage)
        }

        if (readerState.passwordRequired && readerState.passwordUri != null) {
            PasswordDialog(
                onDismiss = readerViewModel::dismissError,
                onSubmit = { password ->
                    readerViewModel.open(Uri.parse(readerState.passwordUri), password)
                },
            )
        } else if (readerState.errorMessage != null) {
            AlertDialog(
                onDismissRequest = readerViewModel::dismissError,
                title = { Text("PDF reader") },
                text = { Text(readerState.errorMessage.orEmpty()) },
                confirmButton = {
                    TextButton(onClick = readerViewModel::dismissError) {
                        Text("OK")
                    }
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryScreen(
    recentPdfs: List<PdfDocumentInfo>,
    onOpenPicker: () -> Unit,
    onOpenRecent: (Uri) -> Unit,
    onToggleFavorite: (Uri) -> Unit,
    onShare: (PdfDocumentInfo) -> Unit,
    onDelete: (Uri) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Pocket PDF", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Library",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(4.dp))
            Button(
                onClick = onOpenPicker,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 14.dp),
            ) {
                Icon(Icons.Default.FolderOpen, contentDescription = null)
                Spacer(Modifier.size(10.dp))
                Text("Open PDF")
            }

            Text(
                "Recent files",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )

            if (recentPdfs.isEmpty()) {
                EmptyLibrary()
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(recentPdfs, key = { it.uri.toString() }) { document ->
                        RecentPdfRow(
                            document = document,
                            onOpen = { onOpenRecent(document.uri) },
                            onToggleFavorite = { onToggleFavorite(document.uri) },
                            onShare = { onShare(document) },
                            onDelete = { onDelete(document.uri) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentPdfRow(
    document: PdfDocumentInfo,
    onOpen: () -> Unit,
    onToggleFavorite: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Card(
        onClick = onOpen,
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        ListItem(
            leadingContent = {
                Icon(
                    Icons.Default.PictureAsPdf,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            },
            headlineContent = {
                Text(document.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            supportingContent = {
                Text(
                    "Page ${document.lastPage + 1} of ${document.pageCount.coerceAtLeast(1)}",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onToggleFavorite) {
                        Icon(
                            if (document.isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                            contentDescription = "Favorite",
                        )
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Share") },
                                leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    onShare()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Delete") },
                                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    onDelete()
                                },
                            )
                        }
                    }
                }
            },
        )
    }
}

@Composable
private fun EmptyLibrary() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 42.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            Icons.Default.PictureAsPdf,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text("No recent PDFs yet", style = MaterialTheme.typography.titleMedium)
        Text(
            "Open a local document to start reading.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LoadingOverlay(message: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Card(shape = RoundedCornerShape(8.dp)) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                CircularProgressIndicator()
                Text(message.ifBlank { "Working..." })
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun PasswordDialog(
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Password required") },
        text = {
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("PDF password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
            )
        },
        confirmButton = {
            Button(onClick = { onSubmit(password) }, enabled = password.isNotBlank()) {
                Text("Unlock")
            }
        },
        dismissButton = {
            FilledTonalButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

