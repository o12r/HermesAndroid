package dev.oscar.hermes

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.security.MessageDigest

/**
 * Chat media the agent sent (PC files): kept in cache/media, served to the UI through [FILE_AUTHORITY].
 * Copied to the public Downloads folder only when the user taps Save/Download.
 */
object MediaCache {
    /** Cached media not written for longer than this is deleted at app start. */
    const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000

    fun dir(ctx: Context): File = File(ctx.cacheDir, "media").apply { mkdirs() }

    /** Cache file for PC file [name]: a hash, so any file name is safe. */
    fun fileFor(ctx: Context, name: String): File {
        val hash = MessageDigest.getInstance("SHA-1").digest(name.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(dir(ctx), hash)
    }

    fun uriFor(ctx: Context, file: File): Uri = FileProvider.getUriForFile(ctx, FILE_AUTHORITY, file)

    /** The cache file behind [uriStr] (from [uriFor]); null for other uris (Downloads) or when the file was removed. */
    fun fileOf(ctx: Context, uriStr: String): File? {
        val u = Uri.parse(uriStr)
        if (u.authority != FILE_AUTHORITY) return null
        val name = u.lastPathSegment?.takeIf { it.isNotEmpty() && !it.contains('/') } ?: return null
        return File(dir(ctx), name).takeIf { it.isFile }
    }

    /** Deletes cached media older than [MAX_AGE_MS]. Called at app start. */
    fun sweep(ctx: Context, now: Long = System.currentTimeMillis()) {
        dir(ctx).listFiles()?.forEach { if (it.isFile && now - it.lastModified() > MAX_AGE_MS) it.delete() }
    }
}
