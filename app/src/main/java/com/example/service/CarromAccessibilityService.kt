package com.example.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class CarromAccessibilityService : AccessibilityService() {

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        AutomationBridge.setAccessibilityConnected(true, this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Frame reading is handled by MediaProjection vision pipeline; no window tree scraping required.
    }

    override fun onInterrupt() {
        AutomationBridge.setAccessibilityConnected(false, null)
    }

    override fun onDestroy() {
        AutomationBridge.setAccessibilityConnected(false, null)
        super.onDestroy()
    }

    suspend fun performTap(x: Float, y: Float, durationMs: Long = 55L): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        return dispatchGestureSuspend(GestureDescription.Builder().addStroke(stroke).build())
    }

    /**
     * Smoothly drags the bottom striker slider bar from current X to target X.
     */
    suspend fun performSliderDrag(
        fromX: Float,
        fromY: Float,
        toX: Float,
        toY: Float,
        durationMs: Long = 340L
    ): Boolean {
        val midX = (fromX + toX) * 0.5f
        val midY = (fromY + toY) * 0.5f
        val path = Path().apply {
            moveTo(fromX, fromY)
            lineTo(midX, midY)
            lineTo(toX, toY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs.coerceAtLeast(180L))
        return dispatchGestureSuspend(GestureDescription.Builder().addStroke(stroke).build())
    }

    /**
     * Touches on the striker at (startX, startY), pulls back smoothly to (pullEndX, pullEndY),
     * briefly holds at full pull so the game's aiming reticle locks, and releases to fire.
     */
    suspend fun performPullAndReleaseShot(
        startX: Float,
        startY: Float,
        pullEndX: Float,
        pullEndY: Float,
        dragDurationMs: Long = 460L
    ): Boolean {
        val path = Path().apply {
            moveTo(startX, startY)
            // Slight intermediate waypoints give smooth acceleration & slight settle before release
            val p75x = startX + (pullEndX - startX) * 0.85f
            val p75y = startY + (pullEndY - startY) * 0.85f
            lineTo(p75x, p75y)
            lineTo(pullEndX, pullEndY)
            // Micro-hold near release point
            lineTo(pullEndX + 0.5f, pullEndY + 0.5f)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, dragDurationMs.coerceIn(250L, 1200L))
        return dispatchGestureSuspend(GestureDescription.Builder().addStroke(stroke).build())
    }

    private suspend fun dispatchGestureSuspend(gesture: GestureDescription): Boolean =
        suspendCancellableCoroutine { cont ->
            val callback = object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(false)
                }
            }
            val dispatched = dispatchGesture(gesture, callback, mainHandler)
            if (!dispatched && cont.isActive) {
                cont.resume(false)
            }
        }
}
