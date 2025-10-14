# CHDBOY

CHDBOY is an Android port of the CHDMAN tool that converts disc images (BIN/CUE, ISO, etc.) into compressed CHD archives. CHD (Compressed Hunks of Data) is a lossless compression format that efficiently combines multiple files into a single compressed archive, significantly reducing storage requirements while preserving perfect quality of the original media. This app provides a mobile-friendly solution for compressing your game backups, saving valuable space on your device without any loss of data.

> **Status:** Google Play submission candidate. The app is currently free. If you enjoy it, please consider supporting future releases when they arrive on the Play Store.

## Highlights

- **Smart mode detection:** Automatically picks `createcd` or `createdvd` based on your source so you never guess the right command.
- **PSP-aware compression:** Uses the recommended hunk size for PSP ISOs to keep conversions compatible and efficient.
- **Duplicate CHD protection:** Existing CHDs with matching names are skipped to save time.
- **Background-friendly:** Accept notification permissions so conversions can finish even when the app is closed.
- **Lossless space savings:** CHD shrinks multi-file disc images into a single lossless archive.

## Getting Started

1. Install the APK or build from source (see below).
2. Use the Android Storage Access Framework picker to select the folder that contains your BIN/CUE/ISO files (no legacy storage permission required).
3. Confirm the files you want to convert.
4. Wait for the notification when compression finishes. Large ISOs can take a while, so let the app run in the background or rely on notifications.

## Building from Source

```bash
./gradlew assembleRelease
```

Artifacts will appear in `app/build/outputs/apk/`.

## Documentation & Policies

- [Project Website & Privacy Policy](https://izzy2lost.github.io/CHDBOY/)
- [GitHub Issues](https://github.com/izzy2lost/CHDBOY/issues)

## Attribution

- Forked from [Pipetto-crypto/Chdman](https://github.com/Pipetto-crypto/Chdman)
- Powered by **CHDMAN**, part of the [MAME project](https://github.com/mamedev/mame)

## License

This project is released under the terms of the [GNU General Public License v2](LICENSE). See the LICENSE file for full details, including the upstream MAME and CHDMAN licenses.

CHDBOY includes or interfaces with code from MAME ((c) MAMEdev). Refer to the upstream repositories for additional notices and acknowledgments.

---

<sub>(c) 2025 izzy2lost. Consider supporting future CHDBOY releases on Google Play.</sub>


