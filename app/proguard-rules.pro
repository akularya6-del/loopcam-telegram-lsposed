# ProGuard rules for LoopCam Xposed Module
#
# Rule 1: Keep all Xposed hook classes — they are loaded by reflection
-keep class com.loopcam.xposed.** { *; }

# Rule 2: Keep Xposed framework API classes referenced at runtime
-keep class de.robv.android.xposed.** { *; }

# Rule 3: Preserve all annotations (required for Xposed framework discovery)
-keepattributes *Annotation*

# Rule 4: Keep all classes implementing IXposedHookLoadPackage with all members
-keepclassmembers class * implements de.robv.android.xposed.IXposedHookLoadPackage { *; }

# Additional safety rules
# Keep companion object members used via reflection
-keepclassmembers class com.loopcam.** {
    static ** Companion;
    static ** INSTANCE;
}

# Prevent obfuscation of method names used in XposedHelpers.findAndHookMethod
-keepnames class android.hardware.camera2.** { *; }
-keepnames class android.hardware.Camera { *; }

# Don't warn about Xposed classes (they're compileOnly — not in the APK)
-dontwarn de.robv.android.xposed.**
