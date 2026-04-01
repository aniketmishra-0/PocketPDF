package com.renameapk.pdfzip

import android.content.Context
import android.graphics.PointF
import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

object VisualEditProjectStore {

    data class VisualEditProject(
        val sourceUriString: String,
        val pageNumbersEnabled: Boolean,
        val deletedPages: Set<Int>,
        val insertedPages: List<InsertedPageState>,
        val pageOrderTokens: List<String>,
        val pageMarkups: Map<Int, PageMarkupState>,
        val resumeUriString: String,
        val resumeDisplayName: String,
        val sourceDisplayName: String,
        val updatedAt: Long,
        val pageCount: Int,
        val isDraft: Boolean
    ) {
        val changedPageCount: Int
            get() = (deletedPages + pageMarkups.keys).size + insertedPages.size

        val operationCount: Int
            get() = pageMarkups.values.sumOf { state -> state.operations.size } + insertedPages.size
    }

    data class ResumeItem(
        val resumeUriString: String,
        val sourceUriString: String,
        val resumeDisplayName: String,
        val sourceDisplayName: String,
        val updatedAt: Long,
        val pageCount: Int,
        val changedPageCount: Int,
        val operationCount: Int,
        val isDraft: Boolean
    )

    fun loadProject(context: Context, editedUri: Uri): VisualEditProject? {
        return readProjectFile(
            file = projectFile(context, editedUri.toString()),
            fallbackResumeUriString = editedUri.toString(),
            fallbackIsDraft = false
        )
    }

    fun loadDraft(context: Context, resumeUri: Uri): VisualEditProject? {
        return readProjectFile(
            file = draftFile(context, resumeUri.toString()),
            fallbackResumeUriString = resumeUri.toString(),
            fallbackIsDraft = true
        )
    }

    fun listResumeItems(context: Context, limit: Int = DEFAULT_RESUME_ITEMS): List<ResumeItem> {
        val directory = File(context.filesDir, PROJECTS_DIRECTORY)
        val files = directory.listFiles { file ->
            file.isFile && file.extension.equals("json", ignoreCase = true)
        }.orEmpty()

        return files
            .mapNotNull(::readProjectFile)
            .sortedByDescending { project -> project.updatedAt }
            .mapNotNull(::buildResumeItem)
            .distinctBy { item -> item.resumeUriString }
            .take(limit)
    }

    fun saveProject(
        context: Context,
        editedUri: Uri,
        sourceUri: Uri,
        resumeDisplayName: String,
        sourceDisplayName: String,
        pageCount: Int,
        pageNumbersEnabled: Boolean,
        deletedPages: Set<Int>,
        insertedPages: List<InsertedPageState>,
        pageOrderTokens: List<String>,
        pageMarkups: Map<Int, PageMarkupState>
    ) {
        writeProjectFile(
            file = projectFile(context, editedUri.toString()),
            resumeUri = editedUri,
            sourceUri = sourceUri,
            resumeDisplayName = resumeDisplayName,
            sourceDisplayName = sourceDisplayName,
            pageCount = pageCount,
            pageNumbersEnabled = pageNumbersEnabled,
            deletedPages = deletedPages,
            insertedPages = insertedPages,
            pageOrderTokens = pageOrderTokens,
            pageMarkups = pageMarkups,
            isDraft = false
        )
    }

    fun saveDraft(
        context: Context,
        resumeUri: Uri,
        sourceUri: Uri,
        resumeDisplayName: String,
        sourceDisplayName: String,
        pageCount: Int,
        pageNumbersEnabled: Boolean,
        deletedPages: Set<Int>,
        insertedPages: List<InsertedPageState>,
        pageOrderTokens: List<String>,
        pageMarkups: Map<Int, PageMarkupState>
    ) {
        writeProjectFile(
            file = draftFile(context, resumeUri.toString()),
            resumeUri = resumeUri,
            sourceUri = sourceUri,
            resumeDisplayName = resumeDisplayName,
            sourceDisplayName = sourceDisplayName,
            pageCount = pageCount,
            pageNumbersEnabled = pageNumbersEnabled,
            deletedPages = deletedPages,
            insertedPages = insertedPages,
            pageOrderTokens = pageOrderTokens,
            pageMarkups = pageMarkups,
            isDraft = true
        )
    }

    fun clearDraft(context: Context, resumeUri: Uri) {
        draftFile(context, resumeUri.toString()).delete()
    }

