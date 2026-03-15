package com.github.divan.txqr;

import android.Manifest;
import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.journeyapps.barcodescanner.CaptureManager;
import com.journeyapps.barcodescanner.DecoratedBarcodeView;

import txqr.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

public class MainActivity extends Activity {

    private static final int PERMISSION_REQUESTS = 1;
    private static final int PERMISSION_CAMERA = 0;
    private static final int QR_SIZE = 750;  // 2.5x larger
    private static final int FRAME_DELAY_MS = 50; // 20 FPS (much faster)

    private Decoder decoder;
    private DecoratedBarcodeView barcodeView;
    private TextView statusText;
    private TextView frameCountText;
    private ProgressBar progressBar;
    private Button downloadButton;
    private Button resetButton;
    private Button previewButton;
    private Button copyButton;
    private ScrollView textScrollView;
    private TextView textContentView;
    private ImageView qrDisplayView;
    private LinearLayout qrContainerLayout;

    // Read/Write mode containers
    private LinearLayout readContainer;
    private LinearLayout writeContainer;
    private Button readTabButton;
    private Button writeTabButton;
    private android.widget.EditText textInput;
    private Button generateButton;

    private int frameCount = 0;
    private String decodedData = null;
    private String decodedFilename = null;

    // Store raw scanned QR chunks for relay
    private ArrayList<String> scannedChunks = new ArrayList<>();

    // Pre-generated QR bitmaps for faster animation
    private ArrayList<Bitmap> qrBitmaps = new ArrayList<>();

    private Handler relayHandler = new Handler(Looper.getMainLooper());
    private Runnable relayRunnable;
    private int currentRelayFrame = 0;
    private boolean isRelaying = false;

    // Modal dialog for QR display
    private Dialog qrDialog;
    private ImageView qrDialogImageView;
    private Handler qrDialogHandler = new Handler(Looper.getMainLooper());
    private Runnable qrDialogRunnable;
    private boolean isQrDialogAnimating = false;
    private int currentQrDialogFrame = 0;
    private ArrayList<Bitmap> qrDialogBitmaps = new ArrayList<>();

    // Preview mode: 0 = show text, 1 = relay, 2 = hide
    private int previewMode = 0;

    private CaptureManager capture;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        decoder = Txqr.newDecoder();

        // Create UI programmatically
        createUI();

