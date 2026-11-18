package com.aurora.companion.android

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import com.aurora.companion.android.ui.AuroraApp
import com.aurora.companion.android.ui.AuroraTheme

class MainActivity : ComponentActivity() {
    private val vm: AuroraViewModel by viewModels()

    /** 0.8.9: asked once (Android 13+) so the "companion is ready" keep-alive and reminders can show a notification. */
    private val notifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AuroraTheme {
                AuroraApp()
            }
        }
        if (Build.VERSION.SDK_INT >= 33 && savedInstanceState == null &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            val prefs = getSharedPreferences("aurora_prefs_once", MODE_PRIVATE)
            if (!prefs.getBoolean("asked_notifications", false)) {
                prefs.edit().putBoolean("asked_notifications", true).apply()
                notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    override fun onResume() { super.onResume(); ReminderBus.appVisible = true; vm.onAppForeground() }
    override fun onPause() { ReminderBus.appVisible = false; super.onPause() }
    override fun onStop() { super.onStop(); if (!isChangingConfigurations) vm.onAppBackground() }
}
