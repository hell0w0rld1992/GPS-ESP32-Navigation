package com.bikegps.android.ui.component

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bikegps.android.manager.BLEManager

@Composable
fun DeviceScanSheetContent(bleMgr: BLEManager, onDismiss: () -> Unit) {
    val status by bleMgr.status.collectAsState()

    Column(modifier = Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        // 用文字代替图标
        Text(
            text = when (status) {
                is BLEManager.ConnectionStatus.Connected -> "● 已连接"
                is BLEManager.ConnectionStatus.Scanning -> "◎ 正在扫描"
                is BLEManager.ConnectionStatus.Connecting -> "◎ 正在连接"
                is BLEManager.ConnectionStatus.Failed -> "○ 连接失败"
                is BLEManager.ConnectionStatus.Disconnected,
                is BLEManager.ConnectionStatus.Idle -> "○ 未连接"
            },
            fontSize = 36.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(8.dp)
        )

        Spacer(Modifier.height(16.dp))
        Text(
            text = when (status) {
                is BLEManager.ConnectionStatus.Idle -> "未连接"
                is BLEManager.ConnectionStatus.Scanning -> "正在扫描 BikeGPS 设备..."
                is BLEManager.ConnectionStatus.Connecting -> "正在连接..."
                is BLEManager.ConnectionStatus.Connected -> "已连接到 BikeGPS"
                is BLEManager.ConnectionStatus.Failed -> "连接失败"
                is BLEManager.ConnectionStatus.Disconnected -> "已断开连接"
            }, style = MaterialTheme.typography.titleMedium
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = {
            when (status) {
                is BLEManager.ConnectionStatus.Connected, is BLEManager.ConnectionStatus.Disconnected -> bleMgr.disconnect()
                else -> bleMgr.startScan()
            }
        }, modifier = Modifier.fillMaxWidth()) {
            Text(when (status) {
                is BLEManager.ConnectionStatus.Connected -> "断开连接"
                is BLEManager.ConnectionStatus.Scanning -> "停止扫描"
                else -> "扫描设备"
            })
        }
    }
}
