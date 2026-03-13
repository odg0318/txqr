package main

import (
	"bytes"
	"compress/flate"
	"encoding/base64"
	"encoding/json"
	"flag"
	"fmt"
	"image"
	"image/gif"
	"io"
	"io/ioutil"
	"log"
	"net/http"
	"os"
	"os/exec"
	"runtime"
	"strings"
	"sync"

	"github.com/divan/txqr"
	"github.com/divan/txqr/qr"
	"github.com/gorilla/websocket"
)

func main() {
	if len(os.Args) < 2 {
		log.Fatalf("Usage: txqr <command> [args]\nCommands:\n  write <file>  - Encode file to animated QR GIF\n  read          - Start QR reader web server")
	}

	switch os.Args[1] {
	case "write":
		runWrite()
	case "read":
		runRead()
	default:
		log.Fatalf("Unknown command: %s\nAvailable commands: write, read", os.Args[1])
	}
}

// runWrite implements the write command (from txqr-gif)
func runWrite() {
	flags := flag.NewFlagSet("write", flag.ExitOnError)
	splitSize := flags.Int("split", 100, "Chunk size for data split per frame")
	size := flags.Int("size", 300, "QR code size")
	fps := flags.Int("fps", 5, "Animation FPS")
	output := flags.String("o", "out.gif", "Output animated gif file")

	if err := flags.Parse(os.Args[2:]); err != nil {
		log.Fatal(err)
	}

	if flags.NArg() != 1 {
		log.Fatalf("Usage: txqr write [options] <file>")
	}

	filename := flags.Arg(0)

	data, err := ioutil.ReadFile(filename)
	if err != nil {
		log.Fatalf("[ERROR] Read input file: %v", err)
	}

	out, err := AnimatedGif(data, filename, *size, *fps, *splitSize, qr.Medium)
	if err != nil {
		log.Fatalf("[ERROR] Creating animated gif: %v", err)
	}

	err = ioutil.WriteFile(*output, out, 0660)
	if err != nil {
		log.Fatalf("[ERROR] Create file: %v", err)
	}
	log.Println("Written output to", *output)
}

// AnimatedGif creates an animated GIF from data with filename
func AnimatedGif(data []byte, filename string, imgSize int, fps, size int, lvl qr.RecoveryLevel) ([]byte, error) {
	// Compress data using flate
	var compressed bytes.Buffer
	w, _ := flate.NewWriter(&compressed, flate.DefaultCompression)
	w.Write(data)
	w.Close()

	// Encode as "<filename>\n<base64_compressed_data>"
	payload := filename + "\n" + base64.StdEncoding.EncodeToString(compressed.Bytes())
	str := base64.StdEncoding.EncodeToString([]byte(payload))
	chunks, err := txqr.NewEncoder(size).Encode(str)
	if err != nil {
		return nil, fmt.Errorf("encode: %v", err)
	}

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
	err = gif.EncodeAll(&buf, out)
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

// runRead implements the read command (from txqr-reader)
var (
	decoder      *txqr.Decoder
	decoderMu    sync.Mutex
	decoded      []byte
	decodedName  string // filename extracted from decoded data
)

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
	Type     string `json:"type"`
	Progress int    `json:"progress,omitempty"`
	Total    int    `json:"total,omitempty"`
	Error    string `json:"error,omitempty"`
	Filename string `json:"filename,omitempty"`
	Content  string `json:"content,omitempty"` // text file content
	IsText   bool   `json:"isText,omitempty"`  // whether content is text
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
	".go":         true,
	".py":         true,
	".rs":         true,
	".c":          true,
	".cpp":        true,
	".h":          true,
	".java":       true,
	".sh":         true,
	".bash":       true,
	".zsh":        true,
	".yaml":       true,
	".yml":        true,
	".toml":       true,
	".ini":        true,
	".cfg":        true,
	".conf":       true,
	".log":        true,
	".csv":        true,
	".tsv":        true,
	".sql":        true,
	".graphql":    true,
	".graphqls":   true,
	".proto":      true,
	".Makefile":   true,
	".Dockerfile": true,
	".gitignore":  true,
	".gitattributes": true,
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

	// No extension or unknown extension - treat as binary
	return false
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

		// Parse "<filename>\n<base64_data>"
		payload := string(raw)
		newlineIdx := strings.Index(payload, "\n")
		if newlineIdx == -1 {
			return wsResponse{Type: "error", Error: "invalid payload format"}
		}
		decodedName = payload[:newlineIdx]
		b64Data := payload[newlineIdx+1:]
		compressed, err := base64.StdEncoding.DecodeString(b64Data)
		if err != nil {
			return wsResponse{Type: "error", Error: fmt.Sprintf("base64 data decode: %v", err)}
		}

		// Decompress flate data
		r := flate.NewReader(bytes.NewReader(compressed))
		decoded, err = io.ReadAll(r)
		r.Close()
		if err != nil {
			return wsResponse{Type: "error", Error: fmt.Sprintf("flate decompress: %v", err)}
		}

		log.Printf("[INFO] Decoding completed! File: %s, Size: %d bytes", decodedName, len(decoded))

		resp := wsResponse{Type: "completed", Total: len(decoded), Filename: decodedName}
		if isTextFile(decodedName) {
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
    display: flex; flex-direction: column; align-items: center;
    min-height: 100vh; padding: 20px;
  }
  h1 { font-size: 1.5rem; margin-bottom: 16px; color: #38bdf8; }
  #videoContainer {
    position: relative; width: 100%; max-width: 480px;
    border-radius: 12px; overflow: hidden;
    border: 2px solid #334155; margin-bottom: 16px;
  }
  video { width: 100%; display: block; }
  canvas { display: none; }
  #overlay {
    position: absolute; top: 0; left: 0; width: 100%; height: 100%;
    pointer-events: none;
  }
  #status {
    background: #1e293b; border-radius: 8px; padding: 16px;
    width: 100%; max-width: 480px; margin-bottom: 16px;
    text-align: center;
  }
  #statusText { font-size: 0.95rem; color: #94a3b8; margin-bottom: 8px; }
  #progressBar {
    width: 100%; height: 8px; background: #334155;
    border-radius: 4px; overflow: hidden;
  }
  #progressFill {
    height: 100%; width: 0%; background: #38bdf8;
    transition: width 0.3s ease;
  }
  #stats {
    font-size: 0.8rem; color: #64748b; margin-top: 8px;
  }
  .btn {
    display: none; padding: 12px 32px; border: none; border-radius: 8px;
    font-size: 1rem; font-weight: 600; cursor: pointer;
    transition: background 0.2s;
  }
  #downloadBtn { background: #22c55e; color: #fff; }
  #downloadBtn:hover { background: #16a34a; }
  #resetBtn {
    display: none; background: #475569; color: #e2e8f0;
    margin-left: 8px;
  }
  #resetBtn:hover { background: #64748b; }
  .buttons { margin-top: 12px; }
  #frameCount { color: #38bdf8; font-weight: bold; }
  #textContent {
    display: none;
    width: 100%; max-width: 600px;
    background: #1e293b; border-radius: 8px; padding: 16px;
    margin-top: 16px;
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
<div id="videoContainer">
  <video id="video" autoplay playsinline></video>
  <canvas id="overlay"></canvas>
