package com.example.heartratemonitor

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private var camera: Camera? = null
    private val TAG = "HRM_DEBUG"
    private lateinit var viewFinder: PreviewView
    private var isAnalyzing = false
    private val analysisExecutor = Executors.newSingleThreadExecutor()

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted -> if (isGranted) setupCamera() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        viewFinder = findViewById(R.id.viewFinder)
        val flashButton = findViewById<Button>(R.id.flashButton)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            setupCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        flashButton.setOnClickListener { startHeartRateDetection(flashButton) }
    }

    private fun setupCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.surfaceProvider = viewFinder.surfaceProvider
            }

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            imageAnalysis.setAnalyzer(analysisExecutor) { imageProxy ->
                if (isAnalyzing) {
                    val avgGreen = processImageForGreen(imageProxy)
                    Log.d("HRM_VALUE", "Green Intensity: $avgGreen")
                }
                imageProxy.close()
            }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis)

                // --- DISABLE CAMERA ADAPTIVITY ---
                // We use CameraControl to lock settings once bound
                val cameraControl = camera?.cameraControl

                // 1. Lock Exposure (AE)
                // We can't strictly "disable" it in basic CameraX, but we can lock the current state
                // by setting the exposure compensation or using Camera2Interop for advanced control.
                // For now, let's keep the focus locked to avoid hunting.
                cameraControl?.cancelFocusAndMetering()

            } catch (e: Exception) {
                Log.e(TAG, "Binding failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun processImageForGreen(image: ImageProxy): Double {
        // Plane 0 = Y (Luminance), Plane 1 = U (Chrominance), Plane 2 = V (Chrominance)
        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val yArray = ByteArray(ySize)
        val uArray = ByteArray(uSize)
        val vArray = ByteArray(vSize)

        yBuffer.get(yArray)
        uBuffer.get(uArray)
        vBuffer.get(vArray)

        val width = image.width
        val height = image.height

        // Sampling a small square in the center
        val centerX = width / 2
        val centerY = height / 2
        val radius = 15

        var greenSum = 0.0
        var count = 0

        for (row in (centerY - radius)..(centerY + radius)) {
            for (col in (centerX - radius)..(centerX + radius)) {
                val yIndex = row * width + col

                // YUV to RGB conversion formula for the Green channel:
                // G = Y - 0.344136 * (U - 128) - 0.714136 * (V - 128)

                val y = yArray[yIndex].toInt() and 0xFF

                // U and V planes are usually subsampled (half resolution)
                val uvIndex = (row / 2) * (width / 2) + (col / 2)

                if (uvIndex < uArray.size && uvIndex < vArray.size) {
                    val u = uArray[uvIndex].toInt() and 0xFF
                    val v = vArray[uvIndex].toInt() and 0xFF

                    val green = y - 0.34414 * (u - 128) - 0.71414 * (v - 128)
                    greenSum += green
                    count++
                }
            }
        }
        return if (count > 0) greenSum / count else 0.0
    }

    private fun startHeartRateDetection(button: Button) {
        val cameraControl = camera?.cameraControl ?: return

        button.isEnabled = false

        // Lock settings before we start
        // This stops the camera from adjusting to the sudden brightness of the finger/flash
        cameraControl.enableTorch(true)

        // Give the camera a half-second to stabilize before we trust the values
        Handler(Looper.getMainLooper()).postDelayed({
            isAnalyzing = true
            Log.d(TAG, "Analysis started - Green Channel")
        }, 500)

        Handler(Looper.getMainLooper()).postDelayed({
            cameraControl.enableTorch(false)
            isAnalyzing = false
            button.isEnabled = true
            Log.d(TAG, "Measurement finished.")
        }, 20000L)
    }

    override fun onDestroy() {
        super.onDestroy()
        analysisExecutor.shutdown()
    }
}