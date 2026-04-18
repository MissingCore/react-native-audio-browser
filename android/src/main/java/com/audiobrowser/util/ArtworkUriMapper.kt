package com.audiobrowser.util

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.margelo.nitro.NitroModules
import java.util.Locale

/** Builds app-local content:// URIs for artwork so Android Auto can open them reliably. */
object ArtworkUriMapper {
  private const val AUTHORITY_SUFFIX = ".audiobrowser.artwork"
  private const val PARAM_SOURCE_URI = "src"

  private fun authorityFor(context: Context): String {
    return "${context.packageName}$AUTHORITY_SUFFIX"
  }

  /**
   * Converts supported source URIs to an app-local content URI handled by ArtworkProvider.
   * If `context` is omitted, Nitro's application context will be used when available.
   * Returns the original URI string when no conversion is required or context is unavailable.
   */
  fun mapToLocalContentUri(source: String, context: Context? = NitroModules.applicationContext): String {
    val ctx = context ?: return source

    val parsed = Uri.parse(source)

    val scheme = parsed.scheme?.lowercase(Locale.ROOT) ?: return source

    // Already mapped by this provider.
    if (scheme == ContentResolver.SCHEME_CONTENT && parsed.authority == authorityFor(ctx)) {
      return source
    }

    // Only local Android-supported artwork schemes are rewritten.
    if (scheme != ContentResolver.SCHEME_FILE) return source

    return Uri.Builder()
      .scheme(ContentResolver.SCHEME_CONTENT)
      .authority(authorityFor(ctx))
      // A path is required for the file descriptor on the `content://` uri in the `src` query parameter to be detected in Android Auto.
      .appendPath("open")
      .appendQueryParameter(PARAM_SOURCE_URI, source)
      .build()
      .toString()
  }

  fun extractSourceUri(uri: Uri): Uri? {
    val source = uri.getQueryParameter(PARAM_SOURCE_URI) ?: return null
    val parsed = Uri.parse(source)
    val scheme = parsed.scheme?.lowercase(Locale.ROOT) ?: return null

    if (scheme != ContentResolver.SCHEME_FILE) return null
    return parsed
  }
}
