# WARP.md

This file provides guidance to WARP (warp.dev) when working with code in this repository.

## Project Overview
CHDBOY is an Android application that provides a mobile frontend for the MAME chdman tool. It allows users to compress and decompress CHD (Compressed Hunks of Data) files on Android devices. The app includes both Java/Kotlin Android code and native C++ libraries built from the MAME project.

## Essential Commands

### Build Commands
```powershell
# Clean build
.\gradlew clean

# Build debug APK
.\gradlew assembleDebug

# Build all variants (creates APKs for different architectures)
.\gradlew build

# Install debug build to connected device
.\gradlew installDebug

# Build and run tests
.\gradlew test
```

### Development Commands
```powershell
# Run unit tests
.\gradlew testDebugUnitTest

# Run instrumentation tests (requires connected device/emulator)
.\gradlew connectedAndroidTest

# Generate lint report
.\gradlew lintDebug

# Check dependencies
.\gradlew dependencies

# Clean and rebuild native libraries
.\gradlew clean externalNativeBuild
```

### NDK/Native Development
```powershell
# Build native libraries only
.\gradlew buildCMakeDebug

# Clean native build
.\gradlew cleanBuildCache
```

## Architecture Overview

### High-Level Structure
- **Android App Layer**: Standard Android application with Activities, Fragments, and utilities
- **JNI Bridge**: Connects Java code to native C++ chdboy implementation
- **Native Layer**: MAME-based chdboy tool compiled for Android ARM architectures

### Key Components

#### Android Components
- `com.chdboy.MainActivity`: Main activity with file picker and operation UI
- `com.chdboy.SettingsActivity`: Preferences and configuration
- `com.chdboy.fragments.PreferenceFragment`: Settings UI implementation
- `com.chdboy.utils.*`: Core utilities including file operations and native interfacing

#### Utils Package (`com.chdboy.utils`)
- `Chdman.java`: Main wrapper class for native chdboy operations, handles compression queue
- `FilePicker.java`: Android file picker implementation with SAF (Storage Access Framework) support  
- `MyFileProvider.java`: Custom file provider for handling file operations
- `Operations.java`: Manages pending operations and UI state
- `UriParser.java`: Handles Android URI parsing for file access

#### Native Libraries (C++)
- Built from MAME project source code
- Includes chdboy tool and all required dependencies (zlib, flac, expat, etc.)
- Targets ARM64-v8a and ARMv7a architectures
- Uses CMake build system defined in `CMakeLists.txt`

### Build Configuration
- **Package Name**: `com.chdboy`
- **App Name**: CHDBOY
- **Gradle**: Uses Android Gradle Plugin 8.1.1
- **Target SDK**: 28 (intentionally older for compatibility)
- **Min SDK**: 23 (Android 6.0+)
- **Compile SDK**: 34
- **NDK**: Version 27.0.12077973
- **ABI Splits**: Generates separate APKs for ARM64-v8a and ARMv7a

### Key Features
- CHD compression (createcd/createdvd modes)
- Batch processing support
- Material Design UI with dark/light theme support
- File transfer operations
- Progress tracking with cancellation support
- Storage permission handling
- Legacy external storage support for older Android versions

## Development Notes

### Native Dependencies
The app includes these compiled native libraries:
- **chdboy**: Core MAME chdman tool
- **utils**: MAME utility libraries  
- **ocore_sdl**: MAME OS abstraction layer
- **flac, zlib, expat, 7z, zstd**: Compression and utility libraries
- **utf8proc**: Unicode processing

### File Operations
- Uses Storage Access Framework (SAF) for modern Android file access
- Supports both internal and external storage
- Implements custom FileProvider for legacy compatibility
- Handles both single file and folder operations

### Threading Model
- UI operations on main thread
- File operations and native calls on background threads via ExecutorService
- Progress updates posted back to main thread via Handler

### Build Artifacts
- Universal APK plus architecture-specific APKs
- Debug builds include all debugging symbols
- Supports both 32-bit and 64-bit ARM architectures