# CHDBOY

CHDBOY converts disc images (BIN/CUE, GDI, ISO) into compressed CHD archives on Android. CHD (Compressed Hunks of Data) is a lossless format that shrinks a disc image substantially while preserving it byte for byte, so your game backups take far less space with no loss of data.

> **Status:** Google Play submission candidate. The app is currently free. If you enjoy it, please consider supporting future releases when they arrive on the Play Store.

## Highlights

- **Purpose-built CHD writer:** A native CHD v5 encoder written for this app, not a port of a desktop command-line tool. Hunks are compressed in parallel across every core, identical hunks are stored once, and zstd does the compressing.
- **Nothing is copied:** Source and output both live in the folder you pick, reached through Storage Access Framework descriptors. A 40 GB image needs 40 GB of free space, not 80, and the conversion starts immediately instead of after two full copies.
- **Real CD support:** `.cue` and `.gdi` produce proper CD-format CHDs with per-track metadata, subcode separation, and recomputable ECC dropped — the same layout `chdman createcd` writes, verified against libchdr.
- **Duplicate CHD protection:** Images that already have a matching `.chd` in the folder are skipped.
- **Background-friendly:** Accept notification permissions so conversions can finish even when the app is closed.

## Getting Started

1. Install the APK or build from source (see below).
2. Use the Storage Access Framework picker to select the folder that contains your BIN/CUE, GDI or ISO files (no legacy storage permission required).
3. Every convertible image in that folder is queued; the finished `.chd` files are written back into the same folder.
4. Wait for the notification when compression finishes. Large images can take a while, so let the app run in the background or rely on notifications.

## Building from Source

The converter is built from source, so the submodules are required:

```bash
git clone --recurse-submodules https://github.com/izzy2lost/CHDBOY
./gradlew assembleRelease
```

Artifacts appear in `app/build/outputs/apk/`. A single universal APK is produced, holding `arm64-v8a` and `armeabi-v7a`.

Building the native converter needs NDK `30.0.15729638` and CMake `3.30.3`, both installable through the SDK manager.

## Compatibility

CHDs are written with zstd by default, which chdman learned to read in 0.264. Recent DuckStation, Flycast, PCSX2 and PPSSPP all read it. To target older tooling, flip `PORTABLE` in [`Converter.kt`](app/src/main/java/com/izzy2lost/chdboy/core/Converter.kt) to write Deflate instead — larger and slower, but readable by every CHD implementation ever shipped.

## Documentation & Policies

- [Project Website & Privacy Policy](https://izzy2lost.github.io/CHDBOY/)
- [GitHub Issues](https://github.com/izzy2lost/CHDBOY/issues)

## Attribution

- Originally forked from [Pipetto-crypto/Chdman](https://github.com/Pipetto-crypto/Chdman)
- The CHD format, and the CD track layout this writer reproduces, are from the [MAME project](https://github.com/mamedev/mame) (© MAMEdev)
- [libchdr](https://github.com/rtissera/libchdr) (BSD-3-Clause) provides the reference decoder and the CD-ROM ECC routines
- [zstd](https://github.com/facebook/zstd) (BSD / GPLv2) provides the compressor

## License

This project is released under the terms of the [GNU General Public License v2](LICENSE). See the LICENSE file for full details.

---

<sub>(c) 2025 izzy2lost. Consider supporting future CHDBOY releases on Google Play.</sub>
