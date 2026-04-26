# ファイルパス: app/proguard-rules.pro

# NCNN JNI Bridge
-keep class com.nexus.vision.ncnn.RealEsrganBridge { *; }

# ObjectBox
-keep class io.objectbox.** { *; }
-keep class com.nexus.vision.data.** { *; }

# LiteRT-LM
-keep class com.google.ai.edge.** { *; }

# ML Kit
-keep class com.google.mlkit.** { *; }