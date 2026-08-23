package com.example.ghjgh

import android.util.Log

object M3u8Parser {
    private const val TAG = "M3u8Parser"

    fun parse(playlistUrl: String, manifestContent: String): ParseResult {
        val lines = manifestContent.lines()
        
        // Check if it's a master playlist
        if (manifestContent.contains("#EXT-X-STREAM-INF")) {
            var bestVariantUrl: String? = null
            var maxBandwidth = -1
            
            for (i in lines.indices) {
                val line = lines[i].trim()
                if (line.startsWith("#EXT-X-STREAM-INF")) {
                    val bandwidth = Regex("BANDWIDTH=(\\d+)").find(line)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    if (bandwidth > maxBandwidth) {
                        maxBandwidth = bandwidth
                        // The URL is usually on the next non-comment line
                        for (j in i + 1 until lines.size) {
                            val nextLine = lines[j].trim()
                            if (nextLine.isNotEmpty() && !nextLine.startsWith("#")) {
                                bestVariantUrl = nextLine
                                break
                            }
                        }
                    }
                }
            }
            
            if (bestVariantUrl != null) {
                return ParseResult.Master(resolveUrl(playlistUrl, bestVariantUrl))
            }
        }

        // It's a media playlist
        val segmentUrls = mutableListOf<String>()
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            segmentUrls.add(resolveUrl(playlistUrl, trimmed))
        }
        
        Log.d(TAG, "Parsed ${segmentUrls.size} segments")
        return ParseResult.Segments(segmentUrls)
    }

    private fun resolveUrl(baseUrl: String, relativeUrl: String): String {
        if (relativeUrl.startsWith("http")) return relativeUrl
        val lastSlash = baseUrl.lastIndexOf("/")
        return baseUrl.substring(0, lastSlash + 1) + relativeUrl
    }

    sealed class ParseResult {
        data class Master(val variantUrl: String) : ParseResult()
        data class Segments(val urls: List<String>) : ParseResult()
    }
}
