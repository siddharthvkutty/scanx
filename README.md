<p align="center">
  <img src="assets/logo.png" alt="ScanX" width="160">
</p>

# ScanX

An Android app that turns photos of paper into a clean PDF. Pick images from your gallery or shoot them with the camera, tidy up each page, and save everything as one file.

## What it does

- **Add pages** from the gallery (several at once) or the camera.
- **Edges are found for you.** OpenCV looks for the sheet of paper in each photo and straightens it. If it guesses wrong, drag the four corner brackets yourself, or tap *Detect edges* to try again.
- **Three looks:** Original, Greyscale, and B&W scan (greyscale with extra contrast, so paper goes white).
- **Set every page at once** with the switch above the page grid, or change a single page in its editor. Pages you add later use whichever look you picked last.
- **Clear** removes every page from the current scan (after a confirmation). Delete a single page from its editor.
- **Save PDF** writes one page per scan to a location you choose. No permissions needed.

## Get the app

The project builds on GitHub, so you don't need Android Studio.

1. Push this repo to GitHub.
2. Open the **Actions** tab and wait for the *Build APK* run to finish (or start it with **Run workflow**).
3. Download the `scanx-debug-apk` artifact from the run and unzip it.
4. Copy `app-debug.apk` to your phone and open it. Android will ask you to allow installs from your file manager or browser the first time.

Needs Android 9 (API 28) or newer.

## Project layout

```
assets/logo.png                       full-size logo
app/src/main/java/dev/scanx/
  MainActivity.kt                     home screen and page editor
  Theme.kt                            colours, font, buttons, filter switch
  Scan.kt                             filters, perspective crop, PDF writer
  Detect.kt                           OpenCV edge detection
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
- PDF pages are A4 width, with height following each scan's aspect ratio.

## Known limits

- B&W scan is a contrast boost, not an adaptive threshold, so shadows and uneven lighting stay.
- Edge detection looks for the largest four-sided shape. A cluttered background or a page on a similar-coloured table can fool it.
- Android's built-in PDF writer makes large files, a few MB per page.
- Pages can't be reordered or rotated yet.
- The CI build is a debug APK. A signed release build needs a keystore added to the workflow.

## License

ScanX is released under the [MIT License](LICENSE).

Third-party pieces keep their own licenses: Inter Tight is under the SIL Open Font License 1.1 ([text](licenses/InterTight-OFL.txt)), and OpenCV is under Apache 2.0.
