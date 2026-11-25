package com.fourriders.next.scanner

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * User-controlled on-device reader. It checks other visible app windows rather
 * than relying only on rootInActiveWindow (which can point at our own overlay).
 * Password nodes are excluded; text is never uploaded.
 */
class LiveOfferAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var lastText = ""
    private var lastSentAt = 0L
    private var lastStatus = ""

    private val poll = object : Runnable {
        override fun run() {
            if (isLiveEnabled()) scanOtherAppWindows()
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        sendStatus("ACCESSIBILITY CONNECTED")
        handler.removeCallbacks(poll)
        handler.post(poll)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event != null && isLiveEnabled()) scanOtherAppWindows()
    }

    private fun isLiveEnabled(): Boolean =
        getSharedPreferences(FloatingScannerService.PREFS, MODE_PRIVATE)
            .getBoolean(FloatingScannerService.KEY_LIVE_ENABLED, false)

    private fun scanOtherAppWindows() {
        val now = System.currentTimeMillis()
        if (now - lastSentAt < MIN_SCAN_INTERVAL_MS) return

        val candidates = runCatching {
            windows.mapNotNull { window ->
                val root = window.root ?: return@mapNotNull null
                if (root.packageName?.toString() == packageName) {
                    root.recycle()
                    return@mapNotNull null
                }
                Triple(if (window.isActive) 2 else if (window.isFocused) 1 else 0, root, window.type)
            }.sortedByDescending { it.first }
        }.getOrDefault(emptyList())

        if (candidates.isEmpty()) {
            sendStatus("NO OTHER APP WINDOW")
            return
        }

        var bestText = ""
        for ((_, root, _) in candidates) {
            val parts = LinkedHashSet<String>()
            collectVisibleText(root, parts, intArrayOf(0))
            val candidateText = parts.joinToString("\n").take(MAX_TEXT_LENGTH).trim()
            root.recycle()
            if (candidateText.length > bestText.length) bestText = candidateText
        }

        if (bestText.length < 8) {
            sendStatus("WINDOW FOUND • NO ACCESSIBLE TEXT")
            return
        }
        if (bestText == lastText) {
            sendStatus("TEXT FOUND • NO CHANGE")
            return
        }

        lastText = bestText
        lastSentAt = now
        sendBroadcast(
            Intent(ScreenCaptureService.ACTION_OCR_RESULT)
                .setPackage(packageName)
                .putExtra(ScreenCaptureService.EXTRA_OCR_TEXT, bestText)
        )
        sendStatus("TEXT SENT TO SCANNER")
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

    private fun sendStatus(status: String) {
        if (status == lastStatus && status != "TEXT SENT TO SCANNER") return
        lastStatus = status
        sendBroadcast(
            Intent(ACTION_SCANNER_STATUS)
                .setPackage(packageName)
                .putExtra(EXTRA_SCANNER_STATUS, status)
        )
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        handler.removeCallbacks(poll)
        super.onDestroy()
    }

    companion object {
        const val ACTION_SCANNER_STATUS = "com.fourriders.next.SCANNER_STATUS"
        const val EXTRA_SCANNER_STATUS = "scanner_status"
        private const val POLL_INTERVAL_MS = 900L
        private const val MIN_SCAN_INTERVAL_MS = 700L
        private const val MAX_NODES = 900
        private const val MAX_TEXT_ITEMS = 180
        private const val MAX_TEXT_LENGTH = 12000
    }
}
