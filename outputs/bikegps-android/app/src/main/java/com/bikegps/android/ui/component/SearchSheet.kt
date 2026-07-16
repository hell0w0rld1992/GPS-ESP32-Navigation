package com.bikegps.android.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bikegps.android.manager.NavigationManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchSheetContent(
    navMgr: NavigationManager,
    currentLocation: android.location.Location?,
    amapLocation: com.amap.api.maps.model.LatLng? = null,
    onStartNavigation: (Double, Double, String) -> Unit,

    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    val searchResults by navMgr.searchResults.collectAsState()
    val isSearching by navMgr.isSearching.collectAsState()
    val recents by navMgr.recentDestinations.collectAsState()
    val routeOptions by navMgr.routeOptions.collectAsState()
    val selectedRouteIndex by navMgr.selectedRouteIndex.collectAsState()
    val transportMode by navMgr.transportMode.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    var showRoutes by remember { mutableStateOf(false) }
    
    // Use AMap blue dot location as primary, Android GPS as fallback
    val startLat = amapLocation?.latitude ?: currentLocation?.latitude
    val startLon = amapLocation?.longitude ?: currentLocation?.longitude
    
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Column(modifier = Modifier.fillMaxWidth()) {
        // Search bar
        OutlinedTextField(
            value = query,
            onValueChange = {
                query = it
                if (it.length >= 2) navMgr.searchWithContext(context, it, currentLat = currentLocation?.latitude, currentLon = currentLocation?.longitude)
                showRoutes = false
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).focusRequester(focusRequester),
            placeholder = { Text("搜索目的地") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = ""; showRoutes = false }) {
                        Icon(Icons.Default.Clear, contentDescription = "清除")
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(12.dp)
        )

        if (isSearching) {
            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (showRoutes && routeOptions.isNotEmpty()) {
            // Route options view
            ModeToggle(mode = transportMode, onModeChange = { navMgr.setTransportMode(it, context) })
            Spacer(Modifier.height(4.dp))
            Text("请选择路线", style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp))
            LazyColumn(modifier = Modifier.heightIn(max = 280.dp).padding(horizontal = 16.dp)) {
                items(routeOptions) { option ->
                    val isSelected = option.index == selectedRouteIndex
                    val bgColor = if (isSelected) Color(0xFFE8F0FE) else Color.White
                    val borderColor = if (isSelected) Color(0xFF1A73E8) else Color(0xFFE0E0E0)
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                            .clickable { navMgr.selectRoute(option.index) },
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = bgColor),
                        border = if (isSelected) androidx.compose.foundation.BorderStroke(2.dp, borderColor) else null
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Route indicator
                            val dotColors = listOf(Color(0xFF1A73E8), Color(0xFF34A853), Color(0xFFFBBC04))
                            val dotColor = if (option.index < dotColors.size) dotColors[option.index] else Color.Gray
                            Box(
                                modifier = Modifier.size(40.dp)
                                    .clip(RoundedCornerShape(20.dp))
                                    .background(dotColor),
                                contentAlignment = Alignment.Center
                            ) { Text("${option.index + 1}", color = Color.White, fontWeight = FontWeight.Bold) }
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(option.label, fontWeight = FontWeight.Medium, fontSize = 15.sp)
                                Row {
                                    val distKm = option.distance / 1000.0
                                    Text(String.format("¥.1f 公里", distKm), fontSize = 13.sp, color = Color.Gray)
                                    Spacer(Modifier.width(8.dp))
                                    val mins = option.duration / 60
                                    Text(String.format("%d 分钟", mins), fontSize = 13.sp, color = Color.Gray)
                                }
                            }
                            if (isSelected) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null,
                                    tint = Color(0xFF1A73E8), modifier = Modifier.size(24.dp))
                            }
                        }
                    }
                }
                item {
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            navMgr.startNavigation()
                            onDismiss()
                        },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A73E8))
                    ) {
                        Icon(Icons.Default.Directions, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("开始导航", fontSize = 16.sp)
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }
        } else if (searchResults.isNotEmpty()) {
            // Transport mode toggle
            ModeToggle(mode = transportMode, onModeChange = { navMgr.setTransportMode(it, context) })
            Spacer(Modifier.height(4.dp))
            Text("搜索结果", style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp))
            LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                items(searchResults) { result ->
                    ListItem(
                        headlineContent = { Text(result.name, fontWeight = FontWeight.Medium) },
                        supportingContent = { Text(result.address, maxLines = 1, color = Color.Gray, fontSize = 13.sp) },
                        leadingContent = {
                            Icon(Icons.Default.LocationOn, contentDescription = null, tint = Color(0xFFEA4335))
                        },
                        modifier = Modifier.clickable {
                            keyboardController?.hide()
                            if (startLat != null && startLon != null) {
                                navMgr.calculateRoutes(context, startLat, startLon, result.latitude, result.longitude, result.name)
                                showRoutes = true
                            } else {
                                navMgr.setErrorMessage("等待GPS定位...")
                            }
                        }
                    )
                }
            }
        } else if (query.isEmpty() && recents.isNotEmpty()) {
            Text("最近目的地", style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp))
            LazyColumn(modifier = Modifier.heightIn(max = 250.dp)) {
                items(recents) { recent ->
                    ListItem(
                        headlineContent = { Text(recent.name) },
                        supportingContent = { Text("最近使用", maxLines = 1, color = Color.Gray) },
                        leadingContent = { Icon(Icons.Default.History, contentDescription = null, tint = Color.Gray) },
                        modifier = Modifier.clickable {
                            keyboardController?.hide()
                            if (startLat != null && startLon != null) {
                                navMgr.calculateRoutes(context, startLat, startLon, recent.lat, recent.lon, recent.name)
                                showRoutes = true
                            } else {
                                navMgr.setErrorMessage("等待GPS定位...")
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun ModeToggle(mode: NavigationManager.TransportMode, onModeChange: (NavigationManager.TransportMode) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val items = listOf(
            NavigationManager.TransportMode.CYCLE to "骑行",
            NavigationManager.TransportMode.DRIVE to "驾车"
        )
        for ((m, label) in items) {
            val isSelected = mode == m
            val grayBlue = Color(0xFF5F7D9E)
            FilterChip(
                selected = isSelected,
                onClick = { onModeChange(m) },
                label = { Text(label, color = if (isSelected) Color.White else Color(0xFF5F6368)) },
                leadingIcon = {
                    Icon(
                        if (m == NavigationManager.TransportMode.CYCLE) Icons.Filled.DirectionsBike else Icons.Filled.DirectionsCar,
                        contentDescription = null, modifier = Modifier.size(18.dp),
                        tint = if (isSelected) Color.White else Color(0xFF5F6368)
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = grayBlue,
                    selectedLabelColor = Color.White
                )
            )
        }
    }
}
