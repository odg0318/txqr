package com.github.divan.txqr;

import android.Manifest;
import android.app.Activity;
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

        // Status text
        statusText = new TextView(this);
        statusText.setText("Initializing camera...");
        statusText.setTextSize(14);
        statusText.setPadding(0, 0, 0, 16);
        layout.addView(statusText);

        // Progress bar
        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setPadding(0, 0, 0, 16);
        layout.addView(progressBar);

        // Frame count
        frameCountText = new TextView(this);
        frameCountText.setText("Frames: 0");
        frameCountText.setTextSize(12);
        frameCountText.setPadding(0, 0, 0, 16);
        layout.addView(frameCountText);

        // Barcode view
        barcodeView = new DecoratedBarcodeView(this);
        android.widget.LinearLayout.LayoutParams params =
                new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        600
                );
        barcodeView.setLayoutParams(params);
        layout.addView(barcodeView);

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
        layout.addView(buttonScrollLayout);

        // Footer with build info
        TextView footerText = new TextView(this);
        footerText.setText("Build: " + getBuildInfo());
        footerText.setTextSize(10);
        footerText.setTextColor(0xFF888888);
        footerText.setGravity(android.view.Gravity.CENTER);
        footerText.setPadding(0, 8, 0, 0);
        layout.addView(footerText);

        // QR display container (for relay mode)
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

        // Text content view (scrollable)
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
                startRelay();
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
    }

    private void startRelay() {
        if (scannedChunks.isEmpty() || qrBitmaps.isEmpty()) {
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

    private String getBuildInfo() {
        // Simple build info - you can enhance this with actual build time
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm");
        return sdf.format(new java.util.Date());
    }
}
