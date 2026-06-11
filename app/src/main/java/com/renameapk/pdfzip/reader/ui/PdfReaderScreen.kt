package com.renameapk.pdfzip.reader.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.FormatColorFill
import androidx.compose.material.icons.filled.FormatUnderlined
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NavigateBefore
import androidx.compose.material.icons.filled.NavigateNext
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.RuleFolder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material.icons.filled.ViewCarousel
import androidx.compose.material.icons.filled.ViewSidebar
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.renameapk.pdfzip.reader.domain.AnnotationTool
import com.renameapk.pdfzip.reader.domain.AnnotationType
import com.renameapk.pdfzip.reader.domain.PageLayoutMode
import com.renameapk.pdfzip.reader.domain.PdfPageInfo
import com.renameapk.pdfzip.reader.domain.PdfPoint
import com.renameapk.pdfzip.reader.domain.PdfRect
import com.renameapk.pdfzip.reader.domain.ReaderAnnotation
import com.renameapk.pdfzip.reader.domain.ScrollAxis
import com.renameapk.pdfzip.reader.domain.SearchHit
import com.renameapk.pdfzip.reader.domain.TextSelection
import com.renameapk.pdfzip.reader.domain.TocEntry
import com.renameapk.pdfzip.reader.pdfium.TileKey
import com.renameapk.pdfzip.reader.viewmodel.ReaderUiState
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.abs
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfReaderScreen(
    state: ReaderUiState,
    tileBitmaps: Map<TileKey, Bitmap>,
    thumbnailBitmaps: Map<Int, Bitmap>,
    onRequestTiles: (Int, Int, Int, Rect) -> Unit,
    onRequestThumbnail: (Int) -> Unit,
    onCurrentPageChanged: (Int) -> Unit,
    onNextPage: () -> Unit,
    onPreviousPage: () -> Unit,
    onZoomChanged: (Float) -> Unit,
    onZoomDelta: (Float) -> Unit,
    onDoubleTapZoom: () -> Unit,
    onToggleChrome: () -> Unit,
    onToggleSidebar: () -> Unit,
    onToggleSearch: () -> Unit,
    onSearchQueryChanged: (String) -> Unit,
    onRunSearch: () -> Unit,
    onNextSearch: () -> Unit,
    onPreviousSearch: () -> Unit,
    onSelectText: (Int, PdfPoint) -> Unit,
    onClearSelection: () -> Unit,
    onHighlightSelection: () -> Unit,
    onUnderlineSelection: () -> Unit,
    onAddNote: (Int, PdfPoint, String) -> Unit,
    onAddInk: (Int, List<PdfPoint>) -> Unit,
    onDeleteAnnotation: (Long) -> Unit,
    onToolChanged: (AnnotationTool) -> Unit,
    onToggleBookmark: () -> Unit,
    onToggleDarkMode: () -> Unit,
    onToggleFullscreen: () -> Unit,
    onScrollAxisChanged: (ScrollAxis) -> Unit,
    onPageLayoutChanged: (PageLayoutMode) -> Unit,
    onClose: () -> Unit,
    onShare: () -> Unit,
    onCompress: () -> Unit,
    onCreateZip: () -> Unit,
    onPreflight: () -> Unit,
    onEdit: () -> Unit,
) {
    val transformableState = rememberTransformableState { zoomChange, _, _ ->
        onZoomChanged((state.zoom * zoomChange).coerceIn(1f, 10f))
    }
    var goToDialogOpen by remember { mutableStateOf(false) }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            AnimatedVisibility(state.chromeVisible) {
                ReaderTopBar(
                    state = state,
                    onClose = onClose,
                    onToggleSidebar = onToggleSidebar,
                    onToggleSearch = onToggleSearch,
                    onToggleBookmark = onToggleBookmark,
                    onToggleDarkMode = onToggleDarkMode,
                    onToggleFullscreen = onToggleFullscreen,
                    onShare = onShare,
                    onGoToPage = { goToDialogOpen = true },
                    onCurrentPageChanged = onCurrentPageChanged,
                    onCompress = onCompress,
                    onCreateZip = onCreateZip,
                    onPreflight = onPreflight,
                    onEdit = onEdit,
                )
            }
        },
        bottomBar = {
            AnimatedVisibility(state.chromeVisible) {
                ReaderBottomBar(
                    state = state,
                    onPreviousPage = onPreviousPage,
                    onNextPage = onNextPage,
                    onCurrentPageChanged = onCurrentPageChanged,
                    onZoomDelta = onZoomDelta,
                    onScrollAxisChanged = onScrollAxisChanged,
                    onPageLayoutChanged = onPageLayoutChanged,
                    onToolChanged = onToolChanged,
                )
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(if (state.settings.darkMode) Color(0xFF0B0D0F) else Color(0xFFE9ECEF))
                .padding(padding)
                .transformable(transformableState),
        ) {
            Row(Modifier.fillMaxSize()) {
                AnimatedVisibility(state.thumbnailSidebarVisible && state.chromeVisible) {
                    ThumbnailSidebar(
                        state = state,
                        thumbnails = thumbnailBitmaps,
                        onRequestThumbnail = onRequestThumbnail,
                        onGoToPage = onCurrentPageChanged,
                    )
                }
                PdfViewport(
                    state = state,
                    tileBitmaps = tileBitmaps,
                    onRequestTiles = onRequestTiles,
                    onCurrentPageChanged = onCurrentPageChanged,
                    onDoubleTapZoom = onDoubleTapZoom,
                    onToggleChrome = onToggleChrome,
                    onSelectText = onSelectText,
                    onAddNote = onAddNote,
                    onAddInk = onAddInk,
                    modifier = Modifier.weight(1f),
                )
            }

            AnimatedVisibility(
                visible = state.searchPanelVisible && state.chromeVisible,
                modifier = Modifier.align(Alignment.TopCenter),
            ) {
                SearchPanel(
                    state = state,
                    onQueryChanged = onSearchQueryChanged,
                    onRunSearch = onRunSearch,
                    onNext = onNextSearch,
                    onPrevious = onPreviousSearch,
                )
            }

            state.selectedText?.let { selection ->
                SelectionToolbar(
                    selection = selection,
                    onCopy = onClearSelection,
                    onShare = onClearSelection,
                    onHighlight = onHighlightSelection,
                    onUnderline = onUnderlineSelection,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }

    if (goToDialogOpen) {
        GoToPageDialog(
            pageCount = state.pageCount,
            onDismiss = { goToDialogOpen = false },
            onGoTo = {
                goToDialogOpen = false
                onCurrentPageChanged(it)
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderTopBar(
    state: ReaderUiState,
    onClose: () -> Unit,
    onToggleSidebar: () -> Unit,
    onToggleSearch: () -> Unit,
    onToggleBookmark: () -> Unit,
    onToggleDarkMode: () -> Unit,
    onToggleFullscreen: () -> Unit,
    onShare: () -> Unit,
    onGoToPage: () -> Unit,
    onCurrentPageChanged: (Int) -> Unit,
    onCompress: () -> Unit,
    onCreateZip: () -> Unit,
    onPreflight: () -> Unit,
    onEdit: () -> Unit,
) {
    var tocOpen by remember { mutableStateOf(false) }
    var toolsOpen by remember { mutableStateOf(false) }
    TopAppBar(
        modifier = Modifier.statusBarsPadding(),
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "Close")
            }
        },
        title = {
            Column {
                Text(
                    state.document?.displayName.orEmpty(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "Page ${state.currentPage + 1} of ${state.pageCount.coerceAtLeast(1)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        actions = {
            IconButton(onClick = onToggleSearch) {
                Icon(Icons.Default.Search, contentDescription = "Search")
            }
            IconButton(onClick = onToggleSidebar) {
                Icon(Icons.Default.ViewSidebar, contentDescription = "Thumbnails")
            }
            Box {
                IconButton(onClick = { tocOpen = true }, enabled = state.toc.isNotEmpty()) {
                    Icon(Icons.Default.Article, contentDescription = "Contents")
                }
                DropdownMenu(expanded = tocOpen, onDismissRequest = { tocOpen = false }) {
                    state.toc.take(80).forEach { item ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    "${"  ".repeat(item.level)}${item.title}",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            onClick = {
                                tocOpen = false
                                onCurrentPageChanged(item.pageIndex)
                            },
                        )
                    }
                }
            }
            IconButton(onClick = onToggleBookmark) {
                Icon(Icons.Default.Bookmark, contentDescription = "Bookmark")
            }
            IconButton(onClick = onGoToPage) {
                Icon(Icons.Default.Menu, contentDescription = "Go to page")
            }
            IconButton(onClick = onShare) {
                Icon(Icons.Default.Share, contentDescription = "Share")
            }
            IconButton(onClick = onToggleDarkMode) {
                Icon(
                    if (state.settings.darkMode) Icons.Default.LightMode else Icons.Default.DarkMode,
                    contentDescription = "Theme",
                )
            }
            IconButton(onClick = onToggleFullscreen) {
                Icon(Icons.Default.OpenInFull, contentDescription = "Fullscreen")
            }
            Box {
                IconButton(onClick = { toolsOpen = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "More tools")
                }
                DropdownMenu(expanded = toolsOpen, onDismissRequest = { toolsOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Compress") },
                        leadingIcon = { Icon(Icons.Default.Compress, contentDescription = null) },
                        onClick = {
                            toolsOpen = false
                            onCompress()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Rename / ZIP") },
                        leadingIcon = { Icon(Icons.Default.FolderZip, contentDescription = null) },
                        onClick = {
                            toolsOpen = false
                            onCreateZip()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Preflight") },
                        leadingIcon = { Icon(Icons.Default.RuleFolder, contentDescription = null) },
                        onClick = {
                            toolsOpen = false
                            onPreflight()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Edit") },
                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                        onClick = {
                            toolsOpen = false
                            onEdit()
                        },
                    )
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        ),
    )
}

@Composable
private fun ReaderBottomBar(
    state: ReaderUiState,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    onCurrentPageChanged: (Int) -> Unit,
    onZoomDelta: (Float) -> Unit,
    onScrollAxisChanged: (ScrollAxis) -> Unit,
    onPageLayoutChanged: (PageLayoutMode) -> Unit,
    onToolChanged: (AnnotationTool) -> Unit,
) {
    Surface(
        tonalElevation = 3.dp,
        modifier = Modifier.navigationBarsPadding(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onPreviousPage) {
                    Icon(Icons.Default.NavigateBefore, contentDescription = "Previous page")
                }
                Slider(
                    value = (state.currentPage + 1).toFloat(),
                    onValueChange = { onCurrentPageChanged(it.roundToInt() - 1) },
                    valueRange = 1f..state.pageCount.coerceAtLeast(1).toFloat(),
                    steps = (state.pageCount - 2).coerceAtLeast(0),
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onNextPage) {
                    Icon(Icons.Default.NavigateNext, contentDescription = "Next page")
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AssistChip(
                    onClick = { onZoomDelta(-0.25f) },
                    label = { Text("${(state.zoom * 100).roundToInt()}%") },
                    leadingIcon = { Icon(Icons.Default.ZoomOut, contentDescription = null, modifier = Modifier.size(18.dp)) },
                )
                FilledIconButton(onClick = { onZoomDelta(0.25f) }) {
                    Icon(Icons.Default.ZoomIn, contentDescription = "Zoom in")
                }
                AssistChip(
                    onClick = {
                        onScrollAxisChanged(
                            if (state.settings.scrollAxis == ScrollAxis.Vertical) ScrollAxis.Horizontal else ScrollAxis.Vertical,
                        )
                    },
                    label = { Text(if (state.settings.scrollAxis == ScrollAxis.Vertical) "Vertical" else "Horizontal") },
                    leadingIcon = {
                        Icon(
                            if (state.settings.scrollAxis == ScrollAxis.Vertical) Icons.Default.SwapVert else Icons.Default.SwapHoriz,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                )
                AssistChip(
                    onClick = {
                        onPageLayoutChanged(
                            if (state.settings.pageLayoutMode == PageLayoutMode.Continuous) {
                                PageLayoutMode.SinglePage
                            } else {
                                PageLayoutMode.Continuous
                            },
                        )
                    },
                    label = { Text(if (state.settings.pageLayoutMode == PageLayoutMode.Continuous) "Continuous" else "Single") },
                    leadingIcon = {
                        Icon(
                            if (state.settings.pageLayoutMode == PageLayoutMode.Continuous) Icons.Default.ViewAgenda else Icons.Default.ViewCarousel,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                )
                AssistChip(
                    onClick = { onToolChanged(AnnotationTool.Highlight) },
                    label = { Text("Highlight") },
                    leadingIcon = { Icon(Icons.Default.FormatColorFill, contentDescription = null, modifier = Modifier.size(18.dp)) },
                )
                AssistChip(
                    onClick = { onToolChanged(AnnotationTool.Ink) },
                    label = { Text("Draw") },
                    leadingIcon = { Icon(Icons.Default.Draw, contentDescription = null, modifier = Modifier.size(18.dp)) },
                )
                AssistChip(
                    onClick = { onToolChanged(AnnotationTool.Note) },
                    label = { Text("Note") },
                    leadingIcon = { Icon(Icons.Default.NoteAdd, contentDescription = null, modifier = Modifier.size(18.dp)) },
                )
            }
        }
    }
}

@Composable
private fun PdfViewport(
    state: ReaderUiState,
    tileBitmaps: Map<TileKey, Bitmap>,
    onRequestTiles: (Int, Int, Int, Rect) -> Unit,
    onCurrentPageChanged: (Int) -> Unit,
    onDoubleTapZoom: () -> Unit,
    onToggleChrome: () -> Unit,
    onSelectText: (Int, PdfPoint) -> Unit,
    onAddNote: (Int, PdfPoint, String) -> Unit,
    onAddInk: (Int, List<PdfPoint>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pageCount = state.pageCount.coerceAtLeast(1)
    val horizontalScroll = rememberScrollState()

    BoxWithConstraints(modifier.fillMaxSize()) {
        val pageWidth = maxWidth * state.zoom
        when (state.settings.pageLayoutMode) {
            PageLayoutMode.SinglePage -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .horizontalScroll(horizontalScroll),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    PdfPageItem(
                        pageInfo = state.pageInfoFor(state.currentPage),
                        widthModifier = Modifier.width(pageWidth),
                        state = state,
                        tileBitmaps = tileBitmaps,
                        onRequestTiles = onRequestTiles,
                        onDoubleTapZoom = onDoubleTapZoom,
                        onToggleChrome = onToggleChrome,
                        onSelectText = onSelectText,
                        onAddNote = onAddNote,
                        onAddInk = onAddInk,
                    )
                }
            }
            PageLayoutMode.Continuous -> {
                if (state.settings.scrollAxis == ScrollAxis.Vertical) {
                    val listState = rememberLazyListState(initialFirstVisibleItemIndex = state.currentPage)
                    LaunchedEffect(listState) {
                        snapshotFlow { listState.firstVisibleItemIndex }
                            .distinctUntilChanged()
                            .collect(onCurrentPageChanged)
                    }
                    Box(Modifier.fillMaxSize().horizontalScroll(horizontalScroll)) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.width(pageWidth),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 18.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            items(pageCount, key = { it }) { pageIndex ->
                                PdfPageItem(
                                    pageInfo = state.pageInfoFor(pageIndex),
                                    widthModifier = Modifier.fillMaxWidth(),
                                    state = state,
                                    tileBitmaps = tileBitmaps,
                                    onRequestTiles = onRequestTiles,
                                    onDoubleTapZoom = onDoubleTapZoom,
                                    onToggleChrome = onToggleChrome,
                                    onSelectText = onSelectText,
                                    onAddNote = onAddNote,
                                    onAddInk = onAddInk,
                                )
                            }
                        }
                    }
                } else {
                    val rowState = rememberLazyListState(initialFirstVisibleItemIndex = state.currentPage)
                    LaunchedEffect(rowState) {
                        snapshotFlow { rowState.firstVisibleItemIndex }
                            .distinctUntilChanged()
                            .collect(onCurrentPageChanged)
                    }
                    LazyRow(
                        state = rowState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 18.dp),
                        horizontalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        items(pageCount, key = { it }) { pageIndex ->
                            PdfPageItem(
                                pageInfo = state.pageInfoFor(pageIndex),
                                widthModifier = Modifier.width(pageWidth),
                                state = state,
                                tileBitmaps = tileBitmaps,
                                onRequestTiles = onRequestTiles,
                                onDoubleTapZoom = onDoubleTapZoom,
                                onToggleChrome = onToggleChrome,
                                onSelectText = onSelectText,
                                onAddNote = onAddNote,
                                onAddInk = onAddInk,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PdfPageItem(
    pageInfo: PdfPageInfo,
    widthModifier: Modifier,
    state: ReaderUiState,
    tileBitmaps: Map<TileKey, Bitmap>,
    onRequestTiles: (Int, Int, Int, Rect) -> Unit,
    onDoubleTapZoom: () -> Unit,
    onToggleChrome: () -> Unit,
    onSelectText: (Int, PdfPoint) -> Unit,
    onAddNote: (Int, PdfPoint, String) -> Unit,
    onAddInk: (Int, List<PdfPoint>) -> Unit,
) {
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.roundToPx() }
    val screenHeightPx = with(density) { configuration.screenHeightDp.dp.roundToPx() }
    var pageWidthPx by remember { mutableStateOf(0) }
    var pageHeightPx by remember { mutableStateOf(0) }
    var visibleRect by remember { mutableStateOf(Rect(0, 0, 0, 0)) }
    var notePoint by remember { mutableStateOf<PdfPoint?>(null) }
    val inkPoints = remember { mutableStateListOf<PdfPoint>() }

    LaunchedEffect(pageInfo.pageIndex, pageWidthPx, pageHeightPx, visibleRect, state.zoom, state.settings.darkMode) {
        if (pageWidthPx > 0 && pageHeightPx > 0 && visibleRect.width() > 0 && visibleRect.height() > 0) {
            onRequestTiles(pageInfo.pageIndex, pageWidthPx, pageHeightPx, visibleRect)
        }
    }

    Card(
        modifier = widthModifier
            .aspectRatio(pageInfo.aspectRatio.coerceAtLeast(0.1f))
            .onGloballyPositioned { coordinates ->
                pageWidthPx = coordinates.size.width
                pageHeightPx = coordinates.size.height
                val bounds = coordinates.boundsInWindow()
                val left = maxOf(0f, -bounds.left).roundToInt()
                val top = maxOf(0f, -bounds.top).roundToInt()
                val right = minOf(coordinates.size.width.toFloat(), screenWidthPx - bounds.left).roundToInt()
                val bottom = minOf(coordinates.size.height.toFloat(), screenHeightPx - bounds.top).roundToInt()
                visibleRect = Rect(left, top, right.coerceAtLeast(left), bottom.coerceAtLeast(top))
            },
        shape = RoundedCornerShape(4.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.White)
                .pointerInput(pageInfo.pageIndex, pageWidthPx, pageHeightPx, state.selectedTool) {
                    detectTapGestures(
                        onTap = { offset ->
                            val point = offset.toNormalizedPoint(pageWidthPx, pageHeightPx)
                            if (state.selectedTool == AnnotationTool.Note) {
                                notePoint = point
                            } else {
                                onToggleChrome()
                            }
                        },
                        onDoubleTap = { onDoubleTapZoom() },
                        onLongPress = { offset ->
                            onSelectText(pageInfo.pageIndex, offset.toNormalizedPoint(pageWidthPx, pageHeightPx))
                        },
                    )
                }
                .pointerInput(pageInfo.pageIndex, state.selectedTool, pageWidthPx, pageHeightPx) {
                    if (state.selectedTool == AnnotationTool.Ink) {
                        detectDragGestures(
                            onDragStart = { start ->
                                inkPoints.clear()
                                inkPoints += start.toNormalizedPoint(pageWidthPx, pageHeightPx)
                            },
                            onDrag = { change, _ ->
                                inkPoints += change.position.toNormalizedPoint(pageWidthPx, pageHeightPx)
                            },
                            onDragEnd = {
                                onAddInk(pageInfo.pageIndex, inkPoints.toList())
                                inkPoints.clear()
                            },
                            onDragCancel = { inkPoints.clear() },
                        )
                    }
                },
        ) {
            PdfPageCanvas(
                pageInfo = pageInfo,
                state = state,
                tileBitmaps = tileBitmaps,
                inProgressInk = inkPoints,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }

    notePoint?.let { point ->
        NoteDialog(
            onDismiss = { notePoint = null },
            onSave = { note ->
                onAddNote(pageInfo.pageIndex, point, note)
                notePoint = null
            },
        )
    }
}

@Composable
private fun PdfPageCanvas(
    pageInfo: PdfPageInfo,
    state: ReaderUiState,
    tileBitmaps: Map<TileKey, Bitmap>,
    inProgressInk: List<PdfPoint>,
    modifier: Modifier = Modifier,
) {
    val pageTiles by remember(tileBitmaps, pageInfo.pageIndex) {
        derivedStateOf {
            tileBitmaps.filterKeys { key ->
                key.pageIndex == pageInfo.pageIndex &&
                    key.pageWidthPx > 0 &&
                    key.pageHeightPx > 0
            }
        }
    }
    val pageSearchHits = state.searchHits.filter { it.pageIndex == pageInfo.pageIndex }
    val pageAnnotations = state.annotations.filter { it.pageIndex == pageInfo.pageIndex }
    Canvas(modifier) {
        drawRect(Color.White)
        pageTiles.forEach { (key, bitmap) ->
            drawImage(
                image = bitmap.asImageBitmap(),
                dstOffset = IntOffset(key.left, key.top),
                dstSize = IntSize(key.width, key.height),
                filterQuality = FilterQuality.High,
            )
        }
        pageSearchHits.forEach { hit ->
            val active = hit.id == state.activeSearchHit?.id
            hit.rects.forEach { rect ->
                drawNormalizedRect(
                    rect = rect,
                    color = if (active) Color(0xFFFF9800).copy(alpha = 0.42f) else Color(0xFFFFEB3B).copy(alpha = 0.30f),
                )
            }
        }
        state.selectedText?.takeIf { it.pageIndex == pageInfo.pageIndex }?.rects?.forEach { rect ->
            drawNormalizedRect(rect, Color(0xFF3B82F6).copy(alpha = 0.28f))
        }
        pageAnnotations.forEach { annotation ->
            drawAnnotation(annotation)
        }
        if (inProgressInk.size > 1) {
            drawInkPath(inProgressInk, Color(0xFFD32F2F), 4f)
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawAnnotation(annotation: ReaderAnnotation) {
    val color = Color(annotation.color)
    when (annotation.type) {
        AnnotationType.Highlight -> annotation.rects.forEach { drawNormalizedRect(it, color.copy(alpha = annotation.alpha)) }
        AnnotationType.Underline -> annotation.rects.forEach { rect ->
            val y = rect.bottom * size.height
            drawLine(
                color = color,
                start = Offset(rect.left * size.width, y),
                end = Offset(rect.right * size.width, y),
                strokeWidth = 3f,
            )
        }
        AnnotationType.Ink -> drawInkPath(annotation.inkPoints, color, annotation.alpha.coerceAtLeast(2f))
        AnnotationType.Note -> annotation.rects.firstOrNull()?.let { rect ->
            drawCircle(
                color = color,
                radius = 13f,
                center = Offset(rect.left * size.width, rect.top * size.height),
            )
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawNormalizedRect(rect: PdfRect, color: Color) {
    drawRect(
        color = color,
        topLeft = Offset(rect.left * size.width, rect.top * size.height),
        size = Size(
            width = ((rect.right - rect.left) * size.width).coerceAtLeast(2f),
            height = ((rect.bottom - rect.top) * size.height).coerceAtLeast(2f),
        ),
    )
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawInkPath(points: List<PdfPoint>, color: Color, strokeWidth: Float) {
    if (points.size < 2) return
    val path = Path().apply {
        moveTo(points.first().x * size.width, points.first().y * size.height)
        points.drop(1).forEach { point ->
            lineTo(point.x * size.width, point.y * size.height)
        }
    }
    drawPath(path, color, style = Stroke(width = strokeWidth))
}

@Composable
private fun ThumbnailSidebar(
    state: ReaderUiState,
    thumbnails: Map<Int, Bitmap>,
    onRequestThumbnail: (Int) -> Unit,
    onGoToPage: (Int) -> Unit,
) {
    Surface(
        modifier = Modifier
            .width(116.dp)
            .fillMaxHeight(),
        tonalElevation = 2.dp,
        color = MaterialTheme.colorScheme.surface,
    ) {
        LazyColumn(
            contentPadding = PaddingValues(vertical = 10.dp, horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(state.pageCount, key = { it }) { pageIndex ->
                LaunchedEffect(pageIndex) {
                    onRequestThumbnail(pageIndex)
                }
                val selected = pageIndex == state.currentPage
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onGoToPage(pageIndex) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Card(
                        shape = RoundedCornerShape(4.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.White,
                        ),
                    ) {
                        val thumbnail = thumbnails[pageIndex]
                        if (thumbnail != null) {
                            Image(
                                bitmap = thumbnail.asImageBitmap(),
                                contentDescription = "Page ${pageIndex + 1}",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(thumbnail.width.toFloat() / thumbnail.height.toFloat()),
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .height(126.dp)
                                    .fillMaxWidth()
                                    .background(Color.White),
                            )
                        }
                    }
                    Text(
                        "${pageIndex + 1}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchPanel(
    state: ReaderUiState,
    onQueryChanged: (String) -> Unit,
    onRunSearch: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        shape = RoundedCornerShape(8.dp),
        tonalElevation = 4.dp,
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = state.searchQuery,
                onValueChange = onQueryChanged,
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text("Search text") },
                trailingIcon = {
                    IconButton(onClick = onRunSearch) {
                        Icon(Icons.Default.Search, contentDescription = "Run search")
                    }
                },
            )
            IconButton(onClick = onPrevious, enabled = state.searchHits.isNotEmpty()) {
                Icon(Icons.Default.KeyboardArrowLeft, contentDescription = "Previous result")
            }
            Text(
                if (state.searchHits.isEmpty()) "0" else "${state.activeSearchIndex + 1}/${state.searchHits.size}",
                style = MaterialTheme.typography.labelMedium,
            )
            IconButton(onClick = onNext, enabled = state.searchHits.isNotEmpty()) {
                Icon(Icons.Default.KeyboardArrowRight, contentDescription = "Next result")
            }
            if (state.isIndexing) {
                Text(
                    "Indexing ${state.indexedPages}/${state.pageCount}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SelectionToolbar(
    selection: TextSelection,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onHighlight: () -> Unit,
    onUnderline: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    Surface(
        modifier = modifier
            .navigationBarsPadding()
            .padding(14.dp),
        shape = RoundedCornerShape(8.dp),
        tonalElevation = 4.dp,
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                selection.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(140.dp),
            )
            IconButton(
                onClick = {
                    clipboard.setText(AnnotatedString(selection.text))
                    onCopy()
                },
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = "Copy")
            }
            IconButton(
                onClick = {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, selection.text)
                    }
                    context.startActivity(Intent.createChooser(intent, "Share text"))
                    onShare()
                },
            ) {
                Icon(Icons.Default.Share, contentDescription = "Share selected text")
            }
            IconButton(onClick = onHighlight) {
                Icon(Icons.Default.FormatColorFill, contentDescription = "Highlight")
            }
            IconButton(onClick = onUnderline) {
                Icon(Icons.Default.FormatUnderlined, contentDescription = "Underline")
            }
        }
    }
}

@Composable
private fun GoToPageDialog(
    pageCount: Int,
    onDismiss: () -> Unit,
    onGoTo: (Int) -> Unit,
) {
    var value by remember { mutableStateOf("") }
    val page = value.toIntOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Go to page") },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it.filter(Char::isDigit) },
                label = { Text("Page 1-$pageCount") },
                singleLine = true,
            )
        },
        confirmButton = {
            Button(
                onClick = { onGoTo((page ?: 1) - 1) },
                enabled = page != null && page in 1..pageCount,
            ) {
                Text("Go")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun NoteDialog(
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var note by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add note") },
        text = {
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("Comment") },
                minLines = 3,
            )
        },
        confirmButton = {
            Button(onClick = { onSave(note) }, enabled = note.isNotBlank()) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

private fun ReaderUiState.pageInfoFor(pageIndex: Int): PdfPageInfo =
    pageInfos.firstOrNull { it.pageIndex == pageIndex }
        ?: pageInfos.firstOrNull()?.copy(pageIndex = pageIndex)
        ?: PdfPageInfo(pageIndex = pageIndex, widthPoints = 612, heightPoints = 792)

private fun Offset.toNormalizedPoint(widthPx: Int, heightPx: Int): PdfPoint =
    PdfPoint(
        x = if (widthPx <= 0) 0f else (x / widthPx).coerceIn(0f, 1f),
        y = if (heightPx <= 0) 0f else (y / heightPx).coerceIn(0f, 1f),
    )
