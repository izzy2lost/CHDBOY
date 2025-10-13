package com.chdboy.utils;

import android.app.ActionBar;
import android.content.DialogInterface;

import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Environment;
import androidx.preference.PreferenceManager;
import java.lang.reflect.Field;
import java.nio.Buffer;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import com.chdboy.R;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.Spinner;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.nio.file.Paths;
import java.io.InputStreamReader;
import java.util.concurrent.Executors;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import android.net.Uri;
import android.provider.DocumentsContract;

public class Chdman {
    
    private enum Status {
        INITIALIZED,
        REINITIALIZED,
        RUNNING,
        COMPLETED,
    };

    private Handler handler;
    private String mode;
    private Status status;
    public static boolean deleteInput;
    private LinkedList<File> inputStack;
    private LinkedList<File> outputStack;
    private LinkedList<Thread> threadStack;
    private LinkedList<ArrayList<File>> cleanupStack;
    private Context mContext;
    private Uri destinationTreeUri;
    
    // Progress tracking for dialog updates
    private volatile int currentProgress = 0;
    private volatile String currentRatio = "--";
    private volatile String currentSpeed = "--";
    private volatile String currentFileName = "";
    private volatile AlertDialog activeDialog = null;
    
    // Cancellation support
    private volatile boolean isCancelled = false;
    private volatile ExecutorService currentExecutor = null;
    private volatile Thread currentCompressionThread = null;
    
    public Chdman(Context ctx) {
        this.mContext = ctx;
        this.mode = "";
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(mContext);
        deleteInput = sp.getBoolean("deletesource", true);
        this.handler = new Handler(Looper.getMainLooper());
        this.inputStack = new LinkedList<>();
        this.outputStack = new LinkedList<>();
        this.threadStack = new LinkedList<>();
        this.cleanupStack = new LinkedList<>();
        this.status = Status.INITIALIZED;
        this.destinationTreeUri = null;
    }
    
    public void setDestinationTreeUri(Uri uri) {
        this.destinationTreeUri = uri;
    }
    
    private void clean() {
        inputStack.clear();
        outputStack.clear();
        threadStack.clear();
        cleanupStack.clear();
        mode = "";
        status = Status.REINITIALIZED;
    }
    
    private AlertDialog createProgressDialog(String title) {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(mContext);
        AlertDialog dialog;
        builder.setTitle(title);
        builder.setCancelable(false);
        
        // Create custom progress layout with Material 3 styling
        android.widget.LinearLayout layout = new android.widget.LinearLayout(mContext);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        layout.setPadding(60, 40, 60, 40);

        // Adjust text contrast based on the active (light/dark) theme
        boolean isNightMode = (mContext.getResources().getConfiguration().uiMode
            & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        int primaryTextColor = ContextCompat.getColor(
            mContext,
            isNightMode ? R.color.md_theme_dark_onSurface : R.color.md_theme_light_onSurface
        );
        int secondaryTextColor = ContextCompat.getColor(
            mContext,
            isNightMode ? R.color.md_theme_dark_onSurfaceVariant : R.color.md_theme_light_onSurfaceVariant
        );
        
        // Main status text
        android.widget.TextView statusText = new android.widget.TextView(mContext);
        statusText.setText("Starting Smart Compress...");
        statusText.setTextSize(16);
        statusText.setTextColor(primaryTextColor);
        statusText.setId(android.R.id.message);
        android.widget.LinearLayout.LayoutParams statusParams = new android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        );
        statusParams.bottomMargin = 24;
        layout.addView(statusText, statusParams);
        
        // Progress bar with Material 3 styling
        android.widget.ProgressBar progressBar = new android.widget.ProgressBar(mContext, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setIndeterminate(false);
        progressBar.setMax(100);
        progressBar.setProgress(0);
        progressBar.setId(android.R.id.progress);
        // Set progress bar height and styling
        android.widget.LinearLayout.LayoutParams progressParams = new android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            24 // 24dp height for better visibility
        );
        progressParams.bottomMargin = 16;
        layout.addView(progressBar, progressParams);
        
        // Progress percentage text
        android.widget.TextView progressText = new android.widget.TextView(mContext);
        progressText.setText("0%");
        progressText.setTextSize(14);
        progressText.setTextColor(0xFFFFEF00); // Bright yellow like FAB background
        progressText.setGravity(android.view.Gravity.CENTER);
        progressText.setTypeface(null, android.graphics.Typeface.BOLD);
        progressText.setId(android.R.id.text1); // Use text1 for progress percentage
        android.widget.LinearLayout.LayoutParams progressTextParams = new android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        );
        progressTextParams.bottomMargin = 12;
        layout.addView(progressText, progressTextParams);
        
