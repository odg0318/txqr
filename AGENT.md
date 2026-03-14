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
