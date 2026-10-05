# ProGuard rules for Vory for Android.
# Minification is currently disabled (see app/build.gradle.kts); these rules
# apply if isMinifyEnabled is ever turned on.

# Keep JSON model classes (parsed reflectively via org.json opt* methods).
-keep class dev.vory.android.data.** { *; }

# OkHttp / Okio.
-dontwarn okhttp3.**
-dontwarn okio.**

# Coil.
-keep class coil.** { *; }
-dontwarn coil.**
