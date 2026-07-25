package com.nap.safe

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import com.google.android.gms.location.LocationServices
import com.google.android.gms.tasks.Tasks
import java.util.concurrent.TimeUnit

class LocationCheckWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "LocationCheckWorker"
        const val CHANNEL_ID = "napsafe_tracking_channel"
        const val NOTIFICATION_ID = 42
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "Worker running location check...")

        // Create foreground notification to allow background operation in latest Android
        createNotificationChannel()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("NapSafe Journey Tracking")
            .setContentText("Checking your location in the background...")
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setOngoing(true)
            .build()

        // Set worker as foreground
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                setForeground(androidx.work.ForegroundInfo(
                    NOTIFICATION_ID,
                    notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                ))
            } else {
                setForeground(androidx.work.ForegroundInfo(NOTIFICATION_ID, notification))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set foreground worker", e)
        }

        // Retrieve saved parameters
        val prefs = context.getSharedPreferences("napsafe_prefs", Context.MODE_PRIVATE)
        val isJourneyActive = prefs.getBoolean("journey_active", false)
        if (!isJourneyActive) {
            Log.d(TAG, "Journey is inactive. Stopping worker recurrence.")
            return Result.success()
        }

        val destLat = prefs.getFloat("dest_lat", Float.NaN)
        val destLng = prefs.getFloat("dest_lng", Float.NaN)
        val alertRadius = prefs.getFloat("alert_radius", 500f)

        if (destLat.isNaN() || destLng.isNaN()) {
            Log.e(TAG, "Destination coordinates not found or invalid.")
            return Result.failure()
        }

        // Get current location
        val currentLocation = getCurrentLocation()
        if (currentLocation == null) {
            Log.w(TAG, "Could not fetch current location. Retrying...")
            scheduleNextCheck()
            return Result.success()
        }

        // Calculate distance
        val destLocation = Location("destination").apply {
            latitude = destLat.toDouble()
            longitude = destLng.toDouble()
        }

        val distance = currentLocation.distanceTo(destLocation)
        Log.d(TAG, "Current Location: (${currentLocation.latitude}, ${currentLocation.longitude})")
        Log.d(TAG, "Destination: ($destLat, $destLng)")
        Log.d(TAG, "Distance to destination: $distance meters (Radius threshold: $alertRadius m)")

        // Store the latest distance for display in MainActivity
        prefs.edit().putFloat("last_distance", distance).apply()

        // Broadcast local intent to update UI
        val updateIntent = Intent("com.nap.safe.UPDATE_DISTANCE").apply {
            putExtra("distance", distance)
        }
        context.sendBroadcast(updateIntent)

        if (distance <= alertRadius) {
            Log.d(TAG, "Destination range reached! Triggering alarm.")
            triggerAlarm()
        } else {
            // Schedule next check in 1 minute using WorkManager chains or delay
            scheduleNextCheck()
        }

        return Result.success()
    }

    private fun getCurrentLocation(): Location? {
        // Try Play Services Location first
        try {
            val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
            val locationTask = fusedLocationClient.lastLocation
            val location = Tasks.await(locationTask, 10, TimeUnit.SECONDS)
            if (location != null) return location
        } catch (e: Exception) {
            Log.e(TAG, "Fused location provider failed", e)
        }

        // Fallback to traditional LocationManager
        try {
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val providers = locationManager.getProviders(true)
            var bestLocation: Location? = null
            for (provider in providers) {
                val loc = locationManager.getLastKnownLocation(provider) ?: continue
                if (bestLocation == null || loc.accuracy < bestLocation.accuracy) {
                    bestLocation = loc
                }
            }
            return bestLocation
        } catch (e: Exception) {
            Log.e(TAG, "LocationManager fallback failed", e)
        }

        return null
    }

    private fun scheduleNextCheck() {
        val prefs = context.getSharedPreferences("napsafe_prefs", Context.MODE_PRIVATE)
        val isJourneyActive = prefs.getBoolean("journey_active", false)
        if (!isJourneyActive) return

        // Schedule a one-time work check in 60 seconds using unique work
        val nextWorkRequest = androidx.work.OneTimeWorkRequestBuilder<LocationCheckWorker>()
            .setInitialDelay(60, TimeUnit.SECONDS)
            .build()

        androidx.work.WorkManager.getInstance(context).enqueueUniqueWork(
            "napsafe_periodic_check",
            androidx.work.ExistingWorkPolicy.REPLACE,
            nextWorkRequest
        )
    }

    private fun triggerAlarm() {
        // Mark journey as inactive
        context.getSharedPreferences("napsafe_prefs", Context.MODE_PRIVATE).edit()
            .putBoolean("journey_active", false)
            .apply()

        // Launch AlarmActivity with flags to wake screen
        val alarmIntent = Intent(context, AlarmActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        context.startActivity(alarmIntent)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "NapSafe Journey Tracking"
            val descriptionText = "Ensures background location check is active"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
}
