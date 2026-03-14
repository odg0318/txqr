# SKILLS.md - Build Processes

This document covers all build processes for the txqr project, with emphasis on Docker-based builds for Android and cross-platform compilation.

## Overview

The txqr project uses multiple build systems:
- **Go** for desktop binaries and CLI tools
- **Docker** for Android builds (SDK/NDK requirements)
- **GitHub Actions** for CI/CD and releases
- **gomobile** for iOS frameworks and Android AAR

## Make Targets

### Desktop Builds

```bash
make gomobile    # Build iOS framework via gomobile
```

### Android Builds

```bash
make aar           # Build Android AAR library
make apk           # Build Android APK
make android       # Build both AAR and APK
make clean-android # Clean Android artifacts and Docker cache
```

### Release

```bash
make release VERSION=1.0.0  # Create GitHub release with APK
```

## Docker Build Methods

### IMPORTANT: Platform Requirement

**Android builds MUST use `--platform=linux/amd64`** due to SDK/NDK availability constraints. The Android SDK and NDK are only available for Linux x86_64 architecture.

### Multi-Stage Dockerfile.android

The project uses a sophisticated multi-stage Docker setup:

#### Stage 1: base

Common toolchain setup shared by all Android builds.

```dockerfile
FROM golang:1.25-bookworm AS base
```

**Components:**
- Go 1.25
- Android SDK (installed to `/opt/android-sdk`)
- Android NDK 26.1.10909125
- Java 17
- Build tools (unzip, wget, etc.)

#### Stage 2: aar-builder

Builds the Android AAR library using gomobile.

```bash
docker build --platform=linux/amd64 \
  -f Dockerfile.android \
  --target aar-builder \
  -t txqr-aar .
```

**What it does:**
- Runs `gomobile bind -target=android/androidar`
- Targets Android API 21+
- Outputs: `/output/txqr.aar`

**Extract AAR:**
```bash
docker run --rm -v "$(PWD):/output" txqr-aar \
  sh -c "cp /output/txqr.aar /output/"
```

#### Stage 3: apk-builder

Builds the Android debug APK.

```bash
docker build --platform=linux/amd64 \
  -f Dockerfile.android \
  --target apk-builder \
  -t txqr-apk .
```

**What it does:**
- Copies AAR from previous stage
- Sets up Gradle 8.4
- Builds Android debug APK with Gradle
- Outputs: `/output/txqr.apk`

**Extract APK:**
```bash
docker run --rm -v "$(PWD):/output" txqr-apk \
  sh -c "cp /output/txqr.apk /output/"
```

### Docker Build Examples

#### Build APK (most common)

```bash
# One-liner for complete APK build
docker buildx build --platform=linux/amd64 \
  -f Dockerfile.android \
  --target apk-builder \
  --output type=local,dest=./build \
  .

# APK will be at: build/output/txqr.apk
```

#### Build AAR only

```bash
docker buildx build --platform=linux/amd64 \
  -f Dockerfile.android \
  --target aar-builder \
  --output type=local,dest=./build \
  .
```

## Cross-Platform Builds

### GitHub Actions Matrix

The `.github/workflows/release.yml` builds for multiple platforms:

| Platform | Architecture | Output Format |
|----------|-------------|---------------|
| macOS | arm64 | tar.gz |
| macOS | amd64 | tar.gz |
| Linux | amd64 | tar.gz |
| Linux | arm64 | tar.gz |
| Windows | amd64 | zip |

### Manual Cross-Platform Build

```bash
# Build for macOS Apple Silicon
CGO_ENABLED=0 GOOS=darwin GOARCH=arm64 \
  go build -ldflags \
  "-X main.Version=1.0.0 \
   -X main.Commit=$(git rev-parse --short HEAD) \
   -X main.BuildDate=$(date -u +'%Y-%m-%dT%H:%M:%SZ')" \
  -o txqr-darwin-arm64 ./cmd/txqr

# Build for Linux AMD64
CGO_ENABLED=0 GOOS=linux GOARCH=amd64 \
  go build -ldflags "..." \
  -o txqr-linux-amd64 ./cmd/txqr
```

## Environment Variables

### Build Variables

| Variable | Purpose | Default |
|----------|---------|---------|
| `DOCKER_PLATFORM` | Platform for Android builds | `--platform=linux/amd64` |
| `VERSION` | Version string for releases | (required) |
| `CGO_ENABLED` | Enable CGO for static binaries | 0 |

### Android Build Variables (in Dockerfile)

| Variable | Value | Purpose |
|----------|-------|---------|
| `ANDROID_HOME` | `/opt/android-sdk` | Android SDK location |
| `ANDROID_NDK_HOME` | `/opt/android-sdk/ndk/26.1.10909125` | NDK location |
| `GRADLE_HOME` | `/opt/gradle-8.4` | Gradle installation |

## Version Injection

The CLI uses ldflags to inject version information at build time:

### Build Command

```bash
go build -ldflags \
  "-X main.Version=1.0.0 \
   -X main.Commit=abc123 \
   -X main.BuildDate=2024-01-01T00:00:00Z" \
  -o txqr ./cmd/txqr
```

### In version.go

```go
var (
    Version   = "dev"       // Overridden by ldflags
    Commit    = "unknown"   // Overridden by ldflags
    BuildDate = "unknown"   // Overridden by ldflags
)
```

### GitHub Actions Implementation

The workflow extracts version from git tags:

