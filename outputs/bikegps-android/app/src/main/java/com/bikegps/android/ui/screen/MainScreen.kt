package com.bikegps.android.ui.screen

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.bikegps.android.manager.*
import com.bikegps.android.ui.component.*
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    locationMgr: LocationManager, bleMgr: BLEManager, navMgr: NavigationManager,
    context: android.content.Context
) {
    val bleStatus by bleMgr.status.collectAsState()
    val isNavigating by navMgr.isNavigating.collectAsState()
    val maneuver by navMgr.maneuver.collectAsState()
    val distanceToStep by navMgr.distanceToStep.collectAsState()
    val stepInstruction by navMgr.stepInstruction.collectAsState()
    val routePath by navMgr.routePath.collectAsState()
    val routeOptions by navMgr.routeOptions.collectAsState()
    val selectedRouteIndex by navMgr.selectedRouteIndex.collectAsState()
    val destLatLng by navMgr.destinationLatLng.collectAsState()
    val destName by navMgr.destinationName.collectAsState()
    val isRerouting by navMgr.isRerouting.collectAsState()
    val errorMsg by navMgr.errorMessage.collectAsState()
    val location by locationMgr.location.collectAsState()
    val transportMode by navMgr.transportMode.collectAsState()
    val totalDistance by navMgr.totalDistance.collectAsState()
    val totalDuration by navMgr.totalDuration.collectAsState()
    val streetName by navMgr.streetName.collectAsState()

    var showSearchSheet by remember { mutableStateOf(true) }
    var amapLocation by remember { mutableStateOf<com.amap.api.maps.model.LatLng?>(null) }
    var showDeviceSheet by remember { mutableStateOf(false) }
    var mapSelectedPoint by remember { mutableStateOf<com.amap.api.maps.model.LatLng?>(null) }

    var hasLocationPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms ->
        hasLocationPermission = perms[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val bleOk = (perms[Manifest.permission.BLUETOOTH_SCAN] == true)
        if (hasLocationPermission) locationMgr.start()
        if (bleOk) bleMgr.startScan()
    }

    LaunchedEffect(Unit) {
        val needed = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            needed.add(Manifest.permission.BLUETOOTH_SCAN); needed.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        val missing = needed.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) permLauncher.launch(missing.toTypedArray())
        else { locationMgr.start(); bleMgr.startScan() }
    }

    LaunchedEffect(isRerouting) {
        if (isRerouting && location != null && destLatLng != null) {
            navMgr.calculateRoutes(context, location!!.latitude, location!!.longitude,
                destLatLng!!.latitude, destLatLng!!.longitude, destName)
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(errorMsg) { errorMsg?.let { snackbarHostState.showSnackbar(it); navMgr.clearErrorMessage() } }

    val multiRoutePaths = remember(routeOptions) {
        routeOptions.map { it.polyline }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SmallFloatingActionButton(
                    onClick = { },
                    containerColor = Color.White,
                    modifier = Modifier.shadow(4.dp, CircleShape)
                ) {
                    Icon(Icons.Default.MyLocation, contentDescription = "我的位置", modifier = Modifier.size(20.dp))
                }
                SmallFloatingActionButton(
                    onClick = { showDeviceSheet = !showDeviceSheet },
                    containerColor = if (bleStatus is BLEManager.ConnectionStatus.Connected) Color(0xFF34A853) else Color.White,
                    modifier = Modifier.shadow(4.dp, CircleShape)
                ) {
                    Icon(Icons.Default.Bluetooth, contentDescription = "蓝牙",
                        tint = if (bleStatus is BLEManager.ConnectionStatus.Connected) Color.White else Color(0xFF5F6368),
                        modifier = Modifier.size(20.dp))
                }
                if (isNavigating) {
                    FloatingActionButton(
                        onClick = { navMgr.stopNavigation() },
                        containerColor = Color(0xFFD93025),
                        modifier = Modifier.shadow(8.dp, CircleShape)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "退出导航", tint = Color.White)
                    }
                } else if (routeOptions.isNotEmpty()) {
                    FloatingActionButton(
                        onClick = {
                            navMgr.startNavigation()
                            showSearchSheet = false
                            val sLat = amapLocation?.latitude ?: location?.latitude
                            val sLon = amapLocation?.longitude ?: location?.longitude
                            if (sLat != null && sLon != null && destLatLng != null) {
                                navMgr.calculateRoutes(context, sLat, sLon,
                                    destLatLng!!.latitude, destLatLng!!.longitude, destName)
                            }
                        },
                        containerColor = Color(0xFF1A73E8),
                        modifier = Modifier.shadow(8.dp, CircleShape)
                    ) {
                        Icon(Icons.Default.Navigation, contentDescription = "开始导航", tint = Color.White, modifier = Modifier.size(28.dp))
                    }
                } else {
                    FloatingActionButton(
                        onClick = { showSearchSheet = true },
                        containerColor = Color(0xFF1A73E8),
                        modifier = Modifier.shadow(8.dp, CircleShape)
                    ) {
                        Icon(Icons.Default.Search, contentDescription = "搜索", tint = Color.White)
                    }
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            AMapComposeView(
                modifier = Modifier.fillMaxSize(),
                routePaths = multiRoutePaths,
                selectedRouteIndex = selectedRouteIndex,
                destination = destLatLng,
                startLocation = amapLocation ?: location?.let { com.amap.api.maps.model.LatLng(it.latitude, it.longitude) },
                isNavigating = isNavigating,
                selectedPoint = mapSelectedPoint,
                onAmapLocationChanged = { latLng -> amapLocation = latLng },
                onMapLongClick = { latLng ->
                    mapSelectedPoint = latLng
                    val sLat = amapLocation?.latitude ?: location?.latitude
                    val sLon = amapLocation?.longitude ?: location?.longitude
                    if (sLat != null && sLon != null) {
                        navMgr.calculateRoutes(context, sLat, sLon,
                            latLng.latitude, latLng.longitude, "地图选点")
                    }
                }
            )

            if (isNavigating) {
                NavigationGuidanceBar(
                    destName = destName,
                    maneuver = maneuver,
                    distanceToStep = distanceToStep,
                    stepInstruction = stepInstruction,
                    streetName = streetName,
                    isRerouting = isRerouting,
                    totalDistance = totalDistance,
                    totalDuration = totalDuration,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp, start = 12.dp, end = 12.dp)
                )
            } else {
                // Search bar at top
                Surface(
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp, start = 12.dp, end = 12.dp)
                        .fillMaxWidth().shadow(4.dp, RoundedCornerShape(12.dp)),
                    shape = RoundedCornerShape(12.dp),
                    color = Color.White,
                    onClick = { showSearchSheet = true }
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Search, contentDescription = null, tint = Color(0xFF9AA0A6))
                        Spacer(Modifier.width(12.dp))
                        val hint = if (transportMode == NavigationManager.TransportMode.CYCLE) "搜索骑行目的地" else "搜索驾车目的地"
                        Text(hint, color = Color(0xFF9AA0A6), fontSize = 15.sp)
                    }
                }

                // Floating mode toggle
                if (!isNavigating) {
                    Surface(
                        modifier = Modifier.align(Alignment.TopStart).padding(top = 64.dp, start = 12.dp)
                            .shadow(4.dp, RoundedCornerShape(20.dp)),
                        shape = RoundedCornerShape(20.dp),
                        color = Color.White,
                        onClick = {
                            val newMode = if (transportMode == NavigationManager.TransportMode.CYCLE)
                                NavigationManager.TransportMode.DRIVE else NavigationManager.TransportMode.CYCLE
                            navMgr.setTransportMode(newMode, context)
                        }
                    ) {
                        Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (transportMode == NavigationManager.TransportMode.CYCLE) Icons.Filled.DirectionsBike else Icons.Filled.DirectionsCar,
                                contentDescription = null, modifier = Modifier.size(20.dp),
                                tint = Color(0xFF5F7D9E))
                            Spacer(Modifier.width(6.dp))
                            Text(if (transportMode == NavigationManager.TransportMode.CYCLE) "骑行" else "驾车",
                                fontSize = 14.sp, color = Color(0xFF5F7D9E), fontWeight = FontWeight.Medium)
                            Spacer(Modifier.width(4.dp))
                            Icon(Icons.Default.SwapHoriz, contentDescription = "切换", modifier = Modifier.size(16.dp),
                                tint = Color(0xFF5F7D9E))
                        }
                    }
                }

                // Route option preview at bottom
                if (routeOptions.isNotEmpty()) {
                    RoutePreviewBar(
                        routeOptions = routeOptions,
                        selectedIndex = selectedRouteIndex,
                        totalDistance = totalDistance,
                        totalDuration = totalDuration,
                        transportMode = transportMode,
                        onSelect = { navMgr.selectRoute(it) },
                        onStart = {
                            navMgr.startNavigation()
                            showSearchSheet = false
                            val sLat = amapLocation?.latitude ?: location?.latitude
                            val sLon = amapLocation?.longitude ?: location?.longitude
                            if (sLat != null && sLon != null && destLatLng != null) {
                                navMgr.calculateRoutes(context, sLat, sLon,
                                    destLatLng!!.latitude, destLatLng!!.longitude, destName)
                            }
                        },
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 80.dp)
                    )
                }
            }

            // BLE connection hint
            if (bleStatus is BLEManager.ConnectionStatus.Disconnected || bleStatus is BLEManager.ConnectionStatus.Failed) {
                Surface(
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 88.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xCC000000)
                ) {
                    Text("未连接 ESP32，点击蓝牙图标连接",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        color = Color.White, fontSize = 12.sp)
                }
            }
        }
    }

    // Search bottom sheet
    if (showSearchSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSearchSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            SearchSheetContent(
                navMgr = navMgr,
                currentLocation = location,
                amapLocation = amapLocation,
                onStartNavigation = { lat, lon, name ->
                    val sLat = amapLocation?.latitude ?: location?.latitude
                    val sLon = amapLocation?.longitude ?: location?.longitude
                    if (sLat != null && sLon != null) {
                        navMgr.calculateRoutes(context, sLat, sLon, lat, lon, name)
                    }
                },
                onDismiss = { showSearchSheet = false }
            )
        }
    }

    // Device scan sheet
    if (showDeviceSheet) {
        ModalBottomSheet(onDismissRequest = { showDeviceSheet = false }) {
            DeviceScanSheetContent(bleMgr = bleMgr, onDismiss = { showDeviceSheet = false })
        }
    }
}


