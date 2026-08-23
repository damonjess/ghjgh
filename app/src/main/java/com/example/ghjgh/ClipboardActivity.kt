package com.example.ghjgh

import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.mugames.vidsnapkit.dataholders.Result
import com.mugames.vidsnapkit.extractor.Extractor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ClipboardActivity : AppCompatActivity() {

    private var finished = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // No UI — completely invisible

        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
        val url = extractUrl(text)

        if (url != null) {
            Toast.makeText(this, "Detecting video…", Toast.LENGTH_SHORT).show()
            extractAndDownload(url)
        } else {
            Toast.makeText(this, "Clipboard empty — copy a link first", Toast.LENGTH_SHORT).show()
            finishAndRemoveTask()
        }

        // Safety timeout so this never hangs invisible
        lifecycleScope.launch {
            delay(30000)
            if (!finished) {
                Toast.makeText(this@ClipboardActivity, "Detection timed out", Toast.LENGTH_SHORT).show()
                finishAndRemoveTask()
            }
        }
    }

    private fun extractUrl(text: String): String? {
        val urlPattern = "(https?://[\\w\\-]+(\\.[\\w\\-]+)+[\\w\\-.,@?^=%&:/~+#]*[\\w\\-@?^=%&/~+#])".toRegex()
        return urlPattern.find(text)?.value
    }

    private fun extractAndDownload(url: String) {
        val extractor = Extractor.findExtractor(url)
        if (extractor != null) {
            if (url.contains("instagram.com")) {
                val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                val savedCookies = prefs.getString("ig_cookies", "")
                if (!savedCookies.isNullOrEmpty()) {
                    extractor.cookies = savedCookies
                }
            }

            lifecycleScope.launch {
                extractor.start { result ->
                    runOnUiThread {
                        when (result) {
                            is Result.Success -> {
                                val mediaList = result.formats
                                if (mediaList.isNotEmpty()) {
                                    val format = mediaList[0]
                                    val videoResource = format.videoData.firstOrNull()
                                    val videoUrl = videoResource?.url ?: format.url
                                    val mimeType = videoResource?.mimeType
                                    
                                    val userAgent = "Mozilla/5.0 (Linux; Android 13; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36"
                                    DownloadHelper.enqueueDownload(
                                        this@ClipboardActivity,
                                        videoUrl,
                                        format.title,
                                        null, // cookies
                                        userAgent,
                                        mimeType
                                    )
                                } else {
                                    Toast.makeText(this@ClipboardActivity, "No video found", Toast.LENGTH_LONG).show()
                                }
                            }
                            is Result.Failed -> {
                                Toast.makeText(this@ClipboardActivity, "Error: ${result.error.message}", Toast.LENGTH_LONG).show()
                            }
                            else -> {}
                        }
                        finished = true
                        finishAndRemoveTask()
                    }
                }
            }
        } else {
            val userAgent = "Mozilla/5.0 (Linux; Android 13; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36"
            DownloadHelper.enqueueDownload(
                this, 
                url, 
                "Video", 
                null, 
                userAgent
            )
            finished = true
            finishAndRemoveTask()
        }
    }
}