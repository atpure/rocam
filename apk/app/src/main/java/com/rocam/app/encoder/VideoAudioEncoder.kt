package com.rocam.app.encoder

import android.content.ContentValues
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class VideoAudioEncoder(
    private val context: Context,
    private val width: Int = 720,
    private val height: Int = 1280,
    private val bitRate: Int = 4_000_000,
    private val frameRate: Int = 30
) {
    companion object {
        private const val TAG = "VideoAudioEncoder"
        private const val VIDEO_MIME = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val AUDIO_MIME = MediaFormat.MIMETYPE_AUDIO_AAC
        private const val AUDIO_SAMPLE_RATE = 44100
        private const val AUDIO_CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val AUDIO_BITRATE = 128_000
    }

    private var videoEncoder: MediaCodec? = null
    private var audioEncoder: MediaCodec? = null
    private var audioRecord: AudioRecord? = null
    private var mediaMuxer: MediaMuxer? = null
    var inputSurface: Surface? = null
        private set

    private var videoTrackIndex = -1
    private var audioTrackIndex = -1
    private var isMuxerStarted = false
    private val isRecording = AtomicBoolean(false)
    private val isStopping = AtomicBoolean(false)

    private var videoThread: Thread? = null
    private var audioThread: Thread? = null

    private var tempFile: File? = null
    private var recordingStartNano: Long = 0L
    private var finalSavedPath: String? = null

    fun start(): String {
        if (isRecording.get()) return finalSavedPath ?: ""

        isStopping.set(false)
        
        // 1. Prepare temp file in app-private external/cache dir (Guaranteed 100% write access across ALL Android versions)
        val tempDir = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir, "temp_rec")
        if (!tempDir.exists()) {
            tempDir.mkdirs()
        }
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        tempFile = File(tempDir, "REC_$timeStamp.mp4")
        if (tempFile!!.exists()) {
            tempFile!!.delete()
        }

        Log.i(TAG, "Initializing MediaMuxer with temp file: ${tempFile!!.absolutePath}")
        mediaMuxer = MediaMuxer(tempFile!!.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        // 2. Setup Video Encoder (H.264 AVC)
        val videoFormat = MediaFormat.createVideoFormat(VIDEO_MIME, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) // 1 sec GOP
        }
        videoEncoder = MediaCodec.createEncoderByType(VIDEO_MIME).apply {
            configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = createInputSurface()
            start()
        }

        // 3. Setup Audio Encoder & AudioRecord (AAC)
        try {
            val audioFormat = MediaFormat.createAudioFormat(AUDIO_MIME, AUDIO_SAMPLE_RATE, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }
            audioEncoder = MediaCodec.createEncoderByType(AUDIO_MIME).apply {
                configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }

            val minBufferSize = AudioRecord.getMinBufferSize(AUDIO_SAMPLE_RATE, AUDIO_CHANNEL_CONFIG, AUDIO_FORMAT)
            val bufferSize = Math.max(minBufferSize * 2, 4096)
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.CAMCORDER,
                AUDIO_SAMPLE_RATE,
                AUDIO_CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )
            if (audioRecord?.state == AudioRecord.STATE_INITIALIZED) {
                audioRecord?.startRecording()
                Log.i(TAG, "AudioRecord started successfully")
            } else {
                Log.w(TAG, "AudioRecord initialization failed")
                audioRecord = null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Audio initialization skipped: ${e.message}")
            audioRecord = null
            audioEncoder = null
        }

        recordingStartNano = System.nanoTime()
        isRecording.set(true)
        isMuxerStarted = false
        videoTrackIndex = -1
        audioTrackIndex = -1

        startDrainThreads()

        return tempFile!!.absolutePath
    }

    private fun startDrainThreads() {
        videoThread = Thread({
            drainVideo()
        }, "VideoDrainThread").apply { start() }

        if (audioRecord != null && audioEncoder != null) {
            audioThread = Thread({
                drainAudio()
            }, "AudioDrainThread").apply { start() }
        }
    }

    private fun drainVideo() {
        val bufferInfo = MediaCodec.BufferInfo()
        val encoder = videoEncoder ?: return

        var isVideoEos = false

        while (!isVideoEos) {
            try {
                val outputBufferIndex = encoder.dequeueOutputBuffer(bufferInfo, 10000)
                if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (isMuxerStarted) {
                        Log.e(TAG, "Video format changed after muxer started")
                    } else {
                        val newFormat = encoder.outputFormat
                        Log.i(TAG, "Video format ready: $newFormat")
                        synchronized(this) {
                            videoTrackIndex = mediaMuxer?.addTrack(newFormat) ?: -1
                            checkStartMuxer()
                        }
                    }
                } else if (outputBufferIndex >= 0) {
                    val outputBuffer = encoder.getOutputBuffer(outputBufferIndex)
                    if (outputBuffer == null) {
                        encoder.releaseOutputBuffer(outputBufferIndex, false)
                        continue
                    }

                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        bufferInfo.size = 0
                    }

                    if (bufferInfo.size > 0) {
                        // If muxer hasn't started yet (e.g. waiting for audio format), force start muxer after 300ms
                        var waitCount = 0
                        while (!isMuxerStarted && waitCount < 30) {
                            Thread.sleep(10)
                            waitCount++
                        }
                        if (!isMuxerStarted && videoTrackIndex >= 0) {
                            synchronized(this) {
                                if (!isMuxerStarted) {
                                    Log.w(TAG, "Starting Muxer with video only (audio timeout)")
                                    mediaMuxer?.start()
                                    isMuxerStarted = true
                                }
                            }
                        }

                        synchronized(this) {
                            if (isMuxerStarted && videoTrackIndex >= 0) {
                                outputBuffer.position(bufferInfo.offset)
                                outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                                mediaMuxer?.writeSampleData(videoTrackIndex, outputBuffer, bufferInfo)
                            }
                        }
                    }

                    encoder.releaseOutputBuffer(outputBufferIndex, false)

                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        Log.i(TAG, "Video encoder EOS reached")
                        isVideoEos = true
                    }
                } else if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (isStopping.get()) {
                        Thread.sleep(10)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in drainVideo", e)
                break
            }
        }
    }

    private fun drainAudio() {
        val audioBuffer = ByteArray(2048)
        val bufferInfo = MediaCodec.BufferInfo()
        val encoder = audioEncoder ?: return
        val recorder = audioRecord ?: return

        var isAudioEos = false

        while (!isAudioEos) {
            try {
                // 1. Feed PCM to Audio Encoder
                if (isRecording.get()) {
                    val readBytes = recorder.read(audioBuffer, 0, audioBuffer.size)
                    if (readBytes > 0) {
                        val inputBufferIndex = encoder.dequeueInputBuffer(5000)
                        if (inputBufferIndex >= 0) {
                            val inputBuffer = encoder.getInputBuffer(inputBufferIndex)
                            if (inputBuffer != null) {
                                inputBuffer.clear()
                                inputBuffer.put(audioBuffer, 0, readBytes)
                                val ptsUs = (System.nanoTime() - recordingStartNano) / 1000L
                                encoder.queueInputBuffer(inputBufferIndex, 0, readBytes, ptsUs, 0)
                            }
                        }
                    }
                } else if (isStopping.get()) {
                    val inputBufferIndex = encoder.dequeueInputBuffer(5000)
                    if (inputBufferIndex >= 0) {
                        encoder.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    }
                }

                // 2. Drain encoded AAC
                var outputBufferIndex = encoder.dequeueOutputBuffer(bufferInfo, 5000)
                while (outputBufferIndex >= 0) {
                    if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (isMuxerStarted) {
                            Log.e(TAG, "Audio format changed after muxer started")
                        } else {
                            val newFormat = encoder.outputFormat
                            Log.i(TAG, "Audio format ready: $newFormat")
                            synchronized(this) {
                                audioTrackIndex = mediaMuxer?.addTrack(newFormat) ?: -1
                                checkStartMuxer()
                            }
                        }
                    } else {
                        val outputBuffer = encoder.getOutputBuffer(outputBufferIndex)
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                bufferInfo.size = 0
                            }
                            if (bufferInfo.size > 0 && isMuxerStarted && audioTrackIndex >= 0) {
                                synchronized(this) {
                                    if (isMuxerStarted && audioTrackIndex >= 0) {
                                        outputBuffer.position(bufferInfo.offset)
                                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                                        mediaMuxer?.writeSampleData(audioTrackIndex, outputBuffer, bufferInfo)
                                    }
                                }
                            }
                        }
                        encoder.releaseOutputBuffer(outputBufferIndex, false)

                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            Log.i(TAG, "Audio encoder EOS reached")
                            isAudioEos = true
                            break
                        }
                    }
                    outputBufferIndex = encoder.dequeueOutputBuffer(bufferInfo, 0)
                }

                if (!isRecording.get() && !isStopping.get()) {
                    break
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in drainAudio", e)
                break
            }
        }
    }

    @Synchronized
    private fun checkStartMuxer() {
        if (!isMuxerStarted && videoTrackIndex >= 0) {
            if (audioRecord == null || audioTrackIndex >= 0) {
                try {
                    mediaMuxer?.start()
                    isMuxerStarted = true
                    Log.i(TAG, "MediaMuxer started! (videoTrack=$videoTrackIndex, audioTrack=$audioTrackIndex)")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start MediaMuxer", e)
                }
            }
        }
    }

    fun stop(): String? {
        if (!isRecording.getAndSet(false)) return finalSavedPath

        isStopping.set(true)
        Log.i(TAG, "Stopping encoder, signaling EOS...")

        try {
            // Signal EOS to Video Encoder
            videoEncoder?.signalEndOfInputStream()

            // Wait for both drain threads to finish flushing frames and writing MP4 moov atom
            videoThread?.join(2000)
            audioThread?.join(1500)

            // Stop and release encoders
            videoEncoder?.stop()
            videoEncoder?.release()
            videoEncoder = null

            try {
                audioRecord?.stop()
                audioRecord?.release()
                audioRecord = null
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping audioRecord", e)
            }

            try {
                audioEncoder?.stop()
                audioEncoder?.release()
                audioEncoder = null
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping audioEncoder", e)
            }

            // Stop and release MediaMuxer
            synchronized(this) {
                if (isMuxerStarted) {
                    try {
                        mediaMuxer?.stop()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error stopping MediaMuxer", e)
                    }
                    try {
                        mediaMuxer?.release()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error releasing MediaMuxer", e)
                    }
                    mediaMuxer = null
                    isMuxerStarted = false
                }
            }

            inputSurface?.release()
            inputSurface = null

            // Export finalized MP4 file into Android Public MediaStore / Gallery
            val temp = tempFile
            if (temp != null && temp.exists() && temp.length() > 0) {
                Log.i(TAG, "Temp video file finalized: ${temp.absolutePath}, size=${temp.length()} bytes")
                val exportedPath = exportToGallery(temp)
                finalSavedPath = exportedPath
                // Clean up temp file
                temp.delete()
                return exportedPath
            } else {
                Log.e(TAG, "Recorded file was empty or missing: ${temp?.absolutePath}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error while stopping and exporting video", e)
        }

        return finalSavedPath
    }

    private fun exportToGallery(sourceFile: File): String {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val displayName = "ROCAM_$timeStamp.mp4"

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/Camera")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }

                val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                        FileInputStream(sourceFile).use { inputStream ->
                            inputStream.copyTo(outputStream)
                            outputStream.flush()
                        }
                    }

                    values.clear()
                    values.put(MediaStore.Video.Media.IS_PENDING, 0)
                    context.contentResolver.update(uri, values, null, null)

                    Log.i(TAG, "Successfully exported video to MediaStore URI: $uri (DCIM/Camera/$displayName)")
                    return "DCIM/Camera/$displayName"
                }
            }

            // Fallback for Android 9 and below or if MediaStore insert failed
            val dcimDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
            val cameraDir = File(dcimDir, "Camera").apply { mkdirs() }
            val destFile = File(cameraDir, displayName)
            
            FileInputStream(sourceFile).use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output)
                    output.flush()
                }
            }

            MediaScannerConnection.scanFile(
                context,
                arrayOf(destFile.absolutePath),
                arrayOf("video/mp4")
            ) { path, uri ->
                Log.i(TAG, "MediaScanner indexed video: path=$path, uri=$uri")
            }

            Log.i(TAG, "Saved video to public DCIM/Camera: ${destFile.absolutePath}")
            return destFile.absolutePath

        } catch (e: Exception) {
            Log.e(TAG, "Error exporting video to Gallery", e)
            return sourceFile.absolutePath
        }
    }
}
