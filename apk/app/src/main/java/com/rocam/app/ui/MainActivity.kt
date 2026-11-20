package com.rocam.app.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.rocam.app.R
import com.rocam.app.camera.CameraGlManager
import com.rocam.app.databinding.ActivityMainBinding
import com.rocam.app.databinding.DialogSettingsBinding
import com.rocam.app.model.CameraControlBridge
import com.rocam.app.network.NetworkUtils
import com.rocam.app.service.RocamService
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var cameraGlManager: CameraGlManager? = null
    private var scaleGestureDetector: ScaleGestureDetector? = null
    private var orientationEventListener: OrientationEventListener? = null

    private var currentDeviceRotation: Int = Surface.ROTATION_0
    private var currentUiRotationAngle: Float = 0f

    private val mainHandler = Handler(Looper.getMainLooper())

    private val uiUpdateRunnable = object : Runnable {
        override fun run() {
            updateUiState()
            mainHandler.postDelayed(this, 500)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val cameraGranted = permissions[Manifest.permission.CAMERA] ?: false
        val audioGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false

        if (cameraGranted && audioGranted) {
            initAppAndCamera()
        } else {
            Toast.makeText(this, "카메라 및 마이크 권한이 필요합니다.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Ensure curved zoom gauge is completely hidden by default at launch
        binding.zoomDialView.hideDialImmediately()
        binding.layoutZoomPresets.alpha = 1f

        setupOrientationListener()
        setupEventListeners()
        setupZoomGestures()
        checkAndRequestPermissions()
    }

    private var wasRecording = false

    private fun setupOrientationListener() {
        orientationEventListener = object : OrientationEventListener(this, SensorManager.SENSOR_DELAY_UI) {
            private var lastDetectedRotation = -1

            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return

                val (targetRotation, targetAngle) = when (orientation) {
                    in 45..134 -> Pair(Surface.ROTATION_270, 270f)
                    in 135..224 -> Pair(Surface.ROTATION_180, 180f)
                    in 225..314 -> Pair(Surface.ROTATION_90, 90f)
                    else -> Pair(Surface.ROTATION_0, 0f)
                }

                if (targetRotation != lastDetectedRotation) {
                    lastDetectedRotation = targetRotation
                    
                    // If actively recording, do NOT rotate UI or reposition overlays!
                    if (CameraControlBridge.getStatus().isRecording) {
                        return
                    }

                    currentDeviceRotation = targetRotation
                    animateUiRotation(targetRotation, targetAngle)
                    cameraGlManager?.setRotation(targetRotation)
                }
            }
        }
        if (orientationEventListener?.canDetectOrientation() == true) {
            orientationEventListener?.enable()
        }
    }

    private fun animateUiRotation(deviceRotation: Int, targetAngle: Float) {
        currentUiRotationAngle = targetAngle
        val duration = 240L
        val interpolator = DecelerateInterpolator()

        // 1. In-place rotation for preset buttons and zoom dial
        binding.btnZoom05.animate().rotation(targetAngle).setDuration(duration).setInterpolator(interpolator).start()
        binding.btnZoom1.animate().rotation(targetAngle).setDuration(duration).setInterpolator(interpolator).start()
        binding.btnZoom2.animate().rotation(targetAngle).setDuration(duration).setInterpolator(interpolator).start()
        binding.zoomDialView.setUiRotation(deviceRotation, targetAngle)
        activeDialogCard?.animate()?.rotation(targetAngle)?.setDuration(duration)?.setInterpolator(interpolator)?.start()

        val rootW = binding.root.width.toFloat()
        val rootH = binding.root.height.toFloat()

        if (rootW > 0f && rootH > 0f) {
            val marginH = dpToPx(16).toFloat()
            val marginV = dpToPx(20).toFloat()
            val btnSize = dpToPx(44).toFloat()
            val bottomReserve = dpToPx(130).toFloat()

            // 2. Settings button: Top-Right from user's view
            val (settingsTx, settingsTy) = when (deviceRotation) {
                Surface.ROTATION_90 -> {
                    Pair(0f, rootH - 2 * marginV - btnSize - bottomReserve)
                }
                Surface.ROTATION_270 -> {
                    Pair(-(rootW - 2 * marginH - btnSize), 0f)
                }
                Surface.ROTATION_180 -> {
                    Pair(-(rootW - 2 * marginH - btnSize), rootH - 2 * marginV - btnSize - bottomReserve)
                }
                else -> {
                    Pair(0f, 0f)
                }
            }

            binding.btnSettings.animate()
                .translationX(settingsTx)
                .translationY(settingsTy)
                .rotation(targetAngle)
                .setDuration(duration)
                .setInterpolator(interpolator)
                .start()

            // 3. REC Badge: Top-Center from user's view
            val badgeApproxH = dpToPx(30).toFloat()
            val (recTx, recTy) = when (deviceRotation) {
                Surface.ROTATION_90 -> {
                    // Physical Right Edge, Vertically Centered
                    val tx = (rootW / 2f) - marginV - (badgeApproxH / 2f)
                    val ty = (rootH / 2f) - marginV - (badgeApproxH / 2f)
                    Pair(tx, ty)
                }
                Surface.ROTATION_270 -> {
                    // Physical Left Edge, Vertically Centered
                    val tx = -((rootW / 2f) - marginV - (badgeApproxH / 2f))
                    val ty = (rootH / 2f) - marginV - (badgeApproxH / 2f)
                    Pair(tx, ty)
                }
                Surface.ROTATION_180 -> {
                    // Physical Bottom Center
                    val tx = 0f
                    val ty = rootH - 2 * marginV - badgeApproxH - bottomReserve
                    Pair(tx, ty)
                }
                else -> {
                    // Surface.ROTATION_0: Physical Top Center
                    Pair(0f, 0f)
                }
            }

            binding.recIndicatorBadge.animate()
                .translationX(recTx)
                .translationY(recTy)
                .rotation(targetAngle)
                .setDuration(duration)
                .setInterpolator(interpolator)
                .start()
        } else {
            binding.btnSettings.animate().rotation(targetAngle).setDuration(duration).setInterpolator(interpolator).start()
            binding.recIndicatorBadge.animate().rotation(targetAngle).setDuration(duration).setInterpolator(interpolator).start()
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    private fun checkAndRequestPermissions() {
        val permissionsToRequest = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )

        // Android 13+ (API 33+) Notification Permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // Android 5.0 - 9.0 (API 21-28) Legacy Storage Permission
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }

        val missingPermissions = permissionsToRequest.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isEmpty()) {
            initAppAndCamera()
        } else {
            permissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    private fun initAppAndCamera() {
        // 1. Resolve local Wi-Fi IP and start Foreground Service
        val localIp = NetworkUtils.getLocalIpAddress(this)
        CameraControlBridge.localIp = localIp
        RocamService.startService(this)

        // 2. Initialize CameraX OpenGL Manager with current orientation
        if (cameraGlManager == null) {
            cameraGlManager = CameraGlManager(this, this, binding.previewSurfaceView, currentDeviceRotation)
        } else {
            cameraGlManager?.setRotation(currentDeviceRotation)
        }

        // 3. Start periodic UI state sync
        mainHandler.post(uiUpdateRunnable)
    }

    private fun setupZoomGestures() {
        // 1. Pinch-to-zoom on Viewfinder
        scaleGestureDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val currentZoom = cameraGlManager?.getZoomRatio() ?: 1.0f
                val newZoom = currentZoom * detector.scaleFactor
                val actual = cameraGlManager?.setZoom(newZoom) ?: newZoom
                updateZoomUi(actual)
                binding.zoomDialView.keepDialVisible()
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                super.onScaleEnd(detector)
                binding.zoomDialView.resetAutoHide(2000L)
            }
        })

        binding.previewSurfaceView.setOnTouchListener { _, event ->
            scaleGestureDetector?.onTouchEvent(event)
            true
        }

        // 2. Direct drag listener on ZoomDialView
        binding.zoomDialView.listener = object : ZoomDialView.OnZoomChangeListener {
            override fun onZoomChanged(zoomRatio: Float) {
                val actual = cameraGlManager?.setZoom(zoomRatio) ?: zoomRatio
                updateZoomUi(actual, updateDial = false)
            }

            override fun onZoomDragStart() {
                cameraGlManager?.cancelZoomAnimation()
            }

            override fun onZoomDragEnd() {
                // Drag completed
            }
        }

        binding.zoomDialView.onDialVisibilityChange = { isDialVisible ->
            if (isDialVisible) {
                binding.layoutZoomPresets.animate().alpha(0f).setDuration(120).start()
            } else {
                binding.layoutZoomPresets.animate().alpha(1f).setDuration(200).start()
            }
        }

        // 3. Continuous drag-to-zoom on zoom presets container (iPhone camera style)
        setupZoomPresetDrag()
    }

    private fun setupZoomPresetDrag() {
        val minDragDistance = dpToPx(8)
        var downX = 0f
        var downY = 0f
        var startZoom = 1.0f
        var isDragging = false
        var currentDragZoom = 1.0f

        binding.layoutZoomPresets.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startZoom = cameraGlManager?.getZoomRatio() ?: 1.0f
                    currentDragZoom = startZoom
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val primaryDelta = when (currentDeviceRotation) {
                        Surface.ROTATION_90 -> downY - event.rawY
                        Surface.ROTATION_270 -> event.rawY - downY
                        Surface.ROTATION_180 -> event.rawX - downX
                        else -> downX - event.rawX
                    }
                    val dragDist = when (currentDeviceRotation) {
                        Surface.ROTATION_90, Surface.ROTATION_270 -> Math.abs(event.rawY - downY)
                        else -> Math.abs(event.rawX - downX)
                    }

                    if (!isDragging && dragDist > minDragDistance) {
                        isDragging = true
                        cameraGlManager?.cancelZoomAnimation()
                        v.parent.requestDisallowInterceptTouchEvent(true)
                        binding.zoomDialView.showDial()
                    }

                    if (isDragging) {
                        val screenSpan = resources.displayMetrics.widthPixels.toFloat() * 0.32f
                        val factor = Math.pow(20.0, (primaryDelta / screenSpan).toDouble()).toFloat()
                        val targetZoom = (startZoom * factor).coerceIn(0.5f, 10.0f)
                        val roundedZoom = (Math.round(targetZoom * 10f) / 10f).coerceIn(0.5f, 10.0f)
                        currentDragZoom = roundedZoom
                        val actual = cameraGlManager?.setZoom(roundedZoom, forceHw = false) ?: roundedZoom
                        updateZoomUi(actual, updateDial = true)
                        binding.zoomDialView.keepDialVisible()
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isDragging) {
                        cameraGlManager?.setZoom(currentDragZoom, forceHw = true)
                        binding.zoomDialView.resetAutoHide(2000L)
                    } else {
                        // Pure button tap (not a drag): ensure curved dial is completely hidden
                        binding.zoomDialView.hideDialImmediately()

                        val location = IntArray(2)
                        v.getLocationOnScreen(location)
                        val localX = event.rawX - location[0]
                        val w = v.width.toFloat()
                        val ratio = if (localX < w / 3f) 0.5f else if (localX < w * 2f / 3f) 1.0f else 2.0f
                        cameraGlManager?.animateZoom(ratio)
                        updateZoomUi(ratio, updateDial = false)
                    }
                    isDragging = false
                    true
                }
                else -> false
            }
        }
    }

    private fun setupEventListeners() {
        // Shutter Button (Toggle Record / Stop)
        binding.btnShutterContainer.setOnClickListener {
            val status = CameraControlBridge.getStatus()
            if (status.isRecording) {
                CameraControlBridge.stopRecording()
            } else {
                CameraControlBridge.startRecording()
            }
            updateUiState()
        }

        // Settings / Info Button
        binding.btnSettings.setOnClickListener { showSettingsDialog() }
    }

    private fun updateZoomUi(activeZoom: Float = cameraGlManager?.getZoomRatio() ?: 1.0f, updateDial: Boolean = true) {
        // Highlight preset pill closest to active zoom
        setZoomButtonSelected(binding.btnZoom05, activeZoom < 0.8f)
        setZoomButtonSelected(binding.btnZoom1, activeZoom in 0.8f..1.5f)
        setZoomButtonSelected(binding.btnZoom2, activeZoom >= 1.6f)

        if (updateDial && binding.zoomDialView.visibility == View.VISIBLE) {
            binding.zoomDialView.setZoom(activeZoom)
        }
    }

    private fun setZoomButtonSelected(button: TextView, selected: Boolean) {
        if (selected) {
            button.setBackgroundResource(R.drawable.bg_zoom_item_selected)
            button.setTextColor(Color.BLACK)
        } else {
            button.setBackgroundResource(R.drawable.bg_zoom_item_normal)
            button.setTextColor(Color.WHITE)
        }
    }

    private fun updateUiState() {
        val status = CameraControlBridge.getStatus()

        if (status.isRecording) {
            wasRecording = true
            binding.recIndicatorBadge.visibility = View.VISIBLE
            val hours = status.durationSeconds / 3600
            val mins = (status.durationSeconds % 3600) / 60
            val secs = status.durationSeconds % 60
            binding.tvRecTimer.text = if (hours > 0) {
                String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, mins, secs)
            } else {
                String.format(Locale.getDefault(), "%02d:%02d", mins, secs)
            }

            // Show Red Square (Stop), Hide Red Circle
            binding.shutterCircle.visibility = View.GONE
            binding.shutterSquare.visibility = View.VISIBLE

            // Hide settings button while recording
            binding.btnSettings.visibility = View.GONE
        } else {
            if (wasRecording) {
                wasRecording = false
                // Unlock orientation and re-sync to current physical orientation
                val targetAngle = when (currentDeviceRotation) {
                    Surface.ROTATION_270 -> 270f
                    Surface.ROTATION_180 -> 180f
                    Surface.ROTATION_90 -> 90f
                    else -> 0f
                }
                animateUiRotation(currentDeviceRotation, targetAngle)
                cameraGlManager?.setRotation(currentDeviceRotation)
            }

            binding.recIndicatorBadge.visibility = View.GONE

            // Show Red Circle (Record), Hide Red Square
            binding.shutterCircle.visibility = View.VISIBLE
            binding.shutterSquare.visibility = View.GONE

            // Show settings button when idle
            binding.btnSettings.visibility = View.VISIBLE
        }

        updateZoomUi(status.zoomRatio)
    }

    private var activeDialogCard: View? = null

    private fun showSettingsDialog() {
        val dialogBinding = DialogSettingsBinding.inflate(LayoutInflater.from(this))
        val currentIp = NetworkUtils.getLocalIpAddress(this)
        CameraControlBridge.localIp = currentIp
        val status = CameraControlBridge.getStatus()
        val connectionInfo = "${status.localIp}:${status.serverPort}"

        dialogBinding.tvAccessInfo.text = connectionInfo

        // Initialize dialog card rotation to match current orientation
        dialogBinding.dialogCard.rotation = currentUiRotationAngle
        activeDialogCard = dialogBinding.dialogCard

        val dialog = AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.setOnDismissListener {
            activeDialogCard = null
        }

        dialogBinding.dialogRootContainer.setOnClickListener {
            dialog.dismiss()
        }

        dialogBinding.dialogCard.setOnClickListener {
            // Consume clicks inside card
        }

        dialogBinding.btnCloseDialog.setOnClickListener {
            dialog.dismiss()
        }

        dialogBinding.btnCloseBottom.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()

        dialog.window?.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT
        )
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Triggered when Home button or App Switcher is pressed
        handleExitAndSave()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) {
            handleExitAndSave()
        }
    }

    private var isTerminating = false

    private fun handleExitAndSave() {
        if (isTerminating) return
        isTerminating = true

        // 1. If recording, stop and save video safely
        if (CameraControlBridge.isRecording) {
            CameraControlBridge.stopRecording()
        }

        // 2. Stop Foreground Service immediately (removes notification from Quick Panel)
        RocamService.stopService(this)

        // 3. Stop UI sync runnable & Orientation listener
        mainHandler.removeCallbacks(uiUpdateRunnable)
        orientationEventListener?.disable()
        orientationEventListener = null

        // 4. Release camera and OpenGL resources
        cameraGlManager?.onDestroy()
        cameraGlManager = null

        // 5. Finish all activities and terminate
        finishAffinity()
    }

    override fun onDestroy() {
        super.onDestroy()
        handleExitAndSave()
    }
}
