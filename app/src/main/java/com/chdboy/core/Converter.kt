package com.chdboy.core

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.preference.PreferenceManager
import com.chdboy.R
import com.chdboy.services.ConversionService
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.InputStream
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.log10
import kotlin.math.pow

/**
 * Converts every disc image in a folder to CHD, in place.
 *
 * The folder is a SAF tree the user granted read and write on, so both the
 * source and the finished .chd live there and nothing is ever staged into the
 * app's own storage. That is not a tidiness point: the old flow copied each
 * image out and the result back, so a 40 GB disc needed 80 GB of free space
 * and two full passes over it before the conversion had even started.
 */
class Converter(private val activity: AppCompatActivity) {

    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    @Volatile
    private var cancelled = false

    private var dialog: AlertDialog? = null

    /** One image plus whatever files its track sheet points at. */
    private data class Job(
        val source: DocumentFile,
        val sidecars: List<DocumentFile>,
        val outputName: String,

        /** True for a PSP UMD image, which gets asked about before it runs. */
        val isPsp: Boolean = false,

        /** 0 leaves the writer's default in place. */
        val hunkBytes: Int = 0,
    )

    fun convertFolder(treeUri: Uri) {
        val tree = runCatching { DocumentFile.fromTreeUri(activity, treeUri) }.getOrNull()

        if (tree == null || !tree.isDirectory) {
            Toast.makeText(activity, R.string.toast_folder_unreadable, Toast.LENGTH_LONG).show()
            return
        }

        val children = tree.listFiles()
        val byName = children.mapNotNull { file -> file.name?.let { it to file } }.toMap()
        val existing = byName.keys.map { it.lowercase(Locale.ROOT) }.toSet()

        val jobs = mutableListOf<Job>()
        var skipped = 0

        for (file in children) {
            val name = file.name ?: continue

            if (!file.isFile || extensionOf(name) !in SUPPORTED) {
                continue
            }

            val outputName = name.substringBeforeLast('.') + ".chd"

            if (outputName.lowercase(Locale.ROOT) in existing) {
                skipped++
                continue
            }

            jobs += Job(file, sidecarsFor(file, byName), outputName, isPsp = isPspImage(file))
        }

        if (jobs.isEmpty()) {
            val message = if (skipped > 0) {
                R.string.toast_all_have_chds
            } else {
                R.string.toast_no_images
            }

            Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
            return
        }

        if (skipped > 0) {
            val message = activity.resources.getQuantityString(R.plurals.toast_skipping, skipped, skipped)
            Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
        }

        val psp = jobs.count { it.isPsp }

        if (psp == 0) {
            start(tree, jobs)
            return
        }

        // PSP is the one target whose reader has a documented preference, so it
        // is the one case worth interrupting for. Everything else in the run
        // keeps the default either way.
        askPspHunkSize(psp) { hunkBytes ->
            start(tree, jobs.map { if (it.isPsp) it.copy(hunkBytes = hunkBytes) else it })
        }
    }

