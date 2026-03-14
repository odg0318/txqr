package main

import (
	"fmt"
)

// Version information (set via ldflags during build)
var (
	Version   = "dev"
	Commit    = "unknown"
	BuildDate = "unknown"
)

// PrintVersion outputs version information
func PrintVersion() {
	if Version == "dev" {
		fmt.Printf("txqr version %s (development build)\n", Version)
	} else {
		fmt.Printf("txqr version %s\n", Version)
	}
	fmt.Printf("Commit: %s\n", Commit)
	fmt.Printf("Built: %s\n", BuildDate)
}