        // Check camera permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA},
                    PERMISSION_CAMERA);
        } else {
            initializeBarcodeView();
        }
    }

    private void createUI() {
        // Create main layout
        android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        layout.setPadding(16, 16, 16, 16);

        // ===== Tab Layout =====
        android.widget.LinearLayout tabLayout = new android.widget.LinearLayout(this);
        tabLayout.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        tabLayout.setPadding(0, 0, 0, 16);

        readTabButton = new Button(this);
        readTabButton.setText("Read");
        readTabButton.setBackgroundColor(0xFF3B82F6); // Blue for active
        readTabButton.setTextColor(0xFFFFFFFF);
        readTabButton.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));
        readTabButton.setOnClickListener(v -> switchToReadMode());
        tabLayout.addView(readTabButton);

        writeTabButton = new Button(this);
        writeTabButton.setText("Write");
        writeTabButton.setBackgroundColor(0xFF1E293B); // Dark for inactive
        writeTabButton.setTextColor(0xFF94A3B8);
        writeTabButton.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));
        writeTabButton.setOnClickListener(v -> switchToWriteMode());
        tabLayout.addView(writeTabButton);

        layout.addView(tabLayout);

        // ===== Read Mode Container =====
        readContainer = new android.widget.LinearLayout(this);
        readContainer.setOrientation(android.widget.LinearLayout.VERTICAL);

        // Status text
        statusText = new TextView(this);
        statusText.setText("Initializing camera...");
        statusText.setTextSize(14);
        statusText.setPadding(0, 0, 0, 16);
        readContainer.addView(statusText);

        // Progress bar
        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setPadding(0, 0, 0, 16);
        readContainer.addView(progressBar);

        // Frame count
        frameCountText = new TextView(this);
        frameCountText.setText("Frames: 0");
        frameCountText.setTextSize(12);
        frameCountText.setPadding(0, 0, 0, 16);
        readContainer.addView(frameCountText);

        // Barcode view
        barcodeView = new DecoratedBarcodeView(this);
        android.widget.LinearLayout.LayoutParams params =
                new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        600
                );
        barcodeView.setLayoutParams(params);
        readContainer.addView(barcodeView);

        // Buttons layout (horizontal scrollable)
        android.widget.HorizontalScrollView buttonScrollLayout = new android.widget.HorizontalScrollView(this);
        buttonScrollLayout.setFillViewport(true);

        android.widget.LinearLayout buttonLayout = new android.widget.LinearLayout(this);
        buttonLayout.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        buttonLayout.setPadding(0, 16, 0, 0);

        downloadButton = new Button(this);
        downloadButton.setText("Download");
        downloadButton.setVisibility(View.GONE);
        downloadButton.setOnClickListener(v -> downloadFile());
        buttonLayout.addView(downloadButton);

        resetButton = new Button(this);
        resetButton.setText("Reset");
        resetButton.setVisibility(View.GONE);
        resetButton.setOnClickListener(v -> resetDecoder());
        buttonLayout.addView(resetButton);

        previewButton = new Button(this);
        previewButton.setText("Preview");
        previewButton.setVisibility(View.GONE);
        previewButton.setOnClickListener(v -> togglePreview());
        buttonLayout.addView(previewButton);

        copyButton = new Button(this);
        copyButton.setText("Copy");
        copyButton.setVisibility(View.GONE);
        copyButton.setOnClickListener(v -> copyToClipboard());
        buttonLayout.addView(copyButton);

        buttonScrollLayout.addView(buttonLayout);
        readContainer.addView(buttonScrollLayout);

        layout.addView(readContainer);

        // ===== Write Mode Container =====
        writeContainer = new android.widget.LinearLayout(this);
        writeContainer.setOrientation(android.widget.LinearLayout.VERTICAL);
        writeContainer.setVisibility(View.GONE);  // Initially hidden

        // Write mode status
        TextView writeStatusText = new TextView(this);
        writeStatusText.setText("Enter text to encode as QR code");
        writeStatusText.setTextSize(14);
        writeStatusText.setPadding(0, 0, 0, 16);
        writeContainer.addView(writeStatusText);

        // Text input area
        android.widget.ScrollView inputScrollView = new android.widget.ScrollView(this);
        inputScrollView.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1.0f));  // Takes remaining space

        textInput = new android.widget.EditText(this);
        textInput.setHint("Enter text here...");
        textInput.setTextSize(14);
        textInput.setPadding(16, 16, 16, 16);
        textInput.setBackgroundColor(0xFF1E293B);
        textInput.setTextColor(0xFFE2E8F0);
        textInput.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        textInput.setMinLines(10);
        inputScrollView.addView(textInput);
        writeContainer.addView(inputScrollView);

        // Buttons layout (Generate + Clear)
        android.widget.LinearLayout writeButtonsLayout = new android.widget.LinearLayout(this);
        writeButtonsLayout.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        writeButtonsLayout.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));

        // Generate button
        generateButton = new Button(this);
        generateButton.setText("GENERATE");
        generateButton.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                0,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                1.0f));
        generateButton.setOnClickListener(v -> generateQRFromText());
        writeButtonsLayout.addView(generateButton);

        // Clear button
        Button clearButton = new Button(this);
        clearButton.setText("CLEAR");
        clearButton.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                0,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                1.0f));
        clearButton.setOnClickListener(v -> clearWriteInput());
        writeButtonsLayout.addView(clearButton);

        writeContainer.addView(writeButtonsLayout);

        layout.addView(writeContainer);

        // ===== Shared Components =====
        // QR display container (for relay/write mode)
        qrContainerLayout = new LinearLayout(this);
        qrContainerLayout.setOrientation(android.widget.LinearLayout.VERTICAL);
        qrContainerLayout.setPadding(0, 16, 0, 0);
        qrContainerLayout.setVisibility(View.GONE);

        qrDisplayView = new ImageView(this);
        qrDisplayView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        android.widget.LinearLayout.LayoutParams qrParams =
                new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        900  // Larger height for bigger QR
                );
        qrDisplayView.setLayoutParams(qrParams);
        qrContainerLayout.addView(qrDisplayView);

        layout.addView(qrContainerLayout);

        // Text content view (scrollable) - for read mode
        textScrollView = new ScrollView(this);
        android.widget.LinearLayout.LayoutParams scrollParams =
                new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                );
        scrollParams.setMargins(0, 16, 0, 0);
        textScrollView.setLayoutParams(scrollParams);
        textScrollView.setVisibility(View.GONE);

        textContentView = new TextView(this);
        textContentView.setTextSize(12);
        textContentView.setPadding(16, 16, 16, 16);
        textContentView.setBackgroundColor(0xFF1E293B); // dark background
        textContentView.setTextColor(0xFFE2E8F0); // light text
        textScrollView.addView(textContentView);

        layout.addView(textScrollView);

        // Footer with build info
        TextView footerText = new TextView(this);
        footerText.setText("Build: " + getBuildInfo());
        footerText.setTextSize(10);
        footerText.setTextColor(0xFF888888);
        footerText.setGravity(android.view.Gravity.CENTER);
        footerText.setPadding(0, 8, 0, 0);
        layout.addView(footerText);

        setContentView(layout);
    }

    private void initializeBarcodeView() {
        barcodeView.decodeContinuous(callback -> {
            if (callback == null || callback.getText() == null) {
                return;
            }

            String data = callback.getText();
            processFrame(data);
        });
    }

    private void processFrame(String data) {
        try {
            decoder.decode(data);
        } catch (Exception e) {
            android.util.Log.e("txqr", "Decode error: " + e.getMessage());
            // Invalid frame, skip
            return;
        }

        // Store raw QR chunk for relay
        scannedChunks.add(data);

        frameCount++;
        frameCountText.setText("Frames: " + frameCount);

        // Update progress
        long progress = decoder.progress();
        progressBar.setProgress((int) progress);

        long total = decoder.length();
        if (total > 0) {
            statusText.setText("Scanning... " + total + " bytes (Progress: " + progress + "%)");
        }

        // Log progress every 50 frames or when progress reaches 100
        if (frameCount % 50 == 0 || progress >= 100) {
            android.util.Log.d("txqr", "Frame " + frameCount + ", Progress: " + progress + "%, Completed: " + decoder.isCompleted());
        }

        if (decoder.isCompleted()) {
            android.util.Log.d("txqr", "Decoding completed!");
            barcodeView.pause();
            handleCompletion();
        }
    }

    private void handleCompletion() {
        try {
            String b64str = decoder.data();

            android.util.Log.d("txqr", "Fountain result length: " + b64str.length());
            android.util.Log.d("txqr", "Fountain result preview: " + b64str.substring(0, Math.min(100, b64str.length())));

            // First base64 decode (outer layer)
            byte[] raw = Base64.decode(b64str, Base64.DEFAULT);
            String payload = new String(raw);

            // Parse "<filename>\n<base64_data>"
            int newlineIdx = payload.indexOf('\n');
            if (newlineIdx == -1) {
                statusText.setText("Error: Invalid data format (no newline)");
                android.util.Log.e("txqr", "No newline found in result");
                return;
            }

            decodedFilename = payload.substring(0, newlineIdx);
            String b64Data = payload.substring(newlineIdx + 1);

            android.util.Log.d("txqr", "Filename: " + decodedFilename);
            android.util.Log.d("txqr", "Base64 data length: " + b64Data.length());

            // Second base64 decode (inner layer - compressed data)
            byte[] compressed = Base64.decode(b64Data, Base64.DEFAULT);

            // Flate decompress
            byte[] decompressed = decompressFlate(compressed);

            decodedData = new String(decompressed);
            statusText.setText("Complete! " + decodedFilename + " (" + decompressed.length + " bytes)");
            progressBar.setProgress(100);

            // Set text content
            textContentView.setText(decodedData);

            // Pre-generate all QR bitmaps in background to avoid UI hang
            generateQRBitmapsInBackground();

            // Reset preview mode - start at mode 2 (next will be 0=Preview)
            previewMode = 2;
            previewButton.setText("Preview");
            textScrollView.setVisibility(View.GONE);  // Hidden until Preview is clicked

            // Show buttons
            downloadButton.setVisibility(View.VISIBLE);
            resetButton.setVisibility(View.VISIBLE);
            previewButton.setVisibility(View.VISIBLE);
            copyButton.setVisibility(View.VISIBLE);

            Toast.makeText(this, "Decoding completed!", Toast.LENGTH_SHORT).show();

        } catch (Exception e) {
            statusText.setText("Error: " + e.getMessage());
            android.util.Log.e("txqr", "Error in handleCompletion", e);
        }
    }

    private byte[] decompressFlate(byte[] compressed) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];

        try {
            // Try raw deflate first (no header)
            Inflater inflater = new Inflater(true);
            inflater.setInput(compressed);
            while (!inflater.finished()) {
                int count = inflater.inflate(buffer);
                baos.write(buffer, 0, count);
            }
            inflater.end();
            return baos.toByteArray();
        } catch (java.util.zip.DataFormatException e) {
            baos.reset();
        }

        // Try with header
        try {
            Inflater inflater = new Inflater();
            inflater.setInput(compressed);
            while (!inflater.finished()) {
                int count = inflater.inflate(buffer);
                baos.write(buffer, 0, count);
            }
            inflater.end();
        } catch (java.util.zip.DataFormatException e) {
            throw new IOException("Decompression failed: " + e.getMessage(), e);
        }

        return baos.toByteArray();
    }

    private void downloadFile() {
        if (decodedData == null || decodedFilename == null) {
            Toast.makeText(this, "No data to download", Toast.LENGTH_SHORT).show();
            return;
        }

        // Save to external storage
        try {
            java.io.File dir = new java.io.File(
                    android.os.Environment.getExternalStoragePublicDirectory(
                            android.os.Environment.DIRECTORY_DOWNLOADS),
                    decodedFilename
            );

            java.io.FileWriter writer = new java.io.FileWriter(dir);
            writer.write(decodedData);
            writer.close();

            Toast.makeText(this, "Saved to: " + dir.getAbsolutePath(), Toast.LENGTH_LONG).show();
        } catch (IOException e) {
            Toast.makeText(this, "Error saving file: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void resetDecoder() {
        stopRelay();
        decoder.reset();
        frameCount = 0;
        scannedChunks.clear();
        qrBitmaps.clear();
        decodedData = null;
        decodedFilename = null;
        previewMode = 0;
        frameCountText.setText("Frames: 0");
        statusText.setText("Reset. Point camera at QR code.");
        progressBar.setProgress(0);
        downloadButton.setVisibility(View.GONE);
        resetButton.setVisibility(View.GONE);
        previewButton.setVisibility(View.GONE);
        copyButton.setVisibility(View.GONE);
        textScrollView.setVisibility(View.GONE);
        qrContainerLayout.setVisibility(View.GONE);
        barcodeView.setVisibility(View.VISIBLE);
        barcodeView.resume();
    }

    private void togglePreview() {
        // Cycle through modes: 0=Preview(text) -> 1=Relay -> 2=Hide -> 0=Preview
        previewMode = (previewMode + 1) % 3;

        // Stop relay if running
        if (previewMode != 1) {
            stopRelay();
        }

        switch (previewMode) {
            case 0: // Show text preview
                qrContainerLayout.setVisibility(View.GONE);
                textScrollView.setVisibility(View.VISIBLE);
                previewButton.setText("Relay");
                statusText.setText("Complete! " + decodedFilename);
                break;

            case 1: // Relay mode
                stopRelay();
                textScrollView.setVisibility(View.GONE);
                qrContainerLayout.setVisibility(View.VISIBLE);
                previewButton.setText("Hide");
                statusText.setText("Relaying... Point another camera here");
                startQRAnimation();
                break;

            case 2: // Hide all
                qrContainerLayout.setVisibility(View.GONE);
                textScrollView.setVisibility(View.GONE);
                previewButton.setText("Preview");
                statusText.setText("Complete! " + decodedFilename);
                break;
        }
    }

    private void copyToClipboard() {
        if (decodedData == null) {
            Toast.makeText(this, "No data to copy", Toast.LENGTH_SHORT).show();
            return;
        }

        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText("Decoded Data", decodedData);
        clipboard.setPrimaryClip(clip);

        Toast.makeText(this, "Copied to clipboard!", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode == PERMISSION_CAMERA) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                initializeBarcodeView();
            } else {
                statusText.setText("Camera permission denied");
            }
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (barcodeView != null) {
            barcodeView.resume();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (barcodeView != null) {
            barcodeView.pause();
        }
        stopRelay();
        stopQRDialogAnimation();
        if (qrDialog != null && qrDialog.isShowing()) {
            qrDialog.dismiss();
        }
    }

    private void startQRAnimation() {
        if (qrBitmaps.isEmpty()) {
            Toast.makeText(this, "No data to relay", Toast.LENGTH_SHORT).show();
            return;
        }

        isRelaying = true;
        currentRelayFrame = 0;

        // Start frame animation with pre-generated bitmaps
        relayRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isRelaying) return;

                // Use pre-generated QR bitmap
                Bitmap qrBitmap = qrBitmaps.get(currentRelayFrame);
                if (qrBitmap != null) {
                    qrDisplayView.setImageBitmap(qrBitmap);
                }

                currentRelayFrame = (currentRelayFrame + 1) % qrBitmaps.size();

                // Schedule next frame
                relayHandler.postDelayed(this, FRAME_DELAY_MS);
            }
        };

        relayHandler.post(relayRunnable);
    }

    private void generateQRBitmaps() {
        qrBitmaps.clear();
        for (String chunk : scannedChunks) {
            Bitmap qrBitmap = generateQRCode(chunk);
            if (qrBitmap != null) {
                qrBitmaps.add(qrBitmap);
            }
        }
        android.util.Log.d("txqr", "Generated " + qrBitmaps.size() + " QR bitmaps");
    }

    private void generateQRBitmapsInBackground() {
        android.util.Log.d("txqr", "Starting background QR bitmap generation...");
        statusText.setText("Complete! Pre-generating QR bitmaps...");

        new Thread(() -> {
            generateQRBitmaps();

            // Update UI on main thread after completion
            relayHandler.post(() -> {
                statusText.setText("Complete! " + decodedFilename);
                android.util.Log.d("txqr", "Background QR bitmap generation completed");
            });
        }).start();
    }

    private void stopRelay() {
        isRelaying = false;
        if (relayHandler != null && relayRunnable != null) {
            relayHandler.removeCallbacks(relayRunnable);
        }
    }

    private Bitmap generateQRCode(String content) {
        try {
            QRCodeWriter qrCodeWriter = new QRCodeWriter();
            BitMatrix bitMatrix = qrCodeWriter.encode(content, BarcodeFormat.QR_CODE, QR_SIZE, QR_SIZE);

            int width = bitMatrix.getWidth();
            int height = bitMatrix.getHeight();
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565);

            for (int x = 0; x < width; x++) {
                for (int y = 0; y < height; y++) {
                    bitmap.setPixel(x, y, bitMatrix.get(x, y) ? Color.BLACK : Color.WHITE);
                }
            }

            return bitmap;
        } catch (Exception e) {
            android.util.Log.e("txqr", "Error generating QR: " + e.getMessage());
            return null;
        }
    }

    // ===== Tab Switching =====

    private void switchToReadMode() {
        writeContainer.setVisibility(View.GONE);
        readContainer.setVisibility(View.VISIBLE);

        // Update tab button styles
        readTabButton.setBackgroundColor(0xFF3B82F6); // Blue for active
        readTabButton.setTextColor(0xFFFFFFFF);
        writeTabButton.setBackgroundColor(0xFF1E293B); // Dark for inactive
        writeTabButton.setTextColor(0xFF94A3B8);

        // Stop QR dialog if running
        stopQRDialogAnimation();
        if (qrDialog != null && qrDialog.isShowing()) {
            qrDialog.dismiss();
        }

        // Resume barcode scanner
        barcodeView.resume();
    }

    private void switchToWriteMode() {
        readContainer.setVisibility(View.GONE);
        writeContainer.setVisibility(View.VISIBLE);

        // Update tab button styles
        writeTabButton.setBackgroundColor(0xFF3B82F6); // Blue for active
        writeTabButton.setTextColor(0xFFFFFFFF);
        readTabButton.setBackgroundColor(0xFF1E293B); // Dark for inactive
        readTabButton.setTextColor(0xFF94A3B8);

        // Pause barcode scanner and stop relay
        barcodeView.pause();
        stopRelay();

        // Hide QR and text displays
        qrContainerLayout.setVisibility(View.GONE);
        textScrollView.setVisibility(View.GONE);
    }

    private void clearWriteInput() {
        textInput.setText("");
        Toast.makeText(this, "Cleared", Toast.LENGTH_SHORT).show();
    }

    // ===== Write Mode: Text Encoding =====

    private String encodePayload(String text) throws IOException {
        // 1. Flate compress
        byte[] compressed = compressFlate(text.getBytes());

        // 2. Base64 encode
        String b64Data = Base64.encodeToString(compressed, Base64.NO_WRAP);

        // 3. Add filename (default: "text.txt")
        String payload = "text.txt\n" + b64Data;

        // 4. Double base64 encode
        return Base64.encodeToString(payload.getBytes(), Base64.NO_WRAP);
    }

    private byte[] compressFlate(byte[] data) throws IOException {
        java.util.zip.Deflater deflater = new java.util.zip.Deflater(java.util.zip.Deflater.BEST_COMPRESSION);
        deflater.setInput(data);
        deflater.finish();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];

        while (!deflater.finished()) {
            int count = deflater.deflate(buffer);
            baos.write(buffer, 0, count);
        }

        deflater.end();
        return baos.toByteArray();
    }

    // ===== Write Mode: QR Generation =====

    private void generateQRFromText() {
        String text = textInput.getText().toString();
        if (text.isEmpty()) {
            Toast.makeText(this, "Please enter text", Toast.LENGTH_SHORT).show();
            return;
        }

        // Hide keyboard
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(textInput.getWindowToken(), 0);

        // Show loading status
        Toast.makeText(this, "Generating QR codes...", Toast.LENGTH_SHORT).show();

        new Thread(() -> {
            try {
                // Encode payload (compress + base64)
                String payload = encodePayload(text);

                // Encode with fountain codes (gomobile)
                Encoder encoder = Txqr.newEncoder(100);  // chunk size
                StringList chunks = encoder.encode(payload);

                android.util.Log.d("txqr", "Generated " + chunks.size() + " chunks");

                // Generate QR bitmaps for dialog
                final ArrayList<Bitmap> writeQrBitmaps = new ArrayList<>();
                for (int i = 0; i < chunks.size(); i++) {
                    String chunk = chunks.get(i);
                    Bitmap qrBitmap = generateQRCode(chunk);
                    if (qrBitmap != null) {
                        writeQrBitmaps.add(qrBitmap);
                    }
                }

                android.util.Log.d("txqr", "Generated " + writeQrBitmaps.size() + " QR bitmaps");

                // Show QR dialog on main thread
                relayHandler.post(() -> {
                    qrDialogBitmaps.clear();
                    qrDialogBitmaps.addAll(writeQrBitmaps);
                    showQRDialog();
                    Toast.makeText(this, "QR codes generated!", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                relayHandler.post(() -> {
                    Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    android.util.Log.e("txqr", "Error generating QR", e);
                });
            }
        }).start();
    }

    // ===== QR Modal Dialog =====

    private void showQRDialog() {
        // Stop any existing animation
        stopQRDialogAnimation();

        // Create dialog if not exists
        if (qrDialog == null) {
            createQRDialog();
        }

        // Reset animation state
        currentQrDialogFrame = 0;
        isQrDialogAnimating = true;

        // Show dialog
        qrDialog.show();

        // Start animation
        startQRDialogAnimation();
    }

    private void createQRDialog() {
        qrDialog = new Dialog(this);

        // Create layout programmatically
        android.widget.LinearLayout dialogLayout = new android.widget.LinearLayout(this);
        dialogLayout.setOrientation(android.widget.LinearLayout.VERTICAL);
        dialogLayout.setPadding(24, 24, 24, 24);
        dialogLayout.setBackgroundColor(0xFF000000);

        // Title
        TextView titleText = new TextView(this);
        titleText.setText("Scan this QR code");
        titleText.setTextSize(18);
        titleText.setTextColor(0xFFFFFFFF);
        titleText.setGravity(android.view.Gravity.CENTER);
        titleText.setPadding(0, 0, 0, 16);
        dialogLayout.addView(titleText);

        // QR Image
        qrDialogImageView = new ImageView(this);
        qrDialogImageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        android.widget.LinearLayout.LayoutParams imageParams =
                new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        800
                );
        qrDialogImageView.setLayoutParams(imageParams);
        dialogLayout.addView(qrDialogImageView);

        // Info text
        TextView infoText = new TextView(this);
        infoText.setText("QR frames: " + qrDialogBitmaps.size());
        infoText.setTextSize(14);
        infoText.setTextColor(0xFFCCCCCC);
        infoText.setGravity(android.view.Gravity.CENTER);
        infoText.setPadding(0, 16, 0, 16);
        dialogLayout.addView(infoText);

        // Close button
        Button closeButton = new Button(this);
        closeButton.setText("Close");
        closeButton.setBackgroundColor(0xFF3B82F6);
        closeButton.setTextColor(0xFFFFFFFF);
        closeButton.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));
        closeButton.setOnClickListener(v -> {
            stopQRDialogAnimation();
            qrDialog.dismiss();
        });
        dialogLayout.addView(closeButton);

        qrDialog.setContentView(dialogLayout);

        // Cancel animation on dismiss
        qrDialog.setOnDismissListener(dialog -> {
            stopQRDialogAnimation();
        });

        // Set dialog size
        android.view.Window window = qrDialog.getWindow();
        if (window != null) {
            window.setLayout(
                    android.view.WindowManager.LayoutParams.MATCH_PARENT,
                    android.view.WindowManager.LayoutParams.WRAP_CONTENT
            );
        }
    }

    private void startQRDialogAnimation() {
        if (qrDialogBitmaps.isEmpty()) {
            return;
        }

        isQrDialogAnimating = true;
        currentQrDialogFrame = 0;

        qrDialogRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isQrDialogAnimating) return;

                Bitmap qrBitmap = qrDialogBitmaps.get(currentQrDialogFrame);
                if (qrBitmap != null && qrDialogImageView != null) {
                    qrDialogImageView.setImageBitmap(qrBitmap);
                }

                currentQrDialogFrame = (currentQrDialogFrame + 1) % qrDialogBitmaps.size();

                qrDialogHandler.postDelayed(this, FRAME_DELAY_MS);
            }
        };

        qrDialogHandler.post(qrDialogRunnable);
    }

    private void stopQRDialogAnimation() {
        isQrDialogAnimating = false;
        if (qrDialogHandler != null && qrDialogRunnable != null) {
            qrDialogHandler.removeCallbacks(qrDialogRunnable);
        }
    }

    private String getBuildInfo() {
        // Simple build info - you can enhance this with actual build time
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm");
        return sdf.format(new java.util.Date());
    }
}
