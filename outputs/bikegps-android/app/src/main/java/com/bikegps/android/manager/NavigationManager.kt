package com.bikegps.android.manager

import android.location.Location
import com.amap.api.maps.model.LatLng
import com.amap.api.services.core.LatLonPoint
import com.amap.api.services.poisearch.PoiSearch
import com.amap.api.services.route.*
import com.amap.api.services.route.RouteSearch.*
import com.bikegps.android.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import android.util.Log
import kotlin.math.roundToInt
import org.json.JSONObject
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.io.OutputStreamWriter

class NavigationManager {
    val isNavigating: StateFlow<Boolean> get() = _isNavigating
    val maneuver: StateFlow<Int> get() = _maneuver
    val distanceToStep: StateFlow<Int> get() = _distanceToStep
    val stepInstruction: StateFlow<String> get() = _stepInstruction
    val streetName: StateFlow<String> get() = _streetName
    val isRerouting: StateFlow<Boolean> get() = _isRerouting
    val errorMessage: StateFlow<String?> get() = _errorMessage
    val routePath: StateFlow<List<LatLng>> get() = _routePath
    val destinationLatLng: StateFlow<LatLng?> get() = _destinationLatLng
    val destinationName: StateFlow<String> get() = _destinationName
    val searchResults: StateFlow<List<SearchResult>> get() = _searchResults
    val isSearching: StateFlow<Boolean> get() = _isSearching
    val recentDestinations: StateFlow<List<RecentDestination>> get() = _recentDestinations
    val transportMode: StateFlow<TransportMode> get() = _transportMode
    val routeOptions: StateFlow<List<RouteOption>> get() = _routeOptions
    val selectedRouteIndex: StateFlow<Int> get() = _selectedRouteIndex
    val totalDistance: StateFlow<Int> get() = _totalDistance
    val totalDuration: StateFlow<Int> get() = _totalDuration

    enum class TransportMode { DRIVE, CYCLE }

    data class RouteOption(
        val index: Int, val distance: Int, val duration: Int,
        val polyline: List<LatLng>, val label: String, val isSelected: Boolean = false
    )
    data class SearchResult(val name: String, val address: String, val latitude: Double, val longitude: Double)

    private val _isNavigating = MutableStateFlow(false)
    private val _maneuver = MutableStateFlow(-1)
    private val _distanceToStep = MutableStateFlow(0)
    private val _stepInstruction = MutableStateFlow("")
    private val _streetName = MutableStateFlow("")
    private val _isRerouting = MutableStateFlow(false)
    private val _errorMessage = MutableStateFlow<String?>(null)
    private val _routePath = MutableStateFlow<List<LatLng>>(emptyList())
    private val _destinationLatLng = MutableStateFlow<LatLng?>(null)
    private val _destinationName = MutableStateFlow("")
    private val _searchResults = MutableStateFlow<List<SearchResult>>(emptyList())
    private val _isSearching = MutableStateFlow(false)
    private val _recentDestinations = MutableStateFlow<List<RecentDestination>>(emptyList())
    private val _transportMode = MutableStateFlow(TransportMode.CYCLE)
    private val _routeOptions = MutableStateFlow<List<RouteOption>>(emptyList())
    private val _selectedRouteIndex = MutableStateFlow(0)
    private val _totalDistance = MutableStateFlow(0)
    private val _totalDuration = MutableStateFlow(0)

    private var routePaths: List<List<LatLonPoint>> = emptyList()
    private var routeInstructions: List<String> = emptyList()
    private var routeActions: List<String> = emptyList()
    private var routeDistances: List<Float> = emptyList()
    private var stepIndex = 0
    private var destinationLat = 0.0; private var destinationLon = 0.0
    private var offRouteCount = 0
    private val offRouteMetres = 80.0; private val offRouteTrigger = 5
    private var pendingFromLat = 0.0; private var pendingFromLon = 0.0
    private var pendingToLat = 0.0; private var pendingToLon = 0.0
    private var pendingDestName = ""
    private var lastContext: android.content.Context? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val webApiKey = "368795d9eeb237185a88983b2ee77e2d"

