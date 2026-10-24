package com.echoradio.app

import android.graphics.Bitmap
import android.graphics.Color

/** Theme colours taken from an album cover. */
data class AlbumTheme(val card: Int, val cardEnd: Int, val page: Int)

object AlbumColors {
    /** The default blue look, used before anything plays and for black/white/grey covers. */
    val DEFAULT = AlbumTheme(Color.parseColor("#1238B8"), Color.parseColor("#3FC5F0"), Color.parseColor("#0B1E5B"))

    /** Finds the cover's strongest colour and turns it into readable card and background colours. */
    fun extract(cover: Bitmap): AlbumTheme {
        val small = Bitmap.createScaledBitmap(cover, 32, 32, true)
        val weight = DoubleArray(12)
        val hueX = DoubleArray(12); val hueY = DoubleArray(12); val sat = DoubleArray(12)
        val hsv = FloatArray(3)
        for (y in 0 until 32) for (x in 0 until 32) {
            Color.colorToHSV(small.getPixel(x, y), hsv)
            if (hsv[2] < 0.18f || hsv[1] < 0.22f) continue          // skip near-black, near-white and greys
            val w = (hsv[1] * hsv[2]).toDouble()
            val b = ((hsv[0] / 30f).toInt()).coerceIn(0, 11)
            val rad = Math.toRadians(hsv[0].toDouble())
            weight[b] += w; hueX[b] += Math.cos(rad) * w; hueY[b] += Math.sin(rad) * w; sat[b] += hsv[1] * w
        }
        if (small !== cover) small.recycle()
        val best = weight.indices.maxByOrNull { weight[it] } ?: return DEFAULT
        if (weight[best] < 8.0) return DEFAULT                           // hardly any colour in this cover
        var hue = Math.toDegrees(Math.atan2(hueY[best], hueX[best])).toFloat()
        if (hue < 0) hue += 360f
        val s = (sat[best] / weight[best]).toFloat().coerceIn(0.45f, 0.85f)
        return AlbumTheme(
            card = Color.HSVToColor(floatArrayOf(hue, s, 0.52f)),                       // rich, white text stays readable
            cardEnd = Color.HSVToColor(floatArrayOf((hue + 18f) % 360f, s * 0.85f, 0.68f)),
            page = Color.HSVToColor(floatArrayOf(hue, (s * 0.9f).coerceAtMost(0.8f), 0.26f)) // deep background tint
        )
    }
}
