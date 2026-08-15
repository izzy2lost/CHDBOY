package com.chdboy.fragments

import android.os.Bundle
import androidx.appcompat.app.AppCompatDelegate
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.chdboy.R

class PreferenceFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)

        findPreference<Preference>("theme")?.let { theme ->
            // The summary is the current value, so it has to be seeded here as
            // well as updated on change.
            theme.summary = preferenceManager.sharedPreferences
                ?.getString("theme", "Light")

            theme.setOnPreferenceChangeListener { preference, newValue ->
                val value = newValue.toString()

                AppCompatDelegate.setDefaultNightMode(
                    when (value) {
                        "Light" -> AppCompatDelegate.MODE_NIGHT_NO
                        "Dark" -> AppCompatDelegate.MODE_NIGHT_YES
                        else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                    },
                )

                preference.summary = value
                true
            }
        }

        // "deletesource" needs no listener: the converter reads it from
        // SharedPreferences when a run starts, so there is no copy to keep in
        // sync -- which is what the old static field was for.
    }
}
