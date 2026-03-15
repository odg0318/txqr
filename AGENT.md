# AGENT.md - TXQR Project Context

This file provides comprehensive context for AI agents working on the txqr codebase.

**Quick Reference:**
- Latest session summary: [memory/MEMORY.md](memory/MEMORY.md)
- Build processes: [SKILLS.md](SKILLS.md)

## Project Overview

**TXQR (Transfer via QR)** is a data transfer protocol using animated QR codes with fountain codes for error correction. It allows robust data transmission even when some QR frames are lost or corrupted during transmission.

**Core Technology:** Luby Transform (LT) fountain codes enable receivers to reconstruct data even with 50%+ frame loss, without requiring ordered delivery.

**Platform Support:**
- Desktop (Go CLI)
- Android (Kotlin)
- iOS (Swift via gomobile bindings)

## Technical Stack

| Component | Technology | Version/Reference |
|-----------|-----------|-------------------|
| Go | 1.25 | Primary language |
| Fountain Codes | github.com/google/gofountain | Error correction |
| QR Generation | github.com/skip2/go-qrcode | Encoding |
| QR Decoding | github.com/makiuchi-d/gozxing | Decoding |
| QR Terminal | github.com/mdp/qrterminal | ASCII display |
| Android | Java + Gradle 8.4 | API 21+, SDK 34 |
| iOS | Swift via gomobile | Framework bindings |
| Web | GopherJS + Vecty | Testing interface |
| Docker | Multi-stage builds | Android toolchain |

## Directory Structure

```
txqr/
├── cmd/                        # Command-line tools and applications
│   ├── txqr/                   # Main CLI tool
│   │   ├── main.go             # Entry point with command routing
│   │   ├── write.go            # Write/encode command + GIF generation
│   │   ├── read.go             # Read/decode command + web server
│   │   ├── terminal.go         # Terminal QR display utilities
│   │   └── version.go          # Version info with ldflags injection
│   ├── txqr-ascii/             # ASCII QR display tool
│   ├── txqr-gif/               # GIF generation tool (legacy)
│   ├── txqr-reader/            # QR reader tool
│   └── txqr-tester/            # Automated testing web app
│       └── app/                # GopherJS/Vecty web components
├── android/                    # Android app (Kotlin)
│   └── app/                    # Gradle project structure
│       └── src/main/
│           └── java/com/github/divan/txqr/
├── mobile/                     # gomobile bindings for iOS/Android
│   ├── decode.go               # Mobile decoder with progress tracking
│   └── encode.go               # Mobile encoder with StringList wrapper
├── qr/                         # QR code generation/decoding library
│   ├── qr.go                   # QR encoding with error levels
│   └── testdata/               # Test QR images
├── docs/                       # Documentation
├── .github/workflows/          # CI/CD pipelines
└── output/                     # Build artifacts
```

## Key Components

### cmd/txqr/ - Main CLI Tool

**main.go** - Entry point (39 lines)
```go
// Command routing with aliases
switch os.Args[1] {
case "write", "w":  runWrite()
case "read", "r":   runRead()
case "version":     PrintVersion()
}
```

**write.go** - Write/encode functionality (125 lines)
- Default: Terminal QR display mode
- GIF output: Use `-o <file>` or `--gif` flag
- Supports file arguments and stdin pipes
- Contains `AnimatedGifFromChunks()` function

**read.go** - Read/decode functionality (647 lines)
- Web server for QR scanning
- WebSocket for real-time scanner updates
- HTML interface with camera access
- **New UI layout** (2024-03):
  - Scanner + Button panel in horizontal flex layout
  - Download, Reset, Preview, Copy buttons
  - Preview area shown below on demand
  - Responsive max-width: 900px

**terminal.go** - Terminal display utilities (38 lines)
```go
func displayTerminal(chunks []string, fps int)  // Loops QR codes
func clearScreen()                               // ANSI clear
```
- Uses qrterminal with BLACK/WHITE chars (fixed from inverted)
- QuietZone: 2 (increased for better recognition)

**version.go** - Version information (20 lines)
```go
var (
    Version   = "dev"       // Injected via ldflags
    Commit    = "unknown"   // Injected via ldflags
    BuildDate = "unknown"   // Injected via ldflags
)
```

### mobile/decode.go - Mobile Decoder

Progress tracking interface used by iOS/Android apps via gomobile bindings.

### qr/qr.go - QR Library