    private fun serializePageMarkups(pageMarkups: Map<Int, PageMarkupState>): JSONArray {
        return JSONArray().apply {
            pageMarkups.toSortedMap().forEach { (pageIndex, state) ->
                put(
                    JSONObject().apply {
                        put(KEY_PAGE_INDEX, pageIndex)
                        put(KEY_OPERATIONS, serializeOperations(state.operations))
                    }
                )
            }
        }
    }

    private fun serializeOperations(operations: List<MarkupOperation>): JSONArray {
        return JSONArray().apply {
            operations.forEach { operation ->
                put(
                    when (operation) {
                        is MarkupOperation.Stroke -> JSONObject().apply {
                            put(KEY_TYPE, TYPE_STROKE)
                            put(KEY_COLOR, operation.color)
                            put(KEY_WIDTH_RATIO, operation.widthRatio.toDouble())
                            put(
                                KEY_POINTS,
                                JSONArray().apply {
                                    operation.points.forEach { point ->
                                        put(
                                            JSONObject().apply {
                                                put(KEY_X, point.x.toDouble())
                                                put(KEY_Y, point.y.toDouble())
                                            }
                                        )
                                    }
                                }
                            )
                        }

                        is MarkupOperation.Text -> JSONObject().apply {
                            put(KEY_TYPE, TYPE_TEXT)
                            put(KEY_TEXT, operation.text)
                            put(KEY_X_RATIO, operation.xRatio.toDouble())
                            put(KEY_Y_RATIO, operation.yRatio.toDouble())
                            put(KEY_COLOR, operation.color)
                            put(KEY_TEXT_SIZE_RATIO, operation.textSizeRatio.toDouble())
                            put(KEY_IS_BOLD, operation.isBold)
                        }

                        is MarkupOperation.Link -> JSONObject().apply {
                            put(KEY_TYPE, TYPE_LINK)
                            put(KEY_TEXT, operation.text)
                            put(KEY_URL, operation.url)
                            put(KEY_X_RATIO, operation.xRatio.toDouble())
                            put(KEY_Y_RATIO, operation.yRatio.toDouble())
                            put(KEY_COLOR, operation.color)
                            put(KEY_TEXT_SIZE_RATIO, operation.textSizeRatio.toDouble())
                            put(KEY_IS_BOLD, operation.isBold)
                        }

                        is MarkupOperation.Image -> JSONObject().apply {
                            put(KEY_TYPE, TYPE_IMAGE)
                            put(KEY_BYTES, Base64.encodeToString(operation.bytes, Base64.NO_WRAP))
                            put(KEY_X_RATIO, operation.xRatio.toDouble())
                            put(KEY_Y_RATIO, operation.yRatio.toDouble())
                            put(KEY_WIDTH_RATIO, operation.widthRatio.toDouble())
                            put(KEY_HEIGHT_RATIO, operation.heightRatio.toDouble())
                        }

                        is MarkupOperation.Cover -> JSONObject().apply {
                            put(KEY_TYPE, TYPE_COVER)
                            put(KEY_X_RATIO, operation.xRatio.toDouble())
                            put(KEY_Y_RATIO, operation.yRatio.toDouble())
                            put(KEY_WIDTH_RATIO, operation.widthRatio.toDouble())
                            put(KEY_HEIGHT_RATIO, operation.heightRatio.toDouble())
                            put(KEY_COLOR, operation.color)
                        }
                    }
                )
            }
        }
    }

    private fun serializeInsertedPages(insertedPages: List<InsertedPageState>): JSONArray {
        return JSONArray().apply {
            insertedPages.forEach { page ->
                put(
                    JSONObject().apply {
                        put(KEY_ID, page.id)
                        put(KEY_BYTES, Base64.encodeToString(page.bytes, Base64.NO_WRAP))
                        put(KEY_SOURCE_NAME, page.sourceName)
                        put(KEY_POSITION, page.position)
                    }
                )
            }
        }
    }

    private fun deserializeInsertedPages(rawInsertedPages: JSONArray?): List<InsertedPageState> {
        if (rawInsertedPages == null) {
            return emptyList()
        }

        return buildList {
            for (index in 0 until rawInsertedPages.length()) {
                val pageObject = rawInsertedPages.optJSONObject(index) ?: continue
                val bytes = runCatching {
                    Base64.decode(pageObject.optString(KEY_BYTES), Base64.DEFAULT)
                }.getOrNull() ?: continue
                add(
                    InsertedPageState(
                        id = pageObject.optString(KEY_ID).ifBlank { java.util.UUID.randomUUID().toString() },
                        bytes = bytes,
                        sourceName = pageObject.optString(KEY_SOURCE_NAME),
                        position = pageObject.optInt(KEY_POSITION, Int.MAX_VALUE)
                    )
                )
            }
        }
    }

