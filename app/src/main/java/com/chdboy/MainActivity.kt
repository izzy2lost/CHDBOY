package com.chdboy

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
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import com.chdboy.utils.FilePicker
import com.chdboy.utils.Operations
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton

class MainActivity : AppCompatActivity() {

    private lateinit var picker: FilePicker
    private lateinit var fab: ExtendedFloatingActionButton

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
        fab.setOnClickListener {
            Operations.pendingOperation = "compress"
            picker.pickFolder()
        }

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
    }
}
