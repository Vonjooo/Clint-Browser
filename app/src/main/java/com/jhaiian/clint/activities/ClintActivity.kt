package com.jhaiian.clint.activities

import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.preference.PreferenceManager

abstract class ClintActivity : AppCompatActivity() {

    override fun onResume() {
        super.onResume()
        applyStatusBarVisibility()
    }

    private fun applyStatusBarVisibility() {
        val hide = PreferenceManager.getDefaultSharedPreferences(this)
            .getBoolean("hide_status_bar", false)
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (hide) {
            controller.hide(WindowInsetsCompat.Type.statusBars())
        } else {
            controller.show(WindowInsetsCompat.Type.statusBars())
        }
    }
}
