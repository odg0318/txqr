# AGENT.md - TXQR Project Context

This file provides comprehensive context for AI agents working on the txqr codebase.

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
| Android | Kotlin + Gradle 8.4 | API 21+, SDK 34 |
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
│   └── decode.go               # Mobile decoder with progress tracking
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

**read.go** - Read/decode functionality (560 lines)
- Web server for QR scanning
- WebSocket for real-time scanner updates
- HTML interface with camera access

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

### Data Encoding Flow

1. **Read data** from file or stdin
2. **Compress** with flate (default compression)
3. **Encode** as: `<filename>\n<base64_compressed_data>`
4. **Double base64 encode** entire payload
5. **Split** into chunks with fountain codes
6. **Generate** QR code for each chunk

### Data Format

```
Original Data
    ↓ (flate compress)
Compressed Data
    ↓ (base64 encode)
<filename>\n<base64_compressed_data>
    ↓ (base64 encode again)
Double-encoded payload
    ↓ (fountain encode + chunk split)
QR code chunks (animated display)
```

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

### Terminal Display (Current Session)
- Changed default: `terminal=true` (was `false`)
- Added `--gif` flag for explicit GIF mode
- `-o` flag now enables GIF mode automatically
- Usage: `txqr write myfile.txt` → terminal, `txqr write -o out.gif myfile.txt` → GIF

### Command Aliases
- `write` can be shortened to `w`
- `read` can be shortened to `r`

### File Organization
- Refactored `main.go` into multiple files by functionality
- Moved GIF functions from `gif.go` to `write.go`
- Deleted `gif.go` (consolidated)

### Android Recognition Fix
- Fixed encoding format in `txqr-ascii` to match GIF (compressed + double base64)
- Fixed color inversion: `BlackChar: BLACK, WhiteChar: WHITE`
- Increased QuietZone to 2 for better recognition

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

**Mobile (gomobile bindings):**
- `mobile/decode.go` - Decoder with progress tracking
- `mobile/encode.go` - Encoder bindings (prepared for Feature 2)

#### Build Process

**AAR Build (gomobile binding):**
```bash
docker build --platform=linux/amd64 \
  -f Dockerfile.android \
  --target aar-builder \
  -t txqr-aar .
```

**APK Build:**
```bash
docker build --platform=linux/amd64 \
  -f Dockerfile.android \
  --target apk-builder \
  -t txqr-apk .
```

**Installation:**
```bash
adb uninstall com.github.divan.txqr 2>/dev/null; adb install txqr.apk
```

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
3. "Generate QR" 버튼 클릭
4. Animated QR 표시
5. 다른 기기에서 스캔

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
| `generateButton` (Button) | Generate QR from text |

**Encoding Methods:**
- `encodePayload(text)` - Compress + double base64 encoding
- `compressFlate(data)` - Flate compression using Deflater
- `generateQRFromText()` - Orchestrates encoding + QR generation

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
4. User enters text and clicks "Generate QR"
5. Background process:
   - Encodes payload (compress + base64)
   - Calls gomobile encoder for fountain codes
   - Generates QR bitmaps using ZXing
   - Displays animated QR

**Animation:**
- Reuses `startQRAnimation()` from relay mode
- Same frame-by-frame bitmap switching
- 20 FPS (50ms delay)

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

### File Structure

```
android/app/src/main/java/com/github/divan/txqr/
└── MainActivity.java          # Main activity with read/write modes

mobile/
├── decode.go                  # gomobile decoder bindings
└── encode.go                  # gomobile encoder bindings with StringList wrapper
```

### Testing

**Test Write Mode Flow:**
1. Open app → Click "Write" tab
2. Enter text: "Hello from Android!"
3. Click "Generate QR"
4. Verify animated QR appears
5. Scan with another device
6. Verify received text matches original

**Test Tab Switching:**
1. Read → Write → Read → Verify camera resumes
2. Generate QR → Switch to Read → Verify QR stops
3. Switch during relay → Verify relay stops
