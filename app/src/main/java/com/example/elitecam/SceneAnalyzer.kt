package com.example.elitecam

import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy

/** Looks at the live preview frames (YUV) a few times per second and classifies the scene. */
class SceneAnalyzer(private val onResult: (SceneInfo) -> Unit) : ImageAnalysis.Analyzer {

    private var lastRun = 0L

    override fun analyze(image: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastRun < 400) {
            image.close()
            return
        }
        lastRun = now
        try {
            val w = image.width
            val h = image.height

            // Luma statistics
            val yPlane = image.planes[0]
            val yBuf = yPlane.buffer
            var sum = 0L
            var dark = 0
            var bright = 0
            var n = 0
            var y = 0
            while (y < h) {
                var x = 0
                while (x < w) {
                    val v = yBuf.get(y * yPlane.rowStride + x * yPlane.pixelStride).toInt() and 0xFF
                    sum += v
                    if (v < 40) dark++
                    if (v > 220) bright++
                    n++
                    x += 6
                }
                y += 6
            }
            val meanY = sum.toFloat() / n
            val scene = SceneType.classify(meanY, dark.toFloat() / n, bright.toFloat() / n)

            // Color cast from chroma planes (U = planes[1], V = planes[2])
            val uP = image.planes[1]
            val vP = image.planes[2]
            var uSum = 0L
            var vSum = 0L
            var c = 0
            y = 0
            while (y < h / 2) {
                var x = 0
                while (x < w / 2) {
                    uSum += uP.buffer.get(y * uP.rowStride + x * uP.pixelStride).toInt() and 0xFF
                    vSum += vP.buffer.get(y * vP.rowStride + x * vP.pixelStride).toInt() and 0xFF
                    c++
                    x += 4
                }
                y += 4
            }
            val diff = (vSum.toFloat() - uSum.toFloat()) / c   // >0 reddish, <0 bluish
            val cast = when {
                diff > 12f -> CastType.WARM
                diff < -12f -> CastType.COOL
                else -> CastType.NEUTRAL
            }
            onResult(SceneInfo(scene, cast))
        } catch (_: Exception) {
            // ignore a bad frame
        } finally {
            image.close()
        }
    }
}
