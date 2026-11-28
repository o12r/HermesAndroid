package dev.oscar.hermes

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/** A long pasted text, saved under the app cache and uploaded like a picked file. */
class PasteAttachment(ctx: Context, text: String) : AttachmentSource {
    private val file: File = run {
        val dir = File(ctx.cacheDir, "paste").apply { mkdirs() }
        var f = File(dir, "paste.txt")
        var n = 2
        while (f.exists()) { f = File(dir, "paste-$n.txt"); n++ }
        f.also { it.writeText(text, Charsets.UTF_8) }
    }
    override val name: String = file.name
    override val size: Long = file.length()
    override val mime: String = "text/plain"

    override fun imageJpeg(): ByteArray? = null

    override fun open(): FileReader {
        val raf = RandomAccessFile(file, "r")
        val ch = raf.channel
        val len = ch.size()
        return object : FileReader {
            override val size: Long get() = len
            override fun read(buf: ByteBuffer, pos: Long): Int = ch.read(buf, pos)
            override fun close() { raf.close() }
        }
    }
}
