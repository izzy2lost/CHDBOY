package com.chdboy.utils;

import android.app.ActionBar;
import android.content.DialogInterface;

import android.content.SharedPreferences;
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
        
        // Create custom progress layout
        android.widget.LinearLayout layout = new android.widget.LinearLayout(mContext);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        layout.setPadding(50, 30, 50, 30);
        
        android.widget.TextView statusText = new android.widget.TextView(mContext);
        statusText.setText("Starting compression...");
        statusText.setId(android.R.id.message);
        layout.addView(statusText);
        
        android.widget.ProgressBar progressBar = new android.widget.ProgressBar(mContext, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setIndeterminate(false);
        progressBar.setMax(100);
        progressBar.setProgress(0);
        progressBar.setId(android.R.id.progress);
        layout.addView(progressBar);
        
        builder.setView(layout);
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
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (dlg.isShowing()) {
                    android.widget.TextView statusText = dlg.findViewById(android.R.id.message);
                    if (statusText != null) {
                        statusText.setText(message);
                    }
                    android.widget.ProgressBar progressBar = dlg.findViewById(android.R.id.progress);
                    if (progressBar != null && progress >= 0) {
                        progressBar.setProgress(progress);
                    }
                }
            }
        });
    }
    
    private void updateProgressDialog(AlertDialog dlg, String message) {
        updateProgressDialog(dlg, message, -1); // -1 means don't update progress
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
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        final AlertDialog progressDialog = createProgressDialog("Compression Progress");
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
                if (threadStack.isEmpty())
                    status = Status.COMPLETED;
                if (status == Status.COMPLETED) {
                    clean();
                    hideProgressDialog(dialog);
                    // Completion notifications
                    try { com.chdboy.services.ChdmanService.updateProgress(mContext, "Idle"); } catch (Exception ignored) {}
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
                    try {
                        processThread = threadStack.pop();
                        inputFile = inputStack.pop();  
                        outputFile = outputStack.pop();
                        sidecarsToCleanup = cleanupStack.pop();
                        String inputfileName = inputFile.getName();    
                        String outputfileName = outputFile.getName();
                        // Calculate progress based on queue position (before popping)
                        int totalFiles = inputStack.size() + 1; // +1 for current file being processed
                        int currentFile = totalFiles - inputStack.size();
                        int progressPercent = totalFiles > 0 ? (currentFile * 100) / totalFiles : 0;
                        
                        updateProgressDialog(dialog, 
                            String.format("Compressing: %s\nFile %d of %d", inputfileName, currentFile + 1, totalFiles), 
                            progressPercent);
                        
                        processThread.start();
                        processThread.join();
                        
                        // Update to 100% when this file is done
                        int newProgressPercent = totalFiles > 0 ? ((currentFile + 1) * 100) / totalFiles : 100;
                        updateProgressDialog(dialog, 
                            String.format("Completed: %s\nFile %d of %d done", outputfileName, currentFile + 1, totalFiles), 
                            newProgressPercent);
                        // Update progress notification
                        try { com.chdboy.services.ChdmanService.updateProgress(mContext, "Compressing: " + outputfileName); } catch (Exception ignored) {}
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
            }
        };
        if (!outputFile.exists()) {
            inputStack.add(new File(file));
            outputStack.add(outputFile);
            cleanupStack.add(sidecars);
            Thread cmdThread = new Thread(r);
            threadStack.add(cmdThread);
        }    
    }

    private native void createcd(String in, String out);
    private native void createdvd(String in, String out);

    static {
        System.loadLibrary("chdman");
    }
}