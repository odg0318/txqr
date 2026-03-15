package main

import (
	"bytes"
	"compress/flate"
	"encoding/base64"
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"os/exec"
	"runtime"
	"strings"
	"sync"

	"github.com/divan/txqr"
	"github.com/gorilla/websocket"
)

var (
	decoder      *txqr.Decoder
	decoderMu    sync.Mutex
	decoded      []byte
	decodedName  string // filename extracted from decoded data
)

// runRead implements the read command
func runRead() {
	flags := flag.NewFlagSet("read", flag.ExitOnError)
	port := flags.Int("port", 8080, "HTTP server port")
	noBrowser := flags.Bool("n", false, "Don't open browser automatically")

	if err := flags.Parse(os.Args[2:]); err != nil {
		log.Fatal(err)
	}

	decoder = txqr.NewDecoder()

	addr := fmt.Sprintf(":%d", *port)
	url := fmt.Sprintf("http://localhost%s", addr)

	http.HandleFunc("/", handleIndex)
	http.HandleFunc("/ws", handleWS)
	http.HandleFunc("/download", handleDownload)

	if !*noBrowser {
		go startBrowser(url)
	}

	log.Printf("Starting txqr reader on %s", url)
	if err := http.ListenAndServe(addr, nil); err != nil {
		log.Fatalf("[ERROR] %v", err)
	}
}

// wsMessage is sent from browser to server.
type wsMessage struct {
	Type string `json:"type"`
	Data string `json:"data"`
}

// wsResponse is sent from server to browser.
type wsResponse struct {
	Type      string `json:"type"`
	Progress  int    `json:"progress,omitempty"`
	Total     int    `json:"total,omitempty"`
	Error     string `json:"error,omitempty"`
	Filename  string `json:"filename,omitempty"`
	Content   string `json:"content,omitempty"` // text file content
	IsText    bool   `json:"isText,omitempty"`  // whether content is text
}

var upgrader = websocket.Upgrader{
	CheckOrigin: func(r *http.Request) bool { return true },
}

func handleWS(w http.ResponseWriter, r *http.Request) {
	conn, err := upgrader.Upgrade(w, r, nil)
	if err != nil {
		log.Println("[ERROR] WebSocket upgrade:", err)
		return
	}
	defer conn.Close()

	log.Println("[INFO] WebSocket client connected")

	for {
		_, raw, err := conn.ReadMessage()
		if err != nil {
			log.Println("[INFO] WebSocket closed:", err)
			break
		}

		var msg wsMessage
		if err := json.Unmarshal(raw, &msg); err != nil {
			log.Println("[ERROR] Parse message:", err)
			continue
		}

		switch msg.Type {
		case "qr":
			resp := handleQRFrame(msg.Data)
			if err := conn.WriteJSON(resp); err != nil {
				log.Println("[ERROR] Write response:", err)
				return
			}
		case "reset":
			decoderMu.Lock()
			decoder.Reset()
			decoded = nil
			decodedName = ""
			decoderMu.Unlock()
			conn.WriteJSON(wsResponse{Type: "reset"})
		}
	}
}

func handleQRFrame(data string) wsResponse {
	decoderMu.Lock()
	defer decoderMu.Unlock()

	if decoder.IsCompleted() {
		return wsResponse{Type: "completed", Total: decoder.Total()}
	}

	if err := decoder.Validate(data); err != nil {
		return wsResponse{Type: "error", Error: fmt.Sprintf("validate: %v", err)}
	}

	if err := decoder.Decode(data); err != nil {
		return wsResponse{Type: "error", Error: fmt.Sprintf("decode: %v", err)}
	}

	if decoder.IsCompleted() {
		b64str := decoder.Data()
		raw, err := base64.StdEncoding.DecodeString(b64str)
		if err != nil {
			return wsResponse{Type: "error", Error: fmt.Sprintf("base64 decode: %v", err)}
		}

		// Parse "<filename>\n<compressed_data>" (single base64 encoding - simplified)
		payload := string(raw)
		newlineIdx := strings.Index(payload, "\n")
		if newlineIdx == -1 {
			return wsResponse{Type: "error", Error: "invalid payload format"}
		}
		decodedName = payload[:newlineIdx]
		compressed := []byte(payload[newlineIdx+1:]) // Direct compressed bytes (no inner base64)

		// Decompress flate data
		r := flate.NewReader(bytes.NewReader(compressed))
		decoded, err = io.ReadAll(r)
		r.Close()
		if err != nil {
			return wsResponse{Type: "error", Error: fmt.Sprintf("flate decompress: %v", err)}
		}

		log.Printf("[INFO] Decoding completed! File: %s, Size: %d bytes", decodedName, len(decoded))

		resp := wsResponse{Type: "completed", Total: len(decoded), Filename: decodedName}
		// Check both extension and content to determine if it's text
		if isTextFile(decodedName) || looksLikeText(decoded) {
			resp.IsText = true
			resp.Content = string(decoded)
		}
		return resp
	}

	return wsResponse{Type: "progress", Total: decoder.Total()}
}

