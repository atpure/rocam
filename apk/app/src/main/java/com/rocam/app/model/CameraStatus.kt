package com.rocam.app.model

data class CameraStatus(
    val isRecording: Boolean = false,
    val currentText: String = "장면 1",
    val serverPort: Int = 8080,
    val durationSeconds: Long = 0L,
    val currentFile: String? = null,
    val localIp: String = "127.0.0.1",
    val zoomRatio: Float = 1.0f,
    val minZoomRatio: Float = 1.0f,
    val maxZoomRatio: Float = 10.0f
)

interface CameraControlListener {
    fun onStartRecording(): Boolean
    fun onStopRecording(): String?
    fun onUpdateText(text: String)
    fun onSetZoom(ratio: Float): Float
    fun getStatus(): CameraStatus
}

object CameraControlBridge {
    private var listener: CameraControlListener? = null
    var serverPort: Int = 8080
    var currentText: String = "장면 1"
    var isRecording: Boolean = false
    var recordingDurationSec: Long = 0L
    var localIp: String = "127.0.0.1"
    var lastSavedFile: String? = null
    var zoomRatio: Float = 1.0f

    fun registerListener(listener: CameraControlListener) {
        this.listener = listener
    }

    fun unregisterListener() {
        this.listener = null
    }

    fun startRecording(): Boolean {
        val result = listener?.onStartRecording() ?: false
        if (result) {
            isRecording = true
        }
        return result
    }

    fun stopRecording(): String? {
        val filePath = listener?.onStopRecording()
        isRecording = false
        lastSavedFile = filePath
        return filePath
    }

    fun updateText(newText: String) {
        currentText = newText
        listener?.onUpdateText(newText)
    }

    fun setZoom(ratio: Float): Float {
        val actual = listener?.onSetZoom(ratio) ?: ratio
        zoomRatio = actual
        return actual
    }

    fun getStatus(): CameraStatus {
        return listener?.getStatus() ?: CameraStatus(
            isRecording = isRecording,
            currentText = currentText,
            serverPort = serverPort,
            durationSeconds = recordingDurationSec,
            currentFile = lastSavedFile,
            localIp = localIp,
            zoomRatio = zoomRatio
        )
    }
}
