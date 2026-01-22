package com.infomaniak.drive.ui.fileList

import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import androidx.core.view.GestureDetectorCompat
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs

/**
 * Touch listener that enables drag-to-select functionality in a RecyclerView.
 * When the user long-presses an item and drags without lifting, items are continuously
 * selected as the finger passes over them.
 */
class DragSelectTouchListener(
    private val recyclerView: RecyclerView,
    private val callback: DragSelectCallback,
) : RecyclerView.OnItemTouchListener {

    interface DragSelectCallback {
        fun isMultiSelectAuthorized(): Boolean
        fun isMultiSelectOn(): Boolean
        fun onDragSelectStarted(startPosition: Int)
        fun onDragSelectChanged(start: Int, end: Int)
        fun onDragSelectFinished()
        fun isPositionSelectable(position: Int): Boolean
    }

    private var isDragSelecting = false
    private var lastSelectedPosition = RecyclerView.NO_POSITION

    private val autoScrollHandler = Handler(Looper.getMainLooper())
    private var autoScrollVelocity = 0
    private val autoScrollRunnable = object : Runnable {
        override fun run() {
            if (isDragSelecting && autoScrollVelocity != 0) {
                recyclerView.scrollBy(0, autoScrollVelocity)
                autoScrollHandler.postDelayed(this, AUTO_SCROLL_DELAY)
            }
        }
    }

    private val gestureDetector = GestureDetectorCompat(
        recyclerView.context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onLongPress(e: MotionEvent) {
                if (!callback.isMultiSelectAuthorized()) return

                val child = recyclerView.findChildViewUnder(e.x, e.y) ?: return
                val position = recyclerView.getChildAdapterPosition(child)

                if (position == RecyclerView.NO_POSITION) return
                if (!callback.isPositionSelectable(position)) return

                // Provide haptic feedback
                recyclerView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)

                isDragSelecting = true
                lastSelectedPosition = position
                callback.onDragSelectStarted(position)
            }
        }
    )

    override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(e)

        when (e.actionMasked) {
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDragSelecting) {
                    stopDragSelection()
                    return true
                }
            }
        }

        return isDragSelecting
    }

    override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
        if (!isDragSelecting) return

        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> handleDragMove(e)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> stopDragSelection()
        }
    }

    override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
        if (disallowIntercept && isDragSelecting) {
            stopDragSelection()
        }
    }

    private fun handleDragMove(e: MotionEvent) {
        val y = e.y

        // Handle auto-scroll at edges
        val height = recyclerView.height
        val edgeThreshold = (height * EDGE_SCROLL_THRESHOLD).toInt()

        autoScrollVelocity = when {
            y < edgeThreshold -> {
                // Near top edge - scroll up
                val intensity = 1 - (y / edgeThreshold)
                -(BASE_SCROLL_SPEED + (intensity * MAX_EXTRA_SCROLL_SPEED)).toInt()
            }
            y > height - edgeThreshold -> {
                // Near bottom edge - scroll down
                val intensity = (y - (height - edgeThreshold)) / edgeThreshold
                (BASE_SCROLL_SPEED + (intensity * MAX_EXTRA_SCROLL_SPEED)).toInt()
            }
            else -> 0
        }

        if (autoScrollVelocity != 0) {
            autoScrollHandler.removeCallbacks(autoScrollRunnable)
            autoScrollHandler.post(autoScrollRunnable)
        } else {
            autoScrollHandler.removeCallbacks(autoScrollRunnable)
        }

        // Find and select item under finger
        val child = recyclerView.findChildViewUnder(e.x, y)
        if (child != null) {
            val currentPosition = recyclerView.getChildAdapterPosition(child)
            if (currentPosition != RecyclerView.NO_POSITION && currentPosition != lastSelectedPosition) {
                // Select all items in range between last position and current position
                val start = minOf(lastSelectedPosition, currentPosition)
                val end = maxOf(lastSelectedPosition, currentPosition)
                callback.onDragSelectChanged(start, end)
                lastSelectedPosition = currentPosition
            }
        }
    }

    private fun stopDragSelection() {
        if (isDragSelecting) {
            isDragSelecting = false
            lastSelectedPosition = RecyclerView.NO_POSITION
            autoScrollVelocity = 0
            autoScrollHandler.removeCallbacks(autoScrollRunnable)
            callback.onDragSelectFinished()
        }
    }

    companion object {
        private const val EDGE_SCROLL_THRESHOLD = 0.15f
        private const val AUTO_SCROLL_DELAY = 16L // ~60fps
        private const val BASE_SCROLL_SPEED = 10
        private const val MAX_EXTRA_SCROLL_SPEED = 30
    }
}
