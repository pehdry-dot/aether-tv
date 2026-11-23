package com.aether.tv

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.aether.tv.ui.LauncherApp
import com.aether.tv.ui.LauncherViewModel
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val vm: LauncherViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { LauncherApp(vm) }
        // The wallpaper covers the whole window; painting the theme's background colour under it every frame was a
        // wasted full-screen pass (the TV's GPU redraws the whole screen each frame: partial updates are off).
        window.setBackgroundDrawable(null)
    }

    /** Every key, overlays included: background page building waits for the remote to go quiet. */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        com.aether.tv.ui.UserActivity.lastKeyAt = android.os.SystemClock.uptimeMillis()
        return super.dispatchKeyEvent(event)
    }

    override fun onResume() {
        super.onResume()
        vm.onResume()
    }

    override fun onPause() {
        vm.onPause()
        super.onPause()
    }

    /** Pressing Home while Aether is the default Home app returns to the Home tab and closes overlays. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.hasCategory(Intent.CATEGORY_HOME)) vm.onHomePressed()
    }
}
