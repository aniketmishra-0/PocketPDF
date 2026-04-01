package com.renameapk.pdfzip

import android.content.ClipboardManager
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.text.InputType
import android.util.LruCache
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.addCallback
import androidx.appcompat.app.AlertDialog
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.renameapk.pdfzip.databinding.DialogInsertPagesBinding
import com.renameapk.pdfzip.databinding.DialogInsertPdfPagesBinding
import com.renameapk.pdfzip.databinding.DialogReorderPageBinding
import com.renameapk.pdfzip.databinding.DialogAddLinkBinding
import com.renameapk.pdfzip.databinding.DialogVisualAddTextBinding
import com.renameapk.pdfzip.databinding.DialogJumpToPageBinding
import com.renameapk.pdfzip.databinding.ItemReorderPageBinding
import com.renameapk.pdfzip.databinding.SheetPdfEditToolsBinding
import com.renameapk.pdfzip.databinding.SheetReorderPagesBinding
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionURI
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDBorderStyleDictionary
import com.renameapk.pdfzip.databinding.ActivityPdfEditBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class PdfEditActivity : AppCompatActivity() {

    private data class OpenedPdf(
        val fileDescriptor: ParcelFileDescriptor,
        val renderer: PdfRenderer
    )

    private data class TextColorChoice(
        val chipId: Int,
        val textColor: Int,
        val selectedBackgroundColor: Int,
        val selectedTextColor: Int,
        val unselectedTextColor: Int = textColor,
        val selectedStrokeColor: Int = textColor
    )

    private data class CanvasZoomAnchor(
        val xRatio: Float,
        val yRatio: Float,
        val viewportX: Float,
        val viewportY: Float
    )

    private sealed interface EditorPageEntry {
        data class Original(val originalIndex: Int) : EditorPageEntry
        data class Inserted(val page: InsertedPageState) : EditorPageEntry
    }

    private lateinit var binding: ActivityPdfEditBinding

    private var pdfUri: Uri? = null
    private var requestedPdfUri: Uri? = null
    private var projectSourceUri: Uri? = null
    private var pdfName: String = ""
    private var fileDescriptor: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private var originalPageCount: Int = 0
    private var pageCount: Int = 0
    private var currentPageIndex: Int = 0
    private var selectionOnlyMode: Boolean = false
    private var isPageSeekBarTracking: Boolean = false
    private var renderJob: Job? = null
    private var initialRemovedPages: Set<Int> = emptySet()
    private var restoredProject: VisualEditProjectStore.VisualEditProject? = null
    private var restoredDraft: Boolean = false
    private var projectRestoreFailed: Boolean = false
    private var hasUnsavedDraftChanges: Boolean = false
    private val pageMarkups = mutableMapOf<Int, PageMarkupState>()
    private val deletedPages = mutableSetOf<Int>()
    private val insertedPages = mutableListOf<InsertedPageState>()
    private val displayPages = mutableListOf<EditorPageEntry>()
    private var pendingInsertStartIndex = 0
    private var jumpToPageDialog: AlertDialog? = null
    private var addTextDialog: AlertDialog? = null
    private var moreToolsDialog: BottomSheetDialog? = null
    private var reorderPagesDialog: BottomSheetDialog? = null
    private var toolbarBasePaddingLeft = 0
    private var toolbarBasePaddingTop = 0
    private var toolbarBasePaddingRight = 0
    private var controlsBasePaddingLeft = 0
    private var controlsBasePaddingRight = 0
    private var controlsBasePaddingBottom = 0
    private var scrollBasePaddingLeft = 0
    private var scrollBasePaddingTop = 0
    private var scrollBasePaddingRight = 0
    private var scrollBasePaddingBottom = 0
    private var canvasBaseWidth = 0
    private var canvasBaseHeight = 0
    private var canvasZoomScale = 1f
    private var canvasDefaultZoomScale = 1f
    private var canvasMinZoomScale = 1f
    private var isCanvasScaleGestureActive = false
    private var pendingCanvasZoomAnchor: CanvasZoomAnchor? = null
    private var isCanvasZoomApplyScheduled = false
    private lateinit var canvasScaleDetector: ScaleGestureDetector
    private val renderMutex = Mutex()
    private val pageBitmapCache = object : LruCache<Int, Bitmap>(EDITOR_PAGE_CACHE_SIZE_KB) {
        override fun sizeOf(key: Int, value: Bitmap): Int = value.allocationByteCount / 1024
    }

    private val createEditedPdfLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
            if (uri == null) {
                showMessage(getString(R.string.visual_edit_cancelled))
                return@registerForActivityResult
            }
            takeDocumentPermission(uri)
            saveVisualEdits(uri)
        }

    private val pickImageLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) {
                return@registerForActivityResult
            }
            addImageToCurrentPage(uri)
        }

    private val addPageImagesLauncher =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNullOrEmpty()) {
                return@registerForActivityResult
            }
            uris.forEach(::takeReadPermission)
            addPagesFromImages(uris)
        }

    private val pickInsertPdfLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) {
                return@registerForActivityResult
            }
            takeReadPermission(uri)
            showInsertPdfPagesDialog(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppThemeStore.applySavedTheme(this)
        super.onCreate(savedInstanceState)
        PDFBoxResourceLoader.init(applicationContext)
        binding = ActivityPdfEditBinding.inflate(layoutInflater)
        setContentView(binding.root)

        captureBaseSpacing()
        setupWindowInsets()
        resolveIntentData(intent)
        setupToolbar()
        setupCanvasZoom()
        setupControls()
        setupBackNavigation()
        loadPdf()
    }

    override fun onDestroy() {
        jumpToPageDialog?.dismiss()
        addTextDialog?.dismiss()
        moreToolsDialog?.dismiss()
        reorderPagesDialog?.dismiss()
        renderJob?.cancel()
        closeCurrentPdf()
        super.onDestroy()
    }

    override fun onStop() {
        persistCurrentDraft()
        super.onStop()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) {
                handleEditorBackPressed()
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun resolveIntentData(intent: Intent) {
        val incomingUri = intent.data ?: intent.getStringExtra(EXTRA_PDF_URI)?.let(Uri::parse)
        requestedPdfUri = incomingUri
        pdfUri = incomingUri
        selectionOnlyMode = intent.getBooleanExtra(EXTRA_SELECTION_ONLY, false)
        initialRemovedPages = intent
            .getIntegerArrayListExtra(EXTRA_INITIAL_REMOVED_PAGES)
            .orEmpty()
            .filter { it > 0 }
            .toSet()
        pdfName = intent.getStringExtra(EXTRA_PDF_NAME).orEmpty().ifBlank {
            incomingUri?.let(::queryDisplayName) ?: getString(R.string.fallback_pdf_name)
        }
        if (incomingUri != null) {
            takeReadPermission(incomingUri)
        }
        if (!selectionOnlyMode && incomingUri != null) {
            val savedProject = VisualEditProjectStore.loadProject(this, incomingUri)
            val savedDraft = VisualEditProjectStore.loadDraft(this, incomingUri)
            restoredProject = listOfNotNull(savedProject, savedDraft)
                .maxWithOrNull(
                    compareBy<VisualEditProjectStore.VisualEditProject> { project ->
                        project.updatedAt
                    }.thenBy { project ->
                        if (project.isDraft) 1 else 0
                    }
                )
            restoredDraft = restoredProject?.isDraft == true
            projectSourceUri = restoredProject?.sourceUriString?.let(Uri::parse)
            projectSourceUri?.let(::takeReadPermission)
        }
    }

    private fun setupToolbar() {
        binding.editToolbar.title = if (selectionOnlyMode) {
            getString(R.string.visual_page_picker_title)
        } else {
            getString(R.string.visual_edit_title)
        }
        binding.editToolbar.subtitle = pdfName
        binding.editSubtitle.isVisible = false
        binding.editToolbar.setNavigationOnClickListener { handleEditorBackPressed() }
    }

    private fun captureBaseSpacing() {
        toolbarBasePaddingLeft = binding.editToolbar.paddingLeft
        toolbarBasePaddingTop = binding.editToolbar.paddingTop
        toolbarBasePaddingRight = binding.editToolbar.paddingRight
        controlsBasePaddingLeft = binding.editControlsPanel.paddingLeft
        controlsBasePaddingRight = binding.editControlsPanel.paddingRight
        controlsBasePaddingBottom = binding.editControlsPanel.paddingBottom
        scrollBasePaddingLeft = binding.editScrollView.paddingLeft
        scrollBasePaddingTop = binding.editScrollView.paddingTop
        scrollBasePaddingRight = binding.editScrollView.paddingRight
        scrollBasePaddingBottom = binding.editScrollView.paddingBottom
    }

    private fun setupWindowInsets() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.editToolbar.updatePadding(
                left = toolbarBasePaddingLeft + systemBars.left,
                top = toolbarBasePaddingTop + systemBars.top,
                right = toolbarBasePaddingRight + systemBars.right
            )
            binding.editControlsPanel.updatePadding(
                left = controlsBasePaddingLeft + systemBars.left,
                right = controlsBasePaddingRight + systemBars.right,
                bottom = controlsBasePaddingBottom + systemBars.bottom
            )
            binding.editScrollView.updatePadding(
                left = scrollBasePaddingLeft,
                top = scrollBasePaddingTop,
                right = scrollBasePaddingRight,
                bottom = scrollBasePaddingBottom
            )
            insets
        }
    }

    private fun setupCanvasZoom() {
        canvasScaleDetector = ScaleGestureDetector(
            this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                    val canScale = canvasBaseWidth > 0 && canvasBaseHeight > 0
                    isCanvasScaleGestureActive = canScale
                    if (canScale) {
                        binding.editPageImage.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    return canScale
                }

                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    return adjustCanvasZoom(
                        scaleFactor = detector.scaleFactor,
                        focusX = detector.focusX,
                        focusY = detector.focusY
                    )
                }

                override fun onScaleEnd(detector: ScaleGestureDetector) {
                    isCanvasScaleGestureActive = false
                    binding.editPageImage.parent?.requestDisallowInterceptTouchEvent(false)
                }
            }
        )

        binding.editPageImage.setOnTouchListener { _, event ->
            handleCanvasScaleTouch(event)
        }
        binding.editZoomInButton.setOnClickListener {
            zoomCanvasUsingButtons(CANVAS_BUTTON_ZOOM_STEP)
        }
        binding.editZoomOutButton.setOnClickListener {
            zoomCanvasUsingButtons(1f / CANVAS_BUTTON_ZOOM_STEP)
        }
        binding.editOverlay.preferCanvasPinchZoom = true
        binding.editOverlay.onCanvasScaleRequested = { scaleFactor, focusX, focusY ->
            adjustCanvasZoom(
                scaleFactor = scaleFactor,
                focusX = focusX,
                focusY = focusY
            )
        }
        binding.editHorizontalScrollView.doOnLayout {
            if (canvasBaseWidth > 0 && canvasBaseHeight > 0) {
                applyCanvasZoom(resetScrollPosition = false)
            }
        }
        binding.editScrollView.doOnLayout {
            if (canvasBaseWidth > 0 && canvasBaseHeight > 0) {
                applyCanvasZoom(resetScrollPosition = false)
            }
        }
        updateCanvasZoomControls()
    }

    private fun handleCanvasScaleTouch(event: MotionEvent): Boolean {
        if (event.pointerCount > 1 || isCanvasScaleGestureActive) {
            binding.editPageImage.parent?.requestDisallowInterceptTouchEvent(true)
            val handled = canvasScaleDetector.onTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                isCanvasScaleGestureActive = false
                binding.editPageImage.parent?.requestDisallowInterceptTouchEvent(false)
            }
            return handled || event.pointerCount > 1 || isCanvasScaleGestureActive
        }
        return false
    }

    private fun adjustCanvasZoom(
        scaleFactor: Float,
        focusX: Float? = null,
        focusY: Float? = null
    ): Boolean {
        if (canvasBaseWidth <= 0 || canvasBaseHeight <= 0 || scaleFactor <= 0f) {
            return false
        }
        val updatedScale = (canvasZoomScale * scaleFactor).coerceIn(canvasMinZoomScale, MAX_CANVAS_ZOOM)
        if (kotlin.math.abs(updatedScale - canvasZoomScale) < CANVAS_SCALE_EPSILON) {
            return false
        }
        val zoomAnchor = buildCanvasZoomAnchor(focusX = focusX, focusY = focusY)
        canvasZoomScale = updatedScale
        scheduleCanvasZoomApply(anchor = zoomAnchor)
        return true
    }

    private fun buildCanvasZoomAnchor(
        focusX: Float? = null,
        focusY: Float? = null
    ): CanvasZoomAnchor? {
        if (canvasBaseWidth <= 0 || canvasBaseHeight <= 0) {
            return null
        }
        val scaledWidth = (canvasBaseWidth * canvasZoomScale).roundToInt().coerceAtLeast(1)
        val scaledHeight = (canvasBaseHeight * canvasZoomScale).roundToInt().coerceAtLeast(1)
        val resolvedFocusX = (focusX ?: scaledWidth / 2f).coerceIn(0f, scaledWidth.toFloat())
        val resolvedFocusY = (focusY ?: scaledHeight / 2f).coerceIn(0f, scaledHeight.toFloat())
        val viewportX = binding.editCanvasViewport.left +
            binding.editCanvasFrame.left +
            resolvedFocusX -
            binding.editHorizontalScrollView.scrollX
        val viewportY = binding.editCanvasViewport.top +
            binding.editCanvasFrame.top +
            resolvedFocusY -
            binding.editScrollView.scrollY
        return CanvasZoomAnchor(
            xRatio = (resolvedFocusX / scaledWidth.toFloat()).coerceIn(0f, 1f),
            yRatio = (resolvedFocusY / scaledHeight.toFloat()).coerceIn(0f, 1f),
            viewportX = viewportX,
            viewportY = viewportY
        )
    }

    private fun scheduleCanvasZoomApply(anchor: CanvasZoomAnchor? = null) {
        pendingCanvasZoomAnchor = anchor ?: pendingCanvasZoomAnchor
        if (isCanvasZoomApplyScheduled) {
            return
        }
        isCanvasZoomApplyScheduled = true
        binding.editCanvasViewport.postOnAnimation {
            isCanvasZoomApplyScheduled = false
            val scheduledAnchor = pendingCanvasZoomAnchor
            pendingCanvasZoomAnchor = null
            applyCanvasZoom(anchor = scheduledAnchor)
        }
    }

    private fun applyCanvasZoom(
        resetScrollPosition: Boolean = false,
        anchor: CanvasZoomAnchor? = null
    ) {
        if (canvasBaseWidth <= 0 || canvasBaseHeight <= 0) {
            return
        }
        val scaledWidth = (canvasBaseWidth * canvasZoomScale).roundToInt().coerceAtLeast(1)
        val scaledHeight = (canvasBaseHeight * canvasZoomScale).roundToInt().coerceAtLeast(1)
        val viewportWidth = max(
            binding.editHorizontalScrollView.width,
            scaledWidth
        ).coerceAtLeast(scaledWidth)
        val viewportHeight = max(
            binding.editScrollView.height,
            scaledHeight
        ).coerceAtLeast(scaledHeight)
        val frameGravity = resolveCanvasFrameGravity(
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
            scaledWidth = scaledWidth,
            scaledHeight = scaledHeight
        )
        val viewportParams = binding.editCanvasViewport.layoutParams
        if (viewportParams.width != viewportWidth || viewportParams.height != viewportHeight) {
            binding.editCanvasViewport.updateLayoutParams<ViewGroup.LayoutParams> {
                width = viewportWidth
                height = viewportHeight
            }
        }
        val frameParams = binding.editCanvasFrame.layoutParams as android.widget.FrameLayout.LayoutParams
        if (
            frameParams.width != scaledWidth ||
            frameParams.height != scaledHeight ||
            frameParams.gravity != frameGravity
        ) {
            binding.editCanvasFrame.updateLayoutParams<android.widget.FrameLayout.LayoutParams> {
                width = scaledWidth
                height = scaledHeight
                gravity = frameGravity
            }
        }
        if (resetScrollPosition) {
            pendingCanvasZoomAnchor = null
            binding.editHorizontalScrollView.post {
                val maxScrollX = (
                    (binding.editHorizontalScrollView.getChildAt(0)?.width ?: 0) -
                        binding.editHorizontalScrollView.width
                    ).coerceAtLeast(0)
                val centeredScrollX = if (maxScrollX > 0) {
                    maxScrollX / 2
                } else {
                    0
                }
                binding.editHorizontalScrollView.scrollTo(centeredScrollX, 0)
                binding.editScrollView.scrollTo(0, 0)
            }
        } else if (anchor != null) {
            binding.editCanvasViewport.postOnAnimation {
                restoreCanvasZoomAnchor(anchor)
            }
        }
        updateCanvasZoomControls()
    }

    private fun resolveCanvasFrameGravity(
        viewportWidth: Int,
        viewportHeight: Int,
        scaledWidth: Int,
        scaledHeight: Int
    ): Int {
        val horizontalGravity = if (scaledWidth < viewportWidth) {
            Gravity.CENTER_HORIZONTAL
        } else {
            Gravity.START
        }
        val verticalGravity = if (scaledHeight < viewportHeight) {
            Gravity.CENTER_VERTICAL
        } else {
            Gravity.TOP
        }
        return horizontalGravity or verticalGravity
    }

    private fun restoreCanvasZoomAnchor(anchor: CanvasZoomAnchor) {
        if (canvasBaseWidth <= 0 || canvasBaseHeight <= 0) {
            return
        }
        val scaledWidth = (canvasBaseWidth * canvasZoomScale).roundToInt().coerceAtLeast(1)
        val scaledHeight = (canvasBaseHeight * canvasZoomScale).roundToInt().coerceAtLeast(1)
        val contentX = binding.editCanvasViewport.left +
            binding.editCanvasFrame.left +
            (anchor.xRatio * scaledWidth)
        val contentY = binding.editCanvasViewport.top +
            binding.editCanvasFrame.top +
            (anchor.yRatio * scaledHeight)
        val maxScrollX = (
            (binding.editHorizontalScrollView.getChildAt(0)?.width ?: 0) -
                binding.editHorizontalScrollView.width
            ).coerceAtLeast(0)
        val maxScrollY = (
            (binding.editScrollView.getChildAt(0)?.height ?: 0) -
                binding.editScrollView.height
            ).coerceAtLeast(0)
        val targetScrollX = (contentX - anchor.viewportX).roundToInt().coerceIn(0, maxScrollX)
        val targetScrollY = (contentY - anchor.viewportY).roundToInt().coerceIn(0, maxScrollY)
        binding.editHorizontalScrollView.scrollTo(targetScrollX, 0)
        binding.editScrollView.scrollTo(0, targetScrollY)
    }

    private fun cachedPageBitmap(pageIndex: Int): Bitmap? {
        return pageBitmapCache.get(pageIndex)?.takeIf { !it.isRecycled }
    }

    private fun pageEntryAt(pageIndex: Int): EditorPageEntry? = displayPages.getOrNull(pageIndex)

    private fun isInsertedPageIndex(pageIndex: Int): Boolean {
        return pageEntryAt(pageIndex) is EditorPageEntry.Inserted
    }

    private fun insertedPageAt(pageIndex: Int): InsertedPageState? {
        return (pageEntryAt(pageIndex) as? EditorPageEntry.Inserted)?.page
    }

    private fun originalPageIndexAt(pageIndex: Int): Int? {
        return (pageEntryAt(pageIndex) as? EditorPageEntry.Original)?.originalIndex
    }

    private fun rebuildDisplayPagesFromDefaultOrder() {
        displayPages.clear()
        repeat(originalPageCount) { originalIndex ->
            displayPages += EditorPageEntry.Original(originalIndex)
        }
        insertedPages
            .sortedBy { insertedPage -> insertedPage.position }
            .forEach { insertedPage ->
                val insertAt = insertedPage.position.coerceIn(0, displayPages.size)
                displayPages.add(insertAt, EditorPageEntry.Inserted(insertedPage))
            }
    }

    private fun refreshPageCountState() {
        pageCount = displayPages.size
        binding.editPageSeekBar.max = (pageCount - 1).coerceAtLeast(0)
        if (pageCount > 0 && currentPageIndex >= pageCount) {
            currentPageIndex = pageCount - 1
        }
    }

    private fun shiftPageStateForInsertion(insertAt: Int, count: Int) {
        if (count <= 0) {
            return
        }

        val shiftedMarkups = pageMarkups.entries
            .sortedByDescending { (pageIndex, _) -> pageIndex }
            .associate { (pageIndex, state) ->
                val shiftedIndex = if (pageIndex >= insertAt) pageIndex + count else pageIndex
                shiftedIndex to state
            }
        pageMarkups.clear()
        pageMarkups.putAll(shiftedMarkups)

        val shiftedDeletedPages = deletedPages.mapTo(mutableSetOf()) { pageIndex ->
            if (pageIndex >= insertAt) pageIndex + count else pageIndex
        }
        deletedPages.clear()
        deletedPages.addAll(shiftedDeletedPages)
    }

    private fun shiftInsertedPagePositions(insertAt: Int, count: Int) {
        if (count <= 0) {
            return
        }

        insertedPages.replaceAll { insertedPage ->
            if (insertedPage.position == Int.MAX_VALUE) {
                insertedPage
            } else if (insertedPage.position >= insertAt) {
                insertedPage.copy(position = insertedPage.position + count)
            } else {
                insertedPage
            }
        }
    }

    private fun syncInsertedPagePositionsFromDisplayOrder() {
        val reorderedInsertedPages = mutableListOf<InsertedPageState>()
        displayPages.forEachIndexed { index, entry ->
            if (entry is EditorPageEntry.Inserted) {
                val updatedPage = entry.page.copy(position = index)
                displayPages[index] = EditorPageEntry.Inserted(updatedPage)
                reorderedInsertedPages += updatedPage
            }
        }
        insertedPages.clear()
        insertedPages.addAll(reorderedInsertedPages)
    }

    private fun restoreDisplayPagesFromProject(project: VisualEditProjectStore.VisualEditProject) {
        val insertedById = insertedPages.associateBy { insertedPage -> insertedPage.id }
        val restoredPages = mutableListOf<EditorPageEntry>()
        val usedOriginalIndexes = mutableSetOf<Int>()
        val usedInsertedIds = mutableSetOf<String>()

        project.pageOrderTokens.forEach { token ->
            when {
                token.startsWith("o:") -> {
                    token.substringAfter("o:").toIntOrNull()
                        ?.takeIf { it in 0 until originalPageCount }
                        ?.takeIf { usedOriginalIndexes.add(it) }
                        ?.let { originalIndex ->
                            restoredPages += EditorPageEntry.Original(originalIndex)
                        }
                }

                token.startsWith("i:") -> {
                    val insertedId = token.substringAfter("i:")
                    insertedById[insertedId]
                        ?.takeIf { usedInsertedIds.add(it.id) }
                        ?.let { insertedPage ->
                            restoredPages += EditorPageEntry.Inserted(insertedPage)
                        }
                }
            }
        }

        if (restoredPages.isEmpty()) {
            rebuildDisplayPagesFromDefaultOrder()
            return
        }

        repeat(originalPageCount) { originalIndex ->
            if (usedOriginalIndexes.add(originalIndex)) {
                restoredPages += EditorPageEntry.Original(originalIndex)
            }
        }
        insertedPages
            .filter { insertedPage -> usedInsertedIds.add(insertedPage.id) }
            .sortedBy { insertedPage -> insertedPage.position }
            .forEach { insertedPage ->
                val insertAt = insertedPage.position.coerceIn(0, restoredPages.size)
                restoredPages.add(insertAt, EditorPageEntry.Inserted(insertedPage))
            }

        displayPages.clear()
        displayPages.addAll(restoredPages)
        syncInsertedPagePositionsFromDisplayOrder()
    }

    private fun currentPageOrderTokens(): List<String> {
        return displayPages.map { entry ->
            when (entry) {
                is EditorPageEntry.Original -> "o:${entry.originalIndex}"
                is EditorPageEntry.Inserted -> "i:${entry.page.id}"
            }
        }
    }

    private fun displayPageBitmap(bitmap: Bitmap, resetScrollPosition: Boolean) {
        binding.editPageImage.setImageBitmap(bitmap)
        canvasBaseWidth = bitmap.width
        canvasBaseHeight = bitmap.height
        canvasDefaultZoomScale = resolveDefaultCanvasScale(
            contentWidth = bitmap.width,
            contentHeight = bitmap.height
        )
        canvasMinZoomScale = canvasDefaultZoomScale
            .coerceAtMost(1f)
            .coerceAtLeast(MIN_CANVAS_ZOOM)
        if (resetScrollPosition || canvasZoomScale < canvasMinZoomScale) {
            canvasZoomScale = canvasDefaultZoomScale
        } else {
            canvasZoomScale = canvasZoomScale.coerceIn(canvasMinZoomScale, MAX_CANVAS_ZOOM)
        }
        binding.editCanvasViewport.post {
            applyCanvasZoom(resetScrollPosition = resetScrollPosition)
        }
        if (!selectionOnlyMode) {
            binding.editCanvasFrame.post {
                binding.editOverlay.bindMarkupState(currentMarkupState())
            }
        }
    }

    private fun resolveDefaultCanvasScale(
        contentWidth: Int,
        contentHeight: Int
    ): Float {
        val viewportWidth = (
            binding.editHorizontalScrollView.width
                .takeIf { it > 0 }
                ?: (resources.displayMetrics.widthPixels -
                    binding.editScrollView.paddingLeft -
                    binding.editScrollView.paddingRight)
            ).coerceAtLeast(1)
        val viewportHeight = (
            binding.editScrollView.height
                .takeIf { it > 0 }
                ?: (resources.displayMetrics.heightPixels * 0.52f).roundToInt()
            ).coerceAtLeast(1)
        val horizontalPadding = (
            binding.editScrollView.paddingLeft + binding.editScrollView.paddingRight
            ).coerceAtLeast(0)
        val verticalPadding = (
            binding.editScrollView.paddingTop + binding.editScrollView.paddingBottom
            ).coerceAtLeast(0)
        val availableWidth = (viewportWidth - horizontalPadding).coerceAtLeast(1)
        val availableHeight = (viewportHeight - verticalPadding).coerceAtLeast(1)
        val fitWidthScale = availableWidth.toFloat() / contentWidth.coerceAtLeast(1).toFloat()
        val fitHeightScale = availableHeight.toFloat() / contentHeight.coerceAtLeast(1).toFloat()
        return min(fitWidthScale, fitHeightScale).coerceIn(MIN_CANVAS_ZOOM, 1f)
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this) {
            handleEditorBackPressed()
        }
    }

    private fun handleEditorBackPressed() {
        when {
            reorderPagesDialog?.isShowing == true -> reorderPagesDialog?.dismiss()
            moreToolsDialog?.isShowing == true -> moreToolsDialog?.dismiss()
            addTextDialog?.isShowing == true -> addTextDialog?.dismiss()
            jumpToPageDialog?.isShowing == true -> jumpToPageDialog?.dismiss()
            !selectionOnlyMode && binding.editOverlay.toolMode != PdfMarkupOverlayView.ToolMode.MOVE -> {
                applyMoveToolSelection()
            }

            !selectionOnlyMode && binding.editOverlay.clearSelection() -> {
                updateSelectionControls()
            }

            else -> finish()
        }
    }

    private fun applyMoveToolSelection() {
        binding.editOverlay.toolMode = PdfMarkupOverlayView.ToolMode.MOVE
        if (binding.editToolToggleGroup.checkedButtonId != R.id.moveToolButton) {
            binding.editToolToggleGroup.check(R.id.moveToolButton)
        }
        binding.editToolHint.text = getString(R.string.visual_edit_move_notice)
    }

    private fun setupControls() {
        binding.editOverlay.isVisible = !selectionOnlyMode
        binding.editNavigationCard.isVisible = true
        binding.editJumpRow.isVisible = true
        binding.editToolsCard.isVisible = !selectionOnlyMode
        binding.editSelectedCard.isVisible = false
        binding.editActionsCard.isVisible = selectionOnlyMode
        binding.selectionOnlyStatusText.isVisible = selectionOnlyMode
        binding.editToolToggleGroup.isVisible = !selectionOnlyMode
        binding.pageNumbersGroup.isVisible = false
        binding.addTextButton.isVisible = !selectionOnlyMode
        binding.editPrimaryActionsSpacerStart.isVisible = !selectionOnlyMode
        binding.addImageButton.isVisible = !selectionOnlyMode
        binding.editPrimaryActionsSpacerEnd.isVisible = !selectionOnlyMode
        binding.editHistoryRow.isVisible = !selectionOnlyMode
        binding.editUndoRow.isVisible = false
        binding.editUndoActionsRow.isVisible = selectionOnlyMode
        binding.moreToolsButton.isVisible = !selectionOnlyMode
        binding.undoEditButton.isVisible = !selectionOnlyMode
        binding.clearPageButton.isVisible = !selectionOnlyMode
        binding.deletePageSpacerOne.isVisible = !selectionOnlyMode
        binding.deletePageSpacerTwo.isVisible = !selectionOnlyMode
        binding.previewEditedPdfButton.isVisible = !selectionOnlyMode
        binding.saveEditedPdfButton.text = if (selectionOnlyMode) {
            getString(R.string.visual_page_picker_apply)
        } else {
            getString(R.string.visual_edit_save_compact)
        }
        binding.editToolHint.text = if (selectionOnlyMode) {
            getString(R.string.visual_page_picker_hint)
        } else {
            getString(R.string.visual_edit_move_notice)
        }

        if (!selectionOnlyMode) {
            applyMoveToolSelection()
            binding.editOverlay.onTextPlacementRequested = { xRatio, yRatio ->
                showAddTextDialog(xRatio, yRatio)
            }
            binding.editOverlay.onMarkupChanged = {
                hasUnsavedDraftChanges = true
                updateActionAvailability()
            }
            binding.editOverlay.onSelectionChanged = {
                updateSelectionControls()
            }
            binding.editToolToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
                if (!isChecked) {
                    return@addOnButtonCheckedListener
                }
                binding.editOverlay.toolMode = when (checkedId) {
                    R.id.moveToolButton -> PdfMarkupOverlayView.ToolMode.MOVE
                    R.id.highlightToolButton -> PdfMarkupOverlayView.ToolMode.HIGHLIGHT
                    else -> PdfMarkupOverlayView.ToolMode.DRAW
                }
                binding.editToolHint.text = when (checkedId) {
                    R.id.moveToolButton -> getString(R.string.visual_edit_move_notice)
                    else -> getString(R.string.visual_edit_hint)
                }
            }
        }

        binding.pageNumbersChip.setOnCheckedChangeListener { _, _ ->
            if (!selectionOnlyMode) {
                hasUnsavedDraftChanges = true
                updateActionAvailability()
            }
        }

        binding.editPreviousPageButton.setOnClickListener {
            showPage((currentPageIndex - 1).coerceAtLeast(0))
        }
        binding.editNextPageButton.setOnClickListener {
            showPage((currentPageIndex + 1).coerceAtMost(pageCount - 1))
        }
        binding.editJumpToPageButton.setOnClickListener {
            showJumpToPageDialog()
        }
        binding.editPageSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && progress in 0 until pageCount) {
                    binding.editPageIndicator.text = getString(
                        R.string.visual_edit_page_counter,
                        progress + 1,
                        pageCount
                    )
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isPageSeekBarTracking = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isPageSeekBarTracking = false
                val targetPageIndex = seekBar?.progress ?: currentPageIndex
                if (targetPageIndex != currentPageIndex) {
                    showPage(targetPageIndex)
                } else {
                    updatePageUi()
                }
            }
        })
        binding.addTextButton.setOnClickListener {
            showAddTextDialog(xRatio = 0.12f, yRatio = 0.18f)
        }
        binding.addImageButton.setOnClickListener {
            pickImageLauncher.launch(arrayOf("image/*"))
        }
        binding.moreToolsButton.setOnClickListener {
            showMoreToolsSheet()
        }
        binding.deletePageButton.setOnClickListener {
            toggleDeleteCurrentPage()
        }
        binding.selectedSmallerButton.setOnClickListener {
            if (binding.editOverlay.adjustSelectedElementScale(0.9f)) {
                updateSelectionControls()
            }
        }
        binding.selectedLargerButton.setOnClickListener {
            if (binding.editOverlay.adjustSelectedElementScale(1.1f)) {
                updateSelectionControls()
            }
        }
        binding.toggleBoldButton.setOnClickListener {
            if (binding.editOverlay.toggleSelectedTextBold()) {
                updateSelectionControls()
            }
        }
        binding.undoEditButton.setOnClickListener {
            binding.editOverlay.undoLast()
        }
        binding.clearPageButton.setOnClickListener {
            binding.editOverlay.clearPage()
            updateActionAvailability()
        }
        binding.previewEditedPdfButton.setOnClickListener {
            if (!hasEditableOutput()) {
                showMessage(getString(R.string.visual_edit_no_changes))
                return@setOnClickListener
            }
            previewVisualEdits()
        }
        binding.saveEditedPdfButton.setOnClickListener {
            if (selectionOnlyMode) {
                applyPageSelection()
                return@setOnClickListener
            }
            if (!hasEditableOutput()) {
                showMessage(getString(R.string.visual_edit_no_changes))
                return@setOnClickListener
            }
            createEditedPdfLauncher.launch(buildEditedPdfFileName(pdfName))
        }
        updateSelectionControls()
    }

    private fun loadPdf() {
        setLoading(true)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { openPdf() }
            }

            result.onSuccess { openedPdf ->
                fileDescriptor = openedPdf.fileDescriptor
                renderer = openedPdf.renderer
                originalPageCount = openedPdf.renderer.pageCount
                pageCount = originalPageCount
                binding.editPageSeekBar.max = (pageCount - 1).coerceAtLeast(0)
                binding.editPageSeekBar.progress = 0
                pageMarkups.clear()
                deletedPages.clear()
                insertedPages.clear()
                displayPages.clear()
                rebuildDisplayPagesFromDefaultOrder()
                refreshPageCountState()
                if (selectionOnlyMode) {
                    deletedPages += initialRemovedPages.mapNotNull { pageNumber ->
                        pageNumber.takeIf { it in 1..pageCount }?.minus(1)
                    }
                } else {
                    applyRestoredProjectState()
                }
                binding.editLoadingGroup.isVisible = false
                binding.editErrorText.isVisible = false
                binding.editScrollView.isVisible = true
                binding.editControlsPanel.isVisible = true
                pageBitmapCache.evictAll()
                showPage(0)
                hasUnsavedDraftChanges = false
                when {
                    restoredDraft -> showMessage(getString(R.string.visual_edit_draft_restored))
                    restoredProject != null -> showMessage(getString(R.string.visual_edit_restore_success))
                    projectRestoreFailed -> showMessage(getString(R.string.visual_edit_restore_failed))
                }
            }.onFailure { error ->
                binding.editLoadingGroup.isVisible = false
                binding.editScrollView.isVisible = false
                binding.editControlsPanel.isVisible = false
                binding.editErrorText.isVisible = true
                binding.editErrorText.text = getString(
                    R.string.viewer_open_failed,
                    error.message ?: getString(R.string.unknown_error)
                )
            }
            setLoading(false)
        }
    }

    private fun openPdf(): OpenedPdf {
        val requestedUri = requestedPdfUri ?: pdfUri ?: throw IOException(getString(R.string.cannot_open_pdf))
        if (!selectionOnlyMode && projectSourceUri != null) {
            val sourceUri = projectSourceUri ?: requestedUri
            return runCatching {
                openPdfFromUri(sourceUri).also {
                    projectRestoreFailed = false
                }
            }.getOrElse {
                restoredProject = null
                projectSourceUri = null
                projectRestoreFailed = true
                openPdfFromUri(requestedUri)
            }
        }
        return openPdfFromUri(requestedUri)
    }

    private fun openPdfFromUri(inputUri: Uri): OpenedPdf {
        val localPdf = LocalPdfStore.prepareForRead(this, inputUri, pdfName)
        requestedPdfUri = if (requestedPdfUri == inputUri) localPdf.uri else requestedPdfUri
        projectSourceUri = if (projectSourceUri == inputUri) localPdf.uri else projectSourceUri
        pdfUri = localPdf.uri
        if (pdfName.isBlank()) {
            pdfName = localPdf.displayName
        }
        val descriptor = ParcelFileDescriptor.open(
            localPdf.file,
            ParcelFileDescriptor.MODE_READ_ONLY
        ) ?: throw IOException(getString(R.string.cannot_open_pdf))
        val pdfRenderer = PdfRenderer(descriptor)
        if (pdfRenderer.pageCount == 0) {
            pdfRenderer.close()
            descriptor.close()
            throw IOException(getString(R.string.empty_pdf))
        }
        return OpenedPdf(descriptor, pdfRenderer)
    }

    private fun applyRestoredProjectState() {
        val project = restoredProject
        binding.pageNumbersChip.isChecked = project?.pageNumbersEnabled == true
        if (project == null) {
            return
        }
        insertedPages += project.insertedPages
        restoreDisplayPagesFromProject(project)
        refreshPageCountState()
        deletedPages += project.deletedPages.filter { it in 0 until pageCount }
        project.pageMarkups
            .filterKeys { it in 0 until pageCount }
            .forEach { (pageIndex, state) ->
                pageMarkups[pageIndex] = state
            }
    }

    private fun showPage(pageIndex: Int) {
        if (pageCount <= 0) {
            return
        }
        val safePageIndex = pageIndex.coerceIn(0, pageCount - 1)
        currentPageIndex = safePageIndex
        renderJob?.cancel()
        cachedPageBitmap(safePageIndex)?.let { cachedBitmap ->
            displayPageBitmap(bitmap = cachedBitmap, resetScrollPosition = true)
            updatePageUi()
            setLoading(false)
            return
        }
        setLoading(true)
        renderJob = lifecycleScope.launch {
            val bitmapResult = runCatching { renderPageBitmap(safePageIndex) }
            bitmapResult.onSuccess { bitmap ->
                pageBitmapCache.put(safePageIndex, bitmap)
                displayPageBitmap(bitmap = bitmap, resetScrollPosition = true)
                updatePageUi()
            }.onFailure { error ->
                if (error is CancellationException) {
                    return@launch
                }
                showMessage(
                    getString(
                        R.string.viewer_open_failed,
                        error.message ?: getString(R.string.unknown_error)
                    )
                )
            }
            setLoading(false)
        }
    }

    private suspend fun renderPageBitmap(pageIndex: Int): Bitmap {
        insertedPageAt(pageIndex)?.let { insertedPage ->
            return renderInsertedPageBitmap(
                insertedPage = insertedPage,
                maxDimension = PAGE_RENDER_MAX_DIMENSION
            )
        }
        return withContext(Dispatchers.IO) {
            renderMutex.withLock {
                val originalPageIndex = originalPageIndexAt(pageIndex)
                    ?: throw IOException(getString(R.string.visual_edit_add_pages_invalid))
                val activeRenderer = renderer ?: throw IOException(getString(R.string.cannot_open_pdf))
                val targetWidth = (
                    resources.displayMetrics.widthPixels -
                        (resources.displayMetrics.density * 24f)
                    ).coerceAtLeast(resources.displayMetrics.widthPixels * 0.7f) *
                    EDITOR_RENDER_QUALITY_MULTIPLIER
                activeRenderer.openPage(originalPageIndex).use { page ->
                    val widthScale = targetWidth / page.width.toFloat().coerceAtLeast(1f)
                    val currentMax = max(page.width, page.height).toFloat().coerceAtLeast(1f)
                    val maxSafeScale = PAGE_RENDER_MAX_DIMENSION / currentMax
                    val renderScale = min(EDITOR_MAX_RENDER_SCALE, min(widthScale, maxSafeScale))
                    val width = (page.width * renderScale).roundToInt().coerceAtLeast(1)
                    val height = (page.height * renderScale).roundToInt().coerceAtLeast(1)
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bitmap)
                    canvas.drawColor(Color.WHITE)
                    val matrix = Matrix().apply {
                        postScale(renderScale, renderScale)
                    }
                    page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmap
                }
            }
        }
    }

    private fun renderInsertedPageBitmap(
        insertedPage: InsertedPageState,
        maxDimension: Float
    ): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(insertedPage.bytes, 0, insertedPage.bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IOException(getString(R.string.visual_edit_add_pages_invalid))
        }
        val sampleSize = calculateInSampleSize(
            width = bounds.outWidth,
            height = bounds.outHeight,
            maxDimension = maxDimension
        )
        val decoded = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = sampleSize
        }
        val rawBitmap = BitmapFactory.decodeByteArray(
            insertedPage.bytes,
            0,
            insertedPage.bytes.size,
            decoded
        ) ?: throw IOException(getString(R.string.visual_edit_add_pages_invalid))
        if (!rawBitmap.hasAlpha() && rawBitmap.config == Bitmap.Config.ARGB_8888) {
            return rawBitmap
        }
        val flattenedBitmap = Bitmap.createBitmap(
            rawBitmap.width.coerceAtLeast(1),
            rawBitmap.height.coerceAtLeast(1),
            Bitmap.Config.ARGB_8888
        )
        Canvas(flattenedBitmap).apply {
            drawColor(Color.WHITE)
            drawBitmap(rawBitmap, 0f, 0f, null)
        }
        if (flattenedBitmap != rawBitmap) {
            rawBitmap.recycle()
        }
        return flattenedBitmap
    }

    private fun calculateInSampleSize(width: Int, height: Int, maxDimension: Float): Int {
        val safeMaxDimension = maxDimension.roundToInt().coerceAtLeast(1)
        var sampleSize = 1
        while (max(width / sampleSize, height / sampleSize) > safeMaxDimension) {
            sampleSize *= 2
        }
        return sampleSize.coerceAtLeast(1)
    }

    private fun updatePageUi() {
        val isCurrentPageMarked = currentPageIndex in deletedPages
        val pageStatusSuffix = when {
            selectionOnlyMode && isCurrentPageMarked -> getString(R.string.visual_page_picker_marked_suffix)
            isCurrentPageMarked -> getString(R.string.visual_edit_deleted_suffix)
            isInsertedPageIndex(currentPageIndex) -> getString(R.string.visual_edit_inserted_suffix)
            else -> null
        }
        binding.editPageMeta.text = if (pageStatusSuffix.isNullOrBlank()) {
            pdfName
        } else {
            "$pdfName • $pageStatusSuffix"
        }
        binding.editPageIndicator.text = getString(
            R.string.visual_edit_page_counter,
            currentPageIndex + 1,
            pageCount
        )
        if (!isPageSeekBarTracking) {
            binding.editPageSeekBar.progress = currentPageIndex
        }
        binding.editPreviousPageButton.isEnabled = currentPageIndex > 0
        binding.editNextPageButton.isEnabled = currentPageIndex < pageCount - 1
        binding.selectionOnlyStatusText.text = if (isCurrentPageMarked) {
            getString(R.string.visual_page_picker_status_removed)
        } else {
            getString(R.string.visual_page_picker_status_kept)
        }
        binding.deletePageButton.text = if (isCurrentPageMarked) {
            if (selectionOnlyMode) {
                getString(R.string.visual_page_picker_unmark_page)
            } else {
                getString(R.string.visual_edit_restore_page)
            }
        } else {
            if (selectionOnlyMode) {
                getString(R.string.visual_page_picker_mark_page)
            } else {
                getString(R.string.visual_edit_delete_page)
            }
        }
        updateActionAvailability()
    }

    private fun updateActionAvailability() {
        val hasCurrentMarkup = currentMarkupState().operations.isNotEmpty()
        binding.undoEditButton.isEnabled = !selectionOnlyMode && hasCurrentMarkup
        binding.clearPageButton.isEnabled = !selectionOnlyMode && hasCurrentMarkup
        binding.saveEditedPdfButton.isEnabled = hasEditableOutput()
        updateSelectionControls()
    }

    private fun zoomCanvasUsingButtons(scaleFactor: Float) {
        if (canvasBaseWidth <= 0 || canvasBaseHeight <= 0) {
            return
        }
        adjustCanvasZoom(scaleFactor = scaleFactor)
    }

    private fun updateCanvasZoomControls() {
        val canZoom = canvasBaseWidth > 0 && canvasBaseHeight > 0
        binding.editZoomInButton.isEnabled =
            canZoom && canvasZoomScale < MAX_CANVAS_ZOOM - CANVAS_SCALE_EPSILON
        binding.editZoomOutButton.isEnabled =
            canZoom && canvasZoomScale > canvasMinZoomScale + CANVAS_SCALE_EPSILON
    }

    private fun updateSelectionControls() {
        val canResizeSelection = !selectionOnlyMode && binding.editOverlay.hasSelectedResizableOperation()
        val hasTextSelection = !selectionOnlyMode && binding.editOverlay.hasSelectedTextOperation()
        binding.editSelectedCard.isVisible = false
        binding.editUndoRow.isVisible = false
        binding.selectedSmallerButton.isEnabled = canResizeSelection
        binding.selectedLargerButton.isEnabled = canResizeSelection
        binding.toggleBoldButton.isEnabled = hasTextSelection
    }

    private fun currentMarkupState(): PageMarkupState {
        return pageMarkups.getOrPut(currentPageIndex) { PageMarkupState() }
    }

    private fun hasAnyMarkup(): Boolean = pageMarkups.values.any { !it.isEmpty() }

    private fun hasEditableOutput(): Boolean =
        if (selectionOnlyMode) {
            pageCount > 0
        } else {
            hasAnyMarkup() ||
                binding.pageNumbersChip.isChecked ||
                deletedPages.isNotEmpty() ||
                insertedPages.isNotEmpty()
        }

    private fun toggleDeleteCurrentPage() {
        if (currentPageIndex in deletedPages) {
            deletedPages.remove(currentPageIndex)
        } else {
            deletedPages.add(currentPageIndex)
        }
        if (!selectionOnlyMode) {
            hasUnsavedDraftChanges = true
        }
        updatePageUi()
    }

    private fun applyPageSelection() {
        val resultIntent = Intent().apply {
            putIntegerArrayListExtra(
                EXTRA_RESULT_REMOVED_PAGES,
                ArrayList(deletedPages.map { it + 1 }.sorted())
            )
        }
        setResult(RESULT_OK, resultIntent)
        finish()
    }

    private fun showJumpToPageDialog() {
        if (pageCount <= 0) {
            return
        }

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
            showPage(pageNumber - 1)
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
        jumpToPageDialog?.dismiss()
        jumpToPageDialog = dialog
        dialog.show()
    }

    private fun showInsertPagesDialog(onConfirm: (Int) -> Unit) {
        if (selectionOnlyMode) {
            return
        }

        val maxPosition = (pageCount + 1).coerceAtLeast(1)
        val defaultPosition = (currentPageIndex + 1).coerceIn(1, maxPosition)
        val dialogBinding = DialogInsertPagesBinding.inflate(layoutInflater)
        dialogBinding.insertPagesRangeBadge.text = getString(
            R.string.visual_edit_insert_pages_range,
            1,
            maxPosition
        )
        dialogBinding.insertPagesInput.setText(defaultPosition.toString())
        dialogBinding.insertPagesInput.setSelection(dialogBinding.insertPagesInput.text?.length ?: 0)
        dialogBinding.insertPagesFirstButton.setOnClickListener {
            dialogBinding.insertPagesInput.setText("1")
            dialogBinding.insertPagesInput.setSelection(dialogBinding.insertPagesInput.text?.length ?: 0)
        }
        dialogBinding.insertPagesCurrentButton.setOnClickListener {
            dialogBinding.insertPagesInput.setText(defaultPosition.toString())
            dialogBinding.insertPagesInput.setSelection(dialogBinding.insertPagesInput.text?.length ?: 0)
        }
        dialogBinding.insertPagesLastButton.setOnClickListener {
            dialogBinding.insertPagesInput.setText(maxPosition.toString())
            dialogBinding.insertPagesInput.setSelection(dialogBinding.insertPagesInput.text?.length ?: 0)
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogBinding.root)
            .create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        dialogBinding.insertPagesCancelButton.setOnClickListener {
            dialog.dismiss()
        }
        dialogBinding.insertPagesConfirmButton.setOnClickListener {
            val startPosition = dialogBinding.insertPagesInput.text?.toString()?.trim()?.toIntOrNull()
            if (startPosition == null || startPosition !in 1..maxPosition) {
                dialogBinding.insertPagesInputLayout.error =
                    getString(R.string.visual_edit_insert_pages_error, maxPosition)
                return@setOnClickListener
            }
            dialogBinding.insertPagesInputLayout.error = null
            pendingInsertStartIndex = startPosition - 1
            dialog.dismiss()
            onConfirm(pendingInsertStartIndex)
        }
        dialog.setOnShowListener {
            dialogBinding.insertPagesInput.requestFocus()
        }
        dialog.show()
    }

    private fun showInsertPdfPagesDialog(importPdfUri: Uri) {
        val importPdfName = queryDisplayName(importPdfUri).orEmpty().ifBlank {
            getString(R.string.fallback_pdf_name)
        }
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val localPdf = LocalPdfStore.prepareForRead(this@PdfEditActivity, importPdfUri, importPdfName)
                    ParcelFileDescriptor.open(localPdf.file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                        PdfRenderer(descriptor).use { renderer ->
                            renderer.pageCount
                        }
                    }
                }
            }

            result.onSuccess { importPageCount ->
                val dialogBinding = DialogInsertPdfPagesBinding.inflate(layoutInflater)
                dialogBinding.insertPdfPageRangeBadge.text = getString(
                    R.string.visual_edit_insert_pages_range,
                    1,
                    importPageCount
                )
                dialogBinding.insertPdfSourceName.text = importPdfName
                dialogBinding.insertPdfAllButton.setOnClickListener {
                    dialogBinding.insertPdfPageInput.setText("")
                }
                dialogBinding.insertPdfCurrentButton.setOnClickListener {
                    dialogBinding.insertPdfPageInput.setText((currentPageIndex + 1).toString())
                    dialogBinding.insertPdfPageInput.setSelection(
                        dialogBinding.insertPdfPageInput.text?.length ?: 0
                    )
                }

                val dialog = MaterialAlertDialogBuilder(this@PdfEditActivity)
                    .setView(dialogBinding.root)
                    .create()
                dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

                dialogBinding.insertPdfCancelButton.setOnClickListener {
                    dialog.dismiss()
                }
                dialogBinding.insertPdfConfirmButton.setOnClickListener {
                    val requestedPages = runCatching {
                        parsePageSequenceInput(dialogBinding.insertPdfPageInput.text?.toString().orEmpty())
                    }.getOrElse {
                        dialogBinding.insertPdfPageInputLayout.error =
                            getString(R.string.visual_edit_insert_pdf_invalid)
                        return@setOnClickListener
                    }
                    val pageNumbers = if (requestedPages.isEmpty()) {
                        (1..importPageCount).toList()
                    } else {
                        requestedPages
                    }
                    if (pageNumbers.any { it !in 1..importPageCount }) {
                        dialogBinding.insertPdfPageInputLayout.error =
                            getString(R.string.visual_edit_insert_pages_error, importPageCount)
                        return@setOnClickListener
                    }
                    dialogBinding.insertPdfPageInputLayout.error = null
                    dialog.dismiss()
                    importPagesFromPdf(importPdfUri, importPdfName, pageNumbers)
                }
                dialog.setOnShowListener {
                    dialogBinding.insertPdfPageInput.requestFocus()
                }
                dialog.show()
            }.onFailure { error ->
                showMessage(
                    getString(
                        R.string.visual_edit_insert_pdf_failed,
                        error.message ?: getString(R.string.unknown_error)
                    )
                )
            }
        }
    }

    private fun parsePageSequenceInput(value: String): List<Int> {
        val trimmedValue = value.trim()
        if (trimmedValue.isEmpty()) {
            return emptyList()
        }

        val pages = mutableListOf<Int>()
        for (token in trimmedValue.split(",")) {
            val part = token.trim()
            if (part.isEmpty()) {
                continue
            }

            when {
                part.matches(Regex("\\d+")) -> {
                    val page = part.toInt()
                    if (page <= 0) {
                        throw IllegalArgumentException(getString(R.string.visual_edit_insert_pdf_invalid))
                    }
                    pages.add(page)
                }

                part.matches(Regex("\\d+\\s*-\\s*\\d+")) -> {
                    val rangeParts = part.split("-").map { it.trim() }
                    val startPage = rangeParts[0].toInt()
                    val endPage = rangeParts[1].toInt()
                    if (startPage <= 0 || endPage <= 0 || startPage > endPage) {
                        throw IllegalArgumentException(getString(R.string.visual_edit_insert_pdf_invalid))
                    }
                    for (page in startPage..endPage) {
                        pages.add(page)
                    }
                }

                else -> throw IllegalArgumentException(getString(R.string.visual_edit_insert_pdf_invalid))
            }
        }
        return pages
    }

    private fun importPagesFromPdf(importPdfUri: Uri, importPdfName: String, pageNumbers: List<Int>) {
        if (pageNumbers.isEmpty()) {
            return
        }

        setLoading(true)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val localPdf = LocalPdfStore.prepareForRead(this@PdfEditActivity, importPdfUri, importPdfName)
                    ParcelFileDescriptor.open(localPdf.file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                        PdfRenderer(descriptor).use { importRenderer ->
                            pageNumbers.map { pageNumber ->
                                importRenderer.openPage(pageNumber - 1).use { importPage ->
                                    val bitmap = renderExportBitmap(importPage)
                                    try {
                                        InsertedPageState(
                                            bytes = compressBitmapToJpeg(bitmap, EXPORT_JPEG_QUALITY),
                                            sourceName = "$importPdfName · ${pageNumber}"
                                        )
                                    } finally {
                                        bitmap.recycle()
                                    }
                                }
                            }
                        }
                    }
                }
            }

            result.onSuccess { importedPages ->
                if (importedPages.isEmpty()) {
                    setLoading(false)
                    return@onSuccess
                }
                val insertAt = pendingInsertStartIndex.coerceIn(0, pageCount)
                shiftPageStateForInsertion(insertAt, importedPages.size)
                shiftInsertedPagePositions(insertAt, importedPages.size)
                val insertedEntries = importedPages.mapIndexed { offset, page ->
                    val updatedPage = page.copy(position = insertAt + offset)
                    insertedPages += updatedPage
                    EditorPageEntry.Inserted(updatedPage)
                }
                displayPages.addAll(insertAt, insertedEntries)
                syncInsertedPagePositionsFromDisplayOrder()
                refreshPageCountState()
                hasUnsavedDraftChanges = true
                pageBitmapCache.evictAll()
                showPage(insertAt)
                showMessage(getString(R.string.visual_edit_add_pages_success, importedPages.size))
            }.onFailure { error ->
                showMessage(
                    getString(
                        R.string.visual_edit_insert_pdf_failed,
                        error.message ?: getString(R.string.unknown_error)
                    )
                )
                setLoading(false)
            }
        }
    }

    private fun showAddTextDialog(xRatio: Float, yRatio: Float) {
        addTextDialog?.dismiss()
        val dialogBinding = DialogVisualAddTextBinding.inflate(layoutInflater)
        val colorChoices = buildTextColorChoices()
        val clipboardManager = getSystemService(ClipboardManager::class.java)
        dialogBinding.addTextColorGroup.check(R.id.addTextColorInkChip)
        colorChoices.forEach { choice ->
            dialogBinding.root.findViewById<Chip>(choice.chipId)?.apply {
                isCheckedIconVisible = false
                setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) {
                        updateAddTextColorSelection(dialogBinding, colorChoices)
                    }
                }
            }
        }
        dialogBinding.pasteClipboardButton.setOnClickListener {
            val clipboardText = currentClipboardText(clipboardManager)
            if (clipboardText.isNullOrBlank()) {
                showMessage(getString(R.string.visual_edit_text_paste_empty))
                return@setOnClickListener
            }
            pasteIntoAddTextField(dialogBinding, clipboardText)
        }
        updateAddTextColorSelection(dialogBinding, colorChoices)

        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogBinding.root)
            .create()
        addTextDialog = dialog

        dialog.setCancelable(true)
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        @Suppress("DEPRECATION")
        val dialogSoftInputMode =
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
        dialog.window?.setSoftInputMode(dialogSoftInputMode)
        dialogBinding.addTextCancelButton.setOnClickListener {
            dialog.dismiss()
        }
        dialogBinding.addTextConfirmButton.setOnClickListener {
            val value = dialogBinding.addTextInput.text?.toString()?.trim().orEmpty()
            if (value.isEmpty()) {
                dialogBinding.addTextInputLayout.error = getString(R.string.visual_edit_empty_text)
                return@setOnClickListener
            }
            dialogBinding.addTextInputLayout.error = null
            binding.editOverlay.addText(
                text = value,
                xRatio = xRatio,
                yRatio = yRatio,
                color = selectedAddTextColor(dialogBinding),
                isBold = dialogBinding.addTextBoldToggle.isChecked
            )
            binding.editOverlay.selectLastOperation()
            applyMoveToolSelection()
            dialog.dismiss()
        }
        dialog.setOnShowListener {
            dialogBinding.addTextInput.requestFocus()
        }
        dialog.setOnDismissListener {
            if (addTextDialog === dialog) {
                addTextDialog = null
            }
        }
        dialog.show()
    }

    private fun showAddLinkDialog(xRatio: Float, yRatio: Float) {
        addTextDialog?.dismiss()
        val dialogBinding = DialogAddLinkBinding.inflate(layoutInflater)
        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogBinding.root)
            .create()
        addTextDialog = dialog

        dialog.setCancelable(true)
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        @Suppress("DEPRECATION")
        val dialogSoftInputMode =
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
        dialog.window?.setSoftInputMode(dialogSoftInputMode)

        dialogBinding.addLinkCancelButton.setOnClickListener {
            dialog.dismiss()
        }
        dialogBinding.addLinkConfirmButton.setOnClickListener {
            val linkText = dialogBinding.addLinkTextInput.text?.toString()?.trim().orEmpty()
            val rawUrl = dialogBinding.addLinkUrlInput.text?.toString()?.trim().orEmpty()
            if (linkText.isEmpty()) {
                dialogBinding.addLinkTextInputLayout.error =
                    getString(R.string.visual_edit_link_invalid_text)
                return@setOnClickListener
            }
            dialogBinding.addLinkTextInputLayout.error = null
            val normalizedUrl = normalizeLinkUrl(rawUrl)
            if (normalizedUrl == null) {
                dialogBinding.addLinkUrlInputLayout.error =
                    getString(R.string.visual_edit_link_invalid_url)
                return@setOnClickListener
            }
            dialogBinding.addLinkUrlInputLayout.error = null
            binding.editOverlay.addLink(
                text = linkText,
                url = normalizedUrl,
                xRatio = xRatio,
                yRatio = yRatio
            )
            binding.editOverlay.selectLastOperation()
            applyMoveToolSelection()
            dialog.dismiss()
        }
        dialog.setOnShowListener {
            dialogBinding.addLinkTextInput.requestFocus()
        }
        dialog.setOnDismissListener {
            if (addTextDialog === dialog) {
                addTextDialog = null
            }
        }
        dialog.show()
    }

    private fun normalizeLinkUrl(rawUrl: String): String? {
        val trimmedUrl = rawUrl.trim()
        if (trimmedUrl.isEmpty()) {
            return null
        }
        val normalizedUrl = if (trimmedUrl.contains("://")) {
            trimmedUrl
        } else {
            "https://$trimmedUrl"
        }
        val parsedUri = Uri.parse(normalizedUrl)
        val scheme = parsedUri.scheme.orEmpty()
        val host = parsedUri.host.orEmpty()
        return if (scheme.isNotBlank() && host.isNotBlank()) {
            normalizedUrl
        } else {
            null
        }
    }

    private fun buildTextColorChoices(): List<TextColorChoice> {
        return listOf(
            TextColorChoice(
                chipId = R.id.addTextColorInkChip,
                textColor = getColor(R.color.title_text),
                selectedBackgroundColor = getColor(R.color.tool_indigo_bg),
                selectedTextColor = getColor(R.color.title_text),
                selectedStrokeColor = getColor(R.color.title_text)
            ),
            TextColorChoice(
                chipId = R.id.addTextColorBlackChip,
                textColor = Color.BLACK,
                selectedBackgroundColor = getColor(R.color.title_text),
                selectedTextColor = Color.WHITE,
                unselectedTextColor = Color.BLACK,
                selectedStrokeColor = getColor(R.color.title_text)
            ),
            TextColorChoice(
                chipId = R.id.addTextColorWhiteChip,
                textColor = Color.WHITE,
                selectedBackgroundColor = Color.WHITE,
                selectedTextColor = getColor(R.color.title_text),
                unselectedTextColor = getColor(R.color.title_text),
                selectedStrokeColor = getColor(R.color.card_stroke)
            ),
            TextColorChoice(
                chipId = R.id.addTextColorCoralChip,
                textColor = getColor(R.color.progress_color),
                selectedBackgroundColor = getColor(R.color.button_secondary_bg),
                selectedTextColor = getColor(R.color.progress_color),
                selectedStrokeColor = getColor(R.color.progress_color)
            ),
            TextColorChoice(
                chipId = R.id.addTextColorBlueChip,
                textColor = getColor(R.color.document_accent),
                selectedBackgroundColor = getColor(R.color.tool_blue_bg),
                selectedTextColor = getColor(R.color.document_accent),
                selectedStrokeColor = getColor(R.color.document_accent)
            ),
            TextColorChoice(
                chipId = R.id.addTextColorGreenChip,
                textColor = getColor(R.color.tool_green_text),
                selectedBackgroundColor = getColor(R.color.tool_green_bg),
                selectedTextColor = getColor(R.color.tool_green_text),
                selectedStrokeColor = getColor(R.color.tool_green_text)
            ),
            TextColorChoice(
                chipId = R.id.addTextColorRoseChip,
                textColor = getColor(R.color.tool_rose_text),
                selectedBackgroundColor = getColor(R.color.tool_rose_bg),
                selectedTextColor = getColor(R.color.tool_rose_text),
                selectedStrokeColor = getColor(R.color.tool_rose_text)
            ),
            TextColorChoice(
                chipId = R.id.addTextColorGoldChip,
                textColor = getColor(R.color.tool_gold_text),
                selectedBackgroundColor = getColor(R.color.tool_gold_bg),
                selectedTextColor = getColor(R.color.tool_gold_text),
                selectedStrokeColor = getColor(R.color.tool_gold_text)
            )
        )
    }

    private fun currentClipboardText(clipboardManager: ClipboardManager?): String? {
        val primaryClip = clipboardManager?.primaryClip ?: return null
        if (primaryClip.itemCount == 0) {
            return null
        }
        return primaryClip.getItemAt(0).coerceToText(this)?.toString()?.trim()
    }

    private fun pasteIntoAddTextField(
        dialogBinding: DialogVisualAddTextBinding,
        clipboardText: String
    ) {
        val editable = dialogBinding.addTextInput.text
        if (editable == null) {
            dialogBinding.addTextInput.setText(clipboardText)
            dialogBinding.addTextInput.setSelection(dialogBinding.addTextInput.text?.length ?: 0)
            return
        }
        val start = dialogBinding.addTextInput.selectionStart.coerceAtLeast(0)
        val end = dialogBinding.addTextInput.selectionEnd.coerceAtLeast(0)
        editable.replace(min(start, end), max(start, end), clipboardText)
        dialogBinding.addTextInput.setSelection(
            (min(start, end) + clipboardText.length).coerceAtMost(editable.length)
        )
    }

    private fun updateAddTextColorSelection(
        dialogBinding: DialogVisualAddTextBinding,
        colorChoices: List<TextColorChoice>
    ) {
        val selectedChoice = selectedAddTextChoice(dialogBinding, colorChoices)
        colorChoices.forEach { choice ->
            val chip = dialogBinding.root.findViewById<Chip>(choice.chipId) ?: return@forEach
            val isChecked = chip.id == selectedChoice.chipId
            chip.chipBackgroundColor = ColorStateList.valueOf(
                if (isChecked) choice.selectedBackgroundColor else getColor(R.color.card_surface)
            )
            chip.chipStrokeColor = ColorStateList.valueOf(
                if (isChecked) choice.selectedStrokeColor else getColor(R.color.card_stroke)
            )
            chip.chipStrokeWidth = resources.displayMetrics.density * if (isChecked) 1.5f else 1f
            chip.setTextColor(if (isChecked) choice.selectedTextColor else choice.unselectedTextColor)
        }
    }

    private fun selectedAddTextChoice(
        dialogBinding: DialogVisualAddTextBinding,
        colorChoices: List<TextColorChoice>
    ): TextColorChoice {
        return colorChoices.firstOrNull { it.chipId == dialogBinding.addTextColorGroup.checkedChipId }
            ?: colorChoices.first()
    }

    private fun selectedAddTextColor(dialogBinding: DialogVisualAddTextBinding): Int {
        return selectedAddTextChoice(dialogBinding, buildTextColorChoices()).textColor
    }

    private fun addImageToCurrentPage(imageUri: Uri) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.openInputStream(imageUri)?.use { stream ->
                        stream.readBytes()
                    } ?: throw IOException(getString(R.string.cannot_open_pdf))
                }
            }
            result.onSuccess { imageBytes ->
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                    showMessage(
                        getString(
                            R.string.visual_edit_pick_image_failed,
                            getString(R.string.unknown_error)
                        )
                    )
                    return@onSuccess
                }
                binding.editOverlay.addImage(imageBytes)
                binding.editOverlay.selectLastOperation()
                applyMoveToolSelection()
            }.onFailure { error ->
                showMessage(
                    getString(
                        R.string.visual_edit_pick_image_failed,
                        error.message ?: getString(R.string.unknown_error)
                    )
                )
            }
        }
    }

    private fun addCoverToCurrentPage(color: Int) {
        if (selectionOnlyMode) {
            return
        }
        val placement = resolveCoverPlacement()
        binding.editOverlay.addCover(
            color = color,
            xRatio = placement.first,
            yRatio = placement.second
        )
        binding.editOverlay.selectLastOperation()
        applyMoveToolSelection()
        updateActionAvailability()
    }

    private fun resolveCoverPlacement(): Pair<Float, Float> {
        val frameWidth = binding.editCanvasFrame.width.toFloat()
        val frameHeight = binding.editCanvasFrame.height.toFloat()
        if (frameWidth <= 0f || frameHeight <= 0f) {
            return 0.12f to 0.18f
        }

        val contentCenterX = binding.editHorizontalScrollView.scrollX +
            (binding.editHorizontalScrollView.width / 2f)
        val contentCenterY = binding.editScrollView.scrollY +
            (binding.editScrollView.height / 2f)
        val frameLeft = binding.editCanvasViewport.left + binding.editCanvasFrame.left
        val frameTop = binding.editCanvasViewport.top + binding.editCanvasFrame.top
        val centeredXRatio = (
            (contentCenterX - frameLeft) / frameWidth
            ) - (MarkupOperation.DEFAULT_COVER_WIDTH_RATIO / 2f)
        val centeredYRatio = (
            (contentCenterY - frameTop) / frameHeight
            ) - (MarkupOperation.DEFAULT_COVER_HEIGHT_RATIO / 2f)
        return centeredXRatio.coerceIn(0.02f, 0.88f) to centeredYRatio.coerceIn(0.02f, 0.88f)
    }

    private fun resolveTextLikePlacement(): Pair<Float, Float> {
        val frameWidth = binding.editCanvasFrame.width.toFloat()
        val frameHeight = binding.editCanvasFrame.height.toFloat()
        if (frameWidth <= 0f || frameHeight <= 0f) {
            return 0.12f to 0.18f
        }

        val contentCenterX = binding.editHorizontalScrollView.scrollX +
            (binding.editHorizontalScrollView.width / 2f)
        val contentCenterY = binding.editScrollView.scrollY +
            (binding.editScrollView.height / 2f)
        val frameLeft = binding.editCanvasViewport.left + binding.editCanvasFrame.left
        val frameTop = binding.editCanvasViewport.top + binding.editCanvasFrame.top
        val centeredXRatio = ((contentCenterX - frameLeft) / frameWidth)
        val centeredYRatio = ((contentCenterY - frameTop) / frameHeight)
        return centeredXRatio.coerceIn(0.05f, 0.95f) to centeredYRatio.coerceIn(0.08f, 0.95f)
    }

    private fun addPagesFromImages(imageUris: List<Uri>) {
        if (imageUris.isEmpty()) {
            return
        }

        setLoading(true)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    imageUris.map { imageUri ->
                        val imageBytes = contentResolver.openInputStream(imageUri)?.use { stream ->
                            stream.readBytes()
                        } ?: throw IOException(getString(R.string.visual_edit_add_pages_invalid))
                        val previewBitmap = renderInsertedPageBitmap(
                            insertedPage = InsertedPageState(bytes = imageBytes),
                            maxDimension = EXPORT_RENDER_MAX_DIMENSION
                        )
                        previewBitmap.recycle()
                        InsertedPageState(
                            bytes = imageBytes,
                            sourceName = queryDisplayName(imageUri).orEmpty()
                        )
                    }
                }
            }

            result.onSuccess { pages ->
                if (pages.isEmpty()) {
                    setLoading(false)
                    return@onSuccess
                }
                val insertAt = pendingInsertStartIndex.coerceIn(0, pageCount)
                shiftPageStateForInsertion(insertAt, pages.size)
                shiftInsertedPagePositions(insertAt, pages.size)
                val insertedEntries = pages.mapIndexed { offset, page ->
                    val updatedPage = page.copy(position = insertAt + offset)
                    insertedPages += updatedPage
                    EditorPageEntry.Inserted(updatedPage)
                }
                displayPages.addAll(insertAt, insertedEntries)
                syncInsertedPagePositionsFromDisplayOrder()
                refreshPageCountState()
                hasUnsavedDraftChanges = true
                pageBitmapCache.evictAll()
                showPage(insertAt)
                showMessage(getString(R.string.visual_edit_add_pages_success, pages.size))
            }.onFailure { error ->
                showMessage(
                    getString(
                        R.string.visual_edit_add_pages_failed,
                        error.message ?: getString(R.string.unknown_error)
                    )
                )
                setLoading(false)
            }
        }
    }

    private fun movePageAssociatedState(fromIndex: Int, toIndex: Int) {
        if (fromIndex == toIndex || pageCount <= 1) {
            return
        }

        val markupStates = MutableList<PageMarkupState?>(pageCount) { index ->
            pageMarkups[index]
        }
        val movedMarkupState = markupStates.removeAt(fromIndex)
        markupStates.add(toIndex, movedMarkupState)
        pageMarkups.clear()
        markupStates.forEachIndexed { index, state ->
            if (state != null && !state.isEmpty()) {
                pageMarkups[index] = state
            }
        }

        val deletedFlags = MutableList(pageCount) { index ->
            index in deletedPages
        }
        val movedDeletedFlag = deletedFlags.removeAt(fromIndex)
        deletedFlags.add(toIndex, movedDeletedFlag)
        deletedPages.clear()
        deletedFlags.forEachIndexed { index, isDeleted ->
            if (isDeleted) {
                deletedPages += index
            }
        }
    }

    private fun moveDisplayPage(fromIndex: Int, toIndex: Int) {
        if (
            fromIndex == toIndex ||
            fromIndex !in displayPages.indices ||
            toIndex !in displayPages.indices
        ) {
            return
        }

        val movedPage = displayPages.removeAt(fromIndex)
        displayPages.add(toIndex, movedPage)
        movePageAssociatedState(fromIndex, toIndex)
        currentPageIndex = when {
            currentPageIndex == fromIndex -> toIndex
            fromIndex < currentPageIndex && currentPageIndex <= toIndex -> currentPageIndex - 1
            toIndex <= currentPageIndex && currentPageIndex < fromIndex -> currentPageIndex + 1
            else -> currentPageIndex
        }
        syncInsertedPagePositionsFromDisplayOrder()
        refreshPageCountState()
        hasUnsavedDraftChanges = true
        pageBitmapCache.evictAll()
    }

    private fun showReorderPagesSheet() {
        if (selectionOnlyMode || pageCount <= 1) {
            return
        }

        reorderPagesDialog?.dismiss()
        val sheetBinding = SheetReorderPagesBinding.inflate(layoutInflater)
        val dialog = BottomSheetDialog(this)
        reorderPagesDialog = dialog
        dialog.setContentView(sheetBinding.root)

        val adapter = ReorderPagesAdapter()
        sheetBinding.reorderPagesRecyclerView.layoutManager = LinearLayoutManager(this)
        sheetBinding.reorderPagesRecyclerView.adapter = adapter
        adapter.submitEntries(displayPages)
        sheetBinding.reorderPagesQuickMoveButton.setOnClickListener {
            showMovePageByNumberDialog {
                adapter.submitEntries(displayPages)
                adapter.notifyDataSetChanged()
                sheetBinding.reorderPagesRecyclerView.scrollToPosition(currentPageIndex)
            }
        }

        val touchHelper = ItemTouchHelper(
            object : ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.UP or ItemTouchHelper.DOWN,
                0
            ) {
                override fun onMove(
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder,
                    target: RecyclerView.ViewHolder
                ): Boolean {
                    val fromPosition = viewHolder.bindingAdapterPosition
                    val toPosition = target.bindingAdapterPosition
                    if (
                        fromPosition == RecyclerView.NO_POSITION ||
                        toPosition == RecyclerView.NO_POSITION
                    ) {
                        return false
                    }
                    moveDisplayPage(fromPosition, toPosition)
                    adapter.submitEntries(displayPages)
                    adapter.notifyItemMoved(fromPosition, toPosition)
                    return true
                }

                override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

                override fun isLongPressDragEnabled(): Boolean = true
            }
        )
        touchHelper.attachToRecyclerView(sheetBinding.reorderPagesRecyclerView)

        sheetBinding.reorderPagesDoneButton.setOnClickListener {
            dialog.dismiss()
            showPage(currentPageIndex)
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
            if (reorderPagesDialog === dialog) {
                reorderPagesDialog = null
            }
            showPage(currentPageIndex)
        }
        dialog.show()
    }

    private fun showMovePageByNumberDialog(onMoved: () -> Unit) {
        if (selectionOnlyMode || pageCount <= 1) {
            return
        }

        val dialogBinding = DialogReorderPageBinding.inflate(layoutInflater)
        val defaultSource = (currentPageIndex + 1).coerceIn(1, pageCount)
        dialogBinding.movePageSourceInput.setText(defaultSource.toString())
        dialogBinding.movePageSourceInput.setSelection(dialogBinding.movePageSourceInput.text?.length ?: 0)
        dialogBinding.movePageTargetInput.setText(defaultSource.toString())
        dialogBinding.movePageTargetInput.setSelection(dialogBinding.movePageTargetInput.text?.length ?: 0)
        dialogBinding.movePageTopButton.setOnClickListener {
            dialogBinding.movePageTargetInput.setText("1")
            dialogBinding.movePageTargetInput.setSelection(dialogBinding.movePageTargetInput.text?.length ?: 0)
        }
        dialogBinding.movePageHereButton.setOnClickListener {
            dialogBinding.movePageTargetInput.setText(defaultSource.toString())
            dialogBinding.movePageTargetInput.setSelection(dialogBinding.movePageTargetInput.text?.length ?: 0)
        }
        dialogBinding.movePageEndButton.setOnClickListener {
            dialogBinding.movePageTargetInput.setText(pageCount.toString())
            dialogBinding.movePageTargetInput.setSelection(dialogBinding.movePageTargetInput.text?.length ?: 0)
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogBinding.root)
            .create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        dialogBinding.movePageCancelButton.setOnClickListener {
            dialog.dismiss()
        }
        dialogBinding.movePageConfirmButton.setOnClickListener {
            val sourcePage = dialogBinding.movePageSourceInput.text?.toString()?.trim()?.toIntOrNull()
            val targetPage = dialogBinding.movePageTargetInput.text?.toString()?.trim()?.toIntOrNull()
            var hasError = false
            if (sourcePage == null || sourcePage !in 1..pageCount) {
                dialogBinding.movePageSourceInputLayout.error =
                    getString(R.string.visual_edit_reorder_error, pageCount)
                hasError = true
            } else {
                dialogBinding.movePageSourceInputLayout.error = null
            }
            if (targetPage == null || targetPage !in 1..pageCount) {
                dialogBinding.movePageTargetInputLayout.error =
                    getString(R.string.visual_edit_reorder_error, pageCount)
                hasError = true
            } else {
                dialogBinding.movePageTargetInputLayout.error = null
            }
            if (hasError) {
                return@setOnClickListener
            }

            moveDisplayPage(sourcePage!! - 1, targetPage!! - 1)
            onMoved()
            dialog.dismiss()
        }
        dialog.setOnShowListener {
            dialogBinding.movePageSourceInput.requestFocus()
        }
        dialog.show()
    }

    private fun showMoreToolsSheet() {
        if (selectionOnlyMode) {
            return
        }

        moreToolsDialog?.dismiss()
        val sheetBinding = SheetPdfEditToolsBinding.inflate(layoutInflater)
        val dialog = BottomSheetDialog(this)
        moreToolsDialog = dialog
        dialog.setContentView(sheetBinding.root)

        fun syncSheetState() {
            val isCurrentPageMarked = currentPageIndex in deletedPages
            val hasCurrentMarkup = currentMarkupState().operations.isNotEmpty()
            val canResizeSelection = binding.editOverlay.hasSelectedResizableOperation()
            val hasTextSelection = binding.editOverlay.hasSelectedTextOperation()
            val selectionAvailable = canResizeSelection || hasTextSelection

            sheetBinding.moreToolsDocumentName.text = pdfName
            sheetBinding.moreToolsPageMeta.text = getString(
                R.string.visual_edit_page_counter,
                currentPageIndex + 1,
                pageCount
            )
            sheetBinding.moreToolsStatusBadge.text = getString(
                when {
                    isCurrentPageMarked -> R.string.visual_edit_more_status_deleted
                    isInsertedPageIndex(currentPageIndex) -> R.string.visual_edit_more_status_inserted
                    else -> R.string.visual_edit_more_status_ready
                }
            )
            sheetBinding.moreToolsSelectionSection.isVisible = selectionAvailable
            sheetBinding.sheetSelectedSmallerButton.isEnabled = canResizeSelection
            sheetBinding.sheetSelectedLargerButton.isEnabled = canResizeSelection
            sheetBinding.sheetSelectedBoldButton.isEnabled = hasTextSelection
            sheetBinding.sheetDeletePageButton.text = getString(
                if (isCurrentPageMarked) {
                    R.string.visual_edit_restore_page
                } else {
                    R.string.visual_edit_delete_page
                }
            )
            sheetBinding.sheetUndoEditButton.isEnabled = hasCurrentMarkup
            sheetBinding.sheetClearPageButton.isEnabled = hasCurrentMarkup
            if (sheetBinding.sheetPageNumbersChip.isChecked != binding.pageNumbersChip.isChecked) {
                sheetBinding.sheetPageNumbersChip.isChecked = binding.pageNumbersChip.isChecked
            }
        }

        sheetBinding.sheetPageNumbersChip.setOnCheckedChangeListener { _, isChecked ->
            if (binding.pageNumbersChip.isChecked != isChecked) {
                binding.pageNumbersChip.isChecked = isChecked
            }
        }
        sheetBinding.sheetAddLinkButton.setOnClickListener {
            dialog.dismiss()
            val placement = resolveTextLikePlacement()
            showAddLinkDialog(
                xRatio = placement.first,
                yRatio = placement.second
            )
        }
        sheetBinding.sheetAddPagesButton.setOnClickListener {
            dialog.dismiss()
            showInsertPagesDialog { insertAt ->
                pendingInsertStartIndex = insertAt
                addPageImagesLauncher.launch(arrayOf("image/*"))
            }
        }
        sheetBinding.sheetInsertPdfPagesButton.setOnClickListener {
            dialog.dismiss()
            showInsertPagesDialog { insertAt ->
                pendingInsertStartIndex = insertAt
                pickInsertPdfLauncher.launch(arrayOf("application/pdf"))
            }
        }
        sheetBinding.sheetReorderPagesButton.setOnClickListener {
            dialog.dismiss()
            showReorderPagesSheet()
        }
        sheetBinding.sheetWhiteoutButton.setOnClickListener {
            dialog.dismiss()
            addCoverToCurrentPage(MarkupOperation.DEFAULT_WHITEOUT_COLOR)
        }
        sheetBinding.sheetRedactButton.setOnClickListener {
            dialog.dismiss()
            addCoverToCurrentPage(MarkupOperation.DEFAULT_REDACT_COLOR)
        }
        sheetBinding.sheetSelectedSmallerButton.setOnClickListener {
            if (binding.editOverlay.adjustSelectedElementScale(0.9f)) {
                updateSelectionControls()
                updateActionAvailability()
                syncSheetState()
            }
        }
        sheetBinding.sheetSelectedLargerButton.setOnClickListener {
            if (binding.editOverlay.adjustSelectedElementScale(1.1f)) {
                updateSelectionControls()
                updateActionAvailability()
                syncSheetState()
            }
        }
        sheetBinding.sheetSelectedBoldButton.setOnClickListener {
            if (binding.editOverlay.toggleSelectedTextBold()) {
                updateSelectionControls()
                updateActionAvailability()
                syncSheetState()
            }
        }
        sheetBinding.sheetDeletePageButton.setOnClickListener {
            toggleDeleteCurrentPage()
            syncSheetState()
        }
        sheetBinding.sheetUndoEditButton.setOnClickListener {
            binding.editOverlay.undoLast()
            updateActionAvailability()
            syncSheetState()
        }
        sheetBinding.sheetClearPageButton.setOnClickListener {
            binding.editOverlay.clearPage()
            updateActionAvailability()
            syncSheetState()
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
            syncSheetState()
        }
        dialog.setOnDismissListener {
            if (moreToolsDialog === dialog) {
                moreToolsDialog = null
            }
        }
        dialog.show()
    }

    private fun saveVisualEdits(outputUri: Uri) {
        val inputUri = pdfUri ?: return
        val pageNumbersEnabled = binding.pageNumbersChip.isChecked
        val deletedPagesSnapshot = deletedPages.toSet()
        val insertedPagesSnapshot = insertedPages.toList()
        val pageMarkupsSnapshot = pageMarkups.toMap()
        setLoading(true)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    exportVisualEdits(
                        inputUri = inputUri,
                        outputUri = outputUri
                    )
                    val outputDisplayName = queryDisplayName(outputUri)
                        ?: getString(R.string.fallback_pdf_name)
                    val sourceDisplayName = queryDisplayName(inputUri).orEmpty().ifBlank {
                        pdfName
                    }
                    VisualEditProjectStore.saveProject(
                        context = this@PdfEditActivity,
                        editedUri = outputUri,
                        sourceUri = inputUri,
                        resumeDisplayName = outputDisplayName,
                        sourceDisplayName = sourceDisplayName,
                        pageCount = pageCount,
                        pageNumbersEnabled = pageNumbersEnabled,
                        deletedPages = deletedPagesSnapshot,
                        insertedPages = insertedPagesSnapshot,
                        pageOrderTokens = currentPageOrderTokens(),
                        pageMarkups = pageMarkupsSnapshot
                    )
                    (requestedPdfUri ?: inputUri).let { resumeUri ->
                        VisualEditProjectStore.clearDraft(this@PdfEditActivity, resumeUri)
                    }
                }
            }
            result.onSuccess {
                hasUnsavedDraftChanges = false
                showMessage(getString(R.string.visual_edit_saved))
            }.onFailure { error ->
                showMessage(
                    getString(
                        R.string.visual_edit_failed,
                        error.message ?: getString(R.string.unknown_error)
                    )
                )
            }
            setLoading(false)
        }
    }

    private fun previewVisualEdits() {
        val inputUri = pdfUri ?: return
        val pageNumbersEnabled = binding.pageNumbersChip.isChecked
        val deletedPagesSnapshot = deletedPages.toSet()
        val insertedPagesSnapshot = insertedPages.toList()
        val pageMarkupsSnapshot = pageMarkups.toMap()
        val pageOrderTokensSnapshot = currentPageOrderTokens()
        setLoading(true)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val previewFile = buildPreviewPdfFile(pdfName)
                    exportVisualEditsToFile(inputUri, previewFile)
                    val previewUri = Uri.fromFile(previewFile)
                    val sourceDisplayName = queryDisplayName(inputUri).orEmpty().ifBlank {
                        pdfName
                    }
                    VisualEditProjectStore.saveProject(
                        context = this@PdfEditActivity,
                        editedUri = previewUri,
                        sourceUri = inputUri,
                        resumeDisplayName = previewFile.name,
                        sourceDisplayName = sourceDisplayName,
                        pageCount = pageCount,
                        pageNumbersEnabled = pageNumbersEnabled,
                        deletedPages = deletedPagesSnapshot,
                        insertedPages = insertedPagesSnapshot,
                        pageOrderTokens = pageOrderTokensSnapshot,
                        pageMarkups = pageMarkupsSnapshot
                    )
                    previewFile
                }
            }
            result.onSuccess { previewFile ->
                startActivity(
                    PdfViewerActivity.createIntent(
                        context = this@PdfEditActivity,
                        pdfUri = Uri.fromFile(previewFile),
                        pdfName = previewFile.name,
                        startInReadMode = true
                    )
                )
            }.onFailure { error ->
                showMessage(
                    getString(
                        R.string.visual_edit_preview_failed,
                        error.message ?: getString(R.string.unknown_error)
                    )
                )
            }
            setLoading(false)
        }
    }

    @Throws(IOException::class)
    private fun exportVisualEdits(inputUri: Uri, outputUri: Uri) {
        contentResolver.openOutputStream(outputUri)?.use { outputStream ->
            writeVisualEditsToStream(inputUri, outputStream)
        } ?: throw IOException(getString(R.string.cannot_write_pdf))
    }

    @Throws(IOException::class)
    private fun exportVisualEditsToFile(inputUri: Uri, outputFile: File) {
        outputFile.parentFile?.mkdirs()
        FileOutputStream(outputFile).use { outputStream ->
            writeVisualEditsToStream(inputUri, outputStream)
        }
    }

    @Throws(IOException::class)
    private fun writeVisualEditsToStream(inputUri: Uri, outputStream: OutputStream) {
        val localPdf = LocalPdfStore.prepareForRead(this, inputUri, pdfName)
        ParcelFileDescriptor.open(localPdf.file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { exportRenderer ->
                val exportPageIndexes = (0 until pageCount)
                    .filterNot { it in deletedPages }
                if (exportPageIndexes.isEmpty()) {
                    throw IOException(getString(R.string.visual_edit_no_pages_left))
                }
                PDDocument().use { document ->
                    exportPageIndexes.forEachIndexed { exportedIndex, pageIndex ->
                        val originalPageIndex = originalPageIndexAt(pageIndex)
                        if (originalPageIndex != null) {
                            exportRenderer.openPage(originalPageIndex).use { page ->
                                val bitmap = renderExportBitmap(page)
                                try {
                                    pageMarkups[pageIndex]?.let { markupState ->
                                        if (!markupState.isEmpty()) {
                                            val canvas = Canvas(bitmap)
                                            PdfMarkupOverlayView.drawMarkupOperations(
                                                canvas = canvas,
                                                operations = markupState.operations,
                                                targetWidth = bitmap.width.toFloat(),
                                                targetHeight = bitmap.height.toFloat()
                                            )
                                        }
                                    }
                                    if (binding.pageNumbersChip.isChecked) {
                                        drawPageNumber(
                                            canvas = Canvas(bitmap),
                                            pageNumber = exportedIndex + 1,
                                            totalPages = exportPageIndexes.size,
                                            width = bitmap.width.toFloat(),
                                            height = bitmap.height.toFloat()
                                        )
                                    }
                                    appendBitmapPage(
                                        document = document,
                                        bitmap = bitmap,
                                        pageWidth = page.width.toFloat(),
                                        pageHeight = page.height.toFloat(),
                                        markupState = pageMarkups[pageIndex]
                                    )
                                } finally {
                                    bitmap.recycle()
                                }
                            }
                        } else {
                            val insertedPage = insertedPageAt(pageIndex)
                                ?: throw IOException(getString(R.string.visual_edit_add_pages_invalid))
                            val bitmap = renderInsertedPageBitmap(
                                insertedPage = insertedPage,
                                maxDimension = EXPORT_RENDER_MAX_DIMENSION
                            )
                            try {
                                pageMarkups[pageIndex]?.let { markupState ->
                                    if (!markupState.isEmpty()) {
                                        val canvas = Canvas(bitmap)
                                        PdfMarkupOverlayView.drawMarkupOperations(
                                            canvas = canvas,
                                            operations = markupState.operations,
                                            targetWidth = bitmap.width.toFloat(),
                                            targetHeight = bitmap.height.toFloat()
                                        )
                                    }
                                }
                                if (binding.pageNumbersChip.isChecked) {
                                    drawPageNumber(
                                        canvas = Canvas(bitmap),
                                        pageNumber = exportedIndex + 1,
                                        totalPages = exportPageIndexes.size,
                                        width = bitmap.width.toFloat(),
                                        height = bitmap.height.toFloat()
                                    )
                                }
                                appendBitmapPage(
                                    document = document,
                                    bitmap = bitmap,
                                    pageWidth = bitmap.width.toFloat(),
                                    pageHeight = bitmap.height.toFloat(),
                                    markupState = pageMarkups[pageIndex]
                                )
                            } finally {
                                bitmap.recycle()
                            }
                        }
                    }
                    BufferedOutputStream(outputStream).use { bufferedStream ->
                        document.save(bufferedStream)
                    }
                }
            }
        }
    }

    private fun appendBitmapPage(
        document: PDDocument,
        bitmap: Bitmap,
        pageWidth: Float,
        pageHeight: Float,
        markupState: PageMarkupState?
    ): PDPage {
        val jpegBytes = compressBitmapToJpeg(bitmap, EXPORT_JPEG_QUALITY)
        val outputPage = PDPage(PDRectangle(pageWidth, pageHeight))
        document.addPage(outputPage)
        val imageObject = JPEGFactory.createFromByteArray(document, jpegBytes)
        PDPageContentStream(document, outputPage).use { contentStream ->
            contentStream.drawImage(
                imageObject,
                0f,
                0f,
                outputPage.mediaBox.width,
                outputPage.mediaBox.height
            )
        }
        appendLinkAnnotations(outputPage, markupState, pageWidth, pageHeight)
        return outputPage
    }

    @Throws(IOException::class)
    private fun appendLinkAnnotations(
        outputPage: PDPage,
        markupState: PageMarkupState?,
        pageWidth: Float,
        pageHeight: Float
    ) {
        val linkOperations = markupState
            ?.operations
            ?.filterIsInstance<MarkupOperation.Link>()
            .orEmpty()
        if (linkOperations.isEmpty()) {
            return
        }

        val annotations = outputPage.annotations
        linkOperations.forEach { operation ->
            createLinkAnnotation(operation, pageWidth, pageHeight)?.let { annotation ->
                annotations.add(annotation)
            }
        }
        outputPage.annotations = annotations
    }

    private fun createLinkAnnotation(
        operation: MarkupOperation.Link,
        pageWidth: Float,
        pageHeight: Float
    ): PDAnnotationLink? {
        val safePageWidth = pageWidth.coerceAtLeast(1f)
        val safePageHeight = pageHeight.coerceAtLeast(1f)
        val text = operation.text.trim()
        if (text.isEmpty() || operation.url.isBlank()) {
            return null
        }

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = (
                min(safePageWidth, safePageHeight) * operation.textSizeRatio
                ).coerceIn(18f, 84f)
            typeface = if (operation.isBold) {
                Typeface.DEFAULT_BOLD
            } else {
                Typeface.DEFAULT
            }
            isFakeBoldText = operation.isBold
        }
        val measuredWidth = textPaint.measureText(text).coerceAtLeast(18f)
        val fontMetrics = textPaint.fontMetrics
        val baselineX = operation.xRatio * safePageWidth
        val baselineYFromTop = operation.yRatio * safePageHeight
        val topFromTop = baselineYFromTop + fontMetrics.ascent
        val bottomFromTop = baselineYFromTop + fontMetrics.descent
        val padding = min(safePageWidth, safePageHeight) * 0.01f
        val left = (baselineX - padding).coerceIn(0f, safePageWidth)
        val right = (baselineX + measuredWidth + padding).coerceIn(left, safePageWidth)
        val top = (safePageHeight - bottomFromTop - padding).coerceIn(0f, safePageHeight)
        val bottom = (safePageHeight - topFromTop + padding).coerceIn(top, safePageHeight)
        if (right - left <= 1f || bottom - top <= 1f) {
            return null
        }

        return PDAnnotationLink().apply {
            rectangle = PDRectangle(left, top, right - left, bottom - top)
            action = PDActionURI().also { action ->
                action.uri = operation.url
            }
            highlightMode = PDAnnotationLink.HIGHLIGHT_MODE_NONE
            borderStyle = PDBorderStyleDictionary().apply {
                width = 0f
            }
        }
    }

    private fun renderExportBitmap(page: PdfRenderer.Page): Bitmap {
        val currentMax = max(page.width, page.height).toFloat().coerceAtLeast(1f)
        val renderScale = (EXPORT_RENDER_MAX_DIMENSION / currentMax).coerceIn(1f, 2.2f)
        val width = (page.width * renderScale).roundToInt().coerceAtLeast(1)
        val height = (page.height * renderScale).roundToInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val matrix = Matrix().apply {
            postScale(renderScale, renderScale)
        }
        page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        return bitmap
    }

    private fun compressBitmapToJpeg(bitmap: Bitmap, quality: Int): ByteArray {
        val outputStream = ByteArrayOutputStream()
        if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)) {
            throw IOException(getString(R.string.compress_pdf_bitmap_failed))
        }
        return outputStream.toByteArray()
    }

    private fun drawPageNumber(
        canvas: Canvas,
        pageNumber: Int,
        totalPages: Int,
        width: Float,
        height: Float
    ) {
        val label = "$pageNumber / $totalPages"
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF33415C.toInt()
            textSize = min(width, height).times(0.026f).coerceIn(22f, 42f)
            textAlign = android.graphics.Paint.Align.RIGHT
            isFakeBoldText = true
        }
        canvas.drawText(
            label,
            width - 28f,
            height - 26f,
            paint
        )
    }

    private fun buildEditedPdfFileName(pdfName: String): String {
        val baseName = pdfName
            .replace(Regex("\\.pdf$", RegexOption.IGNORE_CASE), "")
            .trim()
            .ifBlank { "document" }
        return "${baseName}_edited.pdf"
    }

    private fun buildPreviewPdfFile(pdfName: String): File {
        val baseName = pdfName
            .replace(Regex("\\.pdf$", RegexOption.IGNORE_CASE), "")
            .trim()
            .ifBlank { "document" }
            .replace(Regex("[^A-Za-z0-9._-]+"), "_")
        return File(File(filesDir, PREVIEW_DIRECTORY), "${baseName}_preview.pdf")
    }

    private fun closeCurrentPdf() {
        runCatching { renderer?.close() }
        runCatching { fileDescriptor?.close() }
        renderer = null
        fileDescriptor = null
        pageBitmapCache.evictAll()
    }

    private fun setLoading(isLoading: Boolean) {
        binding.editLoadingGroup.isVisible = isLoading && pageCount == 0
        binding.editPreviousPageButton.isEnabled = !isLoading && currentPageIndex > 0
        binding.editNextPageButton.isEnabled = !isLoading && currentPageIndex < pageCount - 1
        binding.editJumpToPageButton.isEnabled = !isLoading && pageCount > 0
        binding.editPageSeekBar.isEnabled = !isLoading && pageCount > 1
        binding.moveToolButton.isEnabled = !isLoading && !selectionOnlyMode
        binding.drawToolButton.isEnabled = !isLoading && !selectionOnlyMode
        binding.highlightToolButton.isEnabled = !isLoading && !selectionOnlyMode
        binding.addTextButton.isEnabled = !isLoading && !selectionOnlyMode
        binding.addImageButton.isEnabled = !isLoading && !selectionOnlyMode
        binding.moreToolsButton.isEnabled = !isLoading && !selectionOnlyMode
        binding.deletePageButton.isEnabled = !isLoading
        binding.selectedSmallerButton.isEnabled =
            !isLoading && !selectionOnlyMode && binding.editOverlay.hasSelectedResizableOperation()
        binding.selectedLargerButton.isEnabled =
            !isLoading && !selectionOnlyMode && binding.editOverlay.hasSelectedResizableOperation()
        binding.toggleBoldButton.isEnabled =
            !isLoading && !selectionOnlyMode && binding.editOverlay.hasSelectedTextOperation()
        binding.undoEditButton.isEnabled =
            !isLoading && !selectionOnlyMode && currentMarkupState().operations.isNotEmpty()
        binding.clearPageButton.isEnabled =
            !isLoading && !selectionOnlyMode && currentMarkupState().operations.isNotEmpty()
        binding.pageNumbersChip.isEnabled = !isLoading && !selectionOnlyMode
        binding.previewEditedPdfButton.isEnabled = !isLoading && !selectionOnlyMode && hasEditableOutput()
        binding.saveEditedPdfButton.isEnabled = !isLoading && hasEditableOutput()
        binding.editOverlay.isEnabled = !isLoading && !selectionOnlyMode
    }

    private fun queryDisplayName(uri: Uri): String? {
        return LocalPdfStore.queryDisplayName(this, uri)
    }

    private fun takeReadPermission(uri: Uri) {
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
    }

    private fun takeDocumentPermission(uri: Uri) {
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
    }

    private fun persistCurrentDraft() {
        if (selectionOnlyMode || !hasUnsavedDraftChanges || pageCount <= 0) {
            return
        }

        val resumeUri = requestedPdfUri ?: return
        val sourceUri = pdfUri ?: resumeUri
        val hasContent = hasAnyMarkup() ||
            binding.pageNumbersChip.isChecked ||
            deletedPages.isNotEmpty() ||
            insertedPages.isNotEmpty()
        if (!hasContent) {
            VisualEditProjectStore.clearDraft(this, resumeUri)
            hasUnsavedDraftChanges = false
            return
        }

        val sourceDisplayName = queryDisplayName(sourceUri).orEmpty().ifBlank {
            pdfName
        }
        VisualEditProjectStore.saveDraft(
            context = this,
            resumeUri = resumeUri,
            sourceUri = sourceUri,
            resumeDisplayName = pdfName,
            sourceDisplayName = sourceDisplayName,
            pageCount = pageCount,
            pageNumbersEnabled = binding.pageNumbersChip.isChecked,
            deletedPages = deletedPages.toSet(),
            insertedPages = insertedPages.toList(),
            pageOrderTokens = currentPageOrderTokens(),
            pageMarkups = pageMarkups.toMap()
        )
        hasUnsavedDraftChanges = false
    }

    private fun showMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private inner class ReorderPagesAdapter :
        RecyclerView.Adapter<ReorderPagesAdapter.ReorderPageViewHolder>() {

        private val entries = mutableListOf<EditorPageEntry>()

        fun submitEntries(newEntries: List<EditorPageEntry>) {
            entries.clear()
            entries.addAll(newEntries)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ReorderPageViewHolder {
            val binding = ItemReorderPageBinding.inflate(layoutInflater, parent, false)
            return ReorderPageViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ReorderPageViewHolder, position: Int) {
            holder.bind(entries[position], position)
        }

        override fun getItemCount(): Int = entries.size

        inner class ReorderPageViewHolder(
            private val binding: ItemReorderPageBinding
        ) : RecyclerView.ViewHolder(binding.root) {

            fun bind(entry: EditorPageEntry, position: Int) {
                binding.reorderPageNumber.text = (position + 1).toString()
                when (entry) {
                    is EditorPageEntry.Original -> {
                        binding.reorderPageTitle.text = getString(
                            R.string.viewer_page_short,
                            position + 1
                        )
                        binding.reorderPageMeta.text = getString(
                            R.string.visual_edit_reorder_page_meta,
                            entry.originalIndex + 1
                        )
                    }

                    is EditorPageEntry.Inserted -> {
                        binding.reorderPageTitle.text = getString(
                            R.string.viewer_page_short,
                            position + 1
                        )
                        binding.reorderPageMeta.text = entry.page.sourceName.ifBlank {
                            getString(R.string.visual_edit_reorder_page_added)
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val EXTRA_RESULT_REMOVED_PAGES = "extra_result_removed_pages"
        private const val EXTRA_PDF_URI = "extra_pdf_uri"
        private const val EXTRA_PDF_NAME = "extra_pdf_name"
        private const val EXTRA_SELECTION_ONLY = "extra_selection_only"
        private const val EXTRA_INITIAL_REMOVED_PAGES = "extra_initial_removed_pages"
        private const val PAGE_RENDER_MAX_DIMENSION = 2200f
        private const val EXPORT_RENDER_MAX_DIMENSION = 1800f
        private const val EXPORT_JPEG_QUALITY = 90
        private const val PREVIEW_DIRECTORY = "visual_edit_previews"
        private const val EDITOR_PAGE_CACHE_SIZE_KB = 14 * 1024
        private const val EDITOR_RENDER_QUALITY_MULTIPLIER = 1.35f
        private const val EDITOR_MAX_RENDER_SCALE = 2.4f
        private const val MIN_CANVAS_ZOOM = 0.35f
        private const val MAX_CANVAS_ZOOM = 4f
        private const val CANVAS_SCALE_EPSILON = 0.01f
        private const val CANVAS_BUTTON_ZOOM_STEP = 1.2f

        fun createIntent(
            context: android.content.Context,
            pdfUri: Uri,
            pdfName: String
        ): Intent {
            return Intent(context, PdfEditActivity::class.java).apply {
                putExtra(EXTRA_PDF_URI, pdfUri.toString())
                putExtra(EXTRA_PDF_NAME, pdfName)
            }
        }

        fun createSelectionIntent(
            context: android.content.Context,
            pdfUri: Uri,
            pdfName: String,
            initiallyRemovedPages: Set<Int>
        ): Intent {
            return createIntent(
                context = context,
                pdfUri = pdfUri,
                pdfName = pdfName
            ).apply {
                putExtra(EXTRA_SELECTION_ONLY, true)
                putIntegerArrayListExtra(
                    EXTRA_INITIAL_REMOVED_PAGES,
                    ArrayList(initiallyRemovedPages.filter { it > 0 }.sorted())
                )
            }
        }
    }
}
