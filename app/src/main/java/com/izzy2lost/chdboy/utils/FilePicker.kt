package com.izzy2lost.chdboy.utils

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.izzy2lost.chdboy.R

/**
 * The folder and file pickers.
 *
 * A folder is the simple case: one tree grant reaches every image, the `.bin`
 * files a `.cue` names, and the place the CHDs are written. Picking files
 * gives none of that, because a document grant covers only the documents
 * chosen. So the user selects the track files along with their sheets, and
 * is then asked for a folder to write into. Every grant is persisted so a
 * conversion that outlives the activity can still reach its files.
 */
class FilePicker(private val activity: AppCompatActivity) {

    private val folderLauncher = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) {
            if (Operations.pendingOperation == "compress_files") {
                Toast.makeText(activity, R.string.toast_no_destination, Toast.LENGTH_LONG).show()
                Operations.pendingOperation = ""
            }
            return@registerForActivityResult
        }

        val uri = result.data?.data ?: return@registerForActivityResult

        persist(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)

        selected = uri

        when (Operations.pendingOperation) {
            "compress" -> Operations.compress(activity)
            "compress_files" -> Operations.compressFiles(activity)
            "transfer" -> Operations.transfer(activity)
        }
    }

    private val filesLauncher = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data

        if (result.resultCode != Activity.RESULT_OK || data == null) {
            Operations.pendingOperation = ""
            return@registerForActivityResult
        }

        // A single pick comes back in data, several in clipData, depending on
        // the provider rather than on how many the user chose.
        val uris = data.clipData?.let { clip -> List(clip.itemCount) { clip.getItemAt(it).uri } }
            ?: listOfNotNull(data.data)

        if (uris.isEmpty()) {
            Toast.makeText(activity, R.string.toast_no_files, Toast.LENGTH_LONG).show()
            Operations.pendingOperation = ""
            return@registerForActivityResult
        }

        uris.forEach { persist(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        selectedFiles = uris

        Toast.makeText(activity, R.string.toast_pick_destination, Toast.LENGTH_SHORT).show()
        pickFolder(initial = uris.first())
    }

    fun pickFolder(initial: Uri? = null) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            flags = Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION

            // Opens the picker where the files came from, which is where most
            // people want the CHDs. Providers are free to ignore it.
            if (initial != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                putExtra(DocumentsContract.EXTRA_INITIAL_URI, initial)
            }
        }

        folderLauncher.launch(intent)
    }

    fun pickFiles() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            // .bin, .cue and .gdi have no registered MIME types, so filtering
            // by type would hide exactly the files that are needed.
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            flags = Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        }

        filesLauncher.launch(intent)
    }

    private fun persist(uri: Uri, flags: Int) {
        runCatching { activity.contentResolver.takePersistableUriPermission(uri, flags) }
            .onFailure { Log.w(TAG, "Could not persist the grant for $uri", it) }
    }

    companion object {
        private const val TAG = "FilePicker"

        /** The folder the user last picked, read by [Operations]. */
        var selected: Uri? = null
            private set

        /** The files the user last picked, read by [Operations]. */
        var selectedFiles: List<Uri> = emptyList()
            private set
    }
}
