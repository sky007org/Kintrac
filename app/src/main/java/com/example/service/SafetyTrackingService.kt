package com.example.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.data.model.SafetyStatus
import com.example.data.repository.SafetyRepository
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sqrt

class SafetyTrackingService : Service(), SensorEventListener, LocationListener {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var safetyRepository: SafetyRepository
    private var locationManager: LocationManager? = null
    private var sensorManager: SensorManager? = null

    private var currentLatitude: Double = 0.0
    private var currentLongitude: Double = 0.0
    private var currentAccuracy: Float = 0f
    private var currentSpeed: Float = 0f

    private var currentBatteryLevel: Int = 100
    private var isBatteryCharging: Boolean = false

    private var currentActivityState: String = "STILL"
    private var estimatedSteps: Int = 0

    // Acceleration variance calculation
    private val accelHistory = mutableListOf<Float>()
    private val maxHistory = 20

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.let {
                val level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                if (level >= 0 && scale > 0) {
                    currentBatteryLevel = (level * 100) / scale
                }
                val status = it.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                isBatteryCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_STATUS_FULL
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        safetyRepository = SafetyRepository(this)
        locationManager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager

        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        setupSensors()
        startPeriodicSync()
    }

    private fun setupSensors() {
        sensorManager?.let { sm ->
            val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            if (accel != null) {
                sm.registerListener(this, accel, SensorManager.SENSOR_DELAY_NORMAL)
            }
            val stepSensor = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
            if (stepSensor != null) {
                sm.registerListener(this, stepSensor, SensorManager.SENSOR_DELAY_UI)
            }
        }
    }

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val familyCode = intent?.getStringExtra(EXTRA_FAMILY_CODE) ?: ""
        val displayName = intent?.getStringExtra(EXTRA_DISPLAY_NAME) ?: "Family Member"

        createNotificationChannel()
        val notification = buildForegroundNotification(displayName)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        startLocationTracking()
        triggerImmediateSync(familyCode, displayName)

        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startLocationTracking() {
        locationManager?.let { lm ->
            try {
                if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    lm.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER,
                        10000L, // 10 seconds min update
                        5f,     // 5 meters min distance
                        this
                    )
                    lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { onLocationChanged(it) }
                }
                if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    lm.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER,
                        10000L,
                        5f,
                        this
                    )
                    if (currentLatitude == 0.0) {
                        lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)?.let { onLocationChanged(it) }
                    }
                }
            } catch (e: SecurityException) {
                Log.w(TAG, "Location permission not granted for service", e)
            }
        }
    }

    private fun startPeriodicSync() {
        serviceScope.launch {
            while (isActive) {
                syncSafetyStatus()
                delay(60000L) // 1 minute interval as requested
            }
        }
    }

    private fun triggerImmediateSync(familyCode: String, displayName: String) {
        serviceScope.launch {
            delay(1000L)
            syncSafetyStatus(familyCode, displayName)
        }
    }

    private suspend fun syncSafetyStatus(familyCodeArg: String = "", displayNameArg: String = "") {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        try {
            val existing = safetyRepository.getUserProfile(uid).getOrNull()
            val code = if (familyCodeArg.isNotBlank()) familyCodeArg else (existing?.familyCode ?: "DEFAULT")
            val name = if (displayNameArg.isNotBlank()) displayNameArg else (existing?.displayName ?: "Protected Member")

            val status = SafetyStatus(
                userId = uid,
                familyCode = code,
                displayName = name,
                sharingActive = true,
                latitude = currentLatitude,
                longitude = currentLongitude,
                accuracy = currentAccuracy,
                batteryLevel = currentBatteryLevel,
                isCharging = isBatteryCharging,
                activityState = currentActivityState,
                stepCount = estimatedSteps
            )
            safetyRepository.updateSafetyStatus(status)
            Log.d(TAG, "Synced safety status: $currentActivityState, battery: $currentBatteryLevel%")
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing safety status in service", e)
        }
    }

    override fun onLocationChanged(location: Location) {
        currentLatitude = location.latitude
        currentLongitude = location.longitude
        currentAccuracy = location.accuracy
        currentSpeed = location.speed

        // Detect if traveling fast in vehicle
        if (location.speed > 5.5f) { // ~20 km/h
            currentActivityState = "IN_VEHICLE"
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]
                val magnitude = sqrt((x * x + y * y + z * z).toDouble()).toFloat()
                // Subtract gravity ~ 9.81
                val linearMag = kotlin.math.abs(magnitude - 9.81f)

                accelHistory.add(linearMag)
                if (accelHistory.size > maxHistory) {
                    accelHistory.removeAt(0)
                }

                if (accelHistory.size >= 10 && currentSpeed <= 5.5f) {
                    val averageVariance = accelHistory.average().toFloat()
                    currentActivityState = when {
                        averageVariance < 0.4f -> "STILL"
                        averageVariance < 3.2f -> "WALKING"
                        else -> "RUNNING"
                    }
                }
            }
            Sensor.TYPE_STEP_COUNTER -> {
                estimatedSteps = event.values[0].toInt()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Family Safety Sharing",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Persistent notification indicating your location and status are safely shared with family"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(displayName: String): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, SafetyTrackingService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Family Safety Sharing Active")
            .setContentText("Sharing live location & activity for $displayName")
            .setSmallIcon(R.drawable.family_safety_icon)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .addAction(R.drawable.ic_launcher_foreground, "Stop Sharing", stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        try {
            unregisterReceiver(batteryReceiver)
        } catch (_: Exception) {}
        locationManager?.removeUpdates(this)
        sensorManager?.unregisterListener(this)

        // Mark sharing inactive in Firestore
        FirebaseAuth.getInstance().currentUser?.uid?.let { uid ->
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    safetyRepository.updateSafetyStatus(
                        SafetyStatus(
                            userId = uid,
                            sharingActive = false,
                            latitude = currentLatitude,
                            longitude = currentLongitude,
                            batteryLevel = currentBatteryLevel,
                            activityState = "STILL"
                        )
                    )
                } catch (_: Exception) {}
            }
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "SafetyTrackingService"
        private const val NOTIFICATION_ID = 4041
        private const val CHANNEL_ID = "safety_sharing_channel"

        const val ACTION_START = "com.example.service.START_SHARING"
        const val ACTION_STOP = "com.example.service.STOP_SHARING"
        const val EXTRA_FAMILY_CODE = "extra_family_code"
        const val EXTRA_DISPLAY_NAME = "extra_display_name"
    }
}
