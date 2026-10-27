package com.example.kiosk

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private const val WRONG_ANSWER_COOLDOWN_MS = 10_000L

@Composable
fun KioskApp(
    isLocked: Boolean,
    isDeviceOwner: Boolean,
    hasDndAccess: Boolean,
    onStartSession: () -> Unit,
    onExitRequested: () -> Unit,
    onOpenDndSettings: () -> Unit,
    onOpenPinningSettings: () -> Unit,
) {
    if (isLocked) {
        LockedPlayground(onExitRequested)
    } else {
        StartScreen(isDeviceOwner, hasDndAccess, onStartSession, onOpenDndSettings, onOpenPinningSettings)
    }
}

@Composable
private fun LockedPlayground(onExitRequested: () -> Unit) {
    var showGate by remember { mutableStateOf(false) }
    var lockedOutUntil by remember { mutableLongStateOf(0L) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFFFFF4DE), Color(0xFFDDEFFF))))
    ) {
        EmojiPlayground(Modifier.fillMaxSize())

        // Drawn after the canvas → on top in hit-testing, so it receives its own taps.
        ExitButton(
            onClick = { if (SystemClock.elapsedRealtime() >= lockedOutUntil) showGate = true },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .windowInsetsPadding(WindowInsets.displayCutout)
                .padding(16.dp), // away from edge-swipe zones
        )

        if (showGate) {
            MultiplicationGateDialog(
                onPass = {
                    showGate = false
                    onExitRequested()
                },
                onFail = {
                    // Wrong answer closes the gate and blocks it briefly: guessing 0–99 becomes impractical.
                    showGate = false
                    lockedOutUntil = SystemClock.elapsedRealtime() + WRONG_ANSWER_COOLDOWN_MS
                },
                onDismiss = { showGate = false },
            )
        }
    }
}

@Composable
private fun StartScreen(
    isDeviceOwner: Boolean,
    hasDndAccess: Boolean,
    onStartSession: () -> Unit,
    onOpenDndSettings: () -> Unit,
    onOpenPinningSettings: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Text("Toddler Playground", style = MaterialTheme.typography.headlineMedium)
        Button(
            onClick = onStartSession,
            modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth().height(56.dp),
        ) { Text("Start locked play session") }

        HorizontalDivider(Modifier.widthIn(max = 420.dp))
        Text("Parent setup", style = MaterialTheme.typography.titleMedium)

        if (isDeviceOwner) {
            Text("Device-owner mode: full lock is active.")
        } else {
            SetupRow(
                text = "Turn on \"Ask for PIN before unpinning\" (usually Security → App pinning).",
                action = "Open",
                onClick = onOpenPinningSettings,
            )
        }
        if (!hasDndAccess) {
            SetupRow(
                text = "Allow Do Not Disturb access to hide notifications during play.",
                action = "Allow",
                onClick = onOpenDndSettings,
            )
        }
    }
}

@Composable
private fun SetupRow(text: String, action: String, onClick: () -> Unit) {
    Row(
        Modifier.widthIn(max = 420.dp).fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = onClick) { Text(action) }
    }
}
