package com.bikegps.android.model

import com.google.gson.annotations.SerializedName

/**
 * GPS + telemetry + navigation snapshot sent to ESP32 via BLE every ~1s.
 * Matches the JSON schema expected by the bikegps ESP32 firmware.
 *
 * Maneuver values (nav):
 *   -1 = off (no active route)
 *    0 = straight / continue
 *    1 = turn right
 *    2 = turn left
 *    3 = U-turn
 *    4 = arrived
 */
data class NavigationState(
    val lat: Double,
    val lon: Double,
    val speed: Double,
    val heading: Double,
    val time: String,
    val alt: Double,
    val bat: Double? = null,
    val wtmp: Double? = null,
    val wrain: Int? = null,
    val nav: Int = -1,
    val ndist: Int? = null,
    val nst: String? = null
) {
    fun toJson(): String = buildString {
        append("{\"lat\":")
        append(String.format("%.6f", lat))
        append(",\"lon\":")
        append(String.format("%.6f", lon))
        append(",\"speed\":")
        append(String.format("%.1f", speed))
        append(",\"heading\":")
        append(String.format("%.1f", heading))
        append(",\"time\":\"")
        append(time)
        append("\",\"alt\":")
        append(String.format("%.1f", alt))
        bat?.let { append(String.format(",\"bat\":%.2f", it)) }
        wtmp?.let { append(String.format(",\"wtmp\":%.1f", it)) }
        wrain?.let { append(",\"wrain\":$it") }
        append(",\"nav\":$nav")
        if (nav >= 0) {
            ndist?.let { append(",\"ndist\":$it") }
            nst?.let {
                val safe = it.replace("\"", "'").replace("\\", "")
                append(",\"nst\":\"${safe.take(24)}\"")
            }
        }
        append("}")
    }
}

/**
 * A recent destination persisted locally.
 */
data class RecentDestination(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val subtitle: String = "",
    val lat: Double,
    val lon: Double,
    val timestamp: Long = System.currentTimeMillis()
)
