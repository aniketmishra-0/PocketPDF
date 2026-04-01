const pdfjsLib = window["pdfjs-dist/build/pdf"];

pdfjsLib.GlobalWorkerOptions.workerSrc =
    "https://cdnjs.cloudflare.com/ajax/libs/pdf.js/3.11.174/pdf.worker.min.js";

const DEFAULT_JPEG_QUALITY = 82;
const ZIP_RENDER_SCALE = 2;
const ZIP_MAX_DIMENSION = 2200;
const COMPRESS_RENDER_SCALE = 1.25;
const COMPRESS_MAX_DIMENSION = 1600;
const COMPRESS_PDF_JPEG_QUALITY = 0.72;

const IMAGE_FORMATS = {
    PNG: {
        extension: "png",
        mimeType: "image/png",
        usesJpegQuality: false,
    },
    JPG: {
        extension: "jpg",
        mimeType: "image/jpeg",
        usesJpegQuality: true,
    },
    JPEG: {
        extension: "jpeg",
        mimeType: "image/jpeg",
        usesJpegQuality: true,
    },
};

const state = {
    selectedFile: null,
    selectedBytes: null,
    totalPages: null,
    isLoading: false,
};

const elements = {
    selectedPdfText: document.getElementById("selectedPdfText"),
    statusText: document.getElementById("statusText"),
    progressIndicator: document.getElementById("progressIndicator"),
    pageCountBadge: document.getElementById("pageCountBadge"),
    modeBadge: document.getElementById("modeBadge"),
    skipFirstInput: document.getElementById("skipFirstInput"),
    skipLastInput: document.getElementById("skipLastInput"),
    removePagesInput: document.getElementById("removePagesInput"),
    imageFormatInput: document.getElementById("imageFormatInput"),
    jpegQualityLayout: document.getElementById("jpegQualityLayout"),
    jpegQualityInput: document.getElementById("jpegQualityInput"),
    pdfFileInput: document.getElementById("pdfFileInput"),
    pickPdfButton: document.getElementById("pickPdfButton"),
    exportZipButton: document.getElementById("exportZipButton"),
    compressPdfButton: document.getElementById("compressPdfButton"),
};

bindEvents();
updateJpegQualityVisibility();
updateSelectedPdfState();
applyDefaultStatus();

function bindEvents() {
    elements.pickPdfButton.addEventListener("click", () => {
        if (!state.isLoading) {
            elements.pdfFileInput.click();
        }
    });

    elements.pdfFileInput.addEventListener("change", async (event) => {
        const [file] = event.target.files || [];
        if (!file) {
            return;
        }

        await loadSelectedPdf(file);
        event.target.value = "";
    });

    elements.imageFormatInput.addEventListener("change", () => {
        updateJpegQualityVisibility();
    });

    elements.exportZipButton.addEventListener("click", async () => {
        await performAction("zip");
    });

    elements.compressPdfButton.addEventListener("click", async () => {
        await performAction("compress");
    });
}

async function loadSelectedPdf(file) {
    setLoading(true, "Reading PDF...");
    try {
        const bytes = new Uint8Array(await file.arrayBuffer());
        const loadingTask = pdfjsLib.getDocument({ data: bytes.slice() });
        const documentRef = await loadingTask.promise;
        const totalPages = documentRef.numPages;
        await documentRef.destroy();

        state.selectedFile = file;
        state.selectedBytes = bytes;
        state.totalPages = totalPages;

        updateSelectedPdfState();
        setStatus(
            `PDF selected. ${totalPages} page${totalPages === 1 ? "" : "s"} ready for testing.`
        );
    } catch (error) {
        clearSelectedPdf();
        setStatus(errorMessage(error, "Could not open the selected PDF."));
    } finally {
        setLoading(false);
    }
}

