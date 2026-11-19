# DocReader

DocReader is an offline native Android app for opening PDF, DOCX, and XLSX files.
It uses Android's Storage Access Framework for file selection and saving; it does
not request broad storage access or network access.

## Features

- **PDF:** PDFium rendering, fit-to-width startup, continuous vertical scrolling,
  pinch zoom, password entry for encrypted PDFs, page counters, Share, and Download.
- **DOCX:** continuous WebView rendering with document formatting and embedded
  images, scrolling, Share, Export to PDF, and Download.
- **XLSX:** worksheet tabs, cell formatting, merged cells, frozen panes, cached
  formula results, bounded row/column/cell parsing, scrolling, pinch zoom, Share,
  Export to PDF, and Download.
- **Recent documents:** persisted URI history, ordered by last opened, with recovery
  when a file is missing or its access grant has expired.
- **Appearance:** Light, Dark, and System Default modes, saved across app restarts.
- **Open With:** PDF, DOCX, and XLSX document intents are declared in the manifest.

Downloads copy the original document bytes to the location selected in Android's
Save As picker. DOCX and XLSX PDF exports render the content shown by this app, so
their output can differ from Microsoft Word or Excel. The app explains format
limitations before export.

## Known limitations

- DOCX pagination and conversion use the bundled `docx-preview` renderer and may
  differ from Microsoft Word. Some advanced Word features such as text boxes,
  SmartArt, tracked changes, footnotes, comments, and embedded fonts may be absent
  or rendered differently. Vertical table cell merges are not honored.
- XLSX formulas are not recalculated; the workbook's saved cached values are shown.
  Charts, pivot tables, images, macros, external links, data validation, and
  conditional formatting are not rendered. Parsing is capped at a 100 MB input,
  100,000 rows, 512 columns, and 300,000 populated cells per sheet.
- The alternative Android `PdfRenderer` backend remains in source for development
  comparison, but PDFium is the app's default and the Home Screen has no engine
  selector.
- No emulator or physical-device result is implied by this README. See the audit
  report for the current verification record.

## Requirements

The build configuration currently targets the Android SDK installed on the
maintainer's machine:

| Tool | Configured version |
|---|---|
| JDK | 21 |
| Gradle wrapper | 9.9.1 |
| Android Gradle Plugin | 9.5.1 |
| Kotlin | 2.4.21 |
| Compile SDK | 37 |
| Build Tools | 36.0.0 |
| Minimum SDK | 24 |
| Target SDK | 36 |

Install the configured Android SDK platforms and build tools, then set the local
SDK path in `local.properties` (that file is machine-specific and ignored by Git).

## Build and checks

On Windows:

```powershell
.\gradlew.bat :app:assembleDebug :app:lintDebug :app:testDebugUnitTest
```

On macOS or Linux:

```bash
./gradlew :app:assembleDebug :app:lintDebug :app:testDebugUnitTest
```

Debug APKs are generated under `app/build/outputs/apk/debug/`; Gradle creates
per-ABI APKs and a universal APK. The arm64 installable APK is named
`app-arm64-v8a-debug.apk`.

## Architecture

```text
MainActivity.kt             SAF opening, routing, document lifecycle
ui/OpenScreen.kt            Home Screen, recent list, appearance settings
ui/ViewerScreen.kt           PDF toolbar and PDF viewer container
ui/DocxScreen.kt             DOCX WebView, teardown, export actions
ui/XlsxScreen.kt             XLSX tabs, grid, export actions
ui/DocumentActions.kt        Share, Save As, and export menus
ui/Theme.kt                  Persisted Material 3 light/dark theme
viewer/PdfViewerState.kt     PDF layout, zoom, scrolling, render scheduling
viewer/PdfCanvas.kt          PDF canvas and pinch gesture handling
pdf/                         PDFium and platform renderer implementations
xlsx/                        Bounded OOXML parser, canvas renderer, PDF output
export/DocumentExport.kt     SAF copying, sharing, and DOCX/PDF export pipeline
```

The DOCX preview scripts are bundled locally in `app/src/main/assets/docx/` and
loaded without network access.
