package com.izzy2lost.chdboy.utils

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import java.io.File
import java.io.FileNotFoundException

/**
 * Exposes the app's own storage to the system file picker.
 *
 * Conversions write straight into the user's folder, so this is normally empty
 * -- it stays so output from an older version of the app is still reachable
 * without a file manager.
 */
class MyFileProvider : DocumentsProvider() {

    override fun onCreate(): Boolean = true

    private fun rootDirectory(): File =
        context?.getExternalFilesDir("") ?: throw FileNotFoundException("No external storage")

    private fun resolve(documentId: String): File =
        File(rootDirectory(), documentId.removePrefix("/"))

    override fun queryRoots(projection: Array<String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)
        val root = context?.getExternalFilesDir("") ?: return cursor

        cursor.newRow().apply {
            add(Root.COLUMN_ROOT_ID, root.path)
            add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_IS_CHILD)
            add(Root.COLUMN_TITLE, "CHDBOY")
            add(Root.COLUMN_DOCUMENT_ID, "/")
            add(Root.COLUMN_MIME_TYPES, "*/*")
            add(Root.COLUMN_AVAILABLE_BYTES, root.freeSpace)
        }

        return cursor
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<String>?,
        sortOrder: String?,
    ): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        val directory = resolve(parentDocumentId)

        // listFiles() is null for anything that is not a readable directory,
        // which includes the perfectly ordinary case of the folder not
        // existing yet.
        for (file in directory.listFiles().orEmpty()) {
            includeFile(cursor, "${parentDocumentId.trimEnd('/')}/${file.name}", file)
        }

        return cursor
    }

    override fun queryDocument(documentId: String, projection: Array<String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        includeFile(cursor, documentId, resolve(documentId))
        return cursor
    }

    override fun deleteDocument(documentId: String) {
        if (!resolve(documentId).delete()) {
            throw FileNotFoundException("Could not delete $documentId")
        }
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        val file = resolve(documentId)
        val flags = if (mode.contains('w')) {
            ParcelFileDescriptor.MODE_READ_WRITE
        } else {
            ParcelFileDescriptor.MODE_READ_ONLY
        }

        return ParcelFileDescriptor.open(file, flags)
    }

    private fun includeFile(cursor: MatrixCursor, documentId: String, file: File) {
        cursor.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, documentId)

            if (file.isDirectory) {
                add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR)
                add(Document.COLUMN_FLAGS, Document.FLAG_DIR_SUPPORTS_CREATE)
                add(Document.COLUMN_SIZE, 0)
            } else {
                add(Document.COLUMN_MIME_TYPE, "application/octet-stream")
                add(Document.COLUMN_FLAGS, Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_WRITE)
                add(Document.COLUMN_SIZE, file.length())
            }

            add(Document.COLUMN_DISPLAY_NAME, file.name)
            add(Document.COLUMN_LAST_MODIFIED, file.lastModified())
        }
    }

    private companion object {
        val DEFAULT_ROOT_PROJECTION = arrayOf(
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_MIME_TYPES,
            Root.COLUMN_FLAGS,
            Root.COLUMN_ICON,
            Root.COLUMN_TITLE,
            Root.COLUMN_SUMMARY,
            Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_AVAILABLE_BYTES,
        )

        val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS,
            Document.COLUMN_SIZE,
        )
    }
}
