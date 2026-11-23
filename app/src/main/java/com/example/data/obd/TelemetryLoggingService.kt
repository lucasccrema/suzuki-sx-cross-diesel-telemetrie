package com.example.data.obd

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
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import java.util.Locale

class TelemetryLoggingService : Service() {

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    ensureNotificationChannel()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == ACTION_STOP_TRIP) {
      ObdRuntime.get(applicationContext).tripLogManager.stopTripAsync()
      stopForegroundCompat()
      stopSelf()
      return START_NOT_STICKY
    }

    val elapsedSec = intent?.getLongExtra(EXTRA_ELAPSED_SEC, 0L) ?: 0L
    val distanceKm = intent?.getDoubleExtra(EXTRA_DISTANCE_KM, 0.0) ?: 0.0
    val samples = intent?.getLongExtra(EXTRA_SAMPLE_COUNT, 0L) ?: 0L

    val notification = buildNotification(elapsedSec, distanceKm, samples)
    val foregroundType =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
      } else {
        0
      }

    try {
      ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, foregroundType)
    } catch (_: SecurityException) {
      stopSelf()
      return START_NOT_STICKY
    } catch (_: Exception) {
      stopSelf()
      return START_NOT_STICKY
    }

    return START_NOT_STICKY
  }

  private fun stopForegroundCompat() {
    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
  }

  private fun ensureNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val manager = getSystemService(NotificationManager::class.java) ?: return
      val channel =
        NotificationChannel(
            CHANNEL_ID,
            getString(R.string.trip_notif_channel_name),
            NotificationManager.IMPORTANCE_LOW,
          )
          .apply {
            description = getString(R.string.trip_notif_channel_desc)
            setShowBadge(false)
          }
      manager.createNotificationChannel(channel)
    }
  }

  private fun buildNotification(
    elapsedSec: Long,
    distanceKm: Double,
    samples: Long,
  ): Notification {
    val openIntent =
      Intent(this, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
      }
    val openPendingIntent =
      PendingIntent.getActivity(
        this,
        0,
        openIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )

    val stopIntent =
      Intent(this, TelemetryLoggingService::class.java).apply { action = ACTION_STOP_TRIP }
    val stopPendingIntent =
      PendingIntent.getService(
        this,
        1,
        stopIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )

    val mins = elapsedSec / 60
    val secs = elapsedSec % 60
    val text =
      String.format(
        Locale.ITALY,
        "Durata %02d:%02d · %.1f km · %d campioni CSV",
        mins,
        secs,
        distanceKm,
        samples,
      )

    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(R.mipmap.ic_launcher)
      .setContentTitle("Log Tragitto S-Cross attivo")
      .setContentText(text)
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .setSilent(true)
      .setContentIntent(openPendingIntent)
      .addAction(0, getString(R.string.trip_notif_stop_action), stopPendingIntent)
      .build()
  }

  companion object {
    private const val CHANNEL_ID = "obd_trip_logging"
    private const val NOTIFICATION_ID = 4201
    private const val ACTION_STOP_TRIP = "com.example.action.STOP_TRIP_LOGGING"
    private const val EXTRA_ELAPSED_SEC = "extra_elapsed_sec"
    private const val EXTRA_DISTANCE_KM = "extra_distance_km"
    private const val EXTRA_SAMPLE_COUNT = "extra_sample_count"

    fun startOrUpdate(
      context: Context,
      elapsedSec: Long,
      distanceKm: Double,
      sampleCount: Long,
    ) {
      val appCtx = context.applicationContext
      val intent =
        Intent(appCtx, TelemetryLoggingService::class.java).apply {
          putExtra(EXTRA_ELAPSED_SEC, elapsedSec)
          putExtra(EXTRA_DISTANCE_KM, distanceKm)
          putExtra(EXTRA_SAMPLE_COUNT, sampleCount)
        }
      runCatching { ContextCompat.startForegroundService(appCtx, intent) }
    }

    fun stop(context: Context) {
      val appCtx = context.applicationContext
      runCatching { appCtx.stopService(Intent(appCtx, TelemetryLoggingService::class.java)) }
    }
  }
}
