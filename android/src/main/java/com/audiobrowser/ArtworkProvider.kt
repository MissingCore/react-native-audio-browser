package com.audiobrowser

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.audiobrowser.util.ArtworkSecurityConfig
import com.audiobrowser.util.ArtworkUriMapper
import java.io.File
import java.io.FileNotFoundException
import java.util.Locale

/**
 * Proxies local artwork file URIs (file://) as app-local content:// URIs
 * so Android Auto can read artwork through a stable provider endpoint.
 */
class ArtworkProvider : ContentProvider() {

  override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
    if (mode != "r") throw FileNotFoundException("Unsupported mode: $mode")

    val ctx = context ?: throw FileNotFoundException("Context unavailable")

    val sourceUri = ArtworkUriMapper.extractSourceUri(uri) ?: throw FileNotFoundException("Missing source URI")
    val scheme = sourceUri.scheme?.lowercase(Locale.ROOT) ?: throw FileNotFoundException("Missing scheme")

    // Only `file://` sources are allowed. Reject `content://` and any other schemes.
    if (scheme != ContentResolver.SCHEME_FILE) {
      throw FileNotFoundException("Only file:// artwork URIs are supported; got: $scheme")
    }

    val sourceFile = File(sourceUri.path ?: throw FileNotFoundException("Invalid file URI"))
    if (!sourceFile.exists() || !sourceFile.isFile) throw FileNotFoundException("Source file does not exist: $sourceUri")
    if (!isAllowedAppPath(sourceFile, ctx)) throw FileNotFoundException("Refused access to file outside allowed app directories: $sourceUri")

    return ParcelFileDescriptor.open(sourceFile, ParcelFileDescriptor.MODE_READ_ONLY)
  }

  override fun getType(uri: Uri): String = "image/*"
  override fun onCreate(): Boolean = true
  override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor? = null
  override fun insert(uri: Uri, values: ContentValues?): Uri? = null
  override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
  override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0

  /** Checks to see if the file we're opening belongs to us. */
  private fun isAllowedAppPath(file: File, ctx: Context): Boolean {
    return try {
      val filePath = file.canonicalPath
      // If explicit allowed roots were configured, honor them first
      if (ArtworkSecurityConfig.isUnderAllowedRoots(file)) return true
      val allowedRoots = listOfNotNull(
        ctx.cacheDir?.canonicalPath,
        ctx.filesDir?.canonicalPath,
        ctx.externalCacheDir?.canonicalPath,
        ctx.getExternalFilesDir(null)?.canonicalPath
      )

      for (rootPath in allowedRoots) {
        if (filePath == rootPath || filePath.startsWith(rootPath + File.separator)) {
          return true
        }
      }

      false
    } catch (_: Exception) {
      false
    }
  }
}