    private fun serializePageOrderTokens(pageOrderTokens: List<String>): JSONArray {
        return JSONArray().apply {
            pageOrderTokens.forEach { token ->
                put(token)
            }
        }
    }

    private fun deserializeOperations(rawOperations: JSONArray?): MutableList<MarkupOperation> {
        if (rawOperations == null) {
            return mutableListOf()
        }

        return mutableListOf<MarkupOperation>().apply {
            for (index in 0 until rawOperations.length()) {
                val operation = rawOperations.optJSONObject(index) ?: continue
                when (operation.optString(KEY_TYPE)) {
                    TYPE_STROKE -> {
                        val points = mutableListOf<PointF>()
                        val rawPoints = operation.optJSONArray(KEY_POINTS) ?: JSONArray()
                        for (pointIndex in 0 until rawPoints.length()) {
                            val point = rawPoints.optJSONObject(pointIndex) ?: continue
                            points += PointF(
                                point.optDouble(KEY_X, 0.0).toFloat(),
                                point.optDouble(KEY_Y, 0.0).toFloat()
                            )
                        }
                        add(
                            MarkupOperation.Stroke(
                                points = points,
                                color = operation.optInt(
                                    KEY_COLOR,
                                    MarkupOperation.DEFAULT_STROKE_COLOR
                                ),
                                widthRatio = operation.optDouble(
                                    KEY_WIDTH_RATIO,
                                    MarkupOperation.DEFAULT_STROKE_WIDTH_RATIO.toDouble()
                                ).toFloat()
                            )
                        )
                    }

                    TYPE_TEXT -> {
                        add(
                            MarkupOperation.Text(
                                text = operation.optString(KEY_TEXT),
                                xRatio = operation.optDouble(KEY_X_RATIO, 0.12).toFloat(),
                                yRatio = operation.optDouble(KEY_Y_RATIO, 0.18).toFloat(),
                                color = operation.optInt(
                                    KEY_COLOR,
                                    MarkupOperation.DEFAULT_TEXT_COLOR
                                ),
                                textSizeRatio = operation.optDouble(
                                    KEY_TEXT_SIZE_RATIO,
                                    MarkupOperation.DEFAULT_TEXT_SIZE_RATIO.toDouble()
                                ).toFloat(),
                                isBold = operation.optBoolean(KEY_IS_BOLD, false)
                            )
                        )
                    }

                    TYPE_LINK -> {
                        add(
                            MarkupOperation.Link(
                                text = operation.optString(KEY_TEXT),
                                url = operation.optString(KEY_URL),
                                xRatio = operation.optDouble(KEY_X_RATIO, 0.12).toFloat(),
                                yRatio = operation.optDouble(KEY_Y_RATIO, 0.18).toFloat(),
                                color = operation.optInt(
                                    KEY_COLOR,
                                    MarkupOperation.DEFAULT_LINK_COLOR
                                ),
                                textSizeRatio = operation.optDouble(
                                    KEY_TEXT_SIZE_RATIO,
                                    MarkupOperation.DEFAULT_TEXT_SIZE_RATIO.toDouble()
                                ).toFloat(),
                                isBold = operation.optBoolean(KEY_IS_BOLD, false)
                            )
                        )
                    }

                    TYPE_IMAGE -> {
                        val bytes = runCatching {
                            Base64.decode(operation.optString(KEY_BYTES), Base64.DEFAULT)
                        }.getOrNull() ?: continue
                        add(
                            MarkupOperation.Image(
                                bytes = bytes,
                                xRatio = operation.optDouble(KEY_X_RATIO, 0.12).toFloat(),
                                yRatio = operation.optDouble(KEY_Y_RATIO, 0.12).toFloat(),
                                widthRatio = operation.optDouble(
                                    KEY_WIDTH_RATIO,
                                    MarkupOperation.DEFAULT_IMAGE_WIDTH_RATIO.toDouble()
                                ).toFloat(),
                                heightRatio = operation.optDouble(
                                    KEY_HEIGHT_RATIO,
                                    MarkupOperation.DEFAULT_IMAGE_HEIGHT_RATIO.toDouble()
                                ).toFloat()
                            )
                        )
                    }

                    TYPE_COVER -> {
                        add(
                            MarkupOperation.Cover(
                                xRatio = operation.optDouble(KEY_X_RATIO, 0.12).toFloat(),
                                yRatio = operation.optDouble(KEY_Y_RATIO, 0.18).toFloat(),
                                widthRatio = operation.optDouble(
                                    KEY_WIDTH_RATIO,
                                    MarkupOperation.DEFAULT_COVER_WIDTH_RATIO.toDouble()
                                ).toFloat(),
                                heightRatio = operation.optDouble(
                                    KEY_HEIGHT_RATIO,
                                    MarkupOperation.DEFAULT_COVER_HEIGHT_RATIO.toDouble()
                                ).toFloat(),
                                color = operation.optInt(
                                    KEY_COLOR,
                                    MarkupOperation.DEFAULT_WHITEOUT_COLOR
                                )
                            )
                        )
                    }
                }
            }
        }
    }

