package com.bikegps.android.manager

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager as AndroidLocationMgr
import androidx.core.content.ContextCompat
import com.bikegps.android.model.GPSSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class LocationManager(private val context: Context) {
    val location: StateFlow<Location?> get() = _location
    val snapshot: StateFlow<GPSSnapshot?> get() = _snapshot

    private val _location = MutableStateFlow<Location?>(null)
    private val _snapshot = MutableStateFlow<GPSSnapshot?>(null)
    private var bearing = 0f
    private var locationListener: LocationListener? = null

    fun start() {
        if (!hasPermissions()) return
        val mgr = context.getSystemService(Context.LOCATION_SERVICE) as AndroidLocationMgr
        val listener = LocationListener { loc ->
            if (System.currentTimeMillis() - loc.time > 5000) return@LocationListener
            _location.value = loc
            val hdg = if (loc.hasBearing() && loc.speed > 0.5f) loc.bearing else bearing
            bearing = hdg
            _snapshot.value = GPSSnapshot(
                latitude = loc.latitude, longitude = loc.longitude,
                speed = maxOf(0.0, loc.speed * 3.6), heading = hdg.toDouble(),
                timestamp = System.currentTimeMillis()
            )
        }
        locationListener = listener
        try {
            mgr.requestLocationUpdates(AndroidLocationMgr.GPS_PROVIDER, 1000, 0f, listener)
            mgr.requestLocationUpdates(AndroidLocationMgr.NETWORK_PROVIDER, 1000, 0f, listener)
        } catch (_: SecurityException) {}
    }

    fun stop() {
        val mgr = context.getSystemService(Context.LOCATION_SERVICE) as AndroidLocationMgr
        locationListener?.let { mgr.removeUpdates(it) }
        locationListener = null
    }

    fun hasPermissions(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun onDestroy() { stop() }
}