    fun setTransportMode(mode: TransportMode, context: android.content.Context? = null) {
        _transportMode.value = mode
        if (pendingDestName.isNotEmpty() && !_isNavigating.value && context != null) {
            calculateRoutes(context, pendingFromLat, pendingFromLon, pendingToLat, pendingToLon, pendingDestName)
        }
    }

    fun searchWithContext(context: android.content.Context, query: String, city: String? = null, currentLat: Double? = null, currentLon: Double? = null) {
        val q = query.trim(); if (q.isEmpty()) return
        _isSearching.value = true; _errorMessage.value = null
        scope.launch {
            try {
                val search = PoiSearch(context, PoiSearch.Query(q, "", city ?: ""))
                val result = search.searchPOI()
                val results = result?.pois?.map { poi ->
                    SearchResult(poi.title, poi.snippet, poi.latLonPoint.latitude, poi.latLonPoint.longitude)
                } ?: emptyList()
                // Sort by distance from current location
                val sortedResults = if (currentLat != null && currentLon != null) {
                    val isSpecific = q.length >= 4  // 4+ chars = specific query
                    if (isSpecific) {
                        // For specific queries: exact match first, then nearby
                        results.sortedByDescending { r ->
                            val nameMatch = r.name.contains(q, ignoreCase = true)
                            val dist = FloatArray(1)
                            android.location.Location.distanceBetween(currentLat, currentLon, r.latitude, r.longitude, dist)
                            val score = if (nameMatch) 1000000.0 else 0.0
                            score - dist[0]
                        }
                    } else {
                        // For vague queries: nearby first
                        results.sortedBy { r ->
                            val dist = FloatArray(1)
                            android.location.Location.distanceBetween(currentLat, currentLon, r.latitude, r.longitude, dist)
                            dist[0]
                        }
                    }
                } else results
                _searchResults.value = sortedResults
            } catch (e: com.amap.api.services.core.AMapException) {
                Log.e("BikeGPS", "搜索失败: " + e.errorMessage + " (code: " + e.errorCode + ")")
                _errorMessage.value = "搜索失败: " + e.errorMessage
                _searchResults.value = emptyList()
            } catch (e: Exception) {
                _errorMessage.value = "搜索失败"
                _searchResults.value = emptyList()
            }
            _isSearching.value = false
        }
    }

