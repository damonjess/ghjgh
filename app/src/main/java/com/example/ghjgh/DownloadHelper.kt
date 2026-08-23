package com.example.ghjgh

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.widget.Toast

object DownloadHelper {
    fun enqueueDownload(context: Context, url: String, title: String = "Video") {
        try {
            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val uri = Uri.parse(url)
            val fileName = uri.lastPathSegment ?: "${title.replace(" ", "_")}_${System.currentTimeMillis()}.mp4"

            val request = DownloadManager.Request(uri)
                .setTitle("Downloading $title")
                .setDescription(fileName)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(false)

            downloadManager.enqueue(request)
            Toast.makeText(context, "Download started: $title", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, "Download failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}