Four error recovery levels: L, M, Q, H
```go
type RecoveryLevel int
const (
    L RecoveryLevel = iota
    M
    Q
    H
)
```

## Architecture Patterns

### Data Encoding Flow (Simplified - Single Base64)

1. **Read data** from file or stdin
2. **Compress** with flate (BEST_COMPRESSION = level 9)
3. **Encode** as: `<filename>\n<compressed_data>`
4. **Base64 encode** once (simplified from double encoding)
5. **Split** into chunks with fountain codes
6. **Generate** QR code for each chunk

### Data Format

```
Original Data
    ↓ (flate compress, BEST_COMPRESSION)
Compressed Data
    ↓ (prepend filename)
<filename>\n<compressed_data>
    ↓ (base64 encode ONCE)
Base64-encoded payload
    ↓ (fountain encode + chunk split)
QR code chunks (animated display)
```

### Encoding Compatibility

**Cross-Platform Encoding:**
- **Go CLI**: Uses raw deflate (no zlib header) via `flate.NewWriter`
- **Android**: Uses raw deflate via `Deflater(level, true)` - nowrap=true
- **Character Encoding**: ISO-8859-1 for byte→String conversion (1:1 mapping)

**Key Points:**
- Raw deflate format (not zlib) for consistency
- Single base64 encoding (simplified from double)
- ISO-8859-1 preserves raw bytes (0-255 → 1:1 character mapping)
- BEST_COMPRESSION for smaller payloads (fewer QR frames)

### Error Correction

- **Algorithm:** Luby Transform (LT) fountain codes
- **Redundancy factor:** 2.0 (default)
- **Frame ordering:** Not required
- **Loss tolerance:** High (can recover from 50%+ loss)

### Fountain Code Chunk Format

Each chunk follows the format: `blockCode/chunkLen/total|data`

## Important Conventions

### Code Organization

- **Commands:** `cmd/<name>/` directories
- **Shared libraries:** Root level
- **Mobile code:** `mobile/` directory
- **Android native:** `android/` directory

### File Naming Patterns

- `main.go` - Entry points
- `write.go` - Write/encode functionality
- `read.go` - Read/decode functionality
- `version.go` - Version info
- `terminal.go` - Terminal display utilities

### Import Patterns

```go
import (
    "github.com/divan/txqr"      // Core package
    "github.com/divan/txqr/qr"   // QR library
)
```

## Common Development Tasks

### Build CLI

```bash
# Docker build (recommended - cross-platform)
make cli                    # Builds for darwin/arm64 (Apple Silicon)
make cli CLI_GOOS=darwin CLI_GOARCH=amd64   # Intel Mac
make cli CLI_GOOS=linux CLI_GOARCH=amd64    # Linux

# Local build (quick testing)
go build -o txqr ./cmd/txqr
```

### Test Commands

```bash
# Terminal display (default mode)
txqr write myfile.txt
txqr w myfile.txt              # Short alias

# GIF output
txqr write -o out.gif myfile.txt
txqr write --gif myfile.txt    # Creates out.gif

# Pipe input
echo 'hello' | txqr write
echo 'data' | txqr write -o out.gif

# Start QR reader web server
txqr read
txqr r                         # Short alias

# Show version
txqr version
```

### Command Options (write)

```
-o <file>     Output GIF file (enables GIF mode)
-gif          Generate GIF instead of terminal display
-split N      Chunk size per frame (default: 100)
-size N       QR code size for GIF (default: 300)
-fps N        Animation FPS (default: 5)
-name         Filename for stdin data (default: stdin)
```

## Recent Changes (Context for Future Work)

### Encoding Simplification (2024-03)
- **Single base64 encoding** (was double base64)
  - Reduces payload size by ~33%
  - Fewer QR frames needed
  - Format: `<filename>\n<compressed_data>` → base64 → fountain codes
- **Raw deflate format** (not zlib)
  - Go: `flate.NewWriter` (raw deflate by default)
  - Android: `Deflater(level, true)` - nowrap=true
- **BEST_COMPRESSION** for consistency
- **ISO-8859-1** character encoding for byte preservation

### CLI Docker Build System (2024-03)
- **Dockerfile** - Multi-platform CLI builds
  - ARG support for GOOS/GOARCH
  - Defaults: darwin/arm64 (Apple Silicon)
- **Makefile targets**:
  - `make cli` - Build CLI via Docker
  - `make aar` - Build Android AAR
  - `make apk` - Build Android APK
  - `make android` - Build both AAR and APK

