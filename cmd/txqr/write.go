package main

import (
	"bytes"
	"compress/flate"
	"encoding/base64"
	"flag"
	"fmt"
	"image"
	"image/gif"
	"io"
	"io/ioutil"
	"log"
	"os"

	"github.com/divan/txqr"
	"github.com/divan/txqr/qr"
)

// runWrite implements the write command
func runWrite() {
	flags := flag.NewFlagSet("write", flag.ExitOnError)
	splitSize := flags.Int("split", 100, "Chunk size for data split per frame")
	size := flags.Int("size", 300, "QR code size")
	fps := flags.Int("fps", 5, "Animation FPS")
	output := flags.String("o", "", "Output animated gif file (enables GIF mode)")
	name := flags.String("name", "stdin", "Filename to use when reading from stdin")
	gifMode := flags.Bool("gif", false, "Generate GIF instead of displaying in terminal")

	if err := flags.Parse(os.Args[2:]); err != nil {
		log.Fatal(err)
	}

	if flags.NArg() > 1 {
		log.Fatalf("Usage: txqr write [options] [<file>]\n       echo 'data' | txqr write [options]\n\nOptions:\n  -o <file>   Output GIF file (enables GIF mode)\n  -gif        Generate GIF instead of terminal display\n  -split N    Chunk size (default: 100)\n  -size N     QR code size (default: 300)\n  -fps N      Animation FPS (default: 5)\n  -name       Filename for stdin data (default: stdin)")
	}

	var (
		data     []byte
		filename string
		err      error
	)

	if flags.NArg() == 1 {
		// File argument provided - read from file
		filename = flags.Arg(0)
		data, err = ioutil.ReadFile(filename)
	} else {
		// No file argument - check stdin
		fi, _ := os.Stdin.Stat()
		if fi.Mode()&os.ModeCharDevice == 0 {
			// Stdin is piped - read from stdin
			data, err = io.ReadAll(os.Stdin)
			filename = *name
		} else {
			// No piped input and no file - show usage error
			log.Fatalf("Usage: txqr write [options] [<file>]\n       echo 'data' | txqr write [options]\n\nOptions:\n  -o <file>   Output GIF file (enables GIF mode)\n  -gif        Generate GIF instead of terminal display\n  -split N    Chunk size (default: 100)\n  -size N     QR code size (default: 300)\n  -fps N      Animation FPS (default: 5)\n  -name       Filename for stdin data (default: stdin)")
		}
	}

	if err != nil {
		log.Fatalf("[ERROR] Read input: %v", err)
	}

	// Compress data using flate
	var compressed bytes.Buffer
	w, _ := flate.NewWriter(&compressed, flate.DefaultCompression)
	w.Write(data)
	w.Close()

	// Encode as "<filename>\n<base64_compressed_data>"
	payload := filename + "\n" + base64.StdEncoding.EncodeToString(compressed.Bytes())
	str := base64.StdEncoding.EncodeToString([]byte(payload))

	chunks, err := txqr.NewEncoder(*splitSize).Encode(str)
	if err != nil {
		log.Fatalf("[ERROR] Encoding: %v", err)
	}

	// Determine output mode
	useGif := *gifMode || *output != ""
	if useGif && *output == "" {
		*output = "out.gif"
	}

	// Branch based on mode
	if !useGif {
		displayTerminal(chunks, *fps)
		return
	}

	// GIF mode
	out, err := AnimatedGifFromChunks(chunks, *size, *fps, qr.Medium)
	if err != nil {
		log.Fatalf("[ERROR] Creating animated gif: %v", err)
	}

	err = ioutil.WriteFile(*output, out, 0660)
	if err != nil {
		log.Fatalf("[ERROR] Create file: %v", err)
	}
	log.Println("Written output to", *output)
}

// AnimatedGifFromChunks creates an animated GIF from pre-encoded chunks
func AnimatedGifFromChunks(chunks []string, imgSize int, fps int, lvl qr.RecoveryLevel) ([]byte, error) {
	out := &gif.GIF{
		Image: make([]*image.Paletted, len(chunks)),
		Delay: make([]int, len(chunks)),
	}
	for i, chunk := range chunks {
		qr, err := qr.Encode(chunk, imgSize, lvl)
		if err != nil {
			return nil, fmt.Errorf("QR encode: %v", err)
		}
		out.Image[i] = qr.(*image.Paletted)
		out.Delay[i] = fpsToGifDelay(fps)
	}

	var buf bytes.Buffer
	err := gif.EncodeAll(&buf, out)
	if err != nil {
		return nil, fmt.Errorf("gif create: %v", err)
	}
	return buf.Bytes(), nil
}

// fpsToGifDelay converts fps value into animated GIF delay value (in 100th of second)
func fpsToGifDelay(fps int) int {
	if fps == 0 {
		return 100 // default value, 1 sec
	}
	return 100 / fps
}