@Composable
private fun NavigationGuidanceBar(
    destName: String, maneuver: Int, distanceToStep: Int,
    stepInstruction: String, streetName: String, isRerouting: Boolean,
    totalDistance: Int, totalDuration: Int,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth().shadow(8.dp, RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        color = Color(0xF5FFFFFF)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            if (isRerouting) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = Color(0xFFD93025))
                    Spacer(Modifier.width(8.dp))
                    Text("正在重新规划路线...", color = Color(0xFFD93025), fontWeight = FontWeight.Medium, fontSize = 14.sp)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                val icon = when (maneuver) {
                    4 -> Icons.Default.Room
                    2 -> Icons.Default.TurnRight
                    1 -> Icons.Default.TurnLeft
                    else -> Icons.Default.North
                }
                Icon(icon, contentDescription = null, tint = Color(0xFF1A73E8), modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(stepInstruction, fontWeight = FontWeight.Medium, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val distText = if (distanceToStep >= 1000) {
                        "${distanceToStep / 1000}.${(distanceToStep % 1000) / 100} 公里"
                    } else "$distanceToStep 米"
                    Text("剩余 $distText", fontSize = 13.sp, color = Color.Gray)
                }
            }
            if (totalDistance > 0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("全程 ", fontSize = 13.sp, color = Color.Gray)
                    val totalDist = if (totalDistance >= 1000) "${totalDistance / 1000}.${(totalDistance % 1000) / 100} 公里" else "${totalDistance} 米"
                    Text(totalDist, fontSize = 13.sp, color = Color.Gray)
                    Spacer(Modifier.width(16.dp))
                    Text("预计 ${totalDuration / 60}:${String.format("%02d", totalDuration % 60)}", fontSize = 13.sp, color = Color.Gray)
                }
            }
        }
    }
}

