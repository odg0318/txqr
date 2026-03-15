package txqr

import (
	"github.com/divan/txqr"
)

// StringList wraps a string slice for gomobile binding.
// gomobile doesn't support []string return type directly.
type StringList struct {
	items []string
}

// Get returns the item at index i.
func (s *StringList) Get(i int) string {
	if i < 0 || i >= len(s.items) {
		return ""
	}
	return s.items[i]
}

// Size returns the number of items in the list.
func (s *StringList) Size() int {
	return len(s.items)
}

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
// Returns a StringList wrapper for gomobile compatibility.
func (e *Encoder) Encode(data string) *StringList {
	chunks, _ := e.e.Encode(data)
	return &StringList{items: chunks}
}

// SetRedundancyFactor sets the redundancy factor for encoding.
// Higher values mean more QR frames are generated, improving reliability
// but requiring more time to scan all frames.
// Typical values are 1.5 to 3.0.
func (e *Encoder) SetRedundancyFactor(rf float64) {
	e.e.SetRedundancyFactor(rf)
}
