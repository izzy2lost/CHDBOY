package com.chdboy.utils;
import android.content.Context;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.util.Log;
import androidx.appcompat.app.AppCompatActivity;
import androidx.documentfile.provider.DocumentFile;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import android.widget.Toast;
import android.util.Log;

public final class Operations {
    
    public static String pendingOperation = "";
    public Operations() {}
    
   public static void compress(AppCompatActivity mActivity){
        ArrayList<Uri> filesList = FilePicker.getFiles();
        ArrayList<Uri> singleValidFiles = new ArrayList<Uri>();
        boolean handledAnyTree = false;
        
        // Create one Chdman instance per operation
        Chdman chdman = new Chdman(mActivity);

        // Handle each selection; if it's a folder (tree), process with sidecars; if single doc, handle ISO directly
        for (Uri uri : filesList) {
            if (uri.toString().contains("tree")) {
                DocumentFile dir = DocumentFile.fromTreeUri(mActivity, uri);
                if (dir != null) {
                    handledAnyTree = true;
                    // Set destination to selected folder
                    chdman.setDestinationTreeUri(uri);
                    compressDirTree(mActivity, dir, chdman);
                }
            } else {
                DocumentFile file = DocumentFile.fromSingleUri(mActivity, uri);
                if (file != null && isValidForCompression(file.getName())) {
                    // Only allow direct single-file processing for ISO; for CUE/GDI we need the folder to get sidecars
                    String name = file.getName() == null ? "" : file.getName();
                    String ext = getExt(name);
                    if ("iso".equals(ext)) {
                        singleValidFiles.add(uri);
                    } else if ("cue".equals(ext) || "gdi".equals(ext)) {
                        Toast.makeText(mActivity, "For CUE/GDI, please select the containing folder so referenced files can be accessed.", Toast.LENGTH_LONG).show();
                    }
                }
            }
        }
        
        if (!singleValidFiles.isEmpty()) {
            compressUris(mActivity, singleValidFiles, chdman);
        }
        
        // Start compression once for the accumulated queue
        chdman.startCompression();

        // Clear
        filesList.clear();
        pendingOperation = "";
        
        if (!handledAnyTree && singleValidFiles.isEmpty()) {
            Toast.makeText(mActivity, "No valid files found for compression (ISO, CUE, GDI)", Toast.LENGTH_SHORT).show();
        }
    }

    private static String getExt(String fileName) {
        int lastDot = fileName.lastIndexOf('.');
        if (lastDot > 0 && lastDot < fileName.length() - 1) {
            return fileName.substring(lastDot + 1).toLowerCase();
        }
        return "";
    }
    
    private static boolean isValidForCompression(String fileName) {
        if (fileName == null) return false;
        String extension = getExt(fileName);
        return extension.equals("iso") || extension.equals("cue") || extension.equals("gdi");
    }

    private static void compressDirTree(AppCompatActivity mActivity, DocumentFile dir, Chdman chdman) {
        // Build index of files in the folder
        DocumentFile[] children = dir.listFiles();
        HashMap<String, DocumentFile> byName = new HashMap<>();
        for (DocumentFile f : children) {
            if (f.getName() != null) byName.put(f.getName(), f);
        }

        // Collect tasks: for each ISO/CUE/GDI, copy needed files and queue
        ArrayList<Uri> isoFiles = new ArrayList<>();
        ArrayList<DocumentFile> cueFiles = new ArrayList<>();
        ArrayList<DocumentFile> gdiFiles = new ArrayList<>();
        for (DocumentFile f : children) {
            String name = f.getName();
            if (name == null) continue;
            String ext = getExt(name);
            if ("iso".equals(ext)) isoFiles.add(f.getUri());
            else if ("cue".equals(ext)) cueFiles.add(f);
            else if ("gdi".equals(ext)) gdiFiles.add(f);
        }

        // Copy and queue ISOs directly
        if (!isoFiles.isEmpty()) {
            compressUris(mActivity, isoFiles, chdman);
        }

        // Handle CUE with sidecars
        for (DocumentFile cue : cueFiles) {
            try {
                Set<String> sidecars = parseCueDependencies(mActivity, cue.getUri());
                copyWithSidecarsAndQueue(mActivity, cue, sidecars, byName, chdman);
            } catch (IOException e) {
                Toast.makeText(mActivity, "Failed to read CUE: " + cue.getName(), Toast.LENGTH_SHORT).show();
                Log.e("Operations", "parse CUE error", e);
            }
        }

        // Handle GDI with sidecars
        for (DocumentFile gdi : gdiFiles) {
            try {
                Set<String> sidecars = parseGdiDependencies(mActivity, gdi.getUri());
                copyWithSidecarsAndQueue(mActivity, gdi, sidecars, byName, chdman);
            } catch (IOException e) {
                Toast.makeText(mActivity, "Failed to read GDI: " + gdi.getName(), Toast.LENGTH_SHORT).show();
                Log.e("Operations", "parse GDI error", e);
            }
        }
    }

    private static void copyWithSidecarsAndQueue(AppCompatActivity mActivity, DocumentFile mainFile, Set<String> sidecars, HashMap<String, DocumentFile> byName, Chdman chdman) throws IOException {
        File externalFilesDir = mActivity.getExternalFilesDir("");
        if (externalFilesDir == null) return;

        // Copy main file
        String mainName = mainFile.getName();
        if (mainName == null) return;
        File destMain = new File(externalFilesDir, mainName);
        copyUriToFile(mActivity, mainFile.getUri(), destMain);

        ArrayList<File> cleanupSidecars = new ArrayList<>();
        // Copy sidecars if present in the folder
        for (String side : sidecars) {
            DocumentFile sf = byName.get(side);
            if (sf != null) {
                File destSide = new File(externalFilesDir, side);
                copyUriToFile(mActivity, sf.getUri(), destSide);
                cleanupSidecars.add(destSide);
            }
        }

        // Queue with sidecar list for cleanup
        chdman.addToCompressionQueue(destMain.getAbsolutePath(), cleanupSidecars);
    }

