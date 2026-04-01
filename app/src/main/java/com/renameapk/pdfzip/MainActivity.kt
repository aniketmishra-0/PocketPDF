package com.renameapk.pdfzip

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.text.format.DateUtils
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.KeyEvent
import android.view.View
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.renameapk.pdfzip.databinding.ActivityMainBinding
import com.renameapk.pdfzip.databinding.DialogCropPageBinding
import com.renameapk.pdfzip.databinding.DialogDuplicatePageBinding
import com.renameapk.pdfzip.databinding.DialogSplitPdfBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

class MainActivity : AppCompatActivity() {

    private enum class ImageFormat(
        val extension: String,
        val compressFormat: Bitmap.CompressFormat,
        val supportsQuality: Boolean
    ) {
        PNG("png", Bitmap.CompressFormat.PNG, false),
        JPG("jpg", Bitmap.CompressFormat.JPEG, true),
        JPEG("jpeg", Bitmap.CompressFormat.JPEG, true);

        companion object {
            fun fromValue(value: String): ImageFormat? {
                return values().firstOrNull { it.name.equals(value.trim(), ignoreCase = true) }
            }
        }
    }

    private enum class FillMode {
        FIT,
        STRETCH
    }

    private enum class ToolPanelMode {
        EDIT,
        ZIP,
        COMPRESS,
        PREFLIGHT
    }

    private enum class ZipExportPreset {
        FAST,
        BALANCED,
        BEST
    }

    private enum class PageOrientation {
        PORTRAIT,
        LANDSCAPE
    }

    private enum class PagePreset(val label: String, val widthMm: Double, val heightMm: Double) {
        A4("A4", 210.0, 297.0),
        A3("A3", 297.0, 420.0),
        LETTER("Letter", 216.0, 279.0),
        LEGAL("Legal", 216.0, 356.0),
        A5("A5", 148.0, 210.0),
        SQUARE("Square", 216.0, 216.0);
    }

    private enum class ResizeMode {
        AUTO,
        A4,
        A3,
        LETTER,
        LEGAL,
        A5,
        SQUARE,
        CUSTOM
    }

    private data class FilterOptions(
        val skipFromStart: Int = 0,
        val skipFromEnd: Int = 0,
        val removedPages: Set<Int> = emptySet()
    )

    private data class ExportOptions(
        val filterOptions: FilterOptions = FilterOptions(),
        val imageFormat: ImageFormat = ImageFormat.JPG,
        val imageQuality: Int = DEFAULT_JPEG_QUALITY,
        val pdfCompressionQuality: Int = DEFAULT_PDF_COMPRESSION_QUALITY,
        val resizeMode: ResizeMode = ResizeMode.AUTO,
        val customWidthMm: Double? = null,
        val customHeightMm: Double? = null,
        val fillMode: FillMode = FillMode.FIT,
        val resizeSkippedPages: Set<Int> = emptySet()
    )

    private data class ZipRenderProfile(
        val preferredScale: Float,
        val maxDimension: Float,
        val minimumScale: Float,
        val bitmapConfig: Bitmap.Config
    )

    private data class EditOptions(
        val filterOptions: FilterOptions = FilterOptions(),
        val pageSequence: List<Int> = emptyList(),
        val rotatePages: Set<Int> = emptySet(),
        val rotationDegrees: Int = 90,
        val duplicatePages: Set<Int> = emptySet(),
        val stampText: String = "",
        val stampPages: Set<Int> = emptySet(),
        val addPageNumbers: Boolean = false
    )

    private data class CropOptions(
        val pageSelection: Set<Int> = emptySet(),
        val leftPercent: Double = 0.0,
        val topPercent: Double = 0.0,
        val rightPercent: Double = 0.0,
        val bottomPercent: Double = 0.0
    )

    private data class DuplicateOptions(
        val pageSelection: Set<Int>
    )

    private data class SplitOptions(
        val pageGroups: List<List<Int>>
    )

    private data class PageImageExportOptions(
        val pageSequence: List<Int>,
        val imageFormat: ImageFormat = ImageFormat.PNG
    )

    private data class PageMetrics(
        val pageNumber: Int,
        val widthPoints: Int,
        val heightPoints: Int,
        val widthMm: Double,
        val heightMm: Double,
        val widthPixels: Int,
        val heightPixels: Int,
        val formatLabel: String,
        val orientation: PageOrientation
    )

    private data class PageSizeSpec(
        val widthPoints: Int,
        val heightPoints: Int,
        val label: String
    ) {
        fun orientedLike(orientation: PageOrientation): PageSizeSpec {
            val shouldBeLandscape = orientation == PageOrientation.LANDSCAPE
            val isLandscape = widthPoints > heightPoints
            return if (shouldBeLandscape == isLandscape) {
                this
            } else {
                copy(widthPoints = heightPoints, heightPoints = widthPoints)
            }
        }
    }

    private data class PreflightReport(
        val pageMetrics: List<PageMetrics>,
        val uniform: Boolean,
        val uniqueSizeCount: Int,
        val majorityPage: PageMetrics?,
        val sizeGroups: List<PreflightSizeGroup>,
        val differentPageCount: Int
    )

    private data class PreflightSizeGroup(
        val samplePage: PageMetrics,
        val pageCount: Int
    )

    private data class CompressionResult(
        val pageCount: Int,
        val originalBytes: Long?,
        val compressedBytes: Long?
    )

    private data class ZipEstimate(
        val minBytes: Long,
        val maxBytes: Long,
        val estimatedSeconds: Int
    )

    private data class ImagePlacement(
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float
    )

    private lateinit var binding: ActivityMainBinding

    private var selectedPdfUri: Uri? = null
    private var selectedPdfName: String? = null
    private var pendingExportOptions: ExportOptions? = null
    private var pendingCropOptions: CropOptions? = null
    private var pendingDuplicateOptions: DuplicateOptions? = null
    private var pendingSplitOptions: SplitOptions? = null
    private var pendingPageImageExportOptions: PageImageExportOptions? = null
    private var pendingMergeUris: List<Uri> = emptyList()
    private var pendingBatchCompressionUris: List<Uri> = emptyList()
    private var allPageMetrics: List<PageMetrics> = emptyList()
    private var displayedPreflightReport: PreflightReport? = null
    private var activeToolPanelMode: ToolPanelMode? = null
    private var preflightRequestSerial = 0
    private var activePreflightUriString: String? = null
    private var recentDocuments: List<ReaderLibraryStore.RecentDocument> = emptyList()
    private var resumeEditItems: List<VisualEditProjectStore.ResumeItem> = emptyList()
    private var lastBackPressedAt: Long = 0L
    private var homeContentBasePaddingLeft = 0
    private var homeContentBasePaddingTop = 0
    private var homeContentBasePaddingRight = 0
    private var homeContentBasePaddingBottom = 0
    private var isApplyingZipPreset = false

