package com.audiobrowser.util

import android.content.Context
import android.net.Uri
import timber.log.Timber
import java.io.File

/**
 * Holds configuration for allowed artwork file roots.
 *
 * The JS side can pass `content://`, `file://` or plain file paths. This helper
 * attempts to resolve provided roots to canonical filesystem paths when possible.
 */
object ArtworkSecurityConfig {
  @Volatile private var allowedCanonicalRoots: List<String> = emptyList()

  fun setAllowedContentRoots(ctx: Context, roots: List<String>?) {
    if (roots == null) {
      allowedCanonicalRoots = emptyList()
      return
    }

    val canonicalPaths = mutableListOf<String>()

    for (root in roots) {
      try {
        val uri = Uri.parse(root)
        val scheme = uri.scheme?.lowercase() ?: ""

        when (scheme) {
          "file" -> {
            val f = File(uri.path ?: continue)
            canonicalPaths.add(f.canonicalPath)
          }
          "content" -> {
            // If the content URI includes a `src` query parameter that is a file:// URI,
            // extract it and resolve to a filesystem path. Otherwise we cannot resolve
            // the content provider's internal path here and will ignore the entry.
            val src = uri.getQueryParameter("src")
            if (src != null && src.startsWith("file://")) {
              val f = File(Uri.parse(src).path ?: continue)
              canonicalPaths.add(f.canonicalPath)
            } else {
              Timber.w("ArtworkSecurityConfig: content root $root cannot be resolved to a file path; ignoring")
            }
          }
          else -> {
            // Treat as plain filesystem path
            val f = File(root)
            if (f.exists()) {
              canonicalPaths.add(f.canonicalPath)
            } else {
              // attempt to canonicalize even if doesn't exist to normalize path strings
              try {
                canonicalPaths.add(f.canonicalPath)
              } catch (e: Exception) {
                Timber.w(e, "ArtworkSecurityConfig: failed to canonicalize path $root")
              }
            }
          }
        }
      } catch (e: Exception) {
        Timber.w(e, "ArtworkSecurityConfig: failed to parse allowed root: $root")
      }
    }

    allowedCanonicalRoots = canonicalPaths
    Timber.d("ArtworkSecurityConfig: allowed roots set: %s", allowedCanonicalRoots)
  }

  fun isUnderAllowedRoots(file: File): Boolean {
    if (allowedCanonicalRoots.isEmpty()) return false
    val path = try { file.canonicalPath } catch (_: Exception) { return false }
    for (root in allowedCanonicalRoots) {
      if (path == root || path.startsWith(root + File.separator)) return true
    }
    return false
  }
}