```yaml
- name: Extract version info
  id: version
  run: |
    VERSION=${GITHUB_REF#refs/tags/v}
    echo "VERSION=$VERSION" >> $GITHUB_OUTPUT
    COMMIT=$(git rev-parse --short HEAD)
    echo "COMMIT=$COMMIT" >> $GITHUB_OUTPUT
    echo "BUILD_DATE=$(date -u +"%Y-%m-%dT%H:%M:%SZ")" >> $GITHUB_OUTPUT

- name: Build
  run: |
    CGO_ENABLED=0 GOOS=${{ matrix.goos }} GOARCH=${{ matrix.goarch }} \
    go build -ldflags \
    "-X main.Version=${{ steps.version.outputs.VERSION }} \
     -X main.Commit=${{ steps.version.outputs.COMMIT }} \
     -X main.BuildDate=${{ steps.version.outputs.BUILD_DATE }}" \
    -o txqr${{ matrix.ext }} ./cmd/txqr
```

## CI/CD Workflow

### Trigger

Push to `v*` tags (e.g., `v1.0.0`)

### Jobs

#### 1. release

Multi-platform binary builds for desktop:
- macOS (arm64, amd64)
- Linux (amd64, arm64)
- Windows (amd64)

#### 2. android

Docker-based APK build with layer caching:
- Uses Docker Buildx for cross-platform builds
- Caches Docker layers at `/tmp/.buildx-cache`
- Speeds up subsequent builds

#### 3. create-release

Combines all artifacts and creates GitHub release with:
- Desktop binaries
- Android APK
- Comprehensive release notes

## Android Build Details

### Requirements

- Docker with `linux/amd64` platform emulation
- 4GB+ Docker memory recommended
- Go 1.25

### Gradle Configuration

| Setting | Value |
|---------|-------|
| Version | 8.4 |
| Min SDK | 21 |
| Compile SDK | 34 |
| Package | `com.github.divan.txqr` |

### Dependencies

- ZXing for QR scanning
- ZXing for QR generation (QRCodeWriter)
- AndroidX AppCompat
- Glide 4.16.0 for image display
- txqr.aar (gomobile binding)

### Android App Features

The Android app (`com.github.divan.txqr`) provides:

1. **QR Scanning**: Scan animated QR codes with fountain code decoding
2. **Data Preview**: View decoded text content
3. **File Operations**: Download decoded files, copy to clipboard
4. **Relay Mode**: Re-transmit scanned QR codes to another device
   - Stores raw QR chunks during scanning
   - Generates QR codes on-demand using ZXing
   - Frame-by-frame animation at 5 FPS
   - No re-encoding needed - true fountain code relay

### Build Output

**AAR:** `txqr.aar` - Android library for embedding
**APK:** `txqr.apk` - Standalone Android application

## Troubleshooting

### Android build fails

**Symptoms:** Build errors during Docker build

**Solutions:**
- Ensure `--platform=linux/amd64` is set
- Verify Docker has enough memory (4GB+)
- Check SDK/NDK version compatibility

### Gomobile issues

**Symptoms:** Errors during `gomobile bind`

**Solutions:**
- Ensure Go 1.25 is installed
- Verify $ANDROID_HOME is set correctly
- Try `gomobile clean` before rebuilding

### APK install issues

**Symptoms:** Cannot install APK on device

**Solutions:**
- Enable "Unknown sources" on Android device
- Verify minimum API level: 21 (Android 5.0+)
- Check device compatibility with architecture

### Docker platform errors

**Symptoms:** "no matching manifest for linux/amd64" or similar

**Solutions:**
- **macOS:** Ensure Docker Desktop has platform emulation enabled (Rosetta for Apple Silicon)
- **Linux:** Install qemu-user-static for arm emulation:
  ```bash
  sudo apt-get install qemu-user-static
  ```

### Build artifact extraction issues

**Symptoms:** Cannot copy files from Docker container

**Solutions:**
- Use volume mounts: `-v "$(PWD):/output"`
- Ensure output directory exists in container
- Use `docker run --rm` to clean up after extraction

## Quick Reference

### Build CLI (Desktop)

```bash
go build -o txqr ./cmd/txqr
```

### Build APK (Android)

```bash
make apk
# Or manually:
docker buildx build --platform=linux/amd64 \
  -f Dockerfile.android --target apk-builder \
  --output type=local,dest=./build .
```

### Install APK on Device

```bash
# Uninstall existing version (if any)
adb uninstall com.github.divan.txqr

# Install new APK
adb install txqr.apk

# Or one-liner (uninstall then install)
adb uninstall com.github.divan.txqr 2>/dev/null; adb install txqr.apk
```

### Build Error Handling

**IMPORTANT**: If APK or AAR build fails during `make apk` or `make aar`:
1. **STOP the build process immediately**
2. **DO NOT attempt to retry or fix automatically**
3. **Report the full error message to the user**
4. Wait for user instructions before proceeding

This is critical because build failures may indicate:
- Dependency issues requiring manual intervention
- Docker platform problems
- SDK/NDK version mismatches
- Gomobile binding errors

### Create Release

```bash
git tag v1.0.0
git push origin v1.0.0
# GitHub Actions will handle the rest
```

### Local Development

```bash
# Build and test CLI
go build -o txqr ./cmd/txqr
./txqr write test.txt

# Test terminal mode
./txqr write test.txt

# Test GIF mode
./txqr write -o out.gif test.txt
```

### Android App Usage

**Scan QR Code:**
1. Open app, grant camera permission
2. Point camera at animated QR code
3. Wait for decoding to complete (shows progress bar)
4. View decoded content, download, or copy

**Relay QR Code:**
1. Scan a QR code first (as above)
2. Click "Relay" button
3. App displays animated QR frames
4. Point another camera/device at the screen
5. The receiver can decode the relayed QR

This is useful for:
- Extending range of QR transmission
- Relay through multiple devices
- Overcoming line-of-sight limitations
