package com.renameapk.pdfzip

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.text.format.Formatter
import android.util.LruCache
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.ItemTouchHelper
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.FileOutputStream
import java.io.BufferedOutputStream
import com.google.android.material.card.MaterialCardView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.renameapk.pdfzip.databinding.ActivityPdfViewerBinding
import com.renameapk.pdfzip.databinding.DialogJumpToPageBinding
import com.renameapk.pdfzip.databinding.ItemPdfPageBinding
import com.renameapk.pdfzip.databinding.ItemPdfThumbnailBinding
import com.renameapk.pdfzip.databinding.ItemPdfGridPageBinding
import com.renameapk.pdfzip.databinding.SheetViewerPagesGridBinding
import com.renameapk.pdfzip.databinding.SheetDeletePageConfirmBinding
import com.renameapk.pdfzip.databinding.SheetReaderToolsBinding
import com.renameapk.pdfzip.databinding.SheetReaderSettingsBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

private const val PAGE_PLACEHOLDER_ASPECT_RATIO = 1.26f
private const val PAGE_PLACEHOLDER_MIN_HEIGHT_DP = 320f
private const val PAGE_PLACEHOLDER_MAX_HEIGHT_RATIO = 0.82f
private const val PAGE_ACTIVE_RENDER_BUFFER = 1
private const val PAGE_PREFETCH_BUFFER = 1
private const val THUMBNAIL_PREFETCH_BUFFER = 3

class PdfViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPdfViewerBinding
    private lateinit var pageAdapter: PdfPageAdapter
    private lateinit var thumbnailAdapter: PdfThumbnailAdapter

    private val renderMutex = Mutex()
    private val pageCacheSizeKb = (Runtime.getRuntime().maxMemory() / 1024 / 4)
        .coerceIn(DEFAULT_CACHE_SIZE_KB.toLong(), 192L * 1024L)
        .toInt()
    private val thumbnailCacheSizeKb = (Runtime.getRuntime().maxMemory() / 1024 / 24)
        .coerceIn(THUMBNAIL_CACHE_SIZE_KB.toLong(), 24L * 1024L)
        .toInt()
    // Hard cap on the pixel count of a single rendered page bitmap so a large
    // or high-resolution page in a heavy PDF can never allocate enough memory
    // to OOM-kill the process. Scales with the available heap.
    private val maxPageBitmapPixels = (Runtime.getRuntime().maxMemory() / 8 / 4)
        .coerceIn(2_000_000L, 8_000_000L)
    private val pageCache = object : LruCache<Int, Bitmap>(pageCacheSizeKb) {
        override fun sizeOf(key: Int, value: Bitmap): Int = value.allocationByteCount / 1024
    }
    private val thumbnailCache = object : LruCache<Int, Bitmap>(thumbnailCacheSizeKb) {
        override fun sizeOf(key: Int, value: Bitmap): Int = value.allocationByteCount / 1024
    }

    private var pdfUri: Uri? = null
    private var projectLookupUri: Uri? = null
    private var pdfName: String = ""
    private var pdfSizeBytes: Long? = null
    private var renderer: PdfiumPageRenderer? = null
    private var pageCount: Int = 0
    private var projectLinkMap: Map<Int, List<MarkupOperation.Link>> = emptyMap()
    private var isSeekBarTracking = false
    private var isReaderChromeVisible = true
    private var startInReadMode = false
    private var currentReaderMode = ReaderLibraryStore.ReaderMode.SCROLL
    private var toolbarBasePaddingLeft = 0
    private var toolbarBasePaddingTop = 0
    private var toolbarBasePaddingRight = 0
    private var controlsBasePaddingLeft = 0
    private var controlsBasePaddingRight = 0
    private var controlsBasePaddingBottom = 0
    private var quickActionsBaseBottomMargin = 0
    private var quickActionsBaseEndMargin = 0
    private var currentPageIndex = 0
    private var readerQuickActionsDialog: BottomSheetDialog? = null
    private var jumpToPageDialog: AlertDialog? = null
    private var pagesGridDialog: BottomSheetDialog? = null
    private var pendingPageImageExportIndex: Int? = null
    private var documentZoomScale = 1f
    private var currentReaderFitMode = ReaderLibraryStore.ReaderFitMode.FIT_PAGE
    private var isThumbnailStripExpanded = false
    private val pagePrefetchJobs = mutableMapOf<Int, Job>()
    private val thumbnailPrefetchJobs = mutableMapOf<Int, Job>()
    private lateinit var pageLayoutManager: LinearLayoutManager
    private val pageScrollListener = object : RecyclerView.OnScrollListener() {
        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            syncCurrentPageFromScroll()
        }
    }

    private val createPageImageLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
            val pageIndex = pendingPageImageExportIndex
            pendingPageImageExportIndex = null
            if (uri == null || pageIndex == null) {
                return@registerForActivityResult
            }
            exportSinglePageAsImage(pageIndex, uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppThemeStore.applySavedTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityPdfViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        captureBaseSpacing()
        setupWindowInsets()
        currentReaderMode = ReaderLibraryStore.ReaderMode.SCROLL
        currentReaderFitMode = ReaderLibraryStore.getReaderFitMode(this)
        resolveIntentData(intent)
        applyWindowMode()
        setupToolbar()
        setupPager()
        setupThumbnailStrip()
        setupBookmarkStrip()
        setupControls()
        setupQuickActions()
        setupBackNavigation()
        applyReaderMode(currentReaderMode, persistSelection = false)
        loadPdf()
    }

    override fun onPause() {
        if (::binding.isInitialized && pageCount > 0) {
            persistReadingProgress(currentPageIndex)
        }
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (::binding.isInitialized) {
            applyWindowMode()
        }
    }



    override fun onStop() {
        if (::binding.isInitialized) {
            WindowInsetsControllerCompat(window, binding.root)
                .show(WindowInsetsCompat.Type.systemBars())
        }
        super.onStop()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Shed bitmap caches under memory pressure so the system reclaims
        // memory from us instead of killing the process. Cached bitmaps that
        // are still attached to a visible ImageView are not recycled here, so
        // this is safe to call at any time.
        if (level >= TRIM_MEMORY_RUNNING_LOW) {
            thumbnailCache.evictAll()
        }
        if (level >= TRIM_MEMORY_RUNNING_CRITICAL) {
            pageCache.evictAll()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readerQuickActionsDialog?.dismiss()
        jumpToPageDialog?.dismiss()
        cancelPrefetchJobs(pagePrefetchJobs)
        cancelPrefetchJobs(thumbnailPrefetchJobs)
        closeCurrentPdf()
        recycleCachedBitmaps()
        resolveIntentData(intent)
        applyWindowMode()
        resetViewerUi()
        loadPdf()
    }

    private fun resolveIntentData(intent: Intent) {
        val incomingUri = intent.data ?: intent.getStringExtra(EXTRA_PDF_URI)?.let(Uri::parse)
        startInReadMode = true
        pdfUri = incomingUri
        projectLookupUri = incomingUri
        pdfName = intent.getStringExtra(EXTRA_PDF_NAME).orEmpty().ifBlank {
            incomingUri?.let(::queryDisplayName) ?: getString(R.string.fallback_pdf_name)
        }
        pdfSizeBytes = incomingUri?.let(::queryFileSize)

        if (incomingUri != null) {
            runCatching {
                contentResolver.takePersistableUriPermission(
                    incomingUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
        }
    }

    private fun setupToolbar() {
        binding.viewerToolbar.title = pdfName
        binding.viewerToolbar.setNavigationOnClickListener { handleViewerBackPressed() }
        
        binding.viewerToolbar.inflateMenu(R.menu.viewer_menu)
        binding.viewerToolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_bookmark -> {
                    val currentUri = pdfUri
                    if (currentUri != null && pageCount > 0) {
                        val bookmarks = ReaderLibraryStore.toggleBookmark(this, currentUri, currentPageIndex)
                        val isBookmarked = currentPageIndex in bookmarks
                        item.setIcon(if (isBookmarked) R.drawable.ic_star_20 else R.drawable.ic_star_outline_20)
                        val msg = if (isBookmarked) {
                            getString(R.string.viewer_add_bookmark)
                        } else {
                            getString(R.string.viewer_remove_bookmark)
                        }
                        showMessage(msg)
                    }
                    true
                }
                R.id.action_search -> {
                    showJumpToPageDialog()
                    true
                }
                R.id.action_grid -> {
                    showPagesGridDialog()
                    true
                }
                R.id.action_share -> {
                    shareCurrentDocument()
                    true
                }
                R.id.action_more -> {
                    showSettingsDialog()
                    true
                }
                else -> false
            }
        }
    }

    private fun toggleThumbnailStrip() {
        isThumbnailStripExpanded = !isThumbnailStripExpanded
        updateThumbnailStripVisibility()
        if (isThumbnailStripExpanded && pageCount > 0) {
            updateThumbnailStripUi(currentPageIndex, smoothScroll = false)
        }
    }

    private fun showPagesGridDialog() {
        if (pageCount <= 0) {
            return
        }
        pagesGridDialog?.dismiss()
        val sheetBinding = SheetViewerPagesGridBinding.inflate(layoutInflater)
        val dialog = BottomSheetDialog(this)
        pagesGridDialog = dialog

        sheetBinding.gridTitle.text = pdfName

        val historyStack = mutableListOf<List<Int>>()
        val redoStack = mutableListOf<List<Int>>()

        lateinit var adapter: PdfGridAdapter

        fun updateUndoRedoUi() {
            sheetBinding.gridSubtitle.text = getString(R.string.viewer_jump_pages_badge, adapter.itemCount)
            val hasChanges = adapter.getPages() != (0 until pageCount).toList()
            sheetBinding.gridEditButton.isEnabled = hasChanges
            sheetBinding.gridUndoButton.isEnabled = historyStack.isNotEmpty()
            sheetBinding.gridRedoButton.isEnabled = redoStack.isNotEmpty()
            sheetBinding.gridUndoButton.alpha = if (historyStack.isNotEmpty()) 1.0f else 0.35f
            sheetBinding.gridRedoButton.alpha = if (redoStack.isNotEmpty()) 1.0f else 0.35f
        }

        adapter = PdfGridAdapter(
            scope = lifecycleScope,
            renderThumbnail = ::renderThumbnailBitmap,
            cachedThumbnailBitmap = { pageIndex ->
                thumbnailCache.get(pageIndex)?.takeIf { !it.isRecycled }
            },
            onPageTapped = { position, pageIndex ->
                val hasChanges = adapter.getPages() != (0 until pageCount).toList()
                if (hasChanges) {
                    savePagesDirectly(dialog, sheetBinding, adapter, position)
                } else {
                    scrollToPage(pageIndex, smooth = false)
                    dialog.dismiss()
                }
            },
            onDeleteTapped = { position ->
                if (adapter.itemCount <= 1) {
                    Toast.makeText(this@PdfViewerActivity, "PDF must have at least one page.", Toast.LENGTH_SHORT).show()
                } else {
                    showDeletePageConfirmSheet(position) {
                        historyStack.add(adapter.getPages().toList())
                        redoStack.clear()
                        adapter.removeItem(position)
                    }
                }
            },
            onPagesChanged = {
                updateUndoRedoUi()
            }
        )

        sheetBinding.pagesGridRecyclerView.layoutManager = GridLayoutManager(this, 3)
        sheetBinding.pagesGridRecyclerView.adapter = adapter
        adapter.submitPages((0 until pageCount).toList(), currentPageIndex)

        sheetBinding.pagesGridRecyclerView.scrollToPosition(currentPageIndex)

        sheetBinding.gridCloseButton.setOnClickListener {
            dialog.dismiss()
        }
        
        sheetBinding.gridEditButton.isEnabled = false
        sheetBinding.gridEditButton.setOnClickListener {
            val hasChanges = adapter.getPages() != (0 until pageCount).toList()
            if (hasChanges) {
                savePagesDirectly(dialog, sheetBinding, adapter, null)
            } else {
                dialog.dismiss()
            }
        }

        sheetBinding.gridUndoButton.setOnClickListener {
            if (historyStack.isNotEmpty()) {
                val lastState = historyStack.removeAt(historyStack.lastIndex)
                redoStack.add(adapter.getPages().toList())
                adapter.submitPages(lastState, currentPageIndex)
                updateUndoRedoUi()
            }
        }

        sheetBinding.gridRedoButton.setOnClickListener {
            if (redoStack.isNotEmpty()) {
                val nextState = redoStack.removeAt(redoStack.lastIndex)
                historyStack.add(adapter.getPages().toList())
                adapter.submitPages(nextState, currentPageIndex)
                updateUndoRedoUi()
            }
        }

        val itemTouchHelperCallback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT,
            0
        ) {
            private var dragStartIndexList: List<Int>? = null

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                    dragStartIndexList = adapter.getPages().toList()
                }
            }

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val fromPos = viewHolder.bindingAdapterPosition
                val toPos = target.bindingAdapterPosition
                if (fromPos == RecyclerView.NO_POSITION || toPos == RecyclerView.NO_POSITION) return false
                adapter.moveItem(fromPos, toPos)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                // Not used
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                adapter.notifyDataSetChanged()
                val startList = dragStartIndexList
                if (startList != null) {
                    val currentList = adapter.getPages().toList()
                    if (startList != currentList) {
                        historyStack.add(startList)
                        redoStack.clear()
                        updateUndoRedoUi()
                    }
                    dragStartIndexList = null
                }
            }
        }
        val itemTouchHelper = ItemTouchHelper(itemTouchHelperCallback)
        itemTouchHelper.attachToRecyclerView(sheetBinding.pagesGridRecyclerView)

        updateUndoRedoUi()

        dialog.setContentView(sheetBinding.root)
        dialog.setOnDismissListener {
            if (pagesGridDialog === dialog) {
                pagesGridDialog = null
            }
        }
        dialog.show()
    }

    private fun savePagesDirectly(
        dialog: BottomSheetDialog,
        sheetBinding: SheetViewerPagesGridBinding,
        adapter: PdfGridAdapter,
        targetPositionAfterSave: Int?
    ) {
        val currentUri = pdfUri ?: return
        
        dialog.setCancelable(false)
        sheetBinding.gridCloseButton.isEnabled = false
        sheetBinding.gridEditButton.isEnabled = false
        sheetBinding.gridEditButton.text = "Saving..."
        
        lifecycleScope.launch {
            val reorderedPages = adapter.getPages().toList()
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val context = this@PdfViewerActivity
                    val localPdfFile = LocalPdfStore.requireLocalFile(context, currentUri)
                    
                    PDDocument.load(localPdfFile).use { document ->
                        val originalPageCount = document.numberOfPages
                        val pageObjects = (0 until originalPageCount).map { document.getPage(it) }
                        
                        for (i in (originalPageCount - 1) downTo 0) {
                            document.removePage(i)
                        }
                        
                        reorderedPages.forEach { oldPageIndex ->
                            if (oldPageIndex in pageObjects.indices) {
                                document.addPage(pageObjects[oldPageIndex])
                            }
                        }
                        
                        val tempFile = java.io.File(context.cacheDir, "temp_reorder_${System.currentTimeMillis()}.pdf")
                        tempFile.parentFile?.mkdirs()
                        FileOutputStream(tempFile).use { fos ->
                            BufferedOutputStream(fos).use { bos ->
                                document.save(bos)
                            }
                        }
                        
                        withContext(Dispatchers.Main) {
                            closeCurrentPdf()
                            recycleCachedBitmaps()
                        }
                        
                        context.contentResolver.openOutputStream(currentUri)?.use { os ->
                            tempFile.inputStream().use { input ->
                                input.copyTo(os)
                            }
                        } ?: throw IOException("Cannot write to original PDF file")
                        
                        tempFile.copyTo(localPdfFile, overwrite = true)
                        tempFile.delete()
                    }
                    reorderedPages.size
                }
            }
            
            result.onSuccess { newSize ->
                dialog.dismiss()
                
                val oldPageIndex = currentPageIndex
                val newPageIndex = if (targetPositionAfterSave != null) {
                    targetPositionAfterSave.coerceIn(0, newSize - 1)
                } else {
                    val newIndexInReordered = reorderedPages.indexOf(oldPageIndex)
                    if (newIndexInReordered != -1) {
                        newIndexInReordered
                    } else {
                        oldPageIndex.coerceIn(0, newSize - 1)
                    }
                }
                
                persistReadingProgress(newPageIndex)
                loadPdf()
                Toast.makeText(this@PdfViewerActivity, "Changes saved successfully", Toast.LENGTH_SHORT).show()
            }.onFailure { error ->
                dialog.setCancelable(true)
                sheetBinding.gridCloseButton.isEnabled = true
                sheetBinding.gridEditButton.isEnabled = true
                sheetBinding.gridEditButton.text = "Save Changes"
                Toast.makeText(
                    this@PdfViewerActivity,
                    "Failed to save changes: ${error.localizedMessage}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun showDeletePageConfirmSheet(pagePosition: Int, onConfirmed: () -> Unit) {
        val sheetBinding = SheetDeletePageConfirmBinding.inflate(layoutInflater)
        val dialog = BottomSheetDialog(this)
        
        sheetBinding.confirmTitle.text = "Delete Page ${pagePosition + 1}?"
        
        sheetBinding.confirmCancelButton.setOnClickListener {
            dialog.dismiss()
        }
        
        sheetBinding.confirmDeleteButton.setOnClickListener {
            dialog.dismiss()
            onConfirmed()
        }
        
        dialog.setContentView(sheetBinding.root)
        dialog.show()
    }

    private fun showSettingsDialog() {
        val sheetBinding = SheetReaderSettingsBinding.inflate(layoutInflater)
        val dialog = BottomSheetDialog(this)
        
        sheetBinding.settingsDocName.text = pdfName
        sheetBinding.settingsDocDetails.text = buildDocumentMeta()

        val currentTheme = AppThemeStore.getThemeMode(this)
        val checkedId = when (currentTheme) {
            AppThemeStore.ThemeMode.SYSTEM -> R.id.themeSystemButton
            AppThemeStore.ThemeMode.LIGHT -> R.id.themeLightButton
            AppThemeStore.ThemeMode.DARK -> R.id.themeDarkButton
        }
        sheetBinding.settingsThemeToggleGroup.check(checkedId)

        sheetBinding.settingsThemeToggleGroup.addOnButtonCheckedListener { _, checkedButtonId, isChecked ->
            if (isChecked) {
                val chosenMode = when (checkedButtonId) {
                    R.id.themeSystemButton -> AppThemeStore.ThemeMode.SYSTEM
                    R.id.themeLightButton -> AppThemeStore.ThemeMode.LIGHT
                    R.id.themeDarkButton -> AppThemeStore.ThemeMode.DARK
                    else -> AppThemeStore.ThemeMode.SYSTEM
                }
                if (chosenMode != AppThemeStore.getThemeMode(this)) {
                    AppThemeStore.setThemeMode(this@PdfViewerActivity, chosenMode)
                    dialog.dismiss()
                }
            }
        }

        val currentUri = pdfUri
        val bookmarksList = if (currentUri != null) {
            ReaderLibraryStore.getBookmarks(this, currentUri).toList().sorted()
        } else {
            emptyList()
        }

        sheetBinding.settingsNoBookmarksText.isVisible = bookmarksList.isEmpty()
        sheetBinding.settingsBookmarksRecyclerView.isVisible = bookmarksList.isNotEmpty()

        if (bookmarksList.isNotEmpty()) {
            val bookmarkAdapter = BookmarkPageAdapter { targetPageIndex ->
                scrollToPage(targetPageIndex, smooth = false)
                dialog.dismiss()
            }
            sheetBinding.settingsBookmarksRecyclerView.layoutManager =
                LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
            sheetBinding.settingsBookmarksRecyclerView.adapter = bookmarkAdapter
            bookmarkAdapter.submitPages(bookmarksList)
        }

        dialog.setContentView(sheetBinding.root)
        dialog.show()
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this) {
            handleViewerBackPressed()
        }
    }

    private fun handleViewerBackPressed() {
        when {
            readerQuickActionsDialog?.isShowing == true -> readerQuickActionsDialog?.dismiss()
            jumpToPageDialog?.isShowing == true -> jumpToPageDialog?.dismiss()
            pagesGridDialog?.isShowing == true -> pagesGridDialog?.dismiss()
            else -> finish()
        }
    }

    private fun setupPager() {
        pageAdapter = PdfPageAdapter(
            scope = lifecycleScope,
            renderPage = ::renderPageBitmap,
            cachedPageBitmap = { pageIndex ->
                pageCache.get(pageIndex)?.takeIf { !it.isRecycled }
            },
            onZoomChanged = ::handlePageZoomChanged,
            onDocumentScaleChanged = ::handleDocumentScaleChanged,
            onScaleGestureFinished = ::handleGlobalScaleFinished,
            currentDocumentScale = { documentZoomScale },
            currentFitMode = { toZoomableFitMode(currentReaderFitMode) },
            fitPageHeightLimit = {
                (binding.viewerPager.height - binding.viewerPager.paddingTop - binding.viewerPager.paddingBottom)
                    .coerceAtLeast(resources.displayMetrics.heightPixels / 2)
            },
            onPageTappedAt = ::handlePageTap,
            onPageTapped = ::toggleReaderChrome,
            onPageLongPressed = ::promptSavePageImage
        )

        pageLayoutManager = LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false)
        pageLayoutManager.isItemPrefetchEnabled = false
        binding.viewerPager.layoutManager = pageLayoutManager
        binding.viewerPager.adapter = pageAdapter
        binding.viewerPager.addOnScrollListener(pageScrollListener)
        binding.viewerPager.setItemViewCacheSize(1)
        binding.viewerPager.itemAnimator = null
        binding.viewerPager.userScrollEnabled = true

        binding.viewerPager.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            val oldHeight = oldBottom - oldTop
            val newHeight = bottom - top
            if (newHeight > 0 && newHeight != oldHeight) {
                pageAdapter.updateItemHeights(binding.viewerPager)
            }
        }
    }

    private fun setupThumbnailStrip() {
        thumbnailAdapter = PdfThumbnailAdapter(
            scope = lifecycleScope,
            renderThumbnail = ::renderThumbnailBitmap,
            cachedThumbnailBitmap = { pageIndex ->
                thumbnailCache.get(pageIndex)?.takeIf { !it.isRecycled }
            },
            onThumbnailTapped = { pageIndex ->
                scrollToPage(pageIndex, smooth = false)
            }
        )
        binding.thumbnailRecyclerView.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false).apply {
                initialPrefetchItemCount = 4
            }
        binding.thumbnailRecyclerView.adapter = thumbnailAdapter
        binding.thumbnailRecyclerView.setItemViewCacheSize(4)
        binding.thumbnailRecyclerView.itemAnimator = null
    }

    private fun setupBookmarkStrip() {
        // Bookmarks are now integrated as an option menu action item
    }

    private fun setupControls() {
        binding.previousPageButton.setOnClickListener {
            if (pageCount > 0) {
                scrollToPage((currentPageIndex - 1).coerceAtLeast(0))
            }
        }

        binding.nextPageButton.setOnClickListener {
            if (pageCount > 0) {
                scrollToPage((currentPageIndex + 1).coerceAtMost(pageCount - 1))
            }
        }

        binding.jumpToPageButton.setOnClickListener {
            showJumpToPageDialog()
        }

        binding.shareDocumentButton.setOnClickListener {
            shareCurrentDocument()
        }

        binding.toggleThumbnailStripButton.setOnClickListener {
            toggleThumbnailStrip()
        }

        binding.viewerFitModeToggleGroup.check(
            if (currentReaderFitMode == ReaderLibraryStore.ReaderFitMode.FIT_WIDTH) {
                R.id.readerFitWidthButton
            } else {
                R.id.readerFitPageButton
            }
        )
        binding.viewerFitModeToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) {
                return@addOnButtonCheckedListener
            }
            val mode = if (checkedId == R.id.readerFitWidthButton) {
                ReaderLibraryStore.ReaderFitMode.FIT_WIDTH
            } else {
                ReaderLibraryStore.ReaderFitMode.FIT_PAGE
            }
            applyReaderFitMode(mode)
        }
        binding.resetReaderZoomButton.setOnClickListener {
            resetReaderZoom()
        }

        binding.pageSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && progress in 0 until pageCount) {
                    scrollToPage(progress, smooth = false)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isSeekBarTracking = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isSeekBarTracking = false
                updateReaderUi(currentPageIndex, false)
            }
        })
    }

    private fun setupQuickActions() {
        binding.readerQuickActionsButton.setOnClickListener {
            showReaderQuickActions()
        }
    }

    private fun showReaderQuickActions() {
        readerQuickActionsDialog?.dismiss()
        val sheetBinding = SheetReaderToolsBinding.inflate(layoutInflater)
        val dialog = BottomSheetDialog(this)
        readerQuickActionsDialog = dialog
        dialog.setContentView(sheetBinding.root)

        sheetBinding.readerToolsDocumentName.text = pdfName
        sheetBinding.readerToolsDocumentMeta.text = buildDocumentMeta()
        sheetBinding.readerToolsPageBadge.text = getString(R.string.viewer_page_counter, currentPageIndex + 1, pageCount)

        fun dismissThen(action: () -> Unit) {
            dialog.dismiss()
            action()
        }

        sheetBinding.toolCompressCard.setOnClickListener {
            dismissThen { openCurrentPdfTool(MainActivity.TOOL_MODE_COMPRESS) }
        }
        sheetBinding.toolZipCard.setOnClickListener {
            dismissThen { openCurrentPdfTool(MainActivity.TOOL_MODE_ZIP) }
        }
        sheetBinding.toolPreflightCard.setOnClickListener {
            dismissThen { openCurrentPdfTool(MainActivity.TOOL_MODE_PREFLIGHT) }
        }
        sheetBinding.toolEditCard.setOnClickListener {
            dismissThen { openCurrentPdfEditor() }
        }

        dialog.setOnShowListener {
            val bottomSheet =
                dialog.findViewById<android.widget.FrameLayout>(com.google.android.material.R.id.design_bottom_sheet)
                    ?: return@setOnShowListener
            bottomSheet.setBackgroundColor(Color.TRANSPARENT)
            BottomSheetBehavior.from(bottomSheet).apply {
                skipCollapsed = true
                state = BottomSheetBehavior.STATE_EXPANDED
            }
        }
        dialog.setOnDismissListener {
            if (readerQuickActionsDialog === dialog) {
                readerQuickActionsDialog = null
            }
        }
        dialog.show()
    }

    private fun openCurrentPdfTool(toolMode: String) {
        val currentUri = pdfUri ?: return
        persistReadingProgress(currentPageIndex)
        startActivity(
            MainActivity.createToolIntent(
                context = this,
                pdfUri = currentUri,
                pdfName = pdfName,
                toolMode = toolMode
            )
        )
    }

    private fun openCurrentPdfEditor() {
        val currentUri = pdfUri ?: return
        persistReadingProgress(currentPageIndex)
        startActivity(
            PdfEditActivity.createIntent(
                context = this,
                pdfUri = currentUri,
                pdfName = pdfName
            )
        )
    }

    private fun openCurrentPdfEditorForReorder() {
        val currentUri = pdfUri ?: return
        persistReadingProgress(currentPageIndex)
        startActivity(
            PdfEditActivity.createReorderIntent(
                context = this,
                pdfUri = currentUri,
                pdfName = pdfName
            )
        )
    }

    private fun applyReaderMode(
        _mode: ReaderLibraryStore.ReaderMode,
        persistSelection: Boolean = true
    ) {
        currentReaderMode = ReaderLibraryStore.ReaderMode.SCROLL
        if (persistSelection) {
            ReaderLibraryStore.setReaderMode(this, currentReaderMode)
        }

        val checkedButtonId = R.id.scrollModeButton
        if (binding.readerModeToggleGroup.checkedButtonId != checkedButtonId) {
            binding.readerModeToggleGroup.check(checkedButtonId)
        }
        scrollToPage(currentPageIndex, smooth = false)
    }

    private fun resetViewerUi() {
        pageCount = 0
        currentPageIndex = 0
        documentZoomScale = 1f
        projectLinkMap = emptyMap()
        cancelPrefetchJobs(pagePrefetchJobs)
        cancelPrefetchJobs(thumbnailPrefetchJobs)
        binding.viewerPager.userScrollEnabled = true
        setReaderChromeVisible(!startInReadMode)
        binding.viewerToolbar.title = pdfName
        binding.viewerToolbar.subtitle = null
        binding.viewerLoadingGroup.isVisible = true
        binding.viewerErrorText.isVisible = false
        binding.viewerPager.isVisible = true
        binding.viewerDocumentMeta.text = getString(R.string.viewer_loading_pdf)
        binding.viewerPageIndicator.text = getString(R.string.viewer_loading_pdf)
        binding.pageSeekBar.max = 0
        binding.pageSeekBar.progress = 0
        binding.pageSeekBar.isEnabled = false
        binding.previousPageButton.isEnabled = false
        binding.nextPageButton.isEnabled = false
        binding.jumpToPageButton.isEnabled = false
        binding.shareDocumentButton.isEnabled = pdfUri != null
        binding.toggleThumbnailStripButton.isEnabled = false
        isThumbnailStripExpanded = false
        binding.thumbnailStripContainer.isVisible = false
        binding.viewerZoomHint.isVisible = false
        binding.viewerFitControlsRow.isVisible = false
        binding.thumbnailStripMetaText.text = getString(R.string.viewer_thumbnail_strip_hint)
        binding.toggleThumbnailStripButton.text = getString(R.string.viewer_show_pages)
        binding.resetReaderZoomButton.isEnabled = false
        pageAdapter.submitPages(emptyList())
        thumbnailAdapter.submitPages(emptyList())
        applyReaderMode(currentReaderMode, persistSelection = false)
    }

    private fun loadPdf() {
        resetViewerUi()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { openPdf() }
            }

            result.onSuccess { openedRenderer ->
                renderer = openedRenderer
                pageCount = openedRenderer.pageCount
                projectLinkMap = resolveProjectLinkMap()

                pageAdapter.submitPages((0 until pageCount).toList())
                thumbnailAdapter.submitPages((0 until pageCount).toList())

                binding.viewerToolbar.title = pdfName
                binding.viewerLoadingGroup.isVisible = false
                binding.viewerErrorText.isVisible = false
                binding.viewerPager.isVisible = true
                binding.pageSeekBar.max = (pageCount - 1).coerceAtLeast(0)
                binding.pageSeekBar.isEnabled = pageCount > 1
                binding.jumpToPageButton.isEnabled = pageCount > 0
                binding.shareDocumentButton.isEnabled = pdfUri != null
                binding.toggleThumbnailStripButton.isEnabled = pageCount > 1
                binding.viewerDocumentMeta.text = buildDocumentMeta()
                updateThumbnailStripVisibility()
                binding.viewerFitControlsRow.isVisible = pageCount > 0
                binding.resetReaderZoomButton.isEnabled = pageCount > 0
                updateZoomHint()

                val initialPage = pdfUri?.let { uri ->
                    ReaderLibraryStore.getLastPage(this@PdfViewerActivity, uri)
                }?.coerceIn(0, (pageCount - 1).coerceAtLeast(0)) ?: 0

                binding.viewerPager.post {
                    applyReaderFitMode(currentReaderFitMode, persistSelection = false)
                    scrollToPage(initialPage, smooth = false)
                    updateReaderUi(initialPage, false)
                    refreshVisibleRenderers(initialPage)
                    persistReadingProgress(initialPage)
                }
            }.onFailure { error ->
                val message = getString(
                    R.string.viewer_open_failed,
                    error.message ?: getString(R.string.unknown_error)
                )
                setReaderChromeVisible(true)
                binding.viewerLoadingGroup.isVisible = false
                binding.viewerPager.isVisible = false
                binding.viewerControlsPanel.isVisible = false
                binding.viewerErrorText.isVisible = true
                binding.viewerErrorText.text = message
            }
        }
    }

    private suspend fun openPdf(): PdfiumPageRenderer {
        val inputUri = pdfUri ?: throw IOException(getString(R.string.cannot_open_pdf))
        // Zero-copy open: render straight from the source descriptor. No more
        // duplicating the whole file into internal storage, so 800 MB - 1 GB
        // PDFs open instantly and never fail for lack of free space.
        val descriptor = LocalPdfStore.openSourceDescriptor(this, inputUri)
            ?: throw IOException(getString(R.string.cannot_open_pdf))
        if (pdfName.isBlank()) {
            pdfName = LocalPdfStore.queryDisplayName(this, inputUri).orEmpty()
        }
        pdfSizeBytes = LocalPdfStore.queryFileSize(this, inputUri)
        val pageRenderer = try {
            PdfiumPageRenderer.open(descriptor)
        } catch (error: Throwable) {
            runCatching { descriptor.close() }
            throw IOException(getString(R.string.cannot_open_pdf), error)
        }
        if (pageRenderer.pageCount == 0) {
            pageRenderer.close()
            throw IOException(getString(R.string.empty_pdf))
        }
        return pageRenderer
    }

    private suspend fun renderPageBitmap(pageIndex: Int, targetWidth: Int): Bitmap {
        pageCache.get(pageIndex)?.takeIf { !it.isRecycled }?.let { return it }
        val activeRenderer = renderer ?: throw IOException(getString(R.string.cannot_open_pdf))
        // Render above the on-screen width so the page stays sharp when the
        // user pinch-zooms in. The per-bitmap pixel budget still caps the
        // allocation, so this can't blow up memory on huge documents.
        val safeTargetWidth = (
            targetWidth.coerceAtLeast(resources.displayMetrics.widthPixels) * VIEWER_RENDER_QUALITY
            ).roundToInt()

        return withContext(Dispatchers.IO) {
            renderMutex.withLock {
                pageCache.get(pageIndex)?.takeIf { !it.isRecycled }?.let { return@withLock it }
                val bitmap = renderPageWithFallback(
                    activeRenderer = activeRenderer,
                    pageIndex = pageIndex,
                    targetWidth = safeTargetWidth.toFloat(),
                    maxDimension = PAGE_MAX_DIMENSION,
                )
                pageCache.put(pageIndex, bitmap)
                bitmap
            }
        }
    }

    private fun shouldTrimWhitespaceForPage(pageIndex: Int): Boolean {
        return false
    }

    private suspend fun renderThumbnailBitmap(pageIndex: Int): Bitmap {
        thumbnailCache.get(pageIndex)?.takeIf { !it.isRecycled }?.let { return it }
        val activeRenderer = renderer ?: throw IOException(getString(R.string.cannot_open_pdf))

        return withContext(Dispatchers.IO) {
            renderMutex.withLock {
                thumbnailCache.get(pageIndex)?.takeIf { !it.isRecycled }?.let { return@withLock it }
                val bitmap = renderPageWithFallback(
                    activeRenderer = activeRenderer,
                    pageIndex = pageIndex,
                    targetWidth = THUMBNAIL_TARGET_WIDTH.toFloat(),
                    maxDimension = THUMBNAIL_MAX_DIMENSION,
                )
                thumbnailCache.put(pageIndex, bitmap)
                bitmap
            }
        }
    }

    private suspend fun renderPageWithFallback(
        activeRenderer: PdfiumPageRenderer,
        pageIndex: Int,
        targetWidth: Float,
        maxDimension: Float,
    ): Bitmap {
        if (pageIndex < 0 || pageIndex >= activeRenderer.pageCount) {
            throw IOException("Page index $pageIndex is out of bounds (0..${activeRenderer.pageCount - 1})")
        }
        var lastError: Throwable? = null
        RENDER_FALLBACK_FACTORS.forEach { factor ->
            try {
                // Shrink both the max dimension and the pixel budget on each
                // retry so a page that just OOM'd gets a genuinely smaller
                // allocation the next time around.
                return activeRenderer.renderPage(
                    pageIndex = pageIndex,
                    targetWidth = targetWidth,
                    maxDimension = maxDimension * factor,
                    maxPixels = (maxPageBitmapPixels * factor * factor).toLong(),
                )
            } catch (cancellation: CancellationException) {
                // The render was cancelled (e.g. the user swiped to another
                // page before this one finished). This is normal control flow,
                // not a render failure: let it propagate so structured
                // concurrency stays intact and we don't log a bogus error.
                throw cancellation
            } catch (error: Throwable) {
                lastError = error
                if (error is OutOfMemoryError) {
                    // Free whatever we can so the next (smaller) attempt has
                    // room, then nudge the collector before retrying.
                    pageCache.evictAll()
                    thumbnailCache.evictAll()
                    System.gc()
                }
            }
        }
        throw IOException("Failed to render page $pageIndex", lastError)
    }

    private fun shouldTrimViewerWhitespace(bitmap: Bitmap): Boolean {
        val pixelCount = bitmap.width.toLong() * bitmap.height.toLong()
        return pixelCount in 1 until VIEWER_TRIM_MAX_PIXELS
    }

    private fun trimViewerWhitespace(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) {
            return bitmap
        }

        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        fun rowHasContent(row: Int): Boolean {
            val start = row * width
            for (column in 0 until width) {
                if (!isMostlyWhite(pixels[start + column])) {
                    return true
                }
            }
            return false
        }

        fun columnHasContent(column: Int, top: Int, bottom: Int): Boolean {
            for (row in top..bottom) {
                if (!isMostlyWhite(pixels[row * width + column])) {
                    return true
                }
            }
            return false
        }

        var top = 0
        while (top < height && !rowHasContent(top)) {
            top += 1
        }
        if (top >= height) {
            return bitmap
        }

        var bottom = height - 1
        while (bottom > top && !rowHasContent(bottom)) {
            bottom -= 1
        }

        var left = 0
        while (left < width && !columnHasContent(left, top, bottom)) {
            left += 1
        }

        var right = width - 1
        while (right > left && !columnHasContent(right, top, bottom)) {
            right -= 1
        }

        val padding = (resources.displayMetrics.density * VIEWER_TRIM_PADDING_DP).roundToInt()
        val croppedLeft = (left - padding).coerceAtLeast(0)
        val croppedTop = (top - padding).coerceAtLeast(0)
        val croppedRight = (right + padding).coerceAtMost(width - 1)
        val croppedBottom = (bottom + padding).coerceAtMost(height - 1)

        if (
            croppedLeft == 0 &&
            croppedTop == 0 &&
            croppedRight == width - 1 &&
            croppedBottom == height - 1
        ) {
            return bitmap
        }

        val trimmedBitmap = Bitmap.createBitmap(
            bitmap,
            croppedLeft,
            croppedTop,
            croppedRight - croppedLeft + 1,
            croppedBottom - croppedTop + 1
        )
        bitmap.recycle()
        return trimmedBitmap
    }

    private fun isMostlyWhite(pixel: Int): Boolean {
        val alpha = Color.alpha(pixel)
        if (alpha <= WHITE_TRIM_ALPHA_THRESHOLD) {
            return true
        }
        return Color.red(pixel) >= WHITE_TRIM_COLOR_THRESHOLD &&
            Color.green(pixel) >= WHITE_TRIM_COLOR_THRESHOLD &&
            Color.blue(pixel) >= WHITE_TRIM_COLOR_THRESHOLD
    }

    private fun updateReaderUi(position: Int, smoothScrollThumbnails: Boolean = true) {
        if (pageCount <= 0) {
            binding.viewerToolbar.subtitle = null
            binding.viewerPageIndicator.text = getString(R.string.viewer_loading_pdf)
            binding.previousPageButton.isEnabled = false
            binding.nextPageButton.isEnabled = false
            updateBookmarkUi(-1)
            return
        }

        currentPageIndex = position

        binding.viewerToolbar.subtitle = getString(
            R.string.viewer_page_counter,
            position + 1,
            pageCount
        )
        binding.viewerPageIndicator.text = getString(
            R.string.viewer_page_counter,
            position + 1,
            pageCount
        )

        if (!isSeekBarTracking) {
            binding.pageSeekBar.progress = position
        }

        binding.viewerDocumentMeta.text = buildDocumentMeta()
        binding.previousPageButton.isEnabled = position > 0
        binding.nextPageButton.isEnabled = position < pageCount - 1
        updateThumbnailStripUi(position, smoothScrollThumbnails)

        updateBookmarkUi(position)
        persistReadingProgress(position)
    }

    private fun updateThumbnailStripUi(pageIndex: Int, smoothScroll: Boolean) {
        thumbnailAdapter.setSelectedPage(pageIndex)
        binding.thumbnailStripMetaText.text = getString(
            R.string.viewer_thumbnail_strip_meta,
            getString(R.string.viewer_page_short, pageIndex + 1)
        )
        if (isThumbnailStripExpanded) {
            centerThumbnailOnPage(pageIndex, smoothScroll)
        }
    }

    private fun updateThumbnailStripVisibility() {
        val canShowStrip = isReaderChromeVisible && pageCount > 1
        binding.thumbnailStripContainer.isVisible = canShowStrip && isThumbnailStripExpanded
        binding.toggleThumbnailStripButton.isVisible = canShowStrip
        binding.toggleThumbnailStripButton.text = getString(
            if (isThumbnailStripExpanded) {
                R.string.viewer_hide_pages
            } else {
                R.string.viewer_show_pages
            }
        )
    }

    private fun centerThumbnailOnPage(pageIndex: Int, smoothScroll: Boolean) {
        val layoutManager =
            binding.thumbnailRecyclerView.layoutManager as? LinearLayoutManager ?: return
        binding.thumbnailRecyclerView.post {
            val child = layoutManager.findViewByPosition(pageIndex)
            if (child != null) {
                val targetOffset =
                    child.left - (binding.thumbnailRecyclerView.width - child.width) / 2
                if (smoothScroll) {
                    binding.thumbnailRecyclerView.smoothScrollBy(targetOffset, 0)
                } else {
                    binding.thumbnailRecyclerView.scrollBy(targetOffset, 0)
                }
            } else if (smoothScroll) {
                binding.thumbnailRecyclerView.smoothScrollToPosition(pageIndex)
            } else {
                layoutManager.scrollToPositionWithOffset(pageIndex, 32)
            }
        }
    }

    private fun buildDocumentMeta(): String {
        if (pageCount <= 0) {
            return getString(R.string.viewer_loading_pdf)
        }
        val sizeLabel = pdfSizeBytes?.let(::formatFileSize)
        return if (sizeLabel != null) {
            getString(R.string.viewer_document_meta, pageCount, sizeLabel)
        } else {
            getString(R.string.viewer_document_meta_no_size, pageCount)
        }
    }

    private fun updateBookmarkUi(position: Int) {
        if (position < 0) return
        val currentUri = pdfUri ?: return
        val bookmarks = ReaderLibraryStore.getBookmarks(this, currentUri)
        val isBookmarked = position in bookmarks
        val bookmarkItem = binding.viewerToolbar.menu.findItem(R.id.action_bookmark)
        bookmarkItem?.setIcon(if (isBookmarked) R.drawable.ic_star_20 else R.drawable.ic_star_outline_20)
    }

    private fun showJumpToPageDialog() {
        if (pageCount <= 0) {
            return
        }

        jumpToPageDialog?.dismiss()

        val dialogBinding = DialogJumpToPageBinding.inflate(layoutInflater)
        dialogBinding.jumpPageRangeBadge.text = getString(R.string.viewer_jump_pages_badge, pageCount)
        dialogBinding.jumpPageInput.setText((currentPageIndex + 1).toString())
        dialogBinding.jumpPageInput.setSelection(dialogBinding.jumpPageInput.text?.length ?: 0)
        dialogBinding.jumpFirstButton.setOnClickListener {
            dialogBinding.jumpPageInput.setText("1")
            dialogBinding.jumpPageInput.setSelection(dialogBinding.jumpPageInput.text?.length ?: 0)
        }
        dialogBinding.jumpCurrentButton.setOnClickListener {
            dialogBinding.jumpPageInput.setText((currentPageIndex + 1).toString())
            dialogBinding.jumpPageInput.setSelection(dialogBinding.jumpPageInput.text?.length ?: 0)
        }
        dialogBinding.jumpLastButton.setOnClickListener {
            dialogBinding.jumpPageInput.setText(pageCount.toString())
            dialogBinding.jumpPageInput.setSelection(dialogBinding.jumpPageInput.text?.length ?: 0)
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogBinding.root)
            .create()
        jumpToPageDialog = dialog
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        dialogBinding.jumpCancelButton.setOnClickListener {
            dialog.dismiss()
        }
        dialogBinding.jumpConfirmButton.setOnClickListener {
            val pageNumber = dialogBinding.jumpPageInput.text?.toString()?.trim()?.toIntOrNull()
            if (pageNumber == null || pageNumber !in 1..pageCount) {
                dialogBinding.jumpPageInputLayout.error =
                    getString(R.string.viewer_jump_error, pageCount)
                return@setOnClickListener
            }

            dialogBinding.jumpPageInputLayout.error = null
            scrollToPage(pageNumber - 1, smooth = false)
            dialog.dismiss()
        }

        dialog.setOnShowListener {
            dialogBinding.jumpPageInput.requestFocus()
        }
        dialog.setOnDismissListener {
            if (jumpToPageDialog === dialog) {
                jumpToPageDialog = null
            }
        }
        dialog.show()
    }

    private fun promptSavePageImage(pageIndex: Int) {
        if (pageIndex !in 0 until pageCount) {
            return
        }
        pendingPageImageExportIndex = pageIndex
        createPageImageLauncher.launch(buildPageImageFileName(pageIndex))
    }

    private fun handlePageTap(
        pageIndex: Int,
        pageImage: ZoomableImageView,
        tapX: Float,
        tapY: Float
    ): Boolean {
        val tappedLink = findTappedProjectLink(
            pageIndex = pageIndex,
            pageImage = pageImage,
            tapX = tapX,
            tapY = tapY
        ) ?: return false
        openProjectLink(tappedLink.url)
        return true
    }

    private fun findTappedProjectLink(
        pageIndex: Int,
        pageImage: ZoomableImageView,
        tapX: Float,
        tapY: Float
    ): MarkupOperation.Link? {
        val pageLinks = projectLinkMap[pageIndex].orEmpty()
        if (pageLinks.isEmpty()) {
            return null
        }
        val tapRatios = pageImage.mapPointToDrawableRatios(tapX, tapY) ?: return null
        val drawable = pageImage.drawable ?: return null
        val pageWidth = drawable.intrinsicWidth.toFloat().coerceAtLeast(1f)
        val pageHeight = drawable.intrinsicHeight.toFloat().coerceAtLeast(1f)
        val scaledTapX = tapRatios.x * pageWidth
        val scaledTapY = tapRatios.y * pageHeight
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            textAlign = Paint.Align.LEFT
        }
        val touchPadding = min(pageWidth, pageHeight) * 0.014f

        return pageLinks
            .asReversed()
            .firstOrNull { link ->
                computeProjectLinkBounds(
                    operation = link,
                    pageWidth = pageWidth,
                    pageHeight = pageHeight,
                    textPaint = textPaint,
                    touchPadding = touchPadding
                ).contains(scaledTapX, scaledTapY)
            }
    }

    private fun computeProjectLinkBounds(
        operation: MarkupOperation.Link,
        pageWidth: Float,
        pageHeight: Float,
        textPaint: Paint,
        touchPadding: Float
    ): RectF {
        val minDimension = min(pageWidth, pageHeight).coerceAtLeast(1f)
        textPaint.textSize =
            (minDimension * operation.textSizeRatio).coerceIn(10f, 350f)
        textPaint.typeface = if (operation.isBold) {
            Typeface.DEFAULT_BOLD
        } else {
            Typeface.DEFAULT
        }
        textPaint.isFakeBoldText = operation.isBold
        val textWidth = textPaint.measureText(operation.text).coerceAtLeast(touchPadding * 2f)
        val fontMetrics = textPaint.fontMetrics
        val x = operation.xRatio * pageWidth
        val y = operation.yRatio * pageHeight
        return RectF(
            x - touchPadding,
            y + fontMetrics.ascent - touchPadding,
            x + textWidth + touchPadding,
            y + fontMetrics.descent + touchPadding
        )
    }

    private fun openProjectLink(url: String) {
        runCatching {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    addCategory(Intent.CATEGORY_BROWSABLE)
                }
            )
        }.onFailure { error ->
            showMessage(
                getString(
                    R.string.viewer_link_open_failed,
                    error.message ?: getString(R.string.unknown_error)
                )
            )
        }
    }

    private fun resolveProjectLinkMap(): Map<Int, List<MarkupOperation.Link>> {
        val project = sequenceOf(projectLookupUri, pdfUri)
            .filterNotNull()
            .distinct()
            .mapNotNull { candidateUri ->
                VisualEditProjectStore.loadProject(this, candidateUri)
            }
            .firstOrNull() ?: return emptyMap()

        val exportPageIndexes = (0 until project.pageCount)
            .filterNot { it in project.deletedPages }

        return buildMap {
            exportPageIndexes.forEachIndexed { exportedIndex, editorPageIndex ->
                val links = project.pageMarkups[editorPageIndex]
                    ?.operations
                    ?.filterIsInstance<MarkupOperation.Link>()
                    .orEmpty()
                if (links.isNotEmpty()) {
                    put(exportedIndex, links)
                }
            }
        }
    }

    private fun exportSinglePageAsImage(pageIndex: Int, outputUri: Uri) {
        val activeRenderer = renderer ?: return
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.openOutputStream(outputUri)?.use { outputStream ->
                        renderMutex.withLock {
                            val (pageWidthPts, _) = activeRenderer.pageSizePoints(pageIndex)
                            val targetWidth = max(
                                resources.displayMetrics.widthPixels * 2,
                                pageWidthPts
                            )
                            val bitmap = renderPageWithFallback(
                                activeRenderer = activeRenderer,
                                pageIndex = pageIndex,
                                targetWidth = targetWidth.toFloat(),
                                maxDimension = PAGE_MAX_DIMENSION,
                            )
                            try {
                                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)) {
                                    throw IOException(getString(R.string.viewer_save_page_image_failed, getString(R.string.unknown_error)))
                                }
                            } finally {
                                bitmap.recycle()
                            }
                        }
                    } ?: throw IOException(getString(R.string.cannot_write_pdf))
                }
            }

            result.onSuccess {
                showMessage(getString(R.string.viewer_save_page_image_success, pageIndex + 1))
            }.onFailure { error ->
                showMessage(
                    getString(
                        R.string.viewer_save_page_image_failed,
                        error.message ?: getString(R.string.unknown_error)
                    )
                )
            }
        }
    }

    private fun buildPageImageFileName(pageIndex: Int): String {
        val baseName = pdfName
            .replace(Regex("\\.pdf$", RegexOption.IGNORE_CASE), "")
            .trim()
            .ifBlank { "document" }
        return "${baseName}_page_${pageIndex + 1}.png"
    }

    private fun shareCurrentDocument() {
        val currentUri = pdfUri ?: return
        runCatching {
            val shareUri = LocalPdfStore.createShareUri(this, currentUri)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, shareUri)
                clipData = ClipData.newUri(contentResolver, pdfName, shareUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, getString(R.string.viewer_share_chooser)))
        }.onFailure { error ->
            showMessage(
                getString(
                    R.string.viewer_open_failed,
                    error.message ?: getString(R.string.unknown_error)
                )
            )
        }
    }

    private fun persistReadingProgress(pageIndex: Int) {
        val currentUri = pdfUri ?: return
        if (pageCount <= 0) {
            return
        }

        ReaderLibraryStore.updateRecentDocument(
            context = this,
            uri = currentUri,
            displayName = pdfName,
            pageCount = pageCount,
            lastPageIndex = pageIndex.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        )
    }

    private fun scrollToPage(pageIndex: Int, smooth: Boolean = true) {
        if (!::binding.isInitialized || pageCount <= 0) {
            return
        }

        val targetPage = pageIndex.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        if (smooth) {
            binding.viewerPager.smoothScrollToPosition(targetPage)
        } else {
            pageLayoutManager.scrollToPositionWithOffset(targetPage, 0)
        }
        updateReaderUi(targetPage, smooth)
        refreshVisibleRenderers(targetPage)
    }

    private fun syncCurrentPageFromScroll() {
        if (!::binding.isInitialized || pageCount <= 0) {
            return
        }

        val firstVisible = pageLayoutManager.findFirstVisibleItemPosition()
        val lastVisible = pageLayoutManager.findLastVisibleItemPosition()
        if (firstVisible == RecyclerView.NO_POSITION || lastVisible == RecyclerView.NO_POSITION) {
            return
        }

        var bestPage = currentPageIndex
        var bestVisibleHeight = -1
        for (position in firstVisible..lastVisible) {
            val child = pageLayoutManager.findViewByPosition(position) ?: continue
            val visibleTop = max(child.top, binding.viewerPager.paddingTop)
            val visibleBottom = min(child.bottom, binding.viewerPager.height - binding.viewerPager.paddingBottom)
            val visibleHeight = (visibleBottom - visibleTop).coerceAtLeast(0)
            if (visibleHeight > bestVisibleHeight) {
                bestVisibleHeight = visibleHeight
                bestPage = position
            }
        }

        pageAdapter.updateActiveRenderWindow(
            firstVisible = firstVisible,
            lastVisible = lastVisible,
            extraBuffer = PAGE_ACTIVE_RENDER_BUFFER
        )
        schedulePagePrefetch(bestPage)
        scheduleThumbnailPrefetch(bestPage)

        if (bestPage != currentPageIndex) {
            updateReaderUi(bestPage)
        }
    }

    private fun refreshVisibleRenderers(anchorPage: Int = currentPageIndex) {
        if (pageCount <= 0) {
            return
        }
        val safeAnchor = anchorPage.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        val firstVisible = pageLayoutManager.findFirstVisibleItemPosition()
            .takeIf { it != RecyclerView.NO_POSITION }
            ?: safeAnchor
        val lastVisible = pageLayoutManager.findLastVisibleItemPosition()
            .takeIf { it != RecyclerView.NO_POSITION }
            ?: safeAnchor
        pageAdapter.updateActiveRenderWindow(
            firstVisible = firstVisible,
            lastVisible = lastVisible,
            extraBuffer = PAGE_ACTIVE_RENDER_BUFFER
        )
        schedulePagePrefetch(safeAnchor)
        scheduleThumbnailPrefetch(safeAnchor)
    }

    private fun schedulePagePrefetch(anchorPage: Int) {
        if (pageCount <= 1) {
            cancelPrefetchJobs(pagePrefetchJobs)
            return
        }

        val targetWidth = if (binding.viewerPager.width > 0) {
            binding.viewerPager.width
        } else {
            resources.displayMetrics.widthPixels
        }
        val targetPages = ((anchorPage - PAGE_PREFETCH_BUFFER)..(anchorPage + PAGE_PREFETCH_BUFFER))
            .filter { it in 0 until pageCount && it != anchorPage && !isBitmapCached(pageCache, it) }
            .toSet()
        syncPrefetchJobs(
            activeJobs = pagePrefetchJobs,
            targetPages = targetPages
        ) { pageIndex ->
            renderPageBitmap(pageIndex, targetWidth)
        }
    }

    private fun scheduleThumbnailPrefetch(anchorPage: Int) {
        if (pageCount <= 1) {
            cancelPrefetchJobs(thumbnailPrefetchJobs)
            return
        }
        val targetPages = ((anchorPage - THUMBNAIL_PREFETCH_BUFFER)..(anchorPage + THUMBNAIL_PREFETCH_BUFFER))
            .filter { it in 0 until pageCount && !isBitmapCached(thumbnailCache, it) }
            .toSet()
        syncPrefetchJobs(
            activeJobs = thumbnailPrefetchJobs,
            targetPages = targetPages
        ) { pageIndex ->
            renderThumbnailBitmap(pageIndex)
        }
    }

    private fun syncPrefetchJobs(
        activeJobs: MutableMap<Int, Job>,
        targetPages: Set<Int>,
        renderBlock: suspend (Int) -> Unit
    ) {
        val stalePages = activeJobs.keys - targetPages
        stalePages.forEach { pageIndex ->
            activeJobs.remove(pageIndex)?.cancel()
        }
        targetPages.forEach { pageIndex ->
            if (activeJobs.containsKey(pageIndex)) {
                return@forEach
            }
            activeJobs[pageIndex] = lifecycleScope.launch {
                try {
                    renderBlock(pageIndex)
                } catch (e: Exception) {
                    // Suppress prefetch errors (normal if renderer gets closed or document changes)
                } finally {
                    activeJobs.remove(pageIndex)
                }
            }
        }
    }

    private fun cancelPrefetchJobs(activeJobs: MutableMap<Int, Job>) {
        activeJobs.values.forEach { it.cancel() }
        activeJobs.clear()
    }

    private fun isBitmapCached(cache: LruCache<Int, Bitmap>, pageIndex: Int): Boolean {
        return cache.get(pageIndex)?.isRecycled == false
    }

    private fun toggleReaderChrome() {
        setReaderChromeVisible(!isReaderChromeVisible)
    }

    private fun setReaderChromeVisible(isVisible: Boolean) {
        isReaderChromeVisible = isVisible
        binding.viewerToolbar.isVisible = isVisible
        binding.viewerControlsPanel.isVisible = false
        binding.readerQuickActionsButton.isVisible = startInReadMode && !isVisible
        updateThumbnailStripVisibility()
        updateZoomHint()
        syncSystemBars()
    }

    private fun applyReaderFitMode(
        mode: ReaderLibraryStore.ReaderFitMode,
        persistSelection: Boolean = true
    ) {
        currentReaderFitMode = mode
        if (persistSelection) {
            ReaderLibraryStore.setReaderFitMode(this, mode)
        }
        val checkedButtonId = if (mode == ReaderLibraryStore.ReaderFitMode.FIT_WIDTH) {
            R.id.readerFitWidthButton
        } else {
            R.id.readerFitPageButton
        }
        if (binding.viewerFitModeToggleGroup.checkedButtonId != checkedButtonId) {
            binding.viewerFitModeToggleGroup.check(checkedButtonId)
        }
        documentZoomScale = 1f
        if (pageCount > 0) {
            binding.viewerPager.userScrollEnabled = true
            pageAdapter.applyDocumentTransform(
                recyclerView = binding.viewerPager,
                scale = documentZoomScale,
                fitMode = toZoomableFitMode(mode)
            )
        }
        updateZoomHint()
    }

    private fun resetReaderZoom() {
        documentZoomScale = 1f
        binding.viewerPager.userScrollEnabled = true
        pageAdapter.applyDocumentTransform(
            recyclerView = binding.viewerPager,
            scale = documentZoomScale,
            fitMode = toZoomableFitMode(currentReaderFitMode)
        )
        updateZoomHint()
    }

    private fun handlePageZoomChanged(pageIndex: Int, isZoomed: Boolean) {
        // With document-level zoom, ZoomableImageView normalizedScale stays
        // at 1.0 after gesture end (applyDocumentTransform resets it).
        // The zoom state is managed through documentZoomScale and page card
        // height, so we only need to update the hint.
        updateZoomHint()
    }

    private fun handleGlobalScaleFinished(pageIndex: Int, scale: Float) {
        val clampedScale = scale.coerceIn(1f, 4f)
        documentZoomScale = clampedScale
        // Apply the document scale to ALL visible page holders (including the
        // page the user just pinched). Each holder resets its ZoomableImageView
        // back to fit-mode and then applies the shared document scale, so every
        // page shows its full content at the same zoom level – just like
        // Google Docs viewer.
        pageAdapter.applyDocumentTransform(
            recyclerView = binding.viewerPager,
            scale = documentZoomScale,
            fitMode = toZoomableFitMode(currentReaderFitMode)
        )
        pageAdapter.updateItemHeights(binding.viewerPager)
        updateZoomHint()
    }

    private fun updateZoomHint() {
        val isZoomActive = documentZoomScale > 1.01f
        binding.viewerZoomHint.isVisible = pageCount > 0 && isReaderChromeVisible && isZoomActive
        binding.viewerZoomHint.text = if (isZoomActive) {
            getString(R.string.viewer_zoom_active_hint)
        } else {
            getString(R.string.viewer_zoom_hint)
        }
    }

    private fun handleDocumentScaleChanged(pageIndex: Int, scale: Float) {
        // The page under the gesture scales itself smoothly through its own
        // matrix (ZoomableImageView). Propagating the scale to every visible
        // holder and relaying out the list on each gesture frame is what made
        // zoom feel slow and stuttery, so we only track the lightweight state
        // here and let the focused view own the visual zoom.
        val clampedScale = scale.coerceIn(1f, 4f)
        if (abs(clampedScale - documentZoomScale) < 0.01f) {
            return
        }
        documentZoomScale = clampedScale
        updateZoomHint()
    }

    private fun toZoomableFitMode(
        mode: ReaderLibraryStore.ReaderFitMode
    ): ZoomableImageView.FitMode {
        return if (mode == ReaderLibraryStore.ReaderFitMode.FIT_WIDTH) {
            ZoomableImageView.FitMode.FIT_WIDTH
        } else {
            ZoomableImageView.FitMode.FIT_PAGE
        }
    }

    private fun syncSystemBars() {
        val insetsController = WindowInsetsControllerCompat(window, binding.root).apply {
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        if (!startInReadMode || isReaderChromeVisible) {
            insetsController.show(WindowInsetsCompat.Type.systemBars())
        } else {
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun captureBaseSpacing() {
        toolbarBasePaddingLeft = binding.viewerToolbar.paddingLeft
        toolbarBasePaddingTop = binding.viewerToolbar.paddingTop
        toolbarBasePaddingRight = binding.viewerToolbar.paddingRight
        controlsBasePaddingLeft = binding.viewerControlsPanel.paddingLeft
        controlsBasePaddingRight = binding.viewerControlsPanel.paddingRight
        controlsBasePaddingBottom = binding.viewerControlsPanel.paddingBottom
        val quickActionsLayoutParams =
            binding.readerQuickActionsButton.layoutParams as ViewGroup.MarginLayoutParams
        quickActionsBaseBottomMargin = quickActionsLayoutParams.bottomMargin
        quickActionsBaseEndMargin = quickActionsLayoutParams.marginEnd
    }

    private fun setupWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val sideInsetLeft = if (startInReadMode) systemBars.left else 0
            val sideInsetRight = if (startInReadMode) systemBars.right else 0
            val topInset = if (startInReadMode) systemBars.top else 0
            val bottomInset = if (startInReadMode) systemBars.bottom else 0

            binding.viewerToolbar.updatePadding(
                left = toolbarBasePaddingLeft + sideInsetLeft,
                top = toolbarBasePaddingTop + topInset,
                right = toolbarBasePaddingRight + sideInsetRight
            )
            binding.viewerControlsPanel.updatePadding(
                left = controlsBasePaddingLeft + sideInsetLeft,
                right = controlsBasePaddingRight + sideInsetRight,
                bottom = controlsBasePaddingBottom + bottomInset
            )
            binding.readerQuickActionsButton.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = quickActionsBaseBottomMargin + bottomInset
                marginEnd = quickActionsBaseEndMargin + sideInsetRight
            }
            insets
        }
    }

    private fun applyWindowMode() {
        WindowCompat.setDecorFitsSystemWindows(window, !startInReadMode)
        ViewCompat.requestApplyInsets(binding.root)
        syncSystemBars()
    }

    private fun queryDisplayName(uri: Uri): String? {
        return LocalPdfStore.queryDisplayName(this, uri)
    }

    private fun queryFileSize(uri: Uri): Long? {
        return LocalPdfStore.queryFileSize(this, uri)
    }

    private fun formatFileSize(sizeBytes: Long): String {
        return Formatter.formatShortFileSize(this, sizeBytes)
    }

    private fun showMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        readerQuickActionsDialog?.dismiss()
        jumpToPageDialog?.dismiss()
        pagesGridDialog?.dismiss()
        cancelPrefetchJobs(pagePrefetchJobs)
        cancelPrefetchJobs(thumbnailPrefetchJobs)
        binding.viewerPager.removeOnScrollListener(pageScrollListener)
        binding.viewerPager.adapter = null
        binding.thumbnailRecyclerView.adapter = null
        closeCurrentPdf()
        recycleCachedBitmaps()
        super.onDestroy()
    }

    private fun closeCurrentPdf() {
        renderer?.close()
        renderer = null
        pageCount = 0
    }

    private fun recycleCachedBitmaps() {
        pageCache.evictAll()
        thumbnailCache.evictAll()
    }

    companion object {
        private const val EXTRA_PDF_URI = "extra_pdf_uri"
        private const val EXTRA_PDF_NAME = "extra_pdf_name"
        private const val EXTRA_START_IN_READ_MODE = "extra_start_in_read_mode"
        private const val DEFAULT_CACHE_SIZE_KB = 24 * 1024
        private const val THUMBNAIL_CACHE_SIZE_KB = 6 * 1024
        private const val PAGE_MAX_DIMENSION = 4000f
        private const val VIEWER_RENDER_QUALITY = 2f
        private const val THUMBNAIL_MAX_DIMENSION = 420f
        private const val THUMBNAIL_TARGET_WIDTH = 220
        private const val VIEWER_TRIM_PADDING_DP = 8f
        private const val VIEWER_TRIM_MAX_PIXELS = 3_500_000L
        private const val WHITE_TRIM_COLOR_THRESHOLD = 248
        private const val WHITE_TRIM_ALPHA_THRESHOLD = 8
        private const val MIN_RENDER_SCALE = 0.2f
        private val RENDER_FALLBACK_FACTORS = floatArrayOf(1f, 0.82f, 0.68f, 0.55f, 0.42f)

        fun createIntent(
            context: Context,
            pdfUri: Uri,
            pdfName: String,
            startInReadMode: Boolean = false
        ): Intent {
            return Intent(context, PdfViewerActivity::class.java).apply {
                data = pdfUri
                putExtra(EXTRA_PDF_URI, pdfUri.toString())
                putExtra(EXTRA_PDF_NAME, pdfName)
                putExtra(EXTRA_START_IN_READ_MODE, startInReadMode)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }
}

private class PdfPageAdapter(
    private val scope: CoroutineScope,
    private val renderPage: suspend (pageIndex: Int, targetWidth: Int) -> Bitmap,
    private val cachedPageBitmap: (pageIndex: Int) -> Bitmap?,
    private val onZoomChanged: (Int, Boolean) -> Unit,
    private val onDocumentScaleChanged: (Int, Float) -> Unit,
    private val onScaleGestureFinished: (Int, Float) -> Unit,
    private val currentDocumentScale: () -> Float,
    private val currentFitMode: () -> ZoomableImageView.FitMode,
    private val fitPageHeightLimit: () -> Int,
    private val onPageTappedAt: (Int, ZoomableImageView, Float, Float) -> Boolean,
    private val onPageTapped: () -> Unit,
    private val onPageLongPressed: (Int) -> Unit
) : RecyclerView.Adapter<PdfPageAdapter.PageViewHolder>() {

    private val pages = mutableListOf<Int>()
    private var activeRenderStart = 0
    private var activeRenderEnd = -1
    private var attachedRecyclerView: RecyclerView? = null
    private val pendingChangedPositions = linkedSetOf<Int>()
    private var notifyFlushScheduled = false
    private val notifyFlushRunnable = Runnable {
        flushPendingItemChanges()
    }

    init {
        setHasStableIds(true)
    }

    fun submitPages(newPages: List<Int>) {
        pages.clear()
        pages.addAll(newPages)
        activeRenderStart = 0
        activeRenderEnd = -1
        pendingChangedPositions.clear()
        notifyDataSetChanged()
    }

    fun updateActiveRenderWindow(firstVisible: Int, lastVisible: Int, extraBuffer: Int) {
        if (pages.isEmpty()) {
            activeRenderStart = 0
            activeRenderEnd = -1
            return
        }

        val safeFirst = firstVisible.coerceIn(0, pages.lastIndex)
        val safeLast = lastVisible.coerceIn(safeFirst, pages.lastIndex)
        val newStart = (safeFirst - extraBuffer).coerceAtLeast(0)
        val newEnd = (safeLast + extraBuffer).coerceAtMost(pages.lastIndex)
        if (newStart == activeRenderStart && newEnd == activeRenderEnd) {
            return
        }

        val oldStart = activeRenderStart
        val oldEnd = activeRenderEnd
        activeRenderStart = newStart
        activeRenderEnd = newEnd

        val affectedPositions = linkedSetOf<Int>()
        // Notify items that transitioned from active to inactive
        if (oldEnd >= oldStart) {
            for (position in oldStart..oldEnd) {
                if (position !in newStart..newEnd) {
                    affectedPositions += position
                }
            }
        }
        // Notify items that transitioned from inactive to active
        for (position in newStart..newEnd) {
            if (oldEnd < oldStart || position !in oldStart..oldEnd) {
                affectedPositions += position
            }
        }
        affectedPositions.forEach { position ->
            if (position in pages.indices) {
                notifyItemChangedSafely(position)
            }
        }
    }

    private fun notifyItemChangedSafely(position: Int) {
        if (position !in pages.indices) {
            return
        }
        val recyclerView = attachedRecyclerView
        if (recyclerView == null) {
            notifyItemChanged(position)
            return
        }
        pendingChangedPositions += position
        if (!notifyFlushScheduled) {
            notifyFlushScheduled = true
            recyclerView.post(notifyFlushRunnable)
        }
    }

    private fun flushPendingItemChanges() {
        val recyclerView = attachedRecyclerView
        if (recyclerView == null) {
            notifyFlushScheduled = false
            pendingChangedPositions.clear()
            return
        }
        if (recyclerView.isComputingLayout) {
            recyclerView.post(notifyFlushRunnable)
            return
        }
        notifyFlushScheduled = false
        val pendingPositions = pendingChangedPositions.toList()
        pendingChangedPositions.clear()
        pendingPositions.forEach { position ->
            if (position in pages.indices) {
                notifyItemChanged(position)
            }
        }
        if (pendingChangedPositions.isNotEmpty() && !notifyFlushScheduled) {
            notifyFlushScheduled = true
            recyclerView.post(notifyFlushRunnable)
        }
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        attachedRecyclerView = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        recyclerView.removeCallbacks(notifyFlushRunnable)
        if (attachedRecyclerView === recyclerView) {
            attachedRecyclerView = null
        }
        pendingChangedPositions.clear()
        notifyFlushScheduled = false
        super.onDetachedFromRecyclerView(recyclerView)
    }

    fun applyDocumentTransform(
        recyclerView: RecyclerView,
        scale: Float,
        fitMode: ZoomableImageView.FitMode,
        excludedPageIndex: Int? = null
    ) {
        for (childIndex in 0 until recyclerView.childCount) {
            val child = recyclerView.getChildAt(childIndex) ?: continue
            val holder = recyclerView.getChildViewHolder(child) as? PageViewHolder ?: continue
            if (excludedPageIndex != null && holder.boundPageIndex == excludedPageIndex) {
                continue
            }
            holder.applyDocumentTransform(scale, fitMode)
        }
    }

    fun updateItemHeights(recyclerView: RecyclerView) {
        for (childIndex in 0 until recyclerView.childCount) {
            val child = recyclerView.getChildAt(childIndex) ?: continue
            val holder = recyclerView.getChildViewHolder(child) as? PageViewHolder ?: continue
            holder.updateItemHeight()
        }
    }

    fun updatePageLayout(recyclerView: RecyclerView, pageIndex: Int) {
        for (childIndex in 0 until recyclerView.childCount) {
            val child = recyclerView.getChildAt(childIndex) ?: continue
            val holder = recyclerView.getChildViewHolder(child) as? PageViewHolder ?: continue
            if (holder.boundPageIndex == pageIndex) {
                holder.updateItemHeight()
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
        val binding = ItemPdfPageBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return PageViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
        holder.bind(pages[position], position)
    }

    override fun onViewRecycled(holder: PageViewHolder) {
        holder.recycle()
        super.onViewRecycled(holder)
    }

    override fun getItemCount(): Int = pages.size

    override fun getItemId(position: Int): Long = pages[position].toLong()

    inner class PageViewHolder(
        private val binding: ItemPdfPageBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private var renderJob: Job? = null
        var boundPageIndex: Int = RecyclerView.NO_POSITION
            private set
        private var renderedTargetWidth: Int = 0
        private var renderedBitmapWidth: Int = 0
        private var renderedBitmapHeight: Int = 0

        fun updateItemHeight() {
            val fitMode = currentFitMode()
            val isSinglePage = getItemCount() == 1
            val zoomScale = currentDocumentScale().coerceIn(1f, 4f)

            val targetWidth = if (renderedTargetWidth > 0) {
                renderedTargetWidth
            } else if (binding.pageImage.width > 0) {
                binding.pageImage.width
            } else {
                binding.root.resources.displayMetrics.widthPixels
            }

            val baseContentHeight = if (renderedBitmapWidth > 0 && renderedBitmapHeight > 0) {
                val resolvedWidth = targetWidth.coerceAtLeast(1)
                val baseHeight = (
                    resolvedWidth.toFloat() *
                        (renderedBitmapHeight.coerceAtLeast(1).toFloat() / renderedBitmapWidth.coerceAtLeast(1).toFloat())
                    ).roundToInt().coerceAtLeast(1)
                if (fitMode == ZoomableImageView.FitMode.FIT_PAGE) {
                    min(baseHeight, fitPageHeightLimit().coerceAtLeast(1))
                } else {
                    baseHeight
                }
            } else {
                val metrics = binding.root.resources.displayMetrics
                val resolvedWidth = targetWidth.coerceAtLeast(metrics.widthPixels.coerceAtLeast(1))
                val minHeight = (metrics.density * PAGE_PLACEHOLDER_MIN_HEIGHT_DP).roundToInt()
                val maxHeight = (metrics.heightPixels * PAGE_PLACEHOLDER_MAX_HEIGHT_RATIO)
                    .roundToInt()
                    .coerceAtLeast(minHeight)
                val placeholderHeight = (
                    resolvedWidth * PAGE_PLACEHOLDER_ASPECT_RATIO
                    ).roundToInt()
                    .coerceIn(minHeight, maxHeight)
                if (fitMode == ZoomableImageView.FitMode.FIT_PAGE) {
                    min(placeholderHeight, fitPageHeightLimit().coerceAtLeast(minHeight))
                } else {
                    placeholderHeight
                }
            }
            val contentHeight = (baseContentHeight * zoomScale)
                .roundToInt()
                .coerceAtLeast(1)

            val limit = fitPageHeightLimit()
            // Only center within a full-screen slot for a single-page document.
            // For multi-page documents, wrap the content so pages stack tightly
            // instead of leaving large empty gaps around short/landscape pages.
            val shouldCenter = isSinglePage && contentHeight < limit

            binding.root.updateLayoutParams<ViewGroup.LayoutParams> {
                height = if (shouldCenter) {
                    limit
                } else {
                    ViewGroup.LayoutParams.WRAP_CONTENT
                }
            }
            binding.pageImage.updateLayoutParams<ViewGroup.LayoutParams> {
                height = contentHeight
            }
        }

        private fun updatePageImageHeight() {
            updateItemHeight()
        }

        private fun applyPlaceholderHeight(targetWidth: Int) {
            updateItemHeight()
        }

        private fun showRenderedBitmap(bitmap: Bitmap, targetWidth: Int) {
            renderedTargetWidth = targetWidth.coerceAtLeast(1)
            renderedBitmapWidth = bitmap.width
            renderedBitmapHeight = bitmap.height
            updatePageImageHeight()
            binding.pageImage.documentZoomScale = currentDocumentScale()
            binding.pageImage.setFitMode(currentFitMode(), dispatchScaleChange = false)
            binding.pageImage.setImageBitmap(bitmap)
            binding.pageLoading.isVisible = false
            binding.pageError.isVisible = false
        }

        fun bind(pageIndex: Int, displayPosition: Int) {
            updateItemHeight()

            val isSamePageRebind = boundPageIndex == pageIndex
            if (boundPageIndex != RecyclerView.NO_POSITION && !isSamePageRebind) {
                onZoomChanged(boundPageIndex, false)
            }
            val keepExistingRenderJob = isSamePageRebind && renderJob?.isActive == true
            boundPageIndex = pageIndex
            if (!keepExistingRenderJob) {
                renderJob?.cancel()
                renderJob = null
            }
            if (!isSamePageRebind) {
                renderedTargetWidth = 0
                renderedBitmapWidth = 0
                renderedBitmapHeight = 0
            }
            binding.pageNumber.text = binding.root.context.getString(
                R.string.viewer_page_short,
                displayPosition + 1
            )
            binding.pageImage.onSingleTapPoint = { x, y ->
                if (boundPageIndex == pageIndex) {
                    onPageTappedAt(pageIndex, binding.pageImage, x, y)
                } else {
                    false
                }
            }
            binding.pageImage.onSingleTap = onPageTapped
            binding.pageImage.isLongClickable = true
            binding.pageImage.setOnLongClickListener {
                if (boundPageIndex == pageIndex) {
                    onPageLongPressed(pageIndex)
                    true
                } else {
                    false
                }
            }
            binding.pageImage.onZoomChanged = { isZoomed ->
                if (boundPageIndex == pageIndex) {
                    onZoomChanged(pageIndex, isZoomed)
                }
            }
            binding.pageImage.onScaleChanged = { scale ->
                if (boundPageIndex == pageIndex) {
                    onDocumentScaleChanged(pageIndex, scale)
                }
            }
            binding.pageImage.onScaleEnd = {
                if (boundPageIndex == pageIndex) {
                    onScaleGestureFinished(pageIndex, binding.pageImage.let {
                        // Read the current normalizedScale from the image view
                        currentDocumentScale()
                    })
                }
            }
            binding.pageImage.documentZoomScale = currentDocumentScale()
            binding.pageImage.setFitMode(currentFitMode(), dispatchScaleChange = false)

            val targetWidthHint = if (binding.pageImage.width > 0) {
                binding.pageImage.width
            } else {
                binding.root.resources.displayMetrics.widthPixels
            }
            val hasVisibleImage =
                binding.pageImage.drawable != null &&
                    renderedBitmapWidth > 0 &&
                    renderedBitmapHeight > 0
            val cachedBitmap = cachedPageBitmap(pageIndex)
            when {
                cachedBitmap != null -> {
                    showRenderedBitmap(cachedBitmap, targetWidthHint)
                }

                isSamePageRebind && hasVisibleImage -> {
                    updatePageImageHeight()
                    binding.pageLoading.isVisible = false
                    binding.pageError.isVisible = false
                }

                else -> {
                    binding.pageImage.setImageDrawable(null)
                    binding.pageLoading.isVisible = true
                    binding.pageError.isVisible = false
                    applyPlaceholderHeight(targetWidthHint)
                }
            }

            val isActiveForRender =
                activeRenderEnd >= activeRenderStart && displayPosition in activeRenderStart..activeRenderEnd
            if (!isActiveForRender) {
                binding.pageLoading.isVisible = false
                binding.pageError.isVisible = false
                return
            }
            if (cachedBitmap != null || (isSamePageRebind && (hasVisibleImage || keepExistingRenderJob))) {
                return
            }

            val startRender: (Int) -> Unit = { width ->
                val targetWidth = width.coerceAtLeast(
                    binding.root.resources.displayMetrics.widthPixels
                )
                renderJob = scope.launch {
                    runCatching { renderPage(pageIndex, targetWidth) }
                        .onSuccess { bitmap ->
                            if (boundPageIndex == pageIndex) {
                                showRenderedBitmap(bitmap, targetWidth)
                            }
                        }
                        .onFailure {
                            if (boundPageIndex == pageIndex) {
                                binding.pageLoading.isVisible = false
                                binding.pageError.isVisible = true
                            }
                        }
                }
            }

            if (binding.pageImage.width > 0) {
                startRender(binding.pageImage.width)
            } else {
                binding.pageImage.doOnLayout { imageView ->
                    if (boundPageIndex == pageIndex) {
                        startRender(imageView.width)
                    }
                }
            }
        }

        fun recycle() {
            renderJob?.cancel()
            renderJob = null
            if (boundPageIndex != RecyclerView.NO_POSITION) {
                onZoomChanged(boundPageIndex, false)
            }
            boundPageIndex = RecyclerView.NO_POSITION
            binding.pageImage.documentZoomScale = 1f
            binding.pageImage.onZoomChanged = null
            binding.pageImage.onScaleChanged = null
            binding.pageImage.onSingleTapPoint = null
            binding.pageImage.onSingleTap = null
            binding.pageImage.setOnLongClickListener(null)
            binding.pageImage.setImageDrawable(null)
            renderedTargetWidth = 0
            renderedBitmapWidth = 0
            renderedBitmapHeight = 0
            binding.pageImage.updateLayoutParams<ViewGroup.LayoutParams> {
                height = ViewGroup.LayoutParams.WRAP_CONTENT
            }
            binding.pageLoading.isVisible = false
            binding.pageError.isVisible = false
        }

        @Suppress("UNUSED_PARAMETER")
        fun applyDocumentTransform(scale: Float, fitMode: ZoomableImageView.FitMode) {
            if (boundPageIndex == RecyclerView.NO_POSITION) {
                return
            }
            val isSinglePage = getItemCount() == 1
            val shouldCenter = isSinglePage
            binding.root.updateLayoutParams<ViewGroup.LayoutParams> {
                height = if (shouldCenter) {
                    fitPageHeightLimit()
                } else {
                    ViewGroup.LayoutParams.WRAP_CONTENT
                }
            }
            updatePageImageHeight()
            // Reset the image view to fit-width with normalizedScale = 1.
            // The document-level zoom is expressed purely through the taller
            // page card height (computed in updateItemHeight with zoomScale).
            // This ensures the full page content is always visible, like
            // Google Docs viewer, instead of showing a zoomed/panned crop.
            binding.pageImage.post {
                if (boundPageIndex != RecyclerView.NO_POSITION) {
                    binding.pageImage.documentZoomScale = scale
                    binding.pageImage.setFitMode(fitMode, dispatchScaleChange = false)
                }
            }
        }
    }
}

private class PdfThumbnailAdapter(
    private val scope: CoroutineScope,
    private val renderThumbnail: suspend (pageIndex: Int) -> Bitmap,
    private val cachedThumbnailBitmap: (pageIndex: Int) -> Bitmap?,
    private val onThumbnailTapped: (Int) -> Unit
) : RecyclerView.Adapter<PdfThumbnailAdapter.ThumbnailViewHolder>() {

    private val pages = mutableListOf<Int>()
    private var selectedPage: Int = 0
    private var attachedRecyclerView: RecyclerView? = null
    private val pendingChangedPositions = linkedSetOf<Int>()
    private var notifyFlushScheduled = false
    private val notifyFlushRunnable = Runnable {
        flushPendingItemChanges()
    }

    init {
        setHasStableIds(true)
    }

    fun submitPages(newPages: List<Int>) {
        pages.clear()
        pages.addAll(newPages)
        selectedPage = 0
        pendingChangedPositions.clear()
        notifyDataSetChanged()
    }

    fun setSelectedPage(pageIndex: Int) {
        if (selectedPage == pageIndex) {
            return
        }
        val previousPage = selectedPage
        selectedPage = pageIndex
        if (previousPage in pages.indices) {
            notifyItemChangedSafely(previousPage)
        }
        if (pageIndex in pages.indices) {
            notifyItemChangedSafely(pageIndex)
        }
    }

    private fun notifyItemChangedSafely(position: Int) {
        if (position !in pages.indices) {
            return
        }
        val recyclerView = attachedRecyclerView
        if (recyclerView == null) {
            notifyItemChanged(position)
            return
        }
        pendingChangedPositions += position
        if (!notifyFlushScheduled) {
            notifyFlushScheduled = true
            recyclerView.post(notifyFlushRunnable)
        }
    }

    private fun flushPendingItemChanges() {
        val recyclerView = attachedRecyclerView
        if (recyclerView == null) {
            notifyFlushScheduled = false
            pendingChangedPositions.clear()
            return
        }
        if (recyclerView.isComputingLayout) {
            recyclerView.post(notifyFlushRunnable)
            return
        }
        notifyFlushScheduled = false
        val pendingPositions = pendingChangedPositions.toList()
        pendingChangedPositions.clear()
        pendingPositions.forEach { position ->
            if (position in pages.indices) {
                notifyItemChanged(position)
            }
        }
        if (pendingChangedPositions.isNotEmpty() && !notifyFlushScheduled) {
            notifyFlushScheduled = true
            recyclerView.post(notifyFlushRunnable)
        }
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        attachedRecyclerView = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        recyclerView.removeCallbacks(notifyFlushRunnable)
        if (attachedRecyclerView === recyclerView) {
            attachedRecyclerView = null
        }
        pendingChangedPositions.clear()
        notifyFlushScheduled = false
        super.onDetachedFromRecyclerView(recyclerView)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ThumbnailViewHolder {
        val binding = ItemPdfThumbnailBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ThumbnailViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ThumbnailViewHolder, position: Int) {
        holder.bind(pages[position], position, position == selectedPage)
    }

    override fun onViewRecycled(holder: ThumbnailViewHolder) {
        holder.recycle()
        super.onViewRecycled(holder)
    }

    override fun getItemCount(): Int = pages.size

    override fun getItemId(position: Int): Long = pages[position].toLong()

    inner class ThumbnailViewHolder(
        private val binding: ItemPdfThumbnailBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private var renderJob: Job? = null
        private var boundPageIndex: Int = RecyclerView.NO_POSITION

        fun bind(pageIndex: Int, displayPosition: Int, isSelected: Boolean) {
            boundPageIndex = pageIndex
            renderJob?.cancel()
            binding.thumbnailPageNumber.text = (displayPosition + 1).toString()
            val context = binding.root.context
            binding.thumbnailCard.strokeColor =
                context.getColor(
                    if (isSelected) R.color.thumbnail_selected else R.color.thumbnail_unselected
                )
            binding.thumbnailCard.strokeWidth = if (isSelected) 3 else 1
            binding.thumbnailCard.setCardBackgroundColor(
                context.getColor(
                    if (isSelected) R.color.button_secondary_bg else R.color.card_surface_strong
                )
            )
            binding.thumbnailPageNumber.alpha = if (isSelected) 1f else 0.82f
            binding.root.alpha = if (isSelected) 1f else 0.92f
            binding.root.setOnClickListener {
                onThumbnailTapped(pageIndex)
            }

            val cachedBitmap = cachedThumbnailBitmap(pageIndex)
            if (cachedBitmap != null) {
                binding.thumbnailImage.setImageBitmap(cachedBitmap)
                binding.thumbnailLoading.isVisible = false
                return
            }

            binding.thumbnailImage.setImageDrawable(null)
            binding.thumbnailLoading.isVisible = true
            renderJob = scope.launch {
                runCatching { renderThumbnail(pageIndex) }
                    .onSuccess { bitmap ->
                        if (boundPageIndex == pageIndex) {
                            binding.thumbnailImage.setImageBitmap(bitmap)
                            binding.thumbnailLoading.isVisible = false
                        }
                    }
                    .onFailure {
                        if (boundPageIndex == pageIndex) {
                            binding.thumbnailLoading.isVisible = false
                        }
                    }
            }
        }

        fun recycle() {
            renderJob?.cancel()
            renderJob = null
            boundPageIndex = RecyclerView.NO_POSITION
            binding.thumbnailImage.setImageDrawable(null)
            binding.thumbnailLoading.isVisible = false
            binding.root.setOnClickListener(null)
        }
    }
}

private class PdfGridAdapter(
    private val scope: CoroutineScope,
    private val renderThumbnail: suspend (pageIndex: Int) -> Bitmap,
    private val cachedThumbnailBitmap: (pageIndex: Int) -> Bitmap?,
    private val onPageTapped: (position: Int, pageIndex: Int) -> Unit,
    private val onDeleteTapped: (position: Int) -> Unit,
    private val onPagesChanged: () -> Unit
) : RecyclerView.Adapter<PdfGridAdapter.GridViewHolder>() {

    private val pages = mutableListOf<Int>()
    private var selectedPage: Int = -1

    init {
        setHasStableIds(true)
    }

    fun submitPages(newPages: List<Int>, activePage: Int) {
        pages.clear()
        pages.addAll(newPages)
        selectedPage = activePage
        notifyDataSetChanged()
    }

    fun getPages(): List<Int> = pages

    fun moveItem(fromPosition: Int, toPosition: Int) {
        if (fromPosition !in pages.indices || toPosition !in pages.indices) return
        if (fromPosition < toPosition) {
            for (i in fromPosition until toPosition) {
                java.util.Collections.swap(pages, i, i + 1)
            }
        } else {
            for (i in fromPosition downTo toPosition + 1) {
                java.util.Collections.swap(pages, i, i - 1)
            }
        }
        notifyItemMoved(fromPosition, toPosition)
        onPagesChanged()
    }

    fun removeItem(position: Int) {
        if (position in pages.indices) {
            pages.removeAt(position)
            notifyItemRemoved(position)
            notifyItemRangeChanged(position, pages.size - position)
            onPagesChanged()
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GridViewHolder {
        val binding = ItemPdfGridPageBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return GridViewHolder(binding)
    }

    override fun onBindViewHolder(holder: GridViewHolder, position: Int) {
        holder.bind(pages[position], position, pages[position] == selectedPage)
    }

    override fun onViewRecycled(holder: GridViewHolder) {
        holder.recycle()
        super.onViewRecycled(holder)
    }

    override fun getItemCount(): Int = pages.size
    override fun getItemId(position: Int): Long = pages[position].toLong()

    inner class GridViewHolder(
        private val binding: ItemPdfGridPageBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private var renderJob: Job? = null
        private var boundPageIndex: Int = RecyclerView.NO_POSITION

        fun bind(pageIndex: Int, displayPosition: Int, isSelected: Boolean) {
            boundPageIndex = pageIndex
            renderJob?.cancel()
            binding.gridPageNumber.text = (displayPosition + 1).toString()
            val context = binding.root.context
            binding.gridPageCard.strokeColor =
                context.getColor(
                    if (isSelected) R.color.thumbnail_selected else R.color.thumbnail_unselected
                )
            binding.gridPageCard.strokeWidth = if (isSelected) 3 else 1
            binding.gridPageCard.setCardBackgroundColor(
                context.getColor(
                    if (isSelected) R.color.button_secondary_bg else R.color.card_surface_strong
                )
            )
            binding.gridPageNumber.alpha = if (isSelected) 1f else 0.82f
            binding.root.alpha = if (isSelected) 1f else 0.92f
            
            binding.root.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    onPageTapped(pos, pageIndex)
                }
            }

            binding.gridPageDeleteButton.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    onDeleteTapped(pos)
                }
            }

            val cachedBitmap = cachedThumbnailBitmap(pageIndex)
            if (cachedBitmap != null) {
                binding.gridPageImage.setImageBitmap(cachedBitmap)
                binding.gridPageLoading.isVisible = false
                return
            }

            binding.gridPageImage.setImageDrawable(null)
            binding.gridPageLoading.isVisible = true
            renderJob = scope.launch {
                runCatching { renderThumbnail(pageIndex) }
                    .onSuccess { bitmap ->
                        if (boundPageIndex == pageIndex) {
                            binding.gridPageImage.setImageBitmap(bitmap)
                            binding.gridPageLoading.isVisible = false
                        }
                    }
                    .onFailure {
                        if (boundPageIndex == pageIndex) {
                            binding.gridPageLoading.isVisible = false
                        }
                    }
            }
        }

        fun recycle() {
            renderJob?.cancel()
            renderJob = null
            boundPageIndex = RecyclerView.NO_POSITION
            binding.gridPageImage.setImageDrawable(null)
            binding.gridPageLoading.isVisible = false
            binding.root.setOnClickListener(null)
            binding.gridPageDeleteButton.setOnClickListener(null)
        }
    }
}