func handleDownload(w http.ResponseWriter, r *http.Request) {
	decoderMu.Lock()
	data := decoded
	name := decodedName
	decoderMu.Unlock()

	if data == nil {
		http.Error(w, "No data available. Decoding not yet completed.", http.StatusNotFound)
		return
	}

	filename := name
	if filename == "" {
		filename = "output.bin"
	}

	w.Header().Set("Content-Type", "application/octet-stream")
	w.Header().Set("Content-Disposition", fmt.Sprintf("attachment; filename=%q", filename))
	w.Header().Set("Content-Length", fmt.Sprintf("%d", len(data)))
	w.Write(data)
}

func handleIndex(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	fmt.Fprint(w, indexHTML)
}

func startBrowser(url string) {
	var args []string
	switch runtime.GOOS {
	case "darwin":
		args = []string{"open"}
	case "windows":
		args = []string{"cmd", "/c", "start"}
	default:
		args = []string{"xdg-open"}
	}
	cmd := exec.Command(args[0], append(args[1:], url)...)
	log.Printf("If browser window didn't appear, please go to: %s", url)
	cmd.Start()
}

// textExtensions defines common text file extensions
var textExtensions = map[string]bool{
	".txt":        true,
	".md":         true,
	".json":       true,
	".xml":        true,
	".html":       true,
	".htm":        true,
	".css":        true,
	".js":         true,
	".ts":         true,
	".jsx":        true,
	".tsx":        true,
	".go":         true,
	".py":         true,
	".rs":         true,
	".c":          true,
	".cpp":        true,
	".cc":         true,
	".cxx":        true,
	".h":          true,
	".hpp":        true,
	".java":       true,
	".kt":         true,
	".kts":        true,
	".swift":      true,
	".sh":         true,
	".bash":       true,
	".zsh":        true,
	".fish":       true,
	".ps1":        true,
	".yaml":       true,
	".yml":        true,
	".toml":       true,
	".ini":        true,
	".cfg":        true,
	".conf":       true,
	".config":     true,
	".log":        true,
	".csv":        true,
	".tsv":        true,
	".sql":        true,
	".graphql":    true,
	".graphqls":   true,
	".gql":        true,
	".proto":      true,
	".Makefile":   true,
	".Dockerfile": true,
	".dockerignore": true,
	".gitignore":  true,
	".gitattributes": true,
	".gitmodules": true,
	".editorconfig": true,
	".env":        true,
	".env.local":  true,
	".rfc":        true,
	".rst":        true,
	".tex":        true,
	".lua":        true,
	".rb":         true,
	".php":        true,
	".scala":      true,
	".sc":         true,
	".clj":        true,
	".cljs":       true,
	".edn":        true,
	".vim":        true,
	".nix":        true,
	".pl":         true,
	".pm":         true,
	".r":          true,
	".R":          true,
	".m":          true,
	".mm":         true,
	".dart":       true,
	".groovy":     true,
	".gradle":     true,
	".props":      true,
	".properties": true,
	".bat":        true,
	".cmd":        true,
	".powershell": true,
	".pem":        true,
	".crt":        true,
	".key":        true,
	".pub":        true,
	".asc":        true,
	".gpg":        true,
}

