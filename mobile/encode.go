package txqr

import (
	"github.com/divan/txqr"
)

// Encoder wraps txqr.Encoder for gomobile binding.
// This exposes encoder functionality to mobile platforms.
type Encoder struct {
	e *txqr.Encoder
}

// NewEncoder creates a new encoder with the specified chunk size.
// The chunk size determines how much data each QR code frame can hold.
func NewEncoder(chunkSize int) *Encoder {
	return &Encoder{e: txqr.NewEncoder(chunkSize)}
}

// Encode encodes the data string into fountain code chunks.
// Returns a slice of chunk strings, each suitable for encoding as a QR code.
func (e *Encoder) Encode(data string) []string {
	chunks, _ := e.e.Encode(data)
	return chunks
}

// SetRedundancyFactor sets the redundancy factor for encoding.
// Higher values mean more QR frames are generated, improving reliability
// but requiring more time to scan all frames.
// Typical values are 1.5 to 3.0.
func (e *Encoder) SetRedundancyFactor(rf float64) {
	e.e.SetRedundancyFactor(rf)
}