    private fun readProjectFile(
        file: File,
        fallbackResumeUriString: String? = null,
        fallbackIsDraft: Boolean = false
    ): VisualEditProject? {
        if (!file.exists()) {
            return null
        }

        return runCatching {
            val root = JSONObject(file.readText())
            val sourceUriString = root.optString(KEY_SOURCE_URI)
            if (sourceUriString.isBlank()) {
                return null
            }

            val deletedPages = buildSet {
                val rawDeletedPages = root.optJSONArray(KEY_DELETED_PAGES) ?: JSONArray()
                for (index in 0 until rawDeletedPages.length()) {
                    add(rawDeletedPages.optInt(index, -1))
                }
            }.filter { it >= 0 }.toSet()

            val pageMarkups = mutableMapOf<Int, PageMarkupState>()
            val rawPages = root.optJSONArray(KEY_PAGE_MARKUPS) ?: JSONArray()
            for (index in 0 until rawPages.length()) {
                val pageObject = rawPages.optJSONObject(index) ?: continue
                val pageIndex = pageObject.optInt(KEY_PAGE_INDEX, -1)
                if (pageIndex < 0) {
                    continue
                }
                pageMarkups[pageIndex] = PageMarkupState(
                    operations = deserializeOperations(pageObject.optJSONArray(KEY_OPERATIONS))
                )
            }

            val pageOrderTokens = buildList {
                val rawPageOrder = root.optJSONArray(KEY_PAGE_ORDER) ?: JSONArray()
                for (index in 0 until rawPageOrder.length()) {
                    rawPageOrder.optString(index)
                        .takeIf { it.isNotBlank() }
                        ?.let(::add)
                }
            }

            val entryKind = root.optString(KEY_ENTRY_KIND).ifBlank {
                if (fallbackIsDraft) KIND_DRAFT else KIND_PROJECT
            }
            val resumeUriString = root.optString(KEY_RESUME_URI).ifBlank {
                fallbackResumeUriString.orEmpty()
            }

            VisualEditProject(
                sourceUriString = sourceUriString,
                pageNumbersEnabled = root.optBoolean(KEY_PAGE_NUMBERS_ENABLED, false),
                deletedPages = deletedPages,
                insertedPages = deserializeInsertedPages(root.optJSONArray(KEY_INSERTED_PAGES)),
                pageOrderTokens = pageOrderTokens,
                pageMarkups = pageMarkups,
                resumeUriString = resumeUriString,
                resumeDisplayName = root.optString(KEY_RESUME_DISPLAY_NAME),
                sourceDisplayName = root.optString(KEY_SOURCE_DISPLAY_NAME),
                updatedAt = root.optLong(KEY_UPDATED_AT, file.lastModified()),
                pageCount = root.optInt(KEY_PAGE_COUNT, 0),
                isDraft = entryKind == KIND_DRAFT
            )
        }.getOrNull()
    }

    private fun buildResumeItem(project: VisualEditProject): ResumeItem? {
        if (project.resumeUriString.isBlank()) {
            return null
        }
        return ResumeItem(
            resumeUriString = project.resumeUriString,
            sourceUriString = project.sourceUriString,
            resumeDisplayName = project.resumeDisplayName.ifBlank {
                fallbackDisplayName(project.resumeUriString)
            },
            sourceDisplayName = project.sourceDisplayName.ifBlank {
                fallbackDisplayName(project.sourceUriString)
            },
            updatedAt = project.updatedAt,
            pageCount = project.pageCount,
            changedPageCount = project.changedPageCount,
            operationCount = project.operationCount,
            isDraft = project.isDraft
        )
    }

