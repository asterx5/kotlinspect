package io.github.asterx5.kotlinspect.internal

import android.content.ClipData
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import io.github.asterx5.kotlinspect.internal.har.HarFiles
import java.io.File
import java.io.FileNotFoundException

/**
 * Serves exported HAR files to the app the user shares them with, read-only and only from
 * cache/kotlinspect/exports. Kotlinspect's own provider (not androidx FileProvider), so it needs no
 * extra dependency and never clashes with the app's provider. Not exported: other apps get access
 * only through the one-time grant on the share intent.
 */
internal class KotlinspectFileProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    private fun fileFor(uri: Uri): File {
        val dir = HarFiles.exportsDirectory()?.let(::File)?.canonicalFile ?: throw FileNotFoundException("No exports")
        val segments = uri.pathSegments
        if (segments.size != 2 || segments[0] != EXPORTS) throw FileNotFoundException(uri.toString())
        val file = File(dir, segments[1]).canonicalFile
        // Nothing outside the export folder, whatever the name contains.
        if (file.parentFile != dir || !file.isFile) throw FileNotFoundException(uri.toString())
        return file
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("Exports are read-only")
        return ParcelFileDescriptor.open(fileFor(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val file = fileFor(uri)
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val row = columns.map { column ->
            when (column) {
                OpenableColumns.DISPLAY_NAME -> file.name
                OpenableColumns.SIZE -> file.length()
                else -> null
            }
        }
        return MatrixCursor(columns, 1).apply { addRow(row.toTypedArray()) }
    }

    override fun getType(uri: Uri): String = MIME_TYPE

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        private const val EXPORTS = "exports"
        private const val MIME_TYPE = "application/json"

        fun uriFor(context: Context, file: File): Uri = Uri.Builder()
            .scheme("content")
            .authority("${context.packageName}.kotlinspect.fileprovider")
            .appendPath(EXPORTS)
            .appendPath(file.name)
            .build()

        /** Opens the share sheet for an exported file. */
        fun share(context: Context, file: File) {
            val uri = uriFor(context, file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = MIME_TYPE
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, file.name)
                clipData = ClipData.newRawUri(file.name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(send, "Share HAR").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(chooser)
        }
    }
}
