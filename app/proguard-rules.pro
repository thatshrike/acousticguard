# TFLite models are loaded by asset name; keep the runtime visible to reflection-based delegates.
-keep class org.tensorflow.lite.** { *; }

# The native probe is resolved by its JNI symbol rather than a Kotlin call site
# from the existing service. Keep the opt-in boundary and its private external
# method available if release shrinking is enabled later.
-keep class com.acousticguard.NativeAudioBoundary { *; }
