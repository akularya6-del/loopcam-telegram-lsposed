package com.loopcam.xposed

import com.loopcam.xposed.VideoFrameInjector
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.File

/**
 * LoopCam Xposed Module Entry Point.
 *
 * This class is loaded by the Xposed/LSPosed framework into every process listed
 * in the module's scope. It gates on:
 *   1. Package name = Telegram (org.telegram.messenger or .web variant)
 *   2. LoopCam toggle enabled in SharedPreferences
 *   3. Cached video file exists at the IPC bridge path
 *
 * IPC Bridge (SELinux-safe):
 *   The companion app copies the selected video to:
 *     /sdcard/Android/data/com.loopcam/cache/loopcam_cache/video.mp4
 *   and writes the absolute path to SharedPreferences key "loopcam_video_path".
 *
 *   We read the path via XSharedPreferences (LSPosed supports this on modern Android).
 *   As a hard fallback we construct the expected path from the known package name.
 */
class MainHook : IXposedHookLoadPackage {

    companion object {
        private const val TAG = "LoopCam:Hook"

        /** All Telegram package name variants we want to hook */
        private val TELEGRAM_PACKAGES = setOf(
            "org.telegram.messenger",
            "org.telegram.messenger.web"
        )

        private const val PREFS_NAME = "com.loopcam.prefs"
        private const val KEY_ENABLED = "loopcam_enabled"
        private const val KEY_VIDEO_PATH = "loopcam_video_path"

        /**
         * Fallback path: companion app's external cache location.
         * This path is predictable even without reading SharedPreferences.
         */
        private const val FALLBACK_VIDEO_PATH =
            "/sdcard/Android/data/com.loopcam/cache/loopcam_cache/video.mp4"
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        // Only act on Telegram packages
        if (lpparam.packageName !in TELEGRAM_PACKAGES) return

        XposedBridge.log("$TAG: Loaded into ${lpparam.packageName}")

        try {
            // ── Read SharedPreferences via XSharedPreferences ──────────────────
            val prefs = XSharedPreferences("com.loopcam", PREFS_NAME)
            prefs.makeWorldReadable()

            val isEnabled = prefs.getBoolean(KEY_ENABLED, false)
            if (!isEnabled) {
                XposedBridge.log("$TAG: Toggle is OFF — not hooking.")
                return
            }

            // ── Resolve video path ─────────────────────────────────────────────
            val videoPath = resolveVideoPath(prefs)
            if (videoPath == null) {
                XposedBridge.log("$TAG: No valid video path found — not hooking.")
                return
            }

            XposedBridge.log("$TAG: ENABLED — installing hooks. Video: $videoPath")

            // ── Install hooks ──────────────────────────────────────────────────
            VideoFrameInjector(videoPath, lpparam.classLoader).install()

        } catch (e: Exception) {
            XposedBridge.log("$TAG: Error in handleLoadPackage: ${e.message}")
        }
    }

    /**
     * Attempts to find a valid video file path:
     *   1. Path stored in XSharedPreferences (most accurate — reflects user's pick)
     *   2. Hardcoded fallback path based on known package name convention
     */
    private fun resolveVideoPath(prefs: XSharedPreferences): String? {
        // Primary: read from preferences
        val storedPath = prefs.getString(KEY_VIDEO_PATH, null)
        if (!storedPath.isNullOrEmpty() && File(storedPath).exists()) {
            return storedPath
        }

        // Fallback: well-known cache path
        if (File(FALLBACK_VIDEO_PATH).exists()) {
            XposedBridge.log("$TAG: Using fallback video path.")
            return FALLBACK_VIDEO_PATH
        }

        return null
    }
}
