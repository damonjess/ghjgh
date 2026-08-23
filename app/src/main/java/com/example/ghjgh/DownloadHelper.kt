package com.example.ghjgh

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import android.webkit.URLUtil
import android.widget.Toast

object DownloadHelper {
    private const val DEFAULT_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    fun enqueueDownload(
        context: Context,
        url: String,
        title: String = "Video",
        cookies: String? = null,
        userAgent: String? = null,
        mimeType: String? = null,
        referer: String? = null
    ): Long {
        Log.d("DownloadHelper", "Enqueueing download for URL: $url")
        try {
            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val uri = Uri.parse(url)
            
            val finalMimeType = mimeType ?: getMimeTypeFromUrl(url)
            
            var fileName = URLUtil.guessFileName(url, null, finalMimeType)
            if (fileName.isNullOrEmpty() || fileName == "downloadfile.bin") {
                fileName = "${title.replace(Regex("[^a-zA-Z0-9]"), "_")}_${System.currentTimeMillis()}"
            }
            
            // Ensure correct extension based on mimeType
            val extension = getExtensionFromMime(finalMimeType)

            if (!fileName.endsWith(extension, ignoreCase = true)) {
                fileName = if (fileName.contains(".")) {
                    fileName.substringBeforeLast(".") + extension
                } else {
                    fileName + extension
                }
            }

            // Final safety check for filename
            fileName = fileName.replace(Regex("[\\\\/:*?\"<>|]"), "_")

            Log.d("DownloadHelper", "Final filename: $fileName, MIME: $finalMimeType")

            val request = DownloadManager.Request(uri)
                .setTitle("Downloading $title")
                .setDescription(fileName)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(false)
                .setMimeType(finalMimeType)

            if (!cookies.isNullOrEmpty()) {
                request.addRequestHeader("Cookie", cookies)
            }
            
            val finalUserAgent = if (userAgent.isNullOrEmpty()) DEFAULT_USER_AGENT else userAgent
            request.addRequestHeader("User-Agent", finalUserAgent)
            
            // Add Referer if provided or guess it
            if (!referer.isNullOrEmpty()) {
                request.addRequestHeader("Referer", referer)
            } else {
                val domain = uri.host
                if (domain != null) {
                    request.addRequestHeader("Referer", "https://$domain/")
                }
            }

            val id = downloadManager.enqueue(request)
            Toast.makeText(context, "Download started: $title", Toast.LENGTH_SHORT).show()
            return id
        } catch (e: Exception) {
            Log.e("DownloadHelper", "Failed to enqueue download", e)
            Toast.makeText(context, "Download failed: ${e.message}", Toast.LENGTH_SHORT).show()
            return -1L
        }
    }

    private fun getMimeTypeFromUrl(url: String): String {
        return when {
            url.contains(".mp4") -> "video/mp4"
            url.contains(".mkv") -> "video/x-matroska"
            url.contains(".webm") -> "video/webm"
            url.contains(".mov") -> "video/quicktime"
            url.contains(".avi") -> "video/x-msvideo"
            url.contains(".m3u8") -> "application/x-mpegURL"
            else -> "video/mp4"
        }
    }

    private fun getExtensionFromMime(mimeType: String): String {
        return when (mimeType) {
            "video/mp4" -> ".mp4"
            "video/webm" -> ".webm"
            "video/x-matroska" -> ".mkv"
            "video/quicktime" -> ".mov"
            "video/x-msvideo" -> ".avi"
            "application/x-mpegURL" -> ".m3u8"
            else -> ".mp4"
        }
    }
}