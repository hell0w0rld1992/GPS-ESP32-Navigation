package com.bikegps.android

import android.app.Application
import android.util.Log
import com.amap.api.maps.MapsInitializer

class BikeGPSApplication : Application() {

    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(base)
        // 隐私协议必须在任何地图操作之前调用
        try {
            MapsInitializer.updatePrivacyShow(this, true, true)
            MapsInitializer.updatePrivacyAgree(this, true)
            MapsInitializer.setApiKey("4e17fd6da2ba0c99e359db0060829c30")
            MapsInitializer.initialize(this)
            Log.i("BikeGPS", "AMap 初始化成功")
        } catch (e: Exception) {
            Log.e("BikeGPS", "AMap 初始化失败", e)
        }
    }

    override fun onCreate() {
        super.onCreate()

        // 崩溃日志处理器
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val crashDir = java.io.File(filesDir, "crashes")
                crashDir.mkdirs()
                val timeStamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault())
                    .format(java.util.Date())
                val crashFile = java.io.File(crashDir, "crash_$timeStamp.log")
                java.io.FileWriter(crashFile).use { fw ->
                    java.io.PrintWriter(fw).use { pw ->
                        pw.println("=== BikeGPS Crash Report ===")
                        pw.println("Time: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}")
                        pw.println("Thread: ${thread?.name}")
                        pw.println()
                        throwable?.printStackTrace(pw)
                    }
                }
                Log.e("BikeGPS", "Crash logged to: ${crashFile.absolutePath}")
            } catch (e: Exception) {
                Log.e("BikeGPS", "Failed to write crash log", e)
            }
            android.os.Process.killProcess(android.os.Process.myPid())
            System.exit(1)
        }
    }
}
