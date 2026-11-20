package com.rocam.app.gl

import android.opengl.GLES11Ext
import android.opengl.GLES20
import java.nio.FloatBuffer

class CameraOesRenderer {

    companion object {
        private const val VERTEX_SHADER = """
            uniform mat4 uMVPMatrix;
            uniform mat4 uTexMatrix;
            attribute vec4 aPosition;
            attribute vec4 aTextureCoord;
            varying vec2 vTextureCoord;
            void main() {
                gl_Position = uMVPMatrix * aPosition;
                vTextureCoord = (uTexMatrix * aTextureCoord).xy;
            }
        """

        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTextureCoord;
            uniform samplerExternalOES sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, vTextureCoord);
            }
        """

        // Full viewport quad vertices (-1 to 1)
        private val FULL_QUAD_COORDS = floatArrayOf(
            -1.0f, -1.0f,   // 0 bottom left
             1.0f, -1.0f,   // 1 bottom right
            -1.0f,  1.0f,   // 2 top left
             1.0f,  1.0f    // 3 top right
        )

        // Texture coordinates (0 to 1)
        private val FULL_QUAD_TEX_COORDS = floatArrayOf(
            0.0f, 0.0f,     // bottom left
            1.0f, 0.0f,     // bottom right
            0.0f, 1.0f,     // top left
            1.0f, 1.0f      // top right
        )
    }

    private var programHandle = 0
    private var uMVPMatrixLoc = 0
    private var uTexMatrixLoc = 0
    private var aPositionLoc = 0
    private var aTextureCoordLoc = 0

    private val vertexBuffer: FloatBuffer = GlUtil.createFloatBuffer(FULL_QUAD_COORDS)
    private val texCoordBuffer: FloatBuffer = GlUtil.createFloatBuffer(FULL_QUAD_TEX_COORDS)

    init {
        programHandle = GlUtil.createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        if (programHandle == 0) {
            throw RuntimeException("Could not create OES program")
        }
        aPositionLoc = GLES20.glGetAttribLocation(programHandle, "aPosition")
        aTextureCoordLoc = GLES20.glGetAttribLocation(programHandle, "aTextureCoord")
        uMVPMatrixLoc = GLES20.glGetUniformLocation(programHandle, "uMVPMatrix")
        uTexMatrixLoc = GLES20.glGetUniformLocation(programHandle, "uTexMatrix")
    }

    fun draw(texId: Int, texMatrix: FloatArray, mvpMatrix: FloatArray = GlUtil.IDENTITY_MATRIX) {
        GLES20.glUseProgram(programHandle)

        // Bind OES texture
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)

        // Set uniforms
        GLES20.glUniformMatrix4fv(uMVPMatrixLoc, 1, false, mvpMatrix, 0)
        GLES20.glUniformMatrix4fv(uTexMatrixLoc, 1, false, texMatrix, 0)

        // Set vertex positions
        GLES20.glEnableVertexAttribArray(aPositionLoc)
        GLES20.glVertexAttribPointer(aPositionLoc, 2, GLES20.GL_FLOAT, false, 8, vertexBuffer)

        // Set tex coords
        GLES20.glEnableVertexAttribArray(aTextureCoordLoc)
        GLES20.glVertexAttribPointer(aTextureCoordLoc, 2, GLES20.GL_FLOAT, false, 8, texCoordBuffer)

        // Draw quad
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        // Cleanup
        GLES20.glDisableVertexAttribArray(aPositionLoc)
        GLES20.glDisableVertexAttribArray(aTextureCoordLoc)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
        GLES20.glUseProgram(0)
    }

    fun release() {
        if (programHandle != 0) {
            GLES20.glDeleteProgram(programHandle)
            programHandle = 0
        }
    }
}
