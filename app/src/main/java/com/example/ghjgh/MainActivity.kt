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
    
    private var downloadId: Long = -1

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
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        for (service in manager.getRunningServices(Int.MAX_VALUE)) {
            if (serviceClass.name == service.service.className) {
                return true
            }
        }
        return false
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

        if (text.startsWith("http")) {
            etUrl.setText(text)
            extractAndDownload(text)
        } else {
            Toast.makeText(this, "No valid link found in clipboard", Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkClipboardForUrl() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val item = clipboard.primaryClip?.getItemAt(0)
        val text = item?.text?.toString() ?: ""
        
        if (text.contains("instagram.com") || text.contains("facebook.com")) {
            val rootView = findViewById<View>(android.R.id.content)
            Snackbar.make(rootView, "Link detected in clipboard", Snackbar.LENGTH_LONG)
                .setAction("Download") {
                    etUrl.setText(text)
                    extractAndDownload(text)
                }.show()
        }
    }

    private fun extractAndDownload(url: String) {
        tvStatus.text = "Fetching video details..."
        btnDownload.isEnabled = false

        if (url.contains("pornhub.com")) {
            extractPornhubVideo(url)
            return
        }

        val extractor = Extractor.findExtractor(url)
        if (extractor != null) {
            // Apply cookies if it's Instagram
            if (url.contains("instagram.com")) {
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
                                    val videoUrl = mediaList[0].url
                                    DownloadHelper.enqueueDownload(this@MainActivity, videoUrl, mediaList[0].title)
                                } else {
                                    tvStatus.text = "Error: No video found at this link"
                                    btnDownload.isEnabled = true
                                }
                            }
                            is Result.Failed -> {
                                tvStatus.text = "Error: ${result.error.message}"
                                btnDownload.isEnabled = true
                            }
                            else -> {
                                // Handle progress if needed
                            }
                        }
                    }
                }
            }
        } else {
            DownloadHelper.enqueueDownload(this, url)
        }
    }

    private fun setupHiddenWebView() {
        hiddenWebView.settings.javaScriptEnabled = true
        hiddenWebView.settings.domStorageEnabled = true
        hiddenWebView.settings.userAgentString = "Mozilla/5.0 (Linux; Android 10; SM-G973F) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/83.0.4103.106 Mobile Safari/537.36"
        
        hiddenWebView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val url = request?.url.toString()
                
                // Common Pornhub video source patterns
                if ((url.contains(".mp4") && !url.contains("adserv")) || 
                    url.contains("get_media") || 
                    (url.contains(".m3u8") && url.contains("master"))) {
                    
                    runOnUiThread {
                        if (btnDownload.isEnabled == false) {
                            tvStatus.text = "Video detected!"
                            DownloadHelper.enqueueDownload(this@MainActivity, url, "Pornhub Video")
                            btnDownload.isEnabled = true
                            // Stop loading once we found the video
                            hiddenWebView.stopLoading()
                        }
                    }
                }
                return super.shouldInterceptRequest(view, request)
            }
        }
    }

    private fun extractPornhubVideo(url: String) {
        tvStatus.text = "Analyzing Pornhub page..."
        hiddenWebView.loadUrl(url)
        
        // Timeout if not detected in 20 seconds
        lifecycleScope.launch {
            kotlinx.coroutines.delay(20000)
            if (!btnDownload.isEnabled) {
                runOnUiThread {
                    if (tvStatus.text.contains("Analyzing")) {
                        tvStatus.text = "Extraction timed out"
                        btnDownload.isEnabled = true
                        hiddenWebView.stopLoading()
                    }
                }
            }
        }
    }

    private val onDownloadComplete = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
            // Need a way to track downloadId across Helper and Activity if we want specific completion logic
            // But for now, just enable the button
            btnDownload.isEnabled = true
            tvStatus.text = "Download Finished"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(onDownloadComplete)
    }
}