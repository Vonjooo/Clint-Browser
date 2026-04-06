package com.jhaiian.clint.settings

import android.os.Bundle
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jhaiian.clint.R
import com.jhaiian.clint.activities.SettingsActivity

class LookAndFeelFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.look_and_feel_preferences, rootKey)

        findPreference<SwitchPreferenceCompat>("hide_status_bar")
            ?.setOnPreferenceChangeListener { _, _ ->
                showRestartDialog()
                true
            }
    }

    private fun showRestartDialog() {
        val activity = requireActivity() as? SettingsActivity ?: return
        MaterialAlertDialogBuilder(activity, R.style.ThemeOverlay_ClintBrowser_Dialog)
            .setTitle(getString(R.string.restart_required_title))
            .setMessage(getString(R.string.restart_required_message))
            .setCancelable(false)
            .setNegativeButton(getString(R.string.action_later)) { _, _ ->
                activity.pendingRestart = true
            }
            .setPositiveButton(getString(R.string.restart_required_confirm)) { _, _ ->
                activity.restartApp()
            }
            .show()
    }
}
