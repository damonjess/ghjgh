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
import com.mugames.vidsnapkit.extractor.Extractor
import com.mugames.vidsnapkit.dataholders.Result
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class FloatingWidgetService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        
        startForegroundService()

        floatingView = LayoutInflater.from(this).inflate(R.layout.layout_floating_widget, null)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )

        params.gravity = Gravity.TOP or Gravity.START
        params.x = 0
        params.y = 100

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager.addView(floatingView, params)

        val rootContainer = floatingView.findViewById<View>(R.id.root_container)
        rootContainer.setOnTouchListener(object : View.OnTouchListener {
            private var initialX: Int = 0
            private var initialY: Int = 0
            private var initialTouchX: Float = 0.0f
            private var initialTouchY: Float = 0.0f

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        val Xdiff = (event.rawX - initialTouchX).toInt()
                        val Ydiff = (event.rawY - initialTouchY).toInt()
                        if (Xdiff < 10 && Ydiff < 10) {
                            onFloatingWidgetClick()
                        }
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = initialX + (event.rawX - initialTouchX).toInt()
                        params.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager.updateViewLayout(floatingView, params)
                        return true
                    }
                }
                return false
            }
        })
    }

    private fun startForegroundService() {
        val channelId = "floating_widget_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Floating Widget Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Video Downloader Floating Widget")
            .setContentText("Tap to detect video from clipboard")
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .build()

        startForeground(1, notification)
    }

    private fun onFloatingWidgetClick() {
        // Need to briefly take focus to access clipboard on some Android versions,
        // but for now we try standard access.
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val item = clipboard.primaryClip?.getItemAt(0)
        val text = item?.text?.toString() ?: ""

        if (text.startsWith("http")) {
            Toast.makeText(this, "Detecting video...", Toast.LENGTH_SHORT).show()
            extractAndDownload(text)
        } else {
            Toast.makeText(this, "No link in clipboard", Toast.LENGTH_SHORT).show()
        }
    }

    private fun extractAndDownload(url: String) {
        val extractor = Extractor.findExtractor(url)
        if (extractor != null) {
            // Check for saved cookies
            val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            val savedCookies = prefs.getString("ig_cookies", "")
            if (url.contains("instagram.com") && !savedCookies.isNullOrEmpty()) {
                extractor.cookies = savedCookies
            }

            scope.launch {
                extractor.start { result ->
                    when (result) {
                        is Result.Success -> {
                            val mediaList = result.formats
                            if (mediaList.isNotEmpty()) {
                                DownloadHelper.enqueueDownload(this@FloatingWidgetService, mediaList[0].url, mediaList[0].title)
                            }
                        }
                        is Result.Failed -> {
                            scope.launch(Dispatchers.Main) {
                                Toast.makeText(this@FloatingWidgetService, "Error: ${result.error.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                        else -> {}
                    }
                }
            }
        } else {
            DownloadHelper.enqueueDownload(this, url)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::floatingView.isInitialized) windowManager.removeView(floatingView)
    }
}