# Pocket PDF

Pocket PDF is an offline-first Android PDF utility focused on fast reading, lightweight editing, ZIP export, cleanup, and compression.

## Highlights

- Open and read PDFs fully offline
- Visual PDF edit with text, drawing, highlights, and image overlays
- Resume previous edit sessions
- Export selected pages as JPG, JPEG, or PNG inside one ZIP
- Remove pages visually before export
- Compress PDFs offline
- Run preflight checks for page size and orientation issues

## Latest APK

The latest tracked APK is included in this repository:

- [PocketPDF.apk](./PocketPDF.apk)

## Project Structure

- [app/](./app): Android application source
- [browser-preview/](./browser-preview): lightweight preview assets
- [sample-reader-test.pdf](./sample-reader-test.pdf): sample file for local testing

## Build Locally

1. Install Android Studio with Android SDK support.
2. Use JDK 17.
3. Open this folder in Android Studio or use Gradle from the terminal.
4. Run `./gradlew assembleDebug`.

The debug APK will be generated at:

- `app/build/outputs/apk/debug/app-debug.apk`

## Contributing

Contributions are welcome. Please read:

- [CONTRIBUTING.md](./CONTRIBUTING.md)
- [CODE_OF_CONDUCT.md](./CODE_OF_CONDUCT.md)
- [SECURITY.md](./SECURITY.md)

## License

This project is licensed under the MIT License.

- [LICENSE](./LICENSE)
