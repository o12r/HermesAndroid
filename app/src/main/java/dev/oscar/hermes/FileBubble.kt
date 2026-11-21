package dev.oscar.hermes

import android.content.Intent
import android.graphics.Bitmap
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
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic")
private val VIDEO_EXT = setOf("mp4", "webm", "mkv", "3gp", "mov")

/** A file saved from the Inbox, shown in the chat: images and videos inline (thumbnail), tap to open. */
@Composable
fun FileBubble(name: String, uriStr: String) {
    val ctx = LocalContext.current
    val uri = Uri.parse(uriStr)
    val ext = name.substringAfterLast('.', "").lowercase()
    val isImage = ext in IMAGE_EXT
    val isVideo = ext in VIDEO_EXT
    val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
    val thumb by produceState<Bitmap?>(null, uriStr) {
        value = if (isImage || isVideo) withContext(Dispatchers.IO) {
            runCatching { ctx.contentResolver.loadThumbnail(uri, Size(800, 800), null) }.getOrNull()
        } else null
    }
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.widthIn(max = 320.dp).clickable {
            runCatching {
                ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }) {
        Column(Modifier.padding(6.dp)) {
            thumb?.let { bmp ->
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Image(bmp.asImageBitmap(), name, Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Fit)
                    if (isVideo) Icon(Icons.Default.PlayCircle, null, Modifier.size(56.dp), tint = Color.White)
                }
            }
            Row(Modifier.padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (thumb == null) Icon(Icons.Default.InsertDriveFile, null, Modifier.size(20.dp))
                Text(name, Modifier.padding(start = if (thumb == null) 6.dp else 0.dp), style = MaterialTheme.typography.labelMedium, maxLines = 2)
            }
        }
    }
}
