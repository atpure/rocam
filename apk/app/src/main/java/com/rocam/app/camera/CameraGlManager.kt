package com.rocam.app.camera

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.opengl.EGL14
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.animation.DecelerateInterpolator
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.rocam.app.encoder.VideoAudioEncoder
import com.rocam.app.gl.CameraOesRenderer
import com.rocam.app.gl.EglCore
import com.rocam.app.gl.GlUtil
import com.rocam.app.gl.OverlayBannerRenderer
import com.rocam.app.model.CameraControlBridge
import com.rocam.app.model.CameraControlListener
import com.rocam.app.model.CameraStatus
import java.util.concurrent.atomic.AtomicBoolean

class CameraGlManager(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val surfaceView: SurfaceView,
    initialRotation: Int = Surface.ROTATION_0
) : CameraControlListener, SurfaceHolder.Callback {

    companion object {
        private const val TAG = "CameraGlManager"
        const val DEFAULT_PORTRAIT_WIDTH = 720
        const val DEFAULT_PORTRAIT_HEIGHT = 1280
    }

    private val glThread = HandlerThread("CameraGLThread").apply { start() }
    private val glHandler = Handler(glThread.looper)

    private var eglCore: EglCore? = null
    private var previewEglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var encoderEglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var cameraTexId = 0
    private var cameraSurfaceTexture: SurfaceTexture? = null
    private var cameraInputSurface: Surface? = null

    private var cameraRenderer: CameraOesRenderer? = null
    private var previewOverlayRenderer: OverlayBannerRenderer? = null
    private var encoderOverlayRenderer: OverlayBannerRenderer? = null
    private var previewUseCase: Preview? = null

    private val texMatrix = FloatArray(16)
    private val encoderMvpMatrix = FloatArray(16).apply {
        Matrix.setIdentityM(this, 0)
    }
    private val activeRecordingEncoderMvpMatrix = FloatArray(16).apply {
        Matrix.setIdentityM(this, 0)
    }

    private var previewWidth = 720
    private var previewHeight = 1280
    private var previewVpX = 0
    private var previewVpY = 0
    private var previewVpWidth = 720
    private var previewVpHeight = 1280

    private var currentRotation: Int = initialRotation
    private var videoWidth = if (initialRotation == Surface.ROTATION_90 || initialRotation == Surface.ROTATION_270) 1280 else DEFAULT_PORTRAIT_WIDTH
    private var videoHeight = if (initialRotation == Surface.ROTATION_90 || initialRotation == Surface.ROTATION_270) 720 else DEFAULT_PORTRAIT_HEIGHT

    private var videoAudioEncoder: VideoAudioEncoder? = null
    private val isRecording = AtomicBoolean(false)
    private var recordingStartTime = 0L
    private var recordingStartNanoTime = 0L
    private var lastRecordedFile: String? = null

    private var currentText: String = "장면 1"

    init {
        updateEncoderMvpMatrix(initialRotation)
        CameraControlBridge.registerListener(this)
        surfaceView.holder.addCallback(this)
    }

    private fun updateEncoderMvpMatrix(rotation: Int) {
        Matrix.setIdentityM(encoderMvpMatrix, 0)
    }

    fun setRotation(rotation: Int) {
        if (isRecording.get()) {
            return
        }
        currentRotation = rotation
        val isLandscape = (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270)
        val w = if (isLandscape) 1280 else 720
        val h = if (isLandscape) 720 else 1280

        videoWidth = w
        videoHeight = h
        updateEncoderMvpMatrix(rotation)

        if (Looper.myLooper() == Looper.getMainLooper()) {
            try {
                previewUseCase?.targetRotation = rotation
            } catch (e: Exception) {
                Log.w(TAG, "Failed to update preview targetRotation", e)
            }
        } else {
            Handler(Looper.getMainLooper()).post {
                try {
                    previewUseCase?.targetRotation = rotation
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to update preview targetRotation", e)
                }
            }
        }

        glHandler.post {
            val activeWidth = if (previewVpWidth > 0) previewVpWidth.toFloat() else w.toFloat()
            val activeHeight = if (previewVpHeight > 0) previewVpHeight.toFloat() else h.toFloat()
            previewOverlayRenderer?.setDimensions(activeWidth, activeHeight)
            previewOverlayRenderer?.setRotation(rotation)
            encoderOverlayRenderer?.setDimensions(w.toFloat(), h.toFloat())
        }
    }

    private fun updatePreviewViewport() {
        if (previewWidth <= 0 || previewHeight <= 0) return

        // Exact 9:16 aspect ratio (width / height = 9 / 16)
        val targetAspect = 9.0f / 16.0f
        val currentAspect = previewWidth.toFloat() / previewHeight.toFloat()

        if (currentAspect > targetAspect) {
            // Surface is wider than 9:16 -> Pillarbox (black bars left and right)
            previewVpHeight = previewHeight
            previewVpWidth = (previewHeight * targetAspect).toInt()
            previewVpX = (previewWidth - previewVpWidth) / 2
            previewVpY = 0
        } else {
            // Surface is taller than 9:16 -> Letterbox (black bars top and bottom)
            previewVpWidth = previewWidth
            previewVpHeight = (previewWidth / targetAspect).toInt()
            previewVpX = 0
            previewVpY = (previewHeight - previewVpHeight) / 2
        }
        Log.i(TAG, "updatePreviewViewport: surface=${previewWidth}x${previewHeight} -> viewport=${previewVpWidth}x${previewVpHeight} at ($previewVpX, $previewVpY)")
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        Log.i(TAG, "surfaceCreated")
        glHandler.post {
            initGL(holder.surface)
            startCameraX()
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        Log.i(TAG, "surfaceChanged: width=$width, height=$height")
        previewWidth = width
        previewHeight = height
        updatePreviewViewport()
        glHandler.post {
            previewOverlayRenderer?.setDimensions(previewVpWidth.toFloat(), previewVpHeight.toFloat())
            previewOverlayRenderer?.setRotation(currentRotation)
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        Log.i(TAG, "surfaceDestroyed")
        glHandler.post {
            releaseGL()
        }
    }

    private fun initGL(surface: Surface) {
        try {
            val core = EglCore(null, EglCore.FLAG_RECORDABLE)
            eglCore = core
            previewEglSurface = core.createWindowSurface(surface)
            core.makeCurrent(previewEglSurface)

            cameraTexId = GlUtil.createExternalTexture()
            val st = SurfaceTexture(cameraTexId)
            st.setDefaultBufferSize(720, 1280)
            st.setOnFrameAvailableListener {
                glHandler.post {
                    drawFrame()
                }
            }
            cameraSurfaceTexture = st
            cameraInputSurface = Surface(st)
            updatePreviewViewport()

            cameraRenderer = CameraOesRenderer()
            previewOverlayRenderer = OverlayBannerRenderer().apply {
                setDimensions(previewVpWidth.toFloat(), previewVpHeight.toFloat())
                setRotation(currentRotation)
                setText(currentText)
            }
            encoderOverlayRenderer = OverlayBannerRenderer().apply {
                setDimensions(videoWidth.toFloat(), videoHeight.toFloat())
                setText(currentText)
            }
            Log.i(TAG, "OpenGL ES pipeline initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init GL", e)
        }
    }

    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraInstance: androidx.camera.core.Camera? = null
    private var mainCameraSelector: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    private var ultraWideCameraSelector: CameraSelector? = null
    private var currentCameraSelector: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    private var isUltraWideBound = false

    private fun startCameraX() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                val provider = cameraProviderFuture.get()
                cameraProvider = provider

                // Fixed ROTATION_0 with 720x1280 portrait buffer.
                // The Android Window Manager automatically handles physical window rotation for preview,
                // keeping on-screen preview 100% upright in all orientations without double-rotation or re-binding.
                val preview = Preview.Builder()
                    .setTargetAspectRatio(AspectRatio.RATIO_16_9)
                    .setTargetRotation(currentRotation)
                    .build()
                previewUseCase = preview

                preview.setSurfaceProvider { request ->
                    val surf = cameraInputSurface
                    if (surf != null && surf.isValid) {
                        request.provideSurface(surf, ContextCompat.getMainExecutor(context)) {
                            Log.d(TAG, "CameraX surface request completed")
                        }
                    } else {
                        request.willNotProvideSurface()
                    }
                }

                // Detect available back cameras (Main Wide vs Ultra-Wide)
                detectBackCameras(provider)

                // Bind initial camera (Main Wide)
                currentCameraSelector = mainCameraSelector
                provider.unbindAll()
                val cam = provider.bindToLifecycle(lifecycleOwner, mainCameraSelector, preview)
                cameraInstance = cam
                isUltraWideBound = false

                Log.i(TAG, "CameraX bound to lifecycle with rotation=ROTATION_0, zoom min=${cam.cameraInfo.zoomState.value?.minZoomRatio}, max=${cam.cameraInfo.zoomState.value?.maxZoomRatio}, hasUltraWide=${ultraWideCameraSelector != null}")
            } catch (e: Exception) {
                Log.e(TAG, "Error binding CameraX", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun detectBackCameras(provider: ProcessCameraProvider) {
        try {
            val available = provider.availableCameraInfos
            var ultraWideId: String? = null
            var mainId: String? = null

            for (info in available) {
                try {
                    val c2Info = Camera2CameraInfo.from(info)
                    val facing = c2Info.getCameraCharacteristic(CameraCharacteristics.LENS_FACING)
                    if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                        val camId = c2Info.cameraId
                        val focalLengths = c2Info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                        val minFocal = focalLengths?.minOrNull() ?: 4.0f
                        Log.i(TAG, "Found BACK camera ID=$camId, focalLengths=${focalLengths?.joinToString()}, minZoom=${info.zoomState.value?.minZoomRatio}")

                        // Samsung S10 ultra-wide has ID "2" or focal length < 3.0mm (typically ~1.8mm)
                        if (camId == "2" || minFocal < 3.0f) {
                            ultraWideId = camId
                        } else if (camId == "0" || (minFocal in 3.0f..5.5f && mainId == null)) {
                            mainId = camId
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error querying camera characteristics", e)
                }
            }

            if (ultraWideId != null) {
                val targetId = ultraWideId
                ultraWideCameraSelector = CameraSelector.Builder()
                    .addCameraFilter { list ->
                        list.filter {
                            try {
                                Camera2CameraInfo.from(it).cameraId == targetId
                            } catch (e: Exception) {
                                false
                            }
                        }
                    }
                    .build()
                Log.i(TAG, "Configured Ultra-Wide CameraSelector for ID: $ultraWideId")
            }

            if (mainId != null) {
                val targetId = mainId
                mainCameraSelector = CameraSelector.Builder()
                    .addCameraFilter { list ->
                        list.filter {
                            try {
                                Camera2CameraInfo.from(it).cameraId == targetId
                            } catch (e: Exception) {
                                false
                            }
                        }
                    }
                    .build()
            } else {
                mainCameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to detect back cameras", e)
        }
    }

    private var lastAppliedHwZoom = -1f
    private var lastHwZoomTime = 0L

    fun setZoom(ratio: Float, forceHw: Boolean = false): Float {
        val targetZoom = (Math.round(ratio * 10f) / 10f).coerceIn(0.5f, 10.0f)
        CameraControlBridge.zoomRatio = targetZoom

        val now = System.currentTimeMillis()
        if (!forceHw && Math.abs(targetZoom - lastAppliedHwZoom) < 0.05f) {
            return targetZoom
        }
        if (!forceHw && (now - lastHwZoomTime) < 30L) {
            return targetZoom
        }
        lastAppliedHwZoom = targetZoom
        lastHwZoomTime = now

        val currentCam = cameraInstance

        // 1. Check if the current camera natively supports zoom <= 0.6f (logical multi-camera)
        val nativeMin = currentCam?.cameraInfo?.zoomState?.value?.minZoomRatio ?: 1.0f
        if (nativeMin <= 0.6f) {
            val hwMax = currentCam?.cameraInfo?.zoomState?.value?.maxZoomRatio ?: 10.0f
            val hwZoom = targetZoom.coerceIn(nativeMin, hwMax)
            currentCam?.cameraControl?.setZoomRatio(hwZoom)
            return targetZoom
        }

        // 2. Multi-camera switching for Galaxy S10 / discrete sensor devices
        if (targetZoom < 0.8f) {
            // Target is 0.5x (Ultra-Wide)
            if (ultraWideCameraSelector != null && !isUltraWideBound) {
                switchCamera(ultraWideCameraSelector!!, isUltraWide = true)
            }
            return 0.5f
        } else {
            // Target is 1.0x ~ 10.0x (Main Wide / Telephoto)
            if (isUltraWideBound) {
                switchCamera(mainCameraSelector, isUltraWide = false)
            }
            val cam = cameraInstance
            val hwMax = cam?.cameraInfo?.zoomState?.value?.maxZoomRatio ?: 10.0f
            val hwMin = cam?.cameraInfo?.zoomState?.value?.minZoomRatio ?: 1.0f
            val hwZoom = targetZoom.coerceIn(hwMin, hwMax)
            cam?.cameraControl?.setZoomRatio(hwZoom)
            return targetZoom
        }
    }

    private fun switchCamera(selector: CameraSelector, isUltraWide: Boolean) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            doSwitchCamera(selector, isUltraWide)
        } else {
            Handler(Looper.getMainLooper()).post {
                doSwitchCamera(selector, isUltraWide)
            }
        }
    }

    private fun doSwitchCamera(selector: CameraSelector, isUltraWide: Boolean) {
        val provider = cameraProvider ?: return
        val preview = previewUseCase ?: return
        try {
            provider.unbindAll()
            val cam = provider.bindToLifecycle(lifecycleOwner, selector, preview)
            cameraInstance = cam
            currentCameraSelector = selector
            isUltraWideBound = isUltraWide
            Log.i(TAG, "Switched camera (isUltraWide=$isUltraWide, id=${Camera2CameraInfo.from(cam.cameraInfo).cameraId})")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to switch camera", e)
        }
    }

    fun getZoomRatio(): Float {
        if (isUltraWideBound) return 0.5f
        return CameraControlBridge.zoomRatio.coerceIn(0.5f, 10.0f)
    }

    fun getMinZoomRatio(): Float {
        return 0.5f
    }

    fun getMaxZoomRatio(): Float {
        return 10.0f
    }

    private var zoomAnimator: ValueAnimator? = null

    fun animateZoom(targetRatio: Float, durationMs: Long = 240L) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post { animateZoom(targetRatio, durationMs) }
            return
        }

        zoomAnimator?.cancel()
        val startRatio = getZoomRatio()
        if (Math.abs(startRatio - targetRatio) < 0.05f) {
            setZoom(targetRatio)
            return
        }

        val animator = ValueAnimator.ofFloat(startRatio, targetRatio).apply {
            duration = durationMs
            interpolator = DecelerateInterpolator()
            addUpdateListener { animation ->
                val v = animation.animatedValue as Float
                setZoom(v)
            }
        }
        zoomAnimator = animator
        animator.start()
    }

    fun cancelZoomAnimation() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            zoomAnimator?.cancel()
            zoomAnimator = null
        } else {
            Handler(Looper.getMainLooper()).post {
                zoomAnimator?.cancel()
                zoomAnimator = null
            }
        }
    }

    override fun onSetZoom(ratio: Float): Float {
        animateZoom(ratio)
        return ratio
    }

    private var activeRecordingVideoWidth = DEFAULT_PORTRAIT_WIDTH
    private var activeRecordingVideoHeight = DEFAULT_PORTRAIT_HEIGHT

    private fun drawFrame() {
        val core = eglCore ?: return
        val st = cameraSurfaceTexture ?: return

        try {
            st.updateTexImage()
            st.getTransformMatrix(texMatrix)

            // Pass 1: Render to on-screen SurfaceView (Clean 16:9 Viewfinder with Black Letterbox Bars)
            if (previewEglSurface != EGL14.EGL_NO_SURFACE) {
                core.makeCurrent(previewEglSurface)

                // 1. Clear entire surface to pure black (Letterbox / Pillarbox bars)
                GLES20.glViewport(0, 0, previewWidth, previewHeight)
                GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

                // 2. Render camera stream inside exact 9:16 Viewfinder box
                GLES20.glViewport(previewVpX, previewVpY, previewVpWidth, previewVpHeight)
                cameraRenderer?.draw(cameraTexId, texMatrix, GlUtil.IDENTITY_MATRIX)
                previewOverlayRenderer?.setText(currentText)
                previewOverlayRenderer?.draw(GlUtil.IDENTITY_MATRIX)

                core.swapBuffers(previewEglSurface)
            }

            // Pass 2: Render to Video Encoder Surface if recording
            if (isRecording.get() && encoderEglSurface != EGL14.EGL_NO_SURFACE) {
                core.makeCurrent(encoderEglSurface)
                GLES20.glViewport(0, 0, activeRecordingVideoWidth, activeRecordingVideoHeight)
                GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

                cameraRenderer?.draw(cameraTexId, texMatrix, activeRecordingEncoderMvpMatrix)
                encoderOverlayRenderer?.draw(GlUtil.IDENTITY_MATRIX)

                val ptsNs = if (recordingStartNanoTime > 0) {
                    System.nanoTime() - recordingStartNanoTime
                } else 0L

                core.setPresentationTime(encoderEglSurface, ptsNs)
                core.swapBuffers(encoderEglSurface)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error drawing GL frame", e)
        }
    }

    override fun onStartRecording(): Boolean {
        if (isRecording.get()) return true
        Log.i(TAG, "onStartRecording requested: videoWidth=$videoWidth, videoHeight=$videoHeight, currentRotation=$currentRotation")

        val recWidth = videoWidth
        val recHeight = videoHeight
        activeRecordingVideoWidth = recWidth
        activeRecordingVideoHeight = recHeight
        val encoder = VideoAudioEncoder(context, recWidth, recHeight)
        val path = encoder.start()
        lastRecordedFile = path
        videoAudioEncoder = encoder

        glHandler.post {
            val surface = encoder.inputSurface
            if (surface != null && eglCore != null) {
                encoderEglSurface = eglCore!!.createWindowSurface(surface)
            }
            System.arraycopy(encoderMvpMatrix, 0, activeRecordingEncoderMvpMatrix, 0, 16)
            encoderOverlayRenderer?.setDimensions(recWidth.toFloat(), recHeight.toFloat())
            encoderOverlayRenderer?.setText(currentText)

            recordingStartTime = System.currentTimeMillis()
            recordingStartNanoTime = System.nanoTime()
            isRecording.set(true)
            CameraControlBridge.isRecording = true
        }
        return true
    }

    override fun onStopRecording(): String? {
        if (!isRecording.getAndSet(false)) return lastRecordedFile
        Log.i(TAG, "onStopRecording requested")

        CameraControlBridge.isRecording = false

        // Stop and flush encoder synchronously to complete MP4 moov atom and export to Gallery
        val savedPath = videoAudioEncoder?.stop()
        lastRecordedFile = savedPath
        videoAudioEncoder = null

        glHandler.post {
            if (encoderEglSurface != EGL14.EGL_NO_SURFACE && eglCore != null) {
                eglCore?.releaseSurface(encoderEglSurface)
                encoderEglSurface = EGL14.EGL_NO_SURFACE
            }
            recordingStartTime = 0L
            recordingStartNanoTime = 0L
        }

        return savedPath
    }

    override fun onUpdateText(text: String) {
        currentText = text
        glHandler.post {
            previewOverlayRenderer?.setText(text)
            encoderOverlayRenderer?.setText(text)
        }
    }

    override fun getStatus(): CameraStatus {
        val durationSec = if (isRecording.get() && recordingStartTime > 0) {
            (System.currentTimeMillis() - recordingStartTime) / 1000
        } else 0L

        val zoom = getZoomRatio()
        val minZ = getMinZoomRatio()
        val maxZ = getMaxZoomRatio()

        return CameraStatus(
            isRecording = isRecording.get(),
            currentText = currentText,
            serverPort = CameraControlBridge.serverPort,
            durationSeconds = durationSec,
            currentFile = lastRecordedFile,
            localIp = CameraControlBridge.localIp,
            zoomRatio = zoom,
            minZoomRatio = minZ,
            maxZoomRatio = maxZ
        )
    }

    private fun releaseGL() {
        if (isRecording.get()) {
            onStopRecording()
        }
        cameraSurfaceTexture?.release()
        cameraSurfaceTexture = null
        cameraInputSurface?.release()
        cameraInputSurface = null

        cameraRenderer?.release()
        cameraRenderer = null
        previewOverlayRenderer?.release()
        previewOverlayRenderer = null
        encoderOverlayRenderer?.release()
        encoderOverlayRenderer = null

        if (previewEglSurface != EGL14.EGL_NO_SURFACE) {
            eglCore?.releaseSurface(previewEglSurface)
            previewEglSurface = EGL14.EGL_NO_SURFACE
        }
        eglCore?.release()
        eglCore = null
    }

    fun onDestroy() {
        cancelZoomAnimation()
        CameraControlBridge.unregisterListener()
        glHandler.post {
            releaseGL()
        }
        glThread.quitSafely()
    }
}
