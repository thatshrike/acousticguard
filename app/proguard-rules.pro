# TFLite models are loaded by asset name; keep the runtime visible to reflection-based delegates.
-keep class org.tensorflow.lite.** { *; }