async function performAction(action) {
    if (!state.selectedFile || !state.selectedBytes) {
        setStatus("Please choose a PDF first.");
        return;
    }

    let exportOptions;
    try {
        exportOptions = readExportOptions();
    } catch (error) {
        setStatus(error.message || "Please check the page removal fields.");
        return;
    }

    const actionLabel =
        action === "zip"
            ? "Creating ZIP and rendering pages..."
            : "Compressing PDF offline and rebuilding pages...";

    setLoading(true, actionLabel, action === "zip" ? "ZIP mode" : "Compress mode");

    let documentRef = null;
    try {
        const loadingTask = pdfjsLib.getDocument({ data: state.selectedBytes.slice() });
        documentRef = await loadingTask.promise;
        const pagesToProcess = buildPagesToProcess(documentRef.numPages, exportOptions);

        if (action === "zip") {
            const zipBlob = await exportPdfToZip(documentRef, pagesToProcess, exportOptions);
            const zipName = buildZipFileName(state.selectedFile.name);
            await saveBlob(zipBlob, zipName, "application/zip");
            setStatus(`ZIP created successfully with ${pagesToProcess.length} pages.`);
        } else {
            const compressedBlob = await compressPdf(documentRef, pagesToProcess);
            const compressedName = buildCompressedPdfFileName(state.selectedFile.name);
            await saveBlob(compressedBlob, compressedName, "application/pdf");
            setStatus(
                buildCompressionMessage(
                    pagesToProcess.length,
                    state.selectedFile.size,
                    compressedBlob.size
                )
            );
        }
    } catch (error) {
        setStatus(
            action === "zip"
                ? errorMessage(error, "Export failed.")
                : errorMessage(error, "Compression failed.")
        );
    } finally {
        if (documentRef) {
            await documentRef.destroy();
        }
        setLoading(false);
    }
}

function readExportOptions() {
    const skipFromStart = parseNonNegativeInt(
        elements.skipFirstInput.value,
        "Remove first pages"
    );
    const skipFromEnd = parseNonNegativeInt(
        elements.skipLastInput.value,
        "Remove last pages"
    );
    const removedPages = parsePageSelection(elements.removePagesInput.value);
    const imageFormat = parseImageFormat(elements.imageFormatInput.value);
    const jpegQuality = parseJpegQuality(elements.jpegQualityInput.value, imageFormat);

    return {
        skipFromStart,
        skipFromEnd,
        removedPages,
        imageFormat,
        jpegQuality,
    };
}

function parseNonNegativeInt(value, fieldName) {
    const trimmedValue = value.trim();
    if (!trimmedValue) {
        return 0;
    }

    const parsed = Number.parseInt(trimmedValue, 10);
    if (!Number.isFinite(parsed) || parsed < 0) {
        throw new Error(`Enter a valid number in ${fieldName}.`);
    }

    return parsed;
}

function parsePageSelection(value) {
    const trimmedValue = value.trim();
    if (!trimmedValue) {
        return new Set();
    }

    const pages = new Set();

    for (const token of trimmedValue.split(",")) {
        const part = token.trim();
        if (!part) {
            continue;
        }

        if (/^\d+$/.test(part)) {
            const page = Number.parseInt(part, 10);
            if (page <= 0) {
                throw new Error("Use page numbers like 1, 2, 8 or ranges like 10-12.");
            }
            pages.add(page);
            continue;
        }

        if (/^\d+\s*-\s*\d+$/.test(part)) {
            const [startRaw, endRaw] = part.split("-").map((segment) => segment.trim());
            const startPage = Number.parseInt(startRaw, 10);
            const endPage = Number.parseInt(endRaw, 10);

            if (startPage <= 0 || endPage <= 0 || startPage > endPage) {
                throw new Error("Use page numbers like 1, 2, 8 or ranges like 10-12.");
            }

            for (let page = startPage; page <= endPage; page += 1) {
                pages.add(page);
            }
            continue;
        }

        throw new Error("Use page numbers like 1, 2, 8 or ranges like 10-12.");
    }

    return pages;
}

function parseImageFormat(value) {
    const normalized = value.trim().toUpperCase();
    if (!normalized) {
        return "PNG";
    }
    if (!IMAGE_FORMATS[normalized]) {
        throw new Error("Choose a valid image format: PNG, JPG, or JPEG.");
    }
    return normalized;
}

function parseJpegQuality(value, imageFormat) {
    if (!IMAGE_FORMATS[imageFormat].usesJpegQuality) {
        return DEFAULT_JPEG_QUALITY;
    }

    const trimmedValue = value.trim();
    if (!trimmedValue) {
        return DEFAULT_JPEG_QUALITY;
    }

    const parsed = Number.parseInt(trimmedValue, 10);
    if (!Number.isFinite(parsed) || parsed < 1 || parsed > 100) {
        throw new Error("Enter JPG or JPEG quality between 1 and 100.");
    }

    return parsed;
}

