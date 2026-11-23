package vn.gamerec.storage

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Size
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class Video(val uri: Uri, val name: String, val duration: Long, val bytes: Long, val created: Long)

@Singleton
class VideoStore @Inject constructor(@ApplicationContext private val context: Context) {
    private val resolver = context.contentResolver
    private val prefs = context.getSharedPreferences("videos", Context.MODE_PRIVATE)
    private val collection = if (Build.VERSION.SDK_INT >= 29)
        MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    private val mutable = MutableStateFlow(0)
    val changes = mutable.asStateFlow()

    data class Output(val uri: Uri, val descriptor: ParcelFileDescriptor)

    private var active: Uri? = null

    @Synchronized
    fun recover() {
        if (active != null) return
        prefs.getString("pending", null)?.let {
            val uri = Uri.parse(it)
            // A crash may occur after publishing the row and before clearing the journal.
            if (Build.VERSION.SDK_INT >= 29) {
                val pending = resolver.query(uri, arrayOf(MediaStore.MediaColumns.IS_PENDING), null, null, null)
                    ?.use { cursor -> cursor.moveToFirst() && cursor.getInt(0) == 1 } == true
                if (pending) resolver.delete(uri, null, null)
                else if (resolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)?.use { row -> row.moveToFirst() } == true) {
                    check(prefs.edit().putStringSet("saved", known() + it).commit())
                    mutable.update { value -> value + 1 }
                }
            } else if (it !in known()) {
                resolver.delete(uri, null, null)
            }
            prefs.edit().remove("pending").commit()
        }
    }

    @Synchronized
    fun open(folder: String): Output {
        check(active == null) { "Không thể mở hai tệp ghi cùng lúc." }
        recover()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date())
        val name = "GameRec_${stamp}_${UUID.randomUUID().toString().take(8)}.mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$folder/")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            } else {
                val directory = File(Environment.getExternalStorageDirectory(), folder)
                check(directory.isDirectory || directory.mkdirs()) { "Không tạo được thư mục lưu." }
                put(MediaStore.MediaColumns.DATA, File(directory, name).absolutePath)
            }
        }
        val uri = checkNotNull(resolver.insert(collection, values)) { "Không tạo được tệp video." }
        try {
            check(prefs.edit().putString("pending", uri.toString()).commit()) { "Không lưu được thông tin phiên ghi." }
            val descriptor = checkNotNull(resolver.openFileDescriptor(uri, "rw")) { "Không mở được tệp video." }
            active = uri
            return Output(uri, descriptor)
        } catch (failure: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw failure
        }
    }

    @Synchronized
    fun finish(output: Output, keep: Boolean) {
        runCatching { output.descriptor.close() }
        val uri = output.uri
        active = null
        if (keep) {
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                check(resolver.update(uri, values, null, null) == 1) { "Không xuất bản được video." }
            } else {
                val path = resolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)
                    ?.use { if (it.moveToFirst()) it.getString(0) else null }
                val metrics = ContentValues()
                path?.let { metrics.put(MediaStore.MediaColumns.SIZE, File(it).length()) }
                runCatching {
                    MediaMetadataRetriever().useCompat {
                        it.setDataSource(context, uri)
                        it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.let { duration ->
                            metrics.put(MediaStore.Video.Media.DURATION, duration)
                        }
                    }
                }
                if (metrics.size() > 0) resolver.update(uri, metrics, null, null)
            }
            check(prefs.edit().putStringSet("saved", known() + uri.toString()).remove("pending").commit()) {
                "Không cập nhật được thư viện."
            }
        } else {
            resolver.delete(uri, null, null)
            prefs.edit().remove("pending").commit()
        }
        mutable.update { it + 1 }
    }

    private fun known(): Set<String> = prefs.getStringSet("saved", emptySet()).orEmpty()

    @Synchronized
    fun list(): List<Video> {
        val projection = arrayOf(
            MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE, MediaStore.Video.Media.DATE_ADDED
        )
        return known().mapNotNull { key ->
            val uri = Uri.parse(key)
            resolver.query(uri, projection, null, null, null)?.use {
                if (it.moveToFirst()) Video(uri, it.getString(0), it.getLong(1), it.getLong(2), it.getLong(3) * 1000)
                else null
            }
        }.sortedByDescending(Video::created)
    }

    fun thumbnail(video: Video): Bitmap? = runCatching {
        if (Build.VERSION.SDK_INT >= 29) resolver.loadThumbnail(video.uri, Size(192, 108), null)
        else MediaStore.Video.Thumbnails.getThumbnail(
            resolver, ContentUris.parseId(video.uri), MediaStore.Video.Thumbnails.MINI_KIND, null
        )
    }.getOrNull()

    @Synchronized
    fun rename(video: Video, input: String) {
        val name = input.trim().let { if (it.endsWith(".mp4", true)) it.dropLast(4) else it }.trim()
        require(name.isNotBlank() && name.length <= 120 && name.none { it in "/\\:*?\"<>|" || it.isISOControl() }) {
            "Tên video không hợp lệ."
        }
        val values = ContentValues().apply { put(MediaStore.Video.Media.DISPLAY_NAME, "$name.mp4") }
        if (Build.VERSION.SDK_INT >= 29) {
            check(resolver.update(video.uri, values, null, null) == 1) { "Không đổi được tên video." }
        } else {
            val path = resolver.query(video.uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
            val old = File(checkNotNull(path) { "Không tìm thấy video." })
            val target = File(old.parentFile, "$name.mp4")
            if (old != target) {
                check(!target.exists() && old.renameTo(target)) { "Không đổi được tên video." }
                try {
                    values.put(MediaStore.MediaColumns.DATA, target.absolutePath)
                    check(resolver.update(video.uri, values, null, null) == 1) { "Không đổi được tên video." }
                } catch (failure: Exception) {
                    target.renameTo(old)
                    throw failure
                }
            }
        }
        mutable.update { it + 1 }
    }

    @Synchronized
    fun delete(video: Video) {
        check(resolver.delete(video.uri, null, null) == 1) { "Không xóa được video." }
        prefs.edit().putStringSet("saved", known() - video.uri.toString()).apply()
        mutable.update { it + 1 }
    }
}

private inline fun <T> MediaMetadataRetriever.useCompat(block: (MediaMetadataRetriever) -> T): T =
    try { block(this) } finally { release() }
