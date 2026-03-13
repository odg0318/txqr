package com.github.divan.txqr;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Base64;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.journeyapps.barcodescanner.CaptureManager;
import com.journeyapps.barcodescanner.DecoratedBarcodeView;

import txqr.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.Inflater;

public class MainActivity extends Activity {

    private static final int PERMISSION_REQUESTS = 1;
    private static final int PERMISSION_CAMERA = 0;

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

    private int frameCount = 0;
    private String decodedData = null;
    private String decodedFilename = null;

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

        // Buttons layout
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
        previewButton.setOnClickListener(v -> showPreview());
        buttonLayout.addView(previewButton);

        copyButton = new Button(this);
        copyButton.setText("Copy");
        copyButton.setVisibility(View.GONE);
        copyButton.setOnClickListener(v -> copyToClipboard());
        buttonLayout.addView(copyButton);

        layout.addView(buttonLayout);

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
        decoder.reset();
        frameCount = 0;
        decodedData = null;
        decodedFilename = null;
        frameCountText.setText("Frames: 0");
        statusText.setText("Reset. Point camera at QR code.");
        progressBar.setProgress(0);
        downloadButton.setVisibility(View.GONE);
        resetButton.setVisibility(View.GONE);
        previewButton.setVisibility(View.GONE);
        copyButton.setVisibility(View.GONE);
        textScrollView.setVisibility(View.GONE);
        barcodeView.resume();
    }

    private void showPreview() {
        if (textScrollView.getVisibility() == View.VISIBLE) {
            textScrollView.setVisibility(View.GONE);
            previewButton.setText("Preview");
        } else {
            textScrollView.setVisibility(View.VISIBLE);
            previewButton.setText("Hide");
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
    }
}
