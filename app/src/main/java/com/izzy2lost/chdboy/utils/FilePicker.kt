package com.izzy2lost.chdboy.utils

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

/**
 * The folder picker.
 *
 * Both operations work on a whole folder rather than individual files, because
 * a `.cue` is useless without the `.bin` next to it and a tree grant is the
 * only way to reach both. The grant is persisted so a conversion that outlives
 * the activity can still write its output.
 */
class FilePicker(activity: AppCompatActivity) {

    private val launcher = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) {
            return@registerForActivityResult
        }

        val uri = result.data?.data ?: return@registerForActivityResult

        runCatching {
            activity.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }.onFailure { Log.w(TAG, "Could not persist the folder grant", it) }

        selected = uri

        when (Operations.pendingOperation) {
            "compress" -> Operations.compress(activity)
            "transfer" -> Operations.transfer(activity)
        }
    }

    fun pickFolder() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            flags = Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        }

        launcher.launch(intent)
    }

    companion object {
        private const val TAG = "FilePicker"

        /** The folder the user last picked, read by [Operations]. */
        var selected: Uri? = null
            private set
    }
}
