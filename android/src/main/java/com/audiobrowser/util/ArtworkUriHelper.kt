package com.audiobrowser.util

import android.net.Uri
import java.util.Locale

/** Utilities for classifying artwork URIs in Android-only code paths. */
object ArtworkUriHelper {
  private val absoluteUriRegex = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")

  fun isAbsoluteUri(value: String): Boolean {
    return isDataUri(value) || absoluteUriRegex.containsMatchIn(value)
  }

  fun isLocalArtworkUri(value: String): Boolean {
    if (isDataUri(value)) return true
    val scheme = Uri.parse(value).scheme?.lowercase(Locale.ROOT) ?: return false
    return scheme == "content" || scheme == "file" || scheme == "android.resource"
  }

  private fun isDataUri(value: String): Boolean {
    return value.startsWith("data:", ignoreCase = true)
  }
}