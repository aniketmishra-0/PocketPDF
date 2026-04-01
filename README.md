# PDF ZIP & Compress

This Android app lets you:

- choose a PDF from your phone
- open the PDF offline inside the app
- run a preflight check to inspect page sizes and orientation
- remove first pages, last pages, or selected pages
- convert the remaining pages into numbered PNG, JPG, or JPEG images
- save those images inside one ZIP file
- create a smaller offline PDF copy of the selected pages
- normalize compressed pages to one target size with fit or stretch modes

## Build

1. Install Android Studio with the Android SDK.
2. Install JDK 17 if Android Studio does not provide it automatically.
3. Open this folder in Android Studio.
4. Let Gradle sync.
5. Build the APK from the `app` module.

## Notes

- The app uses Android's built-in `PdfRenderer`, so it does not need extra storage permissions.
- PDF viewing, ZIP export, and compression all work fully offline.
- ZIP export supports PNG, JPG, and JPEG output.
- PDF compression works fully offline and rebuilds the output as an image-based PDF.
- Preflight lets you inspect page sizes and apply page-size normalization to compressed PDFs.