// isTextFile checks if a file is likely a text file based on extension
func isTextFile(filename string) bool {
	// Check exact filename matches
	if _, ok := textExtensions[filename]; ok {
		return true
	}

	// Check extension
	for ext := range textExtensions {
		if strings.HasSuffix(filename, ext) {
			return true
		}
	}

	// No extension or unknown extension - will check content
	return false
}

// looksLikeText checks if data appears to be text content by inspecting bytes
// Returns true if data doesn't contain binary markers (null bytes, high ratio of non-printable)
func looksLikeText(data []byte) bool {
	if len(data) == 0 {
		return true // Empty is considered text
	}

	// Quick check: null bytes are a strong indicator of binary
	if bytes.Contains(data, []byte{0}) {
		return false
	}

	// For small files, do a more thorough check
	sampleSize := len(data)
	if sampleSize > 8192 {
		sampleSize = 8192 // Check first 8KB
	}

	// Count non-printable characters (excluding common whitespace)
	// Printable: 9-13 (tab, newline, etc), 32-126 (ASCII printable), 128+ (UTF-8)
	nonPrintable := 0
	for i := 0; i < sampleSize; i++ {
		b := data[i]
		// Allow: tab (9), newline (10), vertical tab (11), form feed (12), carriage return (13)
		// Allow: space (32) to ~ (126)
		// Allow: bytes >= 128 (UTF-8 continuation/leading bytes)
		if b != 9 && b != 10 && b != 11 && b != 12 && b != 13 &&
			(b < 32 || b > 126) && b < 128 {
			nonPrintable++
		}
	}

	// If more than 5% non-printable (in the restricted range), likely binary
	threshold := sampleSize / 20
	if nonPrintable > threshold {
		return false
	}

	return true
}

