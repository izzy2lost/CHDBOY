package com.chdboy.utils

import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import com.chdboy.R
import com.chdboy.core.Converter
import java.io.File

/** What the picker should do once the user has chosen a folder. */
object Operations {

    private const val TAG = "Operations"

    @JvmField
    var pendingOperation: String = ""

    fun compress(activity: AppCompatActivity) {
        val tree = FilePicker.selected

        if (tree == null) {
            Toast.makeText(activity, R.string.toast_no_folder, Toast.LENGTH_LONG).show()
            pendingOperation = ""
            return
        }

        Converter(activity).convertFolder(tree)
        pendingOperation = ""
    }

    /**
     * Moves anything left in the app's own storage into the chosen folder.
     *
     * Conversions write straight into the user's folder now, so this normally
     * finds nothing. It stays because the app directory can still hold output
     * from a previous version, and this is the only way to get it back out.
     */
    fun transfer(activity: AppCompatActivity) {
        val tree = FilePicker.selected
        val destination = tree?.let { DocumentFile.fromTreeUri(activity, it) }

        if (destination == null) {
            Toast.makeText(activity, R.string.toast_no_destination, Toast.LENGTH_LONG).show()
            pendingOperation = ""
            return
        }

        val source = activity.getExternalFilesDir("")
        val files = source?.listFiles().orEmpty().filter(File::isFile)

        if (files.isEmpty()) {
            Toast.makeText(activity, R.string.toast_nothing_to_transfer, Toast.LENGTH_SHORT).show()
            pendingOperation = ""
            return
        }

        var moved = 0

        for (file in files) {
            val result = runCatching {
                val target: Uri = DocumentsContract.createDocument(
                    activity.contentResolver,
                    destination.uri,
                    "application/octet-stream",
                    file.name,
                ) ?: error("could not create ${file.name}")

                activity.contentResolver.openOutputStream(target).use { output ->
                    requireNotNull(output) { "could not open ${file.name} for writing" }
                    file.inputStream().use { it.copyTo(output) }
                }

                // Only after the copy is closed, so a failure part-way leaves
                // the original where it was.
                file.delete()
            }

            result.onSuccess { moved++ }
                .onFailure { Log.e(TAG, "Could not transfer ${file.name}", it) }
        }

        val summary = activity.resources.getQuantityString(R.plurals.toast_transferred, moved, moved)
        Toast.makeText(activity, summary, Toast.LENGTH_SHORT).show()
        pendingOperation = ""
    }
}
