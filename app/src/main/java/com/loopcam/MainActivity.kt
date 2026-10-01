package com.loopcam

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.loopcam.databinding.ActivityMainBinding
import java.io.File
import java.io.FileOutputStream

/**
 * LoopCam Companion Dashboard Activity.
 *
 * Responsibilities:
 *  1. Let the user pick a video file from storage.
 *  2. Copy that video to externalCacheDir/loopcam_cache/video.mp4 (IPC bridge).
 *  3. Write the toggle state + cached path to SharedPreferences("com.loopcam.prefs").
 *  4. Show a live looping preview of the selected video via [VideoPreviewManager].
 *  5. Animate the status indicator between INACTIVE and INJECTING states.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "LoopCam"
        const val PREFS_NAME = "com.loopcam.prefs"
        const val KEY_ENABLED = "loopcam_enabled"
        const val KEY_VIDEO_PATH = "loopcam_video_path"
        private const val CACHE_DIR_NAME = "loopcam_cache"
        private const val CACHE_FILE_NAME = "video.mp4"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
    private var previewManager: VideoPreviewManager? = null
    private var pulseAnimator: ObjectAnimator? = null

    // ── Activity Result API: video file picker ─────────────────────────────

    private val pickVideo = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            handleVideoSelected(uri)
        }
    }

    private val requestStoragePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) launchPicker()
        else Toast.makeText(this, getString(R.string.permission_denied), Toast.LENGTH_LONG).show()
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        previewManager = VideoPreviewManager(this, binding.textureViewPreview)

        restoreState()
        setupListeners()
    }

    override fun onDestroy() {
        super.onDestroy()
        previewManager?.release()
        pulseAnimator?.cancel()
    }

    // ── State Restoration ─────────────────────────────────────────────────────

    private fun restoreState() {
        val enabled = prefs.getBoolean(KEY_ENABLED, false)
        val cachedPath = prefs.getString(KEY_VIDEO_PATH, null)

        binding.switchFakeCamera.isChecked = enabled
        updateStatusUi(enabled)

        if (!cachedPath.isNullOrEmpty()) {
            val file = File(cachedPath)
            if (file.exists()) {
                binding.tvFileName.text = file.name
                binding.tvPreviewPlaceholder.visibility = View.GONE
                previewManager?.setVideoUri(Uri.fromFile(file))
            }
        }
    }

    // ── Event Listeners ───────────────────────────────────────────────────────

    private fun setupListeners() {
        binding.btnSelectVideo.setOnClickListener {
            checkPermissionAndPick()
        }

        binding.switchFakeCamera.setOnCheckedChangeListener { _, isChecked ->
            val hasVideo = prefs.getString(KEY_VIDEO_PATH, null) != null

            if (isChecked && !hasVideo) {
                binding.switchFakeCamera.isChecked = false
                Toast.makeText(this, "Select a video first!", Toast.LENGTH_SHORT).show()
                return@setOnCheckedChangeListener
            }

            prefs.edit().putBoolean(KEY_ENABLED, isChecked).apply()
            updateStatusUi(isChecked)
            Log.i(TAG, "Fake camera ${if (isChecked) "ENABLED" else "DISABLED"}")
        }
    }

    // ── Permission & File Picker ──────────────────────────────────────────────

    private fun checkPermissionAndPick() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            Manifest.permission.READ_MEDIA_VIDEO
        else
            Manifest.permission.READ_EXTERNAL_STORAGE

        when {
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED ->
                launchPicker()
            shouldShowRequestPermissionRationale(permission) -> {
                Toast.makeText(this, getString(R.string.permission_rationale_storage), Toast.LENGTH_LONG).show()
                requestStoragePermission.launch(permission)
            }
            else -> requestStoragePermission.launch(permission)
        }
    }

    private fun launchPicker() {
        pickVideo.launch(arrayOf("video/*"))
    }

    // ── Video Selection Handler ───────────────────────────────────────────────

    private fun handleVideoSelected(uri: Uri) {
        val fileName = queryFileName(contentResolver, uri) ?: "video.mp4"
        binding.tvFileName.text = fileName

        // Copy video to externalCacheDir for cross-process file path IPC
        val cachedFile = cacheVideoFile(uri)
        if (cachedFile == null) {
            Toast.makeText(this, "Failed to cache video file", Toast.LENGTH_LONG).show()
            return
        }

        // Write path to SharedPreferences (Xposed module reads this)
        prefs.edit()
            .putString(KEY_VIDEO_PATH, cachedFile.absolutePath)
            .apply()

        // Show preview
        binding.tvPreviewPlaceholder.visibility = View.GONE
        previewManager?.setVideoUri(Uri.fromFile(cachedFile))

        Toast.makeText(this, "Video ready: ${cachedFile.name}", Toast.LENGTH_SHORT).show()
        Log.i(TAG, "Video cached to: ${cachedFile.absolutePath}")
    }

    /**
     * Copies the selected video URI content to [externalCacheDir]/loopcam_cache/video.mp4.
     * This gives the Xposed module (running in Telegram's process) a stable file path it can
     * read directly, bypassing SELinux restrictions on XSharedPreferences in Android 9+.
     */
    private fun cacheVideoFile(uri: Uri): File? {
        return try {
            val cacheDir = File(externalCacheDir, CACHE_DIR_NAME).also { it.mkdirs() }
            val outFile = File(cacheDir, CACHE_FILE_NAME)

            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(outFile).use { output ->
                    input.copyTo(output)
                }
            }
            outFile
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cache video", e)
            null
        }
    }

    private fun queryFileName(resolver: ContentResolver, uri: Uri): String? {
        return try {
            resolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx) else null
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }

    // ── Status UI ─────────────────────────────────────────────────────────────

    private fun updateStatusUi(enabled: Boolean) {
        pulseAnimator?.cancel()

        if (enabled) {
            binding.tvStatus.text = getString(R.string.status_injecting)
            binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.status_active))
            binding.switchFakeCamera.thumbTintList =
                ContextCompat.getColorStateList(this, R.color.toggle_thumb_on)
            binding.switchFakeCamera.trackTintList =
                ContextCompat.getColorStateList(this, R.color.toggle_track_on)

            // Pulse animation on status text
            pulseAnimator = ObjectAnimator.ofFloat(binding.tvStatus, View.ALPHA, 1f, 0.3f, 1f).apply {
                duration = 1200
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                start()
            }
        } else {
            binding.tvStatus.text = getString(R.string.status_inactive)
            binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.status_inactive))
            binding.tvStatus.alpha = 1f
            binding.switchFakeCamera.thumbTintList =
                ContextCompat.getColorStateList(this, R.color.toggle_thumb_off)
            binding.switchFakeCamera.trackTintList =
                ContextCompat.getColorStateList(this, R.color.toggle_track_off)
        }
    }
}
