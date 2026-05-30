package com.audiobrowser.util

import android.content.Context
import android.net.Uri
import timber.log.Timber
import java.io.File

/**
 * Holds configuration for allowed artwork file roots and enforces security policies.
 *
 * The JS side can pass `content://`, `file://` or plain file paths as allowed roots.
 * This helper attempts to resolve them to canonical filesystem paths.
 *
 * Security model:
 * - If `allowedArtworkContentRoots` is configured: Files must be directly under one
 *   of the specified roots (not in subfolders). Paths are resolved to canonicalize
 *   escaping sequences (e.g., "..").
 * - If `allowedArtworkContentRoots` is empty/unspecified: Default app directories
 *   are allowed, including subfolders.
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

  /**
   * Checks if a file is allowed to be served. If `allowedArtworkContentRoots` is
   * configured, the file must be directly under one of those roots (not in a subfolder).
   * Path escaping is resolved before checking (e.g., "..").
   *
   * If `allowedArtworkContentRoots` is empty/unspecified, default app directories
   * are checked, including subfolders.
   *
   * @param file The file to check
   * @param ctx Android context for accessing default app directories
   * @return true if the file is allowed, false otherwise
   */
  fun isAllowedPath(file: File, ctx: Context): Boolean {
    return try {
      val filePath = file.canonicalPath

      // If explicit allowed roots were configured, enforce direct-parent-only policy
      if (allowedCanonicalRoots.isNotEmpty()) {
        return isDirectlyUnderAllowedRoots(filePath)
      }

      // No explicit roots: check default app directories (allow subfolders)
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

  /**
   * Checks if a file is directly under one of the allowed roots (not in a subfolder).
   * The file path is canonicalized to resolve escaping sequences.
   *
   * @param filePath The canonical file path to check
   * @return true if the file's parent directory is exactly one of the allowed roots
   */
  private fun isDirectlyUnderAllowedRoots(filePath: String): Boolean {
    for (root in allowedCanonicalRoots) {
      val fileParent = File(filePath).parent ?: continue
      // Check if file's parent directory is exactly the root (using canonical comparison)
      if (fileParent == root) {
        return true
      }
    }
    return false
  }
}
