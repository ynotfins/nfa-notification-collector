package com.nfaalerts.collector.ui

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.net.toUri
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.ConcurrentHashMap

class ConfigTestProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = "application/json"

    override fun openFile(
        uri: Uri,
        mode: String,
    ): ParcelFileDescriptor {
        val id = uri.lastPathSegment ?: throw FileNotFoundException("missing id")
        if (mode.contains('w') && failingWrites.contains(id)) throw FileNotFoundException("synthetic")
        val file = files.computeIfAbsent(id) { File(requireNotNull(context).cacheDir, "config-test-$id.json") }
        val flags =
            if (mode.contains('w')) {
                ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or
                    ParcelFileDescriptor.MODE_WRITE_ONLY
            } else {
                ParcelFileDescriptor.MODE_READ_ONLY
            }
        return ParcelFileDescriptor.open(file, flags)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri? = null

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    companion object {
        private val files = ConcurrentHashMap<String, File>()
        private val failingWrites = ConcurrentHashMap.newKeySet<String>()

        fun uri(id: String): Uri = "content://com.nfaalerts.collector.config-test/$id".toUri()

        fun seed(
            id: String,
            bytes: ByteArray,
            context: android.content.Context,
        ): Uri {
            val file = File(context.cacheDir, "config-test-$id.json")
            file.writeBytes(bytes)
            files[id] = file
            return uri(id)
        }

        fun output(id: String): ByteArray = files.getValue(id).readBytes()

        fun failWrite(id: String): Uri {
            failingWrites += id
            return uri(id)
        }

        fun clear() {
            files.values.forEach(File::delete)
            files.clear()
            failingWrites.clear()
        }
    }
}