    /**
     * PPSSPP decompresses a whole hunk to serve one 2048-byte sector and keeps
     * exactly one of them cached, so the hunk size is the difference between a
     * game that streams and a game that stutters. Its documentation asks for
     * 2048; the default here is 64 times that because it compresses far better.
     *
     * Neither answer is wrong, and the trade is legible enough to state, so it
     * is put to the user rather than guessed at.
     */
    private fun askPspHunkSize(count: Int, onChosen: (Int) -> Unit) {
        val night = (activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.psp_hunk_title))
            .setMessage(activity.resources.getQuantityString(R.plurals.psp_hunk_message, count, count))
            .setCancelable(false)
            .setPositiveButton(R.string.psp_hunk_compatible) { _, _ -> onChosen(PSP_HUNK_BYTES) }
            .setNegativeButton(R.string.psp_hunk_smallest) { _, _ -> onChosen(DEFAULT_HUNK_BYTES) }
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.let { styleButton(it, night) }
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.let { styleButton(it, night) }
        }

        dialog.show()
    }

    /**
     * Whether an .iso is a PSP UMD dump.
     *
     * ISO 9660 puts its primary volume descriptor at sector 16, and a UMD
     * writes "PSP GAME" into its system identifier. Some dumping tools blank
     * that field, so the root directory is also checked for UMD_DATA.BIN,
     * which every UMD carries and nothing else does.
     */
    private fun isPspImage(file: DocumentFile): Boolean {
        if (extensionOf(file.name.orEmpty()) != "iso") {
            return false
        }

        return runCatching {
            activity.contentResolver.openInputStream(file.uri)?.use { stream ->
                if (!stream.skipExactly(PVD_OFFSET)) {
                    return@use false
                }

                val descriptor = ByteArray(SECTOR_BYTES)

                if (!stream.readExactly(descriptor)) {
                    return@use false
                }

                // Not ISO 9660 at all, so not a UMD either.
                if (String(descriptor, 1, 5, Charsets.US_ASCII) != "CD001") {
                    return@use false
                }

                if (String(descriptor, 8, 32, Charsets.US_ASCII).trim().startsWith("PSP GAME")) {
                    return@use true
                }

                // The root directory records sit a few sectors past the
                // descriptor, well inside this window.
                val directory = ByteArray(DIRECTORY_SCAN_BYTES)
                val read = stream.readAtMost(directory)
                directory.containsAscii("UMD_DATA.BIN", read)
            } ?: false
        }.getOrElse {
            Log.w(TAG, "Could not inspect ${file.name}", it)
            false
        }
    }

    private fun start(tree: DocumentFile, jobs: List<Job>) {
        cancelled = false

        val progress = createProgressDialog()
        dialog = progress
        progress.show()

        // The foreground service is what keeps a long conversion alive once
        // the user leaves the app, which is the normal case for a disc image.
        runCatching {
            ConversionService.ensureChannel(activity)
            val intent = Intent(activity, ConversionService::class.java)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                activity.startForegroundService(intent)
            } else {
                activity.startService(intent)
            }
        }.onFailure { Log.e(TAG, "Could not start the foreground service", it) }

        Toast.makeText(activity, R.string.toast_compression_started, Toast.LENGTH_SHORT).show()

        val deleteSource = PreferenceManager.getDefaultSharedPreferences(activity)
            .getBoolean("deletesource", false)

        worker.execute {
            var converted = 0
            var failed = 0

            for ((index, job) in jobs.withIndex()) {
                if (cancelled) {
                    break
                }

                onMain {
                    update(
                        message = activity.getString(
                            R.string.progress_file,
                            job.source.name,
                            index + 1,
                            jobs.size,
                        ),
                        percent = 0,
                        ratio = "--",
                        detail = activity.getString(R.string.progress_size_pending),
                    )
                }

                val poller = startPolling(job.source.name.orEmpty(), index + 1, jobs.size)
                val ok = runCatching { convert(tree, job) }
                    .onFailure { Log.e(TAG, "Conversion of ${job.source.name} failed", it) }
                    .getOrDefault(false)

                handler.removeCallbacks(poller)

                if (ok) {
                    converted++

                    if (deleteSource) {
                        deleteAll(job)
                    }
                } else if (!cancelled) {
                    failed++
                    val reason = Native.nativeLastError()
                    Log.e(TAG, "Conversion of ${job.source.name} failed: $reason")
                    onMain {
                        val text = activity.getString(R.string.toast_failure, job.source.name, reason)
                        Toast.makeText(activity, text, Toast.LENGTH_LONG).show()
                    }
                }
            }

            onMain { finish(converted, failed) }
        }
    }

    private fun convert(tree: DocumentFile, job: Job): Boolean {
        val session = Native.nativeOpenSession()

        try {
            val sourcePath = bind(session, job.source.uri, job.source.name.orEmpty(), write = false)
                ?: return false

            // Bound before the conversion starts, because a .cue names them and
            // native has no other way to reach them.
            for (sidecar in job.sidecars) {
                if (bind(session, sidecar.uri, sidecar.name.orEmpty(), write = false) == null) {
                    Log.e(TAG, "Could not bind sidecar ${sidecar.name}")
                    return false
                }
            }

            // Created before it is opened: SAF has no "create and open", and
            // the descriptor has to refer to a document that already exists.
            val output = tree.createFile(CHD_MIME, job.outputName)

            if (output == null) {
                Log.e(TAG, "Could not create ${job.outputName}")
                return false
            }

            // Some providers rewrite the name to match the MIME type they were
            // given. There is no MIME type registered for CHD, so put it back.
            if (output.name?.endsWith(".chd", ignoreCase = true) == false) {
                runCatching { output.renameTo(job.outputName) }
            }

            val outputPath = bind(session, output.uri, job.outputName, write = true)

            if (outputPath == null) {
                output.delete()
                return false
            }

            val ok = Native.nativeConvert(sourcePath, outputPath, PORTABLE, job.hunkBytes)

            if (!ok) {
                // A failed or cancelled run leaves a partial document behind.
                // Native only ever held a descriptor, so it could not remove it.
                runCatching { output.delete() }
                    .onFailure { Log.w(TAG, "Could not remove the partial .chd", it) }
            }

            return ok
        } finally {
            Native.nativeCloseSession(session)
        }
    }

    /**
     * Hands a descriptor for [uri] to native. `detachFd()` transfers ownership,
     * so the [android.os.ParcelFileDescriptor] must not be closed here and the
     * session closes the descriptor instead.
     */
    private fun bind(session: Int, uri: Uri, name: String, write: Boolean): String? {
        // "rw" first for an output, because the writer seeks back to rewrite
        // the header once the hashes are known, and a provider handed "w"
        // alone is allowed to return a pipe. Not every provider accepts "rw"
        // though, so fall back rather than failing the conversion outright.
        val modes = if (write) listOf("rw", "w") else listOf("r")

        val descriptor = modes.firstNotNullOfOrNull { mode ->
            runCatching { activity.contentResolver.openFileDescriptor(uri, mode) }.getOrNull()
        }

        if (descriptor == null) {
            Log.e(TAG, "Could not open $name")
            return null
        }

        val fd = descriptor.detachFd()
        val path = Native.nativeBindFd(session, fd, name)

        if (path.isEmpty()) {
            // The binding did not take, so ownership never transferred.
            runCatching { android.os.ParcelFileDescriptor.adoptFd(fd).close() }
            Log.e(TAG, "Could not bind $name")
            return null
        }

        return path
    }

    private fun deleteAll(job: Job) {
        for (file in listOf(job.source) + job.sidecars) {
            runCatching { file.delete() }
                .onFailure { Log.w(TAG, "Could not delete ${file.name}", it) }
        }
    }

    /**
     * Reads native progress on a timer rather than calling back into the JVM
     * from the compression threads: a callback per batch would mean attaching
     * and detaching a JNIEnv thousands of times for something the user only
     * sees twice a second.
     */
    private fun startPolling(name: String, index: Int, total: Int): Runnable {
        lateinit var poll: Runnable

        poll = Runnable {
            val progress = Native.nativeGetProgress()

            if (progress.isNotEmpty()) {
                val parts = progress.split('\n')

                if (parts.size == 3) {
                    val done = parts[0].toLongOrNull() ?: 0L
                    val bytes = parts[1].toLongOrNull() ?: 0L
                    val written = parts[2].toLongOrNull() ?: 0L

                    val percent = if (bytes > 0) ((done * 100) / bytes).toInt() else 0
                    val ratio = if (done > 0) {
                        String.format(Locale.ROOT, "%.1f%%", written * 100.0 / done)
                    } else {
                        "--"
                    }

                    update(
                        message = activity.getString(R.string.progress_file, name, index, total),
                        percent = percent,
                        ratio = ratio,
                        detail = activity.getString(
                            R.string.progress_size,
                            formatSize(done),
                            formatSize(bytes),
                        ),
                    )

                    runCatching {
                        ConversionService.updateProgress(
                            activity,
                            activity.getString(R.string.notification_progress, name, percent, ratio),
                            percent,
                        )
                    }
                }
            }

            handler.postDelayed(poll, POLL_INTERVAL_MS)
        }

        handler.postDelayed(poll, POLL_INTERVAL_MS)
        return poll
    }

    private fun finish(converted: Int, failed: Int) {
        val summary = when {
            cancelled -> activity.getString(R.string.summary_cancelled)
            failed > 0 -> activity.getString(R.string.summary_partial, converted, failed)
            else -> activity.resources.getQuantityString(
                R.plurals.summary_converted,
                converted,
                converted,
            )
        }

        // The conversion outlives the activity when the user rotates or backs
        // out; the service is what keeps it running, so there may be no window
        // left to touch by the time it lands.
        val alive = !activity.isFinishing && !activity.isDestroyed

        dialog?.let { active ->
            if (alive && active.isShowing) {
                // A null ratio leaves the last one the poller wrote in place,
                // which is the figure worth reading once the run is over.
                update(
                    summary,
                    if (cancelled) 0 else 100,
                    ratio = null,
                    detail = activity.getString(R.string.progress_done_detail),
                )

                // The dialog stays up on an OK rather than vanishing, so the
                // final ratio is still readable.
                active.getButton(AlertDialog.BUTTON_NEGATIVE)?.apply {
                    text = activity.getString(android.R.string.ok)
                    setOnClickListener { active.dismiss() }
                }
            }
        }

        dialog = null

        runCatching {
            ConversionService.updateIdle(activity, activity.getString(R.string.notification_finished))

            if (!cancelled) {
                ConversionService.notifyDone(activity, summary)
            }

            activity.stopService(Intent(activity, ConversionService::class.java))
        }

        if (alive) {
            Toast.makeText(activity, summary, Toast.LENGTH_SHORT).show()
        }
    }

    private fun cancel() {
        cancelled = true
        Native.nativeCancel()
        Toast.makeText(activity, R.string.toast_cancelling, Toast.LENGTH_SHORT).show()
    }

    private fun onMain(action: () -> Unit) = handler.post(action)

    // ---------------------------------------------------------------------
    // Progress dialog
    //
    // Built in code rather than inflated, as it was before: the layout is not
    // referenced from anywhere else and the ids below are the plumbing that
    // update() writes through.
    // ---------------------------------------------------------------------

    private fun createProgressDialog(): AlertDialog {
        val night = (activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

        val primary = ContextCompat.getColor(
            activity,
            if (night) R.color.md_theme_dark_onSurface else R.color.md_theme_light_onSurface,
        )
        val secondary = ContextCompat.getColor(
            activity,
            if (night) R.color.md_theme_dark_onSurfaceVariant else R.color.md_theme_light_onSurfaceVariant,
        )

        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 40, 60, 40)
        }

        val status = TextView(activity).apply {
            text = activity.getString(R.string.progress_preparing)
            textSize = 16f
            setTextColor(primary)
            id = android.R.id.message
        }
        layout.addView(status, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = 24 })

        val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            max = 100
            progress = 0
            id = android.R.id.progress
        }
        layout.addView(bar, LinearLayout.LayoutParams(MATCH, 24).apply { bottomMargin = 16 })

        val percent = TextView(activity).apply {
            text = "0%"
            textSize = 14f
            setTextColor(if (night) 0xFFFFEF00.toInt() else 0xFF1B5E20.toInt())
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
            id = android.R.id.text1
        }
        layout.addView(percent, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = 12 })

        val stats = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        val ratio = TextView(activity).apply {
            text = activity.getString(R.string.progress_ratio_pending)
            textSize = 12f
            setTextColor(if (night) 0xFFFF5C00.toInt() else 0xFFD84315.toInt())
            gravity = Gravity.CENTER
            id = android.R.id.text2
        }
        stats.addView(ratio, LinearLayout.LayoutParams(0, WRAP, 1.0f))

        val separator = TextView(activity).apply {
            text = "•"
            textSize = 12f
            setTextColor(secondary)
            gravity = Gravity.CENTER
        }
        stats.addView(
            separator,
            LinearLayout.LayoutParams(WRAP, WRAP).apply {
                leftMargin = 8
                rightMargin = 8
            },
        )

        val size = TextView(activity).apply {
            text = activity.getString(R.string.progress_size_pending)
            textSize = 12f
            setTextColor(primary)
            gravity = Gravity.CENTER
            id = android.R.id.summary
        }
        stats.addView(size, LinearLayout.LayoutParams(0, WRAP, 1.0f))

        layout.addView(stats)

        val note = TextView(activity).apply {
            text = activity.getString(R.string.progress_background_note)
            textSize = 10f
            setTextColor(secondary)
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 0)
            setTypeface(null, Typeface.ITALIC)
        }
        layout.addView(note)

        val built = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.progress_title)
            .setCancelable(false)
            .setView(layout)
            .setNegativeButton(R.string.progress_cancel) { _, _ -> cancel() }
            .create()

        built.setOnShowListener {
            built.getButton(AlertDialog.BUTTON_NEGATIVE)?.let { styleButton(it, night) }
        }

        return built
    }

    private fun update(message: String, percent: Int, ratio: String?, detail: String?) {
        val active = dialog ?: return

        if (!active.isShowing) {
            return
        }

        active.findViewById<TextView>(android.R.id.message)?.text = message

        if (percent >= 0) {
            active.findViewById<ProgressBar>(android.R.id.progress)?.progress = percent
            active.findViewById<TextView>(android.R.id.text1)?.text = "$percent%"
        }

        ratio?.let {
            active.findViewById<TextView>(android.R.id.text2)?.text =
                activity.getString(R.string.progress_ratio, it)
        }
        detail?.let { active.findViewById<TextView>(android.R.id.summary)?.text = it }
    }

    private fun styleButton(button: Button, night: Boolean) {
        val background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 20f
            setColor(if (night) 0xFF2B2930.toInt() else 0xFF8B4513.toInt())
        }

        button.setTextColor(if (night) 0xFFE6E3DD.toInt() else 0xFF000000.toInt())
        button.background = background
        button.setPadding(32, 16, 32, 16)
    }

    // ---------------------------------------------------------------------
    // Track sheet sidecars
    // ---------------------------------------------------------------------

    /**
     * The files a `.cue` or `.gdi` points at, resolved against the folder it
     * sits in. Only the names matter here — the native parser does the real
     * parsing; this pass exists so every referenced file gets a descriptor
     * bound before the conversion starts.
     */
    private fun sidecarsFor(file: DocumentFile, byName: Map<String, DocumentFile>): List<DocumentFile> {
        val name = file.name ?: return emptyList()

        val names = when (extensionOf(name)) {
            "cue" -> readLines(file.uri).mapNotNull { cueFileName(it) }
            "gdi" -> readLines(file.uri).drop(1).mapNotNull { gdiFileName(it) }
            else -> return emptyList()
        }

        return names.distinct().mapNotNull { referenced ->
            byName[referenced] ?: byName.entries
                .firstOrNull { it.key.equals(referenced, ignoreCase = true) }
                ?.value
        }
    }

    private fun cueFileName(line: String): String? {
        val trimmed = line.trim()

        if (!trimmed.startsWith("FILE ", ignoreCase = true)) {
            return null
        }

        val first = trimmed.indexOf('"')
        val last = trimmed.lastIndexOf('"')

        if (first >= 0 && last > first) {
            return trimmed.substring(first + 1, last)
        }

        // Unquoted names are legal and do appear, though they cannot contain
        // spaces — which is what makes splitting on whitespace safe here.
        return trimmed.split(Regex("\\s+")).getOrNull(1)
    }

    private fun gdiFileName(line: String): String? {
        val trimmed = line.trim()

        if (trimmed.isEmpty()) {
            return null
        }

        val quoted = Regex("\"([^\"]+)\"").find(trimmed)

        if (quoted != null) {
            return quoted.groupValues[1]
        }

        return trimmed.split(Regex("\\s+")).getOrNull(4)
    }

    private fun readLines(uri: Uri): List<String> = runCatching {
        activity.contentResolver.openInputStream(uri)?.use { stream ->
            stream.bufferedReader().readLines()
        }.orEmpty()
    }.getOrElse {
        Log.e(TAG, "Could not read a track sheet", it)
        emptyList()
    }

    private fun extensionOf(name: String) = name.substringAfterLast('.', "").lowercase(Locale.ROOT)

    // InputStream.skip and read are both allowed to do less than asked, which
    // for a content:// stream they routinely do.
    private fun InputStream.skipExactly(count: Long): Boolean {
        var remaining = count

        while (remaining > 0) {
            val skipped = skip(remaining)

            if (skipped > 0) {
                remaining -= skipped
                continue
            }

            // skip() returning 0 is not necessarily the end, so fall back to
            // reading before giving up.
            if (read() < 0) {
                return false
            }

            remaining--
        }

        return true
    }

    private fun InputStream.readExactly(into: ByteArray): Boolean = readAtMost(into) == into.size

    private fun InputStream.readAtMost(into: ByteArray): Int {
        var filled = 0

        while (filled < into.size) {
            val got = read(into, filled, into.size - filled)

            if (got <= 0) {
                break
            }

            filled += got
        }

        return filled
    }

    private fun ByteArray.containsAscii(needle: String, length: Int): Boolean {
        val pattern = needle.toByteArray(Charsets.US_ASCII)

        if (length < pattern.size) {
            return false
        }

        outer@ for (start in 0..length - pattern.size) {
            for (i in pattern.indices) {
                if (this[start + i] != pattern[i]) {
                    continue@outer
                }
            }

            return true
        }

        return false
    }

    private fun formatSize(bytes: Long): String {
        if (bytes < 1024) {
            return "$bytes B"
        }

        val exponent = (log10(bytes.toDouble()) / log10(1024.0)).toInt()
        val unit = "KMGTPE"[exponent - 1]
        return String.format(Locale.ROOT, "%.1f %sB", bytes / 1024.0.pow(exponent.toDouble()), unit)
    }

    private companion object {
        const val TAG = "Converter"

        val SUPPORTED = setOf("iso", "cue", "gdi")

        /** CHD has no registered type; this is what the save dialog wants. */
        const val CHD_MIME = "application/octet-stream"

        /**
         * false selects zstd, which is what makes converting a full-size image
         * on a phone practical. Flip it to write Deflate for tooling whose CHD
         * support predates zstd (chdman before 0.264).
         */
        const val PORTABLE = false

        /**
         * The writer's own default, restated here because the PSP dialog
         * offers it as the alternative and the two must agree.
         */
        const val DEFAULT_HUNK_BYTES = 128 * 1024

        /** What dev.ppsspp.org asks for: one 2048-byte sector per hunk. */
        const val PSP_HUNK_BYTES = 2048

        const val POLL_INTERVAL_MS = 500L

        /** ISO 9660 puts the primary volume descriptor at sector 16. */
        const val SECTOR_BYTES = 2048
        const val PVD_OFFSET = 16L * SECTOR_BYTES
        const val DIRECTORY_SCAN_BYTES = 512 * 1024

        const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
    }
}
