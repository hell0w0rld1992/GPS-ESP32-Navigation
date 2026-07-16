package com.bikegps.android.ui.component

import android.graphics.Color
import android.os.Bundle
import android.util.Log
import androidx.compose.runtime.*
import kotlinx.coroutines.delay
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.amap.api.maps.*
import com.amap.api.maps.model.*

@Composable
fun AMapComposeView(
    modifier: Modifier = Modifier,
    routePaths: List<List<LatLng>> = emptyList(),
    selectedRouteIndex: Int = 0,
    destination: LatLng? = null,
    startLocation: LatLng? = null,
    isNavigating: Boolean = false,
    selectedPoint: LatLng? = null,
    onMapLongClick: ((LatLng) -> Unit)? = null,
    onMapReady: (AMap?) -> Unit = {},
    onAmapLocationChanged: ((com.amap.api.maps.model.LatLng) -> Unit)? = null
) {
    var map by remember { mutableStateOf<AMap?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current
    var mapViewRef = remember { mutableStateOf<MapView?>(null) }

    val routeColors = listOf(
        Color.parseColor("#1A73E8"),   // Blue - selected
        Color.parseColor("#34A853"),   // Green - alt 1
        Color.parseColor("#FBBC04"),   // Yellow - alt 2
        Color.parseColor("#EA4335"),   // Red - alt 3
    )

    LaunchedEffect(routePaths, selectedRouteIndex, destination) {
        val aMap = map ?: return@LaunchedEffect
        try {
            aMap.clear()
            // Draw all route paths
            for ((i, path) in routePaths.withIndex()) {
                if (path.isEmpty()) continue
                val color = if (i == selectedRouteIndex) {
                    Color.parseColor("#1A73E8")
                } else {
                    Color.parseColor("#90A0A0A0")
                }
                val width = if (i == selectedRouteIndex) 12f else 6f
                aMap.addPolyline(
                    PolylineOptions().addAll(path)
                        .color(color).width(width)
                )
            }
            // Draw route start marker (first point of selected route)
            if (routePaths.isNotEmpty() && routePaths[selectedRouteIndex].isNotEmpty()) {
                val firstPt = routePaths[selectedRouteIndex].first()
                aMap.addMarker(MarkerOptions()
                    .position(firstPt).title("起点")
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN))
                    .draggable(false))
            }
            // Draw destination marker
            destination?.let {
                aMap.addMarker(MarkerOptions()
                    .position(it).title("目的地")
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_BLUE))
                    .draggable(true))
            }
            // Draw selected point (map picker)
            if (selectedPoint != null) {
                aMap.addMarker(MarkerOptions()
                    .position(selectedPoint)
                    .title("选取位置")
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_ORANGE))
                    .draggable(true))
            }
            // Draw start marker
            startLocation?.let {
                aMap.addMarker(MarkerOptions()
                    .position(it).title("我的位置")
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN)))
            }
            // Fit camera to show entire route
            if (routePaths.isNotEmpty() && routePaths[selectedRouteIndex].isNotEmpty()) {
                val path = routePaths[selectedRouteIndex]
                val builder = LatLngBounds.builder()
                for (pt in path) builder.include(pt)
                destination?.let { builder.include(it) }
                startLocation?.let { builder.include(it) }
                val bounds = builder.build()
                aMap.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 50), 500, null)
            } else if (destination != null) {
                aMap.animateCamera(CameraUpdateFactory.newLatLngZoom(destination, 15f), 500, null)
            }
        } catch (e: Exception) {
            Log.e("AMapView", "路线绘制失败", e)
        }
    }

    // Zoom in once when navigation starts (not on every location update)
    LaunchedEffect(isNavigating) {
        if (!isNavigating) return@LaunchedEffect
        // Retry up to 5 times to get a location fix
        for (i in 1..5) {
            delay(800)
            val aMap = map ?: continue
            val loc = startLocation ?: continue
            aMap.animateCamera(CameraUpdateFactory.newLatLngZoom(loc, 17f), 800, null)
            break
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            val mv = mapViewRef.value ?: return@LifecycleEventObserver
            try {
                when (event) {
                    Lifecycle.Event.ON_RESUME -> mv.onResume()
                    Lifecycle.Event.ON_PAUSE -> mv.onPause()
                    Lifecycle.Event.ON_DESTROY -> { mv.onDestroy(); try { mv.map?.setMyLocationEnabled(false) } catch (_: Exception) {} }
                    else -> {}
                }
            } catch (e: Exception) { Log.e("AMapView", "生命周期异常", e) }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            try {
                val mv = MapView(ctx)
                mv.onCreate(Bundle())
                mapViewRef.value = mv
                val aMap = mv.map
                if (aMap != null) {
                    map = aMap
                    aMap.uiSettings.isZoomControlsEnabled = false
                    aMap.uiSettings.isMyLocationButtonEnabled = false
                    aMap.uiSettings.isCompassEnabled = true
                    aMap.setMyLocationEnabled(true)
                    aMap.myLocationStyle = MyLocationStyle()
                        .myLocationType(MyLocationStyle.LOCATION_TYPE_LOCATION_ROTATE)
                        .interval(2000)
                }
                onMapReady(aMap)
                // Add my location change listener (syncs AMap GPS with app)
                aMap.setOnMyLocationChangeListener { loc ->
                    if (loc != null) {
                        onAmapLocationChanged?.invoke(com.amap.api.maps.model.LatLng(loc.latitude, loc.longitude))
                    }
                }
                // Add long-press listener
                onMapLongClick?.let { callback ->
                    aMap.setOnMapLongClickListener { latLng ->
                        callback(latLng)
                    }
                }
                mv
            } catch (e: Exception) {
                Log.e("AMapView", "MapView 创建失败", e)
                android.widget.FrameLayout(ctx)
            }
        },
        update = {}
    )
}