        // Compression stats container
        android.widget.LinearLayout statsLayout = new android.widget.LinearLayout(mContext);
        statsLayout.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        statsLayout.setGravity(android.view.Gravity.CENTER);
        
        // Compression ratio text
        android.widget.TextView ratioText = new android.widget.TextView(mContext);
        ratioText.setText("Ratio: --");
        ratioText.setTextSize(12);
        ratioText.setTextColor(0xFFFF5C00); // Bright orange for visibility
        ratioText.setId(android.R.id.text2); // Use text2 for ratio
        android.widget.LinearLayout.LayoutParams ratioParams = new android.widget.LinearLayout.LayoutParams(
            0,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            1.0f
        );
        ratioText.setGravity(android.view.Gravity.CENTER);
        statsLayout.addView(ratioText, ratioParams);
        
        // Separator
        android.widget.TextView separator = new android.widget.TextView(mContext);
        separator.setText("•");
        separator.setTextSize(12);
        separator.setTextColor(secondaryTextColor);
        separator.setGravity(android.view.Gravity.CENTER);
        android.widget.LinearLayout.LayoutParams sepParams = new android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        );
        sepParams.leftMargin = 8;
        sepParams.rightMargin = 8;
        statsLayout.addView(separator, sepParams);
        
        // Speed/time text
        android.widget.TextView speedText = new android.widget.TextView(mContext);
        speedText.setText("Speed: --");
        speedText.setTextSize(12);
        speedText.setTextColor(primaryTextColor);
        speedText.setId(android.R.id.summary); // Use summary for speed/time
        android.widget.LinearLayout.LayoutParams speedParams = new android.widget.LinearLayout.LayoutParams(
            0,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            1.0f
        );
        speedText.setGravity(android.view.Gravity.CENTER);
        statsLayout.addView(speedText, speedParams);
        
        layout.addView(statsLayout);
        
        // Add background processing note
        android.widget.TextView noteText = new android.widget.TextView(mContext);
        noteText.setText("💡 You can exit the app - compression will continue in background and notify when complete");
        noteText.setTextSize(10);
        noteText.setTextColor(secondaryTextColor);
        noteText.setGravity(android.view.Gravity.CENTER);
        noteText.setPadding(0, 16, 0, 0);
        noteText.setTypeface(null, android.graphics.Typeface.ITALIC);
        layout.addView(noteText);
        
        builder.setView(layout);
        
        // Add cancel button
        builder.setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialogInterface, int i) {
                cancelCompression();
            }
        });
        
        dialog = builder.create();
        return dialog;
    }
    
    private void showProgressDialog(AlertDialog dlg) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (!((android.app.Activity) mContext).isFinishing()) {
                    dlg.show();
                }
            }
        });
    }
    
    private void hideProgressDialog(AlertDialog dlg) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (dlg.isShowing()) {
                    dlg.dismiss();
                }
            }
        });
    }
    
    private void updateProgressDialog(AlertDialog dlg, String message, int progress) {
        updateProgressDialog(dlg, message, progress, null, null);
    }
    
    private void updateProgressDialog(AlertDialog dlg, String message, int progress, String ratio, String speed) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (dlg.isShowing()) {
                    // Update main status message
                    android.widget.TextView statusText = dlg.findViewById(android.R.id.message);
                    if (statusText != null) {
                        statusText.setText(message);
                    }
                    
                    // Update progress bar
                    android.widget.ProgressBar progressBar = dlg.findViewById(android.R.id.progress);
                    if (progressBar != null && progress >= 0) {
                        progressBar.setProgress(progress);
                    }
                    
                    // Update progress percentage text
                    android.widget.TextView progressText = dlg.findViewById(android.R.id.text1);
                    if (progressText != null && progress >= 0) {
                        progressText.setText(progress + "%");
                    }
                    
                    // Update compression ratio
                    android.widget.TextView ratioText = dlg.findViewById(android.R.id.text2);
                    if (ratioText != null && ratio != null) {
                        ratioText.setText("Ratio: " + ratio);
                    }
                    
                    // Update speed/time info
                    android.widget.TextView speedText = dlg.findViewById(android.R.id.summary);
                    if (speedText != null && speed != null) {
                        speedText.setText(speed);
                    }
                }
            }
        });
    }
    
    private void updateProgressDialog(AlertDialog dlg, String message) {
        updateProgressDialog(dlg, message, -1); // -1 means don't update progress
    }
    
    private String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        String pre = "KMGTPE".charAt(exp-1) + "";
        return String.format("%.1f %sB", bytes / Math.pow(1024, exp), pre);
    }
    
    private long calculateTotalInputSize(File inputFile) {
        String fileName = inputFile.getName().toLowerCase();
        
        // For CUE files, calculate total size of all referenced BIN files
        if (fileName.endsWith(".cue")) {
            long totalSize = 0;
            File parentDir = inputFile.getParentFile();
            if (parentDir != null) {
                try {
                    // Parse CUE file to find BIN files
                    java.io.FileInputStream fis = new java.io.FileInputStream(inputFile);
                    java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(fis));
                    String line;
                    while ((line = br.readLine()) != null) {
                        line = line.trim();
                        if (line.toUpperCase().startsWith("FILE ")) {
                            // Extract filename from FILE "filename.bin" BINARY
                            int firstQuote = line.indexOf('"');
                            int lastQuote = line.lastIndexOf('"');
                            if (firstQuote >= 0 && lastQuote > firstQuote) {
                                String binFileName = line.substring(firstQuote + 1, lastQuote);
                                File binFile = new File(parentDir, binFileName);
                                if (binFile.exists()) {
                                    totalSize += binFile.length();
                                    Log.d("Chdman", "CUE references: " + binFileName + " (" + formatFileSize(binFile.length()) + ")");
                                }
                            }
                        }
                    }
                    br.close();
                    fis.close();
                    
                    if (totalSize > 0) {
                        Log.i("Chdman", "Total CUE/BIN size: " + formatFileSize(totalSize));
                        return totalSize;
                    }
                } catch (Exception e) {
                    Log.e("Chdman", "Error calculating CUE total size: " + e.getMessage());
                }
            }
        }
        
        // For GDI files, calculate total size of all referenced files
        if (fileName.endsWith(".gdi")) {
            long totalSize = 0;
            File parentDir = inputFile.getParentFile();
            if (parentDir != null) {
                try {
                    java.io.FileInputStream fis = new java.io.FileInputStream(inputFile);
                    java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(fis));
                    String line;
                    int lineNo = 0;
                    while ((line = br.readLine()) != null) {
                        lineNo++;
                        if (lineNo == 1) continue; // Skip track count line
                        line = line.trim();
                        if (!line.isEmpty()) {
                            // Parse GDI line: track lba type sector fileName offset
                            String[] parts = line.split("\\s+");
                            if (parts.length >= 5) {
                                String refFileName = parts[4];
                                File refFile = new File(parentDir, refFileName);
                                if (refFile.exists()) {
                                    totalSize += refFile.length();
                                    Log.d("Chdman", "GDI references: " + refFileName + " (" + formatFileSize(refFile.length()) + ")");
                                }
                            }
                        }
                    }
                    br.close();
                    fis.close();
                    
                    if (totalSize > 0) {
                        Log.i("Chdman", "Total GDI size: " + formatFileSize(totalSize));
                        return totalSize;
                    }
                } catch (Exception e) {
                    Log.e("Chdman", "Error calculating GDI total size: " + e.getMessage());
                }
            }
        }
        
        // For ISO and BIN files, use actual file size
        return inputFile.length();
    }
    
    public void cancelCompression() {
        isCancelled = true;
        
        // Stop current compression thread
        if (currentCompressionThread != null) {
            currentCompressionThread.interrupt();
        }
        
        // Shutdown executor
        if (currentExecutor != null) {
            currentExecutor.shutdownNow();
        }
        
        // Clean up all files in external directory (android/data/...)
        int deletedCount = 0;
        try {
            File externalDir = mContext.getExternalFilesDir("");
            if (externalDir != null && externalDir.exists()) {
                File[] files = externalDir.listFiles();
                if (files != null) {
                    for (File file : files) {
                        if (file != null && file.exists()) {
                            boolean deleted = file.delete();
                            if (deleted) deletedCount++;
                            Log.d("Chdman", "Cleanup: " + file.getName() + (deleted ? " deleted" : " failed to delete"));
                        }
                    }
                }
            }
            Log.i("Chdman", "Cleaned up " + deletedCount + " files from external directory");
        } catch (Exception e) {
            Log.e("Chdman", "Error cleaning up files: " + e.getMessage());
        }
        
        // Clear stacks
        inputStack.clear();
        outputStack.clear();
        threadStack.clear();
        cleanupStack.clear();
        
        // Update UI
        handler.post(() -> {
            try {
                android.widget.Toast.makeText(mContext, "Compression cancelled - temporary files cleaned up", android.widget.Toast.LENGTH_SHORT).show();
                if (activeDialog != null && activeDialog.isShowing()) {
                    updateProgressDialog(activeDialog, "Cancelled", 0, "Stopped", "User cancelled");
                    new android.os.Handler().postDelayed(() -> {
                        if (activeDialog != null && activeDialog.isShowing()) {
                            activeDialog.dismiss();
                        }
                        activeDialog = null;
                    }, 2000); // Show "Cancelled" for 2 seconds before dismissing
                }
                com.chdboy.services.ChdmanService.updateIdle(mContext, "Cancelled");
            } catch (Exception ignored) {}
        });
        
        // Stop service
        try {
            mContext.stopService(new android.content.Intent(mContext, com.chdboy.services.ChdmanService.class));
        } catch (Exception ignored) {}
        
        // Reset status
        status = Status.INITIALIZED;
        
        Log.i("Chdman", "Compression cancelled by user");
    }
    
    private AlertDialog createModesDialog(String title) {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(mContext);
        AlertDialog dialog;
        builder.setTitle(title);
        builder.setCancelable(false);
        String[] modes = {"createcd", "createdvd"};
        final ArrayAdapter<String> adp = new ArrayAdapter<>(mContext, android.R.layout.simple_spinner_dropdown_item, modes);
        final Spinner sp = new Spinner(mContext);
        sp.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        sp.setAdapter(adp);
        builder.setView(sp);
        builder.setPositiveButton("Done", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                mode = sp.getSelectedItem().toString();
            }
        });
        dialog = builder.create();
        return dialog;
    }
    
    // Dialog UI removed to support background operation
    
    public void startCompression() {
        // Reset cancellation flag
        isCancelled = false;
        
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        currentExecutor = executor; // Track for cancellation
        
        final AlertDialog progressDialog = createProgressDialog("Smart Compress Progress");
        activeDialog = progressDialog; // Set for real-time updates
        showProgressDialog(progressDialog);
        
        // Start foreground service for persistent background execution
        try {
            com.chdboy.services.ChdmanService.ensureChannel(mContext);
            android.content.Intent svc = new android.content.Intent(mContext, com.chdboy.services.ChdmanService.class);
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                mContext.startForegroundService(svc);
            } else {
                mContext.startService(svc);
            }
            android.util.Log.d("Chdman", "Started foreground service");
        } catch (Exception e) {
            android.util.Log.e("Chdman", "Failed to start service: " + e.getMessage());
        }
        // Auto-select per job; no UI dialog to allow background execution
        executor.execute(new Runnable(){
            @Override
            public void run() {
                Thread processThread = null;
                File inputFile = null;    
                File outputFile = null;
                ArrayList<File> sidecarsToCleanup = null;
                final AlertDialog dialog = progressDialog;
                
                // Check for cancellation
                if (isCancelled) {
                    return;
                }
                
                if (threadStack.isEmpty())
                    status = Status.COMPLETED;
                if (status == Status.COMPLETED) {
                    activeDialog = null; // Clear dialog reference
                    clean();
                    
                    // Show completion dialog with OK button instead of auto-hiding
                    handler.post(() -> {
                        if (dialog != null && dialog.isShowing()) {
                            updateProgressDialog(dialog, "All files compressed successfully!", 100, "Complete", "Ready to use");
                            
                            // Add OK button to completed dialog
                            dialog.getButton(android.content.DialogInterface.BUTTON_NEGATIVE).setText("OK");
                            dialog.getButton(android.content.DialogInterface.BUTTON_NEGATIVE).setOnClickListener(v -> {
                                dialog.dismiss();
                            });
                        }
                    });
                    
                    // Clean up all files in external directory after completion
                    try {
                        File externalDir = mContext.getExternalFilesDir("");
                        if (externalDir != null && externalDir.exists()) {
                            File[] files = externalDir.listFiles();
                            if (files != null) {
                                int cleanupCount = 0;
                                for (File file : files) {
                                    if (file != null && file.exists() && file.delete()) {
                                        cleanupCount++;
                                    }
                                }
                                Log.i("Chdman", "Completion cleanup: deleted " + cleanupCount + " files");
                            }
                        }
                    } catch (Exception e) {
                        Log.e("Chdman", "Error in completion cleanup: " + e.getMessage());
                    }
                    
                    // Completion notifications
                    try { com.chdboy.services.ChdmanService.updateIdle(mContext, "Idle"); } catch (Exception ignored) {}
                    try { com.chdboy.services.ChdmanService.notifyDone(mContext, "Compression complete"); } catch (Exception ignored) {}
                    handler.post(new Runnable() {
                        @Override public void run() {
                            android.widget.Toast.makeText(mContext, "Compression complete", android.widget.Toast.LENGTH_SHORT).show();
                        }
                    });
                    // Stop foreground service
                    try {
                        mContext.stopService(new android.content.Intent(mContext, com.chdboy.services.ChdmanService.class));
                    } catch (Exception ignored) {}
                    return;
                }
                if (status != Status.RUNNING) {
                    status = Status.RUNNING;
                    handler.post(new Runnable() {
                        @Override public void run() {
                            android.widget.Toast.makeText(mContext, "Compression started", android.widget.Toast.LENGTH_SHORT).show();
                        }
                    });
                }    
                if (status == Status.RUNNING) {
                    // Check for cancellation before processing file
                    if (isCancelled) {
                        return;
                    }
                    
                    try {
                        processThread = threadStack.pop();
                        currentCompressionThread = processThread; // Track for cancellation
                        inputFile = inputStack.pop();  
                        outputFile = outputStack.pop();
                        sidecarsToCleanup = cleanupStack.pop();
                        String inputfileName = inputFile.getName();    
                        String outputfileName = outputFile.getName();
                        // Calculate progress based on queue position 
                        int totalFiles = inputStack.size() + 1; // Total files including current
                        int currentFileNumber = totalFiles - inputStack.size(); // Current file index (0-based)
                        int overallProgressPercent = totalFiles > 0 ? (currentFileNumber * 100) / totalFiles : 0;
                        
                        updateProgressDialog(dialog, 
                            String.format("Smart Compress Active\nFile %d of %d: %s", currentFileNumber, totalFiles, inputfileName), 
                            overallProgressPercent, 
                            "Starting", 
                            String.format("File %d/%d", currentFileNumber, totalFiles));
                        
                        processThread.start();
                        processThread.join();
                        
                        // Calculate final compression statistics
                        if (outputFile.exists() && inputFile.exists()) {
                            long inputSize = calculateTotalInputSize(inputFile); // Use accurate input size
                            double finalRatio = (double)outputFile.length() / inputSize;
                            String ratioText = String.format("%.1f%%", finalRatio * 100);
                            String savedSpace = formatFileSize(inputSize - outputFile.length());
                            String compressionAmount = String.format("%.1f%%", (1.0 - finalRatio) * 100);
                            
                            // Update to show detailed completion with stats
                            int newProgressPercent = totalFiles > 0 ? ((currentFileNumber + 1) * 100) / totalFiles : 100;
                            updateProgressDialog(dialog, 
                                String.format("✅ %s\nCompressed %s (File %d of %d)", outputfileName, compressionAmount, currentFileNumber + 1, totalFiles), 
                                newProgressPercent, 
                                ratioText, 
                                String.format("Saved: %s", savedSpace));
                        } else {
                            // Fallback if file sizes can't be read
                            int newProgressPercent = totalFiles > 0 ? ((currentFileNumber + 1) * 100) / totalFiles : 100;
                            updateProgressDialog(dialog, 
                                String.format("Completed: %s\nFile %d of %d done", outputfileName, currentFileNumber + 1, totalFiles), 
                                newProgressPercent, 
                                null, 
                                null);
                        }
                        // Update progress notification
                        try { com.chdboy.services.ChdmanService.updateProgressMessage(mContext, "Compressing: " + outputfileName); } catch (Exception ignored) {}
                        // After compression, optionally move output to destination via SAF
                        if (destinationTreeUri != null && outputFile.exists()) {
                            try {
                                String treeId = DocumentsContract.getTreeDocumentId(destinationTreeUri);
                                Uri parentDoc = DocumentsContract.buildDocumentUriUsingTree(destinationTreeUri, treeId);
                                Uri destFileUri = DocumentsContract.createDocument(
                                        mContext.getContentResolver(),
                                        parentDoc,
                                        "application/octet-stream",
                                        outputfileName
                                );
                                if (destFileUri != null) {
                                    InputStream is = new FileInputStream(outputFile);
                                    OutputStream os = mContext.getContentResolver().openOutputStream(destFileUri);
                                    if (is != null && os != null) {
                                        byte[] buffer = new byte[8192];
                                        int length;
                                        while ((length = is.read(buffer)) > 0) {
                                            os.write(buffer, 0, length);
                                        }
                                        is.close();
                                        os.close();
                                    }
                                    // Remove CHD from app directory after sending
                                    outputFile.delete();
                                }
                            } catch (IOException ioe) {
                                Log.e("Chdman", "Failed to move CHD to destination: " + ioe.getMessage());
                            }
                        }
                        // Cleanup copied inputs in app external files dir regardless of user delete setting
                        String externalDir = mContext.getExternalFilesDir("").getPath();
                        if (inputFile.getAbsolutePath().startsWith(externalDir)) {
                            // delete input
                            if (inputFile.exists()) inputFile.delete();
                            // delete any recorded sidecars
                            if (sidecarsToCleanup != null) {
                                for (File sf : sidecarsToCleanup) {
                                    try { if (sf != null && sf.exists()) sf.delete(); } catch (Exception ignored) {}
                                }
                            }
                        }
                    }
                    catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }          
                executor.execute(this);
            } 
        });
    }
    
    public void addToCompressionQueue(String file) {
        addToCompressionQueue(file, new ArrayList<>());
    }

    private String guessModeForFile(File inputFile) {
        String name = inputFile.getName().toLowerCase();
        if (name.endsWith(".cue") || name.endsWith(".gdi")) {
            return "createcd";
        }
        if (name.endsWith(".iso")) {
            // Default to createdvd for .iso to support PSP/PS2 and other DVD-like images.
            // Fallback logic will try the opposite if this fails.
            return "createdvd";
        }
        return "createcd";
    }

    public void addToCompressionQueue(String file, ArrayList<File> sidecars) {
        // Auto-select mode based on input if not already chosen
        if (mode == null || mode.isEmpty()) {
            mode = guessModeForFile(new File(file));
        }
        // For modern Android, always output to app's external files directory
        String fileName = file.substring(file.lastIndexOf("/") + 1);
        String baseName = fileName.substring(0, fileName.lastIndexOf("."));
        String output = mContext.getExternalFilesDir("") + "/" + baseName + ".chd";
        
        File outputFile = new File(output);
        final String jobMode = (mode == null || mode.isEmpty()) ? guessModeForFile(new File(file)) : mode;
        final boolean isIso = file.toLowerCase().endsWith(".iso");
        Runnable r = new Runnable() {
            @Override
            public void run() {
                File inputFile = new File(file);
                long inputSize = calculateTotalInputSize(inputFile);
                long startTime = System.currentTimeMillis();
                
                // Start progress monitoring in background
                Thread progressThread = new Thread(() -> {
                    File outputFile = new File(output);
                    try {
                        while (!outputFile.exists() || outputFile.length() == 0) {
                            Thread.sleep(500);
                            if (Thread.currentThread().isInterrupted()) return;
                        }
                        
                        // Monitor compression progress
                        long lastSize = 0;
                        int staleCount = 0;
                        while (true) {
                            Thread.sleep(1000);
                            if (Thread.currentThread().isInterrupted() || isCancelled) return;
                            
                            long currentSize = outputFile.exists() ? outputFile.length() : 0;
                            if (currentSize == lastSize) {
                                staleCount++;
                                if (staleCount > 3) break; // No progress for 3+ seconds, assume done
                            } else {
                                staleCount = 0;
                            }
                            lastSize = currentSize;
                            
                            // Estimate progress (CHD files are typically 60-80% of original size)
                            long estimatedFinalSize = (long)(inputSize * 0.7); // Conservative estimate
                            int progressPercent = currentSize > 0 ? (int)Math.min(95, (currentSize * 100) / estimatedFinalSize) : 0;
                            
                            // Calculate current compression ratio
                            double ratio = currentSize > 0 ? (double)currentSize / inputSize : 0.0;
                            String ratioText = String.format("%.1f%%", ratio * 100);
                            
                            // Update progress dialog with enhanced info
                            String fileName = inputFile.getName();
                            long elapsedTime = (System.currentTimeMillis() - startTime) / 1000;
                            String speedInfo = elapsedTime > 0 ? String.format("Time: %ds", elapsedTime) : "Time: --";
                            
                            // Update both notification and dialog with same data
                            handler.post(() -> {
                                try {
                                    // Update service notification
                                    String notificationText = String.format("Compressing: %s\nProgress: %d%% | Ratio: %s", fileName, progressPercent, ratioText);
                                    com.chdboy.services.ChdmanService.updateProgressMessage(mContext, notificationText);
                                    
                                    // Update dialog with same data
                                    if (activeDialog != null && activeDialog.isShowing()) {
                                        updateProgressDialog(activeDialog, 
                                            String.format("Compressing: %s", fileName), 
                                            progressPercent, 
                                            ratioText, 
                                            speedInfo);
                                    }
                                } catch (Exception ignored) {}
                            });
                        }
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                });
                progressThread.start();
                
                try {
                    // First attempt with chosen job mode
                    if ("createcd".equals(jobMode)) {
                        createcd(file, output);
                    } else {
                        createdvd(file, output);
                    }
                    // If no output produced and it's an ISO, try the opposite mode as fallback
                    if (!new File(output).exists() && isIso) {
                        if ("createcd".equals(jobMode)) {
                            createdvd(file, output);
                        } else {
                            createcd(file, output);
                        }
                    }
                } finally {
                    progressThread.interrupt();
                    
                    // Final ratio calculation
                    File finalOutput = new File(output);
                    if (finalOutput.exists()) {
                        double finalRatio = (double)finalOutput.length() / inputSize;
                        String finalRatioText = String.format("%.1f%%", finalRatio * 100);
                        long compressionTime = (System.currentTimeMillis() - startTime) / 1000;
                        
                        handler.post(() -> {
                            try {
                                com.chdboy.services.ChdmanService.updateProgressMessage(mContext, 
                                    String.format("Completed: %s\nFinal Ratio: %s | Time: %ds", inputFile.getName(), finalRatioText, compressionTime));
                            } catch (Exception ignored) {}
                        });
                        
                        Log.i("Chdman", String.format("Compression complete: %s -> %s (%.1f%% ratio, %ds)", 
                            inputFile.getName(), finalOutput.getName(), finalRatio * 100, compressionTime));
                    }
                }
            }
        };
        // Check if CHD already exists in destination folder (if set) or app files directory
        boolean chdExists = false;
        String skipReason = "";
        
        if (destinationTreeUri != null) {
            // Check if CHD exists in destination folder
            try {
                android.provider.DocumentsContract.getTreeDocumentId(destinationTreeUri);
                androidx.documentfile.provider.DocumentFile destDir = androidx.documentfile.provider.DocumentFile.fromTreeUri(mContext, destinationTreeUri);
                if (destDir != null) {
                    String chdFileName = baseName + ".chd";
                    androidx.documentfile.provider.DocumentFile[] files = destDir.listFiles();
                    for (androidx.documentfile.provider.DocumentFile f : files) {
                        if (f.getName() != null && f.getName().equals(chdFileName)) {
                            chdExists = true;
                            skipReason = "CHD already exists in destination folder: " + chdFileName;
                            break;
                        }
                    }
                }
            } catch (Exception e) {
                Log.e("Chdman", "Error checking destination folder: " + e.getMessage());
            }
        }
        
        // Also check app files directory
        if (!chdExists && outputFile.exists()) {
            chdExists = true;
            skipReason = "CHD already exists in app directory: " + outputFile.getName();
        }
        
        if (!chdExists) {
            inputStack.add(new File(file));
            outputStack.add(outputFile);
            cleanupStack.add(sidecars);
            Thread cmdThread = new Thread(r);
            threadStack.add(cmdThread);
            Log.d("Chdman", "Added to compression queue: " + baseName + ".chd");
        } else {
            Log.i("Chdman", "Skipping compression - " + skipReason);
            // Show toast notification that file was skipped
            handler.post(new Runnable() {
                @Override
                public void run() {
                    android.widget.Toast.makeText(mContext, "Skipped: " + baseName + ".chd already exists", android.widget.Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

    private native void createcd(String in, String out);
    private native void createdvd(String in, String out);

    static {
        System.loadLibrary("chdman");
    }
}
