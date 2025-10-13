package com.chdboy;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.view.animation.AnimationUtils;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.documentfile.provider.DocumentFile;
import androidx.preference.PreferenceManager;
import com.chdboy.utils.Chdman;
import com.chdboy.utils.FilePicker;
import com.chdboy.utils.Operations;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import android.widget.LinearLayout;

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
        new MaterialAlertDialogBuilder(this)
            .setTitle("Enable Notifications")
            .setMessage("CHDBOY needs notification permission to keep you updated on compression progress.\n\n" +
                       "Some conversions can take a while depending on file size. Notifications allow the app to:\n\n" +
                       "• Run compressions in the background\n" +
                       "• Show progress updates\n" +
                       "• Notify you when conversions are complete")
            .setPositiveButton("Allow", (dialog, which) -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    ActivityCompat.requestPermissions(this, 
                        new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1);
                }
            })
            .setNegativeButton("Not Now", (dialog, which) -> {
                dialog.dismiss();
            })
            .setCancelable(false)
            .show();
    }
    
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_settings) {
            Intent settingsIntent = new Intent(this, SettingsActivity.class);
            startActivity(settingsIntent);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
