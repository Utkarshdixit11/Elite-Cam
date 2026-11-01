package com.example.elitecam

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.File
import kotlin.math.pow

/**
 * Scene-adaptive post-processing:
 *  1. classify the captured frame
 *  2. gray-world white balance (gentle)
 *  3. levels stretch + gamma + shadow lift / highlight recovery + S-curve (one LUT per channel)
 *  4. saturation
 *  5. sharpen (or light denoise in low light)
 */
object Enhancer {

    private data class Params(
        val gamma: Float,
        val shadows: Float,
        val highlights: Float,
        val contrast: Float,
        val saturation: Float,
        val sharpen: Float,      // >0 sharpen, <0 smooth
        val wbStrength: Float
    )

    private fun paramsFor(scene: SceneType) = when (scene) {
        SceneType.LOW_LIGHT -> Params(0.75f, 0.30f, 0.0f, 0.15f, 1.10f, -0.35f, 0.6f)
        SceneType.BACKLIT -> Params(0.90f, 0.60f, 0.5f, 0.20f, 1.15f, 0.50f, 0.5f)
        SceneType.BRIGHT -> Params(1.05f, 0.00f, 0.4f, 0.25f, 1.20f, 0.60f, 0.5f)
        SceneType.NORMAL -> Params(1.00f, 0.10f, 0.2f, 0.20f, 1.12f, 0.50f, 0.5f)
    }

    /** Decode (capped at ~16 MP to protect memory) and apply EXIF rotation. */
    fun loadOriented(file: File): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while ((bounds.outWidth / sample).toLong() * (bounds.outHeight / sample) > 16_000_000L) sample *= 2
        val bmp = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IllegalStateException("Could not decode image")

        val deg = when (ExifInterface(file.path).getAttributeInt(
            ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
        )) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (deg == 0f) return bmp
        val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(deg) }, true)
        bmp.recycle()
        return rotated
    }

    fun enhance(src: Bitmap): Pair<Bitmap, SceneType> {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        src.recycle()

        // ---- statistics (sampled) ----
        val hist = IntArray(256)
        var sR = 0L; var sG = 0L; var sB = 0L
        var dark = 0; var bright = 0; var n = 0
        var i = 0
        while (i < px.size) {
            val p = px[i]
            val r = (p shr 16) and 255
            val g = (p shr 8) and 255
            val b = p and 255
            val y = (299 * r + 587 * g + 114 * b) / 1000
            hist[y]++
            sR += r; sG += g; sB += b
            if (y < 40) dark++
            if (y > 220) bright++
            n++
            i += 7
        }
        val meanY = (299f * sR + 587f * sG + 114f * sB) / (1000f * n)
        val scene = SceneType.classify(meanY, dark.toFloat() / n, bright.toFloat() / n)
        val p = paramsFor(scene)

        // ---- white balance gains (gray-world, blended and clamped) ----
        val mR = sR.toFloat() / n
        val mG = sG.toFloat() / n
        val mB = sB.toFloat() / n
        val avg = (mR + mG + mB) / 3f
        fun gain(m: Float) = (1f + (avg / m.coerceAtLeast(1f) - 1f) * p.wbStrength).coerceIn(0.85f, 1.18f)
        val gains = floatArrayOf(gain(mR), gain(mG), gain(mB))

        // ---- levels from percentiles ----
        fun percentile(frac: Float): Int {
            val target = (n * frac).toInt()
            var acc = 0
            for (v in 0..255) { acc += hist[v]; if (acc >= target) return v }
            return 255
        }
        val lo = minOf(percentile(0.005f), 50).toFloat()
        val hi = maxOf(percentile(0.995f), 205).toFloat()

        // ---- one LUT per channel ----
        val lut = Array(3) { IntArray(256) }
        for (c in 0..2) {
            for (v in 0..255) {
                var x = ((v * gains[c] - lo) / (hi - lo)).coerceIn(0f, 1f)
                x = x.pow(p.gamma)
                x += p.shadows * 2f * x * (1f - x) * (1f - x)              // lift shadows
                if (p.highlights > 0f && x > 0.7f) {                        // tame highlights
                    val t = (x - 0.7f) / 0.3f
                    x -= p.highlights * 0.08f * t * t
                }
                val s = x * x * (3f - 2f * x)                               // S-curve
                x = x * (1f - p.contrast) + s * p.contrast
                lut[c][v] = (x.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
            }
        }

        // ---- apply LUT + saturation ----
        val lr = lut[0]; val lg = lut[1]; val lb = lut[2]
        val sat = p.saturation
        for (k in px.indices) {
            val q = px[k]
            val r = lr[(q shr 16) and 255]
            val g = lg[(q shr 8) and 255]
            val b = lb[q and 255]
            val l = 0.299f * r + 0.587f * g + 0.114f * b
            val r2 = (l + (r - l) * sat).toInt().coerceIn(0, 255)
            val g2 = (l + (g - l) * sat).toInt().coerceIn(0, 255)
            val b2 = (l + (b - l) * sat).toInt().coerceIn(0, 255)
            px[k] = (0xFF shl 24) or (r2 shl 16) or (g2 shl 8) or b2
        }

        val result = unsharp(px, w, h, p.sharpen)

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(result, 0, w, 0, 0, w, h)
        return out to scene
    }

    /** out = orig + amount * (orig - boxBlur3x3). Negative amount = light smoothing. */
    private fun unsharp(src: IntArray, w: Int, h: Int, amount: Float): IntArray {
        if (amount == 0f) return src
        val out = src.copyOf()
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                var r = 0; var g = 0; var b = 0
                for (dy in -1..1) {
                    val base = (y + dy) * w + x
                    for (dx in -1..1) {
                        val q = src[base + dx]
                        r += (q shr 16) and 255
                        g += (q shr 8) and 255
                        b += q and 255
                    }
                }
                val c = src[y * w + x]
                val cr = (c shr 16) and 255
                val cg = (c shr 8) and 255
                val cb = c and 255
                val nr = (cr + amount * (cr - r / 9f)).toInt().coerceIn(0, 255)
                val ng = (cg + amount * (cg - g / 9f)).toInt().coerceIn(0, 255)
                val nb = (cb + amount * (cb - b / 9f)).toInt().coerceIn(0, 255)
                out[y * w + x] = (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
            }
        }
        return out
    }
}
