package com.rocam.app.gl

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.view.Surface
import java.nio.FloatBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class OverlayBannerRenderer {

    companion object {
        private const val VERTEX_SHADER = """
            uniform mat4 uMVPMatrix;
            attribute vec4 aPosition;
            attribute vec2 aTextureCoord;
            varying vec2 vTextureCoord;
            void main() {
                gl_Position = uMVPMatrix * aPosition;
                vTextureCoord = aTextureCoord;
            }
        """

        private const val FRAGMENT_SHADER = """
            precision mediump float;
            varying vec2 vTextureCoord;
            uniform sampler2D sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, vTextureCoord);
            }
        """

        private const val MARGIN_LEFT_PX = 24f
        private const val MARGIN_VERTICAL_PX = 28f // Symmetric margin for timestamp & subtitle in all orientations

        private val TEX_COORDS = floatArrayOf(
            0.0f, 1.0f,     // bottom left (flipped Y for GL)
            1.0f, 1.0f,     // bottom right
            0.0f, 0.0f,     // top left
            1.0f, 0.0f      // top right
        )
    }

    private var frameWidth = 720f
    private var frameHeight = 1280f
    private var overlayRotation: Int = Surface.ROTATION_0

    private var ndcMarginH = (MARGIN_LEFT_PX / frameWidth) * 2.0f
    private var ndcMarginV = (MARGIN_VERTICAL_PX / frameHeight) * 2.0f

    private var programHandle = 0
    private var uMVPMatrixLoc = 0
    private var aPositionLoc = 0
    private var aTextureCoordLoc = 0

    private var bannerTextureId = 0
    private var timeTextureId = 0

    private var bannerBitmapWidth = 0f
    private var bannerBitmapHeight = 0f
    private var timeBitmapWidth = 0f
    private var timeBitmapHeight = 0f

    private var bannerVertexBuffer: FloatBuffer = GlUtil.createFloatBuffer(floatArrayOf(
        -1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f
    ))
    private var timeVertexBuffer: FloatBuffer = GlUtil.createFloatBuffer(floatArrayOf(
        -1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f
    ))
    private val texCoordBuffer: FloatBuffer = GlUtil.createFloatBuffer(TEX_COORDS)

    private var currentText: String = ""
    private var isBannerDirty = true

    private var lastTimeSecond: Long = -1L
    private val dateFormat = SimpleDateFormat("yyyy.MM.dd HH:mm:ss", Locale.getDefault())

    init {
        programHandle = GlUtil.createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        if (programHandle == 0) {
            throw RuntimeException("Could not create OverlayBanner program")
        }
        aPositionLoc = GLES20.glGetAttribLocation(programHandle, "aPosition")
        aTextureCoordLoc = GLES20.glGetAttribLocation(programHandle, "aTextureCoord")
        uMVPMatrixLoc = GLES20.glGetUniformLocation(programHandle, "uMVPMatrix")

        bannerTextureId = GlUtil.createTexture2D()
        timeTextureId = GlUtil.createTexture2D()
    }

    @Synchronized
    fun setDimensions(width: Float, height: Float) {
        if (frameWidth != width || frameHeight != height) {
            frameWidth = width
            frameHeight = height
            ndcMarginH = (MARGIN_LEFT_PX / frameWidth) * 2.0f
            ndcMarginV = (MARGIN_VERTICAL_PX / frameHeight) * 2.0f
            isBannerDirty = true
            lastTimeSecond = -1L
        }
    }

    @Synchronized
    fun setRotation(rotation: Int) {
        if (overlayRotation != rotation) {
            overlayRotation = rotation
            isBannerDirty = true
            lastTimeSecond = -1L
        }
    }

    @Synchronized
    fun setText(text: String) {
        if (currentText != text) {
            currentText = text
            isBannerDirty = true
        }
    }

    @Synchronized
    private fun updateBannerTextureIfNeeded() {
        if (!isBannerDirty && bannerTextureId != 0) return

        if (currentText.isBlank()) {
            isBannerDirty = false
            return
        }

        // Font size 40f with crisp drop shadow
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 40f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setShadowLayer(6f, 3f, 3f, Color.argb(220, 0, 0, 0))
        }

        val textWidth = textPaint.measureText(currentText)
        val textHeight = 60f
        val bitmapWidth = (textWidth + 16f).toInt().coerceAtLeast(10)
        val bitmapHeight = textHeight.toInt()

        bannerBitmapWidth = bitmapWidth.toFloat()
        bannerBitmapHeight = bitmapHeight.toFloat()

        val bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val fontMetrics = textPaint.fontMetrics
        val baseline = (bitmapHeight - fontMetrics.bottom - fontMetrics.top) / 2f
        canvas.drawText(currentText, 4f, baseline, textPaint)

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, bannerTextureId)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)

        bitmap.recycle()

        recalculateBannerVertices()
        isBannerDirty = false
    }

    @Synchronized
    private fun recalculateBannerVertices() {
        if (bannerBitmapWidth <= 0f || bannerBitmapHeight <= 0f) return

        val isRotated90 = (overlayRotation == Surface.ROTATION_90 || overlayRotation == Surface.ROTATION_270)

        // When rotated 90°, the bitmap's length (width) lies along screen Y, and thickness (height) along screen X.
        // We calculate NDC units using the respective axis dimensions to guarantee 1:1 pixel scaling without stretching!
        val halfW = if (isRotated90) bannerBitmapWidth / frameHeight else bannerBitmapWidth / frameWidth
        val halfH = if (isRotated90) bannerBitmapHeight / frameWidth else bannerBitmapHeight / frameHeight

        val (centerX, centerY) = when (overlayRotation) {
            Surface.ROTATION_90 -> {
                // User's Bottom-Left = Screen Top-Left
                Pair(-1.0f + ndcMarginH + halfH, 1.0f - ndcMarginV - halfW)
            }
            Surface.ROTATION_270 -> {
                // User's Bottom-Left = Screen Bottom-Right
                Pair(1.0f - ndcMarginH - halfH, -1.0f + ndcMarginV + halfW)
            }
            Surface.ROTATION_180 -> {
                // User's Bottom-Left = Screen Top-Right
                Pair(1.0f - ndcMarginH - halfW, 1.0f - ndcMarginV - halfH)
            }
            else -> {
                // Surface.ROTATION_0: Screen Bottom-Left
                Pair(-1.0f + ndcMarginH + halfW, -1.0f + ndcMarginV + halfH)
            }
        }

        bannerVertexBuffer = buildQuadBuffer(centerX, centerY, halfW, halfH, overlayRotation)
    }

    @Synchronized
    private fun updateTimeTextureIfNeeded() {
        val currentSec = System.currentTimeMillis() / 1000L
        if (currentSec == lastTimeSecond && timeTextureId != 0) return

        lastTimeSecond = currentSec
        val timeString = dateFormat.format(Date(currentSec * 1000L))

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 26f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setShadowLayer(4f, 2f, 2f, Color.argb(220, 0, 0, 0))
        }

        val textWidth = textPaint.measureText(timeString)
        val textHeight = 36f
        val bitmapWidth = (textWidth + 12f).toInt().coerceAtLeast(10)
        val bitmapHeight = textHeight.toInt()

        timeBitmapWidth = bitmapWidth.toFloat()
        timeBitmapHeight = bitmapHeight.toFloat()

        val bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val fontMetrics = textPaint.fontMetrics
        val baseline = (bitmapHeight - fontMetrics.bottom - fontMetrics.top) / 2f
        canvas.drawText(timeString, 4f, baseline, textPaint)

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, timeTextureId)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)

        bitmap.recycle()

        recalculateTimeVertices()
    }

    @Synchronized
    private fun recalculateTimeVertices() {
        if (timeBitmapWidth <= 0f || timeBitmapHeight <= 0f) return

        val isRotated90 = (overlayRotation == Surface.ROTATION_90 || overlayRotation == Surface.ROTATION_270)

        val halfW = if (isRotated90) timeBitmapWidth / frameHeight else timeBitmapWidth / frameWidth
        val halfH = if (isRotated90) timeBitmapHeight / frameWidth else timeBitmapHeight / frameHeight

        val (centerX, centerY) = when (overlayRotation) {
            Surface.ROTATION_90 -> {
                // User's Top-Left = Screen Top-Right
                Pair(1.0f - ndcMarginH - halfH, 1.0f - ndcMarginV - halfW)
            }
            Surface.ROTATION_270 -> {
                // User's Top-Left = Screen Bottom-Left
                Pair(-1.0f + ndcMarginH + halfH, -1.0f + ndcMarginV + halfW)
            }
            Surface.ROTATION_180 -> {
                // User's Top-Left = Screen Bottom-Right
                Pair(1.0f - ndcMarginH - halfW, -1.0f + ndcMarginV + halfH)
            }
            else -> {
                // Surface.ROTATION_0: Screen Top-Left
                Pair(-1.0f + ndcMarginH + halfW, 1.0f - ndcMarginV - halfH)
            }
        }

        timeVertexBuffer = buildQuadBuffer(centerX, centerY, halfW, halfH, overlayRotation)
    }

    private fun buildQuadBuffer(
        centerX: Float,
        centerY: Float,
        halfW: Float,
        halfH: Float,
        rotation: Int
    ): FloatBuffer {
        val coords = when (rotation) {
            Surface.ROTATION_90 -> {
                // Rotated 90° clockwise: halfW is along Y, halfH is along X
                floatArrayOf(
                    centerX - halfH, centerY + halfW, // BL
                    centerX - halfH, centerY - halfW, // BR
                    centerX + halfH, centerY + halfW, // TL
                    centerX + halfH, centerY - halfW  // TR
                )
            }
            Surface.ROTATION_270 -> {
                // Rotated 270° clockwise (-90°): halfW is along Y, halfH is along X
                floatArrayOf(
                    centerX + halfH, centerY - halfW, // BL
                    centerX + halfH, centerY + halfW, // BR
                    centerX - halfH, centerY - halfW, // TL
                    centerX - halfH, centerY + halfW  // TR
                )
            }
            Surface.ROTATION_180 -> {
                // Rotated 180°
                floatArrayOf(
                    centerX + halfW, centerY + halfH, // BL
                    centerX - halfW, centerY + halfH, // BR
                    centerX + halfW, centerY - halfH, // TL
                    centerX - halfW, centerY - halfH  // TR
                )
            }
            else -> {
                // Surface.ROTATION_0 (Unrotated): halfW is along X, halfH is along Y
                floatArrayOf(
                    centerX - halfW, centerY - halfH, // BL
                    centerX + halfW, centerY - halfH, // BR
                    centerX - halfW, centerY + halfH, // TL
                    centerX + halfW, centerY + halfH  // TR
                )
            }
        }
        return GlUtil.createFloatBuffer(coords)
    }

    fun draw(mvpMatrix: FloatArray = GlUtil.IDENTITY_MATRIX) {
        updateTimeTextureIfNeeded()
        updateBannerTextureIfNeeded()

        GLES20.glUseProgram(programHandle)

        // Enable Alpha Blending
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        GLES20.glUniformMatrix4fv(uMVPMatrixLoc, 1, false, mvpMatrix, 0)

        GLES20.glEnableVertexAttribArray(aPositionLoc)
        GLES20.glEnableVertexAttribArray(aTextureCoordLoc)
        GLES20.glVertexAttribPointer(aTextureCoordLoc, 2, GLES20.GL_FLOAT, false, 8, texCoordBuffer)

        // 1. Draw Timestamp Text
        if (timeTextureId != 0) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, timeTextureId)
            GLES20.glVertexAttribPointer(aPositionLoc, 2, GLES20.GL_FLOAT, false, 8, timeVertexBuffer)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }

        // 2. Draw Subtitle Text
        if (currentText.isNotBlank() && bannerTextureId != 0) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, bannerTextureId)
            GLES20.glVertexAttribPointer(aPositionLoc, 2, GLES20.GL_FLOAT, false, 8, bannerVertexBuffer)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }

        GLES20.glDisableVertexAttribArray(aPositionLoc)
        GLES20.glDisableVertexAttribArray(aTextureCoordLoc)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glUseProgram(0)
    }

    fun release() {
        val textures = intArrayOf(bannerTextureId, timeTextureId)
        GLES20.glDeleteTextures(2, textures, 0)
        bannerTextureId = 0
        timeTextureId = 0

        if (programHandle != 0) {
            GLES20.glDeleteProgram(programHandle)
            programHandle = 0
        }
    }
}