    private val pickPdfLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) {
                return@registerForActivityResult
            }

            val resolvedPdfName = queryDisplayName(uri) ?: getString(R.string.fallback_pdf_name)
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            setLoading(true, getString(R.string.preparing_local_pdf))
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        LocalPdfStore.prepareForRead(
                            context = this@MainActivity,
                            sourceUri = uri,
                            preferredDisplayName = resolvedPdfName,
                            refreshExisting = true
                        )
                    }
                }

                setLoading(false)
                result.onSuccess { localPdf ->
                    selectedPdfUri = localPdf.uri
                    selectedPdfName = resolvedPdfName.ifBlank { localPdf.displayName }
                    pendingExportOptions = null
                    allPageMetrics = emptyList()
                    displayedPreflightReport = null
                    updateSelectedPdfState()
                    hideToolPanel()
                    analyzePreflightAsync(localPdf.uri)
                    launchPdfReader(
                        pdfUri = localPdf.uri,
                        pdfName = selectedPdfName ?: getString(R.string.fallback_pdf_name),
                        startInReadMode = true
                    )
                }.onFailure { error ->
                    binding.statusText.text = getString(
                        R.string.prepare_local_pdf_failed,
                        error.message ?: getString(R.string.unknown_error)
                    )
                    showMessage(
                        getString(
                            R.string.prepare_local_pdf_failed,
                            error.message ?: getString(R.string.unknown_error)
                        )
                    )
                }
            }
        }

    private val createZipLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            if (uri == null) {
                pendingExportOptions = null
                showMessage(getString(R.string.export_cancelled))
                return@registerForActivityResult
            }

            val inputUri = selectedPdfUri
            val exportOptions = pendingExportOptions ?: ExportOptions()
            if (inputUri == null) {
                pendingExportOptions = null
                showMessage(getString(R.string.pick_pdf_first))
                return@registerForActivityResult
            }

            exportPdfToZip(inputUri, uri, exportOptions)
        }

    private val createCompressedPdfLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
            if (uri == null) {
                pendingExportOptions = null
                showMessage(getString(R.string.compression_cancelled))
                return@registerForActivityResult
            }

            val inputUri = selectedPdfUri
            val exportOptions = pendingExportOptions ?: ExportOptions()
            if (inputUri == null) {
                pendingExportOptions = null
                showMessage(getString(R.string.pick_pdf_first))
                return@registerForActivityResult
            }

            compressPdfOffline(inputUri, uri, exportOptions)
        }

    private val createEditedPdfLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
            if (uri == null) {
                showMessage(getString(R.string.edit_cancelled))
                return@registerForActivityResult
            }

            val inputUri = selectedPdfUri
            if (inputUri == null) {
                showMessage(getString(R.string.pick_pdf_first))
                return@registerForActivityResult
            }

            val editOptions = runCatching { readEditOptions() }
                .getOrElse { error ->
                    showMessage(error.message ?: getString(R.string.invalid_filters))
                    return@registerForActivityResult
            }
            editPdfOffline(inputUri, uri, editOptions)
        }

    private val createCroppedPdfLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
            if (uri == null) {
                pendingCropOptions = null
                showMessage(getString(R.string.crop_cancelled))
                return@registerForActivityResult
            }

            val inputUri = selectedPdfUri
            val cropOptions = pendingCropOptions
            if (inputUri == null || cropOptions == null) {
                pendingCropOptions = null
                showMessage(getString(R.string.pick_pdf_first))
                return@registerForActivityResult
            }

            cropPdfOffline(inputUri, uri, cropOptions)
        }

    private val createPngPageImageLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
            if (uri == null) {
                pendingPageImageExportOptions = null
                showMessage(getString(R.string.page_image_export_cancelled))
                return@registerForActivityResult
            }

            val inputUri = selectedPdfUri
            val exportOptions = pendingPageImageExportOptions
            if (inputUri == null || exportOptions == null || exportOptions.pageSequence.size != 1) {
                pendingPageImageExportOptions = null
                showMessage(getString(R.string.pick_pdf_first))
                return@registerForActivityResult
            }

            exportSinglePageAsImageOffline(inputUri, uri, exportOptions)
        }

    private val createJpgPageImageLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("image/jpeg")) { uri ->
            if (uri == null) {
                pendingPageImageExportOptions = null
                showMessage(getString(R.string.page_image_export_cancelled))
                return@registerForActivityResult
            }

            val inputUri = selectedPdfUri
            val exportOptions = pendingPageImageExportOptions
            if (inputUri == null || exportOptions == null || exportOptions.pageSequence.size != 1) {
                pendingPageImageExportOptions = null
                showMessage(getString(R.string.pick_pdf_first))
                return@registerForActivityResult
            }

            exportSinglePageAsImageOffline(inputUri, uri, exportOptions)
        }

    private val createPageImagesZipLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            if (uri == null) {
                pendingPageImageExportOptions = null
                showMessage(getString(R.string.page_image_export_cancelled))
                return@registerForActivityResult
            }

            val inputUri = selectedPdfUri
            val exportOptions = pendingPageImageExportOptions
            if (inputUri == null || exportOptions == null || exportOptions.pageSequence.isEmpty()) {
                pendingPageImageExportOptions = null
                showMessage(getString(R.string.pick_pdf_first))
                return@registerForActivityResult
            }

            exportPageImagesZipOffline(inputUri, uri, exportOptions)
        }

    private val createDuplicatedPdfLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
            if (uri == null) {
                pendingDuplicateOptions = null
                showMessage(getString(R.string.duplicate_cancelled))
                return@registerForActivityResult
            }

            val inputUri = selectedPdfUri
            val duplicateOptions = pendingDuplicateOptions
            if (inputUri == null || duplicateOptions == null) {
                pendingDuplicateOptions = null
                showMessage(getString(R.string.pick_pdf_first))
                return@registerForActivityResult
            }

            duplicatePdfOffline(inputUri, uri, duplicateOptions)
        }

    private val pickMergePdfsLauncher =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isEmpty()) {
                return@registerForActivityResult
            }
            uris.forEach(::takePersistableReadPermission)
            if (uris.size < 2) {
                showMessage(getString(R.string.merge_pick_two_pdfs))
                return@registerForActivityResult
            }
            pendingMergeUris = uris
            createMergedPdfLauncher.launch(buildMergedPdfFileName(uris))
        }

    private val createMergedPdfLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
            if (uri == null) {
                pendingMergeUris = emptyList()
                showMessage(getString(R.string.merge_cancelled))
                return@registerForActivityResult
            }

            val inputUris = pendingMergeUris
            if (inputUris.size < 2) {
                pendingMergeUris = emptyList()
                showMessage(getString(R.string.merge_pick_two_pdfs))
                return@registerForActivityResult
            }

            mergePdfsOffline(inputUris, uri)
        }

    private val createSplitZipLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            if (uri == null) {
                pendingSplitOptions = null
                showMessage(getString(R.string.split_cancelled))
                return@registerForActivityResult
            }

            val inputUri = selectedPdfUri
            val splitOptions = pendingSplitOptions
            if (inputUri == null || splitOptions == null) {
                pendingSplitOptions = null
                showMessage(getString(R.string.pick_pdf_first))
                return@registerForActivityResult
            }

            splitPdfOffline(inputUri, uri, splitOptions)
        }

    private val pickBatchPdfsLauncher =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isEmpty()) {
                return@registerForActivityResult
            }
            uris.forEach(::takePersistableReadPermission)
            pendingBatchCompressionUris = uris
            createBatchZipLauncher.launch(buildBatchCompressedZipFileName())
        }

    private val createBatchZipLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            if (uri == null) {
                pendingBatchCompressionUris = emptyList()
                showMessage(getString(R.string.batch_compress_cancelled))
                return@registerForActivityResult
            }

            val inputUris = pendingBatchCompressionUris
            if (inputUris.isEmpty()) {
                pendingBatchCompressionUris = emptyList()
                showMessage(getString(R.string.batch_compress_pick_pdf))
                return@registerForActivityResult
            }

            batchCompressPdfsOffline(inputUris, uri)
        }

    private val visualPagePickerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != Activity.RESULT_OK) {
                return@registerForActivityResult
            }

            val removedPages = result.data
                ?.getIntegerArrayListExtra(PdfEditActivity.EXTRA_RESULT_REMOVED_PAGES)
                .orEmpty()
                .filter { it > 0 }
                .toSet()
            binding.removePagesInput.setText(formatPageSelection(removedPages))
            refreshPreflightPreview()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppThemeStore.applySavedTheme(this)
        super.onCreate(savedInstanceState)
        PDFBoxResourceLoader.init(applicationContext)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        captureBaseSpacing()
        setupWindowInsets()
        setupFormatSelector()
        setupPreflightControls()
        setupFilterWatchers()
        setupToolPanel()
        setupSettingsShortcut()

        binding.pickPdfButton.setOnClickListener {
            pickPdfLauncher.launch(arrayOf("application/pdf"))
        }

        binding.openPdfButton.setOnClickListener {
            val currentUri = selectedPdfUri ?: return@setOnClickListener
            launchPdfEditor(
                pdfUri = currentUri,
                pdfName = selectedPdfName ?: getString(R.string.fallback_pdf_name)
            )
        }
        binding.selectedReadButton.setOnClickListener {
            val currentUri = selectedPdfUri ?: return@setOnClickListener
            launchPdfReader(
                pdfUri = currentUri,
                pdfName = selectedPdfName ?: getString(R.string.fallback_pdf_name),
                startInReadMode = true
            )
        }
        binding.selectedEditButton.setOnClickListener {
            val currentUri = selectedPdfUri ?: return@setOnClickListener
            launchPdfEditor(
                pdfUri = currentUri,
                pdfName = selectedPdfName ?: getString(R.string.fallback_pdf_name)
            )
        }

        binding.selectedFileCard.setOnClickListener {
            val currentUri = selectedPdfUri ?: return@setOnClickListener
            launchPdfReader(
                pdfUri = currentUri,
                pdfName = selectedPdfName ?: getString(R.string.fallback_pdf_name),
                startInReadMode = true
            )
        }

        binding.exportZipButton.setOnClickListener {
            showToolPanel(ToolPanelMode.ZIP)
        }

        binding.compressPdfButton.setOnClickListener {
            showToolPanel(ToolPanelMode.COMPRESS)
        }

        binding.preflightButton.setOnClickListener {
            showToolPanel(ToolPanelMode.PREFLIGHT)
        }

        binding.cropPageButton.setOnClickListener {
            showCropPageDialog()
        }

        binding.duplicatePageButton.setOnClickListener {
            showDuplicatePageDialog()
        }

        binding.mergePdfButton.setOnClickListener {
            pickMergePdfsLauncher.launch(arrayOf("application/pdf"))
        }

        binding.splitPdfButton.setOnClickListener {
            showSplitPdfDialog()
        }

        binding.batchCompressButton.setOnClickListener {
            pickBatchPdfsLauncher.launch(arrayOf("application/pdf"))
        }

        binding.openVisualPageEditorButton.setOnClickListener {
            val currentUri = selectedPdfUri ?: return@setOnClickListener
            launchVisualPagePicker(
                pdfUri = currentUri,
                pdfName = selectedPdfName ?: getString(R.string.fallback_pdf_name)
            )
        }

        updateSelectedPdfState()
        syncSelectedDocumentFromLibrary()
        handleLaunchIntent(intent)
        refreshDashboardCollections()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunchIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        syncSelectedDocumentFromLibrary()
        refreshDashboardCollections()
    }

    private fun captureBaseSpacing() {
        homeContentBasePaddingLeft = binding.homeContentContainer.paddingLeft
        homeContentBasePaddingTop = binding.homeContentContainer.paddingTop
        homeContentBasePaddingRight = binding.homeContentContainer.paddingRight
        homeContentBasePaddingBottom = binding.homeContentContainer.paddingBottom
    }

    private fun setupWindowInsets() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.homeContentContainer.updatePadding(
                left = homeContentBasePaddingLeft + systemBars.left,
                top = homeContentBasePaddingTop + systemBars.top,
                right = homeContentBasePaddingRight + systemBars.right,
                bottom = homeContentBasePaddingBottom + systemBars.bottom
            )
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    private fun setupSettingsShortcut() {
        binding.homeBrandBadge.setOnClickListener {
            showSettingsDialog()
        }
    }

    private fun showSettingsDialog() {
        val themeModes = AppThemeStore.ThemeMode.values()
        val selectedIndex = themeModes.indexOf(AppThemeStore.getThemeMode(this)).coerceAtLeast(0)
        val labels = arrayOf(
            getString(R.string.theme_mode_system),
            getString(R.string.theme_mode_light),
            getString(R.string.theme_mode_dark)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.home_settings_title)
            .setSingleChoiceItems(labels, selectedIndex) { dialog, which ->
                val chosenMode = themeModes.getOrNull(which) ?: AppThemeStore.ThemeMode.SYSTEM
                AppThemeStore.setThemeMode(this, chosenMode)
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) {
                handleHomeBackPressed()
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun setupToolPanel() {
        binding.toolPanelCloseButton.setOnClickListener {
            hideToolPanel()
        }
        binding.toolPrimaryActionButton.setOnClickListener {
            launchToolAction()
        }
        onBackPressedDispatcher.addCallback(this) {
            handleHomeBackPressed()
        }
    }

    private fun handleHomeBackPressed() {
        if (binding.toolPanel.isVisible) {
            hideToolPanel()
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastBackPressedAt <= BACK_PRESS_EXIT_WINDOW_MS) {
            finish()
            return
        }

        lastBackPressedAt = now
        Toast.makeText(this, R.string.back_press_close_app, Toast.LENGTH_SHORT).show()
    }

    private fun setupFilterWatchers() {
        binding.skipFirstInput.doAfterTextChanged {
            refreshPreflightPreview()
            refreshZipEstimate()
        }
        binding.skipLastInput.doAfterTextChanged {
            refreshPreflightPreview()
            refreshZipEstimate()
        }
        binding.removePagesInput.doAfterTextChanged {
            refreshPreflightPreview()
            refreshZipEstimate()
        }
    }

    private fun setupFormatSelector() {
        val formatNames = ImageFormat.values().map { it.name }
        binding.imageFormatInput.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1, formatNames)
        )
        binding.zipPresetBalancedChip.isChecked = true
        binding.zipPresetChipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            if (isApplyingZipPreset) {
                return@setOnCheckedStateChangeListener
            }
            val checkedId = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            val preset = when (checkedId) {
                R.id.zipPresetFastChip -> ZipExportPreset.FAST
                R.id.zipPresetBestChip -> ZipExportPreset.BEST
                else -> ZipExportPreset.BALANCED
            }
            applyZipPreset(preset)
        }
        binding.imageFormatInput.setText(ImageFormat.JPG.name, false)
        binding.imageFormatInput.setOnClickListener {
            binding.imageFormatInput.showDropDown()
        }
        binding.imageFormatInput.setOnItemClickListener { _, _, _, _ ->
            updateFormatUi(getSelectedImageFormat())
        }
        binding.imageFormatInput.doAfterTextChanged {
            updateFormatUi(ImageFormat.fromValue(it?.toString().orEmpty()) ?: ImageFormat.JPG)
        }
        binding.jpegQualityInput.setText(DEFAULT_JPEG_QUALITY.toString())
        updateFormatUi(ImageFormat.JPG)
        binding.jpegQualityInput.doAfterTextChanged {
            syncZipPresetSelection()
            refreshZipEstimate()
        }
        refreshZipEstimate()
    }

    private fun setupPreflightControls() {
        binding.autoResizeChip.isChecked = true
        binding.compressBalancedChip.isChecked = true
        binding.rotate90Chip.isChecked = true
        binding.fillModeToggleGroup.check(R.id.fitModeButton)
        binding.customWidthInput.doAfterTextChanged {
            if (!it.isNullOrBlank()) {
                binding.customResizeChip.isChecked = true
            }
        }
        binding.customHeightInput.doAfterTextChanged {
            if (!it.isNullOrBlank()) {
                binding.customResizeChip.isChecked = true
            }
        }
    }

    private fun syncSelectedDocumentFromLibrary() {
        if (selectedPdfUri != null) {
            updateSelectedPdfState()
            return
        }

        val latestDocument = ReaderLibraryStore.getRecentDocuments(this).firstOrNull() ?: run {
            updateSelectedPdfState()
            return
        }
        val restoredUri = Uri.parse(latestDocument.uriString)
        selectedPdfUri = restoredUri
        selectedPdfName = latestDocument.displayName
        updateSelectedPdfState()
        if (allPageMetrics.isEmpty()) {
            analyzePreflightAsync(restoredUri)
        }
    }

    private fun handleLaunchIntent(intent: Intent) {
        val launchedUri = intent.getStringExtra(EXTRA_SELECTED_PDF_URI)?.let(Uri::parse)
        val launchedName = intent.getStringExtra(EXTRA_SELECTED_PDF_NAME)
        val requestedToolMode = intent.getStringExtra(EXTRA_OPEN_TOOL_MODE)

        if (launchedUri != null) {
            val didChangeDocument = launchedUri != selectedPdfUri
            selectedPdfUri = launchedUri
            selectedPdfName = LocalPdfStore.presentableDisplayName(
                launchedName
                ?: queryDisplayName(launchedUri)
                ?: selectedPdfName
                ?: getString(R.string.fallback_pdf_name)
            )
            runCatching {
                contentResolver.takePersistableUriPermission(
                    launchedUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            if (didChangeDocument) {
                pendingExportOptions = null
                allPageMetrics = emptyList()
                displayedPreflightReport = null
            }
            updateSelectedPdfState()
            analyzePreflightAsync(launchedUri)
        }

        val mode = requestedToolMode?.let { modeName ->
            runCatching { ToolPanelMode.valueOf(modeName) }.getOrNull()
        } ?: return

        binding.root.post {
            showToolPanel(mode)
        }
    }

    private fun takePersistableReadPermission(uri: Uri) {
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
    }

    private fun showCropPageDialog() {
        if (selectedPdfUri == null) {
            showMessage(getString(R.string.pick_pdf_first))
            return
        }

        val dialogBinding = DialogCropPageBinding.inflate(layoutInflater)
        val pageCount = currentSelectedDocumentPageCount()
        dialogBinding.cropPageCountBadge.isVisible = pageCount > 0
        if (pageCount > 0) {
            dialogBinding.cropPageCountBadge.text =
                getString(R.string.viewer_jump_pages_badge, pageCount)
        }
        val syncActionState = {
            syncCropDialogAction(dialogBinding)
        }
        dialogBinding.cropPagesInput.doAfterTextChanged { syncActionState() }
        dialogBinding.cropLeftInput.doAfterTextChanged { syncActionState() }
        dialogBinding.cropTopInput.doAfterTextChanged { syncActionState() }
        dialogBinding.cropRightInput.doAfterTextChanged { syncActionState() }
        dialogBinding.cropBottomInput.doAfterTextChanged { syncActionState() }
        syncCropDialogAction(dialogBinding)

        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogBinding.root)
            .create()

        dialogBinding.cropPageCancelButton.setOnClickListener {
            dialog.dismiss()
        }
        dialogBinding.cropPageConfirmButton.setOnClickListener {
            if (shouldExportSelectedPagesAsImages(dialogBinding)) {
                val exportOptions = runCatching { readPageImageExportOptions(dialogBinding) }
                    .getOrElse { error ->
                        showMessage(error.message ?: getString(R.string.invalid_filters))
                        return@setOnClickListener
                    }
                pendingPageImageExportOptions = exportOptions
                dialog.dismiss()
                if (exportOptions.pageSequence.size == 1) {
                    val fileName = buildPageImageFileName(
                        pdfName = selectedPdfName,
                        pageNumber = exportOptions.pageSequence.first(),
                        imageFormat = exportOptions.imageFormat
                    )
                    when (exportOptions.imageFormat) {
                        ImageFormat.PNG -> createPngPageImageLauncher.launch(fileName)
                        ImageFormat.JPG, ImageFormat.JPEG -> createJpgPageImageLauncher.launch(fileName)
                    }
                } else {
                    createPageImagesZipLauncher.launch(
                        buildPageImagesZipFileName(
                            pdfName = selectedPdfName,
                            imageFormat = exportOptions.imageFormat
                        )
                    )
                }
                return@setOnClickListener
            }
            val cropOptions = runCatching { readCropOptions(dialogBinding) }
                .getOrElse { error ->
                    showMessage(error.message ?: getString(R.string.invalid_filters))
                    return@setOnClickListener
                }
            pendingCropOptions = cropOptions
            dialog.dismiss()
            createCroppedPdfLauncher.launch(buildCroppedPdfFileName(selectedPdfName))
        }
        dialog.show()
    }

    private fun showDuplicatePageDialog() {
        if (selectedPdfUri == null) {
            showMessage(getString(R.string.pick_pdf_first))
            return
        }

        val dialogBinding = DialogDuplicatePageBinding.inflate(layoutInflater)
        val pageCount = currentSelectedDocumentPageCount()
        dialogBinding.duplicatePageCountBadge.isVisible = pageCount > 0
        if (pageCount > 0) {
            dialogBinding.duplicatePageCountBadge.text =
                getString(R.string.viewer_jump_pages_badge, pageCount)
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogBinding.root)
            .create()

        dialogBinding.duplicatePageCancelButton.setOnClickListener {
            dialog.dismiss()
        }
        dialogBinding.duplicatePageConfirmButton.setOnClickListener {
            val duplicateOptions = runCatching { readDuplicateOptions(dialogBinding) }
                .getOrElse { error ->
                    showMessage(error.message ?: getString(R.string.invalid_filters))
                    return@setOnClickListener
                }
            pendingDuplicateOptions = duplicateOptions
            dialog.dismiss()
            createDuplicatedPdfLauncher.launch(buildDuplicatedPdfFileName(selectedPdfName))
        }
        dialog.show()
    }

    private fun showSplitPdfDialog() {
        if (selectedPdfUri == null) {
            showMessage(getString(R.string.pick_pdf_first))
            return
        }

        val dialogBinding = DialogSplitPdfBinding.inflate(layoutInflater)
        val pageCount = currentSelectedDocumentPageCount()
        dialogBinding.splitPageCountBadge.isVisible = pageCount > 0
        if (pageCount > 0) {
            dialogBinding.splitPageCountBadge.text =
                getString(R.string.viewer_jump_pages_badge, pageCount)
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogBinding.root)
            .create()

        dialogBinding.splitPdfCancelButton.setOnClickListener {
            dialog.dismiss()
        }
        dialogBinding.splitPdfConfirmButton.setOnClickListener {
            val splitOptions = runCatching { readSplitOptions(dialogBinding) }
                .getOrElse { error ->
                    showMessage(error.message ?: getString(R.string.invalid_filters))
                    return@setOnClickListener
                }
            pendingSplitOptions = splitOptions
            dialog.dismiss()
            createSplitZipLauncher.launch(buildSplitZipFileName(selectedPdfName))
        }
        dialog.show()
    }

    private fun syncCropDialogAction(dialogBinding: DialogCropPageBinding) {
        dialogBinding.cropPageConfirmButton.setText(
            when {
                !shouldExportSelectedPagesAsImages(dialogBinding) -> R.string.crop_confirm
                readPageImageSequenceSafely(dialogBinding).size > 1 -> R.string.crop_save_images_zip
                else -> R.string.crop_save_image
            }
        )
    }

    private fun shouldExportSelectedPagesAsImages(dialogBinding: DialogCropPageBinding): Boolean {
        if (hasAnyCropMargin(dialogBinding)) {
            return false
        }
        return readPageImageSequenceSafely(dialogBinding).isNotEmpty()
    }

    private fun hasAnyCropMargin(dialogBinding: DialogCropPageBinding): Boolean {
        return listOf(
            dialogBinding.cropLeftInput.text?.toString().orEmpty(),
            dialogBinding.cropTopInput.text?.toString().orEmpty(),
            dialogBinding.cropRightInput.text?.toString().orEmpty(),
            dialogBinding.cropBottomInput.text?.toString().orEmpty()
        ).any { value ->
            value.trim().toDoubleOrNull()?.let { it > 0.0 } == true
        }
    }

    private fun readPageImageSequenceSafely(dialogBinding: DialogCropPageBinding): List<Int> {
        return runCatching {
            parsePageSequence(dialogBinding.cropPagesInput.text?.toString().orEmpty())
                .distinct()
        }.getOrDefault(emptyList())
    }

    private fun showToolPanel(mode: ToolPanelMode) {
        if (selectedPdfUri == null) {
            showMessage(getString(R.string.pick_pdf_first))
            return
        }

        lastBackPressedAt = 0L
        activeToolPanelMode = mode
        binding.toolPanelTitle.text = when (mode) {
            ToolPanelMode.EDIT -> getString(R.string.tool_panel_edit_title)
            ToolPanelMode.ZIP -> getString(R.string.tool_panel_zip_title)
            ToolPanelMode.COMPRESS -> getString(R.string.tool_panel_compress_title)
            ToolPanelMode.PREFLIGHT -> getString(R.string.tool_panel_preflight_title)
        }
        binding.toolPanelSubtitle.text = when (mode) {
            ToolPanelMode.EDIT -> getString(R.string.tool_panel_edit_subtitle)
            ToolPanelMode.ZIP -> getString(R.string.tool_panel_zip_subtitle)
            ToolPanelMode.COMPRESS -> getString(R.string.tool_panel_compress_subtitle)
            ToolPanelMode.PREFLIGHT -> getString(R.string.tool_panel_preflight_subtitle)
        }
        binding.toolPrimaryActionHelper.text = when (mode) {
            ToolPanelMode.EDIT -> getString(R.string.tool_panel_edit_footer)
            ToolPanelMode.ZIP -> getString(R.string.tool_panel_zip_footer)
            ToolPanelMode.COMPRESS -> getString(R.string.tool_panel_compress_footer)
            ToolPanelMode.PREFLIGHT -> getString(R.string.tool_panel_preflight_footer)
        }
        binding.toolPrimaryActionButton.text = when (mode) {
            ToolPanelMode.EDIT -> getString(R.string.tool_apply_edit)
            ToolPanelMode.ZIP -> getString(R.string.tool_apply_zip)
            ToolPanelMode.COMPRESS -> getString(R.string.tool_apply_compress)
            ToolPanelMode.PREFLIGHT -> getString(R.string.tool_apply_fix_pdf)
        }
        binding.outputNameRecommendedBadge.isVisible = mode == ToolPanelMode.COMPRESS
        binding.compressIntroSection.isVisible = mode == ToolPanelMode.COMPRESS
        binding.compressionProfileSection.isVisible = mode == ToolPanelMode.COMPRESS
        binding.editSection.isVisible = mode == ToolPanelMode.EDIT
        binding.cleanupSection.isVisible = mode != ToolPanelMode.PREFLIGHT
        binding.outputNameSection.isVisible = mode != ToolPanelMode.PREFLIGHT
        binding.formatSection.isVisible = mode == ToolPanelMode.ZIP
        binding.preflightSection.isVisible = mode == ToolPanelMode.PREFLIGHT
        binding.resizeSection.isVisible = mode == ToolPanelMode.PREFLIGHT
        binding.cleanupSectionTitle.text = when (mode) {
            ToolPanelMode.EDIT -> getString(R.string.edit_cleanup_title)
            ToolPanelMode.COMPRESS -> getString(R.string.compress_cleanup_title)
            else -> getString(R.string.filter_title)
        }
        binding.cleanupSectionHelper.text = when (mode) {
            ToolPanelMode.EDIT -> getString(R.string.edit_cleanup_helper)
            ToolPanelMode.COMPRESS -> getString(R.string.compress_cleanup_helper)
            else -> getString(R.string.filter_help)
        }
        binding.outputNameSectionTitle.text = when (mode) {
            ToolPanelMode.EDIT -> getString(R.string.edit_output_title)
            ToolPanelMode.COMPRESS -> getString(R.string.compress_output_title)
            else -> getString(R.string.zip_output_title)
        }
        binding.outputNameSectionHelper.text = when (mode) {
            ToolPanelMode.EDIT -> getString(R.string.edit_output_helper)
            ToolPanelMode.COMPRESS -> getString(R.string.compress_output_helper)
            else -> getString(R.string.zip_output_helper)
        }
        reorderToolSections(mode)
        binding.outputNameInput.setText("")
        binding.toolPanel.isVisible = true
        binding.toolPanelScrollView.post {
            binding.toolPanelScrollView.scrollTo(0, 0)
        }
        if (mode == ToolPanelMode.PREFLIGHT) {
            ensurePreflightMetrics()
        }
        refreshPreflightPreview()
    }

    private fun reorderToolSections(mode: ToolPanelMode) {
        val orderedSections = if (mode == ToolPanelMode.EDIT) {
            listOf(
                binding.editSection,
                binding.cleanupSection,
                binding.outputNameSection,
                binding.compressIntroSection,
                binding.compressionProfileSection,
                binding.preflightSection,
                binding.formatSection,
                binding.resizeSection
            )
        } else if (mode == ToolPanelMode.COMPRESS) {
            listOf(
                binding.compressIntroSection,
                binding.compressionProfileSection,
                binding.editSection,
                binding.outputNameSection,
                binding.cleanupSection,
                binding.preflightSection,
                binding.formatSection,
                binding.resizeSection
            )
        } else if (mode == ToolPanelMode.ZIP) {
            listOf(
                binding.editSection,
                binding.cleanupSection,
                binding.outputNameSection,
                binding.formatSection,
                binding.compressIntroSection,
                binding.compressionProfileSection,
                binding.preflightSection,
                binding.resizeSection
            )
        } else {
            listOf(
                binding.preflightSection,
                binding.resizeSection,
                binding.editSection,
                binding.compressIntroSection,
                binding.compressionProfileSection,
                binding.outputNameSection,
                binding.cleanupSection,
                binding.formatSection
            )
        }
        orderedSections.forEach { section ->
            binding.toolPanelContent.removeView(section)
            binding.toolPanelContent.addView(section)
        }
    }

    private fun hideToolPanel() {
        lastBackPressedAt = 0L
        activeToolPanelMode = null
        binding.toolPanel.isVisible = false
    }

    private fun ensurePreflightMetrics() {
        val currentUri = selectedPdfUri ?: return
        if (allPageMetrics.isNotEmpty()) {
            return
        }
        if (activePreflightUriString == currentUri.toString()) {
            return
        }
        analyzePreflightAsync(currentUri)
    }

    private fun launchToolAction() {
        val mode = activeToolPanelMode ?: return
        val outputBaseName = readOutputBaseName()
        if (mode == ToolPanelMode.EDIT) {
            try {
                readEditOptions()
            } catch (error: IllegalArgumentException) {
                showMessage(error.message ?: getString(R.string.invalid_filters))
                return
            }
            hideToolPanel()
            createEditedPdfLauncher.launch(buildEditedPdfFileName(selectedPdfName, outputBaseName))
            return
        }

        val exportOptions = try {
            readExportOptions(
                requireCompressionSettings = mode == ToolPanelMode.COMPRESS ||
                    mode == ToolPanelMode.PREFLIGHT
            )
        } catch (error: IllegalArgumentException) {
            showMessage(error.message ?: getString(R.string.invalid_filters))
            return
        }
        pendingExportOptions = exportOptions
        hideToolPanel()
        if (mode == ToolPanelMode.ZIP) {
            createZipLauncher.launch(
                buildZipFileName(selectedPdfName, exportOptions.imageFormat, outputBaseName)
            )
        } else if (mode == ToolPanelMode.COMPRESS) {
            createCompressedPdfLauncher.launch(
                buildCompressedPdfFileName(selectedPdfName, outputBaseName)
            )
        } else {
            createCompressedPdfLauncher.launch(buildPreflightFixedPdfFileName(selectedPdfName))
        }
    }

    private fun updateFormatUi(imageFormat: ImageFormat) {
        binding.jpegQualityLayout.isVisible = imageFormat.supportsQuality
        syncZipPresetSelection()
        refreshZipEstimate()
    }

    private fun applyZipPreset(preset: ZipExportPreset) {
        isApplyingZipPreset = true
        try {
            when (preset) {
                ZipExportPreset.FAST -> {
                    binding.imageFormatInput.setText(ImageFormat.JPG.name, false)
                    binding.jpegQualityInput.setText(FAST_JPEG_QUALITY.toString())
                }

                ZipExportPreset.BALANCED -> {
                    binding.imageFormatInput.setText(ImageFormat.JPG.name, false)
                    binding.jpegQualityInput.setText(DEFAULT_JPEG_QUALITY.toString())
                }

                ZipExportPreset.BEST -> {
                    binding.imageFormatInput.setText(ImageFormat.PNG.name, false)
                    if (binding.jpegQualityInput.text.isNullOrBlank()) {
                        binding.jpegQualityInput.setText(BEST_JPEG_QUALITY.toString())
                    }
                }
            }
            syncZipPresetSelection(forcePreset = preset)
            refreshZipEstimate()
        } finally {
            isApplyingZipPreset = false
        }
    }

    private fun syncZipPresetSelection(forcePreset: ZipExportPreset? = null) {
        val preset = forcePreset ?: inferZipPreset(
            imageFormat = ImageFormat.fromValue(binding.imageFormatInput.text?.toString().orEmpty())
                ?: ImageFormat.JPG,
            imageQuality = binding.jpegQualityInput.text?.toString()
                ?.trim()
                ?.toIntOrNull()
                ?: DEFAULT_JPEG_QUALITY
        )
        val chipId = when (preset) {
            ZipExportPreset.FAST -> R.id.zipPresetFastChip
            ZipExportPreset.BALANCED -> R.id.zipPresetBalancedChip
            ZipExportPreset.BEST -> R.id.zipPresetBestChip
        }
        if (binding.zipPresetChipGroup.checkedChipId == chipId) {
            return
        }
        val previousState = isApplyingZipPreset
        isApplyingZipPreset = true
        binding.zipPresetChipGroup.check(chipId)
        isApplyingZipPreset = previousState
    }

    private fun inferZipPreset(
        imageFormat: ImageFormat,
        imageQuality: Int
    ): ZipExportPreset {
        return when {
            imageFormat == ImageFormat.PNG -> ZipExportPreset.BEST
            imageQuality <= FAST_JPEG_QUALITY + 2 -> ZipExportPreset.FAST
            imageQuality >= BEST_JPEG_QUALITY -> ZipExportPreset.BEST
            else -> ZipExportPreset.BALANCED
        }
    }

    private fun refreshZipEstimate() {
        val totalPages = currentSelectedDocumentPageCount()
        val hasPdf = selectedPdfUri != null
        if (!hasPdf || totalPages <= 0) {
            binding.zipEstimateTitleText.text = getString(R.string.zip_estimate_title_default)
            binding.zipEstimateBodyText.text = getString(R.string.zip_estimate_body_default)
            binding.zipEstimateHintText.text = getString(R.string.zip_estimate_hint_default)
            return
        }

        val imageFormat = ImageFormat.fromValue(binding.imageFormatInput.text?.toString().orEmpty())
            ?: ImageFormat.JPG
        val imageQuality = parseImageQualitySafely(imageFormat)
        val sourceSizeBytes = selectedPdfUri?.let(::queryFileSize)
        val selectedPages = resolveZipSelectedPageCount(totalPages)
        val preset = inferZipPreset(imageFormat, imageQuality)

        binding.zipEstimateTitleText.text = if (sourceSizeBytes != null) {
            val estimate = buildZipEstimate(
                sourceSizeBytes = sourceSizeBytes,
                totalPages = totalPages,
                selectedPages = selectedPages,
                imageFormat = imageFormat,
                imageQuality = imageQuality
            )
            getString(
                R.string.zip_estimate_title_range,
                formatShortFileSize(estimate.minBytes),
                formatShortFileSize(estimate.maxBytes)
            )
        } else {
            getString(R.string.zip_estimate_title_pages_only, selectedPages, totalPages)
        }

        val estimatedSeconds = sourceSizeBytes?.let {
            buildZipEstimate(
                sourceSizeBytes = it,
                totalPages = totalPages,
                selectedPages = selectedPages,
                imageFormat = imageFormat,
                imageQuality = imageQuality
            ).estimatedSeconds
        } ?: estimateZipSeconds(
            selectedPages = selectedPages,
            imageFormat = imageFormat,
            imageQuality = imageQuality
        )

        binding.zipEstimateBodyText.text = getString(
            R.string.zip_estimate_body,
            selectedPages,
            totalPages,
            formatDurationEstimate(estimatedSeconds)
        )

        binding.zipEstimateHintText.text = when (preset) {
            ZipExportPreset.FAST -> getString(R.string.zip_estimate_hint_fast)
            ZipExportPreset.BALANCED -> getString(R.string.zip_estimate_hint_balanced)
            ZipExportPreset.BEST -> if (imageFormat == ImageFormat.PNG) {
                getString(R.string.zip_estimate_hint_best_png)
            } else {
                getString(R.string.zip_estimate_hint_best_jpg)
            }
        }
    }

    private fun resolveZipSelectedPageCount(totalPages: Int): Int {
        if (totalPages <= 0) {
            return 0
        }
        return runCatching {
            buildPagesToProcess(totalPages, readFilterOptions()).size
        }.getOrDefault(totalPages)
    }

    private fun currentSelectedDocumentPageCount(): Int {
        if (allPageMetrics.isNotEmpty()) {
            return allPageMetrics.size
        }
        val selectedUriString = selectedPdfUri?.toString()
        return recentDocuments.firstOrNull { it.uriString == selectedUriString }?.pageCount
            ?: resumeEditItems.firstOrNull { it.resumeUriString == selectedUriString }?.pageCount
            ?: 0
    }

    private fun parseImageQualitySafely(imageFormat: ImageFormat): Int {
        return runCatching {
            parseImageQuality(binding.jpegQualityInput.text?.toString().orEmpty(), imageFormat)
        }.getOrDefault(DEFAULT_JPEG_QUALITY)
    }

    private fun buildZipEstimate(
        sourceSizeBytes: Long,
        totalPages: Int,
        selectedPages: Int,
        imageFormat: ImageFormat,
        imageQuality: Int
    ): ZipEstimate {
        val safeTotalPages = totalPages.coerceAtLeast(1)
        val safeSelectedPages = selectedPages.coerceIn(1, safeTotalPages)
        val pageRatio = safeSelectedPages.toDouble() / safeTotalPages.toDouble()
        val densityBoost = when {
            safeSelectedPages >= 220 -> 0.20
            safeSelectedPages >= 120 -> 0.12
            safeSelectedPages >= 60 -> 0.06
            else -> 0.0
        }
        val baseMultiplier = if (imageFormat == ImageFormat.PNG) {
            1.85 + densityBoost + if (sourceSizeBytes >= 80L * 1024L * 1024L) 0.40 else 0.0
        } else {
            val qualityMultiplier = when {
                imageQuality <= FAST_JPEG_QUALITY -> 0.58
                imageQuality <= DEFAULT_JPEG_QUALITY -> 0.78
                imageQuality <= 90 -> 0.95
                else -> 1.12
            }
            qualityMultiplier + densityBoost
        }
        val estimatedMidpoint = sourceSizeBytes * pageRatio * baseMultiplier
        val variability = if (imageFormat == ImageFormat.PNG) 0.24 else 0.18
        val minBytes = (estimatedMidpoint * (1.0 - variability)).roundToLong().coerceAtLeast(1L)
        val maxBytes = (estimatedMidpoint * (1.0 + variability)).roundToLong()
            .coerceAtLeast(minBytes)
        return ZipEstimate(
            minBytes = minBytes,
            maxBytes = maxBytes,
            estimatedSeconds = estimateZipSeconds(
                selectedPages = safeSelectedPages,
                imageFormat = imageFormat,
                imageQuality = imageQuality
            )
        )
    }

    private fun estimateZipSeconds(
        selectedPages: Int,
        imageFormat: ImageFormat,
        imageQuality: Int
    ): Int {
        val perPageSeconds = if (imageFormat == ImageFormat.PNG) {
            1.05
        } else {
            when {
                imageQuality <= FAST_JPEG_QUALITY -> 0.34
                imageQuality <= DEFAULT_JPEG_QUALITY -> 0.46
                imageQuality <= 90 -> 0.58
                else -> 0.72
            }
        }
        return (selectedPages.coerceAtLeast(1) * perPageSeconds).roundToInt().coerceAtLeast(10)
    }

    private fun formatShortFileSize(sizeBytes: Long): String {
        return Formatter.formatShortFileSize(this, sizeBytes)
    }

    private fun formatDurationEstimate(totalSeconds: Int): String {
        val safeSeconds = totalSeconds.coerceAtLeast(0)
        val minutes = safeSeconds / 60
        val seconds = safeSeconds % 60
        return if (minutes <= 0) {
            "${seconds}s"
        } else if (seconds == 0) {
            "${minutes}m"
        } else {
            "${minutes}m ${seconds}s"
        }
    }

    private fun getSelectedImageFormat(): ImageFormat {
        val selectedValue = binding.imageFormatInput.text?.toString()?.trim().orEmpty()
        if (selectedValue.isEmpty()) {
            return ImageFormat.JPG
        }
        return ImageFormat.fromValue(selectedValue)
            ?: throw IllegalArgumentException(getString(R.string.invalid_image_format))
    }

    private fun getSelectedPdfCompressionQuality(): Int {
        return when (binding.compressionProfileChipGroup.checkedChipId) {
            R.id.compressSmallerChip -> PDF_COMPRESSION_QUALITY_SMALLER
            R.id.compressBetterChip -> PDF_COMPRESSION_QUALITY_BETTER
            else -> DEFAULT_PDF_COMPRESSION_QUALITY
        }
    }

    private fun refreshDashboardCollections() {
        resumeEditItems = VisualEditProjectStore.listResumeItems(this)
        recentDocuments = ReaderLibraryStore.getRecentDocuments(this)
        renderResumeProjects()
        renderRecentDocuments()
        updateSelectedPdfState()
    }

    private fun renderResumeProjects() {
        val currentUri = selectedPdfUri?.toString()
        val items = resumeEditItems.filterNot { item -> item.resumeUriString == currentUri }
        binding.resumeProjectsCountText.text = if (items.isEmpty()) {
            getString(R.string.home_count_active_zero)
        } else {
            getString(R.string.home_count_active, items.size)
        }
        binding.resumeProjectsContainer.removeAllViews()
        binding.resumeProjectsEmptyText.isVisible = items.isEmpty()
        items.forEach { item ->
            addDashboardEntry(
                container = binding.resumeProjectsContainer,
                badgeText = if (item.isDraft) {
                    getString(R.string.home_resume_badge_draft)
                } else {
                    getString(R.string.home_resume_badge_project)
                },
                title = LocalPdfStore.presentableDisplayName(item.resumeDisplayName),
                subtitle = buildResumeSubtitle(item),
                meta = buildResumeMeta(item),
                timeText = formatRelativeTime(item.updatedAt),
                actionText = getString(R.string.home_action_continue_edit),
                emphasizeAction = item.isDraft
            ) {
                launchPdfEditor(
                    pdfUri = Uri.parse(item.resumeUriString),
                    pdfName = LocalPdfStore.presentableDisplayName(item.resumeDisplayName)
                )
            }
        }
    }

    private fun renderRecentDocuments() {
        val currentUri = selectedPdfUri?.toString()
        val activeResumeUris = resumeEditItems.map { item -> item.resumeUriString }.toSet()
        val items = recentDocuments.filterNot { document ->
            document.uriString == currentUri || document.uriString in activeResumeUris
        }
        binding.recentDocumentsCountText.text = if (items.isEmpty()) {
            getString(R.string.home_count_files_zero)
        } else {
            getString(R.string.home_count_files, items.size)
        }
        binding.recentDocumentsContainer.removeAllViews()
        binding.recentDocumentsEmptyText.isVisible = items.isEmpty()
        items.forEach { document ->
            addDashboardEntry(
                container = binding.recentDocumentsContainer,
                badgeText = getString(R.string.home_recent_badge),
                title = LocalPdfStore.presentableDisplayName(document.displayName),
                subtitle = "",
                meta = buildRecentDocumentMeta(document),
                timeText = formatRelativeTime(document.lastOpenedAt),
                actionText = getString(R.string.home_action_continue_reader)
            ) {
                launchPdfReader(
                    pdfUri = Uri.parse(document.uriString),
                    pdfName = LocalPdfStore.presentableDisplayName(document.displayName),
                    startInReadMode = true
                )
            }
        }
    }

    private fun addDashboardEntry(
        container: LinearLayout,
        badgeText: String,
        title: String,
        subtitle: String,
        meta: String,
        timeText: String,
        actionText: String,
        emphasizeAction: Boolean = false,
        onClick: () -> Unit
    ) {
        val itemView = LayoutInflater.from(this)
            .inflate(R.layout.item_home_dashboard_entry, container, false)
        val badgeView = itemView.findViewById<TextView>(R.id.homeEntryBadgeText)
        val timeView = itemView.findViewById<TextView>(R.id.homeEntryTimeText)
        val titleView = itemView.findViewById<TextView>(R.id.homeEntryTitleText)
        val subtitleView = itemView.findViewById<TextView>(R.id.homeEntrySubtitleText)
        val metaView = itemView.findViewById<TextView>(R.id.homeEntryMetaText)
        val actionButton = itemView.findViewById<MaterialButton>(R.id.homeEntryActionButton)

        badgeView.text = badgeText
        timeView.text = timeText
        timeView.isVisible = timeText.isNotBlank()
        titleView.text = title
        subtitleView.text = subtitle
        subtitleView.isVisible = subtitle.isNotBlank()
        metaView.text = meta
        metaView.isVisible = meta.isNotBlank()
        actionButton.text = actionText
        styleDashboardActionButton(actionButton, emphasizeAction)
        actionButton.setOnClickListener { onClick() }
        itemView.setOnClickListener { onClick() }

        container.addView(itemView)
    }

    private fun styleDashboardActionButton(
        button: MaterialButton,
        emphasizeAction: Boolean
    ) {
        if (emphasizeAction) {
            button.backgroundTintList =
                ColorStateList.valueOf(ContextCompat.getColor(this, R.color.progress_color))
            button.setTextColor(ContextCompat.getColor(this, android.R.color.white))
        } else {
            button.backgroundTintList =
                ColorStateList.valueOf(ContextCompat.getColor(this, R.color.button_secondary_bg))
            button.setTextColor(ContextCompat.getColor(this, R.color.button_secondary_text))
        }
    }

    private fun buildResumeSubtitle(item: VisualEditProjectStore.ResumeItem): String {
        val sourceDisplayName = LocalPdfStore.presentableDisplayName(item.sourceDisplayName)
        val resumeDisplayName = LocalPdfStore.presentableDisplayName(item.resumeDisplayName)
        return sourceDisplayName
            .takeIf { sourceName ->
                sourceName.isNotBlank() && sourceName != resumeDisplayName
            }
            ?.let { sourceName ->
                getString(R.string.home_resume_source_prefix, sourceName)
            }
            .orEmpty()
    }

    private fun buildResumeMeta(item: VisualEditProjectStore.ResumeItem): String {
        val details = mutableListOf<String>()
        if (item.changedPageCount > 0) {
            details += resources.getQuantityString(
                R.plurals.home_resume_changed_pages,
                item.changedPageCount,
                item.changedPageCount
            )
        }
        if (item.pageCount > 0) {
            details += resources.getQuantityString(
                R.plurals.home_resume_total_pages,
                item.pageCount,
                item.pageCount
            )
        }
        return details.joinToString(" • ")
    }

    private fun buildRecentDocumentMeta(document: ReaderLibraryStore.RecentDocument): String {
        return if (document.pageCount > 0) {
            getString(
                R.string.continue_reading_meta,
                document.currentPageNumber.coerceAtMost(document.pageCount),
                document.pageCount
            )
        } else {
            ""
        }
    }

    private fun buildSelectedPdfMeta(
        recentDocument: ReaderLibraryStore.RecentDocument?,
        resumeItem: VisualEditProjectStore.ResumeItem?,
        pageCount: Int
    ): String {
        return when {
            resumeItem != null -> {
                val label = if (resumeItem.isDraft) {
                    getString(R.string.home_selected_meta_draft)
                } else {
                    getString(R.string.home_selected_meta_project)
                }
                listOf(label, buildResumeMeta(resumeItem))
                    .filter { value -> value.isNotBlank() }
                    .joinToString(" • ")
            }

            recentDocument != null && recentDocument.pageCount > 0 -> {
                getString(
                    R.string.continue_reading_meta,
                    recentDocument.currentPageNumber.coerceAtMost(recentDocument.pageCount),
                    recentDocument.pageCount
                )
            }

            pageCount > 0 -> {
                resources.getQuantityString(R.plurals.viewer_pages_ready, pageCount, pageCount)
            }

            else -> ""
        }
    }

    private fun formatRelativeTime(timestamp: Long): String {
        if (timestamp <= 0L) {
            return ""
        }
        return DateUtils.getRelativeTimeSpanString(
            timestamp,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS,
            DateUtils.FORMAT_ABBREV_RELATIVE
        ).toString()
    }

    private fun formatAbsoluteDateTime(timestamp: Long): String {
        if (timestamp <= 0L) {
            return ""
        }
        return DateUtils.formatDateTime(
            this,
            timestamp,
            DateUtils.FORMAT_SHOW_DATE or
                DateUtils.FORMAT_SHOW_TIME or
                DateUtils.FORMAT_ABBREV_MONTH or
                DateUtils.FORMAT_SHOW_YEAR
        )
    }

    private fun buildSelectedPdfLastUsedText(
        recentDocument: ReaderLibraryStore.RecentDocument?,
        resumeItem: VisualEditProjectStore.ResumeItem?
    ): String {
        val lastUsedAt = max(
            recentDocument?.lastOpenedAt ?: 0L,
            resumeItem?.updatedAt ?: 0L
        )
        if (lastUsedAt <= 0L) {
            return ""
        }
        return getString(
            R.string.home_selected_last_used,
            formatAbsoluteDateTime(lastUsedAt)
        )
    }

    private fun setHomeToolEnabled(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        view.alpha = if (enabled) 1f else 0.55f
    }

    private fun updateSelectedPdfState() {
        val hasPdf = selectedPdfUri != null
        val selectedUriString = selectedPdfUri?.toString()
        val recentDocument = recentDocuments.firstOrNull { document ->
            document.uriString == selectedUriString
        }
        val resumeItem = resumeEditItems.firstOrNull { item ->
            item.resumeUriString == selectedUriString
        }
        val selectedPageCount = allPageMetrics.size
            .takeIf { size -> size > 0 }
            ?: recentDocument?.pageCount
            ?: resumeItem?.pageCount
            ?: 0
        setHomeToolEnabled(binding.openPdfButton, hasPdf)
        setHomeToolEnabled(binding.exportZipButton, hasPdf)
        setHomeToolEnabled(binding.compressPdfButton, hasPdf)
        setHomeToolEnabled(binding.preflightButton, hasPdf)
        setHomeToolEnabled(binding.cropPageButton, hasPdf)
        setHomeToolEnabled(binding.duplicatePageButton, hasPdf)
        setHomeToolEnabled(binding.splitPdfButton, hasPdf)
        setHomeToolEnabled(binding.mergePdfButton, true)
        setHomeToolEnabled(binding.batchCompressButton, true)
        binding.selectedReadButton.isEnabled = hasPdf
        binding.selectedEditButton.isEnabled = hasPdf
        binding.selectedFileCard.isEnabled = hasPdf
        binding.selectedFileCard.isClickable = hasPdf
        binding.selectedBadgeText.text = when {
            resumeItem?.isDraft == true -> getString(R.string.home_resume_badge_draft)
            resumeItem != null -> getString(R.string.home_resume_badge_project)
            recentDocument != null -> getString(R.string.home_recent_badge)
            else -> getString(R.string.dashboard_selected_title)
        }
        binding.selectedPdfText.text = if (hasPdf) {
            LocalPdfStore.presentableDisplayName(selectedPdfName ?: getString(R.string.fallback_pdf_name))
        } else {
            getString(R.string.no_pdf_selected)
        }
        binding.statusText.text = if (hasPdf) {
            when {
                resumeItem?.isDraft == true -> getString(R.string.home_selected_status_draft)
                resumeItem != null -> getString(R.string.home_selected_status_project)
                recentDocument != null -> getString(R.string.home_selected_status_recent)
                else -> getString(R.string.ready_for_actions)
            }
        } else {
            getString(R.string.waiting_for_pdf)
        }
        binding.selectedPdfMetaText.text = buildSelectedPdfMeta(
            recentDocument = recentDocument,
            resumeItem = resumeItem,
            pageCount = selectedPageCount
        )
        binding.selectedPdfMetaText.isVisible =
            hasPdf && binding.selectedPdfMetaText.text?.isNotBlank() == true
        binding.selectedPdfLastUsedText.text = buildSelectedPdfLastUsedText(
            recentDocument = recentDocument,
            resumeItem = resumeItem
        )
        binding.selectedPdfLastUsedText.isVisible =
            hasPdf && binding.selectedPdfLastUsedText.text?.isNotBlank() == true
        binding.selectedReadButton.text = if (recentDocument != null) {
            getString(R.string.home_action_continue_reader)
        } else {
            getString(R.string.home_action_open_reader)
        }
        binding.selectedEditButton.text = if (resumeItem != null) {
            getString(R.string.home_action_continue_edit)
        } else {
            getString(R.string.home_action_open_editor)
        }
        refreshZipEstimate()
    }

    private fun launchPdfReader(
        pdfUri: Uri,
        pdfName: String,
        startInReadMode: Boolean = false
    ) {
        startActivity(
            PdfViewerActivity.createIntent(
                context = this,
                pdfUri = pdfUri,
                pdfName = pdfName,
                startInReadMode = startInReadMode
            )
        )
    }

    private fun launchPdfEditor(
        pdfUri: Uri,
        pdfName: String
    ) {
        startActivity(
            PdfEditActivity.createIntent(
                context = this,
                pdfUri = pdfUri,
                pdfName = pdfName
            )
        )
    }

    private fun launchVisualPagePicker(
        pdfUri: Uri,
        pdfName: String
    ) {
        val initialRemovedPages = runCatching {
            parsePageSelection(binding.removePagesInput.text?.toString().orEmpty())
        }.getOrDefault(emptySet())

        visualPagePickerLauncher.launch(
            PdfEditActivity.createSelectionIntent(
                context = this,
                pdfUri = pdfUri,
                pdfName = pdfName,
                initiallyRemovedPages = initialRemovedPages
            )
        )
    }

    private fun analyzePreflightAsync(uri: Uri) {
        val uriString = uri.toString()
        val requestSerial = ++preflightRequestSerial
        activePreflightUriString = uriString
        if (binding.toolPanel.isVisible) {
            showCompactPreflightMessage(
                getString(R.string.preflight_compact_loading),
                R.color.preflight_loading_bg
            )
        }
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { readAllPageMetrics(uri) }
            }

            val selectedUriString = selectedPdfUri?.toString()
            if (requestSerial != preflightRequestSerial || uriString != selectedUriString) {
                return@launch
            }
            activePreflightUriString = null

            result.onSuccess { metrics ->
                allPageMetrics = metrics
                refreshPreflightPreview()
            }.onFailure {
                allPageMetrics = emptyList()
                showCompactPreflightMessage(
                    getString(R.string.preflight_compact_unavailable),
                    R.color.preflight_mixed_bg
                )
            }
        }
    }

    private fun refreshPreflightPreview() {
        if (!binding.toolPanel.isVisible || selectedPdfUri == null) {
            return
        }
        if (allPageMetrics.isEmpty()) {
            ensurePreflightMetrics()
            showCompactPreflightMessage(
                getString(R.string.preflight_compact_loading),
                R.color.preflight_loading_bg
            )
            return
        }

        val result = runCatching {
            val filterOptions = readFilterOptions()
            val pagesToProcess = buildPagesToProcess(allPageMetrics.size, filterOptions)
            val filteredMetrics = pagesToProcess.map { allPageMetrics[it] }
            buildPreflightReport(filteredMetrics)
        }

        result.onSuccess { report ->
            displayedPreflightReport = report
            showPreflightReport(report)
            refreshZipEstimate()
        }.onFailure {
            displayedPreflightReport = null
            showCompactPreflightMessage(
                getString(R.string.preflight_invalid_filters),
                R.color.preflight_mixed_bg
            )
            refreshZipEstimate()
        }
    }

    private fun showPreflightReport(report: PreflightReport) {
        val majorityGroup = report.sizeGroups.firstOrNull()
        val summary = if (report.uniform) {
            getString(R.string.preflight_compact_uniform_title)
        } else {
            resources.getQuantityString(
                R.plurals.preflight_compact_detected_sizes,
                report.uniqueSizeCount,
                report.uniqueSizeCount
            )
        }
        val detail = majorityGroup?.let { group ->
            if (report.uniform) {
                resources.getQuantityString(
                    R.plurals.preflight_compact_pages_with_size,
                    group.pageCount,
                    group.pageCount,
                    buildPageSizeSummary(group.samplePage)
                )
            } else {
                resources.getQuantityString(
                    R.plurals.preflight_compact_different_pages,
                    report.differentPageCount,
                    report.differentPageCount
                )
            }
        }
        val breakdown = if (report.uniform || majorityGroup == null) {
            null
        } else {
            buildPreflightBreakdown(report, majorityGroup)
        }
        showCompactPreflightMessage(
            message = summary,
            colorRes = if (report.uniform) R.color.preflight_uniform_bg else R.color.preflight_mixed_bg,
            detail = detail,
            breakdown = breakdown
        )
    }

    private fun buildPreflightBreakdown(
        report: PreflightReport,
        majorityGroup: PreflightSizeGroup
    ): String {
        val lines = mutableListOf<String>()
        lines += resources.getQuantityString(
            R.plurals.preflight_compact_size_line,
            majorityGroup.pageCount,
            getString(R.string.preflight_compact_majority_label),
            majorityGroup.pageCount,
            buildPageSizeSummary(majorityGroup.samplePage)
        )
        val differentGroups = report.sizeGroups.drop(1)
        differentGroups.take(PREFLIGHT_PREVIEW_LIMIT).forEach { group ->
            lines += resources.getQuantityString(
                R.plurals.preflight_compact_size_line,
                group.pageCount,
                getString(R.string.preflight_compact_other_label),
                group.pageCount,
                buildPageSizeSummary(group.samplePage)
            )
        }
        val remainingGroups = differentGroups.size - PREFLIGHT_PREVIEW_LIMIT
        if (remainingGroups > 0) {
            lines += resources.getQuantityString(
                R.plurals.preflight_compact_more_sizes,
                remainingGroups,
                remainingGroups
            )
        }
        return lines.joinToString("\n")
    }

    private fun showCompactPreflightMessage(
        message: String,
        colorRes: Int,
        detail: String? = null,
        breakdown: String? = null
    ) {
        binding.preflightSummaryText.text = message
        binding.preflightDetailText.isVisible = !detail.isNullOrBlank()
        binding.preflightBreakdownText.isVisible = !breakdown.isNullOrBlank()
        if (!detail.isNullOrBlank()) {
            binding.preflightDetailText.text = detail
        }
        if (!breakdown.isNullOrBlank()) {
            binding.preflightBreakdownText.text = breakdown
        }
        binding.preflightSummaryCard.setCardBackgroundColor(
            ContextCompat.getColor(this, colorRes)
        )
    }

    private fun exportPdfToZip(inputUri: Uri, outputUri: Uri, exportOptions: ExportOptions) {
        setLoading(true, getString(R.string.exporting_status))
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    renderPdfIntoZip(inputUri, outputUri, exportOptions)
                }
            }

            pendingExportOptions = null
            setLoading(false)
            result.onSuccess { pageCount ->
                binding.statusText.text = getString(R.string.export_success, pageCount)
                showMessage(getString(R.string.export_success, pageCount))
            }.onFailure { error ->
                binding.statusText.text = getString(
                    R.string.export_failed,
                    error.message ?: getString(R.string.unknown_error)
                )
                showMessage(
                    getString(
                        R.string.export_failed,
                        error.message ?: getString(R.string.unknown_error)
                    )
                )
            }
        }
    }

    private fun compressPdfOffline(inputUri: Uri, outputUri: Uri, exportOptions: ExportOptions) {
        setLoading(true, getString(R.string.compressing_pdf_status))
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    createCompressedPdf(inputUri, outputUri, exportOptions)
                }
            }

            pendingExportOptions = null
            setLoading(false)
            result.onSuccess { compressionResult ->
                val message = buildCompressionSuccessMessage(compressionResult)
                binding.statusText.text = message
                showMessage(message)
            }.onFailure { error ->
                binding.statusText.text = getString(
                    R.string.compress_pdf_failed,
                    error.message ?: getString(R.string.unknown_error)
                )
                showMessage(
                    getString(
                        R.string.compress_pdf_failed,
                        error.message ?: getString(R.string.unknown_error)
                    )
                )
            }
        }
    }

    private fun editPdfOffline(inputUri: Uri, outputUri: Uri, editOptions: EditOptions) {
        setLoading(true, getString(R.string.editing_pdf_status))
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    createEditedPdf(inputUri, outputUri, editOptions)
                }
            }

            setLoading(false)
            result.onSuccess { pageCount ->
                val message = getString(R.string.edit_pdf_success, pageCount)
                binding.statusText.text = message
                showMessage(message)
            }.onFailure { error ->
                binding.statusText.text = getString(
                    R.string.edit_pdf_failed,
                    error.message ?: getString(R.string.unknown_error)
                )
                showMessage(
                    getString(
                        R.string.edit_pdf_failed,
                        error.message ?: getString(R.string.unknown_error)
                    )
                )
            }
        }
    }

    private fun cropPdfOffline(inputUri: Uri, outputUri: Uri, cropOptions: CropOptions) {
        setLoading(true, getString(R.string.cropping_pdf_status))
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    createCroppedPdf(inputUri, outputUri, cropOptions)
                }
            }

            pendingCropOptions = null
            setLoading(false)
            result.onSuccess { pageCount ->
                val message = getString(R.string.crop_pdf_success, pageCount)
                binding.statusText.text = message
                showMessage(message)
            }.onFailure { error ->
                binding.statusText.text = getString(
                    R.string.crop_pdf_failed,
                    error.message ?: getString(R.string.unknown_error)
                )
                showMessage(
                    getString(
                        R.string.crop_pdf_failed,
                        error.message ?: getString(R.string.unknown_error)
                    )
                )
            }
        }
    }

    private fun exportSinglePageAsImageOffline(
        inputUri: Uri,
        outputUri: Uri,
        exportOptions: PageImageExportOptions
    ) {
        setLoading(true, getString(R.string.page_image_export_status))
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    saveSinglePageAsImage(inputUri, outputUri, exportOptions)
                }
            }

            pendingPageImageExportOptions = null
            setLoading(false)
            result.onSuccess { pageNumber ->
                val message = getString(R.string.page_image_export_success, pageNumber)
                binding.statusText.text = message
                showMessage(message)
            }.onFailure { error ->
                binding.statusText.text = getString(
                    R.string.page_image_export_failed,
                    error.message ?: getString(R.string.unknown_error)
                )
                showMessage(
                    getString(
                        R.string.page_image_export_failed,
                        error.message ?: getString(R.string.unknown_error)
                    )
                )
            }
        }
    }

    private fun exportPageImagesZipOffline(
        inputUri: Uri,
        outputUri: Uri,
        exportOptions: PageImageExportOptions
    ) {
        setLoading(true, getString(R.string.page_images_zip_export_status))
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    savePagesAsImagesZip(inputUri, outputUri, exportOptions)
                }
            }

            pendingPageImageExportOptions = null
            setLoading(false)
            result.onSuccess { pageCount ->
                val message = getString(R.string.page_images_zip_export_success, pageCount)
                binding.statusText.text = message
                showMessage(message)
            }.onFailure { error ->
                binding.statusText.text = getString(
                    R.string.page_images_zip_export_failed,
                    error.message ?: getString(R.string.unknown_error)
                )
                showMessage(
                    getString(
                        R.string.page_images_zip_export_failed,
                        error.message ?: getString(R.string.unknown_error)
                    )
                )
            }
        }
    }

    private fun duplicatePdfOffline(
        inputUri: Uri,
        outputUri: Uri,
        duplicateOptions: DuplicateOptions
    ) {
        setLoading(true, getString(R.string.duplicating_pdf_status))
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    createDuplicatedPdf(inputUri, outputUri, duplicateOptions)
                }
            }

            pendingDuplicateOptions = null
            setLoading(false)
            result.onSuccess { pageCount ->
                val message = getString(R.string.duplicate_pdf_success, pageCount)
                binding.statusText.text = message
                showMessage(message)
            }.onFailure { error ->
                binding.statusText.text = getString(
                    R.string.duplicate_pdf_failed,
                    error.message ?: getString(R.string.unknown_error)
                )
                showMessage(
                    getString(
                        R.string.duplicate_pdf_failed,
                        error.message ?: getString(R.string.unknown_error)
                    )
                )
            }
        }
    }

    private fun mergePdfsOffline(inputUris: List<Uri>, outputUri: Uri) {
        val sourceCount = inputUris.size
        setLoading(true, getString(R.string.merging_pdfs_status))
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    mergePdfs(inputUris, outputUri)
                }
            }

            pendingMergeUris = emptyList()
            setLoading(false)
            result.onSuccess { pageCount ->
                val message = getString(R.string.merge_pdf_success, sourceCount, pageCount)
                binding.statusText.text = message
                showMessage(message)
            }.onFailure { error ->
                binding.statusText.text = getString(
                    R.string.merge_pdf_failed,
                    error.message ?: getString(R.string.unknown_error)
                )
                showMessage(
                    getString(
                        R.string.merge_pdf_failed,
                        error.message ?: getString(R.string.unknown_error)
                    )
                )
            }
        }
    }

    private fun splitPdfOffline(inputUri: Uri, outputUri: Uri, splitOptions: SplitOptions) {
        setLoading(true, getString(R.string.splitting_pdf_status))
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    splitPdfToZip(inputUri, outputUri, splitOptions)
                }
            }

            pendingSplitOptions = null
            setLoading(false)
            result.onSuccess { partCount ->
                val message = getString(R.string.split_pdf_success, partCount)
                binding.statusText.text = message
                showMessage(message)
            }.onFailure { error ->
                binding.statusText.text = getString(
                    R.string.split_pdf_failed,
                    error.message ?: getString(R.string.unknown_error)
                )
                showMessage(
                    getString(
                        R.string.split_pdf_failed,
                        error.message ?: getString(R.string.unknown_error)
                    )
                )
            }
        }
    }

    private fun batchCompressPdfsOffline(inputUris: List<Uri>, outputUri: Uri) {
        val sourceCount = inputUris.size
        setLoading(true, getString(R.string.batch_compress_status))
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    batchCompressPdfsToZip(inputUris, outputUri)
                }
            }

            pendingBatchCompressionUris = emptyList()
            setLoading(false)
            result.onSuccess { pageCount ->
                val message = getString(R.string.batch_compress_success, sourceCount, pageCount)
                binding.statusText.text = message
                showMessage(message)
            }.onFailure { error ->
                binding.statusText.text = getString(
                    R.string.batch_compress_failed,
                    error.message ?: getString(R.string.unknown_error)
                )
                showMessage(
                    getString(
                        R.string.batch_compress_failed,
                        error.message ?: getString(R.string.unknown_error)
                    )
                )
            }
        }
    }

    @Throws(IOException::class)
    private fun renderPdfIntoZip(
        inputUri: Uri,
        outputUri: Uri,
        exportOptions: ExportOptions
    ): Int {
        val localPdf = LocalPdfStore.prepareForRead(
            this,
            inputUri,
            preferredDisplayNameForUri(inputUri)
        )
        ParcelFileDescriptor.open(localPdf.file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            contentResolver.openOutputStream(outputUri)?.use { stream ->
                PdfRenderer(descriptor).use { renderer ->
                    if (renderer.pageCount == 0) {
                        throw IOException(getString(R.string.empty_pdf))
                    }

                    val pagesToProcess = buildPagesToProcess(renderer.pageCount, exportOptions.filterOptions)
                    val zipRenderProfile = buildZipRenderProfile(
                        pageCount = pagesToProcess.size,
                        sourceBytes = localPdf.sizeBytes,
                        imageFormat = exportOptions.imageFormat
                    )

                    BufferedOutputStream(stream).use { bufferedStream ->
                        ZipOutputStream(bufferedStream).use { zipStream ->
                            for ((exportedIndex, pageIndex) in pagesToProcess.withIndex()) {
                                renderer.openPage(pageIndex).use { page ->
                                    val bitmap = createZipPageBitmap(
                                        page = page,
                                        renderProfile = zipRenderProfile
                                    )
                                    try {
                                        val entryName =
                                            "(${exportedIndex + 1}).${exportOptions.imageFormat.extension}"
                                        val entryBytes = encodeZipPageBytes(
                                            bitmap = bitmap,
                                            imageFormat = exportOptions.imageFormat,
                                            imageQuality = exportOptions.imageQuality
                                        )
                                        writeStoredZipEntry(
                                            zipStream = zipStream,
                                            entryName = entryName,
                                            entryBytes = entryBytes
                                        )
                                    } finally {
                                        bitmap.recycle()
                                    }
                                }
                            }
                        }
                    }
                    return pagesToProcess.size
                }
            } ?: throw IOException(getString(R.string.cannot_open_zip))
        }
    }

    @Throws(IOException::class)
    private fun saveSinglePageAsImage(
        inputUri: Uri,
        outputUri: Uri,
        exportOptions: PageImageExportOptions
    ): Int {
        val localPdf = LocalPdfStore.prepareForRead(
            this,
            inputUri,
            preferredDisplayNameForUri(inputUri)
        )
        val pageNumber = exportOptions.pageSequence.firstOrNull()
            ?: throw IOException(getString(R.string.page_image_pages_required))

        ParcelFileDescriptor.open(localPdf.file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            contentResolver.openOutputStream(outputUri)?.use { stream ->
                PdfRenderer(descriptor).use { renderer ->
                    if (renderer.pageCount == 0) {
                        throw IOException(getString(R.string.empty_pdf))
                    }

                    buildEditPageOrder(renderer.pageCount, listOf(pageNumber))
                    val renderProfile = buildZipRenderProfile(
                        pageCount = 1,
                        sourceBytes = localPdf.sizeBytes,
                        imageFormat = exportOptions.imageFormat
                    )

                    renderer.openPage(pageNumber - 1).use { page ->
                        val bitmap = createZipPageBitmap(page, renderProfile)
                        try {
                            BufferedOutputStream(stream).use { bufferedStream ->
                                val didCompress = bitmap.compress(
                                    exportOptions.imageFormat.compressFormat,
                                    if (exportOptions.imageFormat.supportsQuality) {
                                        DEFAULT_JPEG_QUALITY
                                    } else {
                                        100
                                    },
                                    bufferedStream
                                )
                                if (!didCompress) {
                                    throw IOException(
                                        getString(
                                            R.string.compress_failed,
                                            exportOptions.imageFormat.extension
                                        )
                                    )
                                }
                            }
                        } finally {
                            bitmap.recycle()
                        }
                    }
                }
            } ?: throw IOException(getString(R.string.cannot_write_pdf))
        }

        return pageNumber
    }

    @Throws(IOException::class)
    private fun savePagesAsImagesZip(
        inputUri: Uri,
        outputUri: Uri,
        exportOptions: PageImageExportOptions
    ): Int {
        val localPdf = LocalPdfStore.prepareForRead(
            this,
            inputUri,
            preferredDisplayNameForUri(inputUri)
        )

        ParcelFileDescriptor.open(localPdf.file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            contentResolver.openOutputStream(outputUri)?.use { stream ->
                PdfRenderer(descriptor).use { renderer ->
                    if (renderer.pageCount == 0) {
                        throw IOException(getString(R.string.empty_pdf))
                    }

                    val pageSequence = buildEditPageOrder(
                        renderer.pageCount,
                        exportOptions.pageSequence
                    )
                    val renderProfile = buildZipRenderProfile(
                        pageCount = pageSequence.size,
                        sourceBytes = localPdf.sizeBytes,
                        imageFormat = exportOptions.imageFormat
                    )

                    BufferedOutputStream(stream).use { bufferedStream ->
                        ZipOutputStream(bufferedStream).use { zipStream ->
                            pageSequence.forEachIndexed { exportIndex, pageNumber ->
                                renderer.openPage(pageNumber - 1).use { page ->
                                    val bitmap = createZipPageBitmap(page, renderProfile)
                                    try {
                                        val entryBytes = encodeZipPageBytes(
                                            bitmap = bitmap,
                                            imageFormat = exportOptions.imageFormat,
                                            imageQuality = if (exportOptions.imageFormat.supportsQuality) {
                                                DEFAULT_JPEG_QUALITY
                                            } else {
                                                100
                                            }
                                        )
                                        val entryName = buildPageImageEntryName(
                                            pageNumber = pageNumber,
                                            exportIndex = exportIndex,
                                            imageFormat = exportOptions.imageFormat
                                        )
                                        writeStoredZipEntry(
                                            zipStream = zipStream,
                                            entryName = entryName,
                                            entryBytes = entryBytes
                                        )
                                    } finally {
                                        bitmap.recycle()
                                    }
                                }
                            }
                        }
                    }
                    return pageSequence.size
                }
            } ?: throw IOException(getString(R.string.cannot_open_zip))
        }
    }

    @Throws(IOException::class)
    private fun createCompressedPdf(
        inputUri: Uri,
        outputUri: Uri,
        exportOptions: ExportOptions
    ): CompressionResult {
        contentResolver.openOutputStream(outputUri)?.use { stream ->
            val result = writeCompressedPdf(inputUri, stream, exportOptions)
            return result.copy(compressedBytes = queryFileSize(outputUri))
        } ?: throw IOException(getString(R.string.cannot_write_pdf))
    }

    @Throws(IOException::class)
    private fun createEditedPdf(
        inputUri: Uri,
        outputUri: Uri,
        editOptions: EditOptions
    ): Int {
        val localPdfFile = LocalPdfStore.requireLocalFile(
            this,
            inputUri,
            preferredDisplayNameForUri(inputUri)
        )
        contentResolver.openOutputStream(outputUri)?.use { outputStream ->
            PDDocument.load(localPdfFile).use { sourceDocument ->
                if (sourceDocument.numberOfPages == 0) {
                    throw IOException(getString(R.string.empty_pdf))
                }

                val filteredPageIndexes = buildPagesToProcess(
                    sourceDocument.numberOfPages,
                    editOptions.filterOptions
                )
                val orderedPages = buildEditPageOrder(
                    filteredPageIndexes.size,
                    editOptions.pageSequence
                )

                PDDocument().use { editedDocument ->
                    orderedPages.forEach { pageNumber ->
                        val sourcePageIndex = filteredPageIndexes[pageNumber - 1]
                        copyImportedPage(
                            destinationDocument = editedDocument,
                            sourcePage = sourceDocument.getPage(sourcePageIndex)
                        )
                    }

                    validateEditPageSelection(editedDocument.numberOfPages, editOptions.rotatePages)
                    validateEditPageSelection(editedDocument.numberOfPages, editOptions.duplicatePages)
                    if (editOptions.stampText.isNotEmpty()) {
                        validateEditPageSelection(editedDocument.numberOfPages, editOptions.stampPages)
                    }

                    editOptions.rotatePages.sorted().forEach { pageNumber ->
                        val page = editedDocument.getPage(pageNumber - 1)
                        page.rotation = normalizeRotation(page.rotation + editOptions.rotationDegrees)
                    }

                    if (editOptions.stampText.isNotEmpty()) {
                        val pagesToStamp = if (editOptions.stampPages.isEmpty()) {
                            (1..editedDocument.numberOfPages).toList()
                        } else {
                            editOptions.stampPages.sorted()
                        }
                        pagesToStamp.forEach { pageNumber ->
                            stampTextOnPage(
                                document = editedDocument,
                                page = editedDocument.getPage(pageNumber - 1),
                                stampText = editOptions.stampText
                            )
                        }
                    }

                    editOptions.duplicatePages.sorted().forEach { pageNumber ->
                        copyImportedPage(
                            destinationDocument = editedDocument,
                            sourcePage = editedDocument.getPage(pageNumber - 1)
                        )
                    }

                    if (editOptions.addPageNumbers) {
                        val totalPages = editedDocument.numberOfPages
                        for (pageIndex in 0 until totalPages) {
                            addPageNumberOverlay(
                                document = editedDocument,
                                page = editedDocument.getPage(pageIndex),
                                pageNumber = pageIndex + 1,
                                totalPages = totalPages
                            )
                        }
                    }

                    BufferedOutputStream(outputStream).use { bufferedStream ->
                        editedDocument.save(bufferedStream)
                    }
                    return editedDocument.numberOfPages
                }
            }
        } ?: throw IOException(getString(R.string.cannot_write_pdf))
    }

    @Throws(IOException::class)
    private fun createCroppedPdf(
        inputUri: Uri,
        outputUri: Uri,
        cropOptions: CropOptions
    ): Int {
        val localPdfFile = LocalPdfStore.requireLocalFile(
            this,
            inputUri,
            preferredDisplayNameForUri(inputUri)
        )
        contentResolver.openOutputStream(outputUri)?.use { outputStream ->
            PDDocument.load(localPdfFile).use { sourceDocument ->
                if (sourceDocument.numberOfPages == 0) {
                    throw IOException(getString(R.string.empty_pdf))
                }

                val pagesToCrop = if (cropOptions.pageSelection.isEmpty()) {
                    (1..sourceDocument.numberOfPages).toSet()
                } else {
                    validateEditPageSelection(sourceDocument.numberOfPages, cropOptions.pageSelection)
                    cropOptions.pageSelection
                }

                PDDocument().use { croppedDocument ->
                    for (pageIndex in 0 until sourceDocument.numberOfPages) {
                        val importedPage = copyImportedPage(
                            destinationDocument = croppedDocument,
                            sourcePage = sourceDocument.getPage(pageIndex)
                        )
                        if ((pageIndex + 1) in pagesToCrop) {
                            applyCropToPage(importedPage, cropOptions)
                        }
                    }

                    BufferedOutputStream(outputStream).use { bufferedStream ->
                        croppedDocument.save(bufferedStream)
                    }
                    return croppedDocument.numberOfPages
                }
            }
        } ?: throw IOException(getString(R.string.cannot_write_pdf))
    }

    @Throws(IOException::class)
    private fun createDuplicatedPdf(
        inputUri: Uri,
        outputUri: Uri,
        duplicateOptions: DuplicateOptions
    ): Int {
        val localPdfFile = LocalPdfStore.requireLocalFile(
            this,
            inputUri,
            preferredDisplayNameForUri(inputUri)
        )
        contentResolver.openOutputStream(outputUri)?.use { outputStream ->
            PDDocument.load(localPdfFile).use { sourceDocument ->
                if (sourceDocument.numberOfPages == 0) {
                    throw IOException(getString(R.string.empty_pdf))
                }

                validateEditPageSelection(sourceDocument.numberOfPages, duplicateOptions.pageSelection)

                PDDocument().use { duplicatedDocument ->
                    for (pageIndex in 0 until sourceDocument.numberOfPages) {
                        val sourcePage = sourceDocument.getPage(pageIndex)
                        copyImportedPage(
                            destinationDocument = duplicatedDocument,
                            sourcePage = sourcePage
                        )
                        if ((pageIndex + 1) in duplicateOptions.pageSelection) {
                            copyImportedPage(
                                destinationDocument = duplicatedDocument,
                                sourcePage = sourcePage
                            )
                        }
                    }

                    BufferedOutputStream(outputStream).use { bufferedStream ->
                        duplicatedDocument.save(bufferedStream)
                    }
                    return duplicatedDocument.numberOfPages
                }
            }
        } ?: throw IOException(getString(R.string.cannot_write_pdf))
    }

    @Throws(IOException::class)
    private fun mergePdfs(
        inputUris: List<Uri>,
        outputUri: Uri
    ): Int {
        if (inputUris.size < 2) {
            throw IOException(getString(R.string.merge_pick_two_pdfs))
        }

        contentResolver.openOutputStream(outputUri)?.use { outputStream ->
            PDDocument().use { mergedDocument ->
                var mergedPageCount = 0
                inputUris.forEach { inputUri ->
                    val localPdfFile = LocalPdfStore.requireLocalFile(
                        this,
                        inputUri,
                        preferredDisplayNameForUri(inputUri)
                    )
                    PDDocument.load(localPdfFile).use { sourceDocument ->
                        if (sourceDocument.numberOfPages > 0) {
                            for (pageIndex in 0 until sourceDocument.numberOfPages) {
                                copyImportedPage(
                                    destinationDocument = mergedDocument,
                                    sourcePage = sourceDocument.getPage(pageIndex)
                                )
                                mergedPageCount += 1
                            }
                        }
                    }
                }

                if (mergedPageCount == 0) {
                    throw IOException(getString(R.string.empty_pdf))
                }

                BufferedOutputStream(outputStream).use { bufferedStream ->
                    mergedDocument.save(bufferedStream)
                }
                return mergedPageCount
            }
        } ?: throw IOException(getString(R.string.cannot_write_pdf))
    }

    @Throws(IOException::class)
    private fun splitPdfToZip(
        inputUri: Uri,
        outputUri: Uri,
        splitOptions: SplitOptions
    ): Int {
        val localPdfFile = LocalPdfStore.requireLocalFile(
            this,
            inputUri,
            preferredDisplayNameForUri(inputUri)
        )
        contentResolver.openOutputStream(outputUri)?.use { outputStream ->
            PDDocument.load(localPdfFile).use { sourceDocument ->
                if (sourceDocument.numberOfPages == 0) {
                    throw IOException(getString(R.string.empty_pdf))
                }

                splitOptions.pageGroups.forEach { pageGroup ->
                    buildEditPageOrder(sourceDocument.numberOfPages, pageGroup)
                }

                val baseName = preferredDisplayNameForUri(inputUri)
                    ?.replace(Regex("\\.pdf$", RegexOption.IGNORE_CASE), "")
                    ?.ifBlank { "document" }
                    ?: "document"

                BufferedOutputStream(outputStream).use { bufferedStream ->
                    ZipOutputStream(bufferedStream).use { zipStream ->
                        splitOptions.pageGroups.forEachIndexed { partIndex, pageGroup ->
                            PDDocument().use { partDocument ->
                                pageGroup.forEach { pageNumber ->
                                    copyImportedPage(
                                        destinationDocument = partDocument,
                                        sourcePage = sourceDocument.getPage(pageNumber - 1)
                                    )
                                }
                                val partBytes = partDocument.toPdfBytes()
                                val entryName = "${baseName}_part_${partIndex + 1}.pdf"
                                writeStoredZipEntry(
                                    zipStream = zipStream,
                                    entryName = entryName,
                                    entryBytes = partBytes
                                )
                            }
                        }
                    }
                }
                return splitOptions.pageGroups.size
            }
        } ?: throw IOException(getString(R.string.cannot_open_zip))
    }

    @Throws(IOException::class)
    private fun batchCompressPdfsToZip(
        inputUris: List<Uri>,
        outputUri: Uri
    ): Int {
        if (inputUris.isEmpty()) {
            throw IOException(getString(R.string.batch_compress_pick_pdf))
        }

        val exportOptions = ExportOptions(pdfCompressionQuality = DEFAULT_PDF_COMPRESSION_QUALITY)
        var totalPageCount = 0

        contentResolver.openOutputStream(outputUri)?.use { outputStream ->
            BufferedOutputStream(outputStream).use { bufferedStream ->
                ZipOutputStream(bufferedStream).use { zipStream ->
                    inputUris.forEachIndexed { index, inputUri ->
                        val (compressionResult, compressedBytes) =
                            createCompressedPdfBytes(inputUri, exportOptions)
                        totalPageCount += compressionResult.pageCount
                        writeStoredZipEntry(
                            zipStream = zipStream,
                            entryName = buildBatchCompressedEntryName(inputUri, index),
                            entryBytes = compressedBytes
                        )
                    }
                }
            }
        } ?: throw IOException(getString(R.string.cannot_open_zip))

        return totalPageCount
    }

    private fun readExportOptions(requireCompressionSettings: Boolean): ExportOptions {
        val filterOptions = readFilterOptions()
        val imageFormat = getSelectedImageFormat()
        val imageQuality = parseImageQuality(
            binding.jpegQualityInput.text?.toString().orEmpty(),
            imageFormat
        )
        val resizeMode = getSelectedResizeMode()
        val customWidthInput = binding.customWidthInput.text?.toString().orEmpty()
        val customHeightInput = binding.customHeightInput.text?.toString().orEmpty()
        val shouldValidateCustomSize =
            requireCompressionSettings && resizeMode == ResizeMode.CUSTOM
        val customWidthMm = if (shouldValidateCustomSize) {
            parseOptionalPositiveDecimal(
                customWidthInput,
                getString(R.string.custom_width_label)
            )
        } else {
            customWidthInput.trim().toDoubleOrNull()
        }
        val customHeightMm = if (shouldValidateCustomSize) {
            parseOptionalPositiveDecimal(
                customHeightInput,
                getString(R.string.custom_height_label)
            )
        } else {
            customHeightInput.trim().toDoubleOrNull()
        }
        if (
            shouldValidateCustomSize &&
            (customWidthMm == null || customHeightMm == null)
        ) {
            throw IllegalArgumentException(getString(R.string.invalid_custom_size))
        }
        val fillMode = getSelectedFillMode()
        val resizeSkippedPages = parsePageSelection(
            binding.resizeSkipPagesInput.text?.toString().orEmpty()
        )
        return ExportOptions(
            filterOptions = filterOptions,
            imageFormat = imageFormat,
            imageQuality = imageQuality,
            pdfCompressionQuality = if (requireCompressionSettings) {
                getSelectedPdfCompressionQuality()
            } else {
                DEFAULT_PDF_COMPRESSION_QUALITY
            },
            resizeMode = resizeMode,
            customWidthMm = customWidthMm,
            customHeightMm = customHeightMm,
            fillMode = fillMode,
            resizeSkippedPages = resizeSkippedPages
        )
    }

    private fun readEditOptions(): EditOptions {
        return EditOptions(
            filterOptions = readFilterOptions(),
            pageSequence = parsePageSequence(
                binding.pageOrderInput.text?.toString().orEmpty()
            ),
            rotatePages = parsePageSelection(
                binding.rotatePagesInput.text?.toString().orEmpty()
            ),
            rotationDegrees = getSelectedRotationDegrees(),
            duplicatePages = parsePageSelection(
                binding.duplicatePagesInput.text?.toString().orEmpty()
            ),
            stampText = binding.stampTextInput.text?.toString()
                .orEmpty()
                .trim()
                .replace(Regex("\\s+"), " "),
            stampPages = parsePageSelection(
                binding.stampPagesInput.text?.toString().orEmpty()
            ),
            addPageNumbers = binding.addPageNumbersChip.isChecked
        )
    }

    private fun readCropOptions(dialogBinding: DialogCropPageBinding): CropOptions {
        val leftPercent = parseCropPercent(
            dialogBinding.cropLeftInput.text?.toString().orEmpty(),
            getString(R.string.crop_left_label)
        )
        val topPercent = parseCropPercent(
            dialogBinding.cropTopInput.text?.toString().orEmpty(),
            getString(R.string.crop_top_label)
        )
        val rightPercent = parseCropPercent(
            dialogBinding.cropRightInput.text?.toString().orEmpty(),
            getString(R.string.crop_right_label)
        )
        val bottomPercent = parseCropPercent(
            dialogBinding.cropBottomInput.text?.toString().orEmpty(),
            getString(R.string.crop_bottom_label)
        )
        if (leftPercent == 0.0 && topPercent == 0.0 && rightPercent == 0.0 && bottomPercent == 0.0) {
            throw IllegalArgumentException(getString(R.string.crop_amount_required))
        }
        if (leftPercent + rightPercent >= 100.0 || topPercent + bottomPercent >= 100.0) {
            throw IllegalArgumentException(getString(R.string.crop_amount_too_large))
        }
        return CropOptions(
            pageSelection = parsePageSelection(
                dialogBinding.cropPagesInput.text?.toString().orEmpty()
            ),
            leftPercent = leftPercent,
            topPercent = topPercent,
            rightPercent = rightPercent,
            bottomPercent = bottomPercent
        )
    }

    private fun readPageImageExportOptions(
        dialogBinding: DialogCropPageBinding
    ): PageImageExportOptions {
        val pageSequence = parsePageSequence(
            dialogBinding.cropPagesInput.text?.toString().orEmpty()
        ).distinct()
        if (pageSequence.isEmpty()) {
            throw IllegalArgumentException(getString(R.string.page_image_pages_required))
        }
        return PageImageExportOptions(
            pageSequence = pageSequence,
            imageFormat = getSelectedCropImageFormat(dialogBinding)
        )
    }

    private fun getSelectedCropImageFormat(dialogBinding: DialogCropPageBinding): ImageFormat {
        return when (dialogBinding.cropImageFormatChipGroup.checkedChipId) {
            R.id.cropImageFormatJpgChip -> ImageFormat.JPG
            else -> ImageFormat.PNG
        }
    }

    private fun readDuplicateOptions(dialogBinding: DialogDuplicatePageBinding): DuplicateOptions {
        val pageSelection = parsePageSelection(
            dialogBinding.duplicatePagesInput.text?.toString().orEmpty()
        )
        if (pageSelection.isEmpty()) {
            throw IllegalArgumentException(getString(R.string.duplicate_pages_required))
        }
        return DuplicateOptions(pageSelection = pageSelection)
    }

    private fun readSplitOptions(dialogBinding: DialogSplitPdfBinding): SplitOptions {
        return SplitOptions(
            pageGroups = parseSplitGroups(
                dialogBinding.splitGroupsInput.text?.toString().orEmpty()
            )
        )
    }

    private fun readFilterOptions(): FilterOptions {
        val skipFromStart = parseNonNegativeInt(
            binding.skipFirstInput.text?.toString().orEmpty(),
            getString(R.string.skip_first_label)
        )
        val skipFromEnd = parseNonNegativeInt(
            binding.skipLastInput.text?.toString().orEmpty(),
            getString(R.string.skip_last_label)
        )
        val removedPages = parsePageSelection(
            binding.removePagesInput.text?.toString().orEmpty()
        )
        return FilterOptions(
            skipFromStart = skipFromStart,
            skipFromEnd = skipFromEnd,
            removedPages = removedPages
        )
    }

    private fun getSelectedResizeMode(): ResizeMode {
        return when (binding.resizePresetChipGroup.checkedChipId) {
            R.id.a4ResizeChip -> ResizeMode.A4
            R.id.a3ResizeChip -> ResizeMode.A3
            R.id.letterResizeChip -> ResizeMode.LETTER
            R.id.legalResizeChip -> ResizeMode.LEGAL
            R.id.a5ResizeChip -> ResizeMode.A5
            R.id.squareResizeChip -> ResizeMode.SQUARE
            R.id.customResizeChip -> ResizeMode.CUSTOM
            else -> ResizeMode.AUTO
        }
    }

    private fun getSelectedRotationDegrees(): Int {
        return when (binding.rotationChipGroup.checkedChipId) {
            R.id.rotate180Chip -> 180
            R.id.rotate270Chip -> 270
            else -> 90
        }
    }

    private fun getSelectedFillMode(): FillMode {
        return when (binding.fillModeToggleGroup.checkedButtonId) {
            R.id.stretchModeButton -> FillMode.STRETCH
            else -> FillMode.FIT
        }
    }

    private fun parseNonNegativeInt(value: String, fieldName: String): Int {
        val trimmedValue = value.trim()
        if (trimmedValue.isEmpty()) {
            return 0
        }

        return trimmedValue.toIntOrNull()?.takeIf { it >= 0 }
            ?: throw IllegalArgumentException(
                getString(R.string.invalid_number_field, fieldName)
            )
    }

    private fun parseOptionalPositiveDecimal(value: String, fieldName: String): Double? {
        val trimmedValue = value.trim()
        if (trimmedValue.isEmpty()) {
            return null
        }

        return trimmedValue.toDoubleOrNull()?.takeIf { it > 0.0 }
            ?: throw IllegalArgumentException(
                getString(R.string.invalid_decimal_field, fieldName)
            )
    }

    private fun parseCropPercent(value: String, fieldName: String): Double {
        val trimmedValue = value.trim()
        if (trimmedValue.isEmpty()) {
            return 0.0
        }

        return trimmedValue.toDoubleOrNull()?.takeIf { it >= 0.0 && it < 100.0 }
            ?: throw IllegalArgumentException(
                getString(R.string.crop_invalid_percent, fieldName)
            )
    }

    private fun parsePageSelection(value: String): Set<Int> {
        val trimmedValue = value.trim()
        if (trimmedValue.isEmpty()) {
            return emptySet()
        }

        val pages = linkedSetOf<Int>()
        for (token in trimmedValue.split(",")) {
            val part = token.trim()
            if (part.isEmpty()) {
                continue
            }

            when {
                part.matches(Regex("\\d+")) -> {
                    val page = part.toInt()
                    if (page <= 0) {
                        throw IllegalArgumentException(getString(R.string.invalid_page_selection))
                    }
                    pages.add(page)
                }

                part.matches(Regex("\\d+\\s*-\\s*\\d+")) -> {
                    val rangeParts = part.split("-").map { it.trim() }
                    val startPage = rangeParts[0].toInt()
                    val endPage = rangeParts[1].toInt()
                    if (startPage <= 0 || endPage <= 0 || startPage > endPage) {
                        throw IllegalArgumentException(getString(R.string.invalid_page_selection))
                    }
                    for (page in startPage..endPage) {
                        pages.add(page)
                    }
                }

                else -> {
                    throw IllegalArgumentException(getString(R.string.invalid_page_selection))
                }
            }
        }

        return pages
    }

    private fun parsePageSequence(value: String): List<Int> {
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
                        throw IllegalArgumentException(getString(R.string.invalid_page_selection))
                    }
                    pages.add(page)
                }

                part.matches(Regex("\\d+\\s*-\\s*\\d+")) -> {
                    val rangeParts = part.split("-").map { it.trim() }
                    val startPage = rangeParts[0].toInt()
                    val endPage = rangeParts[1].toInt()
                    if (startPage <= 0 || endPage <= 0) {
                        throw IllegalArgumentException(getString(R.string.invalid_page_selection))
                    }
                    if (startPage <= endPage) {
                        for (page in startPage..endPage) {
                            pages.add(page)
                        }
                    } else {
                        for (page in startPage downTo endPage) {
                            pages.add(page)
                        }
                    }
                }

                else -> {
                    throw IllegalArgumentException(getString(R.string.invalid_page_selection))
                }
            }
        }

        return pages
    }

    private fun parseSplitGroups(value: String): List<List<Int>> {
        val trimmedValue = value.trim()
        if (trimmedValue.isEmpty()) {
            throw IllegalArgumentException(getString(R.string.split_groups_required))
        }

        val groups = trimmedValue
            .split(Regex("\\s*\\|\\s*"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { groupValue ->
                parsePageSequence(groupValue).ifEmpty {
                    throw IllegalArgumentException(getString(R.string.split_groups_required))
                }
            }

        if (groups.size < 2) {
            throw IllegalArgumentException(getString(R.string.split_groups_required))
        }

        return groups
    }

    private fun formatPageSelection(pages: Set<Int>): String {
        val sortedPages = pages.filter { it > 0 }.distinct().sorted()
        if (sortedPages.isEmpty()) {
            return ""
        }

        val formattedRanges = mutableListOf<String>()
        var rangeStart = sortedPages.first()
        var rangeEnd = rangeStart

        for (page in sortedPages.drop(1)) {
            if (page == rangeEnd + 1) {
                rangeEnd = page
            } else {
                formattedRanges += formatPageRange(rangeStart, rangeEnd)
                rangeStart = page
                rangeEnd = page
            }
        }

        formattedRanges += formatPageRange(rangeStart, rangeEnd)
        return formattedRanges.joinToString(", ")
    }

    private fun formatPageRange(startPage: Int, endPage: Int): String {
        return if (startPage == endPage) {
            startPage.toString()
        } else {
            "$startPage-$endPage"
        }
    }

    private fun parseImageQuality(value: String, imageFormat: ImageFormat): Int {
        if (!imageFormat.supportsQuality) {
            return 100
        }

        val trimmedValue = value.trim()
        if (trimmedValue.isEmpty()) {
            return DEFAULT_JPEG_QUALITY
        }

        return trimmedValue.toIntOrNull()?.takeIf { it in 1..100 }
            ?: throw IllegalArgumentException(getString(R.string.invalid_quality))
    }

    private fun readOutputBaseName(): String? {
        val rawValue = binding.outputNameInput.text?.toString().orEmpty().trim()
        if (rawValue.isEmpty()) {
            return null
        }

        val withoutExtension = rawValue
            .replace(Regex("\\.(zip|pdf)$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("[\\\\/:*?\"<>|]"), " ")
            .trim()
        return withoutExtension.ifBlank { null }
    }

    private fun buildPagesToProcess(totalPages: Int, filterOptions: FilterOptions): List<Int> {
        val invalidPages = filterOptions.removedPages.filterNot { it in 1..totalPages }
        if (invalidPages.isNotEmpty()) {
            throw IOException(
                getString(
                    R.string.pages_out_of_range,
                    invalidPages.joinToString(", "),
                    totalPages
                )
            )
        }

        val excludedPages = mutableSetOf<Int>()
        for (pageNumber in 1..min(filterOptions.skipFromStart, totalPages)) {
            excludedPages.add(pageNumber)
        }
        if (filterOptions.skipFromEnd > 0) {
            val startPage = max(1, totalPages - filterOptions.skipFromEnd + 1)
            for (pageNumber in startPage..totalPages) {
                excludedPages.add(pageNumber)
            }
        }
        excludedPages.addAll(filterOptions.removedPages)

        val pagesToProcess = (1..totalPages)
            .filterNot { it in excludedPages }
            .map { it - 1 }

        if (pagesToProcess.isEmpty()) {
            throw IOException(getString(R.string.no_pages_after_filter))
        }

        return pagesToProcess
    }

    private fun validateResizeSkippedPages(totalPages: Int, resizeSkippedPages: Set<Int>) {
        val invalidPages = resizeSkippedPages.filterNot { it in 1..totalPages }
        if (invalidPages.isNotEmpty()) {
            throw IOException(
                getString(
                    R.string.resize_pages_out_of_range,
                    invalidPages.joinToString(", "),
                    totalPages
                )
            )
        }
    }

    private fun validateEditPageSelection(totalPages: Int, selectedPages: Set<Int>) {
        val invalidPages = selectedPages.filterNot { it in 1..totalPages }
        if (invalidPages.isNotEmpty()) {
            throw IOException(
                getString(
                    R.string.pages_out_of_range,
                    invalidPages.joinToString(", "),
                    totalPages
                )
            )
        }
    }

    private fun buildEditPageOrder(totalPages: Int, pageSequence: List<Int>): List<Int> {
        if (pageSequence.isEmpty()) {
            return (1..totalPages).toList()
        }

        val invalidPages = pageSequence.filterNot { it in 1..totalPages }.distinct()
        if (invalidPages.isNotEmpty()) {
            throw IOException(
                getString(
                    R.string.pages_out_of_range,
                    invalidPages.joinToString(", "),
                    totalPages
                )
            )
        }
        return pageSequence
    }

    @Throws(IOException::class)
    private fun copyImportedPage(
        destinationDocument: PDDocument,
        sourcePage: PDPage
    ): PDPage {
        return destinationDocument.importPage(sourcePage).also { importedPage ->
            importedPage.resources = sourcePage.resources
        }
    }

    @Throws(IOException::class)
    private fun writeCompressedPdf(
        inputUri: Uri,
        outputStream: OutputStream,
        exportOptions: ExportOptions
    ): CompressionResult {
        val localPdf = LocalPdfStore.prepareForRead(
            this,
            inputUri,
            preferredDisplayNameForUri(inputUri)
        )
        val originalBytes = localPdf.sizeBytes
        var exportedPageCount = 0

        ParcelFileDescriptor.open(localPdf.file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                if (renderer.pageCount == 0) {
                    throw IOException(getString(R.string.empty_pdf))
                }

                val pageMetrics = cachedPageMetricsForUri(inputUri, renderer.pageCount)
                    ?: extractPageMetrics(renderer)
                val pagesToProcess = buildPagesToProcess(renderer.pageCount, exportOptions.filterOptions)
                validateResizeSkippedPages(renderer.pageCount, exportOptions.resizeSkippedPages)
                val filteredMetrics = pagesToProcess.map { pageMetrics[it] }
                val preflightReport = buildPreflightReport(filteredMetrics)

                PDDocument().use { document ->
                    for (pageIndex in pagesToProcess) {
                        renderer.openPage(pageIndex).use { page ->
                            val pageMetric = pageMetrics[pageIndex]
                            val targetSize = resolveTargetPageSize(
                                pageMetrics = pageMetric,
                                report = preflightReport,
                                exportOptions = exportOptions
                            )
                            val compressedPageBytes = createCompressedPageJpeg(
                                page = page,
                                targetSize = targetSize,
                                quality = exportOptions.pdfCompressionQuality
                            )
                            val outputPage = PDPage(
                                PDRectangle(
                                    targetSize.widthPoints.toFloat(),
                                    targetSize.heightPoints.toFloat()
                                )
                            )
                            document.addPage(outputPage)
                            val imageObject = JPEGFactory.createFromByteArray(
                                document,
                                compressedPageBytes
                            )
                            val placement = resolveImagePlacement(
                                sourceWidth = imageObject.width,
                                sourceHeight = imageObject.height,
                                targetSize = targetSize,
                                fillMode = exportOptions.fillMode
                            )
                            PDPageContentStream(document, outputPage).use { contentStream ->
                                contentStream.drawImage(
                                    imageObject,
                                    placement.x,
                                    placement.y,
                                    placement.width,
                                    placement.height
                                )
                            }
                        }
                    }

                    BufferedOutputStream(outputStream).use { bufferedStream ->
                        document.save(bufferedStream)
                    }
                }
                exportedPageCount = pagesToProcess.size
            }
        }

        return CompressionResult(
            pageCount = exportedPageCount,
            originalBytes = originalBytes,
            compressedBytes = null
        )
    }

    @Throws(IOException::class)
    private fun createCompressedPdfBytes(
        inputUri: Uri,
        exportOptions: ExportOptions
    ): Pair<CompressionResult, ByteArray> {
        val outputStream = ByteArrayOutputStream()
        val result = writeCompressedPdf(inputUri, outputStream, exportOptions)
        val bytes = outputStream.toByteArray()
        return result.copy(compressedBytes = bytes.size.toLong()) to bytes
    }

    private fun applyCropToPage(
        page: PDPage,
        cropOptions: CropOptions
    ) {
        val pageBox = page.cropBox ?: page.mediaBox
        val leftInset = (pageBox.width * (cropOptions.leftPercent / 100.0)).toFloat()
        val topInset = (pageBox.height * (cropOptions.topPercent / 100.0)).toFloat()
        val rightInset = (pageBox.width * (cropOptions.rightPercent / 100.0)).toFloat()
        val bottomInset = (pageBox.height * (cropOptions.bottomPercent / 100.0)).toFloat()
        val croppedWidth = pageBox.width - leftInset - rightInset
        val croppedHeight = pageBox.height - topInset - bottomInset
        if (croppedWidth <= 1f || croppedHeight <= 1f) {
            throw IllegalArgumentException(getString(R.string.crop_amount_too_large))
        }

        val croppedBox = PDRectangle(
            pageBox.lowerLeftX + leftInset,
            pageBox.lowerLeftY + bottomInset,
            croppedWidth,
            croppedHeight
        )
        page.cropBox = croppedBox
        page.mediaBox = croppedBox
    }

    private fun cachedPageMetricsForUri(inputUri: Uri, expectedPageCount: Int): List<PageMetrics>? {
        val selectedUri = selectedPdfUri ?: return null
        return if (selectedUri.toString() == inputUri.toString() && allPageMetrics.size == expectedPageCount) {
            allPageMetrics
        } else {
            null
        }
    }

    @Throws(IOException::class)
    private fun PDDocument.toPdfBytes(): ByteArray {
        return ByteArrayOutputStream().use { outputStream ->
            BufferedOutputStream(outputStream).use { bufferedStream ->
                save(bufferedStream)
            }
            outputStream.toByteArray()
        }
    }

    @Throws(IOException::class)
    private fun stampTextOnPage(
        document: PDDocument,
        page: PDPage,
        stampText: String
    ) {
        val pageBox = page.cropBox ?: page.mediaBox
        val font = PDType1Font.HELVETICA_BOLD
        val baseFontSize = min(pageBox.width, pageBox.height)
            .times(0.055f)
            .coerceIn(18f, 42f)
        val textWidthUnit = font.getStringWidth(stampText) / 1000f
        val maxTextWidth = pageBox.width * 0.78f
        val fontSize = if (textWidthUnit > 0f) {
            min(baseFontSize, maxTextWidth / textWidthUnit)
        } else {
            baseFontSize
        }.coerceAtLeast(14f)
        val textWidth = textWidthUnit * fontSize
        val startX = pageBox.lowerLeftX + ((pageBox.width - textWidth) / 2f)
        val startY = pageBox.lowerLeftY + (pageBox.height / 2f)

        PDPageContentStream(
            document,
            page,
            PDPageContentStream.AppendMode.APPEND,
            true,
            true
        ).use { contentStream ->
            contentStream.beginText()
            contentStream.setNonStrokingColor(110, 110, 110)
            contentStream.setFont(font, fontSize)
            contentStream.setTextRotation(
                Math.toRadians(24.0),
                startX.toDouble(),
                startY.toDouble()
            )
            contentStream.showText(stampText)
            contentStream.endText()
        }
    }

    @Throws(IOException::class)
    private fun addPageNumberOverlay(
        document: PDDocument,
        page: PDPage,
        pageNumber: Int,
        totalPages: Int
    ) {
        val pageBox = page.cropBox ?: page.mediaBox
        val font = PDType1Font.HELVETICA
        val fontSize = min(pageBox.width, pageBox.height)
            .times(0.018f)
            .coerceIn(10f, 14f)
        val pageLabel = "$pageNumber / $totalPages"
        val labelWidth = (font.getStringWidth(pageLabel) / 1000f) * fontSize
        val startX = pageBox.lowerLeftX + pageBox.width - labelWidth - 22f
        val startY = pageBox.lowerLeftY + 18f

        PDPageContentStream(
            document,
            page,
            PDPageContentStream.AppendMode.APPEND,
            true,
            true
        ).use { contentStream ->
            contentStream.beginText()
            contentStream.setNonStrokingColor(90, 90, 90)
            contentStream.setFont(font, fontSize)
            contentStream.newLineAtOffset(startX, startY)
            contentStream.showText(pageLabel)
            contentStream.endText()
        }
    }

    private fun readAllPageMetrics(uri: Uri): List<PageMetrics> {
        val localPdf = LocalPdfStore.prepareForRead(this, uri, preferredDisplayNameForUri(uri))
        ParcelFileDescriptor.open(localPdf.file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                if (renderer.pageCount == 0) {
                    throw IOException(getString(R.string.empty_pdf))
                }
                return extractPageMetrics(renderer)
            }
        }
    }

    private fun extractPageMetrics(renderer: PdfRenderer): List<PageMetrics> {
        val metrics = mutableListOf<PageMetrics>()
        for (pageIndex in 0 until renderer.pageCount) {
            renderer.openPage(pageIndex).use { page ->
                metrics += buildPageMetrics(
                    pageNumber = pageIndex + 1,
                    widthPoints = page.width,
                    heightPoints = page.height
                )
            }
        }
        return metrics
    }

    private fun buildPageMetrics(
        pageNumber: Int,
        widthPoints: Int,
        heightPoints: Int
    ): PageMetrics {
        val widthMm = pointsToMm(widthPoints)
        val heightMm = pointsToMm(heightPoints)
        val orientation = if (widthPoints > heightPoints) {
            PageOrientation.LANDSCAPE
        } else {
            PageOrientation.PORTRAIT
        }
        return PageMetrics(
            pageNumber = pageNumber,
            widthPoints = widthPoints,
            heightPoints = heightPoints,
            widthMm = widthMm,
            heightMm = heightMm,
            widthPixels = pointsToPixels(widthPoints),
            heightPixels = pointsToPixels(heightPoints),
            formatLabel = detectFormatLabel(widthMm, heightMm),
            orientation = orientation
        )
    }

    private fun detectFormatLabel(widthMm: Double, heightMm: Double): String {
        val shortSide = min(widthMm, heightMm)
        val longSide = max(widthMm, heightMm)
        val preset = PagePreset.values().firstOrNull { pagePreset ->
            val presetShort = min(pagePreset.widthMm, pagePreset.heightMm)
            val presetLong = max(pagePreset.widthMm, pagePreset.heightMm)
            abs(shortSide - presetShort) <= FORMAT_MATCH_TOLERANCE_MM &&
                abs(longSide - presetLong) <= FORMAT_MATCH_TOLERANCE_MM
        }
        return preset?.label ?: getString(R.string.preflight_format_custom)
    }

    private fun buildPreflightReport(pageMetrics: List<PageMetrics>): PreflightReport {
        val sizeGroups = pageMetrics
            .groupBy { it.widthPoints to it.heightPoints }
            .values
            .map { groupedPages ->
                PreflightSizeGroup(
                    samplePage = groupedPages.first(),
                    pageCount = groupedPages.size
                )
            }
            .sortedWith(
                compareByDescending<PreflightSizeGroup> { it.pageCount }
                    .thenBy { it.samplePage.pageNumber }
            )
        val majorityGroup = sizeGroups.firstOrNull()
        return PreflightReport(
            pageMetrics = pageMetrics,
            uniform = sizeGroups.size <= 1,
            uniqueSizeCount = sizeGroups.size,
            majorityPage = majorityGroup?.samplePage,
            sizeGroups = sizeGroups,
            differentPageCount = (pageMetrics.size - (majorityGroup?.pageCount ?: 0)).coerceAtLeast(0)
        )
    }

    private fun buildPageSizeSummary(pageMetrics: PageMetrics): String {
        return getString(
            R.string.preflight_page_size_summary,
            formatDecimal(pageMetrics.widthMm),
            formatDecimal(pageMetrics.heightMm),
            pageMetrics.formatLabel
        )
    }

    private fun resolveTargetPageSize(
        pageMetrics: PageMetrics,
        report: PreflightReport,
        exportOptions: ExportOptions
    ): PageSizeSpec {
        if (pageMetrics.pageNumber in exportOptions.resizeSkippedPages) {
            return pageMetrics.toPageSizeSpec()
        }

        val baseSize = when (exportOptions.resizeMode) {
            ResizeMode.AUTO -> report.majorityPage?.toPageSizeSpec() ?: pageMetrics.toPageSizeSpec()
            ResizeMode.A4 -> PagePreset.A4.toPageSizeSpec()
            ResizeMode.A3 -> PagePreset.A3.toPageSizeSpec()
            ResizeMode.LETTER -> PagePreset.LETTER.toPageSizeSpec()
            ResizeMode.LEGAL -> PagePreset.LEGAL.toPageSizeSpec()
            ResizeMode.A5 -> PagePreset.A5.toPageSizeSpec()
            ResizeMode.SQUARE -> PagePreset.SQUARE.toPageSizeSpec()
            ResizeMode.CUSTOM -> PageSizeSpec(
                widthPoints = mmToPoints(exportOptions.customWidthMm ?: 0.0),
                heightPoints = mmToPoints(exportOptions.customHeightMm ?: 0.0),
                label = getString(R.string.preflight_format_custom)
            )
        }
        return baseSize.orientedLike(pageMetrics.orientation)
    }

    private fun resolveImagePlacement(
        sourceWidth: Int,
        sourceHeight: Int,
        targetSize: PageSizeSpec,
        fillMode: FillMode
    ): ImagePlacement {
        return when (fillMode) {
            FillMode.FIT -> {
                val scale = min(
                    targetSize.widthPoints.toFloat() / sourceWidth.toFloat(),
                    targetSize.heightPoints.toFloat() / sourceHeight.toFloat()
                )
                val drawWidth = (sourceWidth * scale).coerceAtLeast(1f)
                val drawHeight = (sourceHeight * scale).coerceAtLeast(1f)
                ImagePlacement(
                    x = (targetSize.widthPoints - drawWidth) / 2f,
                    y = (targetSize.heightPoints - drawHeight) / 2f,
                    width = drawWidth,
                    height = drawHeight
                )
            }

            FillMode.STRETCH -> ImagePlacement(
                x = 0f,
                y = 0f,
                width = targetSize.widthPoints.toFloat(),
                height = targetSize.heightPoints.toFloat()
            )
        }
    }

    private fun createZipPageBitmap(
        page: PdfRenderer.Page,
        renderProfile: ZipRenderProfile
    ): Bitmap {
        return createRenderedBitmap(
            page = page,
            preferredScale = renderProfile.preferredScale,
            maxDimension = renderProfile.maxDimension,
            minimumScale = renderProfile.minimumScale,
            bitmapConfig = renderProfile.bitmapConfig
        )
    }

    private fun buildZipRenderProfile(
        pageCount: Int,
        sourceBytes: Long?,
        imageFormat: ImageFormat
    ): ZipRenderProfile {
        val sourceSizeBytes = sourceBytes ?: 0L
        val isLargeDocument =
            pageCount >= ZIP_LARGE_DOCUMENT_PAGE_THRESHOLD ||
                sourceSizeBytes >= ZIP_LARGE_DOCUMENT_SIZE_BYTES
        val isHugeDocument =
            pageCount >= ZIP_HUGE_DOCUMENT_PAGE_THRESHOLD ||
                sourceSizeBytes >= ZIP_HUGE_DOCUMENT_SIZE_BYTES
        val isLossyFormat = imageFormat.supportsQuality
        val preferredScale = when {
            isHugeDocument && isLossyFormat -> 1.08f
            isHugeDocument -> 1.0f
            isLargeDocument && isLossyFormat -> 1.2f
            isLargeDocument -> 1.08f
            isLossyFormat -> 1.4f
            else -> 1.16f
        }
        val minimumScale = when {
            isHugeDocument -> 0.72f
            isLargeDocument -> 0.82f
            else -> 0.92f
        }
        val maxDimension = when {
            isHugeDocument -> 1280f
            isLargeDocument -> 1480f
            isLossyFormat -> 1680f
            else -> 1560f
        }
        return ZipRenderProfile(
            preferredScale = preferredScale,
            maxDimension = maxDimension,
            minimumScale = minimumScale,
            // PdfRenderer requires ARGB_8888; using RGB_565 throws
            // "Unsupported pixel format" on JPEG/JPG exports.
            bitmapConfig = Bitmap.Config.ARGB_8888
        )
    }

    @Throws(IOException::class)
    private fun createCompressedPageJpeg(
        page: PdfRenderer.Page,
        targetSize: PageSizeSpec,
        quality: Int
    ): ByteArray {
        val targetMaxPixels = (
            max(targetSize.widthPoints, targetSize.heightPoints).toFloat() *
                PDF_COMPRESSION_RENDER_DPI / PDF_POINTS_PER_INCH.toFloat()
            ).coerceAtMost(PDF_COMPRESSION_MAX_DIMENSION)
        val currentMax = max(page.width, page.height).toFloat().coerceAtLeast(1f)
        val preferredScale = (targetMaxPixels / currentMax)
            .coerceIn(PDF_COMPRESSION_MIN_RENDER_SCALE, PDF_COMPRESSION_MAX_RENDER_SCALE)
        val bitmap = createRenderedBitmap(
            page = page,
            preferredScale = preferredScale,
            maxDimension = targetMaxPixels,
            minimumScale = PDF_COMPRESSION_MIN_RENDER_SCALE,
            bitmapConfig = Bitmap.Config.ARGB_8888
        )
        return try {
            compressBitmapToJpeg(bitmap, quality)
        } finally {
            bitmap.recycle()
        }
    }

    private fun createRenderedBitmap(
        page: PdfRenderer.Page,
        preferredScale: Float,
        maxDimension: Float,
        minimumScale: Float,
        bitmapConfig: Bitmap.Config
    ): Bitmap {
        val renderScale = calculateRenderScale(
            pageWidth = page.width,
            pageHeight = page.height,
            preferredScale = preferredScale,
            maxDimension = maxDimension,
            minimumScale = minimumScale
        )
        val width = (page.width * renderScale).roundToInt().coerceAtLeast(1)
        val height = (page.height * renderScale).roundToInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, bitmapConfig)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val matrix = Matrix().apply {
            postScale(renderScale, renderScale)
        }
        page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        return bitmap
    }

    private fun calculateRenderScale(
        pageWidth: Int,
        pageHeight: Int,
        preferredScale: Float,
        maxDimension: Float,
        minimumScale: Float
    ): Float {
        val currentMax = max(pageWidth, pageHeight).toFloat().coerceAtLeast(1f)
        val maxSafeScale = (maxDimension / currentMax).coerceAtLeast(0.1f)
        val effectiveMinimumScale = min(minimumScale, maxSafeScale)
        return min(preferredScale, maxSafeScale).coerceAtLeast(effectiveMinimumScale)
    }

    @Throws(IOException::class)
    private fun compressBitmapToJpeg(bitmap: Bitmap, quality: Int): ByteArray {
        val outputStream = ByteArrayOutputStream()
        outputStream.use { stream ->
            val didCompress = bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
            if (!didCompress) {
                throw IOException(getString(R.string.compress_pdf_bitmap_failed))
            }
            return stream.toByteArray()
        }
    }

    @Throws(IOException::class)
    private fun encodeZipPageBytes(
        bitmap: Bitmap,
        imageFormat: ImageFormat,
        imageQuality: Int
    ): ByteArray {
        val outputStream = ByteArrayOutputStream()
        outputStream.use { stream ->
            val didCompress = bitmap.compress(
                imageFormat.compressFormat,
                imageQuality,
                stream
            )
            if (!didCompress) {
                throw IOException(getString(R.string.compress_failed, imageFormat.extension))
            }
            return stream.toByteArray()
        }
    }

    @Throws(IOException::class)
    private fun writeStoredZipEntry(
        zipStream: ZipOutputStream,
        entryName: String,
        entryBytes: ByteArray
    ) {
        val crc = CRC32().apply {
            update(entryBytes)
        }
        val entry = ZipEntry(entryName).apply {
            method = ZipEntry.STORED
            size = entryBytes.size.toLong()
            compressedSize = entryBytes.size.toLong()
            this.crc = crc.value
        }
        zipStream.putNextEntry(entry)
        try {
            zipStream.write(entryBytes)
        } finally {
            zipStream.closeEntry()
        }
    }

    private fun buildZipFileName(
        pdfName: String?,
        imageFormat: ImageFormat,
        outputBaseName: String? = null
    ): String {
        val manualName = outputBaseName?.trim()?.ifBlank { null }
        if (manualName != null) {
            return "${manualName}.zip"
        }
        val baseName = pdfName
            ?.replace(Regex("\\.pdf$", RegexOption.IGNORE_CASE), "")
            ?.trim()
            ?.ifBlank { null }
            ?: "images"
        return "${baseName}_${imageFormat.extension}_images.zip"
    }

    private fun buildPageImageFileName(
        pdfName: String?,
        pageNumber: Int,
        imageFormat: ImageFormat
    ): String {
        val baseName = pdfName
            ?.replace(Regex("\\.pdf$", RegexOption.IGNORE_CASE), "")
            ?.trim()
            ?.ifBlank { null }
            ?: "document"
        return "${baseName}_page_${pageNumber}.${imageFormat.extension}"
    }

    private fun buildPageImagesZipFileName(
        pdfName: String?,
        imageFormat: ImageFormat
    ): String {
        val baseName = pdfName
            ?.replace(Regex("\\.pdf$", RegexOption.IGNORE_CASE), "")
            ?.trim()
            ?.ifBlank { null }
            ?: "document"
        return "${baseName}_${imageFormat.extension}_pages.zip"
    }

    private fun buildPageImageEntryName(
        pageNumber: Int,
        exportIndex: Int,
        imageFormat: ImageFormat
    ): String {
        return "page_${pageNumber}_${exportIndex + 1}.${imageFormat.extension}"
    }

    private fun buildCroppedPdfFileName(pdfName: String?): String {
        val baseName = pdfName
            ?.replace(Regex("\\.pdf$", RegexOption.IGNORE_CASE), "")
            ?.trim()
            ?.ifBlank { null }
            ?: "document"
        return "${baseName}_cropped.pdf"
    }

    private fun buildDuplicatedPdfFileName(pdfName: String?): String {
        val baseName = pdfName
            ?.replace(Regex("\\.pdf$", RegexOption.IGNORE_CASE), "")
            ?.trim()
            ?.ifBlank { null }
            ?: "document"
        return "${baseName}_duplicated.pdf"
    }

    private fun buildCompressedPdfFileName(
        pdfName: String?,
        outputBaseName: String? = null
    ): String {
        val manualName = outputBaseName?.trim()?.ifBlank { null }
        if (manualName != null) {
            return "${manualName}.pdf"
        }
        val baseName = pdfName
            ?.replace(Regex("\\.pdf$", RegexOption.IGNORE_CASE), "")
            ?.trim()
            ?.ifBlank { null }
            ?: "document"
        return "${baseName}_compressed.pdf"
    }

    private fun buildEditedPdfFileName(
        pdfName: String?,
        outputBaseName: String? = null
    ): String {
        val manualName = outputBaseName?.trim()?.ifBlank { null }
        if (manualName != null) {
            return "${manualName}.pdf"
        }
        val baseName = pdfName
            ?.replace(Regex("\\.pdf$", RegexOption.IGNORE_CASE), "")
            ?.trim()
            ?.ifBlank { null }
            ?: "document"
        return "${baseName}_edited.pdf"
    }

    private fun buildPreflightFixedPdfFileName(pdfName: String?): String {
        val baseName = pdfName
            ?.replace(Regex("\\.pdf$", RegexOption.IGNORE_CASE), "")
            ?.trim()
            ?.ifBlank { null }
            ?: "document"
        return "${baseName}_fixed.pdf"
    }

    private fun buildMergedPdfFileName(inputUris: List<Uri>): String {
        val firstName = inputUris.firstOrNull()?.let(::preferredDisplayNameForUri)
        val baseName = firstName
            ?.replace(Regex("\\.pdf$", RegexOption.IGNORE_CASE), "")
            ?.trim()
            ?.ifBlank { null }
            ?: "merged_pdfs"
        return "${baseName}_merged.pdf"
    }

    private fun buildSplitZipFileName(pdfName: String?): String {
        val baseName = pdfName
            ?.replace(Regex("\\.pdf$", RegexOption.IGNORE_CASE), "")
            ?.trim()
            ?.ifBlank { null }
            ?: "document"
        return "${baseName}_split.zip"
    }

    private fun buildBatchCompressedZipFileName(): String {
        return "PocketPDF_batch_compressed.zip"
    }

    private fun buildBatchCompressedEntryName(inputUri: Uri, index: Int): String {
        val baseName = preferredDisplayNameForUri(inputUri)
            ?.replace(Regex("\\.pdf$", RegexOption.IGNORE_CASE), "")
            ?.replace(Regex("[^A-Za-z0-9._-]+"), "_")
            ?.trim('_')
            ?.ifBlank { null }
            ?: "document_${index + 1}"
        return "${baseName}_compressed_${index + 1}.pdf"
    }

    private fun buildCompressionSuccessMessage(result: CompressionResult): String {
        val originalBytes = result.originalBytes
        val compressedBytes = result.compressedBytes
        return if (
            originalBytes != null &&
            compressedBytes != null &&
            originalBytes > 0L &&
            compressedBytes > 0L
        ) {
            getString(
                R.string.compress_pdf_success_with_size,
                result.pageCount,
                formatFileSize(originalBytes),
                formatFileSize(compressedBytes)
            )
        } else {
            getString(R.string.compress_pdf_success, result.pageCount)
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        return LocalPdfStore.queryDisplayName(this, uri)
    }

    private fun preferredDisplayNameForUri(uri: Uri): String? {
        return if (selectedPdfUri?.toString() == uri.toString()) {
            selectedPdfName ?: queryDisplayName(uri)
        } else {
            queryDisplayName(uri)
        }
    }

    private fun queryFileSize(uri: Uri): Long? {
        return LocalPdfStore.queryFileSize(this, uri)
    }

    private fun formatFileSize(sizeBytes: Long): String {
        return Formatter.formatShortFileSize(this, sizeBytes)
    }

    private fun formatDecimal(value: Double): String {
        return String.format(Locale.US, "%.1f", value)
    }

    private fun normalizeRotation(rotation: Int): Int {
        val normalized = rotation % 360
        return if (normalized < 0) normalized + 360 else normalized
    }

    private fun formatOrientation(orientation: PageOrientation): String {
        return if (orientation == PageOrientation.LANDSCAPE) {
            getString(R.string.preflight_orientation_landscape)
        } else {
            getString(R.string.preflight_orientation_portrait)
        }
    }

    private fun pointsToMm(points: Int): Double {
        return points * MM_PER_INCH / PDF_POINTS_PER_INCH
    }

    private fun pointsToPixels(points: Int): Int {
        return (points * PREVIEW_DPI / PDF_POINTS_PER_INCH).roundToInt().coerceAtLeast(1)
    }

    private fun mmToPoints(mm: Double): Int {
        return (mm * PDF_POINTS_PER_INCH / MM_PER_INCH).roundToInt().coerceAtLeast(1)
    }

    private fun PageMetrics.toPageSizeSpec(): PageSizeSpec {
        return PageSizeSpec(
            widthPoints = widthPoints,
            heightPoints = heightPoints,
            label = formatLabel
        )
    }

    private fun PagePreset.toPageSizeSpec(): PageSizeSpec {
        return PageSizeSpec(
            widthPoints = mmToPoints(widthMm),
            heightPoints = mmToPoints(heightMm),
            label = label
        )
    }

    private fun setLoading(isLoading: Boolean, loadingMessage: String? = null) {
        binding.progressIndicator.isVisible = isLoading
        binding.pickPdfButton.isEnabled = !isLoading
        setHomeToolEnabled(binding.openPdfButton, !isLoading && selectedPdfUri != null)
        setHomeToolEnabled(binding.exportZipButton, !isLoading && selectedPdfUri != null)
        setHomeToolEnabled(binding.compressPdfButton, !isLoading && selectedPdfUri != null)
        setHomeToolEnabled(binding.preflightButton, !isLoading && selectedPdfUri != null)
        setHomeToolEnabled(binding.cropPageButton, !isLoading && selectedPdfUri != null)
        setHomeToolEnabled(binding.duplicatePageButton, !isLoading && selectedPdfUri != null)
        setHomeToolEnabled(binding.splitPdfButton, !isLoading && selectedPdfUri != null)
        setHomeToolEnabled(binding.mergePdfButton, !isLoading)
        setHomeToolEnabled(binding.batchCompressButton, !isLoading)
        binding.selectedReadButton.isEnabled = !isLoading && selectedPdfUri != null
        binding.selectedEditButton.isEnabled = !isLoading && selectedPdfUri != null
        binding.toolPrimaryActionButton.isEnabled = !isLoading
        binding.toolPanelCloseButton.isEnabled = !isLoading
        binding.openVisualPageEditorButton.isEnabled = !isLoading
        binding.outputNameInput.isEnabled = !isLoading
        binding.skipFirstInput.isEnabled = !isLoading
        binding.skipLastInput.isEnabled = !isLoading
        binding.removePagesInput.isEnabled = !isLoading
        binding.pageOrderInput.isEnabled = !isLoading
        binding.rotatePagesInput.isEnabled = !isLoading
        binding.duplicatePagesInput.isEnabled = !isLoading
        binding.stampTextInput.isEnabled = !isLoading
        binding.stampPagesInput.isEnabled = !isLoading
        binding.imageFormatInput.isEnabled = !isLoading
        binding.jpegQualityInput.isEnabled = !isLoading
        binding.zipPresetFastChip.isEnabled = !isLoading
        binding.zipPresetBalancedChip.isEnabled = !isLoading
        binding.zipPresetBestChip.isEnabled = !isLoading
        binding.compressSmallerChip.isEnabled = !isLoading
        binding.compressBalancedChip.isEnabled = !isLoading
        binding.compressBetterChip.isEnabled = !isLoading
        binding.addPageNumbersChip.isEnabled = !isLoading
        binding.rotate90Chip.isEnabled = !isLoading
        binding.rotate180Chip.isEnabled = !isLoading
        binding.rotate270Chip.isEnabled = !isLoading
        binding.autoResizeChip.isEnabled = !isLoading
        binding.a4ResizeChip.isEnabled = !isLoading
        binding.a3ResizeChip.isEnabled = !isLoading
        binding.letterResizeChip.isEnabled = !isLoading
        binding.legalResizeChip.isEnabled = !isLoading
        binding.a5ResizeChip.isEnabled = !isLoading
        binding.squareResizeChip.isEnabled = !isLoading
        binding.customResizeChip.isEnabled = !isLoading
        binding.customWidthInput.isEnabled = !isLoading
        binding.customHeightInput.isEnabled = !isLoading
        binding.fitModeButton.isEnabled = !isLoading
        binding.stretchModeButton.isEnabled = !isLoading
        binding.resizeSkipPagesInput.isEnabled = !isLoading
        if (isLoading && loadingMessage != null) {
            binding.statusText.text = loadingMessage
        }
    }

    private fun showMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    companion object {
        const val EXTRA_SELECTED_PDF_URI = "extra_selected_pdf_uri"
        const val EXTRA_SELECTED_PDF_NAME = "extra_selected_pdf_name"
        const val EXTRA_OPEN_TOOL_MODE = "extra_open_tool_mode"
        const val TOOL_MODE_EDIT = "EDIT"
        const val TOOL_MODE_ZIP = "ZIP"
        const val TOOL_MODE_COMPRESS = "COMPRESS"
        const val TOOL_MODE_PREFLIGHT = "PREFLIGHT"

        fun createToolIntent(
            context: android.content.Context,
            pdfUri: Uri,
            pdfName: String,
            toolMode: String
        ): Intent {
            return Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(EXTRA_SELECTED_PDF_URI, pdfUri.toString())
                putExtra(EXTRA_SELECTED_PDF_NAME, pdfName)
                putExtra(EXTRA_OPEN_TOOL_MODE, toolMode)
            }
        }

        private const val FAST_JPEG_QUALITY = 72
        private const val DEFAULT_JPEG_QUALITY = 82
        private const val BEST_JPEG_QUALITY = 92
        private const val DEFAULT_PDF_COMPRESSION_QUALITY = 72
        private const val PDF_COMPRESSION_QUALITY_SMALLER = 58
        private const val PDF_COMPRESSION_QUALITY_BETTER = 86
        private const val PDF_POINTS_PER_INCH = 72.0
        private const val MM_PER_INCH = 25.4
        private const val PREVIEW_DPI = 96.0
        private const val PDF_COMPRESSION_RENDER_DPI = 132f
        private const val PDF_COMPRESSION_MIN_RENDER_SCALE = 0.55f
        private const val PDF_COMPRESSION_MAX_RENDER_SCALE = 1.25f
        private const val PDF_COMPRESSION_MAX_DIMENSION = 1440f
        private const val ZIP_LARGE_DOCUMENT_PAGE_THRESHOLD = 120
        private const val ZIP_HUGE_DOCUMENT_PAGE_THRESHOLD = 220
        private const val ZIP_LARGE_DOCUMENT_SIZE_BYTES = 70L * 1024L * 1024L
        private const val ZIP_HUGE_DOCUMENT_SIZE_BYTES = 140L * 1024L * 1024L
        private const val PREFLIGHT_PREVIEW_LIMIT = 3
        private const val FORMAT_MATCH_TOLERANCE_MM = 2.0
        private const val BACK_PRESS_EXIT_WINDOW_MS = 2000L
    }
}
