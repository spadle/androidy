package com.androidy.voicereader.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Accessibility Service that reads screen content from any app.
 * Extracts text by traversing the view hierarchy and supports
 * scrolling to capture content beyond the visible viewport.
 */
class ScreenReaderAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    companion object {
        private const val TAG = "ScreenReaderA11y"
        private const val MAX_SCROLL_ATTEMPTS = 15
        private const val SCROLL_DELAY_MS = 600L

        private var instance: ScreenReaderAccessibilityService? = null

        private val _isConnected = MutableStateFlow(false)
        val isConnected: StateFlow<Boolean> = _isConnected

        private val _extractedText = MutableSharedFlow<ExtractedContent>(replay = 1)
        val extractedText: SharedFlow<ExtractedContent> = _extractedText

        fun getInstance(): ScreenReaderAccessibilityService? = instance

        /** Trigger a full-page text extraction with scrolling. */
        fun requestExtraction() {
            instance?.extractAllVisibleText()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _isConnected.value = true
        Log.d(TAG, "Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // We don't need to react to every event — extraction is on-demand
    }

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        _isConnected.value = false
        scope.cancel()
    }

    /**
     * Extract all text from the current screen, scrolling down to capture
     * content that extends beyond the visible viewport (e.g., long Facebook posts).
     */
    fun extractAllVisibleText() {
        scope.launch {
            val allText = mutableListOf<String>()
            val seenTexts = mutableSetOf<String>()
            var scrollAttempts = 0
            var lastTextSnapshot = ""

            // First pass: get visible text
            val initialText = extractCurrentScreenText()
            allText.addAll(initialText)
            seenTexts.addAll(initialText)
            lastTextSnapshot = initialText.joinToString("\n")

            // Scroll and extract until no new content appears
            while (scrollAttempts < MAX_SCROLL_ATTEMPTS) {
                val scrolled = performScroll()
                if (!scrolled) break

                delay(SCROLL_DELAY_MS)

                val newText = extractCurrentScreenText()
                val freshLines = newText.filter { it !in seenTexts }

                if (freshLines.isEmpty()) {
                    scrollAttempts++
                    if (scrollAttempts >= 3) break // No new content after 3 attempts
                } else {
                    scrollAttempts = 0
                    allText.addAll(freshLines)
                    seenTexts.addAll(freshLines)
                }

                val currentSnapshot = newText.joinToString("\n")
                if (currentSnapshot == lastTextSnapshot) break
                lastTextSnapshot = currentSnapshot
            }

            val content = ExtractedContent(
                texts = allText,
                sourceApp = rootInActiveWindow?.packageName?.toString() ?: "unknown",
                fullText = allText.joinToString("\n")
            )

            Log.d(TAG, "Extracted ${allText.size} text segments from ${content.sourceApp}")
            _extractedText.emit(content)
        }
    }

    /** Traverse the current view tree and collect all text nodes. */
    private fun extractCurrentScreenText(): List<String> {
        val rootNode = rootInActiveWindow ?: return emptyList()
        val texts = mutableListOf<String>()
        traverseNode(rootNode, texts)
        rootNode.recycle()
        return texts
    }

    private fun traverseNode(node: AccessibilityNodeInfo, texts: MutableList<String>) {
        // Collect text content
        val text = node.text?.toString()?.trim()
        if (!text.isNullOrBlank() && text.length > 1) {
            texts.add(text)
        }

        // Also check content description (for images with alt text, etc.)
        val desc = node.contentDescription?.toString()?.trim()
        if (!desc.isNullOrBlank() && desc.length > 1 && desc != text) {
            texts.add("[Image: $desc]")
        }

        // Recurse into children
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            traverseNode(child, texts)
            child.recycle()
        }
    }

    /** Perform a scroll-down gesture on the current screen. */
    private fun performScroll(): Boolean {
        val rootNode = rootInActiveWindow ?: return false

        // Try to find a scrollable node first
        val scrollable = findScrollableNode(rootNode)
        if (scrollable != null) {
            val result = scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            scrollable.recycle()
            rootNode.recycle()
            return result
        }

        rootNode.recycle()

        // Fallback: use gesture-based scroll
        return performScrollGesture()
    }

    private fun findScrollableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isScrollable) return AccessibilityNodeInfo.obtain(node)

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findScrollableNode(child)
            if (found != null) {
                child.recycle()
                return found
            }
            child.recycle()
        }
        return null
    }

    private fun performScrollGesture(): Boolean {
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels

        val path = Path().apply {
            moveTo(screenWidth / 2f, screenHeight * 0.7f)
            lineTo(screenWidth / 2f, screenHeight * 0.3f)
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
            .build()

        return dispatchGesture(gesture, null, null)
    }
}

data class ExtractedContent(
    val texts: List<String>,
    val sourceApp: String,
    val fullText: String
)
