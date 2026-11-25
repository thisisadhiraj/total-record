package com.callrecorder.lite.ui

import android.content.ContentUris
import android.content.Intent
import android.database.ContentObserver
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.callrecorder.lite.R
import com.callrecorder.lite.core.CallStateCoordinator
import com.callrecorder.lite.core.ConsentBridge
import com.callrecorder.lite.core.SetupHealthChecker
import com.callrecorder.lite.databinding.ActivityMainBinding
import com.callrecorder.lite.model.CallSource
import com.callrecorder.lite.model.RecordingItem
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: RecordingsAdapter

    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var mediaPlayer: MediaPlayer? = null
    private var playingItemId: Long = -1L

    private val playbackTicker = object : Runnable {
        override fun run() {
            val mp = mediaPlayer
            if (mp != null && mp.isPlaying && playingItemId != -1L) {
                adapter.updatePlaybackState(
                    itemId = playingItemId,
                    playing = true,
                    positionMs = mp.currentPosition,
                    durationMs = mp.duration.coerceAtLeast(0)
                )
                mainHandler.postDelayed(this, 250L)
            }
        }
    }

    private val mediaObserver = object : ContentObserver(mainHandler) {
        override fun onChange(selfChange: Boolean) {
            loadRecordings()
        }
    }

    private val recordingStateListener: () -> Unit = {
        updateLiveRecordingBanner()
        if (!ConsentBridge.isRecordingActive) {
            loadRecordings()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Launch Onboarding automatically on first run if basic permissions are not yet granted
        if (!SetupHealthChecker.hasBasicPermissions(this)) {
            startActivity(Intent(this, OnboardingActivity::class.java))
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        setupListeners()

        contentResolver.registerContentObserver(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            true,
            mediaObserver
        )
    }

    override fun onResume() {
        super.onResume()
        ConsentBridge.addStateListener(recordingStateListener)
        updateStatusBanner()
        updateLiveRecordingBanner()
        loadRecordings()
    }

    override fun onPause() {
        ConsentBridge.removeStateListener(recordingStateListener)
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        stopAudioPlayback()
    }

    override fun onDestroy() {
        runCatching { contentResolver.unregisterContentObserver(mediaObserver) }
        stopAudioPlayback()
        ioExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun setupRecyclerView() {
        adapter = RecordingsAdapter(
            onPlayPauseClick = { item -> togglePlayback(item) },
            onSeekTo = { item, posMs ->
                if (item.id == playingItemId) {
                    runCatching { mediaPlayer?.seekTo(posMs) }
                }
            },
            onShareClick = { item -> shareRecording(item) },
            onDeleteClick = { item -> confirmDelete(item) }
        )

        binding.rvRecordings.layoutManager = LinearLayoutManager(this)
        binding.rvRecordings.adapter = adapter
    }

    private fun setupListeners() {
        binding.btnOpenSetup.setOnClickListener {
            startActivity(Intent(this, OnboardingActivity::class.java))
        }

        binding.cardStatusBanner.setOnClickListener {
            if (!SetupHealthChecker.isAllSetupComplete(this)) {
                startActivity(Intent(this, OnboardingActivity::class.java))
            }
        }

        binding.btnStopRecording.setOnClickListener {
            CallStateCoordinator.onManualStopRequested(applicationContext)
        }
    }

    private fun updateStatusBanner() {
        val allComplete = SetupHealthChecker.isAllSetupComplete(this)
        if (allComplete) {
            binding.cardStatusBanner.setCardBackgroundColor(ContextCompat.getColor(this, R.color.status_ok_bg))
            binding.cardStatusBanner.strokeColor = ContextCompat.getColor(this, R.color.status_ok_border)
            binding.ivBannerIcon.setImageResource(R.drawable.ic_check_circle)
            binding.tvBannerTitle.text = getString(R.string.status_active_title)
            binding.tvBannerTitle.setTextColor(ContextCompat.getColor(this, R.color.status_ok_text))
            binding.tvBannerSubtitle.text = getString(R.string.status_active_subtitle)
            binding.tvBannerSubtitle.setTextColor(ContextCompat.getColor(this, R.color.status_ok_text))
        } else {
            binding.cardStatusBanner.setCardBackgroundColor(ContextCompat.getColor(this, R.color.status_warn_bg))
            binding.cardStatusBanner.strokeColor = ContextCompat.getColor(this, R.color.status_warn_border)
            binding.ivBannerIcon.setImageResource(R.drawable.ic_warning)
            binding.tvBannerTitle.text = getString(R.string.status_incomplete_title)
            binding.tvBannerTitle.setTextColor(ContextCompat.getColor(this, R.color.status_warn_text))
            binding.tvBannerSubtitle.text = getString(R.string.status_incomplete_subtitle)
            binding.tvBannerSubtitle.setTextColor(ContextCompat.getColor(this, R.color.status_warn_text))
        }
    }

    private fun updateLiveRecordingBanner() {
        val active = ConsentBridge.isRecordingActive
        val source = ConsentBridge.activeRecordingSource
        if (active && source != null) {
            binding.cardLiveRecording.visibility = View.VISIBLE
            binding.tvLiveRecordingLabel.text = getString(R.string.status_recording_now, source.displayName)
        } else {
            binding.cardLiveRecording.visibility = View.GONE
        }
    }

    private fun loadRecordings() {
        ioExecutor.execute {
            val list = queryMediaStoreRecordings()
            mainHandler.post {
                if (isFinishing || isDestroyed) return@post
                adapter.submitList(list)
                binding.layoutEmptyState.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun queryMediaStoreRecordings(): List<RecordingItem> {
        val results = mutableListOf<RecordingItem>()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE
        )
        val selection = "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ? AND ${MediaStore.Audio.Media.IS_PENDING} = 0"
        val selectionArgs = arrayOf("%CallRecordings%")
        val sortOrder = "${MediaStore.Audio.Media.DATE_ADDED} DESC"

        runCatching {
            contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
                val durCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val name = cursor.getString(nameCol) ?: continue
                    val dateAdded = cursor.getLong(dateCol)
                    val durationMs = cursor.getLong(durCol)
                    val sizeBytes = cursor.getLong(sizeCol)
                    val uri = ContentUris.withAppendedId(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        id
                    )
                    results.add(
                        RecordingItem(
                            id = id,
                            uri = uri,
                            displayName = name,
                            source = CallSource.fromFileName(name),
                            dateAddedSeconds = dateAdded,
                            durationMs = durationMs,
                            sizeBytes = sizeBytes
                        )
                    )
                }
            }
        }
        return results
    }

    private fun togglePlayback(item: RecordingItem) {
        val currentMp = mediaPlayer
        if (playingItemId == item.id && currentMp != null) {
            if (currentMp.isPlaying) {
                currentMp.pause()
                mainHandler.removeCallbacks(playbackTicker)
                adapter.updatePlaybackState(
                    itemId = item.id,
                    playing = false,
                    positionMs = currentMp.currentPosition,
                    durationMs = currentMp.duration.coerceAtLeast(0)
                )
            } else {
                currentMp.start()
                mainHandler.post(playbackTicker)
            }
            return
        }

        stopAudioPlayback()

        val mp = MediaPlayer()
        try {
            mp.setDataSource(this, item.uri)
            mp.prepare()
            mp.setOnCompletionListener {
                mainHandler.removeCallbacks(playbackTicker)
                adapter.updatePlaybackState(
                    itemId = item.id,
                    playing = false,
                    positionMs = 0,
                    durationMs = mp.duration.coerceAtLeast(0)
                )
                stopAudioPlayback()
            }
            mp.start()
            mediaPlayer = mp
            playingItemId = item.id
            mainHandler.post(playbackTicker)
        } catch (_: Exception) {
            runCatching { mp.release() }
        }
    }

    private fun stopAudioPlayback() {
        mainHandler.removeCallbacks(playbackTicker)
        val prevId = playingItemId
        playingItemId = -1L
        runCatching { mediaPlayer?.stop() }
        runCatching { mediaPlayer?.release() }
        mediaPlayer = null
        if (prevId != -1L) {
            adapter.updatePlaybackState(-1L, false, 0, 0)
        }
    }

    private fun shareRecording(item: RecordingItem) {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "audio/mp4"
            putExtra(Intent.EXTRA_STREAM, item.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(shareIntent, getString(R.string.share_chooser_title)))
    }

    private fun confirmDelete(item: RecordingItem) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_confirm_title)
            .setMessage(getString(R.string.delete_confirm_message, item.displayName))
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                if (playingItemId == item.id) {
                    stopAudioPlayback()
                }
                ioExecutor.execute {
                    runCatching { contentResolver.delete(item.uri, null, null) }
                    loadRecordings()
                }
            }
            .show()
    }
}
