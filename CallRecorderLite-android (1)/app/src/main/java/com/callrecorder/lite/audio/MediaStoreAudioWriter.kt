package com.callrecorder.lite.audio

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import com.callrecorder.lite.model.CallSource
import java.io.FileDescriptor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MediaStoreAudioWriter(
    private val context: Context,
    initialSource: CallSource
) {
    private val resolver: ContentResolver = context.contentResolver
    private val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())

    var uri: Uri? = null
        private set

    private var pfd: ParcelFileDescriptor? = null

    fun open(source: CallSource): FileDescriptor? {
        val fileName = "${source.filePrefix}_$timestamp.m4a"
        val relativePath = "${Environment.DIRECTORY_MUSIC}/CallRecordings"
        val nowSeconds = System.currentTimeMillis() / 1000L

        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
            put(MediaStore.Audio.Media.RELATIVE_PATH, relativePath)
            put(MediaStore.Audio.Media.IS_MUSIC, 0)
            put(MediaStore.Audio.Media.IS_PENDING, 1)
            put(MediaStore.Audio.Media.DATE_ADDED, nowSeconds)
            put(MediaStore.Audio.Media.DATE_MODIFIED, nowSeconds)
        }

        val insertedUri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
            ?: return null

        return try {
            val descriptor = resolver.openFileDescriptor(insertedUri, "rw")
            if (descriptor == null) {
                resolver.delete(insertedUri, null, null)
                null
            } else {
                uri = insertedUri
                pfd = descriptor
                descriptor.fileDescriptor
            }
        } catch (e: Exception) {
            runCatching { resolver.delete(insertedUri, null, null) }
            null
        }
    }

    fun updateSourceName(newSource: CallSource) {
        val targetUri = uri ?: return
        val newName = "${newSource.filePrefix}_$timestamp.m4a"
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, newName)
        }
        runCatching {
            resolver.update(targetUri, values, null, null)
        }
    }

    fun finalizeOrDiscard(durationMs: Long, hasAudioData: Boolean) {
        val targetUri = uri
        runCatching { pfd?.close() }
        pfd = null

        if (targetUri == null) return

        if (!hasAudioData || durationMs < 200L) {
            runCatching { resolver.delete(targetUri, null, null) }
            uri = null
            return
        }

        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.IS_PENDING, 0)
            put(MediaStore.Audio.Media.DURATION, durationMs)
            put(MediaStore.Audio.Media.DATE_MODIFIED, System.currentTimeMillis() / 1000L)
        }
        runCatching {
            resolver.update(targetUri, values, null, null)
        }
    }
}
