package com.example.kiosk

import android.app.ActivityManager
import android.app.NotificationManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class KioskActivity : ComponentActivity() {

    private val dpm by lazy { getSystemService(DevicePolicyManager::class.java) }
    private val am by lazy { getSystemService(ActivityManager::class.java) }
    private val nm by lazy { getSystemService(NotificationManager::class.java) }
    private val prefs by lazy { getSharedPreferences("kiosk", MODE_PRIVATE) }
    private val admin by lazy { ComponentName(this, KioskAdminReceiver::class.java) }
    private val homeAlias by lazy { ComponentName(this, "com.example.kiosk.KioskHome") }

    private var isLocked by mutableStateOf(false)
    private var isDeviceOwner by mutableStateOf(false)
    private var hasDndAccess by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Without this, Back on the root activity would finish the task.
        onBackPressedDispatcher.addCallback(this) { }

        // Screen pinning can be ended by the system gesture with no callback to the app → poll.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    refreshLockState()
                    delay(1_000)
                }
            }
        }

        setContent {
            MaterialTheme {
                KioskApp(
                    isLocked = isLocked,
                    isDeviceOwner = isDeviceOwner,
                    hasDndAccess = hasDndAccess,
                    onStartSession = ::startSession,
                    onExitRequested = ::exitKiosk,
                    onOpenDndSettings = { openSettings(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS) },
                    onOpenPinningSettings = { openSettings(Settings.ACTION_SECURITY_SETTINGS) },
                )
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemBars()   // bars come back after dialogs / focus loss
            refreshLockState() // the "Pin app?" dialog steals focus; re-check when it closes
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (isLocked && (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            return true // consume
        }
        return super.onKeyDown(keyCode, event)
    }

    // ---- Session lifecycle -----------------------------------------------------------

    private fun startSession() {
        if (dpm.isDeviceOwnerApp(packageName)) configureDeviceOwnerPolicies()
        // Allowlisted (device owner) → silent lock. Otherwise → system "Pin app?" dialog.
        startLockTask()
        refreshLockState()
    }

    private fun exitKiosk() {
        if (dpm.isDeviceOwnerApp(packageName)) {
            dpm.clearPackagePersistentPreferredActivities(admin, packageName)
            dpm.setStatusBarDisabled(admin, false)
            setHomeAliasEnabled(false)
        }
        stopLockTask()
        refreshLockState()
    }

    /**
     * Reconciles side effects (DND) with the *observed* lock state instead of with button
     * presses. This covers: pin dialog declined, system unpin gesture, and crash recovery
     * (DND restored on next launch).
     */
    private fun refreshLockState() {
        val locked = am.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE
        val sessionActive = prefs.getBoolean(KEY_SESSION_ACTIVE, false)
        when {
            locked && !sessionActive -> onSessionStarted()
            !locked && sessionActive -> onSessionEnded()
        }
        isLocked = locked
        isDeviceOwner = dpm.isDeviceOwnerApp(packageName)
        hasDndAccess = nm.isNotificationPolicyAccessGranted
    }

    private fun onSessionStarted() {
        if (nm.isNotificationPolicyAccessGranted) {
            val current = nm.currentInterruptionFilter
            val previous = if (current in VALID_FILTERS) current else NotificationManager.INTERRUPTION_FILTER_ALL
            prefs.edit(commit = true) { putInt(KEY_PREVIOUS_FILTER, previous) }
            // NONE also silences alarms; use INTERRUPTION_FILTER_ALARMS if parents need them.
            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
        }
        prefs.edit(commit = true) { putBoolean(KEY_SESSION_ACTIVE, true) }
    }

    private fun onSessionEnded() {
        val previous = prefs.getInt(KEY_PREVIOUS_FILTER, -1)
        if (previous in VALID_FILTERS && nm.isNotificationPolicyAccessGranted) {
            nm.setInterruptionFilter(previous)
        }
        prefs.edit(commit = true) {
            remove(KEY_PREVIOUS_FILTER)
            putBoolean(KEY_SESSION_ACTIVE, false)
        }
    }

    // ---- Device owner ----------------------------------------------------------------

    private fun configureDeviceOwnerPolicies() {
        dpm.setLockTaskPackages(admin, arrayOf(packageName))
        // NONE: no Home, Recents, notifications, power menu, or status-bar info.
        dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_NONE)
        dpm.setStatusBarDisabled(admin, true)

        // Become the launcher, so Home and reboots land back in the kiosk.
        setHomeAliasEnabled(true)
        val home = IntentFilter(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addCategory(Intent.CATEGORY_DEFAULT)
        }
        dpm.addPersistentPreferredActivity(admin, home, homeAlias)
    }

    private fun setHomeAliasEnabled(enabled: Boolean) {
        packageManager.setComponentEnabledSetting(
            homeAlias,
            if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
    }

    // ---- Helpers ---------------------------------------------------------------------

    private fun hideSystemBars() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun openSettings(action: String) {
        // Settings screens differ across OEMs; fall back to the main Settings page.
        runCatching { startActivity(Intent(action)) }
            .onFailure { runCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) } }
    }

    private companion object {
        const val KEY_SESSION_ACTIVE = "session_active"
        const val KEY_PREVIOUS_FILTER = "previous_interruption_filter"
        val VALID_FILTERS = NotificationManager.INTERRUPTION_FILTER_ALL..NotificationManager.INTERRUPTION_FILTER_ALARMS
    }
}
