package com.example.ghjgh

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer

object HlsDownloader {
    private const val TAG = "HlsDownloader"

    fun interface ProgressListener {
        fun onUpdate(message: String)
    }

    suspend fun download(
        context: Context,
        playlistUrl: String,
        referer: String?,
        userAgent: String?,
        title: String,
        listener: ProgressListener
    ): Boolean = withContext(Dispatchers.IO) {
        var currentUrl = playlistUrl
        var manifestContent: String?
        
        try {
            while (true) {
                listener.onUpdate("Fetching manifest...")
                manifestContent = fetchUrl(currentUrl, referer, userAgent)
                if (manifestContent == null) {
                    listener.onUpdate("Error: Failed to fetch manifest")
                    return@withContext false
                }

                when (val result = M3u8Parser.parse(currentUrl, manifestContent)) {
                    is M3u8Parser.ParseResult.Master -> {
                        Log.d(TAG, "Master playlist detected, switching to variant: ${result.variantUrl}")
                        currentUrl = result.variantUrl
                    }
                    is M3u8Parser.ParseResult.Segments -> {
                        return@withContext downloadSegmentsAndMux(
                            context, result.urls, referer, userAgent, title, listener
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Download failed", e)
            listener.onUpdate("Error: ${e.message}")
            return@withContext false
        }
        @Suppress("UNREACHABLE_CODE")
        return@withContext false
    }

    private fun downloadSegmentsAndMux(
        context: Context,
        segments: List<String>,
        referer: String?,
        userAgent: String?,
        title: String,
        listener: ProgressListener
    ): Boolean {
        if (segments.isEmpty()) {
            listener.onUpdate("Error: No segments found")
            return false
        }

        val tempFile = File(context.cacheDir, "temp_hls_${System.currentTimeMillis()}.ts")
        val outputStream = FileOutputStream(tempFile)

        try {
            listener.onUpdate("Downloading 0/${segments.size}...")
            for ((index, segmentUrl) in segments.withIndex()) {
                if (!downloadSegment(segmentUrl, referer, userAgent, outputStream)) {
                    listener.onUpdate("Error: Failed at segment ${index + 1}")
                    outputStream.close()
                    tempFile.delete()
                    return false
                }
                if (index % 5 == 0 || index == segments.size - 1) {
                    listener.onUpdate("Downloading ${index + 1}/${segments.size}...")
                }
            }
            outputStream.close()

            listener.onUpdate("Processing final video...")
            val fileName = "${title.replace(" ", "_")}_${System.currentTimeMillis()}.mp4"
            
            // Use app-specific external storage to avoid permission issues on Android 11+
            val outputDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            val outputFile = File(outputDir, fileName)

            val success = remux(tempFile, outputFile)
            Log.d(TAG, "Concatenated TS file size: ${tempFile.length()} bytes, track count check pending")
            tempFile.delete()

            if (success) {
                listener.onUpdate("Saved: ${outputFile.absolutePath}")
                Log.d(TAG, "HLS download complete: ${outputFile.absolutePath}")
            }
            return success
        } catch (e: Exception) {
            Log.e(TAG, "Segment processing failed", e)
            listener.onUpdate("Error: ${e.message}")
            return false
        } finally {
            outputStream.close()
            if (tempFile.exists()) tempFile.delete()
        }
    }

    private fun fetchUrl(url: String, referer: String?, userAgent: String?): String? {
        return try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.setRequestProperty("User-Agent", userAgent)
            if (referer != null) connection.setRequestProperty("Referer", referer)
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            
            if (connection.responseCode == 200) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch URL: $url", e)
            null
        }
    }

    private fun downloadSegment(
        url: String,
        referer: String?,
        userAgent: String?,
        outputStream: FileOutputStream
    ): Boolean {
        return try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.setRequestProperty("User-Agent", userAgent)
            if (referer != null) connection.setRequestProperty("Referer", referer)
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            
            if (connection.responseCode == 200) {
                connection.inputStream.use { input ->
                    input.copyTo(outputStream)
                }
                true
            } else false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download segment: $url", e)
            false
        }
    }

    private fun remux(inputFile: File, outputFile: File): Boolean {
        var extractor: MediaExtractor? = null
        var muxer: MediaMuxer? = null
        try {
            extractor = MediaExtractor()
            extractor.setDataSource(inputFile.absolutePath)

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val trackCount = extractor.trackCount
            val trackIndexMap = mutableMapOf<Int, Int>()

            for (i in 0 until trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                    extractor.selectTrack(i)
                    val newIndex = muxer.addTrack(format)
                    trackIndexMap[i] = newIndex
                }
            }

            if (trackIndexMap.isEmpty()) {
                Log.e(TAG, "No valid audio/video tracks found for remuxing")
                return false
            }

            muxer.start()

            val bufferSize = 2 * 1024 * 1024 // 2MB
            val byteBuffer = ByteBuffer.allocate(bufferSize)
            val bufferInfo = MediaCodec.BufferInfo()

            while (true) {
                bufferInfo.offset = 0
                bufferInfo.size = extractor.readSampleData(byteBuffer, 0)
                if (bufferInfo.size < 0) break

                bufferInfo.presentationTimeUs = extractor.sampleTime
                @Suppress("WrongConstant")
                bufferInfo.flags = extractor.sampleFlags
                
                val trackIndex = extractor.sampleTrackIndex
                val muxerTrackIndex = trackIndexMap[trackIndex]
                if (muxerTrackIndex != null) {
                    muxer.writeSampleData(muxerTrackIndex, byteBuffer, bufferInfo)
                }
                extractor.advance()
            }

            muxer.stop()
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Remux failed", e)
            return false
        } finally {
            extractor?.release()
            muxer?.release()
        }
    }
}
