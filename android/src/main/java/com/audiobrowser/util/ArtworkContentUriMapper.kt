package com.audiobrowser.util

import android.content.ContentResolver
import android.content.Context
import android.net.Uri

/** Builds app-local content:// URIs for artwork so Android Auto can open them reliably. */
object ArtworkContentUriMapper {
  private const val AUTHORITY_SUFFIX = ".audiobrowser.artwork"
  private const val PARAM_SOURCE_URI = "src"

  fun authorityFor(context: Context): String {
    return "${context.packageName}$AUTHORITY_SUFFIX"
  }

  /**
   * Converts supported source URIs to an app-local content URI handled by ArtworkContentProvider.
   * Returns the original URI string when no conversion is required.
   */
  fun mapToLocalContentUri(context: Context, source: String): String {
    val parsed = try {
      Uri.parse(source)
    } catch (_: Exception) {
      return source
    }

    val scheme = parsed.scheme?.lowercase() ?: return source

    // Already mapped by this provider.
    if (scheme == ContentResolver.SCHEME_CONTENT && parsed.authority == authorityFor(context)) {
      return source
    }

    // Only local Android-supported artwork schemes are rewritten.
    if (
      scheme != ContentResolver.SCHEME_FILE &&
        scheme != ContentResolver.SCHEME_CONTENT &&
        scheme != ContentResolver.SCHEME_ANDROID_RESOURCE
    ) {
      return source
    }

    return Uri.Builder()
      .scheme(ContentResolver.SCHEME_CONTENT)
      .authority(authorityFor(context))
      .appendPath("open")
      .appendQueryParameter(PARAM_SOURCE_URI, source)
      .build()
      .toString()
  }

  fun extractSourceUri(uri: Uri): Uri? {
    val source = uri.getQueryParameter(PARAM_SOURCE_URI) ?: return null
    return try {
      Uri.parse(source)
    } catch (_: Exception) {
      null
    }
  }
}
