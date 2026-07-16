package com.bikegps.android.model

/**
 * Represents one step of a driving route from AMap RouteSearch.
 */
data class RouteStep(
    val instruction: String,        // e.g. "沿建国路行驶500米"
    val action: String,             // e.g. "左转", "右转", "直行", "掉头"
    val distance: Int,              // metres
    val polyline: List<LatLng>      // shape points for this step
)

data class LatLng(
    val latitude: Double,
    val longitude: Double
)

/**
 * Parses AMap polyline string "lat,lng;lat,lng;..." into List<LatLng>.
 */
fun parsePolyline(polyline: String): List<LatLng> {
    return polyline.split(";").mapNotNull { segment ->
        val parts = segment.split(",")
        if (parts.size >= 2) {
            val lat = parts[0].toDoubleOrNull()
            val lng = parts[1].toDoubleOrNull()
            if (lat != null && lng != null) LatLng(lat, lng) else null
        } else null
    }
}

/**
 * Converts AMap action string to nav maneuver int:
 *   -1 = off, 0 = straight, 1 = right, 2 = left, 3 = U-turn, 4 = arrived
 */
fun actionToManeuver(action: String): Int = when {
    action.contains("掉头") -> 3
    action.contains("右转") -> 1
    action.contains("左转") -> 2
    action.contains("直行") -> 0
    action.contains("到达") || action.contains("终点") -> 4
    else -> 0
}

fun instructionToManeuver(instruction: String): Int = when {
    instruction.contains("掉头") || instruction.contains("调头") -> 3
    instruction.contains("右转") -> 1
    instruction.contains("左转") -> 2
    instruction.contains("直行") -> 0
    else -> 0
}