### Web UI Redesign (2024-03)
- **New layout**: Scanner (left) + Button panel (right)
- **4 action buttons**: Download, Reset, Preview, Copy
- **Preview behavior**: Click to show text content below
- **Responsive design**: flex layout with gap spacing

### Android UTF-8 Text Detection Fix (2024-03)
- **Fixed `looksLikeText()` function** to properly detect UTF-8 text
- **Bug**: Original condition `b < 32 && b < 0` incorrectly marked UTF-8 bytes as non-printable
- **Fix**: `boolean isPrintable = (b >= 9 && b <= 13) || (b >= 32 && b <= 126) || (b < 0)`
- **Result**: Korean, Chinese, Japanese text now properly detected as text

### Legacy Changes
- Terminal display default: `terminal=true`
- Command aliases: `write` → `w`, `read` → `r`
- File organization: Refactored main.go into multiple files
- Android recognition: Fixed color inversion, increased QuietZone

## Development Notes

- **Terminal QR codes** are larger than image QR codes → less data per frame
- **Animation loops** infinitely in terminal mode (Ctrl+C to exit)
- **GIF mode** requires explicit `-o` flag or `--gif` flag
- **Version info** injected via ldflags in CI/CD builds

## Feature 1: Android Animated QR Relay

### Overview
Android app can scan animated QR codes and re-transmit them as animated QR codes, acting as a QR repeater/relay. This extends the range of QR transmission through multiple devices.

**User Flow:**
1. MacBook1 generates animated QR → Android app scans it
2. Android app generates animated QR from scanned chunks
3. MacBook2 scans relayed QR → receives original data

### Technical Implementation

#### True Relay Approach
Instead of decoding and re-encoding, the app:
1. **Stores raw QR chunks** during scanning (`scannedChunks` ArrayList)
2. **Regenerates QR images** from stored chunks using ZXing
3. **Pre-generates all bitmaps** at scan completion for smooth animation
4. **Displays animated QR** using frame-by-frame bitmap switching

This is more efficient because:
- No redundant fountain code encoding
- Preserves original encoding parameters
- Faster processing (no decode→re-encode cycle)

#### Key Components

**MainActivity.java** - Main activity with relay functionality

| Component | Purpose |
|-----------|---------|
| `scannedChunks: ArrayList<String>` | Stores raw QR chunk strings during scan |
| `qrBitmaps: ArrayList<Bitmap>` | Pre-generated QR bitmaps for smooth animation |
| `previewMode: int` | Toggle state: 0=Preview(text), 1=Relay, 2=Hide |
| `QR_SIZE = 750` | QR code size (2.5x larger for better recognition) |
| `FRAME_DELAY_MS = 50` | Animation delay (20 FPS) |

**UI Components:**
- `DecoratedBarcodeView` - Camera QR scanner
- `qrDisplayView` (ImageView) - Displays animated QR in relay mode
- `textScrollView` - Scrollable text view for decoded content
- `previewButton` (Button) - Single toggle: Preview → Relay → Hide → Preview
- `downloadButton`, `resetButton`, `copyButton` - File operations

#### Relay Mode Behavior

**Toggle States:**
1. **Preview (mode 0)**: Shows decoded text content
2. **Relay (mode 1)**: Shows animated QR for re-transmission
3. **Hide (mode 2)**: Hides both text and QR

**Animation:**
- Uses pre-generated bitmaps for smooth playback
- Handler + Runnable pattern for frame scheduling
- Loops infinitely until user toggles away or resets

#### Background Processing

**QR Bitmap Generation:**
```java
private void generateQRBitmapsInBackground() {
    new Thread(() -> {
        generateQRBitmaps();  // Runs in background
        relayHandler.post(() -> {
            statusText.setText("Complete! " + decodedFilename);
        });
    }).start();
}
```

Prevents UI hang during QR generation by running on background thread.

#### Dependencies

**Android (build.gradle):**
```gradle
implementation 'androidx.appcompat:appcompat:1.6.1'
implementation 'com.google.zxing:core:3.5.1'
implementation 'com.journeyapps:zxing-android-embedded:4.3.0'
implementation 'com.github.bumptech.glide:glide:4.16.0'
implementation(name: 'txqr', ext: 'aar')  // gomobile binding
```

**Android Imports (MainActivity.java):**
```java
import android.app.Dialog;                    // Modal popup
import android.view.inputmethod.InputMethodManager;  // Keyboard control
import java.util.zip.Deflater;                // Flate compression
```

