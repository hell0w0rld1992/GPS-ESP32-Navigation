# AMap SDK
-keep class com.amap.** { *; }
-keep class com.autonavi.** { *; }
-dontwarn com.amap.**
-dontwarn com.autonavi.**

# Gson
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }

# BikeGPS models
-keep class com.bikegps.android.model.** { *; }
