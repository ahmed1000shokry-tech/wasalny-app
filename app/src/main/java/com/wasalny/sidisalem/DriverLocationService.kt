package com.wasalny.sidisalem

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationResult
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.firebase.geofire.GeoFireUtils
import com.firebase.geofire.GeoLocation

class DriverLocationService : Service() {
    private val fused by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private val db by lazy { FirebaseFirestore.getInstance() }
    private var callback: LocationCallback? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, notification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val uid = intent?.getStringExtra(EXTRA_UID)
        val name = intent?.getStringExtra(EXTRA_NAME)
        if (uid.isNullOrBlank()) { stopSelf(); return START_NOT_STICKY }
        startLocationUpdates(uid, name.orEmpty())
        return START_NOT_STICKY
    }

    private fun startLocationUpdates(uid: String, name: String) {
        callback?.let { fused.removeLocationUpdates(it) }
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10_000L)
            .setMinUpdateIntervalMillis(5_000L)
            .setWaitForAccurateLocation(false)
            .build()
        callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return
                if (location.accuracy > 100f) return
                db.collection("drivers").document(uid).update(
                    mapOf(
                        "displayName" to name,
                        "lat" to location.latitude,
                        "lon" to location.longitude,
                        "geohash" to GeoFireUtils.getGeoHashForLocation(GeoLocation(location.latitude, location.longitude)),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
            }
        }
        try {
            fused.requestLocationUpdates(request, callback!!, mainLooper)
        } catch (_: SecurityException) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        callback?.let { fused.removeLocationUpdates(it) }
        callback = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "وصلني - موقع السائق", NotificationManager.IMPORTANCE_LOW))
    }

    private fun notification(): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("وصلني توكتوك")
        .setContentText("أنت متاح لاستقبال الرحلات وموقعك مفعّل")
        .setSmallIcon(android.R.drawable.ic_menu_mylocation)
        .setOngoing(true)
        .build()

    companion object {
        const val EXTRA_UID = "uid"
        const val EXTRA_NAME = "name"
        private const val CHANNEL_ID = "wasalny_driver_location"
        private const val NOTIFICATION_ID = 7101
    }
}
