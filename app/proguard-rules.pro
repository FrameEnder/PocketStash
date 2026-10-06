# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }

-keep,includedescriptorclasses class com.frameender.pocketstash.**$$serializer { *; }
-keepclassmembers class com.frameender.pocketstash.** { *** Companion; }
-keepclasseswithmembers class com.frameender.pocketstash.** { kotlinx.serialization.KSerializer serializer(...); }

# Keep API models and navigation routes intact (reflection-free, but names show up in errors)
-keep class com.frameender.pocketstash.data.model.** { *; }
-keep class com.frameender.pocketstash.ui.nav.** { *; }

# OkHttp
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
