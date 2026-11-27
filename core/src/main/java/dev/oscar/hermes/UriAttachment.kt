package dev.oscar.hermes

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.UUID

/** Android implementation of [AttachmentSource] for a content Uri from the document picker. */
class UriAttachment(private val ctx: Context, private val uri: Uri) : AttachmentSource {
    private val cr: ContentResolver = ctx.contentResolver
    override var name: String = "file"; private set
    override var size: Long = -1L; private set
    override val mime: String

    init {
        cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst()) { it.getString(0)?.let { n -> name = n }; if (!it.isNull(1)) size = it.getLong(1) }
        }
        mime = cr.getType(uri).orEmpty()
    }

    override fun imageJpeg(): ByteArray? {
        val bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(cr, uri)) { dec, info, _ ->
            val s = info.size; val m = maxOf(s.width, s.height)
            if (m > 1600) dec.setTargetSize(s.width * 1600 / m, s.height * 1600 / m)
            dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        val out = ByteArrayOutputStream(); bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
        return out.toByteArray()
    }

    override fun open(): FileReader {
        var pfd: ParcelFileDescriptor? = cr.openFileDescriptor(uri, "r")
        val seekable = pfd != null && size >= 0 && runCatching { FileInputStream(pfd!!.fileDescriptor).channel.position(0) }.isSuccess
        var tmp: File? = null
        var total = size
        if (!seekable) {   // provider gave a pipe / no size: spool to cache so chunks can be read at any offset
            pfd?.close()
            val f = File(ctx.cacheDir, "upload-" + UUID.randomUUID()); tmp = f
            cr.openInputStream(uri)!!.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
            total = f.length(); pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
        }
        val p = pfd!!
        val ch: FileChannel = FileInputStream(p.fileDescriptor).channel
        val len = total
        return object : FileReader {
            override val size: Long get() = len
            override fun read(buf: ByteBuffer, pos: Long): Int = ch.read(buf, pos)
            override fun close() { runCatching { p.close() }; tmp?.delete() }
        }
    }
}
