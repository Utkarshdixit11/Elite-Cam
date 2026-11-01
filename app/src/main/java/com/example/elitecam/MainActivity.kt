package com.example.elitecam

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.hardware.camera2.CaptureRequest
import android.os.Bundle
import android.provider.MediaStore
import android.util.Size
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt

@OptIn(ExperimentalCamera2Interop::class)
class MainActivity : ComponentActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var sceneLabel: TextView
    private lateinit var autoSwitch: Switch
    private lateinit var captureBtn: View

    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var lastScene: SceneType? = null

    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val processExecutor = Executors.newSingleThreadExecutor()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else {
                Toast.makeText(this, "Camera permission is required", Toast.LENGTH_LONG).show()
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        previewView = findViewById(R.id.previewView)
        sceneLabel = findViewById(R.id.sceneLabel)
        autoSwitch = findViewById(R.id.autoSwitch)
        captureBtn = findViewById(R.id.captureBtn)

        captureBtn.setOnClickListener { capture() }
        autoSwitch.setOnCheckedChangeListener { _, on ->
            if (!on) camera?.cameraControl?.setExposureCompensationIndex(0)
            lastScene = null
        }
        setupTouch()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) startCamera() else permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            // ~12 MP, max quality, hardware noise reduction + edge enhancement at highest quality
            val captureBuilder = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setJpegQuality(98)
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(Size(4000, 3000), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
                        ).build()
                )
            Camera2Interop.Extender(captureBuilder)
                .setCaptureRequestOption(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
                .setCaptureRequestOption(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
            imageCapture = captureBuilder.build()

            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
                        ).build()
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(analysisExecutor, SceneAnalyzer { info -> runOnUiThread { onScene(info) } })

            provider.unbindAll()
            camera = provider.bindToLifecycle(
                this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture, analysis
            )
        }, ContextCompat.getMainExecutor(this))
    }

    private fun onScene(info: SceneInfo) {
        sceneLabel.text = "${info.scene.label} · ${info.cast.label}"
        if (!autoSwitch.isChecked || info.scene == lastScene) return
        lastScene = info.scene
        val cam = camera ?: return
        val range = cam.cameraInfo.exposureState.exposureCompensationRange
        if (range.upper == 0) return
        val idx = when (info.scene) {
            SceneType.LOW_LIGHT -> (range.upper * 0.6f).roundToInt()
            SceneType.BACKLIT -> (range.upper * 0.3f).roundToInt()
            SceneType.BRIGHT -> (range.lower * 0.3f).roundToInt()
            SceneType.NORMAL -> 0
        }
        cam.cameraControl.setExposureCompensationIndex(idx)
    }

    private fun setupTouch() {
        val scale = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                val z = camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1f
                camera?.cameraControl?.setZoomRatio(z * d.scaleFactor)
                return true
            }
        })
        previewView.setOnTouchListener { v, e ->
            scale.onTouchEvent(e)
            if (e.action == MotionEvent.ACTION_UP && !scale.isInProgress && e.eventTime - e.downTime < 300) {
                val point = previewView.meteringPointFactory.createPoint(e.x, e.y)
                camera?.cameraControl?.startFocusAndMetering(FocusMeteringAction.Builder(point).build())
                v.performClick()
            }
            true
        }
    }

    private fun capture() {
        val ic = imageCapture ?: return
        val enhance = autoSwitch.isChecked
        captureBtn.isEnabled = false
        val tmp = File(cacheDir, "cap_${System.currentTimeMillis()}.jpg")

        ic.takePicture(
            ImageCapture.OutputFileOptions.Builder(tmp).build(),
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    processExecutor.execute { processAndSave(tmp, enhance) }
                }

                override fun onError(e: ImageCaptureException) {
                    toast("Capture failed: ${e.message}")
                    captureBtn.isEnabled = true
                }
            }
        )
    }

    private fun processAndSave(tmp: File, enhance: Boolean) {
        try {
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            if (!enhance) {
                saveToGallery("ELITE_$stamp.jpg") { it.write(tmp.readBytes()) }
                toast("Saved original")
                return
            }
            saveToGallery("ELITE_${stamp}_orig.jpg") { it.write(tmp.readBytes()) }
            runOnUiThread { sceneLabel.text = "Enhancing…" }
            val (bmp, scene) = Enhancer.enhance(Enhancer.loadOriented(tmp))
            saveToGallery("ELITE_$stamp.jpg") { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            bmp.recycle()
            toast("Saved enhanced (${scene.label}) → Pictures/EliteCam")
        } catch (e: Throwable) {
            toast("Processing failed: ${e.message}")
        } finally {
            tmp.delete()
            runOnUiThread { captureBtn.isEnabled = true }
        }
    }

    private fun saveToGallery(name: String, write: (OutputStream) -> Unit) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/EliteCam")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("MediaStore insert failed")
        contentResolver.openOutputStream(uri)?.use(write)
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        contentResolver.update(uri, values, null, null)
    }

    private fun toast(msg: String) = runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }

    override fun onDestroy() {
        super.onDestroy()
        analysisExecutor.shutdown()
        processExecutor.shutdown()
    }
}
