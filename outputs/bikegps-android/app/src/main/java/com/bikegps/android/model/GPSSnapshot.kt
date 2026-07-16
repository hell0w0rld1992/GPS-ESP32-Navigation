package com.bikegps.android.model

/**
 * Snapshot of location + heading at one instant.
 */
data class GPSSnapshot(
    val latitude: Double,
    val longitude: Double,
    val speed: Double,      // km/h
    val heading: Double,    // true heading 0-360
    val timestamp: Long     // System.currentTimeMillis()
)
