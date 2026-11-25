package com.callrecorder.lite.model

import android.net.Uri

data class RecordingItem(
    val id: Long,
    val uri: Uri,
    val displayName: String,
    val source: CallSource,
    val dateAddedSeconds: Long,
    val durationMs: Long,
    val sizeBytes: Long
)
