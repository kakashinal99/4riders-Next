package com.fourriders.next.scanner

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView
import androidx.core.content.ContextCompat

class FloatingScannerService : Service() {
    companion object {
        const val PREFS = "scanner_settings"
        const val KEY_LIVE_ENABLED = "live_scanning_enabled"
    }

    private lateinit var windowManager: WindowManager
    private var panel: TextView? = null
    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    private val resultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val raw = intent?.getStringExtra(ScreenCaptureService.EXTRA_OCR_TEXT).orEmpty()
            if (prefs.getBoolean(KEY_LIVE_ENABLED, false)) showOffer(raw)
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
            textSize = 11f
            setTextColor(android.graphics.Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.START
            setPadding(16, 13, 16, 13)
            maxLines = 10
            background = GradientDrawable().apply {
                setColor(android.graphics.Color.rgb(10, 20, 34))
                cornerRadius = 22f
                setStroke(2, android.graphics.Color.rgb(69, 224, 208))
            }
            elevation = 16f
            text = if (prefs.getBoolean(KEY_LIVE_ENABLED, false)) "4RIDERS NEXT\nLIVE SCANNER ON\nWaiting for offer…" else "4RIDERS NEXT\nLIVE SCANNER OFF\nTap to start"
        }
        val layout = WindowManager.LayoutParams(
            (resources.displayMetrics.widthPixels * 0.72f).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 18; y = 220 }

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
                    val moved = kotlin.math.abs(event.rawX - startX) > 10 || kotlin.math.abs(event.rawY - startY) > 10
                    if (!moved) {
                        val enabled = !prefs.getBoolean(KEY_LIVE_ENABLED, false)
                        prefs.edit().putBoolean(KEY_LIVE_ENABLED, enabled).apply()
                        view.text = if (enabled) "4RIDERS NEXT\nLIVE SCANNER ON\nWaiting for offer…" else "4RIDERS NEXT\nLIVE SCANNER PAUSED\nTap to resume"
                    }
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
        panel = view
        windowManager.addView(view, layout)
        ContextCompat.registerReceiver(this, resultReceiver, IntentFilter(ScreenCaptureService.ACTION_OCR_RESULT), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private fun showOffer(raw: String) {
        val offer = OfferParser.parse(raw)
        val lines = mutableListOf("4RIDERS NEXT • LIVE SCAN", if (offer.rawText.isBlank()) "No readable offer text" else "OFFER DETECTED")
        lines += "FARE: " + (offer.fare?.let { "RM %.2f".format(java.util.Locale.US, it) } ?: "NOT FOUND")
        lines += "PICKUP: " + (offer.pickup ?: "NOT FOUND")
        lines += "DROP-OFF: " + (offer.dropoff ?: "NOT FOUND")
        lines += "DISTANCE: " + (offer.distanceKm?.let { "%.1f km".format(java.util.Locale.US, it) } ?: "NOT FOUND")
        lines += "DURATION: " + (offer.durationMin?.let { "$it min" } ?: "NOT FOUND")
        lines += "Tap panel to pause"
        panel?.text = lines.joinToString("\n")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY
    override fun onDestroy() {
        runCatching { unregisterReceiver(resultReceiver) }
        panel?.let { runCatching { windowManager.removeView(it) } }
        panel = null
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
