package com.rocam.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.rocam.app.model.CameraControlBridge
import com.rocam.app.network.NetworkUtils
import com.rocam.app.network.RocamHttpServer
import com.rocam.app.ui.MainActivity

class RocamService : Service() {

    companion object {
        private const val TAG = "RocamService"
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "rocam_service_channel"

        const val ACTION_START_SERVICE = "com.rocam.app.START_SERVICE"
        const val ACTION_STOP_SERVICE = "com.rocam.app.STOP_SERVICE"
        const val ACTION_UPDATE_NOTIFICATION = "com.rocam.app.UPDATE_NOTIFICATION"

        fun startService(context: Context) {
            val intent = Intent(context, RocamService::class.java).apply {
                action = ACTION_START_SERVICE
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, RocamService::class.java).apply {
                action = ACTION_STOP_SERVICE
            }
            context.startService(intent)
        }
    }

    private var httpServer: RocamHttpServer? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_SERVICE -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START_SERVICE, null -> {
                startServerAndForeground()
            }
            ACTION_UPDATE_NOTIFICATION -> {
                updateNotification()
            }
        }
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.i(TAG, "App task removed from recent apps. Stopping RocamService.")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun startServerAndForeground() {
        val localIp = NetworkUtils.getLocalIpAddress(this)
        CameraControlBridge.localIp = localIp

        if (httpServer == null) {
            try {
                httpServer = RocamHttpServer.createAndStart(8080)
                CameraControlBridge.serverPort = httpServer?.actualPort ?: 8080
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start HTTP server", e)
            }
        }

        val notification = buildNotification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var serviceType = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                serviceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            startForeground(NOTIFICATION_ID, notification, serviceType)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val ip = CameraControlBridge.localIp
        val port = CameraControlBridge.serverPort
        val isRec = CameraControlBridge.isRecording
        val statusText = if (isRec) "🔴 촬영/녹화 중" else "🟢 제어 대기 중"

        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("RoCam - 스마트 카메라 서버 [$statusText]")
            .setContentText("제어 주소: http://$ip:$port")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "RoCam Background Camera Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Runs the background HTTP control server and persistent recording session"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.cancel(NOTIFICATION_ID)
        httpServer?.stop()
        httpServer = null
        Log.i(TAG, "RocamService destroyed, notification removed, and HTTP server stopped")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
