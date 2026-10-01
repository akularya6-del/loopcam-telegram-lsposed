package com.loopcam.xposed

import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CaptureRequest
import android.media.MediaPlayer
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.io.File

/**
 * VideoFrameInjector — Camera2 Surface Swap Strategy (Strategy A).
 *
 * How it works:
 *   Telegram uses the Camera2 API. When it opens a camera session, it calls
 *   [CameraDevice.createCaptureSession] with a list of [Surface] targets.
 *   We hook this method and inject our own [Surface] (driven by a looping
 *   [MediaPlayer] → [SurfaceTexture] pipeline) as an ADDITIONAL output, then
 *   intercept the [CameraCaptureSession.CaptureCallback] or, simpler, swap the
 *   first surface in the provided list with our surface.
 *
 * Surface Swap Mechanics:
 *   The list passed to createCaptureSession is not always modifiable. We therefore:
 *   1. Create our own [SurfaceTexture] + [Surface] backed by [MediaPlayer].
 *   2. Wrap the callback by replacing surfaces in the [CaptureRequest] builders
 *      by hooking [CaptureRequest.Builder.addTarget].
 *
 * Legacy Camera Fallback (Strategy B — inline):
 *   If Telegram happens to use the old Camera API (unlikely on modern builds but
 *   included for completeness), we hook [android.hardware.Camera.setPreviewCallback]
 *   and replace the byte array in [onPreviewFrame] with NV21-encoded frames from
 *   our MediaPlayer output via [ImageReader].
 */
class VideoFrameInjector(
    private val videoPath: String,
    private val classLoader: ClassLoader
) {

    companion object {
        private const val TAG = "LoopCam:Injector"
    }

    // Background thread for MediaPlayer operations inside Telegram's process
    private val handlerThread = HandlerThread("LoopCam-Player").also { it.start() }
    private val handler = Handler(handlerThread.looper)

    // SurfaceTexture created from MediaPlayer output — this is what we swap in
    private var playerSurfaceTexture: SurfaceTexture? = null
    private var playerSurface: Surface? = null
    private var mediaPlayer: MediaPlayer? = null

    private var isPlayerReady = false

    // ── Public API ────────────────────────────────────────────────────────────

    fun install() {
        initMediaPlayer()
        hookCamera2API()
        hookLegacyCameraAPI()
    }

    // ── MediaPlayer Init (runs in Telegram's process) ─────────────────────────

    private fun initMediaPlayer() {
        handler.post {
            try {
                val file = File(videoPath)
                if (!file.exists()) {
                    XposedBridge.log("$TAG: Video file not found: $videoPath")
                    return@post
                }

                // SurfaceTexture with a dummy texture ID — we render INTO this
                val st = SurfaceTexture(10).also { playerSurfaceTexture = it }
                val surface = Surface(st).also { playerSurface = it }

                mediaPlayer = MediaPlayer().apply {
                    setDataSource(videoPath)
                    setSurface(surface)
                    isLooping = true
                    setOnPreparedListener { mp ->
                        mp.start()
                        isPlayerReady = true
                        XposedBridge.log("$TAG: MediaPlayer started — ${file.name}")
                    }
                    setOnErrorListener { _, what, extra ->
                        XposedBridge.log("$TAG: MediaPlayer error what=$what extra=$extra")
                        false
                    }
                    prepareAsync()
                }
            } catch (e: Exception) {
                XposedBridge.log("$TAG: Failed to init MediaPlayer: ${e.message}")
            }
        }
    }

    // ── Camera2 Hook (Strategy A — primary) ──────────────────────────────────

    @Suppress("UNCHECKED_CAST")
    private fun hookCamera2API() {
        try {
            // Hook CaptureRequest.Builder.addTarget(Surface)
            // When Telegram adds a Surface to a capture request, we swap it with ours.
            XposedHelpers.findAndHookMethod(
                "android.hardware.camera2.CaptureRequest\$Builder",
                classLoader,
                "addTarget",
                Surface::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!isPlayerReady) return
                        val ourSurface = playerSurface ?: return
                        // Replace the target surface with our fake surface
                        param.args[0] = ourSurface
                        XposedBridge.log("$TAG: Swapped CaptureRequest surface → fake camera")
                    }
                }
            )

            // Also hook CameraDevice.createCaptureSession to inject our surface into outputs
            XposedHelpers.findAndHookMethod(
                "android.hardware.camera2.CameraDevice",
                classLoader,
                "createCaptureSession",
                List::class.java,
                CameraCaptureSession.StateCallback::class.java,
                Handler::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!isPlayerReady) return
                        val ourSurface = playerSurface ?: return

                        @Suppress("UNCHECKED_CAST")
                        val outputs = param.args[0] as? MutableList<Surface> ?: return

                        // Replace ALL surfaces with our surface so every
                        // camera output goes to our MediaPlayer feed
                        if (outputs.isNotEmpty()) {
                            val replaced = mutableListOf<Surface>()
                            repeat(outputs.size) { replaced.add(ourSurface) }
                            param.args[0] = replaced
                            XposedBridge.log("$TAG: Replaced ${outputs.size} session surface(s)")
                        }
                    }
                }
            )

            XposedBridge.log("$TAG: Camera2 hooks installed")

        } catch (e: Exception) {
            XposedBridge.log("$TAG: Failed to install Camera2 hooks: ${e.message}")
        }
    }

    // ── Legacy Camera Hook (Strategy B — fallback) ────────────────────────────

    private fun hookLegacyCameraAPI() {
        try {
            // Hook Camera.setPreviewTexture — replace the SurfaceTexture with ours
            XposedHelpers.findAndHookMethod(
                "android.hardware.Camera",
                classLoader,
                "setPreviewTexture",
                SurfaceTexture::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!isPlayerReady) return
                        val ourSt = playerSurfaceTexture ?: return
                        param.args[0] = ourSt
                        XposedBridge.log("$TAG: Swapped legacy Camera SurfaceTexture")
                    }
                }
            )

            // Hook Camera.setPreviewDisplay (SurfaceHolder) — wrap with ours if possible
            XposedHelpers.findAndHookMethod(
                "android.hardware.Camera",
                classLoader,
                "setPreviewDisplay",
                android.view.SurfaceHolder::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!isPlayerReady) return
                        // Cannot trivially swap SurfaceHolder; suppress the call instead
                        // so Telegram's camera doesn't attach a real preview surface.
                        // The CaptureRequest hook above handles the frame path.
                        param.result = null // no-op the setPreviewDisplay call
                        XposedBridge.log("$TAG: Suppressed legacy Camera.setPreviewDisplay")
                    }
                }
            )

            XposedBridge.log("$TAG: Legacy Camera hooks installed")

        } catch (e: Exception) {
            // Expected to fail if the target doesn't use the legacy API — non-fatal
            XposedBridge.log("$TAG: Legacy Camera hook skipped (${e.message})")
        }
    }
}