    private static void copyUriToFile(AppCompatActivity mActivity, Uri uri, File dest) throws IOException {
        InputStream inputStream = mActivity.getContentResolver().openInputStream(uri);
        if (inputStream == null) throw new IOException("Cannot open input stream");
        FileOutputStream outputStream = new FileOutputStream(dest);
        byte[] buffer = new byte[8192];
        int length;
        try {
            while ((length = inputStream.read(buffer)) > 0) {
                outputStream.write(buffer, 0, length);
            }
        } finally {
            try { inputStream.close(); } catch (IOException ignored) {}
            try { outputStream.close(); } catch (IOException ignored) {}
        }
    }

    private static Set<String> parseCueDependencies(AppCompatActivity mActivity, Uri cueUri) throws IOException {
        HashSet<String> deps = new HashSet<>();
        InputStream is = mActivity.getContentResolver().openInputStream(cueUri);
        if (is == null) return deps;
        BufferedReader br = new BufferedReader(new InputStreamReader(is));
        String line;
        try {
            while ((line = br.readLine()) != null) {
                line = line.trim();
                // FILE "name.bin" BINARY
                if (line.toUpperCase().startsWith("FILE ")) {
                    int firstQuote = line.indexOf('"');
                    int lastQuote = line.lastIndexOf('"');
                    if (firstQuote >= 0 && lastQuote > firstQuote) {
                        String fname = line.substring(firstQuote + 1, lastQuote);
                        deps.add(fname);
                    } else {
                        // Sometimes unquoted filenames
                        String[] parts = line.split("\\s+");
                        if (parts.length >= 2) deps.add(parts[1]);
                    }
                }
            }
        } finally {
            try { br.close(); } catch (IOException ignored) {}
        }
        return deps;
    }

    private static Set<String> parseGdiDependencies(AppCompatActivity mActivity, Uri gdiUri) throws IOException {
        HashSet<String> deps = new HashSet<>();
        InputStream is = mActivity.getContentResolver().openInputStream(gdiUri);
        if (is == null) return deps;
        BufferedReader br = new BufferedReader(new InputStreamReader(is));
        String line;
        int lineNo = 0;
        try {
            while ((line = br.readLine()) != null) {
                lineNo++;
                line = line.trim();
                if (lineNo == 1) continue; // first line is track count
                if (line.isEmpty()) continue;
                // Typical GDI line: track lba type sector fileName offset
                String[] parts = line.split("\\s+");
                if (parts.length >= 5) {
                    String fname = parts[4];
                    deps.add(fname);
                }
            }
        } finally {
            try { br.close(); } catch (IOException ignored) {}
        }
        return deps;
    }
    
    private static void compressUris(AppCompatActivity mActivity, ArrayList<Uri> uris, Chdman chdman) {
        File externalFilesDir = mActivity.getExternalFilesDir("");
        if (externalFilesDir == null) {
            Toast.makeText(mActivity, "External storage not available", Toast.LENGTH_SHORT).show();
            return;
        }
        
        // Copy URI files to external files directory and add to compression queue
        for (Uri uri : uris) {
            DocumentFile sourceFile = DocumentFile.fromSingleUri(mActivity, uri);
            if (sourceFile != null && sourceFile.getName() != null) {
                try {
                    // Create file in external files directory (keep original name so CUE/GDI references resolve)
                    File inputFile = new File(externalFilesDir, sourceFile.getName());
                    
                    // Copy from URI to external files directory
                    InputStream inputStream = mActivity.getContentResolver().openInputStream(uri);
                    FileOutputStream outputStream = new FileOutputStream(inputFile);
                    
                    if (inputStream != null) {
                        byte[] buffer = new byte[8192];
                        int length;
                        while ((length = inputStream.read(buffer)) > 0) {
                            outputStream.write(buffer, 0, length);
                        }
                        inputStream.close();
                    }
                    outputStream.close();
                    
                    // Add to compression queue using the copied file path
                    chdman.addToCompressionQueue(inputFile.getAbsolutePath(), new ArrayList<>());
                    
                } catch (IOException e) {
                    Log.e("Operations", "Error copying file for compression: " + e.getMessage());
                    Toast.makeText(mActivity, "Error preparing file: " + sourceFile.getName(), Toast.LENGTH_SHORT).show();
                }
            }
        }
        
        // Do not start here; caller starts after queueing all inputs
    }
    
    public static void transfer(AppCompatActivity mActivity) {
        ArrayList<Uri> filesList = FilePicker.getFiles();
        DocumentFile destinationDir = DocumentFile.fromTreeUri(mActivity, filesList.get(0));
        File sourceDir = new File(mActivity.getExternalFilesDir("").getPath());
        for (File f : sourceDir.listFiles()) {
            try {
                String sourceName = f.getName();
                Uri destinationFileUri = DocumentsContract.createDocument(mActivity.getContentResolver(), destinationDir.getUri(), "application/octet-stream", sourceName);
                InputStream is = new FileInputStream(f);
                OutputStream os = mActivity.getContentResolver().openOutputStream(destinationFileUri);
                if (is != null && os != null) {
                    byte[] buffer = new byte[1024];
                    int length;
                    while ((length = is.read(buffer)) > 0) {
                        os.write(buffer, 0, length);
                    }
                    is.close();
                    os.close();
                    f.delete();
                }    
            }
            catch (FileNotFoundException e) {
                throw new RuntimeException(e);
            }
            catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        filesList.clear();
        pendingOperation = "";
    }
}
