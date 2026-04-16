package com.audiobrowser

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.audiobrowser.util.ArtworkContentUriMapper
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest

/**
 * Proxies local artwork URIs (file://, content://, android.resource://) as app-local content://
 * URIs so Android Auto can read artwork through a stable provider endpoint.
 */
class ArtworkContentProvider : ContentProvider() {

  override fun onCreate(): Boolean = true

  override fun query(
    uri: Uri,
    projection: Array<out String>?,
    selection: String?,
    selectionArgs: Array<out String>?,
    sortOrder: String?,
  ): Cursor? = null

  override fun getType(uri: Uri): String = "image/*"

  override fun insert(uri: Uri, values: ContentValues?): Uri? {
    throw UnsupportedOperationException("Read-only provider")
  }

  override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
    throw UnsupportedOperationException("Read-only provider")
  }

  override fun update(
    uri: Uri,
    values: ContentValues?,
    selection: String?,
    selectionArgs: Array<out String>?,
  ): Int {
    throw UnsupportedOperationException("Read-only provider")
  }

  override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
    if (!mode.startsWith("r")) {
      throw FileNotFoundException("Only read mode is supported")
    }

    val ctx = context ?: throw FileNotFoundException("Context unavailable")
    val sourceUri =
      ArtworkContentUriMapper.extractSourceUri(uri)
        ?: throw FileNotFoundException("Missing source URI")

    val sourceScheme = sourceUri.scheme?.lowercase() ?: throw FileNotFoundException("Missing scheme")
    if (
      sourceScheme != ContentResolver.SCHEME_FILE &&
        sourceScheme != ContentResolver.SCHEME_CONTENT &&
        sourceScheme != ContentResolver.SCHEME_ANDROID_RESOURCE
    ) {
      throw FileNotFoundException("Unsupported artwork URI scheme: $sourceScheme")
    }

    val cacheDir = File(ctx.cacheDir, "audiobrowser-artwork")
    if (!cacheDir.exists()) {
      cacheDir.mkdirs()
    }

    // Fast path: open file:// directly.
    if (sourceScheme == ContentResolver.SCHEME_FILE) {
      val sourceFile = File(sourceUri.path ?: throw FileNotFoundException("Invalid file URI"))
      if (!sourceFile.exists() || !sourceFile.isFile) {
        throw FileNotFoundException("Source file does not exist: $sourceUri")
      }
      return ParcelFileDescriptor.open(sourceFile, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    // Fast path: many content/resource providers support opening a file descriptor directly.
    if (
      sourceScheme == ContentResolver.SCHEME_CONTENT ||
        sourceScheme == ContentResolver.SCHEME_ANDROID_RESOURCE
    ) {
      ctx.contentResolver.openFileDescriptor(sourceUri, "r")?.let { return it }
    }

    val targetFile = File(cacheDir, "${sha256(sourceUri.toString())}.img")

    if (!targetFile.exists() || targetFile.length() <= 0L) {
      val tempFile = File(cacheDir, "${targetFile.name}.tmp")
      ctx.contentResolver.openInputStream(sourceUri).use { input ->
        if (input == null) {
          throw FileNotFoundException("Unable to open source artwork URI: $sourceUri")
        }
        tempFile.outputStream().use { output ->
          input.copyTo(output)
        }
      }

      if (targetFile.exists()) {
        targetFile.delete()
      }
      if (!tempFile.renameTo(targetFile)) {
        tempFile.copyTo(targetFile, overwrite = true)
        tempFile.delete()
      }
    }

    return ParcelFileDescriptor.open(targetFile, ParcelFileDescriptor.MODE_READ_ONLY)
  }

  private fun sha256(value: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
    return digest.joinToString("") { "%02x".format(it) }
  }
}
