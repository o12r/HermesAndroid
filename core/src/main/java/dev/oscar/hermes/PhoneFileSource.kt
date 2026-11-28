package dev.oscar.hermes

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.webkit.MimeTypeMap
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/** A file on the phone, by absolute path (FILE: lines in a message). Reads through java.io, so only files this app may open. */
class PhoneFileSource private constructor(private val f: File) : AttachmentSource {
    override val name: String get() = f.name
    override val size: Long = f.length()
    override val mime: String = MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()).orEmpty()

    override fun imageJpeg(): ByteArray? {
        val bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(f)) { dec, info, _ ->
            val s = info.size; val m = maxOf(s.width, s.height)
            if (m > 1600) dec.setTargetSize(s.width * 1600 / m, s.height * 1600 / m)
            dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        val out = ByteArrayOutputStream(); bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
        return out.toByteArray()
    }

    override fun open(): FileReader {
        val raf = RandomAccessFile(f, "r"); val ch = raf.channel; val len = raf.length()
        return object : FileReader {
            override val size: Long get() = len
            override fun read(buf: ByteBuffer, pos: Long): Int = ch.read(buf, pos)
            override fun close() { runCatching { raf.close() } }
        }
    }

    companion object {
        /** The source for [path] if it is a file this app can actually open (its own files, or shared-storage files the OS grants); else null. */
        fun open(path: String): PhoneFileSource? {
            val f = File(path)
            if (!f.isFile) return null
            return if (runCatching { RandomAccessFile(f, "r").close() }.isSuccess) PhoneFileSource(f) else null
        }
    }
}
