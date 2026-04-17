package com.audiobrowser

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.audiobrowser.util.ArtworkUriMapper
import java.io.File
import java.io.FileNotFoundException

/**
 * Proxies local artwork URIs (file://, content://) as app-local content://
 * URIs so Android Auto can read artwork through a stable provider endpoint.
 */
class ArtworkProvider : ContentProvider() {

  override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
    val ctx = context ?: throw FileNotFoundException("Context unavailable")

    val sourceUri = ArtworkUriMapper.extractSourceUri(uri) ?: throw FileNotFoundException("Missing source URI")
    val sourceScheme = sourceUri.scheme?.lowercase() ?: throw FileNotFoundException("Missing scheme")

    when (sourceScheme) {
      // Open `file://` directly.
      ContentResolver.SCHEME_FILE -> {
        val sourceFile = File(sourceUri.path)
        if (!sourceFile.exists()) return null
        return ParcelFileDescriptor.open(sourceFile, ParcelFileDescriptor.MODE_READ_ONLY)
      }

      // Many content providers support opening a file descriptor directly.
      ContentResolver.SCHEME_CONTENT -> {
        ctx.contentResolver.openFileDescriptor(sourceUri, "r")?.let { return it }
      }
    }

    throw FileNotFoundException("Unsupported artwork URI scheme: $sourceScheme")
  }

  override fun getType(uri: Uri): String = "image/*"
  override fun onCreate(): Boolean = true
  override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor? = null
  override fun insert(uri: Uri, values: ContentValues?): Uri? = null
  override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
  override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0
}
