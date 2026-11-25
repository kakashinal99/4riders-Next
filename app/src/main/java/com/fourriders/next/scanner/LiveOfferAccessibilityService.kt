package com.fourriders.next.scanner

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Opt-in live reader. Polls the active window while the user has switched
 * Live Scanner ON, because some apps do not emit a fresh event after overlay taps.
 * Password fields are never read. All processing remains on-device.
 */
class LiveOfferAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var lastText = ""
    private var lastSentAt = 0L

    private val poll = object : Runnable {
        override fun run() {
            if (isLiveEnabled()) scanActiveWindow()
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        handler.removeCallbacks(poll)
        handler.post(poll)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !isLiveEnabled()) return
        // Do not ignore app events: overlay interaction may be the only event
        // after the user switches scanning ON. Polling still reads active app window.
        scanActiveWindow()
    }

    private fun isLiveEnabled(): Boolean =
        getSharedPreferences(FloatingScannerService.PREFS, MODE_PRIVATE)
            .getBoolean(FloatingScannerService.KEY_LIVE_ENABLED, false)

    private fun scanActiveWindow() {
        val now = System.currentTimeMillis()
        if (now - lastSentAt < MIN_SCAN_INTERVAL_MS) return
        val root = rootInActiveWindow ?: return
        val parts = LinkedHashSet<String>()
        collectVisibleText(root, parts, intArrayOf(0))
        val text = parts.joinToString("\n").take(MAX_TEXT_LENGTH).trim()
        if (text.length < 8 || text == lastText) return
        lastText = text
        lastSentAt = now
        sendBroadcast(
            Intent(ScreenCaptureService.ACTION_OCR_RESULT)
                .setPackage(packageName)
                .putExtra(ScreenCaptureService.EXTRA_OCR_TEXT, text)
        )
    }

    private fun collectVisibleText(node: AccessibilityNodeInfo?, output: MutableSet<String>, count: IntArray) {
        if (node == null || count[0] >= MAX_NODES || output.size >= MAX_TEXT_ITEMS) return
        count[0]++
        if (node.isVisibleToUser && !node.isPassword) {
            node.text?.toString()?.trim()?.takeIf { it.length >= 2 }?.let(output::add)
            node.contentDescription?.toString()?.trim()?.takeIf { it.length >= 2 }?.let(output::add)
        }
        for (index in 0 until node.childCount) {
            if (count[0] >= MAX_NODES || output.size >= MAX_TEXT_ITEMS) break
            collectVisibleText(node.getChild(index), output, count)
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        handler.removeCallbacks(poll)
        super.onDestroy()
    }

    private companion object {
        const val POLL_INTERVAL_MS = 900L
        const val MIN_SCAN_INTERVAL_MS = 700L
        const val MAX_NODES = 700
        const val MAX_TEXT_ITEMS = 140
        const val MAX_TEXT_LENGTH = 10000
    }
}
