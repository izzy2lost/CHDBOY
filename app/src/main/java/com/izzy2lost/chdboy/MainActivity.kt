package com.izzy2lost.chdboy

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import com.izzy2lost.chdboy.utils.FilePicker
import com.izzy2lost.chdboy.utils.Operations
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton

class MainActivity : AppCompatActivity() {

    private lateinit var picker: FilePicker
    private lateinit var fab: ExtendedFloatingActionButton
    private lateinit var fabFolder: ExtendedFloatingActionButton
    private lateinit var fabFiles: ExtendedFloatingActionButton
    private lateinit var scrim: View

    private var fabMenuOpen = false

    private val closeFabMenuOnBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = setFabMenuOpen(false)
    }

    private var bottomSheetBehavior: BottomSheetBehavior<LinearLayout>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        PreferenceManager.setDefaultValues(this, R.xml.preferences, false)
        applyTheme(
            PreferenceManager.getDefaultSharedPreferences(this)
                .getString("theme", "Light") ?: "Light",
        )

        setContentView(R.layout.activity_main)

        picker = FilePicker(this)

        setSupportActionBar(findViewById<MaterialToolbar>(R.id.main_toolbar))

        // The layout draws its own centred title, so the bar's would be a
        // second one next to it.
        supportActionBar?.title = ""

        initializeViews()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            showNotificationPermissionDialog()
        }
    }

    private fun initializeViews() {
        fab = findViewById(R.id.fab)
        fabFolder = findViewById(R.id.fab_folder)
        fabFiles = findViewById(R.id.fab_files)
        scrim = findViewById(R.id.fab_scrim)

        fab.setOnClickListener { setFabMenuOpen(!fabMenuOpen) }
        scrim.setOnClickListener { setFabMenuOpen(false) }

        fabFolder.setOnClickListener {
            setFabMenuOpen(false)
            Operations.pendingOperation = "compress"
            picker.pickFolder()
        }

        fabFiles.setOnClickListener {
            setFabMenuOpen(false)
            Operations.pendingOperation = "compress_files"
            picker.pickFiles()
        }

        onBackPressedDispatcher.addCallback(this, closeFabMenuOnBack)

        val bottomSheet = findViewById<LinearLayout>(R.id.bottom_sheet_folder_selection)

        if (bottomSheet != null) {
            bottomSheetBehavior = BottomSheetBehavior.from(bottomSheet).apply {
                state = BottomSheetBehavior.STATE_HIDDEN
                isHideable = true
                peekHeight = 0
            }
        }

        findViewById<MaterialCardView>(R.id.compress_option)?.setOnClickListener { card ->
            animateCardPress(card as MaterialCardView)
            hideBottomSheet()
            Operations.pendingOperation = "compress"
            picker.pickFolder()
        }

        findViewById<MaterialCardView>(R.id.transfer_option)?.setOnClickListener { card ->
            animateCardPress(card as MaterialCardView)
            hideBottomSheet()
            Operations.pendingOperation = "transfer"
            picker.pickFolder()
        }
    }

    private fun applyTheme(theme: String) {
        AppCompatDelegate.setDefaultNightMode(
            when (theme) {
                "Light" -> AppCompatDelegate.MODE_NIGHT_NO
                "Dark" -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            },
        )
    }

    /**
     * Opens or closes the speed dial. The main button shrinks to a round close
     * button while the two choices rise out of it, the lower one first.
     */
    private fun setFabMenuOpen(open: Boolean) {
        if (open == fabMenuOpen) {
            return
        }

        fabMenuOpen = open
        closeFabMenuOnBack.isEnabled = open

        val rise = resources.displayMetrics.density * FAB_RISE_DP
        val choices = listOf(fabFolder, fabFiles)

        if (open) {
            fab.setIconResource(R.drawable.ic_close)
            fab.shrink()

            scrim.visibility = View.VISIBLE
            scrim.animate().alpha(1f).setDuration(FAB_ANIM_MS).start()

            choices.forEachIndexed { index, choice ->
                choice.animate().cancel()
                choice.alpha = 0f
                choice.scaleX = 0.6f
                choice.scaleY = 0.6f
                choice.translationY = rise * (index + 1)
                choice.visibility = View.VISIBLE

                choice.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .translationY(0f)
                    .setStartDelay(index * FAB_STAGGER_MS)
                    .setDuration(FAB_ANIM_MS)
                    .setInterpolator(OvershootInterpolator(1.4f))
                    .start()
            }
        } else {
            fab.setIconResource(R.drawable.compress_24px)
            fab.extend()

            scrim.animate()
                .alpha(0f)
                .setDuration(FAB_ANIM_MS)
                .withEndAction { if (!fabMenuOpen) scrim.visibility = View.GONE }
                .start()

            // Reversed, so the one furthest from the button leaves first.
            choices.asReversed().forEachIndexed { index, choice ->
                choice.animate().cancel()
                choice.animate()
                    .alpha(0f)
                    .scaleX(0.6f)
                    .scaleY(0.6f)
                    .translationY(rise * (choices.size - index))
                    .setStartDelay(index * FAB_STAGGER_MS)
                    .setDuration(FAB_ANIM_MS)
                    .setInterpolator(AccelerateInterpolator())
                    .withEndAction { if (!fabMenuOpen) choice.visibility = View.INVISIBLE }
                    .start()
            }
        }
    }

    private fun animateCardPress(card: MaterialCardView) {
        card.animate()
            .scaleX(0.95f)
            .scaleY(0.95f)
            .setDuration(100)
            .withEndAction {
                card.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
            }
            .start()
    }

    private fun hideBottomSheet() {
        bottomSheetBehavior?.state = BottomSheetBehavior.STATE_HIDDEN
    }

    private fun showNotificationPermissionDialog() {
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.notifications_title)
            .setMessage(R.string.notifications_message)
            .setPositiveButton(R.string.notifications_allow) { _, _ ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    ActivityCompat.requestPermissions(
                        this,
                        arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                        REQUEST_NOTIFICATIONS,
                    )
                }
            }
            .setNegativeButton(R.string.notifications_decline) { d, _ -> d.dismiss() }
            .setCancelable(false)
            .create()

        dialog.show()
        styleDialogButtons(dialog)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_settings -> {
            startActivity(Intent(this, SettingsActivity::class.java))
            true
        }

        R.id.action_about -> {
            showAboutDialog()
            true
        }

        else -> super.onOptionsItemSelected(item)
    }

    private fun showAboutDialog() {
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.about_title)
            .setMessage(getString(R.string.about_message))
            .setPositiveButton(R.string.about_button_website) { _, _ ->
                openUrl("https://izzy2lost.github.io/CHDBOY/")
            }
            .setNeutralButton(R.string.about_button_license) { _, _ ->
                openUrl("https://www.gnu.org/licenses/old-licenses/gpl-2.0.html")
            }
            .setNegativeButton(android.R.string.ok, null)
            .create()

        dialog.show()
        styleDialogButtons(dialog)
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.about_browser_error, Toast.LENGTH_SHORT).show()
        }
    }

    private fun styleDialogButtons(dialog: AlertDialog) {
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

        for (which in intArrayOf(
            AlertDialog.BUTTON_POSITIVE,
            AlertDialog.BUTTON_NEGATIVE,
            AlertDialog.BUTTON_NEUTRAL,
        )) {
            dialog.getButton(which)?.let { styleButton(it, night) }
        }
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

    private companion object {
        const val REQUEST_NOTIFICATIONS = 1

        const val FAB_ANIM_MS = 220L
        const val FAB_STAGGER_MS = 50L

        /** How far below its resting place each choice starts, per step. */
        const val FAB_RISE_DP = 24f
    }
}
