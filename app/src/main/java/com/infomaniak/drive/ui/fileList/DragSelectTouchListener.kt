package com.infomaniak.drive.ui.fileList

import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import androidx.core.view.GestureDetectorCompat
import androidx.recyclerview.widget.RecyclerView

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
        fun onDragSelectRangeDeselected(start: Int, end: Int)
        fun onDragSelectFinished()
        fun isPositionSelectable(position: Int): Boolean
    }

    private var isDragSelecting = false
    private var dragAnchorPosition = RecyclerView.NO_POSITION
    private var lastSelectedPosition = RecyclerView.NO_POSITION
    private var selectionMinBound = RecyclerView.NO_POSITION
    private var selectionMaxBound = RecyclerView.NO_POSITION

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
                dragAnchorPosition = position
                lastSelectedPosition = position
                selectionMinBound = position
                selectionMaxBound = position
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

        // Find and select/deselect item under finger
        val child = recyclerView.findChildViewUnder(e.x, y)
        if (child != null) {
            val currentPosition = recyclerView.getChildAdapterPosition(child)
            if (currentPosition != RecyclerView.NO_POSITION && currentPosition != lastSelectedPosition) {
                // Calculate new selection range from anchor to current position
                val newMin = minOf(dragAnchorPosition, currentPosition)
                val newMax = maxOf(dragAnchorPosition, currentPosition)

                // Deselect items that are no longer in the selection range
                // Items below the new minimum that were previously selected
                if (newMin > selectionMinBound) {
                    callback.onDragSelectRangeDeselected(selectionMinBound, newMin - 1)
                }
                // Items above the new maximum that were previously selected
                if (newMax < selectionMaxBound) {
                    callback.onDragSelectRangeDeselected(newMax + 1, selectionMaxBound)
                }

                // Select items in the new range that weren't previously selected
                // New items below the old minimum
                if (newMin < selectionMinBound) {
                    callback.onDragSelectChanged(newMin, selectionMinBound - 1)
                }
                // New items above the old maximum
                if (newMax > selectionMaxBound) {
                    callback.onDragSelectChanged(selectionMaxBound + 1, newMax)
                }

                // Update bounds to track the current selection range
                selectionMinBound = newMin
                selectionMaxBound = newMax
                lastSelectedPosition = currentPosition
            }
        }
    }

    private fun stopDragSelection() {
        if (isDragSelecting) {
            isDragSelecting = false
            dragAnchorPosition = RecyclerView.NO_POSITION
            lastSelectedPosition = RecyclerView.NO_POSITION
            selectionMinBound = RecyclerView.NO_POSITION
            selectionMaxBound = RecyclerView.NO_POSITION
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
