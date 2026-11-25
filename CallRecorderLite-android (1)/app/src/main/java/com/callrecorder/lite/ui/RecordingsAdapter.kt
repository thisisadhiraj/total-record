package com.callrecorder.lite.ui

import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.callrecorder.lite.R
import com.callrecorder.lite.databinding.ItemRecordingBinding
import com.callrecorder.lite.model.RecordingItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecordingsAdapter(
    private val onPlayPauseClick: (RecordingItem) -> Unit,
    private val onSeekTo: (RecordingItem, Int) -> Unit,
    private val onShareClick: (RecordingItem) -> Unit,
    private val onDeleteClick: (RecordingItem) -> Unit
) : ListAdapter<RecordingItem, RecordingsAdapter.RecordingViewHolder>(DiffCallback) {

    var playingItemId: Long = -1L
        private set
    var isPlaying: Boolean = false
        private set
    var currentPositionMs: Int = 0
        private set
    var activeDurationMs: Int = 0
        private set

    fun updatePlaybackState(
        itemId: Long,
        playing: Boolean,
        positionMs: Int,
        durationMs: Int
    ) {
        val previousId = playingItemId
        playingItemId = itemId
        isPlaying = playing
        currentPositionMs = positionMs
        activeDurationMs = durationMs

        if (previousId != -1L && previousId != itemId) {
            val prevPos = currentList.indexOfFirst { it.id == previousId }
            if (prevPos >= 0) notifyItemChanged(prevPos, PAYLOAD_PLAYBACK)
        }
        if (itemId != -1L) {
            val currPos = currentList.indexOfFirst { it.id == itemId }
            if (currPos >= 0) notifyItemChanged(currPos, PAYLOAD_PLAYBACK)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecordingViewHolder {
        val binding = ItemRecordingBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return RecordingViewHolder(binding)
    }

    override fun onBindViewHolder(holder: RecordingViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onBindViewHolder(
        holder: RecordingViewHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        if (payloads.contains(PAYLOAD_PLAYBACK)) {
            holder.bindPlaybackOnly(getItem(position))
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    inner class RecordingViewHolder(
        private val binding: ItemRecordingBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private var isUserSeeking = false

        fun bind(item: RecordingItem) {
            val ctx = binding.root.context
            binding.ivSourceIcon.setImageResource(item.source.iconRes)
            binding.tvTitle.text = item.displayName

            val dateText = dateFormatter.format(Date(item.dateAddedSeconds * 1000L))
            val sizeText = Formatter.formatShortFileSize(ctx, item.sizeBytes)
            binding.tvSubtitle.text = "${item.source.displayName} • $dateText • $sizeText"

            binding.btnPlayPause.setOnClickListener { onPlayPauseClick(item) }
            binding.btnShare.setOnClickListener { onShareClick(item) }
            binding.btnDelete.setOnClickListener { onDeleteClick(item) }

            binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser && item.id == playingItemId) {
                        val total = maxOf(activeDurationMs, item.durationMs.toInt())
                        binding.tvDuration.text = "${formatTime(progress)} / ${formatTime(total)}"
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {
                    isUserSeeking = true
                }

                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    isUserSeeking = false
                    if (seekBar != null && item.id == playingItemId) {
                        onSeekTo(item, seekBar.progress)
                    } else {
                        seekBar?.progress = 0
                    }
                }
            })

            bindPlaybackOnly(item)
        }

        fun bindPlaybackOnly(item: RecordingItem) {
            val isThisItem = item.id == playingItemId
            val totalMs = if (isThisItem && activeDurationMs > 0) {
                activeDurationMs
            } else {
                item.durationMs.toInt().coerceAtLeast(0)
            }

            binding.btnPlayPause.setImageResource(
                if (isThisItem && isPlaying) R.drawable.ic_pause else R.drawable.ic_play
            )

            binding.seekBar.max = totalMs.coerceAtLeast(1)
            binding.seekBar.isEnabled = isThisItem

            if (!isUserSeeking) {
                val pos = if (isThisItem) currentPositionMs.coerceIn(0, totalMs) else 0
                binding.seekBar.progress = pos
                binding.tvDuration.text = if (isThisItem) {
                    "${formatTime(pos)} / ${formatTime(totalMs)}"
                } else {
                    formatTime(totalMs)
                }
            }
        }

        private fun formatTime(ms: Int): String {
            val totalSec = (ms / 1000).coerceAtLeast(0)
            val min = totalSec / 60
            val sec = totalSec % 60
            return String.format(Locale.US, "%02d:%02d", min, sec)
        }
    }

    companion object {
        private const val PAYLOAD_PLAYBACK = "payload_playback"
        private val dateFormatter = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())

        private val DiffCallback = object : DiffUtil.ItemCallback<RecordingItem>() {
            override fun areItemsTheSame(oldItem: RecordingItem, newItem: RecordingItem): Boolean {
                return oldItem.id == newItem.id
            }

            override fun areContentsTheSame(oldItem: RecordingItem, newItem: RecordingItem): Boolean {
                return oldItem == newItem
            }
        }
    }
}