function buildPagesToProcess(totalPages, exportOptions) {
    const invalidPages = [...exportOptions.removedPages].filter(
        (page) => page < 1 || page > totalPages
    );

    if (invalidPages.length > 0) {
        throw new Error(
            `These pages do not exist in the PDF: ${invalidPages.join(", ")}. PDF has ${totalPages} pages.`
        );
    }

    const excludedPages = new Set();

    for (let pageNumber = 1; pageNumber <= Math.min(exportOptions.skipFromStart, totalPages); pageNumber += 1) {
        excludedPages.add(pageNumber);
    }

    if (exportOptions.skipFromEnd > 0) {
        const startPage = Math.max(1, totalPages - exportOptions.skipFromEnd + 1);
        for (let pageNumber = startPage; pageNumber <= totalPages; pageNumber += 1) {
            excludedPages.add(pageNumber);
        }
    }

    for (const page of exportOptions.removedPages) {
        excludedPages.add(page);
    }

    const pagesToProcess = [];
    for (let pageNumber = 1; pageNumber <= totalPages; pageNumber += 1) {
        if (!excludedPages.has(pageNumber)) {
            pagesToProcess.push(pageNumber - 1);
        }
    }

    if (pagesToProcess.length === 0) {
        throw new Error("All pages were removed. Change the filters and try again.");
    }

    return pagesToProcess;
}

async function exportPdfToZip(documentRef, pagesToProcess, exportOptions) {
    const zip = new JSZip();
    const imageFormat = IMAGE_FORMATS[exportOptions.imageFormat];

    for (let exportedIndex = 0; exportedIndex < pagesToProcess.length; exportedIndex += 1) {
        const pageIndex = pagesToProcess[exportedIndex];
        setStatus(
            `Rendering page ${exportedIndex + 1} of ${pagesToProcess.length} for ZIP...`
        );
        const page = await documentRef.getPage(pageIndex + 1);
        const { canvas } = await renderPageToCanvas(
            page,
            ZIP_RENDER_SCALE,
            ZIP_MAX_DIMENSION
        );
        const blob = await canvasToBlob(
            canvas,
            imageFormat.mimeType,
            exportOptions.jpegQuality / 100
        );
        zip.file(`(${exportedIndex + 1}).${imageFormat.extension}`, blob);
    }

    return zip.generateAsync({ type: "blob" });
}

async function compressPdf(documentRef, pagesToProcess) {
    const pdfDoc = await PDFLib.PDFDocument.create();

    for (let outputPageIndex = 0; outputPageIndex < pagesToProcess.length; outputPageIndex += 1) {
        const pageIndex = pagesToProcess[outputPageIndex];
        setStatus(
            `Compressing page ${outputPageIndex + 1} of ${pagesToProcess.length}...`
        );
        const page = await documentRef.getPage(pageIndex + 1);
        const { canvas, baseViewport } = await renderPageToCanvas(
            page,
            COMPRESS_RENDER_SCALE,
            COMPRESS_MAX_DIMENSION
        );
        const blob = await canvasToBlob(canvas, "image/jpeg", COMPRESS_PDF_JPEG_QUALITY);
        const bytes = await blob.arrayBuffer();
        const jpgImage = await pdfDoc.embedJpg(bytes);
        const pdfPage = pdfDoc.addPage([baseViewport.width, baseViewport.height]);
        pdfPage.drawImage(jpgImage, {
            x: 0,
            y: 0,
            width: baseViewport.width,
            height: baseViewport.height,
        });
    }

    const pdfBytes = await pdfDoc.save();
    return new Blob([pdfBytes], { type: "application/pdf" });
}

async function renderPageToCanvas(page, preferredScale, maxDimension) {
    const baseViewport = page.getViewport({ scale: 1 });
    const renderScale = calculateRenderScale(
        baseViewport.width,
        baseViewport.height,
        preferredScale,
        maxDimension
    );
    const viewport = page.getViewport({ scale: renderScale });
    const canvas = document.createElement("canvas");
    const context = canvas.getContext("2d", { alpha: false });

    canvas.width = Math.max(1, Math.round(viewport.width));
    canvas.height = Math.max(1, Math.round(viewport.height));

    context.save();
    context.fillStyle = "#ffffff";
    context.fillRect(0, 0, canvas.width, canvas.height);
    context.restore();

    await page.render({
        canvasContext: context,
        viewport,
    }).promise;

    return { canvas, baseViewport };
}

function calculateRenderScale(pageWidth, pageHeight, preferredScale, maxDimension) {
    const currentMax = Math.max(pageWidth, pageHeight);
    const maxSafeScale = maxDimension / currentMax;
    return Math.max(1, Math.min(preferredScale, maxSafeScale));
}

function canvasToBlob(canvas, mimeType, quality) {
    return new Promise((resolve, reject) => {
        canvas.toBlob((blob) => {
            if (!blob) {
                reject(new Error("Unable to create image data from the PDF page."));
                return;
            }
            resolve(blob);
        }, mimeType, quality);
    });
}

