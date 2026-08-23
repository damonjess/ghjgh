package com.example.ghjgh

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.switchmaterial.SwitchMaterial
import com.mugames.vidsnapkit.extractor.Extractor
import com.mugames.vidsnapkit.dataholders.Result
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var etUrl: EditText
    private lateinit var btnDownload: Button
    private lateinit var btnPaste: Button
    private lateinit var tvAdvanced: TextView
    private lateinit var layoutAdvanced: LinearLayout
    private lateinit var etCookies: EditText
    private lateinit var btnSaveCookies: Button
    private lateinit var switchFloating: SwitchMaterial
    private lateinit var tvStatus: TextView
    private lateinit var fabDetect: FloatingActionButton
    private lateinit var hiddenWebView: WebView

    private val userAgentString = "Mozilla/5.0 (Linux; Android 13; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36"
    
    private var downloadId: Long = -1

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (!isGranted) {
            Toast.makeText(this, "Notification permission denied. You won't see download progress.", Toast.LENGTH_LONG).show()
        }
    }

    private val prefs by lazy { getSharedPreferences("app_prefs", Context.MODE_PRIVATE) }

    private val overlayPermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Overlay permission denied", Toast.LENGTH_SHORT).show()
            switchFloating.isChecked = false
        } else {
            startFloatingService()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etUrl = findViewById(R.id.etUrl)
        btnDownload = findViewById(R.id.btnDownload)
        btnPaste = findViewById(R.id.btnPaste)
        tvAdvanced = findViewById(R.id.tvAdvanced)
        layoutAdvanced = findViewById(R.id.layoutAdvanced)
        etCookies = findViewById(R.id.etCookies)
        btnSaveCookies = findViewById(R.id.btnSaveCookies)
        switchFloating = findViewById(R.id.switchFloating)
        tvStatus = findViewById(R.id.tvStatus)
        fabDetect = findViewById(R.id.fabDetect)
        hiddenWebView = findViewById(R.id.hiddenWebView)

        setupHiddenWebView()

        etCookies.setText(prefs.getString("ig_cookies", ""))
        switchFloating.isChecked = isServiceRunning(FloatingWidgetService::class.java)

        btnDownload.setOnClickListener {
            val url = etUrl.text.toString().trim()
            if (url.isNotEmpty()) {
                extractAndDownload(url)
            } else {
                Toast.makeText(this, "Please enter a URL", Toast.LENGTH_SHORT).show()
            }
        }

        btnPaste.setOnClickListener {
            pasteFromClipboard()
        }

        fabDetect.setOnClickListener {
            detectAndDownloadFromClipboard()
        }

        tvAdvanced.setOnClickListener {
            layoutAdvanced.visibility = if (layoutAdvanced.visibility == View.GONE) View.VISIBLE else View.GONE
        }

        btnSaveCookies.setOnClickListener {
            val cookies = etCookies.text.toString().trim()
            prefs.edit().putString("ig_cookies", cookies).apply()
            Toast.makeText(this, "Cookies saved!", Toast.LENGTH_SHORT).show()
            layoutAdvanced.visibility = View.GONE
        }

        switchFloating.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (Settings.canDrawOverlays(this)) {
                    startFloatingService()
                } else {
                    checkOverlayPermission()
                }
            } else {
                stopFloatingService()
            }
        }

        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(onDownloadComplete, filter, RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(onDownloadComplete, filter)
        }

        checkNotificationPermission()
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun startFloatingService() {
        val intent = Intent(this, FloatingWidgetService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopFloatingService() {
        stopService(Intent(this, FloatingWidgetService::class.java))
    }

    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        return try {
            val manager = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            manager.getRunningServices(Int.MAX_VALUE).any {
                it.service.className == serviceClass.name
            }
        } catch (_: Exception) {
            false
        }
    }

    override fun onResume() {
        super.onResume()
        checkClipboardForUrl()
        switchFloating.isChecked = isServiceRunning(FloatingWidgetService::class.java)
    }

    private fun checkOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            val rootView = findViewById<View>(android.R.id.content)
            Snackbar.make(rootView, "Overlay permission required for detection", Snackbar.LENGTH_INDEFINITE)
                .setAction("Enable") {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    overlayPermissionLauncher.launch(intent)
                }.show()
        }
    }

    private fun pasteFromClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val item = clipboard.primaryClip?.getItemAt(0)
        val text = item?.text?.toString()
        if (!text.isNullOrEmpty()) {
            etUrl.setText(text)
        } else {
            Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
        }
    }

    private fun detectAndDownloadFromClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val item = clipboard.primaryClip?.getItemAt(0)
        val text = item?.text?.toString() ?: ""
        val url = extractUrl(text)

        if (url != null) {
            etUrl.setText(url)
            extractAndDownload(url)
        } else {
            Toast.makeText(this, "No valid link found in clipboard", Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkClipboardForUrl() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val item = clipboard.primaryClip?.getItemAt(0)
        val text = item?.text?.toString() ?: ""
        val url = extractUrl(text)
        
        if (url != null && (url.contains("instagram.com") || url.contains("facebook.com") || url.contains("tiktok.com"))) {
            val rootView = findViewById<View>(android.R.id.content)
            Snackbar.make(rootView, "Link detected in clipboard", Snackbar.LENGTH_LONG)
                .setAction("Download") {
                    etUrl.setText(url)
                    extractAndDownload(url)
                }.show()
        }
    }

    private fun extractUrl(text: String): String? {
        val urlPattern = "(https?://[\\w\\-]+(\\.[\\w\\-]+)+[\\w\\-.,@?^=%&:/~+#]*[\\w\\-@?^=%&/~+#])".toRegex()
        return urlPattern.find(text)?.value
    }

    private fun extractAndDownload(url: String) {
        var finalUrl = url.trim()
        if (!finalUrl.startsWith("http")) {
            finalUrl = "https://$finalUrl"
        }

        Log.d("Downloader", "Starting extraction for: $finalUrl")
        tvStatus.text = "Fetching video details..."
        btnDownload.isEnabled = false

        lifecycleScope.launch {
            val resolvedUrl = resolveRedirects(finalUrl)
            Log.d("Downloader", "Resolved URL: $resolvedUrl")
            
            runOnUiThread {
                val isTikTok = resolvedUrl.contains("tiktok.com")
                val isHls = resolvedUrl.contains(".m3u8")
                val isWebViewFallbackSite = resolvedUrl.contains("pornhub.com") || 
                                           resolvedUrl.contains("douyin.com") ||
                                           resolvedUrl.contains("instagram.com/reels")

                if (isHls) {
                    tvStatus.text = "HLS stream detected — downloading segments..."
                    lifecycleScope.launch {
                        val success = HlsDownloader.download(
                            context = this@MainActivity,
                            playlistUrl = resolvedUrl,
                            referer = resolvedUrl,
                            userAgent = userAgentString,
                            title = "video",
                            listener = { message ->
                                runOnUiThread { tvStatus.text = message }
                            }
                        )
                        runOnUiThread {
                            if (success) {
                                // Downloader already set the final path message
                            } else if (!tvStatus.text.contains("Error:")) {
                                tvStatus.text = "HLS download failed — check Logcat"
                            }
                            btnDownload.isEnabled = true
                        }
                    }
                    return@runOnUiThread
                }

                if (isTikTok) {
                    tvStatus.text = "Fetching video details..."
                    lifecycleScope.launch {
                        val result = TikTokExtractor.extract(resolvedUrl)
                        runOnUiThread {
                            if (result != null) {
                                downloadId = DownloadHelper.enqueueDownload(
                                    this@MainActivity,
                                    result.url,
                                    result.title,
                                    null,
                                    userAgentString,
                                    "video/mp4",
                                    TikTokExtractor.REFERER
                                )
                                tvStatus.text = "Download started"
                            } else {
                                Log.d("Downloader", "TikTok JSON extraction failed, trying WebView fallback")
                                extractWithWebView(resolvedUrl)
                            }
                        }
                    }
                    return@runOnUiThread
                }

                if (isWebViewFallbackSite) {
                    Log.d("Downloader", "Using WebView extraction for $resolvedUrl")
                    extractWithWebView(resolvedUrl)
                    return@runOnUiThread
                }

                val extractor = Extractor.findExtractor(resolvedUrl)
                if (extractor != null) {
                    Log.d("Downloader", "Found extractor for $resolvedUrl")
                    if (resolvedUrl.contains("instagram.com")) {
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
                                        Log.d("Downloader", "Extraction success, found ${mediaList.size} formats")
                                        if (mediaList.isNotEmpty()) {
                                            val format = mediaList[0]
                                            val videoResource = format.videoData.firstOrNull()
                                            val videoUrl = videoResource?.url ?: format.url
                                            val mimeType = videoResource?.mimeType
                                            
                                            downloadId = DownloadHelper.enqueueDownload(
                                                this@MainActivity, 
                                                videoUrl, 
                                                format.title,
                                                null,
                                                userAgentString,
                                                mimeType
                                            )
                                            tvStatus.text = "Download started"
                                        } else {
                                            Log.e("Downloader", "No media formats found")
                                            tvStatus.text = "Error: No video found at this link"
                                            btnDownload.isEnabled = true
                                        }
                                    }
                                    is Result.Failed -> {
                                        Log.e("Downloader", "Extraction failed: ${result.error.message}")
                                        if (result.error.message?.contains("safe analyse") == true) {
                                            tvStatus.text = "Retrying with advanced extraction..."
                                            extractWithWebView(resolvedUrl)
                                        } else {
                                            tvStatus.text = "Error: ${result.error.message}"
                                            btnDownload.isEnabled = true
                                        }
                                    }
                                    else -> {}
                                }
                            }
                        }
                    }
                } else {
                    Log.d("Downloader", "No extractor found, attempting direct download")
                    downloadId = DownloadHelper.enqueueDownload(this@MainActivity, resolvedUrl, "Video", null, userAgentString)
                    tvStatus.text = "Direct download started"
                    btnDownload.isEnabled = true
                }
            }
        }
    }

    private suspend fun resolveRedirects(url: String): String {
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                connection.setRequestProperty("User-Agent", userAgentString)
                
                val responseCode = connection.responseCode
                if (responseCode in 300..399) {
                    val redirectUrl = connection.getHeaderField("Location")
                    if (!redirectUrl.isNullOrEmpty()) {
                        return@withContext resolveRedirects(redirectUrl)
                    }
                }
                url
            } catch (_: Exception) {
                url
            }
        }
    }

    private fun setupHiddenWebView() {
        hiddenWebView.settings.javaScriptEnabled = true
        hiddenWebView.settings.domStorageEnabled = true
        hiddenWebView.settings.userAgentString = userAgentString
        
        hiddenWebView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                Log.d("WebViewDetect", "Page finished: $url")
                hiddenWebView.evaluateJavascript(
                    "(function() { " +
                    "  var vids = document.getElementsByTagName('video');" +
                    "  if (vids.length > 0) { vids[0].play(); }" +
                    "})();", null
                )
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val url = request?.url.toString()
                
                // Detection patterns for various sites
                val isVideo = (url.contains(".mp4") || 
                             url.contains("video-h264") || 
                             url.contains("tiktokcdn.com") ||
                             url.contains("byteoversea.com") ||
                             url.contains("ibyteimg.com") ||
                             url.contains("p16-tiktok") ||
                             url.contains("p77-tiktok") ||
                             (url.contains(".m3u8") && url.contains("master")) ||
                             url.contains("get_media")) &&
                             !url.contains("placeholder", ignoreCase = true) &&
                             !url.contains("loading", ignoreCase = true) &&
                             !url.contains("adserv") &&
                             !url.contains("analytics")

                if (isVideo) {
                    runOnUiThread {
                        if (!btnDownload.isEnabled) {
                            Log.d("WebViewDetect", "MATCHED VIDEO: $url")
                            hiddenWebView.stopLoading()

                            if (url.contains(".m3u8")) {
                                tvStatus.text = "HLS stream detected — downloading segments..."
                                val pageReferer = hiddenWebView.url ?: url

                                lifecycleScope.launch {
                                    val success = HlsDownloader.download(
                                        context = this@MainActivity,
                                        playlistUrl = url,
                                        referer = pageReferer,
                                        userAgent = userAgentString,
                                        title = "video",
                                        listener = { message ->
                                            runOnUiThread { tvStatus.text = message }
                                        }
                                    )
                                    runOnUiThread {
                                        tvStatus.text = if (success) "Download complete" else "HLS download failed — check Logcat"
                                        btnDownload.isEnabled = true
                                    }
                                }
                            } else {
                                tvStatus.text = "Video detected!"
                                val cookies = android.webkit.CookieManager.getInstance().getCookie(url)
                                downloadId = DownloadHelper.enqueueDownload(
                                    this@MainActivity,
                                    url,
                                    "Downloaded Video",
                                    cookies,
                                    userAgentString,
                                    null
                                )
                                btnDownload.isEnabled = true
                            }
                        }
                    }
                }
                return super.shouldInterceptRequest(view, request)
            }
        }
    }

    private fun extractWithWebView(url: String) {
        tvStatus.text = "Analyzing page content..."
        hiddenWebView.loadUrl(url)
        
        lifecycleScope.launch {
            kotlinx.coroutines.delay(30000)
            if (!btnDownload.isEnabled) {
                runOnUiThread {
                    if (tvStatus.text.contains("Analyzing")) {
                        tvStatus.text = "Advanced extraction timed out"
                        btnDownload.isEnabled = true
                        hiddenWebView.stopLoading()
                    }
                }
            }
        }
    }

    private val onDownloadComplete = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            if (id == downloadId) {
                btnDownload.isEnabled = true
                
                val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                val query = DownloadManager.Query().setFilterById(id)
                val cursor = downloadManager.query(query)
                
                if (cursor.moveToFirst()) {
                    val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                    val status = cursor.getInt(statusIndex)
                    
                    if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        tvStatus.text = "Download Finished Successfully"
                        Toast.makeText(context, "Download complete!", Toast.LENGTH_SHORT).show()
                    } else {
                        val reasonIndex = cursor.getColumnIndex(DownloadManager.COLUMN_REASON)
                        val reason = cursor.getInt(reasonIndex)
                        tvStatus.text = "Download Failed (Error: $reason)"
                        Log.e("Downloader", "Download failed with reason: $reason")
                    }
                }
                cursor.close()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(onDownloadComplete)
    }
}