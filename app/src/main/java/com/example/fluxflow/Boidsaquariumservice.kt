package com.example.fluxflow

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.service.wallpaper.WallpaperService
import android.view.Choreographer
import android.view.MotionEvent
import android.view.SurfaceHolder
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

class BoidsAquariumService : WallpaperService() {

    override fun onCreateEngine(): Engine = AquariumEngine()

    inner class AquariumEngine : Engine(), SensorEventListener, Choreographer.FrameCallback {

        private val PI_F = Math.PI.toFloat()
        private val density = resources.displayMetrics.density
        private val choreographer: Choreographer by lazy { Choreographer.getInstance() }
        private val sensorManager: SensorManager by lazy {
            getSystemService(Context.SENSOR_SERVICE) as SensorManager
        }

        // ---------- Zustand ----------
        private var w = 0
        private var h = 0
        private var cx = 0f
        private var cy = 0f
        private var visible = false
        private var lastNanos = 0L
        private var time = 0f
        private var boidsReady = false

        // Wasser (Winkel der Schwerkraft, gedämpfte Feder -> Schwappen)
        private var angle = 0f
        private var angVel = 0f
        private var targetAngle = 0f
        private var waveAmp = 0f

        // Finger
        private var touching = false
        private var touchX = 0f
        private var touchY = 0f

        // ---------- Boids ----------
        private val count = 42
        private val px = FloatArray(count)
        private val py = FloatArray(count)
        private val vx = FloatArray(count)
        private val vy = FloatArray(count)
        private val br = FloatArray(count)
        private val bc = IntArray(count)

        private val glassColors = intArrayOf(
            0xFFFFD60A.toInt(), // Gelb
            0xFF1E88FF.toInt(), // Blau
            0xFF8E44FF.toInt(), // Violett
            0xFFFF8C1A.toInt(), // Orange
            0xFF00D1D1.toInt(), // Türkis
            0xFFFF3B30.toInt(), // Rot
            0xFFE8F7FF.toInt()  // Klarglas
        )
        private val spheres = ArrayList<Bitmap>()

        // ---------- Zeichnen ----------
        private var background: Bitmap? = null
        private val waterPath = Path()
        private val surfacePath = Path()
        private val waterPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val veilPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x302A8FBF }
        private val spritePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val surfaceGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = 0x33FFFFFF
            strokeWidth = 9f * density
            strokeJoin = Paint.Join.ROUND
        }
        private val surfaceLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = 0xAAFFFFFF.toInt()
            strokeWidth = 2f * density
            strokeJoin = Paint.Join.ROUND
        }
        private val glassEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = 0x33FFFFFF
            strokeWidth = 6f * density
        }
        private val glassShine = Paint(Paint.ANTI_ALIAS_FLAG)
        private val tmpRect = RectF()

        // =====================================================
        // Lebenszyklus
        // =====================================================
        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            setTouchEventsEnabled(true)
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            val sizeChanged = (width != w || height != h)
            w = width
            h = height
            cx = w / 2f
            cy = h / 2f

            background = createBackground(w, h)
            glassShine.shader = LinearGradient(
                0f, 0f, 40f * density, 0f,
                0x40FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP
            )

            if (spheres.isEmpty()) {
                for (c in glassColors) spheres.add(createSphere(c))
            }
            if (!boidsReady || sizeChanged) initBoids()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            this.visible = visible
            if (visible) {
                sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
                    sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
                }
                lastNanos = 0L
                choreographer.postFrameCallback(this)
            } else {
                stopLoop()
            }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            super.onSurfaceDestroyed(holder)
            visible = false
            stopLoop()
        }

        override fun onDestroy() {
            super.onDestroy()
            stopLoop()
        }

        private fun stopLoop() {
            choreographer.removeFrameCallback(this)
            sensorManager.unregisterListener(this)
        }

        // =====================================================
        // Eingaben
        // =====================================================
        override fun onSensorChanged(e: SensorEvent) {
            // Schwerkraft in Bildschirmkoordinaten (x rechts, y unten)
            val gx = -e.values[0]
            val gy = e.values[1]
            // Liegt das Handy flach, bleibt der alte Winkel erhalten
            if (hypot(gx, gy) > 2.0f) targetAngle = atan2(gx, gy)
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

        override fun onTouchEvent(event: MotionEvent) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    touching = true
                    touchX = event.x
                    touchY = event.y
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> touching = false
            }
        }

        // =====================================================
        // Hauptschleife
        // =====================================================
        override fun doFrame(frameTimeNanos: Long) {
            if (!visible) return
            val dt = if (lastNanos == 0L) 0.016f
            else min(0.05f, (frameTimeNanos - lastNanos) / 1_000_000_000f)
            lastNanos = frameTimeNanos
            time += dt

            if (w > 0 && h > 0 && boidsReady) {
                update(dt)
                var canvas: Canvas? = null
                try {
                    canvas = surfaceHolder.lockHardwareCanvas()
                    if (canvas != null) drawFrame(canvas)
                } catch (_: Exception) {
                } finally {
                    if (canvas != null) {
                        try {
                            surfaceHolder.unlockCanvasAndPost(canvas)
                        } catch (_: Exception) {
                        }
                    }
                }
            }
            if (visible) choreographer.postFrameCallback(this)
        }

        // =====================================================
        // Simulation
        // =====================================================
        private fun initBoids() {
            val rnd = Random(7)
            for (i in 0 until count) {
                br[i] = (11f + rnd.nextFloat() * 9f) * density
                bc[i] = rnd.nextInt(glassColors.size)
                px[i] = br[i] + rnd.nextFloat() * (w - 2 * br[i])
                py[i] = cy + br[i] + rnd.nextFloat() * (h / 2f - 2 * br[i])
                val a = rnd.nextFloat() * 2f * PI_F
                val s = 60f * density
                vx[i] = cos(a) * s
                vy[i] = sin(a) * s
            }
            angle = 0f
            angVel = 0f
            targetAngle = 0f
            boidsReady = true
        }

        private fun wrapAngle(a: Float): Float {
            var r = a
            while (r > PI_F) r -= 2f * PI_F
            while (r < -PI_F) r += 2f * PI_F
            return r
        }

        /** Auslenkung der Wasseroberfläche entlang der Normalen an Tangentenposition s. */
        private fun wave(s: Float): Float {
            return waveAmp * sin(s * 0.018f + time * 1.8f) +
                    waveAmp * 0.5f * sin(s * 0.041f - time * 2.4f)
        }

        private fun update(dt: Float) {
            // Wasserwinkel: gedämpfte Feder (unterdämpft -> Schwappen)
            val diff = wrapAngle(targetAngle - angle)
            angVel += (diff * 18f - angVel * 2.2f) * dt
            angle += angVel * dt
            waveAmp = (4f * density) + min(12f * density, abs(angVel) * 7f * density)

            val nx = -sin(angle)
            val ny = -cos(angle)      // Normale zeigt aus dem Wasser heraus
            val gx = -nx
            val gy = -ny              // Schwerkraftrichtung
            val tx = cos(angle)
            val ty = -sin(angle)

            val view = 90f * density
            val view2 = view * view
            val minSpeed = 35f * density
            val maxSpeed = 95f * density
            val margin = 50f * density
            val touchRange = 260f * density

            for (i in 0 until count) {
                var sepX = 0f
                var sepY = 0f
                var aliX = 0f
                var aliY = 0f
                var cohX = 0f
                var cohY = 0f
                var n = 0

                for (j in 0 until count) {
                    if (j == i) continue
                    val dx = px[j] - px[i]
                    val dy = py[j] - py[i]
                    val d2 = dx * dx + dy * dy
                    if (d2 < view2) {
                        n++
                        aliX += vx[j]
                        aliY += vy[j]
                        cohX += px[j]
                        cohY += py[j]
                        val sepDist = (br[i] + br[j]) * 1.5f
                        val d = Math.sqrt(d2.toDouble()).toFloat() + 0.001f
                        if (d < sepDist) {
                            val k = (sepDist - d) / sepDist
                            sepX -= dx / d * k
                            sepY -= dy / d * k
                        }
                    }
                }

                var ax = sepX * 700f * density
                var ay = sepY * 700f * density
                if (n > 0) {
                    ax += (aliX / n - vx[i]) * 2.0f
                    ay += (aliY / n - vy[i]) * 2.0f
                    ax += (cohX / n - px[i]) * 0.8f
                    ay += (cohY / n - py[i]) * 0.8f
                }

                // Zufallsrauschen
                ax += (Random.nextFloat() - 0.5f) * 80f * density
                ay += (Random.nextFloat() - 0.5f) * 80f * density

                // Finger lockt an
                if (touching) {
                    val dx = touchX - px[i]
                    val dy = touchY - py[i]
                    val d = hypot(dx, dy)
                    if (d in 1f..touchRange) {
                        val k = (1f - d / touchRange) * 240f * density
                        ax += dx / d * k
                        ay += dy / d * k
                    }
                }

                // Weiche Ränder
                if (px[i] < margin) ax += (margin - px[i]) * 8f
                if (px[i] > w - margin) ax -= (px[i] - (w - margin)) * 8f
                if (py[i] > h - margin) ay -= (py[i] - (h - margin)) * 8f
                if (py[i] < margin) ay += (margin - py[i]) * 8f

                // Wasseroberfläche: Kugeln bleiben unter Wasser
                val s = (px[i] - cx) * tx + (py[i] - cy) * ty
                val height = (px[i] - cx) * nx + (py[i] - cy) * ny - wave(s)
                val limit = -br[i] * 1.1f
                if (height > limit) {
                    val pen = height - limit
                    ax += gx * (pen * 14f + 200f * density)
                    ay += gy * (pen * 14f + 200f * density)
                }

                // Integration
                vx[i] += ax * dt
                vy[i] += ay * dt

                // Geschwindigkeitsgrenzen
                var sp = hypot(vx[i], vy[i])
                if (sp > maxSpeed) {
                    vx[i] *= maxSpeed / sp
                    vy[i] *= maxSpeed / sp
                } else if (sp < minSpeed) {
                    if (sp < 0.001f) {
                        val a = Random.nextFloat() * 2f * PI_F
                        vx[i] = cos(a) * minSpeed
                        vy[i] = sin(a) * minSpeed
                    } else {
                        vx[i] *= minSpeed / sp
                        vy[i] *= minSpeed / sp
                    }
                }

                px[i] += vx[i] * dt
                py[i] += vy[i] * dt

                // Harte Grenze an der Wasseroberfläche
                val s2 = (px[i] - cx) * tx + (py[i] - cy) * ty
                val h2 = (px[i] - cx) * nx + (py[i] - cy) * ny - wave(s2)
                if (h2 > -br[i]) {
                    val push = h2 + br[i]
                    px[i] -= nx * push
                    py[i] -= ny * push
                    val vn = vx[i] * nx + vy[i] * ny
                    if (vn > 0f) {
                        vx[i] -= nx * vn
                        vy[i] -= ny * vn
                    }
                }

                // Harte Grenze am Displayrand
                val r = br[i]
                if (px[i] < r) { px[i] = r; vx[i] = abs(vx[i]) }
                if (px[i] > w - r) { px[i] = w - r; vx[i] = -abs(vx[i]) }
                if (py[i] < r) { py[i] = r; vy[i] = abs(vy[i]) }
                if (py[i] > h - r) { py[i] = h - r; vy[i] = -abs(vy[i]) }
            }
        }

        // =====================================================
        // Zeichnen
        // =====================================================
        private fun buildWater() {
            val nx = -sin(angle)
            val ny = -cos(angle)
            val tx = cos(angle)
            val ty = -sin(angle)
            val big = hypot(w.toFloat(), h.toFloat())
            val steps = 120

            waterPath.rewind()
            surfacePath.rewind()
            for (i in 0..steps) {
                val s = -big + 2f * big * i / steps
                val o = wave(s)
                val x = cx + tx * s + nx * o
                val y = cy + ty * s + ny * o
                if (i == 0) {
                    waterPath.moveTo(x, y)
                    surfacePath.moveTo(x, y)
                } else {
                    waterPath.lineTo(x, y)
                    surfacePath.lineTo(x, y)
                }
            }
            waterPath.lineTo(cx + tx * big - nx * big * 2f, cy + ty * big - ny * big * 2f)
            waterPath.lineTo(cx - tx * big - nx * big * 2f, cy - ty * big - ny * big * 2f)
            waterPath.close()
        }

        private fun drawFrame(c: Canvas) {
            // Untergrund
            val bg = background
            if (bg != null) c.drawBitmap(bg, 0f, 0f, null) else c.drawColor(0xFF3F9E6B.toInt())

            // Wasser mit Tiefenverlauf
            buildWater()
            val nx = -sin(angle)
            val ny = -cos(angle)
            val depth = hypot(w.toFloat(), h.toFloat()) * 0.5f
            waterPaint.shader = LinearGradient(
                cx, cy, cx - nx * depth, cy - ny * depth,
                intArrayOf(0x6640B4D8, 0xB00A4A70.toInt()),
                null, Shader.TileMode.CLAMP
            )
            c.drawPath(waterPath, waterPaint)

            // Glaskugeln
            for (i in 0 until count) {
                val r = br[i]
                tmpRect.set(px[i] - r, py[i] - r, px[i] + r, py[i] + r)
                c.drawBitmap(spheres[bc[i]], null, tmpRect, spritePaint)
            }

            // Leichter Wasserschleier über den Kugeln (wirkt "untergetaucht")
            c.drawPath(waterPath, veilPaint)

            // Oberflächenlinie
            c.drawPath(surfacePath, surfaceGlow)
            c.drawPath(surfacePath, surfaceLine)

            // Glasrand des Gefäßes
            c.drawRect(3f * density, 3f * density, w - 3f * density, h - 3f * density, glassEdge)
            c.drawRect(0f, 0f, 40f * density, h.toFloat(), glassShine)
        }

        // =====================================================
        // Grafik-Erzeugung
        // =====================================================
        private fun smoothstep(e0: Float, e1: Float, x: Float): Float {
            val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }

        private fun lerpColor(a: Int, b: Int, t: Float): Int {
            val r = Color.red(a) + ((Color.red(b) - Color.red(a)) * t).toInt()
            val g = Color.green(a) + ((Color.green(b) - Color.green(a)) * t).toInt()
            val bl = Color.blue(a) + ((Color.blue(b) - Color.blue(a)) * t).toInt()
            return Color.rgb(r, g, bl)
        }

        /** Marmoriertes grün-rosa Muster, in 1/4 Auflösung berechnet und hochskaliert. */
        private fun createBackground(width: Int, height: Int): Bitmap {
            val sw = max(1, width / 4)
            val sh = max(1, height / 4)
            val pixels = IntArray(sw * sh)
            val green = 0xFF3F9E6B.toInt()
            val pink = 0xFFE58AAE.toInt()

            for (y in 0 until sh) {
                val v = y / sh.toFloat() * 13f
                for (x in 0 until sw) {
                    val u = x / sw.toFloat() * 7f
                    val value = sin(u * 1.3f + sin(v * 0.9f)) +
                            sin(v * 1.1f + sin(u * 1.7f)) +
                            sin((u + v) * 0.8f) +
                            sin(hypot(u - 3.5f, v - 6f) * 1.6f)
                    val t = 0.5f + 0.5f * sin(value * 1.4f)
                    var col = lerpColor(green, pink, smoothstep(0.42f, 0.58f, t))
                    // Dunkle Konturlinien zwischen den Farbflächen
                    val edge = 1f - min(1f, abs(t - 0.5f) * 14f)
                    col = lerpColor(col, 0xFF1F4D3A.toInt(), edge * 0.35f)
                    pixels[y * sw + x] = col
                }
            }
            val small = Bitmap.createBitmap(pixels, sw, sh, Bitmap.Config.ARGB_8888)
            return Bitmap.createScaledBitmap(small, width, height, true)
        }

        private fun withAlpha(color: Int, a: Int): Int = (color and 0x00FFFFFF) or (a shl 24)

        /** Glaskugel: transparenter Körper, kräftiger Rand, Glanzlicht und Lichtsaum unten. */
        private fun createSphere(color: Int): Bitmap {
            val size = (48f * density).toInt()
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val r = size / 2f
            val p = Paint(Paint.ANTI_ALIAS_FLAG)

            // Körper
            p.shader = RadialGradient(
                r, r, r,
                intArrayOf(withAlpha(color, 60), withAlpha(color, 120), withAlpha(color, 235)),
                floatArrayOf(0f, 0.7f, 1f),
                Shader.TileMode.CLAMP
            )
            c.drawCircle(r, r, r - 1f, p)

            // Lichtsaum unten rechts (Brechung)
            val clip = Path().apply { addCircle(r, r, r - 1f, Path.Direction.CW) }
            c.save()
            c.clipPath(clip)
            p.shader = RadialGradient(
                r * 1.3f, r * 1.35f, r * 0.7f,
                intArrayOf(0x70FFFFFF, 0x00FFFFFF),
                null,
                Shader.TileMode.CLAMP
            )
            c.drawCircle(r * 1.3f, r * 1.35f, r * 0.7f, p)
            c.restore()

            // Rand
            p.shader = null
            p.style = Paint.Style.STROKE
            p.strokeWidth = 1.5f * density
            p.color = 0x99FFFFFF.toInt()
            c.drawCircle(r, r, r - 1.5f * density, p)

            // Glanzlicht oben links
            p.style = Paint.Style.FILL
            p.color = 0xE6FFFFFF.toInt()
            c.drawOval(RectF(r * 0.30f, r * 0.20f, r * 0.80f, r * 0.50f), p)
            p.color = 0xB3FFFFFF.toInt()
            c.drawCircle(r * 0.35f, r * 0.62f, r * 0.06f, p)

            return bmp
        }
    }
}