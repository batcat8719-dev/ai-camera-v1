package com.aicam.v1

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Range
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var tvBadge: TextView
    private lateinit var tvIso: TextView
    private lateinit var tvShutter: TextView
    private lateinit var tvZoom: TextView
    private lateinit var manualPanel: LinearLayout
    private lateinit var seekIso: SeekBar
    private lateinit var seekShutter: SeekBar
    private lateinit var seekZoom: SeekBar
    private lateinit var btnMode: Button

    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var isManualMode = false

    private var isoRange: Range<Int>? = null
    private var exposureRange: Range<Long>? = null
    private var activeShutterSteps: List<Pair<String, Long>> = emptyList()
    private var hardwareReport: String = "Scanning..."

    private val allShutterPresets = listOf(
        "1/4000s" to 250_000L,
        "1/2000s" to 500_000L,
        "1/1000s" to 1_000_000L,
        "1/500s" to 2_000_000L,
        "1/250s" to 4_000_000L,
        "1/125s" to 8_000_000L,
        "1/60s" to 16_666_666L,
        "1/30s" to 33_333_333L,
        "1/15s" to 66_666_666L,
        "1/8s" to 125_000_000L,
        "1/4s" to 250_000_000L,
        "1/2s" to 500_000_000L,
        "1s" to 1_000_000_000L
    )

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                hardwareReport = scanAllCameraHardware()
                startCamera()
            } else {
                Toast.makeText(this, "Camera permission zaroori hai", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        tvBadge = findViewById(R.id.tvBadge)
        tvIso = findViewById(R.id.tvIso)
        tvShutter = findViewById(R.id.tvShutter)
        tvZoom = findViewById(R.id.tvZoom)
        manualPanel = findViewById(R.id.manualPanel)
        seekIso = findViewById(R.id.seekIso)
        seekShutter = findViewById(R.id.seekShutter)
        seekZoom = findViewById(R.id.seekZoom)
        btnMode = findViewById(R.id.btnMode)

        findViewById<Button>(R.id.btnSpecs).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Phone Camera DNA Report")
                .setMessage(hardwareReport)
                .setPositiveButton("OK", null)
                .show()
        }

        findViewById<Button>(R.id.btnFlip).setOnClickListener {
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                CameraSelector.LENS_FACING_FRONT
            } else {
                CameraSelector.LENS_FACING_BACK
            }
            isManualMode = false
            updateModeUI()
            startCamera()
        }

        btnMode.setOnClickListener {
            if (!isManualMode && (isoRange == null || activeShutterSteps.isEmpty())) {
                Toast.makeText(this, "Ye lens manual ISO/Shutter support nahi karta", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            isManualMode = !isManualMode
            updateModeUI()
            applyManualOrAutoControl()
        }

        findViewById<Button>(R.id.btnCapture).setOnClickListener {
            takePhoto()
        }

        setupSliders()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            hardwareReport = scanAllCameraHardware()
            startCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun updateModeUI() {
        btnMode.text = if (isManualMode) "MODE: MANUAL" else "MODE: AUTO"
        manualPanel.visibility = if (isManualMode) View.VISIBLE else View.GONE
    }

    private fun setupSliders() {
        val listener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && isManualMode) applyManualOrAutoControl()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        }
        seekIso.setOnSeekBarChangeListener(listener)
        seekShutter.setOnSeekBarChangeListener(listener)

        seekZoom.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val zoomState = camera?.cameraInfo?.zoomState?.value ?: return
                val minZ = zoomState.minZoomRatio
                val maxZ = zoomState.maxZoomRatio.coerceAtMost(10f)
                val targetZoom = minZ + (maxZ - minZ) * (progress / 100f)
                camera?.cameraControl?.setZoomRatio(targetZoom)
                tvZoom.text = String.format(Locale.US, "Zoom: %.1fx (Range %.1fx - %.1fx)", targetZoom, minZ, maxZ)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .build()

            val cameraSelector = CameraSelector.Builder()
                .requireLensFacing(lensFacing)
                .build()

            try {
                cameraProvider.unbindAll()
                val boundCamera = cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture
                )
                camera = boundCamera

                val c2Info = Camera2CameraInfo.from(boundCamera.cameraInfo)
                isoRange = c2Info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
                exposureRange = c2Info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
                val hwLevel = c2Info.getCameraCharacteristic(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)

                activeShutterSteps = exposureRange?.let { range ->
                    allShutterPresets.filter { it.second in range.lower..range.upper }
                } ?: emptyList()

                if (activeShutterSteps.isNotEmpty()) {
                    seekShutter.max = activeShutterSteps.size - 1
                    seekShutter.progress = activeShutterSteps.size / 2
                }

                seekZoom.progress = 0
                val side = if (lensFacing == CameraSelector.LENS_FACING_BACK) "BACK" else "FRONT"
                val levelText = hardwareLevelName(hwLevel)
                tvBadge.text = "$side | $levelText | ISO ${isoRange?.lower ?: "?"}-${isoRange?.upper ?: "?"}"
                applyManualOrAutoControl()
            } catch (e: Exception) {
                Toast.makeText(this, "Camera start error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun applyManualOrAutoControl() {
        val cam = camera ?: return
        val c2Control = Camera2CameraControl.from(cam.cameraControl)

        if (!isManualMode) {
            val autoOptions = CaptureRequestOptions.Builder().build()
            c2Control.setCaptureRequestOptions(autoOptions)
            tvIso.text = "ISO: Auto"
            tvShutter.text = "Shutter: Auto"
            return
        }

        val iRange = isoRange ?: return
        if (activeShutterSteps.isEmpty()) return

        val maxUsableIso = iRange.upper.coerceAtMost(6400)
        val selectedIso = (iRange.lower.toLong() +
            ((maxUsableIso - iRange.lower).toLong() * seekIso.progress / 100L)).toInt()

        val shutterPair = activeShutterSteps[seekShutter.progress.coerceIn(0, activeShutterSteps.lastIndex)]
        tvIso.text = "ISO: $selectedIso"
        tvShutter.text = "Shutter: ${shutterPair.first}"

        val manualOptions = CaptureRequestOptions.Builder()
            .setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            .setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, selectedIso)
            .setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, shutterPair.second)
            .build()

        c2Control.setCaptureRequestOptions(manualOptions)
    }

    private fun takePhoto() {
        val capture = imageCapture ?: return
        val name = "AICAM_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
            .format(System.currentTimeMillis())

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/AICam-V1")
            }
        }

        val outputOptions = ImageCapture.OutputFileOptions.Builder(
            contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        ).build()

        capture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    Toast.makeText(this@MainActivity, "Saved in Gallery/AICam-V1", Toast.LENGTH_SHORT).show()
                }
                override fun onError(exc: ImageCaptureException) {
                    Toast.makeText(this@MainActivity, "Capture failed: ${exc.message}", Toast.LENGTH_LONG).show()
                }
            }
        )
    }

    private fun scanAllCameraHardware(): String {
        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val sb = StringBuilder()
        sb.append("Device: ${Build.MANUFACTURER} ${Build.MODEL}\n")
        sb.append("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n\n")

        for (id in manager.cameraIdList) {
            val chars = manager.getCameraCharacteristics(id)
            val facing = when (chars.get(CameraCharacteristics.LENS_FACING)) {
                CameraMetadata.LENS_FACING_BACK -> "Rear"
                CameraMetadata.LENS_FACING_FRONT -> "Front"
                else -> "External"
            }
            val level = hardwareLevelName(chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL))
            val iso = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            val exp = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            val focals = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.joinToString { "${it}mm" } ?: "?"
            val apertures = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.joinToString { "f/$it" } ?: "?"
            val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            val hasRaw = caps?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW) == true

            sb.append("Camera #$id ($facing)\n")
            sb.append("  Level: $level | RAW: ${if (hasRaw) "YES" else "NO"}\n")
            sb.append("  Lens: $focals ($apertures)\n")
            sb.append("  ISO: ${iso?.lower ?: "N/A"} - ${iso?.upper ?: "N/A"}\n")
            if (exp != null) {
                val minSec = 1_000_000_000L / exp.lower.coerceAtLeast(1L)
                val maxSec = exp.upper / 1_000_000_000.0
                sb.append(String.format(Locale.US, "  Shutter: 1/%ds to %.1fs\n\n", minSec, maxSec))
            } else {
                sb.append("  Shutter: Auto Only\n\n")
            }
        }
        return sb.toString()
    }

    private fun hardwareLevelName(level: Int?): String = when (level) {
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3 (Pro+RAW)"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL (Manual OK)"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
        else -> "UNKNOWN"
    }
}