**Mobile (gomobile bindings):**
- `mobile/decode.go` - Decoder with progress tracking
- `mobile/encode.go` - Encoder bindings (prepared for Feature 2)

#### Build Process

**Use make command (recommended):**
```bash
make aar           # Build Android AAR library
make apk           # Build Android APK (includes AAR)
make android       # Build both AAR and APK
```

**Manual Docker build:**
```bash
# AAR Build (gomobile binding)
docker build --platform=linux/amd64 \
  -f Dockerfile.android \
  --target aar-builder \
  -t txqr-aar .

# APK Build
docker build --platform=linux/amd64 \
  -f Dockerfile.android \
  --target apk-builder \
  -t txqr-apk .
```

**Installation:**
```bash
adb uninstall com.github.divan.txqr 2>/dev/null; adb install txqr.apk
```

#### Build Error Handling (IMPORTANT)

If APK or AAR build fails during `make apk` or `make aar`:
1. **STOP the build process immediately**
2. **DO NOT attempt to retry or fix automatically**
3. **Report the full error message to the user**
4. Wait for user instructions before proceeding

This is critical because build failures may indicate:
- Dependency issues requiring manual intervention
- Docker platform problems
- SDK/NDK version mismatches
- Gomobile binding errors

#### Known Issues & Solutions

**Issue 1:** Text shows before Preview button is clicked
- **Solution:** Set `textScrollView.setVisibility(View.GONE)` initially

**Issue 2:** UI hang during QR bitmap generation
- **Solution:** Run bitmap generation in background thread

**Issue 3:** QR animation too slow
- **Solution:** Pre-generate all bitmaps at scan completion

### File Structure

```
android/app/src/main/java/com/github/divan/txqr/
└── MainActivity.java          # Main activity with relay functionality

mobile/
├── decode.go                  # gomobile decoder bindings
└── encode.go                  # gomobile encoder bindings (Feature 2 prep)
```

### Testing

**Test Relay Flow:**
1. Generate QR on macOS: `txqr write -o test.gif file.txt`
2. Scan on Android app
3. Wait for decoding completion
4. Click "Preview" → verify text content
5. Click "Relay" → verify QR animation
6. Scan relayed QR on another device
7. Verify file contents match original

## Feature 2: Android Write Mode (Text → Animated QR)

### Overview
안드로이드 앱에 텍스트를 입력하여 animated QR을 생성하는 기능입니다. 사용자가 직접 텍스트를 입력하고 QR 코드를 생성하여 다른 기기로 전송할 수 있습니다.

**사용 흐름:**
1. 앱 실행 → "Write" 탭 선택
2. Textarea에 텍스트 입력
3. "GENERATE" 버튼 클릭
4. Modal 팝업에 Animated QR 표시
5. 다른 기기에서 스캔
6. "Close" 버튼으로 팝업 닫기
7. "CLEAR" 버튼으로 텍스트 지우기

### Technical Implementation

#### Tab UI Architecture
- **Read Mode**: 기존 QR 스캔 기능 (barcodeView, decoder, 등)
- **Write Mode**: 텍스트 입력 + QR 생성 기능
- **Toggle**: Footer에 Read/Write 탭으로 전환

#### Data Encoding Flow (Write Mode)
```
Input Text
    ↓ (flate compress)
Compressed Data
    ↓ (base64 encode)
<filename>\n<base64_compressed_data>
    ↓ (base64 encode again)
Double-encoded payload
    ↓ (gomobile: encoder.encode())
StringList (wrapper for []string)
    ↓ (ZXing: QRCodeWriter.encode)
QR bitmaps (ArrayList<Bitmap>)
    ↓ (animation)
Animated QR display
```

#### Key Components

**MainActivity.java** - Write mode additions

| Component | Purpose |
|-----------|---------|
| `readContainer` (LinearLayout) | Read mode UI (camera, scan) |
| `writeContainer` (LinearLayout) | Write mode UI (textarea, generate) |
| `readTabButton` (Button) | Switch to read mode |
| `writeTabButton` (Button) | Switch to write mode |
| `textInput` (EditText) | Multi-line text input |
| `generateButton` (Button) | "GENERATE" - Generate QR from text |
| `clearButton` (Button) | "CLEAR" - Clear text input |
| `qrDialog` (Dialog) | Modal popup for QR display |
| `qrDialogImageView` (ImageView) | QR display in dialog |
| `qrDialogBitmaps` (ArrayList<Bitmap>) | Pre-generated QR bitmaps for dialog |
| `qrDialogHandler/Runnable` | Dialog animation control |

