package com.echoradio.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The "DJ is talking" visual shown in place of the album cover: a glowing DJ orb with a ring
 * of sound bars that move with the loudness of the DJ's actual voice.
 */
class DjTalkView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    /** Returns the current voice loudness, 0..1. */
    var levelProvider: () -> Float = { 0f }

    private var running = false
    private var level = 0f            // smoothed loudness
    private var t0 = System.nanoTime()
    private val bars = 56
    private val seeds = FloatArray(bars) { (it * 37 % 11) / 11f }

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val orb = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; color = Color.WHITE }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); textAlign = Paint.Align.CENTER
    }

    fun start() {
        if (running) return
        running = true
        t0 = System.nanoTime()
        postInvalidateOnAnimation()
    }

    fun stop() { running = false }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        bg.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(),
            intArrayOf(Color.parseColor("#0B1E5B"), Color.parseColor("#1238B8"), Color.parseColor("#2B5BE0")), null, Shader.TileMode.CLAMP)
        val r = min(w, h) * 0.5f
        glow.shader = RadialGradient(w / 2f, h / 2f, r, intArrayOf(Color.parseColor("#803FC5F0"), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
        orb.shader = LinearGradient(w / 2f - r, h / 2f - r, w / 2f + r, h / 2f + r,
            Color.parseColor("#3D7BFF"), Color.parseColor("#3FC5F0"), Shader.TileMode.CLAMP)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val cx = w / 2f; val cy = h / 2f
        val time = (System.nanoTime() - t0) / 1e9f
        // Ease toward the real loudness; idle "breathing" while the voice is loading
        val target = levelProvider().coerceIn(0f, 1f)
        level += (target - level) * (if (target > level) 0.45f else 0.12f)
        val breath = 0.06f + 0.04f * sin(time * 2.2f)
        val e = max(level, breath)

        c.drawRect(0f, 0f, w, h, bg)

        val base = min(w, h) * 0.17f
        val orbR = base * (1f + e * 0.18f)

        // Soft glow and expanding rings
        c.save(); c.scale(0.8f + e * 0.5f, 0.8f + e * 0.5f, cx, cy); c.drawCircle(cx, cy, min(w, h) * 0.5f, glow); c.restore()
        for (i in 0 until 3) {
            val p = ((time * 0.45f + i / 3f) % 1f)
            ring.strokeWidth = 2f * resources.displayMetrics.density
            ring.alpha = ((1f - p) * 90 * (0.4f + e)).toInt().coerceIn(0, 255)
            c.drawCircle(cx, cy, orbR + p * min(w, h) * 0.32f, ring)
        }

        // Ring of sound bars
        val inner = orbR + base * 0.28f
        bar.strokeWidth = max(3f, (2 * PI.toFloat() * inner / bars) * 0.45f)
        for (i in 0 until bars) {
            val a = (i.toFloat() / bars) * 2f * PI.toFloat() - PI.toFloat() / 2f
            val wobble = 0.5f + 0.5f * sin(time * (5f + seeds[i] * 4f) + i * 0.7f)
            val len = base * (0.12f + e * (0.55f + 0.9f * wobble * (0.6f + seeds[i])))
            bar.alpha = (140 + 115 * e).toInt().coerceAtMost(255)
            c.drawLine(cx + cos(a) * inner, cy + sin(a) * inner, cx + cos(a) * (inner + len), cy + sin(a) * (inner + len), bar)
        }

        // The DJ orb
        c.drawCircle(cx, cy, orbR, orb)
        text.textSize = orbR * 0.62f
        c.drawText("DJ", cx, cy - (text.descent() + text.ascent()) / 2f, text)

        if (running) postInvalidateOnAnimation()
    }
}
