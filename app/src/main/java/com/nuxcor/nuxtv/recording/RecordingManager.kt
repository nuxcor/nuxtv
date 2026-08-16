package com.nuxcor.nuxtv.recording

import android.content.Context
import android.content.Intent
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

data class ActiveRecording(
    val channelName: String,
    val file: File,
    val startedAtMs: Long,
    val bytesWritten: Long,
)

data class Recording(
    val file: File,
    val name: String,
    val sizeBytes: Long,
    val recordedAtMs: Long,
)

/** Tracks the (single) active recording and owns the recordings directory. */
object RecordingManager {

    private val _active = MutableStateFlow<ActiveRecording?>(null)
    val active: StateFlow<ActiveRecording?> = _active

    internal fun update(recording: ActiveRecording?) {
        _active.value = recording
    }

    fun directory(context: Context): File =
        File(context.getExternalFilesDir(null), "recordings").apply { mkdirs() }

    /**
     * Excludes the file currently being written. Listing it let the user delete
     * an in-progress recording: the inode went away, the service kept writing
     * to an unlinked file, and the UI still showed a recording that could never
     * produce anything.
     */
    fun list(context: Context): List<Recording> =
        directory(context).listFiles { f ->
            f.isFile && f.length() > 0 && f.absolutePath != active.value?.file?.absolutePath
        }
            .orEmpty()
            .map { Recording(file = it, name = it.nameWithoutExtension, sizeBytes = it.length(), recordedAtMs = it.lastModified()) }
            .sortedByDescending { it.recordedAtMs }

    fun delete(recording: Recording): Boolean = recording.file.delete()

    fun start(context: Context, url: String, channelName: String) {
        val intent = Intent(context, RecordingService::class.java)
            .setAction(RecordingService.ACTION_START)
            .putExtra(RecordingService.EXTRA_URL, url)
            .putExtra(RecordingService.EXTRA_NAME, channelName)
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent)
        else context.startService(intent)
    }

    fun stop(context: Context) {
        context.startService(
            Intent(context, RecordingService::class.java).setAction(RecordingService.ACTION_STOP)
        )
    }
}
