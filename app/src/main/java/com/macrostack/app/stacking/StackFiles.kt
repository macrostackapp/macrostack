package com.macrostack.app.stacking

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.MediaStore
import com.macrostack.app.fusion.GrayImage
import com.macrostack.app.fusion.RgbImage
import com.macrostack.app.fusion.StackFusion
import com.macrostack.app.stack.StackSaver

/** Finds a stack's photos in the gallery and decodes them for [StackFusion]. */
object StackFiles {

    data class FrameFile(val uri: Uri, val name: String, val mime: String)

    /** The stack's frames in shooting order — JPEGs when there are any, otherwise DNGs. */
    fun query(context: Context, relativePath: String): List<FrameFile> {
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val path = relativePath.trimEnd('/') + "/"
        val all = mutableListOf<FrameFile>()
        context.contentResolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.MIME_TYPE),
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
            arrayOf(path),
            "${MediaStore.MediaColumns.DISPLAY_NAME} ASC",
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            while (cursor.moveToNext()) {
                all += FrameFile(
                    uri = ContentUris.withAppendedId(collection, cursor.getLong(idCol)),
                    name = cursor.getString(nameCol) ?: "",
                    mime = cursor.getString(mimeCol) ?: "",
                )
            }
        }
        val jpegs = all.filter { it.mime == StackSaver.JPEG_MIME }
        return if (jpegs.size >= 2) jpegs else all.filter { it.mime == StackSaver.DNG_MIME }
    }

    /** Decodes [uri] shrunk by the power of two [sampleSize] gives for the photo's full size. */
    fun decode(resolver: ContentResolver, uri: Uri, sampleSize: (width: Int, height: Int) -> Int): Bitmap {
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE // pixels must be readable
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
            val s = sampleSize(info.size.width, info.size.height)
            if (s > 1) decoder.setTargetSampleSize(s)
        }
        if (bitmap.config == Bitmap.Config.ARGB_8888) return bitmap
        return bitmap.copy(Bitmap.Config.ARGB_8888, false).also { bitmap.recycle() }
    }

    class Frame(private val resolver: ContentResolver, private val uri: Uri) : StackFusion.Frame {
        override fun loadSmall(): GrayImage {
            val bitmap = decode(resolver, uri, StackFusion::alignSampleSize)
            try {
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                return GrayImage.luma(pixels, bitmap.width, bitmap.height)
            } finally {
                bitmap.recycle()
            }
        }

        override fun openFull(sampleSize: Int): StackFusion.FullFrame = BitmapFrame(decode(resolver, uri) { _, _ -> sampleSize })
    }

    /** A decoded photo; its pixels live outside the Java heap until [close]. */
    private class BitmapFrame(private val bitmap: Bitmap) : StackFusion.FullFrame {
        override val width: Int = bitmap.width
        override val height: Int = bitmap.height
        override fun readRows(y: Int, count: Int, dst: IntArray) = bitmap.getPixels(dst, 0, width, 0, y, width, count)
        override fun close() = bitmap.recycle()
    }

    fun RgbImage.toBitmap(): Bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
}
