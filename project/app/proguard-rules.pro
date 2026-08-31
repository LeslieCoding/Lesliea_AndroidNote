# Keep model classes serialized via reflection (Gson).
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
# ML Kit
-keep class com.google.mlkit.** { *; }
# Apache POI on Android
-keep class org.apache.poi.** { *; }
-dontwarn org.apache.poi.**
-dontwarn javax.xml.**
-dontwarn org.apache.xmlbeans.**
-dontwarn org.openxmlformats.**
# POI transitive references that never execute on Android (R8 missing-class)
-dontwarn aQute.bnd.**
-dontwarn org.osgi.**
-dontwarn org.apache.logging.log4j.**
-dontwarn com.graphbuilder.**
-dontwarn java.awt.**
-dontwarn javax.imageio.**
-dontwarn org.apache.commons.io.**
-dontwarn org.apache.commons.codec.**
-dontwarn org.apache.commons.collections4.**
-dontwarn org.apache.commons.math3.**
-dontwarn com.zaxxer.sparsebits.**
# WorkManager / Guava
-dontwarn com.google.common.util.concurrent.ListenableFuture
# Vosk offline ASR (JNI accessed via JNA)
-keep class org.vosk.** { *; }
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { *; }
-dontwarn org.vosk.**
-dontwarn com.sun.jna.**
