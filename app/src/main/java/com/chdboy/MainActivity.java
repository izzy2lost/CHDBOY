package com.chdboy;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.net.Uri;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.documentfile.provider.DocumentFile;
import androidx.preference.PreferenceManager;
import android.content.res.Configuration;
import android.widget.Button;
import android.graphics.drawable.GradientDrawable;
import com.chdboy.utils.Chdman;
import com.chdboy.utils.FilePicker;
import com.chdboy.utils.Operations;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import android.widget.LinearLayout;
import android.widget.Toast;

public class MainActivity extends AppCompatActivity {
    
    private FilePicker picker;
    private static MainActivity instance;
    private MaterialToolbar toolbar;
    private ExtendedFloatingActionButton fab;
    private BottomSheetBehavior<LinearLayout> bottomSheetBehavior;
    private LinearLayout bottomSheet;
    private MaterialCardView compressOption;
    private MaterialCardView transferOption;
    
    public MainActivity() {
        instance = this;
    }

    public static MainActivity getInstance() {
        return instance;
    }
    
    private String getEnabledTheme() {
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(getInstance());
        String theme = sp.getString("theme", "Light");
        return theme;
    }
    
    private void setEnabledTheme(String theme) {
        switch (theme) {
            case "Light":
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
                break;
            case "Dark":
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
                break;
            case "Follow System":
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
                break;
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Initialize preferences and theme
        PreferenceManager.setDefaultValues(this, R.xml.preferences, false);
        setEnabledTheme(getEnabledTheme());
        
        // Set up the layout
        setContentView(R.layout.activity_main);
        
        // Initialize picker
        picker = new FilePicker(this);
        
        // Set up toolbar
        toolbar = findViewById(R.id.main_toolbar);
        setSupportActionBar(toolbar);
        
        // Clear default title to show only our custom centered title
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("");
        }
        
        // Initialize views
        initializeViews();
        
        // Set up click listeners
        setupClickListeners();
        
        // Request notification permission for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                showNotificationPermissionDialog();
            }
        }
    }
    
    private void initializeViews() {
        fab = findViewById(R.id.fab);
        
        // Initialize bottom sheet
        bottomSheet = findViewById(R.id.bottom_sheet_folder_selection);
        compressOption = findViewById(R.id.compress_option);
        transferOption = findViewById(R.id.transfer_option);
        
        if (bottomSheet != null) {
            bottomSheetBehavior = BottomSheetBehavior.from(bottomSheet);
            bottomSheetBehavior.setState(BottomSheetBehavior.STATE_HIDDEN);
            bottomSheetBehavior.setHideable(true);
            bottomSheetBehavior.setPeekHeight(0);
        }
    }
    
    private void setupClickListeners() {
        // FAB click listener - directly compress files
        fab.setOnClickListener(v -> {
            Operations.pendingOperation = "compress";
            picker.pickFolder();
        });
        
        // Bottom sheet option click listeners
        if (compressOption != null) {
            compressOption.setOnClickListener(v -> {
                animateCardPress(compressOption);
                hideBottomSheet();
                Operations.pendingOperation = "compress";
                picker.pickFolder();
            });
        }
        
        if (transferOption != null) {
            transferOption.setOnClickListener(v -> {
                animateCardPress(transferOption);
                hideBottomSheet();
                Operations.pendingOperation = "transfer";
                picker.pickFolder();
            });
        }
    }
    
    private void animateCardPress(MaterialCardView card) {
        card.animate()
            .scaleX(0.95f)
            .scaleY(0.95f)
            .setDuration(100)
            .withEndAction(() -> {
                card.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(100)
                    .start();
            })
            .start();
    }
    
    private void showFolderSelectionMenu() {
        showBottomSheet(null);
    }
    
    private void showBottomSheet(String preselectedOperation) {
        if (bottomSheetBehavior != null) {
            bottomSheetBehavior.setState(BottomSheetBehavior.STATE_EXPANDED);
        }
    }
    
    private void hideBottomSheet() {
        if (bottomSheetBehavior != null) {
            bottomSheetBehavior.setState(BottomSheetBehavior.STATE_HIDDEN);
        }
    }
    
    private void showNotificationPermissionDialog() {
        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
            .setTitle("Enable Notifications")
            .setMessage("CHDBOY needs notification permission to keep you updated on compression progress.\n\n" +
                       "Some conversions can take a while depending on file size. Notifications allow the app to:\n\n" +
                       "- Run compressions in the background\n" +
                       "- Show progress updates\n" +
                       "- Notify you when conversions are complete")
            .setPositiveButton("Allow", (dialog1, which) -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    ActivityCompat.requestPermissions(this,
                        new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1);
                }
            })
            .setNegativeButton("Not Now", (dialog1, which) -> {
                dialog1.dismiss();
            })
            .setCancelable(false)
            .create();
        
        dialog.show();
        
        // Style buttons after showing dialog
        styleDialogButtons(dialog);
    }
    
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }
    
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_settings) {
            Intent settingsIntent = new Intent(this, SettingsActivity.class);
            startActivity(settingsIntent);
            return true;
        } else if (item.getItemId() == R.id.action_about) {
            showAboutDialog();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void showAboutDialog() {
        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.about_title)
            .setMessage(getString(R.string.about_message))
            .setPositiveButton(R.string.about_button_website, (dialog1, which) -> {
                openUrl("https://izzy2lost.github.io/CHDBOY/");
            })
            .setNeutralButton(R.string.about_button_license, (dialog1, which) -> {
                openUrl("https://www.gnu.org/licenses/old-licenses/gpl-2.0.html");
            })
            .setNegativeButton(android.R.string.ok, null)
            .create();
        
        dialog.show();
        
        // Style buttons after showing dialog
        styleDialogButtons(dialog);
    }

    private void openUrl(String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.about_browser_error, Toast.LENGTH_SHORT).show();
        }
    }
    
    private void styleDialogButtons(AlertDialog dialog) {
        // Check if we're in dark mode
        boolean isNightMode = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        
        // Get button references
        Button positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        Button negativeButton = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
        Button neutralButton = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
        
        // Style each button that exists
        if (positiveButton != null) {
            styleButton(positiveButton, isNightMode);
        }
        if (negativeButton != null) {
            styleButton(negativeButton, isNightMode);
        }
        if (neutralButton != null) {
            styleButton(neutralButton, isNightMode);
        }
    }
    
    private void styleButton(Button button, boolean isNightMode) {
        // Create rounded background
        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.RECTANGLE);
        background.setCornerRadius(20f);
        
        if (isNightMode) {
            // Dark mode: dark background with light text
            background.setColor(0xFF2B2930);
            button.setTextColor(0xFFE6E3DD);
        } else {
            // Light mode: brown background with black text
            background.setColor(0xFF8B4513);
            button.setTextColor(0xFF000000);
        }
        
        button.setBackground(background);
        button.setPadding(32, 16, 32, 16);
    }
}