</div>
<canvas id="canvas"></canvas>
<div id="status">
  <div id="statusText">Initializing camera...</div>
  <div id="progressBar"><div id="progressFill"></div></div>
  <div id="stats">Frames decoded: <span id="frameCount">0</span></div>
</div>
<div id="textContent">
  <div id="textContentHeader">
    <span id="textContentTitle"></span>
    <button class="btn" id="copyBtn" style="display: inline-block; padding: 6px 16px; font-size: 0.85rem; background: #3b82f6;">Copy</button>
  </div>
  <pre id="textContentBody"></pre>
</div>
<div class="buttons">
  <button class="btn" id="downloadBtn">Download File</button>
  <button class="btn" id="resetBtn">Reset</button>
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
  const textContent = document.getElementById('textContent');
  const textContentTitle = document.getElementById('textContentTitle');
  const textContentBody = document.getElementById('textContentBody');
  const copyBtn = document.getElementById('copyBtn');

  let ws;
  let frames = 0;
  let completed = false;
  let scanning = false;
  let lastSent = '';
  let totalSize = 0;

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
          statusText.textContent = 'Scanning... Total data: ' + totalSize + ' bytes';
        }
        break;
      case 'completed':
        completed = true;
        scanning = false;
        totalSize = resp.total;
        progressFill.style.width = '100%';
        const filename = resp.filename || 'output.bin';
        statusText.textContent = 'Decoding complete! File: ' + filename + ' (' + totalSize + ' bytes)';

        if (resp.isText && resp.content !== undefined) {
          textContentTitle.textContent = filename;
          textContentBody.textContent = resp.content;
          textContent.style.display = 'block';
          copyBtn.style.display = 'inline-block';
          downloadBtn.style.display = 'none';
        } else {
          textContent.style.display = 'none';
          copyBtn.style.display = 'none';
          downloadBtn.style.display = 'inline-block';
        }
        resetBtn.style.display = 'inline-block';
        break;
      case 'error':
        // non-fatal, keep scanning
        break;
      case 'reset':
        frames = 0;
        frameCount.textContent = '0';
        completed = false;
        totalSize = 0;
        progressFill.style.width = '0%';
        statusText.textContent = 'Reset. Point camera at animated QR code.';
        downloadBtn.style.display = 'none';
        resetBtn.style.display = 'none';
        textContent.style.display = 'none';
        copyBtn.style.display = 'none';
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

  copyBtn.addEventListener('click', async () => {
    try {
      await navigator.clipboard.writeText(textContentBody.textContent);
      copyBtn.textContent = 'Copied!';
      setTimeout(() => {
        copyBtn.textContent = 'Copy';
      }, 2000);
    } catch (err) {
      copyBtn.textContent = 'Failed';
      setTimeout(() => {
        copyBtn.textContent = 'Copy';
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
        // draw bounding box
        overlayCtx.strokeStyle = '#38bdf8';
        overlayCtx.lineWidth = 3;
        overlayCtx.beginPath();
        overlayCtx.moveTo(code.location.topLeftCorner.x, code.location.topLeftCorner.y);
        overlayCtx.lineTo(code.location.topRightCorner.x, code.location.topRightCorner.y);
        overlayCtx.lineTo(code.location.bottomRightCorner.x, code.location.bottomRightCorner.y);
        overlayCtx.lineTo(code.location.bottomLeftCorner.x, code.location.bottomLeftCorner.y);
        overlayCtx.closePath();
        overlayCtx.stroke();

        // avoid sending duplicate consecutive frames
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
