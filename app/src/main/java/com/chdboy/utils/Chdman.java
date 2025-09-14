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
    
    private AlertDialog createProgressBarDialog(String title) {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(mContext);
        AlertDialog dialog;
        builder.setTitle(title);
        builder.setCancelable(false);
        builder.setView(R.layout.progress_bar);
        dialog = builder.create();
        return dialog;
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
    
    private void showAlertDialog(AlertDialog dlg) {
        final Runnable showDialog = new Runnable() {
            @Override
            public void run() {
                dlg.show();
            }
        };
        handler.post(showDialog);
    }
    
    private void hideAlertDialog(AlertDialog dlg) {
        final Runnable hideDialog = new Runnable() {
            @Override
            public void run() {
                dlg.hide();
            }
        };
        handler.post(hideDialog);
    }
    
    public void startCompression() {
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        AlertDialog progressDialog = createProgressBarDialog("Compress");
        progressDialog.setMessage("");
        AlertDialog modesDialog = createModesDialog("Select mode");
        if (!threadStack.isEmpty())
            showAlertDialog(modesDialog);
        executor.execute(new Runnable(){
            @Override
            public void run() {
                Thread processThread = null;
                File inputFile = null;    
                File outputFile = null;
                ArrayList<File> sidecarsToCleanup = null;
                if (threadStack.isEmpty())
                    status = Status.COMPLETED;
                if (status == Status.COMPLETED) {
                    clean();
                    hideAlertDialog(progressDialog);
                    // Notify completion on UI
                    handler.post(new Runnable() {
                        @Override public void run() {
                            android.widget.Toast.makeText(mContext, "Compression complete", android.widget.Toast.LENGTH_SHORT).show();
                        }
                    });
                    return;    
                }
                if (mode != "" && status != Status.RUNNING) {
                    status = Status.RUNNING;
                    showAlertDialog(progressDialog);
                }    
                if (status == Status.RUNNING) {
                    try {
                        processThread = threadStack.pop();
                        inputFile = inputStack.pop();  
                        outputFile = outputStack.pop();
                        sidecarsToCleanup = cleanupStack.pop();
                        String inputfileName = inputFile.getName();    
                        String outputfileName = outputFile.getName();
                        progressDialog.setMessage(outputfileName);     
                        processThread.start();
                        processThread.join();
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

    public void addToCompressionQueue(String file, ArrayList<File> sidecars) {
        // For modern Android, always output to app's external files directory
        String fileName = file.substring(file.lastIndexOf("/") + 1);
        String baseName = fileName.substring(0, fileName.lastIndexOf("."));
        String output = mContext.getExternalFilesDir("") + "/" + baseName + ".chd";
        
        File outputFile = new File(output);
        Runnable r = new Runnable() {
            @Override
            public void run() {
                switch(mode) {
                    case "createcd":
                        createcd(file, output);
                        break;
                    case "createdvd":
                        createdvd(file, output);
                        break;
                }
                // Clean up copied input file(s) after compression if setting is enabled
                if (deleteInput) {
                    String externalDir = mContext.getExternalFilesDir("").getPath();
                    if (file.startsWith(externalDir)) {
                        File inputFile = new File(file);
                        if (inputFile.exists()) {
                            inputFile.delete();
                        }
                        // Also clean up associated bin/raw/wav file for cue files
                        if (file.endsWith(".cue")) {
                            String[] sidecars = new String[] { ".bin", ".raw", ".wav" };
                            for (String ext : sidecars) {
                                String candidate = file.substring(0, file.lastIndexOf(".")) + ext;
                                File maybe = new File(candidate);
                                if (maybe.exists()) {
                                    maybe.delete();
                                }
                            }
                        }
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