<p align="center">
  <img src="assets/logo.png" alt="ScanX" width="160">
</p>

# ScanX

An Android app that turns photos of paper into a clean PDF. Pick images from your gallery or shoot them with the camera, tidy up each page, and save everything as one file.

## What it does

- **Add pages** from the gallery (several at once) or the built-in camera. The camera outlines the page live and keeps shooting until you tap Done, so a whole stack is one go.
- **Edges are found for you.** OpenCV looks for the sheet of paper in each photo and straightens it. If it guesses wrong, drag the four corner brackets yourself, or tap *Detect edges* to try again.
- **Three looks:** Original, Greyscale, and B&W scan. B&W scan compares each pixel with its surroundings, so shadows and uneven light drop out and the paper goes white.
- **Rotate and reorder.** Each page editor has Rotate, and Earlier / Later to move the page within the scan.
- **Set every page at once** with the switch above the page grid, or change a single page in its editor. Pages you add later use whichever look you picked last.
- **Clear** removes every page from the current scan (after a confirmation). Delete a single page from its editor.
- **Save PDF** writes one page per scan to a location you choose, then offers Share and Open. Pages are JPEG-compressed, so files stay small.
- **Your scan is kept** if you close the app. Pages stay until you save or clear them.

## Get the app

The project builds on GitHub, so you don't need Android Studio.

1. Push this repo to GitHub.
2. Open the **Actions** tab and wait for the *Build APK* run to finish (or start it with **Run workflow**).
3. Download the `scanx-debug-apk` artifact from the run and unzip it.
4. Copy `app-debug.apk` to your phone and open it. Android will ask you to allow installs from your file manager or browser the first time.

Needs Android 9 (API 28) or newer. The camera asks for permission the first time; the gallery needs none.

## Project layout

```
assets/logo.png                       full-size logo
app/src/main/java/dev/scanx/
  MainActivity.kt                     home screen, page grid, saving
  Editor.kt                           page editor and the corner-bracket overlay
  Camera.kt                           live camera with edge outline (CameraX)
  Theme.kt                            colours, font, buttons, filter switch
  Scan.kt                             page model, perspective crop, PDF writer
  Detect.kt                           OpenCV edge detection and B&W threshold
app/src/main/res/
  drawable-nodpi/                     in-app logo and launcher icon artwork
  font/inter_tight.ttf                Inter Tight (SIL Open Font License)
.github/workflows/build.yml           CI that builds the debug APK
LICENSE                               MIT
licenses/                             license text for bundled third-party files
```

## How it works

- Each page keeps the original photo plus four crop corners and a filter. The photo is never modified, so you can change edges and looks as often as you like.
- The crop is a perspective warp (`Matrix.setPolyToPoly`) from the four corners onto a rectangle. Filters are colour matrices, so the editor previews them live.
- PDF pages are A4 width, with height following each scan's aspect ratio. The PDF is written by hand (one JPEG per page) instead of Android's built-in writer, which stores pixels uncompressed.
- The in-progress scan is a small text file (`pages.txt`) next to the photos in the app's private storage.

## Known limits

- Edge detection looks for the largest four-sided shape. A cluttered background or a page on a similar-coloured table can fool it.
- Pages are reordered with Earlier / Later buttons, not by dragging.
- B&W scan is pure black and white. Faint pencil or light-coloured text can drop out; use Greyscale for those.
- The CI build is a debug APK. A signed release build needs a keystore added to the workflow.

## License

ScanX is released under the [MIT License](LICENSE).

Third-party pieces keep their own licenses: Inter Tight is under the SIL Open Font License 1.1 ([text](licenses/InterTight-OFL.txt)), and OpenCV is under Apache 2.0.