    fun calculateRoutes(
    context: android.content.Context?,
    fromLat: Double, fromLon: Double,
    toLat: Double, toLon: Double,
    destName: String = "目的地"
) {
    _errorMessage.value = null; _routeOptions.value = emptyList(); _selectedRouteIndex.value = 0
    pendingFromLat = fromLat; pendingFromLon = fromLon; pendingToLat = toLat; pendingToLon = toLon; pendingDestName = destName
    _destinationLatLng.value = LatLng(toLat, toLon); _destinationName.value = destName
    lastContext = context
    if (context == null) return

    val from = LatLonPoint(fromLat, fromLon); val to = LatLonPoint(toLat, toLon)
    val routeSearch = RouteSearch(context)

    when (_transportMode.value) {
        TransportMode.DRIVE -> {
            val query = DriveRouteQuery(FromAndTo(from, to), DRIVING_SINGLE_DEFAULT, emptyList(), emptyList(), "")
            routeSearch.setRouteSearchListener(object : RouteSearch.OnRouteSearchListener {
                override fun onDriveRouteSearched(result: DriveRouteResult?, errorCode: Int) {
                    handleDriveResult(result, errorCode, destName, toLat, toLon)
                }
                override fun onWalkRouteSearched(r: WalkRouteResult?, e: Int) {}
                override fun onBusRouteSearched(r: BusRouteResult?, e: Int) {}
                override fun onRideRouteSearched(r: RideRouteResult?, e: Int) {}
            })
            routeSearch.calculateDriveRouteAsyn(query)
        }
        TransportMode.CYCLE -> {
            calculateRideRouteWithFallback(context, fromLat, fromLon, toLat, toLon, destName)
        }
    }
}
    private fun handleDriveResult(result: DriveRouteResult?, errorCode: Int, destName: String, toLat: Double, toLon: Double) {
        if (errorCode != 1000 || result == null || result.paths.isEmpty()) {
            _errorMessage.value = "路线规划失败 (错误码: $errorCode)"
            return
        }
        val options = mutableListOf<RouteOption>()
        routePaths = emptyList(); routeInstructions = emptyList()
        routeActions = emptyList(); routeDistances = emptyList()
        for ((idx, path) in result.paths.withIndex()) {
            val pts = mutableListOf<LatLng>()
            val steps = path.steps
            for (step in steps) {
                val latLngs = step.polyline
                for (pt in latLngs) {
                    pts.add(LatLng(pt.latitude, pt.longitude))
                }
            }
            if (pts.isEmpty()) continue
            val dist = path.distance.roundToInt()
            val dur = (path.duration / 60f).roundToInt()
            val label = if (dist >= 1000) "${dist / 1000}.${(dist % 1000) / 100}公里 / ${dur}分钟" else "${dist}米 / ${dur}分钟"
            options.add(RouteOption(idx, dist, dur, pts, label, idx == 0))
            if (idx == 0) {
                routePaths = steps.map { s ->
                    s.polyline
                }
                routeInstructions = steps.map { it.instruction.ifEmpty { "继续直行" } }
                routeActions = steps.map { it.action }
                routeDistances = steps.map { it.distance.toFloat() }
            }
        }
        if (options.isEmpty()) {
            _errorMessage.value = "路线规划失败: 无法解析路径数据"
            return
        }
        _routeOptions.value = options
        _selectedRouteIndex.value = 0
        _routePath.value = options[0].polyline
        _totalDistance.value = options[0].distance
        _totalDuration.value = options[0].duration
        _destinationLatLng.value = LatLng(toLat, toLon)
        _destinationName.value = destName
    }

    private fun calculateRideRouteWithFallback(
        context: android.content.Context,
        fromLat: Double, fromLon: Double,
        toLat: Double, toLon: Double,
        destName: String
    ) {
        _errorMessage.value = null; _routeOptions.value = emptyList(); _selectedRouteIndex.value = 0
        _destinationLatLng.value = LatLng(toLat, toLon); _destinationName.value = destName
        val from = LatLonPoint(fromLat, fromLon); val to = LatLonPoint(toLat, toLon)
        val routeSearch = RouteSearch(context)
        val query = RideRouteQuery(FromAndTo(from, to), RouteSearch.RIDING_RECOMMEND)
        routeSearch.setRouteSearchListener(object : RouteSearch.OnRouteSearchListener {
            override fun onRideRouteSearched(result: RideRouteResult?, errorCode: Int) {
                Log.i("BikeGPS", "RideRoute callback - errorCode=$errorCode, paths=${result?.paths?.size ?: 0}")
                if (errorCode == 1000 && result != null && result.paths.isNotEmpty()) {
                    handleRideRouteResult(result, destName, toLat, toLon)
                } else if (errorCode == 30007 || errorCode == 3001 || errorCode == 2003) {
                    // RideRoute not available - fallback to DriveRoute with no-highway strategy
                    Log.w("BikeGPS", "RideRoute failed ($errorCode), trying DriveRoute no-highway")
                    val dq = DriveRouteQuery(FromAndTo(from, to), RouteSearch.DRIVING_SINGLE_NO_HIGHWAY, emptyList(), emptyList(), "")
                    routeSearch.setRouteSearchListener(object : RouteSearch.OnRouteSearchListener {
                        override fun onDriveRouteSearched(dr: DriveRouteResult?, de: Int) {
                            Log.i("BikeGPS", "DriveRoute(no-highway) callback - errorCode=$de")
                            if (de == 1000 && dr != null && dr.paths.isNotEmpty()) {
                                handleDriveResult(dr, 1000, destName, toLat, toLon)
                                _routeOptions.value = _routeOptions.value.map { it.copy(label = "骑行(驾车-不走高速)") }
                            } else {
                                _errorMessage.value = "骑行路线规划失败 (错误码: $de)"
                            }
                        }
                        override fun onWalkRouteSearched(r: WalkRouteResult?, e: Int) {}
                        override fun onBusRouteSearched(r: BusRouteResult?, e: Int) {}
                        override fun onRideRouteSearched(r: RideRouteResult?, e: Int) {}
                    })
                    routeSearch.calculateDriveRouteAsyn(dq)
                } else {
                    _errorMessage.value = "骑行路线规划失败 (错误码: $errorCode)"
                }
            }
            override fun onWalkRouteSearched(r: WalkRouteResult?, e: Int) {}
            override fun onDriveRouteSearched(r: DriveRouteResult?, e: Int) {}
            override fun onBusRouteSearched(r: BusRouteResult?, e: Int) {}
        })
        routeSearch.calculateRideRouteAsyn(query)
    }

