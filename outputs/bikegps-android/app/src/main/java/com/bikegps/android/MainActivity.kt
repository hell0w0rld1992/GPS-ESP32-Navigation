package com.bikegps.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.bikegps.android.manager.*
import com.bikegps.android.ui.screen.MainScreen
import com.bikegps.android.ui.theme.BikeGPSTheme
import kotlinx.coroutines.*

class MainActivity : ComponentActivity() {

    private lateinit var locationMgr: LocationManager
    private lateinit var bleMgr: BLEManager
    private lateinit var navMgr: NavigationManager
    private lateinit var transmitter: GPSTransmitter
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        locationMgr = LocationManager(this)
        bleMgr = BLEManager(this)
        navMgr = NavigationManager()
        transmitter = GPSTransmitter(this, locationMgr, bleMgr, navMgr)

        scope.launch {
            bleMgr.status.collect { status ->
                when (status) {
                    is BLEManager.ConnectionStatus.Connected -> transmitter.start()
                    is BLEManager.ConnectionStatus.Disconnected,
                    is BLEManager.ConnectionStatus.Failed,
                    is BLEManager.ConnectionStatus.Idle -> transmitter.stop()
                    else -> {}
                }
            }
        }

        setContent {
            BikeGPSTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen(
                        locationMgr = locationMgr,
                        bleMgr = bleMgr,
                        navMgr = navMgr,
                        context = this@MainActivity
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        transmitter.onDestroy()
        locationMgr.onDestroy()
        bleMgr.onDestroy()
        navMgr.onDestroy()
        scope.cancel()
        super.onDestroy()
    }
}
