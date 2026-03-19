# AutoPilot BLE App ProGuard Rules

# Keep BLE model classes (serialized over the wire)
-keep class com.mikewen.autopilot.model.** { *; }
-keepclassmembers class com.mikewen.autopilot.model.** { *; }

# Kotlin serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }

# Kotlin Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Compose
-keep class androidx.compose.** { *; }

# Accompanist
-keep class com.google.accompanist.** { *; }