    private fun handleRideRouteResult(result: RideRouteResult, destName: String, toLat: Double, toLon: Double) {
        val options = mutableListOf<RouteOption>()
        routePaths = emptyList(); routeInstructions = emptyList()
        routeActions = emptyList(); routeDistances = emptyList()
        for ((idx, path) in result.paths.withIndex()) {
            val pts = mutableListOf<LatLng>()
            val steps = path.steps
            for (step in steps) {
                for (pt in step.polyline) {
                    pts.add(LatLng(pt.latitude, pt.longitude))
                }
            }
            if (pts.isEmpty()) continue
            val dist = path.distance.roundToInt()
            val dur = (path.duration / 60f).roundToInt()
            val label = if (dist >= 1000) "${dist / 1000}.${(dist % 1000) / 100}公里 / ${dur}分钟" else "${dist}米 / ${dur}分钟"
            options.add(RouteOption(idx, dist, dur, pts, label, idx == 0))
            if (idx == 0) {
                routePaths = steps.map { it.polyline }
                routeInstructions = steps.map { it.instruction.ifEmpty { "继续直行" } }
                routeActions = steps.map { it.action }
                routeDistances = steps.map { it.distance }
            }
        }
        if (options.isEmpty()) { _errorMessage.value = "骑行路线规划失败: 无路径数据"; return }
        _routeOptions.value = options; _selectedRouteIndex.value = 0
        _routePath.value = options[0].polyline
        _totalDistance.value = options[0].distance; _totalDuration.value = options[0].duration
    }

    private fun handleWalkRouteResult(result: WalkRouteResult, destName: String, toLat: Double, toLon: Double, labelPrefix: String) {
        val options = mutableListOf<RouteOption>()
        routePaths = emptyList(); routeInstructions = emptyList()
        routeActions = emptyList(); routeDistances = emptyList()
        for ((idx, path) in result.paths.withIndex()) {
            val pts = mutableListOf<LatLng>()
            val steps = path.steps
            for (step in steps) {
                for (pt in step.polyline) {
                    pts.add(LatLng(pt.latitude, pt.longitude))
                }
            }
            if (pts.isEmpty()) continue
            val dist = path.distance.roundToInt()
            val dur = (path.duration / 60f).roundToInt()
            val label = if (dist >= 1000) "${dist / 1000}.${(dist % 1000) / 100}公里 / ${dur}分钟" else "${dist}米 / ${dur}分钟"
            options.add(RouteOption(idx, dist, dur, pts, "$labelPrefix $label", idx == 0))
            if (idx == 0) {
                routePaths = steps.map { it.polyline }
                routeInstructions = steps.map { it.instruction.ifEmpty { "继续直行" } }
                routeActions = steps.map { it.action }
                routeDistances = steps.map { it.distance }
            }
        }
        if (options.isEmpty()) { _errorMessage.value = "骑行路线规划失败: 无路径数据"; return }
        _routeOptions.value = options; _selectedRouteIndex.value = 0
        _routePath.value = options[0].polyline
        _totalDistance.value = options[0].distance; _totalDuration.value = options[0].duration
    }



