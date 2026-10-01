package com.loopcam

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log
import android.view.Surface
import android.view.TextureView

/**
 * Manages MediaPlayer lifecycle tied to a TextureView for looping video preview
 * in the LoopCam dashboard. The video loops continuously via [MediaPlayer.setOnCompletionListener].
 */
class VideoPreviewManager(
    private val context: Context,
    private val textureView: TextureView
) : TextureView.SurfaceTextureListener {

    companion object {
        private const val TAG = "VideoPreviewManager"
    }

    private var mediaPlayer: MediaPlayer? = null
    private var videoUri: Uri? = null
    private var surface: Surface? = null

    init {
        textureView.surfaceTextureListener = this
    }

    /**
     * Set the video URI to preview. If the TextureView surface is already available,
     * playback starts immediately. Otherwise it starts once the surface is ready.
     */
    fun setVideoUri(uri: Uri) {
        videoUri = uri
        if (textureView.isAvailable) {
            startPlayback(textureView.surfaceTexture!!)
        }
    }

    /** Release all resources. Call from Activity.onDestroy(). */
    fun release() {
        mediaPlayer?.apply {
            if (isPlaying) stop()
            release()
        }
        mediaPlayer = null
        surface?.release()
        surface = null
    }

    // ── TextureView.SurfaceTextureListener ────────────────────────────────────

    override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, w: Int, h: Int) {
        surface = Surface(surfaceTexture)
        videoUri?.let { startPlayback(surfaceTexture) }
    }

    override fun onSurfaceTextureSizeChanged(surfaceTexture: SurfaceTexture, w: Int, h: Int) {
        // No-op for preview
    }

    override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
        release()
        return true
    }

    override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) {
        // Called every frame — no-op
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun startPlayback(surfaceTexture: SurfaceTexture) {
        try {
            mediaPlayer?.release()

            mediaPlayer = MediaPlayer().apply {
                setDataSource(context, videoUri!!)
                setSurface(Surface(surfaceTexture))
                isLooping = true
                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "MediaPlayer error: what=$what extra=$extra")
                    false
                }
                prepare()
                start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start video preview", e)
        }
    }
}
