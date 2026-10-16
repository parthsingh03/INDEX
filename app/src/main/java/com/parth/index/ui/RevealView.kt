package com.parth.index.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.View

/** The INDEX loading screen (v1.5): the integrated Cursor reveal, drawn
 *  natively on Canvas — pure vector geometry, so it is pixel-sharp on
 *  every screen and costs the APK nothing (no video, no image assets).
 *
 *  The choreography is the one Parth directed:
 *   1. the Cursor builds from its three pieces, CLOSE-UP, on white;
 *   2. it pulls back and shrinks while the background turns black,
 *      landing exactly where the "I" of INDEX belongs;
 *   3. N-D-E-X build in beside it, left to right, soft fade + slide;
 *   4. the finished lockup holds — this view IS the loading screen, so
 *      it stays up until the member gate behind it has an answer.
 *
 *  Letterforms are the app's own constructed wordmark paths (the same
 *  coordinates as the brand SVGs), so splash, icon and wordmark are
 *  one identity. Total motion ~2.35 s; [onFinished] fires once when
 *  the motion completes (routing waits for gate AND this, whichever
 *  is slower). */
class RevealView(context: Context) : View(context) {

    /** Called once when the reveal motion has fully played. */
    var onFinished: (() -> Unit)? = null

    private var startMs = 0L
    private var finished = false
    private var halted = false

