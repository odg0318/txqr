 package main
 
 import (
     "encoding/base64"
     "encoding/json"
     "flag"
     "fmt"
     "log"
     "net/http"
     "os/exec"
     "runtime"
     "sync"
 
     "github.com/divan/txqr"
     "github.com/gorilla/websocket"
 )
 
 var (
     decoder   *txqr.Decoder
     decoderMu sync.Mutex
     decoded   []byte // final binary result after base64 decode
 )
 
 func main() {
     port := flag.Int("port", 8080, "HTTP server port")
     noBrowser := flag.Bool("n", false, "Don't open browser automatically")
     flag.Parse()
 
     decoder = txqr.NewDecoder()
 
     addr := fmt.Sprintf(":%d", *port)
     url := fmt.Sprintf("http://localhost%s", addr)
 
     http.HandleFunc("/", handleIndex)
     http.HandleFunc("/ws", handleWS)
     http.HandleFunc("/download", handleDownload)
 
     if !*noBrowser {
         go startBrowser(url)
     }
 
     log.Printf("Starting txqr-reader on %s", url)
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
         // fountain decode done, now base64 decode to get original binary
         b64str := decoder.Data()
         raw, err := base64.StdEncoding.DecodeString(b64str)
         if err != nil {
             return wsResponse{Type: "error", Error: fmt.Sprintf("base64 decode: %v", err)}
         }
         decoded = raw
         log.Printf("[INFO] Decoding completed! Size: %d bytes", len(decoded))
         return wsResponse{Type: "completed", Total: len(decoded)}
     }
 
     return wsResponse{Type: "progress", Total: decoder.Total()}
 }
 
 func handleDownload(w http.ResponseWriter, r *http.Request) {
     decoderMu.Lock()
     data := decoded
     decoderMu.Unlock()
 
     if data == nil {
         http.Error(w, "No data available. Decoding not yet completed.", http.StatusNotFound)
         return
     }
 
     filename := r.URL.Query().Get("filename")
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
         statusText.textContent = 'Decoding complete! File size: ' + totalSize + ' bytes';
         downloadBtn.style.display = 'inline-block';
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
