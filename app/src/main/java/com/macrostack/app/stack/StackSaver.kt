package com.macrostack.app.stack

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Writes stack frames to Pictures/MacroStack/Stack_<date>_<time>/, one folder per stack. */
class StackSaver(context: Context) {

    data class Folder(val name: String, val relativePath: String)

    private val resolver = context.applicationContext.contentResolver

    fun newFolder(): Folder {
        val name = "Stack_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return Folder(name, "${Environment.DIRECTORY_PICTURES}/$ROOT_FOLDER/$name")
    }

    /**
     * Saves a stacked result to Pictures/MacroStack/Stacked — away from the frame folders, so it
     * never ends up inside a stack you copy to a laptop.
     */
    fun saveStacked(stackName: String, image: Bitmap, exif: Map<String, String> = emptyMap()): Uri {
        val folder = Folder(stackName, "${Environment.DIRECTORY_PICTURES}/$ROOT_FOLDER/$STACKED_FOLDER")
        return save(folder, "${stackName}_stacked.jpg", JPEG_MIME, exif) { out ->
            if (!image.compress(Bitmap.CompressFormat.JPEG, STACKED_QUALITY, out)) throw IOException("JPEG encoding failed")
        }
    }

    /** Saves frame [index] (0-based) as a JPEG. Runs on a background thread. */
    fun saveJpeg(folder: Folder, index: Int, jpeg: ByteArray): Uri =
        save(folder, index, "jpg", JPEG_MIME) { it.write(jpeg) }

    /** Saves frame [index] (0-based) as a DNG produced by [write]. Runs on a background thread. */
    fun saveDng(folder: Folder, index: Int, write: (OutputStream) -> Unit): Uri =
        save(folder, index, "dng", DNG_MIME, write)

    private fun save(folder: Folder, index: Int, extension: String, mimeType: String, write: (OutputStream) -> Unit): Uri =
        save(folder, "%s_%03d.%s".format(Locale.US, folder.name, index + 1, extension), mimeType, write = write)

    private fun save(
        folder: Folder,
        fileName: String,
        mimeType: String,
        exif: Map<String, String> = emptyMap(),
        write: (OutputStream) -> Unit,
    ): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, folder.relativePath)
            put(MediaStore.MediaColumns.DATE_TAKEN, System.currentTimeMillis())
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: throw IOException("Could not create $fileName")
        try {
            val out = resolver.openOutputStream(uri) ?: throw IOException("Could not open $fileName")
            BufferedOutputStream(out, WRITE_BUFFER_BYTES).use(write)
            if (exif.isNotEmpty()) writeExif(uri, exif)
            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
            return uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    /** Adds EXIF tags to a saved JPEG. The photo is fine without them, so failures are only logged. */
    private fun writeExif(uri: Uri, tags: Map<String, String>) {
        try {
            resolver.openFileDescriptor(uri, "rw")?.use { fd ->
                val exif = ExifInterface(fd.fileDescriptor)
                for ((tag, value) in tags) exif.setAttribute(tag, value)
                exif.saveAttributes()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't write EXIF to $uri", e)
        }
    }

    companion object {
        private const val TAG = "StackSaver"
        const val ROOT_FOLDER = "MacroStack"
        const val STACKED_FOLDER = "Stacked"
        private const val STACKED_QUALITY = 95
        const val JPEG_MIME = "image/jpeg"
        const val DNG_MIME = "image/x-adobe-dng"
        private const val WRITE_BUFFER_BYTES = 1 shl 20
    }
}
