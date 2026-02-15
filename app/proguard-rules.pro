# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in android/sdk/tools/proguard/proguard-android.txt

# Keep EUC data models for JSON serialization
-keep class net.osmand.eucworld.api.** { *; }

# Keep service classes
-keep class net.osmand.eucworld.service.** { *; }

# Keep Android Auto classes
-keep class net.osmand.eucworld.auto.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# Gson
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn sun.misc.**
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

# AndroidX Car App
-keep class androidx.car.app.** { *; }
