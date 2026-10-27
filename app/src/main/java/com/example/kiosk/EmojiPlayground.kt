package com.example.kiosk

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.example.kiosk.physics.EmojiWorld

private val EMOJIS = listOf("🐶", "🐱", "🐻", "🐼", "🦁", "🐸", "🐵", "🐰", "🦊", "🐯", "🍎", "🍓", "⚽", "🎈")
private val SIZES_DP = listOf(64, 56, 72, 60, 76, 52, 64, 56, 60, 72, 52, 56, 60, 68)

/** Collision radius as a fraction of the glyph's half-width (emoji glyphs have side bearings). */
private const val RADIUS_FACTOR = 0.9f
private const val RAD_TO_DEG = (180.0 / Math.PI).toFloat()

@Composable
fun EmojiPlayground(modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()

    // Measure each glyph once; the layout is reused every frame.
    val glyphs = remember(textMeasurer, density) {
        EMOJIS.zip(SIZES_DP) { emoji, sizeDp ->
            // dp → sp so emoji size ignores the user's font-scale setting.
            textMeasurer.measure(emoji, style = TextStyle(fontSize = with(density) { sizeDp.dp.toSp() }))
        }
    }
    val world = remember(glyphs) {
        EmojiWorld(radii = glyphs.map { it.size.width / 2f * RADIUS_FACTOR }, density = density.density)
    }

    // Bumped every frame. Read only inside the draw lambda → invalidates draw, not composition.
    var frameTick by remember { mutableLongStateOf(0L) }

    LaunchedEffect(world) {
        var last = withFrameNanos { it }
        while (true) {
            // Suspends while the activity is stopped, so no battery drain in background.
            withFrameNanos { now ->
                world.advance((now - last) / 1_000_000_000f)
                last = now
                frameTick = now
            }
        }
    }

    // Gesture navigation reserves the left/right edges for the Back swipe, so touches there never
    // reach the canvas. Opt a band of each edge out so emojis near the walls can be swiped.
    // Android honours at most 200 dp of vertical exclusion per edge (API 29+, no-op below).
    // Escape is still impossible: Home is never excludable, and Back is swallowed by the activity.
    val edgeWidth = with(density) { 48.dp.toPx() }
    val bandHeight = with(density) { 200.dp.toPx() }

    Canvas(
        modifier
            .systemGestureExclusion { c -> edgeBand(c.size.width.toFloat(), c.size.height.toFloat(), 0f, edgeWidth, bandHeight) }
            .systemGestureExclusion { c ->
                val w = c.size.width.toFloat()
                edgeBand(w, c.size.height.toFloat(), w - edgeWidth, w, bandHeight)
            }
            .onSizeChanged { world.resize(it.width.toFloat(), it.height.toFloat()) }
            .pointerInput(world) {
                val trackers = HashMap<PointerId, VelocityTracker>()
                try {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            // Each finger is handled independently: toddlers use several at once.
                            for (change in event.changes) {
                                val id = change.id.value
                                val pos = change.position
                                when {
                                    change.changedToDown() -> {
                                        trackers[change.id] = VelocityTracker().apply {
                                            addPosition(change.uptimeMillis, pos)
                                        }
                                        world.pointerDown(id, pos.x, pos.y)
                                    }
                                    change.changedToUp() -> {
                                        val v = trackers.remove(change.id)?.calculateVelocity() ?: Velocity.Zero
                                        world.pointerUp(id, v.x, v.y)
                                    }
                                    change.pressed -> {
                                        val tracker = trackers[change.id] ?: continue
                                        tracker.addPosition(change.uptimeMillis, pos)
                                        val v = tracker.calculateVelocity()
                                        world.pointerMove(id, pos.x, pos.y, v.x, v.y)
                                    }
                                }
                                change.consume()
                            }
                        }
                    }
                } finally {
                    // Gesture cancelled (e.g. dialog opened): drop any held emojis.
                    world.releaseAll()
                }
            }
    ) {
        @Suppress("UNUSED_EXPRESSION")
        frameTick

        for (b in world.bodies) {
            val glyph = glyphs[b.glyph]
            rotate(degrees = b.angle * RAD_TO_DEG, pivot = Offset(b.x, b.y)) {
                drawText(
                    textLayoutResult = glyph,
                    topLeft = Offset(b.x - glyph.size.width / 2f, b.y - glyph.size.height / 2f),
                )
            }
        }
    }
}

/** A vertically centred band [left, right] × bandHeight. */
private fun edgeBand(width: Float, height: Float, left: Float, right: Float, bandHeight: Float): Rect {
    val top = ((height - bandHeight) / 2f).coerceAtLeast(0f)
    return Rect(left.coerceIn(0f, width), top, right.coerceIn(0f, width), (top + bandHeight).coerceAtMost(height))
}