async function saveBlob(blob, suggestedName, mimeType) {
    if (window.showSaveFilePicker) {
        try {
            const handle = await window.showSaveFilePicker({
                suggestedName,
                types: [
                    {
                        description: mimeType,
                        accept: { [mimeType]: [fileExtensionFromName(suggestedName)] },
                    },
                ],
            });
            const writable = await handle.createWritable();
            await writable.write(blob);
            await writable.close();
            return;
        } catch (error) {
            if (error && error.name === "AbortError") {
                throw new Error("Save was cancelled.");
            }
        }
    }

    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.download = suggestedName;
    document.body.appendChild(link);
    link.click();
    link.remove();

    setTimeout(() => URL.revokeObjectURL(url), 1000);
}

function fileExtensionFromName(name) {
    const dotIndex = name.lastIndexOf(".");
    return dotIndex >= 0 ? name.slice(dotIndex) : "";
}

function buildZipFileName(pdfName) {
    const baseName = (pdfName || "images").replace(/\.pdf$/i, "").trim() || "images";
    return `${baseName}_images.zip`;
}

function buildCompressedPdfFileName(pdfName) {
    const baseName = (pdfName || "document").replace(/\.pdf$/i, "").trim() || "document";
    return `${baseName}_compressed.pdf`;
}

function buildCompressionMessage(pageCount, originalSize, compressedSize) {
    if (originalSize > 0 && compressedSize > 0) {
        return `Compressed PDF saved with ${pageCount} pages. Size: ${formatFileSize(originalSize)} to ${formatFileSize(compressedSize)}.`;
    }
    return `Compressed PDF saved successfully with ${pageCount} pages.`;
}

function formatFileSize(sizeBytes) {
    return new Intl.NumberFormat("en", {
        notation: "compact",
        maximumFractionDigits: 1,
    }).format(sizeBytes) + "B";
}

function updateSelectedPdfState() {
    const hasPdf = Boolean(state.selectedFile);

    elements.exportZipButton.disabled = !hasPdf || state.isLoading;
    elements.compressPdfButton.disabled = !hasPdf || state.isLoading;
    elements.selectedPdfText.textContent = hasPdf
        ? `Selected PDF: ${state.selectedFile.name}`
        : "No PDF selected yet.";

    if (hasPdf && state.totalPages != null) {
        elements.pageCountBadge.textContent = `${state.totalPages} pages`;
        elements.pageCountBadge.classList.remove("hidden");
    } else {
        elements.pageCountBadge.classList.add("hidden");
        elements.pageCountBadge.textContent = "";
    }
}

function updateJpegQualityVisibility() {
    const imageFormat = parseImageFormat(elements.imageFormatInput.value);
    const showQuality = IMAGE_FORMATS[imageFormat].usesJpegQuality;
    elements.jpegQualityLayout.classList.toggle("hidden", !showQuality);
    elements.jpegQualityInput.disabled = !showQuality || state.isLoading;
}

function clearSelectedPdf() {
    state.selectedFile = null;
    state.selectedBytes = null;
    state.totalPages = null;
    updateSelectedPdfState();
    applyDefaultStatus();
}

function setLoading(isLoading, loadingMessage = "", modeLabel = "") {
    state.isLoading = isLoading;
    elements.progressIndicator.classList.toggle("hidden", !isLoading);
    elements.pickPdfButton.disabled = isLoading;
    elements.skipFirstInput.disabled = isLoading;
    elements.skipLastInput.disabled = isLoading;
    elements.removePagesInput.disabled = isLoading;
    elements.imageFormatInput.disabled = isLoading;
    updateJpegQualityVisibility();
    updateSelectedPdfState();

    if (isLoading && loadingMessage) {
        elements.statusText.textContent = loadingMessage;
    }

    if (modeLabel) {
        elements.modeBadge.textContent = modeLabel;
        elements.modeBadge.classList.remove("hidden");
    } else if (!isLoading) {
        elements.modeBadge.classList.add("hidden");
        elements.modeBadge.textContent = "";
    }
}

function setStatus(message) {
    elements.statusText.textContent = message;
}

function applyDefaultStatus() {
    elements.statusText.textContent = state.selectedFile
        ? "PDF selected. Set optional page filters below, then create a ZIP or compressed PDF."
        : "Choose a PDF to begin.";
}

function errorMessage(error, fallback) {
    if (error instanceof Error && error.message) {
        return error.message;
    }
    return fallback;
}
