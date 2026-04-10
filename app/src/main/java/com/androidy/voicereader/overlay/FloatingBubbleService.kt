package com.androidy.voicereader.overlay

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.androidy.voicereader.R
import com.androidy.voicereader.pipeline.PipelineState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class FloatingBubbleService : Service() {

    companion object {
        private const val TAG = "FloatingBubble"

        private val _bubbleState = MutableStateFlow(BubbleState.IDLE)
        val bubbleState: StateFlow<BubbleState> = _bubbleState

        fun updateState(state: BubbleState) {
            _bubbleState.value = state
        }

        fun fromPipelineState(state: PipelineState): BubbleState {
            return when (state) {
                PipelineState.IDLE -> BubbleState.IDLE
                PipelineState.EXTRACTING -> BubbleState.EXTRACTING
                PipelineState.ANALYZING -> BubbleState.ANALYZING
                PipelineState.SPEAKING -> BubbleState.SPEAKING
                PipelineState.ERROR -> BubbleState.ERROR
            }
        }

        fun start(context: Context) {
            context.startService(Intent(context, FloatingBubbleService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingBubbleService::class.java))
        }
    }

    private var windowManager: WindowManager? = null
    private var bubbleView: View? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createBubble()
        observeState()
    }

    private fun createBubble() {
        val container = FrameLayout(this).apply {
            val size = (56 * resources.displayMetrics.density).toInt()

            val icon = ImageView(this@FloatingBubbleService).apply {
                setImageResource(R.drawable.ic_notification)
                layoutParams = FrameLayout.LayoutParams(
                    (32 * resources.displayMetrics.density).toInt(),
                    (32 * resources.displayMetrics.density).toInt(),
                    Gravity.CENTER
                )
                setColorFilter(0xFFFFFFFF.toInt())
            }
            addView(icon)

            val statusText = TextView(this@FloatingBubbleService).apply {
                tag = "status_text"
                textSize = 8f
                setTextColor(0xFFFFFFFF.toInt())
                gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                )
            }
            addView(statusText)

            background = GradientDrawable().apply {
                setColor(0xCC333333.toInt())
                cornerRadius = 28f * resources.displayMetrics.density
            }
        }

        val params = WindowManager.LayoutParams(
            (56 * resources.displayMetrics.density).toInt(),
            (64 * resources.displayMetrics.density).toInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 16
            y = 200
        }

        // Make draggable
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        container.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX - (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager?.updateViewLayout(container, params)
                    true
                }
                else -> false
            }
        }

        try {
            windowManager?.addView(container, params)
            bubbleView = container
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add bubble view", e)
        }
    }

    private fun observeState() {
        scope.launch {
            _bubbleState.collect { state ->
                updateBubbleAppearance(state)
            }
        }
    }

    private fun updateBubbleAppearance(state: BubbleState) {
        val container = bubbleView as? FrameLayout ?: return
        val statusText = container.findViewWithTag<TextView>("status_text") ?: return

        when (state) {
            BubbleState.IDLE -> {
                container.alpha = 0.6f
                statusText.text = ""
            }
            BubbleState.EXTRACTING -> {
                container.alpha = 1.0f
                statusText.text = "Reading..."
            }
            BubbleState.ANALYZING -> {
                container.alpha = 1.0f
                statusText.text = "Thinking..."
            }
            BubbleState.SPEAKING -> {
                container.alpha = 1.0f
                statusText.text = "Speaking"
            }
            BubbleState.ERROR -> {
                container.alpha = 1.0f
                statusText.text = "Error"
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        bubbleView?.let { windowManager?.removeView(it) }
        bubbleView = null
        scope.cancel()
    }
}

enum class BubbleState {
    IDLE, EXTRACTING, ANALYZING, SPEAKING, ERROR
}
