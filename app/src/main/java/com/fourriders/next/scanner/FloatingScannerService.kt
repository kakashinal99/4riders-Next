package com.fourriders.next.scanner

import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
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
    private var lastOfferDisplay: String? = null
    private var session: OrderForm? = null
    private var lastRaw: String = ""
    private var latestStatus: String = "WAITING FOR OFFER"
    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    private val resultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!prefs.getBoolean(KEY_LIVE_ENABLED, false)) return
            when (intent?.action) {
                ScreenCaptureService.ACTION_OCR_RESULT -> {
                    val raw = intent.getStringExtra(ScreenCaptureService.EXTRA_OCR_TEXT).orEmpty()
                    showOffer(raw)
                }
                LiveOfferAccessibilityService.ACTION_SCANNER_STATUS -> {
                    val status = intent.getStringExtra(LiveOfferAccessibilityService.EXTRA_SCANNER_STATUS).orEmpty()
                    latestStatus = status
                    panel?.text = lastOfferDisplay?.let { "$it\nSTATUS: $latestStatus" }
                        ?: "4RIDERS NEXT\nLIVE SCANNER ON\n$status"
                }
            }
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
            maxLines = 24
            background = GradientDrawable().apply {
                setColor(android.graphics.Color.rgb(10, 20, 34))
                cornerRadius = 22f
                setStroke(2, android.graphics.Color.rgb(69, 224, 208))
            }
            elevation = 16f
            text = if (prefs.getBoolean(KEY_LIVE_ENABLED, false)) "4RIDERS NEXT\nLIVE SCANNER ON\nStarting accessibility…" else "4RIDERS NEXT\nLIVE SCANNER OFF\nTap to start"
        }
        val layout = WindowManager.LayoutParams(
            (resources.displayMetrics.widthPixels * 0.72f).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_SECURE,
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
                    val heldMs = event.eventTime - event.downTime
                    if (!moved && heldMs >= 700) {
                        copyRawText()
                    } else if (!moved) {
                        val enabled = !prefs.getBoolean(KEY_LIVE_ENABLED, false)
                        prefs.edit().putBoolean(KEY_LIVE_ENABLED, enabled).apply()
                        view.text = if (enabled) "4RIDERS NEXT\nLIVE SCANNER ON\nStarting accessibility…" else "4RIDERS NEXT\nLIVE SCANNER PAUSED\nTap to resume"
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
        val filter = IntentFilter().apply {
            addAction(ScreenCaptureService.ACTION_OCR_RESULT)
            addAction(LiveOfferAccessibilityService.ACTION_SCANNER_STATUS)
        }
        ContextCompat.registerReceiver(this, resultReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private fun copyRawText() {
        if (lastRaw.isBlank()) {
            panel?.text = "4RIDERS NEXT\nNO TEXT READ YET\nOpen an order screen first"
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("4riders raw text", lastRaw))
        panel?.text = "4RIDERS NEXT\nRAW TEXT COPIED (${lastRaw.length} chars)\nPaste it into the chat"
    }

    private fun showOffer(raw: String) {
        if (raw.isBlank()) {
            lastOfferDisplay = "4RIDERS NEXT\nSCAN FAILED • NO TEXT READ\nKeep the order screen visible"
            panel?.text = lastOfferDisplay
            return
        }
        lastRaw = raw
        val parsed = OrderFormParser.parse(raw)
        val form = if (parsed.isEmpty) session else OrderFormParser.merge(session, parsed)
        session = form
        val lines = mutableListOf("4RIDERS NEXT • ORDER FORM")
        if (form == null) {
            lines += "NO ORDER FIELDS ON THIS SCREEN"
        } else {
            if (parsed.isEmpty) lines += "NO ORDER FIELDS ON SCREEN • SHOWING LAST ORDER"
            lines += OrderFormParser.render(form)
            lines += parsed.notes
        }
        lines += "Tap = pause • hold = copy raw text"
        lastOfferDisplay = lines.joinToString("\n")
        panel?.text = "$lastOfferDisplay\nSTATUS: $latestStatus"
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
