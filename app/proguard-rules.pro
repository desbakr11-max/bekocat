# Keep project model names stable for crash reports; kotlinx.serialization needs generated serializers.
-keepnames class app.bekocat.core.timeline.** { *; }
-keep,includedescriptorclasses class app.bekocat.**$$serializer { *; }
-keepclassmembers class app.bekocat.** { *** Companion; kotlinx.serialization.KSerializer serializer(...); }

# Native bridges: classes and fields are looked up by name from JNI.
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keep class ai.onnxruntime.** { *; }
-keep class com.google.mediapipe.** { *; }
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.mediapipe.**
-dontwarn com.google.protobuf.**
-dontwarn javax.annotation.**
-dontwarn org.checkerframework.**
-dontwarn com.google.auto.value.**
