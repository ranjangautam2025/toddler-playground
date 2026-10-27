package com.example.kiosk.physics

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * A rigid disc. Units: pixels, seconds, radians.
 * Mass is proportional to area, so big emojis shove small ones.
 */
class Body(
    val glyph: Int,
    val radius: Float,
    var x: Float,
    var y: Float,
    var vx: Float,
    var vy: Float,
) {
    var angle = 0f
    var omega = 0f
    /** Held by a finger: moves with the finger and behaves as infinite mass. */
    var grabbed = false

    val mass = radius * radius
    val invMass: Float get() = if (grabbed) 0f else 1f / mass
}

/**
 * Zero-gravity world of discs inside a rectangle.
 *
 * Fixed-timestep, impulse-based solver:
 *  - integrate (with light damping + a minimum drift so emojis keep floating)
 *  - finger "paddles" push discs they sweep through
 *  - disc–disc elastic collisions (mass-weighted)
 *  - wall bounces with restitution; wall friction converts sliding into spin
 *
 * Not thread-safe: call everything from one thread (Compose's main thread).
 */
class EmojiWorld(
    private val radii: List<Float>,
    density: Float,
    private val random: Random = Random.Default,
) {
    val bodies = ArrayList<Body>(radii.size)

    var width = 0f
        private set
    var height = 0f
        private set

    private var accumulator = 0f

    private val maxSpeed = 3_500f * density
    private val driftSpeed = 40f * density
    private val fingerRadius = 24f * density

    private class Grab(val body: Body, val offsetX: Float, val offsetY: Float)
    private class Finger(var x: Float, var y: Float, var vx: Float = 0f, var vy: Float = 0f)

    private val grabs = HashMap<Long, Grab>()
    private val fingers = HashMap<Long, Finger>()

    fun resize(w: Float, h: Float) {
        width = w
        height = h
        if (bodies.isEmpty()) spawn() else bodies.forEach(::clampToBounds)
    }

    /** Advance by real elapsed time; runs as many fixed steps as fit. */
    fun advance(frameSeconds: Float) {
        if (width <= 0f || height <= 0f) return
        // Cap so a long pause (jank, app resumed) doesn't trigger a burst of catch-up steps.
        accumulator += frameSeconds.coerceIn(0f, MAX_FRAME)
        while (accumulator >= STEP) {
            step(STEP)
            accumulator -= STEP
        }
    }

    // ---- Touch input ------------------------------------------------------------------

    fun pointerDown(id: Long, x: Float, y: Float) {
        val hit = bodies.lastOrNull { !it.grabbed && hypot(it.x - x, it.y - y) <= it.radius * GRAB_SLOP }
        if (hit != null) {
            hit.grabbed = true
            hit.vx = 0f
            hit.vy = 0f
            hit.omega *= 0.5f
            bodies.remove(hit)
            bodies.add(hit) // draw on top
            grabs[id] = Grab(hit, hit.x - x, hit.y - y)
        } else {
            fingers[id] = Finger(x, y)
        }
    }

    /** [vx]/[vy]: finger velocity in px/s, from a VelocityTracker. */
    fun pointerMove(id: Long, x: Float, y: Float, vx: Float, vy: Float) {
        grabs[id]?.let { g ->
            val b = g.body
            b.x = x + g.offsetX
            b.y = y + g.offsetY
            b.vx = vx // so collisions with other discs transfer the finger's momentum
            b.vy = vy
            clampSpeed(b)
            clampToBounds(b)
            return
        }
        fingers[id]?.let {
            it.x = x
            it.y = y
            it.vx = vx
            it.vy = vy
        }
    }

    /** Release: a held disc is thrown with the finger's fling velocity. */
    fun pointerUp(id: Long, vx: Float, vy: Float) {
        grabs.remove(id)?.body?.let { b ->
            b.grabbed = false
            b.vx = vx
            b.vy = vy
            clampSpeed(b)
        }
        fingers.remove(id)
    }

    fun releaseAll() {
        grabs.values.forEach { it.body.grabbed = false }
        grabs.clear()
        fingers.clear()
    }

    // ---- Simulation ------------------------------------------------------------------

    private fun step(dt: Float) {
        val linearDecay = exp(-LINEAR_DAMPING * dt)
        val angularDecay = exp(-ANGULAR_DAMPING * dt)

        for (b in bodies) {
            if (b.grabbed) continue
            b.vx *= linearDecay
            b.vy *= linearDecay
            keepDrifting(b, dt)
            clampSpeed(b)
            b.x += b.vx * dt
            b.y += b.vy * dt
            b.omega *= angularDecay
            b.angle += b.omega * dt
        }

        for (f in fingers.values) {
            for (b in bodies) if (!b.grabbed) pushByFinger(b, f)
        }

        // O(n²) is fine for ~15 bodies (105 pairs). Use a spatial grid beyond ~100.
        for (i in bodies.indices) {
            for (j in i + 1 until bodies.size) collide(bodies[i], bodies[j])
        }

        // Walls last, so every body ends the step inside the screen.
        for (b in bodies) collideWithWalls(b)
    }

    /** Below [driftSpeed], gently accelerate so emojis never come to a dead stop. */
    private fun keepDrifting(b: Body, dt: Float) {
        val speed = hypot(b.vx, b.vy)
        if (speed >= driftSpeed) return
        val dx: Float
        val dy: Float
        if (speed > 1e-3f) {
            dx = b.vx / speed
            dy = b.vy / speed
        } else {
            val a = random.nextFloat() * TWO_PI
            dx = cos(a)
            dy = sin(a)
        }
        val boost = (driftSpeed - speed) * DRIFT_RECOVERY * dt
        b.vx += dx * boost
        b.vy += dy * boost
    }

    /** A moving finger is an infinite-mass circle: discs bounce off it. */
    private fun pushByFinger(b: Body, f: Finger) {
        val dx = b.x - f.x
        val dy = b.y - f.y
        val minDist = b.radius + fingerRadius
        val d2 = dx * dx + dy * dy
        if (d2 >= minDist * minDist || d2 < EPSILON) return

        val d = sqrt(d2)
        val nx = dx / d
        val ny = dy / d
        val penetration = minDist - d
        b.x += nx * penetration
        b.y += ny * penetration

        val vn = (b.vx - f.vx) * nx + (b.vy - f.vy) * ny
        if (vn < 0f) {
            val j = -(1f + FINGER_RESTITUTION) * vn
            b.vx += j * nx
            b.vy += j * ny
        }
    }

    /** Impulse-based collision between two discs along the line of centres. */
    private fun collide(a: Body, b: Body) {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val minDist = a.radius + b.radius
        val d2 = dx * dx + dy * dy
        if (d2 >= minDist * minDist || d2 < EPSILON) return

        val invSum = a.invMass + b.invMass
        if (invSum == 0f) return // two held discs

        val d = sqrt(d2)
        val nx = dx / d
        val ny = dy / d

        // Positional correction: separate them, the lighter one moving more.
        val correction = (minDist - d) / invSum
        a.x -= nx * correction * a.invMass
        a.y -= ny * correction * a.invMass
        b.x += nx * correction * b.invMass
        b.y += ny * correction * b.invMass

        // Velocity: only resolve if they're approaching.
        val vn = (b.vx - a.vx) * nx + (b.vy - a.vy) * ny
        if (vn >= 0f) return
        val j = -(1f + DISC_RESTITUTION) * vn / invSum
        a.vx -= j * nx * a.invMass
        a.vy -= j * ny * a.invMass
        b.vx += j * nx * b.invMass
        b.vy += j * ny * b.invMass
    }

    private fun collideWithWalls(b: Body) {
        val r = b.radius
        if (b.x < r) {
            b.x = r; bounce(b, 1f, 0f)
        } else if (b.x > width - r) {
            b.x = width - r; bounce(b, -1f, 0f)
        }
        if (b.y < r) {
            b.y = r; bounce(b, 0f, 1f)
        } else if (b.y > height - r) {
            b.y = height - r; bounce(b, 0f, -1f)
        }
    }

    /**
     * Bounce off a wall with inward normal (nx, ny).
     * Normal impulse reverses the approach velocity (scaled by restitution).
     * Coulomb friction at the contact point reduces sliding and spins the disc
     * (solid disc: I = ½·m·r²).
     */
    private fun bounce(b: Body, nx: Float, ny: Float) {
        if (b.grabbed) return // held discs are just clamped
        val vn = b.vx * nx + b.vy * ny
        if (vn >= 0f) return

        val m = b.mass
        val jn = -(1f + WALL_RESTITUTION) * vn * m
        b.vx += jn / m * nx
        b.vy += jn / m * ny

        // Contact point is at -n·r from the centre. Its velocity = v + ω × r_c.
        val r = b.radius
        val tx = -ny
        val ty = nx
        val contactVx = b.vx + b.omega * ny * r
        val contactVy = b.vy - b.omega * nx * r
        val slip = contactVx * tx + contactVy * ty

        // Effective tangential mass for a solid disc: 1/(1/m + r²/I) = m/3.
        val maxFriction = WALL_FRICTION * jn
        val jt = (-slip * m / 3f).coerceIn(-maxFriction, maxFriction)
        b.vx += jt / m * tx
        b.vy += jt / m * ty
        b.omega += -2f * jt / (m * r)
    }

    private fun clampSpeed(b: Body) {
        val speed = hypot(b.vx, b.vy)
        if (speed > maxSpeed) {
            val s = maxSpeed / speed
            b.vx *= s
            b.vy *= s
        }
    }

    private fun clampToBounds(b: Body) {
        b.x = clampAxis(b.x, b.radius, width)
        b.y = clampAxis(b.y, b.radius, height)
    }

    private fun clampAxis(v: Float, r: Float, size: Float): Float =
        if (size <= 2 * r) size / 2 else v.coerceIn(r, size - r)

    private fun spawn() {
        radii.forEachIndexed { glyph, r ->
            var x = width / 2
            var y = height / 2
            for (attempt in 0 until SPAWN_ATTEMPTS) {
                x = random.between(r, width - r)
                y = random.between(r, height - r)
                if (bodies.none { hypot(it.x - x, it.y - y) < it.radius + r }) break
            }
            val heading = random.nextFloat() * TWO_PI
            val speed = driftSpeed * (1.5f + random.nextFloat() * 2f)
            bodies += Body(glyph, r, x, y, cos(heading) * speed, sin(heading) * speed).apply {
                omega = (random.nextFloat() - 0.5f) * 2f
            }
        }
    }

    private fun Random.between(min: Float, max: Float): Float =
        if (max <= min) (min + max) / 2 else min + nextFloat() * (max - min)

    companion object {
        /** 240 Hz: at max speed a disc moves ~15 dp per step, far less than any radius → no tunnelling. */
        const val STEP = 1f / 240f
        private const val MAX_FRAME = 1f / 20f

        private const val LINEAR_DAMPING = 0.35f  // 1/s: a hard fling coasts ~3 s
        private const val ANGULAR_DAMPING = 0.8f  // 1/s
        private const val DRIFT_RECOVERY = 1.5f   // 1/s

        private const val WALL_RESTITUTION = 0.8f
        private const val DISC_RESTITUTION = 0.9f
        private const val FINGER_RESTITUTION = 0.5f
        private const val WALL_FRICTION = 0.3f

        private const val GRAB_SLOP = 1.15f       // toddler fingers are imprecise
        private const val SPAWN_ATTEMPTS = 200
        private const val EPSILON = 1e-4f
        private const val TWO_PI = (2 * PI).toFloat()
    }
}
