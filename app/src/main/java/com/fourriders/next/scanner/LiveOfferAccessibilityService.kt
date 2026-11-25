package com.fourriders.next.scanner

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/** Reads visible, non-password UI text only while Live Scanner is switched ON. */
class LiveOfferAccessibilityService : AccessibilityService() {
    private var lastText = ""
    private var lastSentAt = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (!getSharedPreferences(FloatingScannerService.PREFS, MODE_PRIVATE)
                .getBoolean(FloatingScannerService.KEY_LIVE_ENABLED, false)) return
        if (event.packageName?.toString() == packageName) return
        val now = System.currentTimeMillis()
        if (now - lastSentAt < 500) return
        val root = rootInActiveWindow ?: return
        val parts = LinkedHashSet<String>()
        collectVisibleText(root, parts, intArrayOf(0))
        val text = parts.joinToString("\n").take(7000).trim()
        if (text.length < 8 || text == lastText) return
        lastText = text
        lastSentAt = now
        sendBroadcast(Intent(ScreenCaptureService.ACTION_OCR_RESULT)
            .setPackage(packageName)
            .putExtra(ScreenCaptureService.EXTRA_OCR_TEXT, text))
    }

    private fun collectVisibleText(node: AccessibilityNodeInfo?, output: MutableSet<String>, count: IntArray) {
        if (node == null || count[0] >= 450 || output.size >= 100) return
        count[0]++
        if (!node.isPassword) {
            node.text?.toString()?.trim()?.takeIf { it.length >= 2 }?.let(output::add)
            node.contentDescription?.toString()?.trim()?.takeIf { it.length >= 2 }?.let(output::add)
        }
        for (index in 0 until node.childCount) {
            if (count[0] >= 450 || output.size >= 100) break
            collectVisibleText(node.getChild(index), output, count)
        }
    }
    override fun onInterrupt() = Unit
}
