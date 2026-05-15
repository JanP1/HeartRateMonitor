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

class MainActivity : AppCompatActivity() {

    private var camera: Camera? = null
    private val TAG = "HRM_DEBUG"
    private lateinit var viewFinder: PreviewView
    private var isAnalyzing = false
    private val analysisExecutor = Executors.newSingleThreadExecutor()

    // Array to store the readings
    private val measurementData = mutableListOf<Double>()

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
            val preview = Preview.Builder().build().also { it.surfaceProvider = viewFinder.surfaceProvider }

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            imageAnalysis.setAnalyzer(analysisExecutor) { imageProxy ->
                if (isAnalyzing) {
                    val avgGreen = processImageForGreen(imageProxy)
                    measurementData.add(avgGreen)
                }
                imageProxy.close()
            }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            try {
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis)
            } catch (e: Exception) {
                Log.e(TAG, "Binding failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun processImageForGreen(image: ImageProxy): Double {
        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer

        val yArray = ByteArray(yBuffer.remaining()).also { yBuffer.get(it) }
        val uArray = ByteArray(uBuffer.remaining()).also { uBuffer.get(it) }
        val vArray = ByteArray(vBuffer.remaining()).also { vBuffer.get(it) }

        val width = image.width
        val height = image.height
        val centerX = width / 2
        val centerY = height / 2
        val radius = 15

        var greenSum = 0.0
        var count = 0

        for (row in (centerY - radius)..(centerY + radius)) {
            for (col in (centerX - radius)..(centerX + radius)) {
                val yIndex = row * width + col
                val uvIndex = (row / 2) * (width / 2) + (col / 2)

                if (yIndex < yArray.size && uvIndex < uArray.size && uvIndex < vArray.size) {
                    val y = yArray[yIndex].toInt() and 0xFF
                    val u = uArray[uvIndex].toInt() and 0xFF
                    val v = vArray[uvIndex].toInt() and 0xFF

                    // G = Y - 0.34414 * (U - 128) - 0.71414 * (V - 128)
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

        measurementData.clear() // Reset data for new test
        button.isEnabled = false
        cameraControl.enableTorch(true)

        Toast.makeText(this, "Keep your finger steady...", Toast.LENGTH_SHORT).show()

        Handler(Looper.getMainLooper()).postDelayed({
            isAnalyzing = true
            Log.d(TAG, "Data collection started")
        }, 1000)

        Handler(Looper.getMainLooper()).postDelayed({
            isAnalyzing = false
            cameraControl.enableTorch(false)
            button.isEnabled = true
            estimateBPM()
        }, 21000L) // 1s stabilization + 20s data
    }

    private fun estimateBPM() {
        if (measurementData.size < 100) {
            Log.e(TAG, "Not enough data: ${measurementData.size} frames")
            return
        }

        // 1. Moving Average
        val smoothedData = mutableListOf<Double>()
        for (i in 2 until measurementData.size - 2) {
            val avg = (measurementData[i-2] + measurementData[i-1] + measurementData[i] +
                    measurementData[i+1] + measurementData[i+2]) / 5
            smoothedData.add(avg)
        }

        // 2. Debugging Logs - Look at these in Logcat!
        val minVal = smoothedData.minOrNull() ?: 0.0
        val maxVal = smoothedData.maxOrNull() ?: 0.0
        val range = maxVal - minVal

        // We lower the threshold to 1% of the range to catch even weak signals
        val threshold = minVal + (range * 0.01)

        Log.d(TAG, "Min: $minVal, Max: $maxVal, Range: $range, Threshold: $threshold")

        var peakCount = 0
        val frameGap = 7
        var lastPeakFrame = -frameGap

        for (i in 1 until smoothedData.size - 1) {
            val isPeak = smoothedData[i] > smoothedData[i - 1] && smoothedData[i] > smoothedData[i + 1]
            val aboveThreshold = smoothedData[i] > threshold
            val enoughTimePassed = (i - lastPeakFrame) > frameGap

            if (isPeak && aboveThreshold && enoughTimePassed) {
                peakCount++
                lastPeakFrame = i
            }
        }

        val bpm = (peakCount.toDouble() / 20.0) * 60.0
        Log.d(TAG, "Final Peak Count: $peakCount")

        if (peakCount == 0) {
            Toast.makeText(this, "No pulse detected. Try lighter pressure.", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "Estimated BPM: ${bpm.toInt()}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        analysisExecutor.shutdown()
    }
}