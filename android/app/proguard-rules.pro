# JNI entry points use Java_com_purepixel_camera_model_NativePresetProcessor_* names.
-keep class com.purepixel.camera.model.NativePresetProcessor { *; }

# Keep enum names persisted in settings/cache metadata stable across releases.
-keepclassmembers enum com.purepixel.camera.model.ProcessingMode { *; }
-keepclassmembers enum com.purepixel.camera.model.PresetCategory { *; }
