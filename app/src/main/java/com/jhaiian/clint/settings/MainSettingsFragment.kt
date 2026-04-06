package com.jhaiian.clint.settings

import android.os.Bundle
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.jhaiian.clint.R

class MainSettingsFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.main_preferences, rootKey)
    }

    override fun onResume() {
        super.onResume()
        updateVersionSummary()
    }

    private fun updateVersionSummary() {
        val pInfo = requireContext().packageManager.getPackageInfo(requireContext().packageName, 0)
        findPreference<Preference>("pref_about")?.summary =
            getString(R.string.about_summary, pInfo.versionName)
    }
}
