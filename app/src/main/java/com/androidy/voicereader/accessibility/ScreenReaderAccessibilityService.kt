package com.androidy.voicereader.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

class ScreenReaderAccessibilityService : AccessibilityService() {

    // Use IO dispatcher — extraction is I/O-like work, not UI
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        private const val TAG = "ScreenReaderA11y"
        private const val MAX_SCROLL_ATTEMPTS = 15
        private const val SCROLL_DELAY_MS = 600L
        private const val MAX_TEXT_LENGTH = 50_000
        private const val MAX_SEGMENTS = 500

        private var instance: ScreenReaderAccessibilityService? = null

        private val _isConnected = MutableStateFlow(false)
        val isConnected: StateFlow<Boolean> = _isConnected

        // replay=0: no stale cached results
        private val _extractedText = MutableSharedFlow<ExtractedContent>(replay = 0)
        val extractedText: SharedFlow<ExtractedContent> = _extractedText

        fun getInstance(): ScreenReaderAccessibilityService? = instance

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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        _isConnected.value = false
        scope.cancel()
    }

    fun extractAllVisibleText() {
        scope.launch {
            val allText = mutableListOf<String>()
            val seenTexts = mutableSetOf<String>()
            var scrollAttempts = 0
            var lastTextSnapshot = ""
            var totalLength = 0

            val initialText = withContext(Dispatchers.Main) { extractCurrentScreenText() }
            for (t in initialText) {
                if (allText.size >= MAX_SEGMENTS || totalLength >= MAX_TEXT_LENGTH) break
                allText.add(t)
                seenTexts.add(t)
                totalLength += t.length
            }
            lastTextSnapshot = initialText.joinToString("\n")

            while (scrollAttempts < MAX_SCROLL_ATTEMPTS &&
                   totalLength < MAX_TEXT_LENGTH &&
                   allText.size < MAX_SEGMENTS) {

                val scrolled = withContext(Dispatchers.Main) { performScroll() }
                if (!scrolled) break

                delay(SCROLL_DELAY_MS)

                val newText = withContext(Dispatchers.Main) { extractCurrentScreenText() }
                val freshLines = newText.filter { it !in seenTexts }

                if (freshLines.isEmpty()) {
                    scrollAttempts++
                    if (scrollAttempts >= 3) break
                } else {
                    scrollAttempts = 0
                    for (t in freshLines) {
                        if (allText.size >= MAX_SEGMENTS || totalLength >= MAX_TEXT_LENGTH) break
                        allText.add(t)
                        seenTexts.add(t)
                        totalLength += t.length
                    }
                }

                val currentSnapshot = newText.joinToString("\n")
                if (currentSnapshot == lastTextSnapshot) break
                lastTextSnapshot = currentSnapshot
            }

            val content = ExtractedContent(
                texts = allText,
                sourceApp = withContext(Dispatchers.Main) {
                    rootInActiveWindow?.packageName?.toString() ?: "unknown"
                },
                fullText = allText.joinToString("\n")
            )

            Log.d(TAG, "Extracted ${allText.size} segments (${totalLength} chars) from ${content.sourceApp}")
            _extractedText.emit(content)
        }
    }

    private fun extractCurrentScreenText(): List<String> {
        val rootNode = rootInActiveWindow ?: return emptyList()
        val texts = mutableListOf<String>()
        traverseNode(rootNode, texts)
        rootNode.recycle()
        return texts
    }

    private fun traverseNode(node: AccessibilityNodeInfo, texts: MutableList<String>) {
        if (texts.size >= MAX_SEGMENTS) return

        val text = node.text?.toString()?.trim()
        if (!text.isNullOrBlank() && text.length > 1) {
            texts.add(text)
        }

        val desc = node.contentDescription?.toString()?.trim()
        if (!desc.isNullOrBlank() && desc.length > 1 && desc != text) {
            texts.add("[Image: $desc]")
        }

        for (i in 0 until node.childCount) {
            if (texts.size >= MAX_SEGMENTS) break
            val child = node.getChild(i) ?: continue
            traverseNode(child, texts)
            child.recycle()
        }
    }

    private fun performScroll(): Boolean {
        val rootNode = rootInActiveWindow ?: return false

        val scrollable = findScrollableNode(rootNode)
        if (scrollable != null) {
            val result = scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            scrollable.recycle()
            rootNode.recycle()
            return result
        }

        rootNode.recycle()
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
        val dm = resources.displayMetrics
        val path = Path().apply {
            moveTo(dm.widthPixels / 2f, dm.heightPixels * 0.7f)
            lineTo(dm.widthPixels / 2f, dm.heightPixels * 0.3f)
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