const indexHTML = `<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>txqr reader</title>
<style>
  * { margin: 0; padding: 0; box-sizing: border-box; }
  body {
    font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
    background: #0f172a; color: #e2e8f0;
    min-height: 100vh; padding: 20px;
  }
  h1 { font-size: 1.5rem; margin-bottom: 16px; color: #38bdf8; text-align: center; }
  .mainRow {
    display: flex; gap: 16px; align-items: flex-start;
    max-width: 900px; margin: 0 auto;
  }
  #videoContainer {
    flex: 0 0 400px;
    position: relative;
    border-radius: 12px; overflow: hidden;
    border: 2px solid #334155;
  }
  video { width: 100%; display: block; }
  canvas { display: none; }
  #overlay {
    position: absolute; top: 0; left: 0; width: 100%; height: 100%;
    pointer-events: none;
  }
  .rightPanel {
    flex: 1;
    display: flex; flex-direction: column; gap: 12px;
  }
  #status {
    background: #1e293b; border-radius: 8px; padding: 12px;
  }
  #statusText { font-size: 0.9rem; color: #94a3b8; margin-bottom: 8px; }
  #progressBar {
    width: 100%; height: 6px; background: #334155;
    border-radius: 3px; overflow: hidden;
  }
  #progressFill {
    height: 100%; width: 0%; background: #38bdf8;
    transition: width 0.3s ease;
  }
  #stats {
    font-size: 0.75rem; color: #64748b; margin-top: 6px;
  }
  #frameCount { color: #38bdf8; font-weight: bold; }
  .buttons {
    display: flex; gap: 8px; flex-wrap: wrap;
  }
  .btn {
    padding: 10px 20px; border: none; border-radius: 6px;
    font-size: 0.9rem; font-weight: 600; cursor: pointer;
    transition: background 0.2s;
  }
  #downloadBtn { background: #22c55e; color: #fff; }
  #downloadBtn:hover { background: #16a34a; }
  #resetBtn { background: #475569; color: #e2e8f0; }
  #resetBtn:hover { background: #64748b; }
  #previewBtn { background: #3b82f6; color: #fff; }
  #previewBtn:hover { background: #2563eb; }
  #copyBtn { background: #8b5cf6; color: #fff; }
  #copyBtn:hover { background: #7c3aed; }
  #textContent {
    display: none;
    background: #1e293b; border-radius: 8px; padding: 16px;
    max-width: 900px; margin: 16px auto 0;
  }
  #textContent pre {
    margin: 0; white-space: pre-wrap; word-break: break-all;
    font-family: 'Monaco', 'Menlo', 'Ubuntu Mono', monospace;
    font-size: 0.85rem; color: #e2e8f0; max-height: 400px;
    overflow-y: auto;
  }
  #textContentHeader {
    display: flex; justify-content: space-between; align-items: center;
    margin-bottom: 12px; padding-bottom: 8px;
    border-bottom: 1px solid #334155;
  }
  #textContentTitle {
    font-size: 0.9rem; color: #94a3b8; font-weight: 600;
  }
</style>
</head>
<body>
<h1>txqr reader</h1>
<div class="mainRow">
  <div id="videoContainer">
    <video id="video" autoplay playsinline></video>
    <canvas id="overlay"></canvas>
  </div>
  <canvas id="canvas"></canvas>
  <div class="rightPanel">
    <div id="status">
      <div id="statusText">Initializing camera...</div>
      <div id="progressBar"><div id="progressFill"></div></div>
      <div id="stats">Frames: <span id="frameCount">0</span></div>
    </div>
    <div class="buttons">
      <button class="btn" id="downloadBtn">Download</button>
      <button class="btn" id="resetBtn">Reset</button>
      <button class="btn" id="previewBtn">Preview</button>
      <button class="btn" id="copyBtn">Copy</button>
    </div>
  </div>
</div>
<div id="textContent">
  <div id="textContentHeader">
    <span id="textContentTitle"></span>
  </div>
  <pre id="textContentBody"></pre>
</div>

<script src="https://cdn.jsdelivr.net/npm/jsqr@1.4.0/dist/jsQR.min.js"></script>
<script>
(function() {
  const video = document.getElementById('video');
  const canvas = document.getElementById('canvas');
  const ctx = canvas.getContext('2d');
  const overlay = document.getElementById('overlay');
  const overlayCtx = overlay.getContext('2d');
  const statusText = document.getElementById('statusText');
  const progressFill = document.getElementById('progressFill');
  const frameCount = document.getElementById('frameCount');
  const downloadBtn = document.getElementById('downloadBtn');
  const resetBtn = document.getElementById('resetBtn');
  const previewBtn = document.getElementById('previewBtn');
  const copyBtn = document.getElementById('copyBtn');
  const textContent = document.getElementById('textContent');
  const textContentTitle = document.getElementById('textContentTitle');
  const textContentBody = document.getElementById('textContentBody');

  let ws;
  let frames = 0;
  let completed = false;
  let scanning = false;
  let lastSent = '';
  let totalSize = 0;
  let decodedContent = null;
  let decodedFilename = '';

  function connectWS() {
    const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
    ws = new WebSocket(proto + '//' + location.host + '/ws');
    ws.onopen = () => {
      statusText.textContent = 'Connected. Point camera at animated QR code.';
      startScanning();
    };
    ws.onmessage = (evt) => {
      const resp = JSON.parse(evt.data);
      handleResponse(resp);
    };
    ws.onclose = () => {
      if (!completed) {
        statusText.textContent = 'Connection lost. Reconnecting...';
        setTimeout(connectWS, 1000);
      }
    };
    ws.onerror = () => { ws.close(); };
  }

  function handleResponse(resp) {
    switch (resp.type) {
      case 'progress':
        frames++;
        frameCount.textContent = frames;
        if (resp.total > 0) {
          totalSize = resp.total;
          statusText.textContent = 'Scanning... Total: ' + totalSize + ' bytes';
        }
        break;
      case 'completed':
        completed = true;
        scanning = false;
        totalSize = resp.total;
        progressFill.style.width = '100%';
        decodedFilename = resp.filename || 'output.bin';
        statusText.textContent = 'Complete! ' + decodedFilename + ' (' + totalSize + ' bytes)';

        if (resp.isText && resp.content !== undefined) {
          decodedContent = resp.content;
          downloadBtn.style.display = 'inline-block';
          previewBtn.style.display = 'inline-block';
          copyBtn.style.display = 'inline-block';
        } else {
          decodedContent = null;
          downloadBtn.style.display = 'inline-block';
          previewBtn.style.display = 'none';
          copyBtn.style.display = 'none';
        }
        resetBtn.style.display = 'inline-block';
        break;
      case 'error':
        break;
      case 'reset':
        frames = 0;
        frameCount.textContent = '0';
        completed = false;
        totalSize = 0;
        decodedContent = null;
        decodedFilename = '';
        progressFill.style.width = '0%';
        statusText.textContent = 'Reset. Point camera at animated QR code.';
        downloadBtn.style.display = 'none';
        resetBtn.style.display = 'none';
        previewBtn.style.display = 'none';
        copyBtn.style.display = 'none';
        textContent.style.display = 'none';
        lastSent = '';
        startScanning();
        break;
    }
  }

  downloadBtn.addEventListener('click', () => {
    window.location.href = '/download';
  });

  resetBtn.addEventListener('click', () => {
    if (ws && ws.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify({type: 'reset'}));
    }
  });

  previewBtn.addEventListener('click', () => {
    if (decodedContent !== null) {
      textContentTitle.textContent = decodedFilename;
      textContentBody.textContent = decodedContent;
      textContent.style.display = 'block';
    }
  });

  copyBtn.addEventListener('click', async () => {
    if (decodedContent === null) return;
    try {
      await navigator.clipboard.writeText(decodedContent);
      const origText = copyBtn.textContent;
      copyBtn.textContent = 'Copied!';
      setTimeout(() => {
        copyBtn.textContent = origText;
      }, 2000);
    } catch (err) {
      const origText = copyBtn.textContent;
      copyBtn.textContent = 'Failed';
      setTimeout(() => {
        copyBtn.textContent = origText;
      }, 2000);
    }
  });

  async function initCamera() {
    try {
      const stream = await navigator.mediaDevices.getUserMedia({
        video: { facingMode: 'environment', width: { ideal: 640 }, height: { ideal: 480 } }
      });
      video.srcObject = stream;
      await video.play();
      canvas.width = video.videoWidth;
      canvas.height = video.videoHeight;
      overlay.width = video.videoWidth;
      overlay.height = video.videoHeight;
      connectWS();
    } catch (err) {
      statusText.textContent = 'Camera error: ' + err.message;
    }
  }

  function startScanning() {
    if (scanning) return;
    scanning = true;
    requestAnimationFrame(scan);
  }

  function scan() {
    if (!scanning) return;
    if (video.readyState === video.HAVE_ENOUGH_DATA) {
      ctx.drawImage(video, 0, 0, canvas.width, canvas.height);
      const imageData = ctx.getImageData(0, 0, canvas.width, canvas.height);
      const code = jsQR(imageData.data, imageData.width, imageData.height, {
        inversionAttempts: 'dontInvert'
      });

      overlayCtx.clearRect(0, 0, overlay.width, overlay.height);
      if (code) {
        overlayCtx.strokeStyle = '#38bdf8';
        overlayCtx.lineWidth = 3;
        overlayCtx.beginPath();
        overlayCtx.moveTo(code.location.topLeftCorner.x, code.location.topLeftCorner.y);
        overlayCtx.lineTo(code.location.topRightCorner.x, code.location.topRightCorner.y);
        overlayCtx.lineTo(code.location.bottomRightCorner.x, code.location.bottomRightCorner.y);
        overlayCtx.lineTo(code.location.bottomLeftCorner.x, code.location.bottomLeftCorner.y);
        overlayCtx.closePath();
        overlayCtx.stroke();

        if (code.data !== lastSent && ws && ws.readyState === WebSocket.OPEN) {
          lastSent = code.data;
          ws.send(JSON.stringify({type: 'qr', data: code.data}));
        }
      }
    }
    requestAnimationFrame(scan);
  }

  initCamera();
})();
</script>
</body>
</html>`