    private val bgPaint = Paint().apply { style = Paint.Style.FILL }
    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = BLUE
    }
    private val letterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = CREAM
    }
    private val letterBluePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = BLUE
    }

    // ---- Wordmark geometry (the brand's 640x240 letter space) --------
    private data class Letter(val ink: Path, val blue: Path?)

    private val letters: List<Letter> by lazy { buildLetters() }

    private fun buildLetters(): List<Letter> {
        fun poly(vararg pts: Float): Path {
            val p = Path()
            p.moveTo(pts[0], pts[1])
            var i = 2
            while (i < pts.size) {
                p.lineTo(pts[i], pts[i + 1])
                i += 2
            }
            p.close()
            return p
        }
        fun rects(vararg rs: Float): Path {
            val p = Path()
            var i = 0
            while (i < rs.size) {
                p.addRect(rs[i], rs[i + 1], rs[i + 2], rs[i + 3], Path.Direction.CW)
                i += 4
            }
            return p
        }
        // N — stems at stem weight, the slim diagonal (28) Parth approved.
        val n = Path()
        n.addRect(102f, 60f, 128f, 180f, Path.Direction.CW)
        n.addRect(194f, 60f, 220f, 180f, Path.Direction.CW)
        val nDiag = poly(128f, 60f, 156f, 60f, 220f, 180f, 192f, 180f)
        n.addPath(nDiag)
        // D — bowl with its counter punched by even-odd fill.
        val d = Path().apply {
            fillType = Path.FillType.EVEN_ODD
            moveTo(246f, 60f)
            lineTo(284f, 60f)
            cubicTo(330f, 60f, 352f, 87f, 352f, 120f)
            cubicTo(352f, 153f, 330f, 180f, 284f, 180f)
            lineTo(246f, 180f)
            close()
            moveTo(272f, 86f)
            lineTo(286f, 86f)
            cubicTo(311f, 86f, 324f, 101f, 324f, 120f)
            cubicTo(324f, 139f, 311f, 154f, 286f, 154f)
            lineTo(272f, 154f)
            close()
        }
        // E — stem + three bars.
        val e = rects(
            376f, 60f, 402f, 180f,
            376f, 60f, 468f, 86f,
            376f, 107f, 450f, 133f,
            376f, 154f, 468f, 180f
        )
        // X — falling bar in ink, rising bar in brand blue.
        val xInk = poly(494f, 60f, 526f, 60f, 598f, 180f, 566f, 180f)
        val xBlue = poly(566f, 60f, 598f, 60f, 526f, 180f, 494f, 180f)
        return listOf(Letter(n, null), Letter(d, null), Letter(e, null), Letter(xInk, xBlue))
    }

    fun start() {
        startMs = SystemClock.uptimeMillis()
        finished = false
        halted = false
        postInvalidateOnAnimation()
    }

    /** Stop the animation loop without firing [onFinished] (the
     *  activity is going away). */
    fun halt() {
        halted = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return
        val t = if (startMs == 0L) 0L else SystemClock.uptimeMillis() - startMs
        val w = width.toFloat()
        val h = height.toFloat()
        val u = w / 420f // design unit of the 420x740 choreography

        // Phase 2 (pull-back) drives the background white -> black.
        val pull = easeInOut((t - PULL_START) / PULL_MS.toFloat())
        bgPaint.color = blend(WHITE, BLACK, pull)
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        // Wordmark metrics: letters occupy the middle 341 of 420 units.
        // The Cursor's bars are wider than a normal letter I, so in the
        // lockup the letters shift a breath right (+18 units) and the
        // Cursor a touch left (-6), re-centred by +11.5 — otherwise the
        // bar tips would kiss the N's stem (caught in the 1080p QA pass).
        val k = (w * 0.812f) / 640f
        val wmCx = w / 2f
        val wmCy = h * 0.473f
        val cursorFinalX = wmCx + (59f - 6f + 11.5f - 320f) * k
        val cursorFinalScale = 0.4f * k
        val buildCx = w / 2f
        val buildCy = h * 0.446f
        val buildScale = w * (305f / 420f) / 260f

        val cursorCx = lerp(buildCx, cursorFinalX, pull)
        val cursorCy = lerp(buildCy, wmCy, pull)
        val cursorScale = lerp(buildScale, cursorFinalScale, pull)
        val rotation = lerp(-20f, -8f, easeOut(t / 740f))

        val topP = clamp01(t / 500f)
        val stemP = clamp01((t - 120f) / 500f)
        val botP = clamp01((t - 240f) / 500f)
        drawCursor(canvas, cursorCx, cursorCy, cursorScale, rotation, topP, stemP, botP, u)

        // Phase 3: N-D-E-X build in beside the icon I.
        val scratch = Path()
        val matrix = Matrix()
        letters.forEachIndexed { i, letter ->
            val lp = easeOut((t - (TEXT_START + i * 110f)) / 380f)
            if (lp <= 0f) return@forEachIndexed
            val dx = (1f - lp) * 14f * u
            matrix.reset()
            matrix.setScale(k, k)
            matrix.postTranslate(
                wmCx + (18f + 11.5f - 320f) * k + dx,
                wmCy - 120f * k
            )
            letterPaint.alpha = (lp * 255).toInt()
            scratch.reset()
            letter.ink.transform(matrix, scratch)
            canvas.drawPath(scratch, letterPaint)
            letter.blue?.let { bluePath ->
                letterBluePaint.alpha = (lp * 255).toInt()
                scratch.reset()
                bluePath.transform(matrix, scratch)
                canvas.drawPath(scratch, letterBluePaint)
            }
        }

        if (halted) return
        if (t < TOTAL_MS) {
            postInvalidateOnAnimation()
        } else if (!finished) {
            finished = true
            onFinished?.invoke()
        }
    }

    private fun drawCursor(
        canvas: Canvas, cx: Float, cy: Float, scale: Float, rotation: Float,
        topP: Float, stemP: Float, botP: Float, u: Float
    ) {
        canvas.save()
        canvas.translate(cx, cy)
        canvas.rotate(rotation)
        canvas.scale(scale, scale)
        val dyTop = (1f - easeOut(topP)) * -70f
        val dyBot = (1f - easeOut(botP)) * 70f
        // Top bar.
        canvas.drawRoundRect(
            RectF(-130f, -150f + dyTop, 130f, -98f + dyTop), 14f, 14f, cursorPaint
        )
        // Stem grows downward from under the top bar.
        val stemH = 196f * easeOut(stemP)
        if (stemH > 0f) {
            canvas.drawRect(RectF(-26f, -98f, 26f, -98f + stemH), cursorPaint)
        }
        // Bottom bar.
        canvas.drawRoundRect(
            RectF(-130f, 98f + dyBot, 130f, 150f + dyBot), 14f, 14f, cursorPaint
        )
        canvas.restore()
    }

    private fun clamp01(x: Float): Float = x.coerceIn(0f, 1f)
    private fun easeOut(x: Float): Float {
        val t = clamp01(x)
        return 1f - (1f - t) * (1f - t) * (1f - t)
    }
    private fun easeInOut(x: Float): Float {
        val t = clamp01(x)
        return t * t * (3f - 2f * t)
    }
    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
    private fun blend(c1: Int, c2: Int, t: Float): Int {
        val r = (Color.red(c1) + (Color.red(c2) - Color.red(c1)) * t).toInt()
        val g = (Color.green(c1) + (Color.green(c2) - Color.green(c1)) * t).toInt()
        val b = (Color.blue(c1) + (Color.blue(c2) - Color.blue(c1)) * t).toInt()
        return Color.rgb(r, g, b)
    }

    companion object {
        private val BLUE = Color.rgb(0x25, 0x63, 0xEB)
        private val CREAM = Color.rgb(0xE8, 0xEC, 0xFF)
        private val WHITE = Color.WHITE
        private val BLACK = Color.BLACK
        private const val PULL_START = 900f
        private const val PULL_MS = 550f
        private const val TEXT_START = 1500f
        private const val TOTAL_MS = 2350L
    }
}
