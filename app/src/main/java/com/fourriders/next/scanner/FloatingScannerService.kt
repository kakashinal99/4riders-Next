package com.fourriders.next.scanner

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.fourriders.next.MainActivity

/** User-started draggable bubble. Screen capture still needs Android consent. */
class FloatingScannerService : Service() {
    private lateinit var windowManager: WindowManager
    private var bubble: TextView? = null
    private val resultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val text = intent?.getStringExtra(ScreenCaptureService.EXTRA_OCR_TEXT).orEmpty()
            bubble?.text = if (text.isBlank()) "4\nNO TEXT" else "4\nREAD"
        }
    }

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 23 && !android.provider.Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val view = TextView(this).apply {
            text = "4\nSCAN"
            textSize = 11f
            setTextColor(android.graphics.Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(18, 12, 18, 12)
            setBackgroundColor(android.graphics.Color.rgb(0, 145, 145))
            elevation = 12f
        }
        val layout = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 24; y = 240 }
        var downX = 0
        var downY = 0
        var startX = 0f
        var startY = 0f
        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = layout.x; downY = layout.y
                    startX = event.rawX; startY = event.rawY
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val moved = kotlin.math.abs(event.rawX - startX) > 8 || kotlin.math.abs(event.rawY - startY) > 8
                    if (!moved) startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            .putExtra(MainActivity.EXTRA_FROM_FLOATING_SCANNER, true))
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    layout.x = downX + (event.rawX - startX).toInt()
                    layout.y = downY + (event.rawY - startY).toInt()
                    windowManager.updateViewLayout(view, layout)
                    true
                }
                else -> false
            }
        }
        bubble = view
        windowManager.addView(view, layout)
        ContextCompat.registerReceiver(this, resultReceiver, IntentFilter(ScreenCaptureService.ACTION_OCR_RESULT), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onDestroy() {
        runCatching { unregisterReceiver(resultReceiver) }
        bubble?.let { runCatching { windowManager.removeView(it) } }
        bubble = null
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
