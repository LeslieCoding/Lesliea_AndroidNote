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
# androidx motion prediction is reached only reflectively from
# core-ink's MotionPredictor, so R8 would otherwise strip it and
# low-latency stroke prediction would silently degrade to NoOp.
-keep class androidx.input.motionprediction.MotionEventPredictor { *; }
-keep class androidx.input.motionprediction.** { *; }

# ---------------------------------------------------------------------------
# Gson-persisted types — these rules pin the on-disk .inknote format.
#
# Gson maps JSON keys to *field names* and enum values to *constant names*.
# R8 rewrites both. The @SerializedName rule at the top of this file only
# covers annotated fields; it protects neither enum constants nor the
# plain-field DTOs below. Without the rules here R8 silently corrupts saved
# documents rather than crashing: DocType.PDF_IMPORT is renamed to "D", so an
# imported PDF re-opens with a blank underlay; PageBackground.Type falls back
# to a blank template; Stroke.Layer loses HIGHLIGHT; and InknoteGson's
# polymorphic PageObject factory keys on ObjectType.<CONST>.name(), so every
# image / text box / shape / sticker / tape stops deserializing.
# ---------------------------------------------------------------------------

# The document model *is* the file format.
-keep class com.leslie.colorosnote.core.model.** { *; }

# Office fixed-layout payload stored in the container: plain-field DTOs, plus
# the enums it persists through Enum.name().
-keep class com.leslie.colorosnote.document.office.ImportedDocumentJson$* { *; }
-keep class com.leslie.colorosnote.document.office.OfficeType { *; }
-keep class com.leslie.colorosnote.document.office.TextAlign { *; }

# Handwriting search index entries (plain public fields).
-keep class com.leslie.colorosnote.feature.handwriting.HandwritingIndexEntry { *; }

# Vosk recognizer output DTOs (plain fields).
-keep class com.leslie.colorosnote.feature.audio.VoskJson { *; }
-keep class com.leslie.colorosnote.feature.audio.VoskJson$* { *; }

# Safety net for every other app enum: keep constant names so Enum.name() and
# valueOf() survive a round trip through any persisted string.
-keepclassmembers enum com.leslie.colorosnote.** { *; }