    fun selectRoute(index: Int) {
        val options = _routeOptions.value
        if (index < 0 || index >= options.size) return
        _routeOptions.value = options.mapIndexed { i, opt -> opt.copy(isSelected = i == index) }
        _selectedRouteIndex.value = index; _routePath.value = options[index].polyline
        _totalDistance.value = options[index].distance; _totalDuration.value = options[index].duration
    }

    fun startNavigation() {
        _isNavigating.value = true; _isRerouting.value = false; _maneuver.value = -1
        _distanceToStep.value = 0; stepIndex = 0; offRouteCount = 0
        addRecent(RecentDestination(name = _destinationName.value, lat = _destinationLatLng.value?.latitude ?: 0.0, lon = _destinationLatLng.value?.longitude ?: 0.0))
    }

    fun tick(location: Location) {
        if (!_isNavigating.value || routePaths.isEmpty() || _isRerouting.value) return
        val dist = distanceToRoute(location)
        if (dist > offRouteMetres) {
            offRouteCount++; if (offRouteCount >= offRouteTrigger) { offRouteCount = 0; _isRerouting.value = true; _stepInstruction.value = "正在重新规划路线..."; return }
        } else { offRouteCount = 0 }
        refreshStep(location)
    }

    fun stopNavigation() {
        _isNavigating.value = false; _isRerouting.value = false; _maneuver.value = -1
        _distanceToStep.value = 0; _stepInstruction.value = ""; _streetName.value = ""
        _routePath.value = emptyList(); _destinationLatLng.value = null; _destinationName.value = ""
        _routeOptions.value = emptyList(); _selectedRouteIndex.value = 0
        routePaths = emptyList(); routeInstructions = emptyList(); routeActions = emptyList(); routeDistances = emptyList()
        stepIndex = 0; offRouteCount = 0; pendingDestName = ""
    }

    fun setErrorMessage(msg: String) {
        _errorMessage.value = msg
    }

    fun clearErrorMessage() {
        _errorMessage.value = null
    }

    fun onDestroy() { scope.cancel() }

    private fun refreshStep(location: Location) {
        if (stepIndex >= routePaths.size) { _maneuver.value = 4; _stepInstruction.value = "您已到达目的地"; _streetName.value = "目的地"; _distanceToStep.value = 0; return }
        val pts = routePaths[stepIndex]
        if (pts.isEmpty()) { if (stepIndex + 1 < routePaths.size) { stepIndex++; refreshStep(location) }; return }
        val endPt = pts.last()
        val results = FloatArray(1)
        Location.distanceBetween(location.latitude, location.longitude, endPt.latitude, endPt.longitude, results)
        if (results[0] < 30 && stepIndex + 1 < routePaths.size) { stepIndex++; refreshStep(location); return }
        _distanceToStep.value = maxOf(0, results[0].roundToInt())
        _stepInstruction.value = routeInstructions.getOrElse(stepIndex) { "" }
        val action = routeActions.getOrElse(stepIndex) { "" }
        _maneuver.value = if (action.isNotEmpty()) actionToManeuver(action) else 0
        _streetName.value = (_stepInstruction.value).take(24)
    }

    private fun distanceToRoute(location: Location): Double {
        var minD = Double.MAX_VALUE; val results = FloatArray(1)
        for (pts in routePaths) { for (pt in pts) {
            Location.distanceBetween(location.latitude, location.longitude, pt.latitude, pt.longitude, results)
            if (results[0] < minD) minD = results[0].toDouble()
            if (minD < 30.0) return minD
        }}
        return minD
    }

    private fun addRecent(dest: RecentDestination) {
        val current = _recentDestinations.value.toMutableList()
        current.removeAll { existing -> val r = FloatArray(1); Location.distanceBetween(existing.lat, existing.lon, dest.lat, dest.lon, r); r[0] < 100 }
        current.add(0, dest)
        if (current.size > 8) current.removeAt(current.lastIndex)
        _recentDestinations.value = current
    }
}