    private fun writeProjectFile(
        file: File,
        resumeUri: Uri,
        sourceUri: Uri,
        resumeDisplayName: String,
        sourceDisplayName: String,
        pageCount: Int,
        pageNumbersEnabled: Boolean,
        deletedPages: Set<Int>,
        insertedPages: List<InsertedPageState>,
        pageOrderTokens: List<String>,
        pageMarkups: Map<Int, PageMarkupState>,
        isDraft: Boolean
    ) {
        file.parentFile?.mkdirs()
        file.writeText(
            JSONObject().apply {
                put(KEY_ENTRY_KIND, if (isDraft) KIND_DRAFT else KIND_PROJECT)
                put(KEY_RESUME_URI, resumeUri.toString())
                put(KEY_SOURCE_URI, sourceUri.toString())
                put(KEY_RESUME_DISPLAY_NAME, resumeDisplayName)
                put(KEY_SOURCE_DISPLAY_NAME, sourceDisplayName)
                put(KEY_UPDATED_AT, System.currentTimeMillis())
                put(KEY_PAGE_COUNT, pageCount)
                put(KEY_PAGE_NUMBERS_ENABLED, pageNumbersEnabled)
                put(
                    KEY_DELETED_PAGES,
                    JSONArray().apply {
                        deletedPages.toSortedSet().forEach { pageIndex ->
                            put(pageIndex)
                        }
                    }
                )
                put(KEY_INSERTED_PAGES, serializeInsertedPages(insertedPages))
                put(KEY_PAGE_ORDER, serializePageOrderTokens(pageOrderTokens))
                put(KEY_PAGE_MARKUPS, serializePageMarkups(pageMarkups))
            }.toString()
        )
    }

    private fun projectFile(context: Context, editedUriString: String): File {
        return File(
            File(context.filesDir, PROJECTS_DIRECTORY),
            "${sha256(editedUriString)}.json"
        )
    }

    private fun draftFile(context: Context, resumeUriString: String): File {
        return File(
            File(context.filesDir, PROJECTS_DIRECTORY),
            "draft_${sha256(resumeUriString)}.json"
        )
    }

    private fun fallbackDisplayName(uriString: String): String {
        return runCatching {
            Uri.parse(uriString).lastPathSegment.orEmpty().ifBlank { uriString }
        }.getOrDefault(uriString)
    }

    private fun sha256(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return buildString(bytes.size * 2) {
            bytes.forEach { byte ->
                append("%02x".format(byte))
            }
        }
    }

    private const val PROJECTS_DIRECTORY = "visual_edit_projects"
    private const val KEY_ENTRY_KIND = "entry_kind"
    private const val KEY_RESUME_URI = "resume_uri"
    private const val KEY_SOURCE_URI = "source_uri"
    private const val KEY_RESUME_DISPLAY_NAME = "resume_display_name"
    private const val KEY_SOURCE_DISPLAY_NAME = "source_display_name"
    private const val KEY_UPDATED_AT = "updated_at"
    private const val KEY_PAGE_COUNT = "page_count"
    private const val KEY_PAGE_NUMBERS_ENABLED = "page_numbers_enabled"
    private const val KEY_DELETED_PAGES = "deleted_pages"
    private const val KEY_INSERTED_PAGES = "inserted_pages"
    private const val KEY_PAGE_ORDER = "page_order"
    private const val KEY_PAGE_MARKUPS = "page_markups"
    private const val KEY_PAGE_INDEX = "page_index"
    private const val KEY_OPERATIONS = "operations"
    private const val KEY_TYPE = "type"
    private const val KEY_POINTS = "points"
    private const val KEY_X = "x"
    private const val KEY_Y = "y"
    private const val KEY_COLOR = "color"
    private const val KEY_WIDTH_RATIO = "width_ratio"
    private const val KEY_HEIGHT_RATIO = "height_ratio"
    private const val KEY_TEXT = "text"
    private const val KEY_URL = "url"
    private const val KEY_X_RATIO = "x_ratio"
    private const val KEY_Y_RATIO = "y_ratio"
    private const val KEY_ID = "id"
    private const val KEY_SOURCE_NAME = "source_name"
    private const val KEY_POSITION = "position"
    private const val KEY_TEXT_SIZE_RATIO = "text_size_ratio"
    private const val KEY_IS_BOLD = "is_bold"
    private const val KEY_BYTES = "bytes"
    private const val TYPE_STROKE = "stroke"
    private const val TYPE_TEXT = "text"
    private const val TYPE_LINK = "link"
    private const val TYPE_IMAGE = "image"
    private const val TYPE_COVER = "cover"
    private const val KIND_PROJECT = "project"
    private const val KIND_DRAFT = "draft"
    private const val DEFAULT_RESUME_ITEMS = 4
}
