package com.example.ghjgh

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.mugames.vidsnapkit.dataholders.Result
import com.mugames.vidsnapkit.extractor.Extractor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

class FloatingWidgetService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View
    private lateinit var layoutParams: WindowManager.LayoutParams
    private val serviceJob = Job()
    private val scope = CoroutineScope(Dispatchers.Main + serviceJob)
    private val clipboard by lazy { getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager }

    companion object {
        private const val CLICK_DRAG_TOLERANCE = 10
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "floating_widget_channel"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundService()
        setupFloatingView()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    private fun setupFloatingView() {
        floatingView = LayoutInflater.from(this).inflate(R.layout.layout_floating_widget, null)

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 100
        }

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager.addView(floatingView, layoutParams)

        val rootContainer = floatingView.findViewById<View>(R.id.root_container)

        rootContainer.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var isDragging = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = layoutParams.x
                        initialY = layoutParams.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isDragging = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - initialTouchX
                        val dy = event.rawY - initialTouchY
                        if (abs(dx) > CLICK_DRAG_TOLERANCE || abs(dy) > CLICK_DRAG_TOLERANCE) {
                            isDragging = true
                        }
                        layoutParams.x = initialX + dx.toInt()
                        layoutParams.y = initialY + dy.toInt()
                        windowManager.updateViewLayout(floatingView, layoutParams)
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!isDragging) {
                            performClick()
                        }
                        return true
                    }
                }
                return false
            }
        })
    }

    private fun performClick() {
        // Android 10+ blocks clipboard unless we have focus.
        // Temporarily drop FLAG_NOT_FOCUSABLE, grab focus, read clipboard, then restore.
        val originalFlags = layoutParams.flags
        layoutParams.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        windowManager.updateViewLayout(floatingView, layoutParams)
        floatingView.requestFocus()

        // Small delay so the system actually grants focus before we read
        floatingView.postDelayed({
            val text = clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: ""

            // Restore non-focusable state so back-button/keyboard stay normal
            layoutParams.flags = originalFlags
            windowManager.updateViewLayout(floatingView, layoutParams)

            if (text.startsWith("http")) {
                Toast.makeText(this, "Detecting video…", Toast.LENGTH_SHORT).show()
                extractAndDownload(text)
            } else {
                Toast.makeText(this, "Clipboard empty or no URL found", Toast.LENGTH_SHORT).show()
            }
        }, 80)
    }

    private fun startForegroundService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Floating Widget Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Video Downloader")
            .setContentText("Floating widget is active")
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun extractAndDownload(url: String) {
        val extractor = Extractor.findExtractor(url) ?: run {
            DownloadHelper.enqueueDownload(this, url)
            return
        }

        if (url.contains("instagram.com")) {
            val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            val savedCookies = prefs.getString("ig_cookies", "")
            if (!savedCookies.isNullOrEmpty()) {
                extractor.cookies = savedCookies
            }
        }

        scope.launch {
            extractor.start { result ->
                scope.launch(Dispatchers.Main) {
                    when (result) {
                        is Result.Success -> {
                            val mediaList = result.formats
                            if (mediaList.isNotEmpty()) {
                                DownloadHelper.enqueueDownload(
                                    this@FloatingWidgetService,
                                    mediaList[0].url,
                                    mediaList[0].title
                                )
                            } else {
                                Toast.makeText(
                                    this@FloatingWidgetService,
                                    "No video found at this link",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                        is Result.Failed -> {
                            Toast.makeText(
                                this@FloatingWidgetService,
                                "Error: ${result.error.message}",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                        else -> { /* progress updates ignored */ }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
        if (::floatingView.isInitialized) {
            try {
                windowManager.removeView(floatingView)
            } catch (_: IllegalArgumentException) {
                // already removed
            }
        }
    }
}