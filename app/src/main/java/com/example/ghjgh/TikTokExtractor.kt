package com.example.ghjgh

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.regex.Pattern

object TikTokExtractor {
    const val REFERER = "https://www.tiktok.com/"
    private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    data class ExtractionResult(val url: String, val title: String)

    suspend fun extract(url: String): ExtractionResult? = withContext(Dispatchers.IO) {
        try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Referer", REFERER)
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            
            val responseCode = connection.responseCode
            if (responseCode != 200) {
                Log.e("TikTokExtractor", "Failed to fetch page: $responseCode")
                return@withContext null
            }

            val html = connection.inputStream.bufferedReader().use { it.readText() }
            
            // Find __UNIVERSAL_DATA_FOR_REHYDRATION__
            val pattern = Pattern.compile("<script id=\"__UNIVERSAL_DATA_FOR_REHYDRATION__\" type=\"application/json\">(.*?)</script>")
            val matcher = pattern.matcher(html)
            
            if (matcher.find()) {
                val jsonStr = matcher.group(1) ?: return@withContext null
                val json = JSONObject(jsonStr)
                
                // Structure: __DEFAULT_SCOPE__ -> webapp.video-detail -> itemInfo -> itemStruct -> video -> downloadAddr
                val defaultScope = json.optJSONObject("__DEFAULT_SCOPE__") ?: return@withContext null
                val videoDetail = defaultScope.optJSONObject("webapp.video-detail") ?: return@withContext null
                val itemInfo = videoDetail.optJSONObject("itemInfo") ?: return@withContext null
                val itemStruct = itemInfo.optJSONObject("itemStruct") ?: return@withContext null
                val video = itemStruct.optJSONObject("video") ?: return@withContext null
                
                val videoUrl = video.optString("downloadAddr").takeIf { it.isNotEmpty() }
                    ?: video.optString("playAddr").takeIf { it.isNotEmpty() }
                    ?: return@withContext null
                
                val title = itemStruct.optString("desc").takeIf { it.isNotEmpty() } ?: "TikTok Video"
                
                Log.d("TikTokExtractor", "Successfully extracted: $videoUrl")
                return@withContext ExtractionResult(videoUrl, title)
            } else {
                Log.d("TikTokExtractor", "Rehydration script tag not found")
            }
        } catch (e: Exception) {
            Log.e("TikTokExtractor", "Extraction failed", e)
        }
        null
    }
}
