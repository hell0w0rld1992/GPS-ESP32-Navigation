package com.bikegps.android.manager

import android.content.Context
import android.location.Location
import android.os.BatteryManager
import com.bikegps.android.model.NavigationState
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

class GPSTransmitter(
    private val context: Context,
    private val locationMgr: LocationManager,
    private val bleMgr: BLEManager,
    private val navMgr: NavigationManager
) {
    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var weatherTemp: Double? = null
    private var weatherRain: Int? = null
    private var lastWeatherFetch: Long = 0L
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    fun start() {
        if (job != null) return
        job = scope.launch {
            while (isActive) {
                transmit()
                delay(1000)
            }
        }
    }

    fun stop() { job?.cancel(); job = null }

    private suspend fun transmit() {
        if (bleMgr.status.value !is BLEManager.ConnectionStatus.Connected) return
        val snap = locationMgr.snapshot.value ?: return
        val loc = locationMgr.location.value ?: return
        navMgr.tick(loc)
        val now = System.currentTimeMillis()
        if (now - lastWeatherFetch > 600_000) { fetchWeather(snap.latitude, snap.longitude); lastWeatherFetch = now }
        val hhmm = timeFormat.format(Date())
        val bat = getBatteryLevel()
        val alt = loc.altitude
        val isNavActive = navMgr.isNavigating.value
        val navVal = if (isNavActive) navMgr.maneuver.value else -1
        val ndist = if (isNavActive && navMgr.maneuver.value >= 0) navMgr.distanceToStep.value else null
        val nst = if (isNavActive && navMgr.maneuver.value >= 0) navMgr.streetName.value else null
        val state = NavigationState(
            lat = snap.latitude, lon = snap.longitude, speed = snap.speed, heading = snap.heading,
            time = hhmm, alt = alt, bat = bat, wtmp = weatherTemp, wrain = weatherRain,
            nav = navVal, ndist = ndist, nst = nst
        )
        bleMgr.send(state.toJson().toByteArray())
    }

    private fun getBatteryLevel(): Double? {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return null
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return if (level >= 0) level / 100.0 else null
    }

    private suspend fun fetchWeather(lat: Double, lon: Double) {
        try {
            val url = URL("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,precipitation_probability&timezone=auto&forecast_days=1")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 5000; conn.readTimeout = 5000
            try {
                val reader = BufferedReader(InputStreamReader(conn.inputStream))
                val response = reader.readText(); reader.close()
                weatherTemp = Regex("\"temperature_2m\"\\s*:\\s*([\\d.\\-]+)").find(response)?.groupValues?.get(1)?.toDoubleOrNull()
                weatherRain = Regex("\"precipitation_probability\"\\s*:\\s*(\\d+)").find(response)?.groupValues?.get(1)?.toIntOrNull()
            } finally { conn.disconnect() }
        } catch (_: Exception) {}
    }

    fun onDestroy() { stop(); scope.cancel() }
}
