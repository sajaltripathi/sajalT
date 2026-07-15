# sajalT

**An offline, privacy-first document, PDF, and image converter.**

sajalT converts between images, PDF, and DOCX, and compresses PDFs and images to a target size —
entirely on your device. It has no `INTERNET` permission, contains no networking code, and
cannot upload, sync, or transmit anything, by construction, not by policy.

This repository currently contains the **Android app** (complete, buildable). The **Windows
desktop app is planned as a follow-up** and is not yet in this repository — see
["Windows desktop app"](#windows-desktop-app-planned) below for the current status and plan.

---

## Table of contents

- [Features](#features)
- [Privacy policy](#privacy-policy)
- [Security architecture](#security-architecture)
- [Project structure](#project-structure)
- [Toolchain & dependency choices](#toolchain--dependency-choices)
- [Building the Android app](#building-the-android-app)
- [Signing the release build](#signing-the-release-build)
- [OCR: offline details & setup](#ocr-offline-details--setup)
- [Known limitations](#known-limitations)
- [Windows desktop app (planned)](#windows-desktop-app-planned)
- [Uploading this project to GitHub](#uploading-this-project-to-github)
- [Running GitHub Actions & downloading the APK](#running-github-actions--downloading-the-apk)
- [Verification checklist](#verification-checklist)
- [Third-party licenses](#third-party-licenses)

---

## Features

1. **Image(s) → PDF** — pick one or more images with a single picker, reorder them as pages
   (move up/down/remove, with live thumbnails), then combine them into one PDF. Every page is
   sized to match its source image's own aspect ratio; pixel data is embedded at full source
   resolution with no intentional downscaling or recompression, including for very large photos
   (see [Known limitations](#known-limitations) for the memory-safety mechanism behind this).
2. **DOCX → PDF** — headings, paragraphs, run formatting (bold/italic/underline/strikethrough/
   color), bulleted/numbered lists, inline images, tables (including horizontal cell merge),
   tracked-change insertions/deletions, and comments (collected into a trailing section).
3. **PDF → DOCX** — an editable, approximated reconstruction: text with a heading heuristic,
   simple tables, inline images, and full-page images for scanned/image-only pages.
4. **PDF compression** — a one-tap ~100 KB preset, or a custom target in KB/MB/GB. Recompresses
   embedded images (the usual dominant contributor to PDF size) via quality search, falling back
   to resolution reduction only if quality reduction alone cannot reach a very aggressive target.
5. **Image compression** — the same ~100 KB preset / custom target, via JPEG quality binary
   search with a resolution-reduction fallback for very small targets.
6. **Offline OCR** — on-device text recognition (Tesseract) for a picked image or a specific PDF
   page. See [OCR: offline details & setup](#ocr-offline-details--setup).

All six features are reachable from the home screen as clearly labeled cards, each showing a
Select button, a clearly-named action button, and success/error status text.

## Privacy policy

**Your files never leave this device. There is no way for them to.**

- sajalT has **no `INTERNET` permission** in its manifest and **no networking library** in its
  dependency graph (no Retrofit/OkHttp/Volley/Ktor client, no Firebase, no analytics, ads, or
  crash-reporting SDK of any kind). A phone with sajalT installed cannot make this app talk to
  the network, at the operating-system permission level — this is enforced by Android, not by
  sajalT's own good behavior.
- All file access is through the **Storage Access Framework** (system Open/Save dialogs). sajalT
  only ever touches a file the user explicitly picked; it never scans storage, never reads files
  in the background, and requests no storage permission (`READ_EXTERNAL_STORAGE`,
  `WRITE_EXTERNAL_STORAGE`, `MANAGE_EXTERNAL_STORAGE` are all absent, and are not needed on API
  26+ for SAF-based access).
- Conversions happen **in memory, in the foreground**, only while you are actively using a
  screen. Nothing is written to app-private storage as a persistent cache of your documents.
- Backups and device-to-device transfer of app data are **disabled** (`android:allowBackup=
  "false"` plus a `dataExtractionRules` policy excluding the entire app-data domain from both
  cloud backup and Android's "Copy your data" transfer flow).
- **Temporary files**: some steps are technically difficult to do with zero scratch data (see the
  itemized list below). Every case is minimized, confined to this app's own private cache
  directory (never external/shared storage), and cleaned up immediately after the operation —
  both proactively (a `finally` block after every conversion) and as a fallback (the cache
  directory is also wiped once on app start, in case a previous run was killed mid-operation).
  The specific cases:
  - **PDFBox scratch files**: PDFBox is configured to keep up to 64 MB of working data in RAM
    and only spill beyond that to a scratch file, for very large PDFs, to avoid
    `OutOfMemoryError`. See `core/pdf/PdfDocumentSource.kt`.
  - **OCR language data**: a user-selected `.traineddata` file is copied once into this app's
    private storage so OCR does not require re-picking it every time. This is OCR *engine/model*
    data, not your document content — see [OCR: offline details & setup](#ocr-offline-details--setup)
    for why this is a deliberate, disclosed exception to "no persistent caching," and how to
    remove it from the OCR screen at any time.
- OCR (if you use it) is a 100%-local native library call. See the dedicated section below.

## Security architecture

| Requirement | How it's satisfied |
|---|---|
| No `INTERNET` permission | Not declared anywhere in `AndroidManifest.xml` — verify with `grep -r "INTERNET" android/app/src/main/AndroidManifest.xml` (no match). |
| No storage permissions | `READ_EXTERNAL_STORAGE` / `WRITE_EXTERNAL_STORAGE` / `MANAGE_EXTERNAL_STORAGE` are not declared; all access is via SAF pickers. |
| No networking libraries | `app/build.gradle`'s dependency list contains exactly two non-AndroidX libraries (PdfBox-Android, Tesseract4Android), neither of which is a network client. |
| No Firebase/analytics/ads/crash-reporting | None present in `app/build.gradle`. |
| SAF picker/save dialog only | `ActivityResultContracts.OpenDocument` / `OpenMultipleDocuments` / `CreateDocument` throughout `ui/*Activity.kt`. |
| Offline processing only | All conversion logic lives under `core/` and operates on already-opened streams/bitmaps in memory. |
| Backup disabled | `android:allowBackup="false"` in the manifest. |
| Device transfer/data extraction disabled | `res/xml/data_extraction_rules.xml` excludes `domain="root"` from both `cloud-backup` and `device-transfer`. |
| Release minification enabled | `buildTypes.release { minifyEnabled true; shrinkResources true }` in `app/build.gradle`, with `proguard-rules.pro`. |
| OCR fully offline/on-device | Tesseract4Android (native libtesseract via JNI); see below. |
| No cloud OCR | N/A — no cloud OCR API is referenced anywhere in the codebase. |
| No runtime OCR model download | Language data is either placed in `assets/tessdata/` at build time by a developer, or picked once from local storage by the user — never fetched over a network (there is no network to fetch it over). |
| No permanent OCR cache of user content | Only the language *model* file is cached (see Privacy policy above); recognized text, input images, and any OCR-internal temp data are not persisted. |

Two additional, defense-in-depth measures beyond what was strictly required:

- **`network_security_config.xml`** denies all cleartext traffic and trusts no certificate
  authority. Redundant given there's no `INTERNET` permission to begin with, but makes the "no
  networking, verified two independent ways" claim literally true rather than merely implied.
- **`StrictMode`** is armed in debug builds (`SajalTApplication.kt`) to crash immediately on any
  accidental network/disk-on-main-thread violation, so a future contributor who accidentally adds
  networking code finds out in the debugger, not in a privacy audit.

## Project structure

```text
sajalT/
  android/
    settings.gradle
    build.gradle
    gradle.properties
    .gitignore
    app/
      build.gradle
      proguard-rules.pro
      src/main/AndroidManifest.xml
      src/main/java/com/sajalt/converter/
        SajalTApplication.kt
        ui/                    # 7 activities + the reorderable-list adapter
        core/
          pdf/                 # Image->PDF (native PdfDocument), PDF compression, PDF text/page
                                # extraction and rasterization (PdfBox-Android)
          docx/                # Hand-built OOXML reader/writer, DOCX->PDF layout engine,
                                # PDF->DOCX heuristic reconstruction
          image/                # Bitmap utilities, image compression
          ocr/                  # Tesseract4Android wrapper
          util/                 # Size parsing, SAF helpers, XML escaping, temp-file cleanup
      src/main/res/             # layouts, strings, colors, themes, hand-authored vector icons
      src/main/assets/tessdata/ # empty by default — see OCR section
  .github/workflows/build-apk.yml
  README.md                     # this file
```

## Toolchain & dependency choices

Pinned deliberately, each for a stated reason — nothing here is an arbitrary or default choice:

- **Android Gradle Plugin 8.7.3 / Kotlin 2.0.21 / Gradle 8.9.** AGP 9.x exists as of mid-2026
  with a new "built-in Kotlin" DSL model that changes how the Kotlin plugin is applied. This
  project stays on the well-established, thoroughly-documented 8.x line for build reliability.
  Upgrading later is an isolated Gradle-file change that does not touch any Kotlin source.
- **`com.tom-roush:pdfbox-android:2.0.27.0`** (Apache 2.0, Maven Central) — the standard offline
  Android port of Apache PDFBox. Justification: writing a compliant PDF object/xref parser from
  scratch is not a reasonable scope for an app like this; PDFBox is the well-audited, standard
  choice, and every byte it processes stays on-device.
- **`cz.adaptech.tesseract4android:tesseract4android:4.9.0`** (Apache 2.0, via JitPack) — a
  maintained, actively-updated wrapper around native libtesseract. **JitPack is a build-time
  artifact host only** (like Maven Central) — it is where Gradle fetches the *precompiled
  library* from while you build the app on your own machine or in CI. It has nothing to do with
  what the compiled APK does at runtime; the APK itself has no networking code or permission.
- **Nothing else beyond first-party AndroidX/Material libraries.** In particular, **DOCX reading
  and writing use no third-party library at all** — just `java.util.zip` and `XmlPullParser`,
  both built into every Android device since API 1. Apache POI (the obvious alternative) has
  genuinely uncertain Android compatibility (it references several `java.awt.*` classes that
  plain Android does not provide) and pulls in a large schema jar for what is, underneath, just
  XML inside a zip file. A small, purpose-built reader/writer against exactly the OOXML tags this
  app needs is more code than "just add POI," but removes that whole category of risk and keeps
  every processing step auditable in this codebase — see `core/docx/DocxParser.kt` and
  `DocxWriter.kt` for the full reasoning in context.
- **No committed `gradle-wrapper.jar`.** A wrapper jar is a compiled binary; committing one to a
  privacy/security-focused repo means an auditor has to trust a blob they can't read. Instead,
  CI provisions Gradle 8.9 directly via `gradle/actions/setup-gradle`. For local development,
  run `gradle wrapper` once (with any Gradle 8.9+ install) to generate your own wrapper if you'd
  prefer `./gradlew` — it's `.gitignore`'d so it won't get committed by accident.

**A note on build verification.** This project was written and reviewed carefully, including
tracing through Android/Kotlin API contracts by hand (see inline comments throughout, especially
in `BitmapUtils.kt`, `PdfCompressor.kt`, and `DocxParser.kt`, for the specific correctness issues
that reasoning caught and fixed before you ever saw this code). It was **not**, however, compiled
against the real Android SDK and the real PdfBox-Android/Tesseract4Android jars before being
handed to you — the environment this was generated in has no network access and no Android SDK
installed. The GitHub Actions workflow in this repo (or a local Android Studio sync) will be the
first real compile. If it surfaces an error, the three most likely spots, in order of likelihood,
are:
1. `PdfCompressor.kt` — `PDResources.put(COSName, PDXObject)` and
   `JPEGFactory.createFromImage(PDDocument, Bitmap, Float)`: the exact method signatures were not
   verifiable against the pinned jar from this environment.
2. `PdfTextExtractor.kt` — the `writeString(String, MutableList<TextPosition>)` override
   signature against `PDFTextStripper`.
3. `PdfDocumentSource.kt` — `MemoryUsageSetting.setupMixed(long).setTempDir(File)`; the safe
   fallback if this signature has drifted is `PDDocument.load(inputStream)` with no second
   argument.

Any of these would show up as a clear, specific compiler error at that exact line — not a subtle
runtime bug — and Android Studio's "fix imports"/quick-fix tooling resolves this class of issue
in seconds once the real dependency is on the classpath.

## Building the Android app

**Prerequisites:** JDK 17, Android SDK (compileSdk/targetSdk 34), and either Android Studio
(Ladybug or newer recommended) or a standalone Gradle 8.9+ install.

### Android Studio
1. `File → Open`, select the `android/` folder.
2. Let Gradle sync (this downloads AGP, Kotlin, and the two third-party libraries — this step
   needs network access on your machine, which is unrelated to the compiled app's own runtime
   networking, or lack thereof).
3. `Run ▶` on a device/emulator running API 26+, or `Build → Build Bundle(s)/APK(s) → Build APK(s)`.

### Command line
```bash
cd android
gradle assembleDebug          # -> app/build/outputs/apk/debug/app-debug.apk
gradle assembleRelease        # -> app/build/outputs/apk/release/app-release.apk (unsigned unless configured — see below)
```
(If you generated a wrapper with `gradle wrapper`, use `./gradlew` instead of `gradle` above.)

## Signing the release build

An unsigned release APK is a normal result of the steps above — Android just won't let you
*install* an unsigned APK on a real device without either signing it or using
`adb install -r` in specific debug scenarios. To produce an installable, signed release build:

**Locally:** set four properties (`SAJALT_RELEASE_STORE_FILE`, `_STORE_PASSWORD`, `_KEY_ALIAS`,
`_KEY_PASSWORD`) in `android/gradle.properties` (commented-out template already there) or as
`-P` command line flags, then run `gradle assembleRelease` again.

**In GitHub Actions:** the included workflow signs automatically if you add these secrets to
your repo (`Settings → Secrets and variables → Actions → New repository secret`):
- `SAJALT_RELEASE_KEYSTORE_BASE64` — your keystore file, base64-encoded: run
  `base64 -i your-release.keystore` (macOS/Linux) or `certutil -encode your-release.keystore tmp.b64`
  (Windows) and paste the resulting text as the secret value.
- `SAJALT_RELEASE_STORE_PASSWORD`, `SAJALT_RELEASE_KEY_ALIAS`, `SAJALT_RELEASE_KEY_PASSWORD`.

Don't have a keystore yet? Generate one with:
```bash
keytool -genkeypair -v -keystore release.keystore -alias sajalt -keyalg RSA -keysize 2048 -validity 10000
```
Keep this file and its passwords safe — losing them means you can never publish an update to an
app that used them for its first release.

## OCR: offline details & setup

OCR uses [Tesseract4Android](https://github.com/adaptech-cz/Tesseract4Android) (Apache 2.0), a
JNI wrapper around the native Tesseract OCR engine. Recognition happens as a single synchronous
native library call on the bitmap you provide — there is no code path that could send that image
or its extracted text anywhere.

**Language data is not bundled in this repository** (`assets/tessdata/` is present but
intentionally empty). A `.traineddata` file is tens of megabytes of binary model weights — it
isn't something to fabricate, and bundling one by default would substantially bloat the APK for
every user regardless of whether they use OCR. You have two options, both spec-compliant ("OCR
models/language data must be bundled with the app or loaded from a user-selected local file"):

1. **Bundle at build time**: download a language file (e.g. `eng.traineddata`) from the
   [official tessdata repository](https://github.com/tesseract-ocr/tessdata) — use the *fast* or
   standard variant, not `tessdata_best`, for reasonable APK size and speed on mobile — and place
   it at `android/app/src/main/assets/tessdata/eng.traineddata` before building.
2. **User-selected at runtime** (works out of the box, no build changes needed): open the OCR
   screen, tap "Select Language Data," and pick a `.traineddata` file via the system file picker.
   sajalT copies it once into its own private storage (`context.filesDir/tessdata/`) so you don't
   have to re-pick it every time — this is OCR *model* data, not your document content, which is
   why this one case is a deliberate, disclosed exception to "no persistent caching" (see
   [Privacy policy](#privacy-policy)). Tap "Remove Language Data" on the same screen at any time
   to delete it.

Either way, once language data is present, OCR works with the device fully in airplane mode.

## Known limitations

Stated plainly, as requested, rather than glossed over. None of these are bugs to be fixed later
in the sense of "this is broken" — they are documented scope boundaries of an offline, from-first-
principles implementation, several of which the original spec explicitly anticipated
("where feasible," "if perfect ... is technically impossible offline, clearly state the
limitation").

**DOCX → PDF**
- Headings are recognized by Word's standard `Heading1`–`Heading4`/`Title` style IDs only;
  custom/renamed styles are not detected.
- List markers are rendered as a generic bullet, not the source document's exact numbering
  scheme (roman numerals, custom formats, etc.).
- Table cell content is flattened to styled text (bold/italic/underline/strikethrough survive);
  an image or nested table *inside* a cell does not render.
- Vertically merged table cells render as blank continuation cells rather than one cell with a
  true spanning border.
- A paragraph or table row is never split across a page boundary — an unusually long single
  paragraph may extend past the bottom margin on its page rather than being cut across two.
- Hyperlinks keep their text and visual styling but are not clickable in the output PDF (Android's
  `PdfDocument` API has no link-annotation support).
- Comments are collected into a trailing "Comments" section rather than appearing as true PDF
  margin annotations (same underlying API limitation).
- Headers, footers, footnotes, endnotes, and embedded OLE objects (e.g. an embedded Excel range)
  are not extracted.

**PDF → DOCX**
- This is a **heuristic reconstruction**, not a lossless recovery — PDF has no semantic concept
  of "heading" or "table," only text positioned at coordinates. Headings are inferred from
  relative font size; tables are inferred from consistently multi-space-aligned text columns
  across 2+ consecutive lines. Both work well on typically-formatted documents and can both miss
  true structure and occasionally false-positive on unusual layouts.
- Merged cells, multi-column layouts, headers/footers, footnotes, and embedded objects are not
  reconstructed — none of these exist as recoverable metadata in a plain PDF.
- Pages with little extractable text are treated as scanned and embedded as a full-page image
  (matching the spec's own required fallback) — if that page in fact had a little real text and a
  lot of decorative graphics, that text is not extracted; use the OCR feature on that page instead
  if you need it as editable text.

**Image → PDF**
- Extremely large images are decoded and drawn in bounded-memory tiles rather than one full
  decode, to avoid `OutOfMemoryError` — see `BitmapUtils.kt`. This preserves every source pixel
  (no downscaling) but is more code than a naive single-decode approach; it is also the only
  correct way to honor "do not intentionally downscale" for modern 50–200 MP camera photos on a
  memory-constrained mobile device.
- Mirrored EXIF orientations (as opposed to 90°/180°/270° rotation) are treated as unrotated —
  true mirroring is rare in camera output.

**Compression (both PDF and image)**
- A very aggressive target on a very large/detailed source may not be reachable with acceptable
  quality; the closest achievable size is always returned rather than silently failing, and the
  UI tells you when the exact target wasn't hit.
- A PDF with little or no embedded image content has limited further compressibility without
  rasterizing pages (turning selectable text into an image) — this app does not do that, since it
  would be a much bigger fidelity trade-off than recompressing photos, so very text-heavy PDFs may
  not compress much further no matter the target.
- Compressing an image with transparency requires converting to JPEG (which has no alpha
  channel); transparent areas become white. The app warns about this in the result message.

**General**
- Legacy binary `.doc` (pre-2007 Word format) is not supported — only `.docx` (Office Open XML).

## Windows desktop app (planned)

The Windows desktop project (`windows/`) is **not yet included in this repository**. Building a
correct, genuinely offline .NET/WPF equivalent — with its own DOCX/PDF engine choices, its own
project file, and its own honestly-documented limitations — is a substantial second project in a
different language and toolchain, and rushing it alongside the Android app in the same pass would
mean lower quality on both. It is next, as a dedicated follow-up.

Planned approach, so this section isn't empty: .NET 8 + WPF; Open XML SDK (Microsoft's own,
MIT-licensed) for reading/writing `.docx` directly, mirroring the same block model this Android
app uses; a permissively-licensed PDF library (PDFsharp/MigraDoc) for PDF creation and
compression, with the same "recompress embedded images toward a target size" strategy as the
Android `PdfCompressor`; the same zero-network, zero-telemetry, SAF-equivalent (`OpenFileDialog`/
`SaveFileDialog`-only) constraints throughout.

## Uploading this project to GitHub

1. [Create a new repository](https://github.com/new) on GitHub (public or private, your choice;
   don't initialize it with a README/`.gitignore`/license, since this project already has one).
2. On your machine, in the folder containing this `sajalT/` directory:
   ```bash
   cd sajalT
   git init
   git add .
   git commit -m "Initial commit: sajalT Android app"
   git branch -M main
   git remote add origin https://github.com/<your-username>/<your-repo-name>.git
   git push -u origin main
   ```
3. That's it — pushing to `main` (or opening a pull request into it) automatically triggers the
   included GitHub Actions workflow (see below).

## Running GitHub Actions & downloading the APK

The workflow at `.github/workflows/build-apk.yml` runs automatically on every push/PR that
touches the `android/` folder. To run it manually, or to download the result:

1. On GitHub, open your repository's **Actions** tab.
2. Select **Build Android APK** in the left sidebar.
3. To trigger it manually: click **Run workflow** (this works because the workflow includes a
   `workflow_dispatch` trigger) → **Run workflow** again to confirm.
4. Click into the run once it finishes (green check ✅).
5. Scroll to the **Artifacts** section at the bottom of the run summary page — you'll see
   `sajalT-debug-apk` and `sajalT-release-apk`. Click either to download a `.zip` containing the
   `.apk`.
6. Unzip it, transfer the `.apk` to an Android device (or `adb install path/to/app-debug.apk`),
   and install it — you may need to allow "install from this source" once, depending on your
   Android version.

## Verification checklist

- [x] No `INTERNET` permission anywhere in `AndroidManifest.xml`
- [x] No storage permissions (`READ_EXTERNAL_STORAGE` / `WRITE_EXTERNAL_STORAGE` /
      `MANAGE_EXTERNAL_STORAGE`) — SAF only
- [x] No networking libraries in `app/build.gradle`
- [x] No Firebase / analytics / ads SDKs
- [x] SAF picker (`OpenDocument`/`OpenMultipleDocuments`) and save dialog (`CreateDocument`) used
      throughout
- [x] Offline processing only — every conversion runs against already-opened local streams
- [x] Backup disabled (`android:allowBackup="false"`)
- [x] Device transfer / data extraction disabled (`data_extraction_rules.xml`)
- [x] Release minification enabled (`minifyEnabled true`, `shrinkResources true`, ProGuard rules)
- [x] OCR is fully offline/on-device (native Tesseract via JNI, no cloud API)
- [x] No cloud OCR
- [x] No runtime OCR model download (bundled at build time or picked locally by the user, never
      fetched over a network)
- [x] No permanent OCR cache of user content (only the language *model* file is cached, by
      design and disclosed — see Privacy policy)

## Third-party licenses

- **PdfBox-Android** — Apache License 2.0.
- **Tesseract4Android** (and the underlying Tesseract OCR engine / Leptonica) — Apache License 2.0.
- **AndroidX / Material Components** — Apache License 2.0.
- **Kotlin coroutines** — Apache License 2.0.

All are permissive and compatible with any distribution model for this app, including commercial
use, without a copyleft obligation on your own code.
