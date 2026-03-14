package main

import (
	"log"
	"os"
)

func main() {
	if len(os.Args) < 2 {
		printUsage()
	}

	switch os.Args[1] {
	case "write", "w":
		runWrite()
	case "read", "r":
		runRead()
	case "version", "--version", "-v":
		PrintVersion()
	default:
		log.Fatalf("Unknown command: %s\nAvailable commands: write (w), read (r), version", os.Args[1])
	}
}

func printUsage() {
	log.Fatalf(`Usage: txqr <command> [args]

Commands:
  write, w <file>    Encode file to QR codes (default: terminal display)
  read, r            Start QR reader web server
  version            Show version information

Write Options:
  -o <file>          Output to GIF file instead of terminal
  -gif               Force GIF mode
  -split N           Chunk size per frame (default: 100)
  -size N            QR code size for GIF (default: 300)
  -fps N             Animation FPS (default: 5)
  -name              Filename for stdin data (default: stdin)

Examples:
  txqr write myfile.txt              # Display in terminal
  txqr write -o out.gif myfile.txt   # Generate GIF file
  txqr write --gif myfile.txt        # Generate out.gif
  echo 'data' | txqr write           # Pipe data to terminal
  echo 'data' | txqr write -o a.gif  # Pipe data to GIF
  txqr read                           # Start QR reader server
  txqr w myfile.txt                   # Short form
  txqr r                              # Short form`)
}