**Encoding Methods:**
- `encodePayload(text)` - Compress + double base64 encoding
- `compressFlate(data)` - Flate compression using Deflater
- `generateQRFromText()` - Orchestrates encoding + QR generation
- `clearWriteInput()` - Clears text input and shows toast

**Modal Dialog Methods:**
- `showQRDialog()` - Creates and shows modal dialog with QR animation
- `createQRDialog()` - Programmatically creates dialog layout
- `startQRDialogAnimation()` - Starts QR animation loop in dialog
- `stopQRDialogAnimation()` - Stops dialog animation

#### gomobile Encoder Bindings

**File:** [mobile/encode.go](mobile/encode.go)

**StringList Wrapper Pattern:**
gomobile doesn't support `[]string` return type directly. Solution: wrapper struct.

```go
type StringList struct {
    items []string
}

func (s *StringList) Get(i int) string { return s.items[i] }
func (s *StringList) Size() int        { return len(s.items) }

func (e *Encoder) Encode(data string) *StringList {
    chunks, _ := e.e.Encode(data)
    return &StringList{items: chunks}
}
```

**Java Usage:**
```java
Encoder encoder = Txqr.newEncoder(100);
StringList chunks = encoder.encode(payload);

for (int i = 0; i < (int)chunks.size(); i++) {
    String chunk = chunks.get((long)i);
    // Generate QR from chunk
}
```

#### Write Mode Behavior

**User Flow:**
1. User clicks "Write" tab
2. Camera pauses, scan stops
3. Textarea appears for input
4. User enters text and clicks "GENERATE"
5. Background process:
   - Encodes payload (compress + base64)
   - Calls gomobile encoder for fountain codes
   - Generates QR bitmaps using ZXing
   - Shows modal popup with animated QR
6. User can click "Close" to dismiss dialog
7. User can click "CLEAR" to clear text input

**Modal Dialog QR Display:**
- Full-screen modal dialog with black background
- Title: "Scan this QR code"
- QR ImageView (800px height, FIT_CENTER)
- Frame count info: "QR frames: N"
- Close button at bottom
- Dismiss animation when dialog is closed
- Separate from relay mode QR display (no conflicts)

**Animation:**
- Separate animation loop for dialog (`qrDialogHandler`, `qrDialogRunnable`)
- Frame-by-frame bitmap switching
- 20 FPS (50ms delay)
- Independent from relay mode animation

#### Dependencies

**Mobile (gomobile bindings):**
- `mobile/decode.go` - Decoder with progress tracking
- `mobile/encode.go` - Encoder with StringList wrapper

### Known Issues & Solutions

**Issue:** gomobile doesn't support `[]string` return type
- **Solution:** StringList wrapper struct with `Get(i)` and `Size()` methods

**Issue:** UI hang during QR bitmap generation
- **Solution:** Run encoding + QR generation in background thread

**Issue:** gomobile method naming convention
- **Solution:** Go exported methods (uppercase) → Java lowercase (`Encode()` → `encode()`)

**Issue:** QR not visible for long text (inline display issue)
- **Solution:** Modal popup dialog for QR display (more prominent, separate from main UI)

### File Structure

```
android/app/src/main/java/com/github/divan/txqr/
└── MainActivity.java          # Main activity with read/write/relay modes

android/app/libs/
└── txqr.aar                   # gomobile binding library

mobile/
├── decode.go                  # gomobile decoder bindings
└── encode.go                  # gomobile encoder bindings with StringList wrapper
```

### Testing

**Test Write Mode Flow:**
1. Open app → Click "Write" tab
2. Enter text: "Hello from Android!"
3. Click "GENERATE"
4. Verify modal dialog appears with animated QR
5. Scan with another device
6. Verify received text matches original
7. Click "Close" to dismiss dialog
8. Click "CLEAR" to verify text is cleared

**Test Tab Switching:**
1. Read → Write → Read → Verify camera resumes
2. Generate QR → Switch to Read → Verify dialog closes
3. Switch during relay → Verify relay stops

**Test Long Text:**
1. Enter long text (500+ characters)
2. Click "GENERATE"
3. Verify modal dialog shows with multiple QR frames
4. Verify animation loops smoothly
