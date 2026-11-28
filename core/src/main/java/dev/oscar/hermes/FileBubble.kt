package dev.oscar.hermes

import android.content.ClipData
import android.content.Intent
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Size
import android.webkit.MimeTypeMap
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic")
private val VIDEO_EXT = setOf("mp4", "webm", "mkv", "3gp", "mov")

/** Images are shown inline and fetched from the PC automatically. */

/** Thumbnail of a file in the app cache (FileProvider uri): the image decoded small, or a video's first frame. */
private fun cachedThumb(ctx: Context, uri: Uri, video: Boolean): Bitmap? {
    if (video) return MediaMetadataRetriever().let { r -> try { r.setDataSource(ctx, uri); r.getFrameAtTime(0L) } finally { r.release() } }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    val sample = maxOf(1, maxOf(bounds.outWidth, bounds.outHeight) / 800)
    return ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
}

/**
 * A file in the chat. With [uriStr] (saved to Downloads): images and videos show a thumbnail, tap opens it.
 * Without it (still on the PC): a card with a Save button.
 */
@Composable
fun FileBubble(name: String, uriStr: String?, progress: Int? = null, onCancel: () -> Unit = {}, onDownload: ((String) -> Unit)? = null, onSave: () -> Unit) {
    val ctx = LocalContext.current
    // A cache file that was cleaned up counts as not on the phone yet, so Save fetches it again.
    val uri = uriStr?.let { Uri.parse(it) }?.takeIf { it.authority != FILE_AUTHORITY || MediaCache.fileOf(ctx, it.toString()) != null }
    val ext = name.substringAfterLast('.', "").lowercase()
    val isImage = ext in IMAGE_EXT
    val isVideo = ext in VIDEO_EXT
    val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
    val thumb by produceState<Bitmap?>(null, uriStr) {
        value = if (uri != null && (isImage || isVideo)) withContext(Dispatchers.IO) {
            runCatching { if (uri.authority == FILE_AUTHORITY) cachedThumb(ctx, uri, isVideo) else ctx.contentResolver.loadThumbnail(uri, Size(800, 800), null) }.getOrNull()
        } else null
    }
    val chooserTitle = stringResource(R.string.file_open_with)
    // Explicit "open" action: always shows the system app chooser, so the user picks the app.
    val openFile: () -> Unit = {
        runCatching {
            val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            view.clipData = ClipData.newRawUri("", uri)
            ctx.startActivity(Intent.createChooser(view, chooserTitle).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.widthIn(max = 320.dp)) {
        Column(Modifier.padding(6.dp)) {
            thumb?.let { bmp ->
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Image(bmp.asImageBitmap(), name, Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Fit)
                    if (isVideo) Icon(Icons.Default.PlayCircle, null, Modifier.size(56.dp), tint = Color.White)
                }
            }
            Row(Modifier.padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (thumb == null) Icon(Icons.Default.InsertDriveFile, null, Modifier.size(20.dp))
                Text(name, Modifier.weight(1f).padding(start = if (thumb == null) 6.dp else 0.dp),
                    style = MaterialTheme.typography.labelMedium, maxLines = 2)
                if (uri != null) {
                    if (onDownload != null && uri.authority == FILE_AUTHORITY)
                        IconButton(onClick = { onDownload(uriStr!!) }) { Icon(Icons.Default.Download, stringResource(R.string.file_save_downloads), Modifier.size(20.dp)) }
                    TextButton(onClick = openFile) { Text(stringResource(R.string.file_open)) }
                }
                else if (progress != null) {
                    Text("$progress%", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 4.dp))
                    IconButton(onClick = onCancel) { Icon(Icons.Default.Close, stringResource(R.string.download_cancel), Modifier.size(20.dp)) }
                } else TextButton(onClick = onSave) { Text(stringResource(R.string.inbox_save)) }
            }
            if (progress != null) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp))
        }
    }
}