@Composable
private fun RoutePreviewBar(
    routeOptions: List<NavigationManager.RouteOption>,
    selectedIndex: Int, totalDistance: Int, totalDuration: Int,
    transportMode: NavigationManager.TransportMode,
    onSelect: (Int) -> Unit, onStart: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.padding(horizontal = 12.dp).fillMaxWidth().shadow(8.dp, RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        color = Color(0xF5FFFFFF)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (transportMode == NavigationManager.TransportMode.CYCLE) Icons.Filled.DirectionsBike else Icons.Filled.DirectionsCar,
                        contentDescription = null, tint = Color(0xFF1A73E8), modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    val totalDist = if (totalDistance >= 1000) "${totalDistance / 1000}.${(totalDistance % 1000) / 100} 公里" else "${totalDistance} 米"
                    Text(totalDist, fontWeight = FontWeight.Medium, fontSize = 15.sp)
                    Spacer(Modifier.width(8.dp))
                    Text("${totalDuration / 60} 分钟", fontSize = 13.sp, color = Color.Gray)
                }
                Surface(
                    onClick = onStart,
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFF1A73E8)
                ) {
                    Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Navigation, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("导航", fontSize = 15.sp)
                    }
                }
            }
            if (routeOptions.size > 1) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((i, opt) in routeOptions.withIndex()) {
                        val isSel = i == selectedIndex
                        val dotColors = listOf(Color(0xFF1A73E8), Color(0xFF34A853), Color(0xFFFBBC04))
                        val dotColor = if (i < dotColors.size) dotColors[i] else Color.Gray
                        Surface(
                            modifier = Modifier.weight(1f).clickable { onSelect(i) },
                            shape = RoundedCornerShape(8.dp),
                            color = if (isSel) dotColor.copy(alpha = 0.1f) else Color(0xFFF5F5F5),
                            border = if (isSel) androidx.compose.foundation.BorderStroke(1.5.dp, dotColor) else null
                        ) {
                            Column(modifier = Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(opt.label, fontSize = 12.sp, fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSel) dotColor else Color.Gray)
                                val d = opt.distance / 1000.0
                                Text(String.format("%.1fkm", d), fontSize = 11.sp, color = Color.Gray)
                            }
                        }
                    }
                }
            }
        }
    }
}
