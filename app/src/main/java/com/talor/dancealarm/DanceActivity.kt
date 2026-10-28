package com.talor.dancealarm

import android.Manifest
import android.app.KeyguardManager
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseDetector
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.sqrt

/** Full-screen "lock" shown over the lock screen until you've danced enough. */
class DanceActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "DanceAlarm"
        @Volatile var isVisible = false
            private set
    }

    private lateinit var previewView: PreviewView
    private lateinit var overlay: PoseOverlayView
    private lateinit var titleText: TextView
    private lateinit var statusText: TextView
    private lateinit var percentText: TextView
    private lateinit var debugText: TextView
    private lateinit var songText: TextView
    private lateinit var progressBar: ProgressBar

    private lateinit var detector: DanceDetector
    private var poseDetector: PoseDetector? = null
    private var cameraExecutor: ExecutorService? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var sensorManager: SensorManager? = null
    private var sensorListener: SensorEventListener? = null
    private var usingFront = true
    private var finished = false
    private val handler = Handler(Looper.getMainLooper())

    private val songTicker = object : Runnable {
        override fun run() {
            val s = AlarmService.currentSong
            songText.text = if (s.isBlank()) "" else "🎵  $s"
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_dance)
        hideSystemBars()

        if (!AlarmService.isRinging) {
            finish()
            return
        }

        previewView = findViewById(R.id.previewView)
        overlay = findViewById(R.id.overlay)
        titleText = findViewById(R.id.titleText)
        statusText = findViewById(R.id.statusText)
        percentText = findViewById(R.id.percentText)
        debugText = findViewById(R.id.debugText)
        songText = findViewById(R.id.songText)
        progressBar = findViewById(R.id.progressBar)
        previewView.scaleType = PreviewView.ScaleType.FILL_CENTER

        detector = DanceDetector(Prefs.danceSeconds(this), Prefs.difficulty(this))

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                Toast.makeText(this@DanceActivity, "Nope 😄 Dance!", Toast.LENGTH_SHORT).show()
            }
        })

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            startMotionFallback("No camera permission — hold your phone and dance!")
        }
        handler.post(songTicker)
    }

    override fun onResume() {
        super.onResume()
        isVisible = true
        hideSystemBars()
    }

    override fun onPause() {
        isVisible = false
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onDestroy() {
        isVisible = false
        handler.removeCallbacksAndMessages(null)
        sensorListener?.let { sensorManager?.unregisterListener(it) }
        try { cameraProvider?.unbindAll() } catch (_: Exception) {}
        cameraExecutor?.shutdown()
        poseDetector?.close()
        super.onDestroy()
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    // ---------- camera + pose ----------

    private fun startCamera() {
        poseDetector = PoseDetection.getClient(
            PoseDetectorOptions.Builder()
                .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
                .build()
        )
        cameraExecutor = Executors.newSingleThreadExecutor()

        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(cameraExecutor!!) { proxy -> analyze(proxy) }

                usingFront = provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
                val selector = if (usingFront) CameraSelector.DEFAULT_FRONT_CAMERA
                else CameraSelector.DEFAULT_BACK_CAMERA

                provider.unbindAll()
                provider.bindToLifecycle(this, selector, preview, analysis)
                statusText.text = "Step back so I can see your whole body"
            } catch (e: Exception) {
                Log.e(TAG, "Camera failed", e)
                startMotionFallback("Camera unavailable — hold your phone and dance!")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @OptIn(ExperimentalGetImage::class)
    private fun analyze(proxy: ImageProxy) {
        val media = proxy.image
        val pd = poseDetector
        if (media == null || pd == null || finished) {
            proxy.close()
            return
        }
        val rotation = proxy.imageInfo.rotationDegrees
        val w = if (rotation % 180 == 0) proxy.width else proxy.height
        val h = if (rotation % 180 == 0) proxy.height else proxy.width
        val t = proxy.imageInfo.timestamp / 1e9

        pd.process(InputImage.fromMediaImage(media, rotation))
            .addOnSuccessListener { pose ->
                if (finished) return@addOnSuccessListener
                val state = detector.update(pose, t)
                overlay.setPose(pose, w, h, usingFront, state.dancing)
                render(state, cameraMode = true)
            }
            .addOnFailureListener { e -> Log.w(TAG, "Pose failed", e) }
            .addOnCompleteListener { proxy.close() }
    }

    // ---------- fallback: motion sensor ----------

    private fun startMotionFallback(reason: String) {
        previewView.visibility = View.GONE
        overlay.visibility = View.GONE
        titleText.text = "Hold your phone and dance!"
        statusText.text = reason

        val sm = getSystemService(SensorManager::class.java)
        sensorManager = sm
        val linear = sm?.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        val sensor = linear ?: sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (sm == null || sensor == null) {
            statusText.text = "No camera or motion sensor — alarm will stop in 60 s"
            handler.postDelayed({ complete() }, 60_000)
            return
        }
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val m = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2])
                val mag = if (linear != null) m else abs(m - SensorManager.GRAVITY_EARTH)
                render(detector.updateMotion(mag, e.timestamp / 1e9), cameraMode = false)
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        sensorListener = listener
        sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
    }

    // ---------- UI ----------

    private fun render(s: DanceDetector.State, cameraMode: Boolean) {
        if (finished) return
        progressBar.progress = (s.progress * 1000).toInt().coerceIn(0, 1000)
        percentText.text = "${(s.progress * 100).toInt()}%"

        if (cameraMode) {
            val armsOk = s.arms > detector.armThreshold
            val legsOk = s.legs > detector.legThreshold
            statusText.text = when {
                !s.bodyVisible -> "Step back — I need to see your whole body 👀"
                s.dancing -> "YES! Keep dancing! 🔥"
                !armsOk && !legsOk -> "Move! Arms AND legs 💃"
                !armsOk -> "More arms! 🙌"
                else -> "Use your legs too! 🦵"
            }
            debugText.text = String.format(
                "arms %.2f / %.2f    legs %.2f / %.2f",
                s.arms, detector.armThreshold, s.legs, detector.legThreshold
            )
        } else {
            statusText.text = if (s.dancing) "YES! Keep moving! 🔥" else "Move more! 💃"
            debugText.text = String.format("motion %.1f / %.1f", s.arms, detector.motionDisplayThreshold)
        }

        if (s.progress >= 1f) complete()
    }

    private fun complete() {
        if (finished) return
        finished = true
        AlarmService.stop(this)
        sensorListener?.let { sensorManager?.unregisterListener(it) }
        try { cameraProvider?.unbindAll() } catch (_: Exception) {}

        progressBar.progress = 1000
        percentText.text = "100%"
        titleText.text = "Good morning! ☀️"
        statusText.text = "Alarm off. Have an awesome day."
        debugText.text = ""

        try {
            getSystemService(KeyguardManager::class.java)?.requestDismissKeyguard(this, null)
        } catch (_: Exception) {}
        handler.postDelayed({ finish() }, 2500)
    }
}
