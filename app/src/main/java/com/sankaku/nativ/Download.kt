package com.sankaku.nativ

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

private val dlClient by lazy { OkHttpClient.Builder().build() }

private fun mimeOf(ext: String) = when (ext.lowercase()) {
    "mp4" -> "video/mp4"
    "webm" -> "video/webm"
    "mov" -> "video/quicktime"
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "webp" -> "image/webp"
    "gif" -> "image/gif"
    "avif" -> "image/avif"
    else -> "application/octet-stream"
}

/** In-app download with progress. Returns (content uri, bytes), throws otherwise.
 *  [tree] is a SAF directory the user picked; null means the stock Downloads folder,
 *  which MediaStore owns — it cannot write anywhere else. */
suspend fun Context.fetchToDownloads(
    url: String,
    filename: String,
    mime: String,
    onProgress: (done: Long, total: Long) -> Unit,
    tree: Uri? = null,
): Pair<Uri, Long> = withContext(Dispatchers.IO) {
    var last: Exception? = null
    repeat(2) {
        try {
            return@withContext downloadOnce(url, filename, mime, onProgress, tree)
        } catch (e: Exception) {
            last = e
        }
    }
    throw last!!
}

private fun Context.createInDownloads(filename: String, mime: String): Uri {
    val values = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, filename)
        put(MediaStore.Downloads.MIME_TYPE, mime)
        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
    }
    return contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        ?: throw java.io.IOException("mediastore insert failed")
}

/** User-picked folder. Uses the framework call directly rather than pulling in
 *  androidx.documentfile just for createFile. */
private fun Context.createInTree(tree: Uri, filename: String, mime: String): Uri =
    DocumentsContract.createDocument(contentResolver, tree, mime, filename)
        ?: throw java.io.IOException("could not create $filename in that folder")

private fun Context.downloadOnce(
    url: String,
    filename: String,
    mime: String,
    onProgress: (Long, Long) -> Unit,
    tree: Uri?,
): Pair<Uri, Long> {
    val req = Request.Builder().url(url).header("User-Agent", "SankakuNative/0.1").build()
    dlClient.newCall(req).execute().use { res ->
        if (!res.isSuccessful) throw java.io.IOException("HTTP ${res.code}")
        val body = res.body ?: throw java.io.IOException("empty body")
        val total = body.contentLength()
        val uri = if (tree != null) createInTree(tree, filename, mime)
        else createInDownloads(filename, mime)
        var done = 0L
        try {
            contentResolver.openOutputStream(uri)?.use { out ->
                body.byteStream().use { `in` ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        val n = `in`.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        onProgress(done, total)
                    }
                }
            } ?: throw java.io.IOException("open failed")
        } catch (e: Exception) {
            runCatching { if (tree != null) DocumentsContract.deleteDocument(contentResolver, uri) else contentResolver.delete(uri, null, null) }
            throw e
        }
        return uri to done
    }
}

private const val DL_CHANNEL = "downloads"

private fun Context.dlNotify() =
    getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

fun Context.ensureDlChannel() {
    val nm = dlNotify()
    if (nm.getNotificationChannel(DL_CHANNEL) == null)
        nm.createNotificationChannel(NotificationChannel(DL_CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW))
}

/** Download with system notification (progress → complete/failed, tap to open). */
suspend fun Context.downloadPost(
    post: Post,
    url: String,
    onProgress: (Long, Long) -> Unit,
    tree: Uri? = null,
): Long {
    ensureDlChannel()
    val nm = dlNotify()
    val nid = post.id.hashCode()
    val filename = downloadName(post)
    val mime = mimeOfPost(post)
    val ongoing = NotificationCompat.Builder(this, DL_CHANNEL)
        .setSmallIcon(android.R.drawable.stat_sys_download)
        .setContentTitle("Downloading $filename")
        .setOngoing(true).setOnlyAlertOnce(true)
    nm.notify(nid, ongoing.setProgress(0, 0, true).build())
    var lastT = 0L
    try {
        val (uri, bytes) = fetchToDownloads(url, filename, mime, { d, t ->
            onProgress(d, t)
            val now = System.currentTimeMillis()
            if (now - lastT > 500) {
                lastT = now
                val pct = if (t > 0) (100 * d / t).toInt() else 0
                nm.notify(
                    nid, ongoing.setProgress(100, pct, t <= 0)
                        .setContentText(if (t > 0) "$pct%" else "${d / 1024} KB").build(),
                )
            }
        }, tree)
        val open = PendingIntent.getActivity(
            this, nid,
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        nm.notify(
            nid, NotificationCompat.Builder(this, DL_CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Download complete").setContentText(filename)
                .setContentIntent(open).setAutoCancel(true).build(),
        )
        PrefsStore(this).addHistory(DlEntry(post.id, filename, mime, uri.toString(), bytes, System.currentTimeMillis()))
        return bytes
    } catch (e: Exception) {
        nm.notify(
            nid, NotificationCompat.Builder(this, DL_CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle("Download failed").setContentText(e.message)
                .setAutoCancel(true).build(),
        )
        throw e
    }
}

fun extOf(post: Post) = post.fileExt.ifEmpty { "bin" }
fun mimeOfPost(post: Post) = mimeOf(extOf(post))

private const val TAG_ARTIST = 1
private const val TAG_CHARACTER = 4

/** Display form of the first tag of [type]; nameEn reads "Letho Rin", tagName "letho_rin". */
private fun Post.tagOfType(type: Int): String? =
    tags.firstOrNull { it.type == type }
        ?.let { it.nameEn.ifEmpty { it.tagName } }
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

/**
 * "artist - character.ext" (e.g. "goon - el goonio.mp4") only when both tags are
 * there; anything less falls back to the default sankaku-<id>.<ext>.
 */
fun downloadName(post: Post): String {
    val fallback = "sankaku-${post.id}.${extOf(post)}"
    val artist = post.tagOfType(TAG_ARTIST) ?: return fallback
    val character = post.tagOfType(TAG_CHARACTER) ?: return fallback
    val label = "$artist - $character"
        .replace(Regex("""[\\/:*?"<>|]"""), "")
        .replace(Regex("""\s+"""), " ")
        .trim()
        .take(80)
    return if (label.trim('-', ' ').isBlank()) fallback else "$label.${extOf(post)}"
}
