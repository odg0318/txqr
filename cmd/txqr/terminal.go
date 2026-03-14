package main

import (
	"fmt"
	"os"
	"time"

	"github.com/mdp/qrterminal"
)

// displayTerminal shows QR codes in terminal with animation
func displayTerminal(chunks []string, fps int) {
	delay := time.Second / time.Duration(fps)
	if delay < 50*time.Millisecond {
		delay = 50 * time.Millisecond
	}

	for {
		for i, chunk := range chunks {
			clearScreen()
			fmt.Printf("Frame %d/%d | Scan with txqr read\n", i+1, len(chunks))

			config := qrterminal.Config{
				Level:     qrterminal.M,
				Writer:    os.Stdout,
				BlackChar: qrterminal.BLACK,
				WhiteChar: qrterminal.WHITE,
				QuietZone: 2,
			}
			qrterminal.GenerateWithConfig(chunk, config)
			time.Sleep(delay)
		}
	}
}

// clearScreen clears the terminal screen
func clearScreen() {
	print("\033[H\033[2J")
}
