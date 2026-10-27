package com.example.kiosk

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.delay
import kotlin.random.Random

@Composable
fun ExitButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onClick, modifier = modifier.size(40.dp).alpha(0.35f)) {
        Icon(Icons.Filled.Lock, contentDescription = "Parent exit")
    }
}

/**
 * Single-digit multiplication on a custom keypad.
 * The custom keypad avoids the system IME, which has its own escape routes
 * (settings gear, voice input, clipboard).
 */
@Composable
fun MultiplicationGateDialog(
    onPass: () -> Unit,
    onFail: () -> Unit,
    onDismiss: () -> Unit,
) {
    // 2..9 excludes trivial ×0 and ×1. Max answer is 81 → 2 digits.
    val (a, b) = remember { Random.nextInt(2, 10) to Random.nextInt(2, 10) }
    var input by remember { mutableStateOf("") }

    // Auto-close if a toddler opened it and wandered off.
    LaunchedEffect(Unit) {
        delay(20_000)
        onDismiss()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnClickOutside = true),
    ) {
        HideSystemBarsInDialog()
        Surface(shape = RoundedCornerShape(16.dp)) {
            Column(
                Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Grown-ups only", style = MaterialTheme.typography.titleMedium)
                Text("$a × $b = ?", style = MaterialTheme.typography.headlineMedium)
                Text(input.ifEmpty { "–" }, style = MaterialTheme.typography.headlineLarge)
                NumberPad(
                    onDigit = { if (input.length < 2) input += it },
                    onDelete = { input = input.dropLast(1) },
                    onSubmit = { if (input.toIntOrNull() == a * b) onPass() else onFail() },
                )
            }
        }
    }
}

@Composable
private fun NumberPad(onDigit: (Char) -> Unit, onDelete: () -> Unit, onSubmit: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("123", "456", "789").forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { c -> PadKey(c.toString()) { onDigit(c) } }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PadKey("⌫", onDelete)
            PadKey("0") { onDigit('0') }
            PadKey("OK", onSubmit)
        }
    }
}

@Composable
private fun PadKey(label: String, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        modifier = Modifier.size(64.dp),
        contentPadding = PaddingValues(0.dp),
    ) { Text(label, style = MaterialTheme.typography.titleLarge) }
}

/** A Dialog has its own window, so the activity's hidden system bars can reappear. Re-hide them. */
@Composable
private fun HideSystemBarsInDialog() {
    val view = LocalView.current
    LaunchedEffect(view) {
        val window = (view.parent as? DialogWindowProvider)?.window ?: return@LaunchedEffect
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}
