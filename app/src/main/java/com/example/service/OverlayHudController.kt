package com.example.service

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.example.model.AutomationPhase
import com.example.model.AutomationTuning
import com.example.model.BeforeAfterComparison
import com.example.model.BoardCalibration
import com.example.model.BoardDetectionResult
import com.example.model.CalibrationHandle
import com.example.model.NormalizedPoint
import com.example.model.PieceColor
import com.example.model.ShotOutcomeCategory
import com.example.model.ShotPlan
import com.example.model.TurnTargetMode
import kotlin.math.hypot

class OverlayHudController(
    private val context: Context,
    private val onStartClick: () -> Unit,
    private val onStopClick: () -> Unit,
    private val onAutoCalibrateClick: () -> Unit,
    private val onSaveCalibrationClick: (BoardCalibration) -> Unit,
    private val onCycleTurnMode: () -> Unit,
    private val onToggleDebugMarkers: () -> Unit,
    private val onCloseOverlayClick: () -> Unit
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var canvasView: OverlayCanvasView? = null
    private var canvasParams: WindowManager.LayoutParams? = null
    private var dockView: LinearLayout? = null
    private var dockParams: WindowManager.LayoutParams? = null

    private var statusTextView: TextView? = null
    private var alertTextView: TextView? = null
    private var reportTextView: TextView? = null
    private var startButton: Button? = null
    private var calibrateButton: Button? = null
    private var debugButton: Button? = null
    private var turnButton: Button? = null
    private var calibrationActionsRow: LinearLayout? = null

    private var showReportCard = false

    fun canDrawOverlay(): Boolean = Settings.canDrawOverlays(context)

    fun show() {
        if (!canDrawOverlay()) return
        mainHandler.post {
            if (canvasView != null) return@post
            createCanvasOverlay()
            createControlDock()
            AutomationBridge.setOverlayShowing(true)
            refreshState()
        }
    }

    fun hide() {
        mainHandler.post {
            canvasView?.let { runCatching { windowManager.removeView(it) } }
            dockView?.let { runCatching { windowManager.removeView(it) } }
            canvasView = null
            dockView = null
            AutomationBridge.setOverlayShowing(false)
            AutomationBridge.setCalibrationMode(false)
        }
    }

    fun refreshState() {
        mainHandler.post {
            val isPlaying = AutomationBridge.isAutoPlaying.value
            val phase = AutomationBridge.phase.value
            val status = AutomationBridge.statusBanner.value
            val alert = AutomationBridge.alertBanner.value
            val tuning = AutomationBridge.tuning.value
            val calMode = AutomationBridge.isCalibrationMode.value
            val detection = AutomationBridge.latestDetection.value
            val plan = AutomationBridge.latestShotPlan.value
            val comparison = AutomationBridge.latestComparison.value
            val calibration = AutomationBridge.activeCalibration.value

            canvasView?.updateRenderState(
                calibration = calibration,
                detection = detection,
                shotPlan = plan,
                comparison = comparison,
                tuning = tuning,
                calibrationMode = calMode,
                alert = alert
            )

            statusTextView?.text = "${phase.label}: $status"
            if (!alert.isNullOrBlank()) {
                alertTextView?.visibility = View.VISIBLE
                alertTextView?.text = "ALERT: $alert"
            } else {
                alertTextView?.visibility = View.GONE
            }

            startButton?.text = if (isPlaying) "Pause" else "Start"
            startButton?.setBackgroundColor(
                if (isPlaying) Color.parseColor("#F59E0B") else Color.parseColor("#22C55E")
            )
            calibrateButton?.text = if (calMode) "Done Cal" else "Calibrate"
            calibrateButton?.setBackgroundColor(
                if (calMode) Color.parseColor("#38BDF8") else Color.parseColor("#1A332B")
            )
            calibrationActionsRow?.visibility = if (calMode) View.VISIBLE else View.GONE

            debugButton?.text = if (tuning.showDebugMarkers) "Debug ON" else "Debug OFF"
            turnButton?.text = "Turn: ${tuning.turnTargetMode.label}"

            if (showReportCard) {
                reportTextView?.visibility = View.VISIBLE
                reportTextView?.text = buildQuickReportText(detection, plan, comparison)
            } else {
                reportTextView?.visibility = View.GONE
            }

            updateCanvasTouchability(calMode)
        }
    }

    private fun updateCanvasTouchability(calibrating: Boolean) {
        val view = canvasView ?: return
        val params = canvasParams ?: return
        val baseFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        val desiredFlags = if (calibrating) baseFlags else (baseFlags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        if (params.flags != desiredFlags) {
            params.flags = desiredFlags
            runCatching { windowManager.updateViewLayout(view, params) }
        }
    }

    private fun createCanvasOverlay() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        val view = OverlayCanvasView(context) { updatedCal ->
            AutomationBridge.updateCalibration(updatedCal)
        }
        canvasView = view
        canvasParams = params
        windowManager.addView(view, params)
    }

    private fun createControlDock() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 110
        }

        val dp = context.resources.displayMetrics.density
        val pad = (10 * dp).toInt()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E60B1915"))
                cornerRadius = 16 * dp
                setStroke((1.5f * dp).toInt(), Color.parseColor("#2E5C4E"))
            }
        }

        // Top drag bar + title + close
        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val dragTitle = TextView(context).apply {
            text = "⋮⋮ CarromPilot HUD"
            setTextColor(Color.parseColor("#F8FAFC"))
            textSize = 12.5f
            setPadding(4, 0, (12 * dp).toInt(), 0)
        }
        val closeBtn = TextView(context).apply {
            text = "✕"
            setTextColor(Color.parseColor("#F43F5E"))
            textSize = 15f
            setPadding((8 * dp).toInt(), 2, (4 * dp).toInt(), 2)
            setOnClickListener { onCloseOverlayClick() }
        }
        headerRow.addView(dragTitle, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        headerRow.addView(closeBtn)

        // Enable dragging the dock via headerRow
        headerRow.setOnTouchListener(object : View.OnTouchListener {
            private var startX = 0
            private var startY = 0
            private var touchStartX = 0f
            private var touchStartY = 0f
            override fun onTouch(v: View?, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = params.x
                        startY = params.y
                        touchStartX = event.rawX
                        touchStartY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = startX + (event.rawX - touchStartX).toInt()
                        params.y = startY + (event.rawY - touchStartY).toInt()
                        runCatching { windowManager.updateViewLayout(root, params) }
                        return true
                    }
                }
                return false
            }
        })
        root.addView(headerRow)

        // Live status line
        val statusTv = TextView(context).apply {
            text = "Overlay Ready"
            setTextColor(Color.parseColor("#6EE7B7"))
            textSize = 11f
            setPadding(4, (4 * dp).toInt(), 4, (4 * dp).toInt())
            maxWidth = (280 * dp).toInt()
        }
        statusTextView = statusTv
        root.addView(statusTv)

        // Alert text (hidden unless error/obstruction)
        val alertTv = TextView(context).apply {
            visibility = View.GONE
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#CCDC2626"))
            textSize = 11f
            setPadding((6 * dp).toInt(), (4 * dp).toInt(), (6 * dp).toInt(), (4 * dp).toInt())
            maxWidth = (280 * dp).toInt()
        }
        alertTextView = alertTv
        root.addView(alertTv)

        // Primary controls row: Start, Stop, Calibrate, Debug
        val btnRow1 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, (4 * dp).toInt(), 0, 0)
        }

        val btnStart = makeHudButton("Start", Color.parseColor("#22C55E"), Color.parseColor("#08120F")) {
            onStartClick()
        }
        val btnStop = makeHudButton("Stop", Color.parseColor("#F43F5E"), Color.WHITE) {
            onStopClick()
        }
        val btnCal = makeHudButton("Calibrate", Color.parseColor("#1A332B"), Color.WHITE) {
            val next = !AutomationBridge.isCalibrationMode.value
            AutomationBridge.setCalibrationMode(next)
            refreshState()
        }
        startButton = btnStart
        calibrateButton = btnCal

        btnRow1.addView(btnStart)
        btnRow1.addView(btnStop)
        btnRow1.addView(btnCal)
        root.addView(btnRow1)

        // Secondary controls row: Debug, Report toggle, Turn mode
        val btnRow2 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, (4 * dp).toInt(), 0, 0)
        }
        val btnDebug = makeHudButton("Debug ON", Color.parseColor("#1A332B"), Color.parseColor("#38BDF8")) {
            onToggleDebugMarkers()
        }
        val btnReport = makeHudButton("Report", Color.parseColor("#1A332B"), Color.parseColor("#FBBF24")) {
            showReportCard = !showReportCard
            refreshState()
        }
        val btnTurn = makeHudButton("Turn: Auto", Color.parseColor("#1A332B"), Color.WHITE) {
            onCycleTurnMode()
        }
        debugButton = btnDebug
        turnButton = btnTurn
        btnRow2.addView(btnDebug)
        btnRow2.addView(btnReport)
        btnRow2.addView(btnTurn)
        root.addView(btnRow2)

        // Calibration helper actions row (visible only while calibrating)
        val calRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
            setPadding(0, (6 * dp).toInt(), 0, 0)
        }
        val btnAutoFit = makeHudButton("Auto-Detect Board", Color.parseColor("#3D2807"), Color.parseColor("#FBBF24")) {
            onAutoCalibrateClick()
        }
        val btnSaveCal = makeHudButton("Save Calibration", Color.parseColor("#0E3B25"), Color.parseColor("#6EE7B7")) {
            onSaveCalibrationClick(AutomationBridge.activeCalibration.value)
            AutomationBridge.setCalibrationMode(false)
            refreshState()
        }
        calRow.addView(btnAutoFit)
        calRow.addView(btnSaveCal)
        calibrationActionsRow = calRow
        root.addView(calRow)

        // Quick Before/After & Piece Debug Report box
        val reportTv = TextView(context).apply {
            visibility = View.GONE
            setTextColor(Color.parseColor("#E2E8F0"))
            textSize = 10.5f
            setPadding((6 * dp).toInt(), (6 * dp).toInt(), (6 * dp).toInt(), (6 * dp).toInt())
            maxWidth = (280 * dp).toInt()
        }
        reportTextView = reportTv
        root.addView(reportTv)

        dockView = root
        dockParams = params
        windowManager.addView(root, params)
    }

    private fun makeHudButton(label: String, bgColor: Int, textColor: Int, onClick: () -> Unit): Button {
        val dp = context.resources.displayMetrics.density
        return Button(context).apply {
            text = label
            isAllCaps = false
            textSize = 11f
            setTextColor(textColor)
            setBackgroundColor(bgColor)
            setPadding((8 * dp).toInt(), (4 * dp).toInt(), (8 * dp).toInt(), (4 * dp).toInt())
            minHeight = (34 * dp).toInt()
            minimumHeight = (34 * dp).toInt()
            minWidth = 0
            minimumWidth = 0
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, (4 * dp).toInt(), 0)
            }
            setOnClickListener { onClick() }
        }
    }

    private fun buildQuickReportText(
        detection: BoardDetectionResult?,
        plan: ShotPlan?,
        comparison: BeforeAfterComparison?
    ): String {
        val sb = StringBuilder()
        if (detection != null) {
            sb.appendLine("Board: ${detection.validity.label}")
            sb.appendLine("Pieces: W=${detection.whiteCount} B=${detection.blackCount} Q=${detection.queenCount} Striker=${if (detection.striker != null) "OK" else "NO"}")
        } else {
            sb.appendLine("No live frame analyzed yet.")
        }
        if (plan != null) {
            sb.appendLine("Plan: ${plan.targetPiece.color.label} -> ${plan.selectedPocket.label} | Cut=${plan.cutAngleDeg.toInt()}° Pow=${(plan.appliedPower * 100).toInt()}%")
        }
        if (comparison != null) {
            val tag = if (comparison.outcomeDetail.category == ShotOutcomeCategory.SUCCESS) "GOOD" else "FAIL"
            sb.appendLine("Last [$tag]: ${comparison.outcomeDetail.label}")
            sb.appendLine("Travel: ${comparison.targetDirectionLabel} | StrMv=${String.format("%.3f", comparison.strikerDisplacementNorm)}")
            if (comparison.errorNote.isNotBlank()) sb.appendLine("Note: ${comparison.errorNote}")
        }
        return sb.toString().trim()
    }
}

private class OverlayCanvasView(
    context: Context,
    private val onCalibrationDragged: (BoardCalibration) -> Unit
) : View(context) {

    private var calibration = BoardCalibration()
    private var detection: BoardDetectionResult? = null
    private var shotPlan: ShotPlan? = null
    private var comparison: BeforeAfterComparison? = null
    private var tuning = AutomationTuning()
    private var calibrationMode = false
    private var alertBanner: String? = null

    private var activeHandle = CalibrationHandle.NONE

    private val boardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 56, 189, 248)
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val pocketPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(190, 251, 191, 36)
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val baselinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 34, 197, 94)
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val aimLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(225, 251, 191, 36)
        style = Paint.Style.STROKE
        strokeWidth = 5f
        pathEffect = DashPathEffect(floatArrayOf(18f, 10f), 0f)
    }
    private val pullArrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(235, 244, 63, 94)
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }
    private val pieceStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 24f
        setShadowLayer(4f, 1f, 1f, Color.BLACK)
    }
    private val handleFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 56, 189, 248)
        style = Paint.Style.FILL
    }
    private val ghostPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 168, 85, 247)
        style = Paint.Style.STROKE
        strokeWidth = 4f
        pathEffect = DashPathEffect(floatArrayOf(10f, 8f), 0f)
    }

    fun updateRenderState(
        calibration: BoardCalibration,
        detection: BoardDetectionResult?,
        shotPlan: ShotPlan?,
        comparison: BeforeAfterComparison?,
        tuning: AutomationTuning,
        calibrationMode: Boolean,
        alert: String?
    ) {
        this.calibration = calibration
        this.detection = detection
        this.shotPlan = shotPlan
        this.comparison = comparison
        this.tuning = tuning
        this.calibrationMode = calibrationMode
        this.alertBanner = alert
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val showMarkers = tuning.showDebugMarkers || calibrationMode

        if (showMarkers) {
            // Board boundary
            canvas.drawRect(
                calibration.boardLeft * w,
                calibration.boardTop * h,
                calibration.boardRight * w,
                calibration.boardBottom * h,
                boardPaint
            )

            // Corner pockets
            val pRad = calibration.pocketRadiusNorm * w
            calibration.allPockets().forEach { (_, pt) ->
                canvas.drawCircle(pt.x * w, pt.y * h, pRad, pocketPaint)
            }

            // Striker baseline & slider track
            canvas.drawLine(
                calibration.baselineLeftX * w,
                calibration.strikerBaselineY * h,
                calibration.baselineRightX * w,
                calibration.strikerBaselineY * h,
                baselinePaint
            )
            canvas.drawLine(
                calibration.sliderLeftX * w,
                calibration.sliderY * h,
                calibration.sliderRightX * w,
                calibration.sliderY * h,
                pocketPaint
            )
        }

        // Detected pieces
        if (tuning.showDebugMarkers) {
            detection?.pieces?.forEach { piece ->
                val cx = piece.normX * w
                val cy = piece.normY * h
                val r = (piece.radiusNorm * w).coerceAtLeast(14f)
                pieceStrokePaint.color = when (piece.color) {
                    PieceColor.WHITE -> Color.CYAN
                    PieceColor.BLACK -> Color.rgb(251, 191, 36)
                    PieceColor.QUEEN -> Color.rgb(244, 63, 94)
                    PieceColor.STRIKER -> Color.GREEN
                }
                canvas.drawCircle(cx, cy, r, pieceStrokePaint)
                val tag = when (piece.color) {
                    PieceColor.WHITE -> "W"
                    PieceColor.BLACK -> "B"
                    PieceColor.QUEEN -> "Q"
                    PieceColor.STRIKER -> "S"
                }
                canvas.drawText(tag, cx - 8f, cy - r - 6f, labelPaint)
            }
            detection?.striker?.let { st ->
                val cx = st.normX * w
                val cy = st.normY * h
                val r = (st.radiusNorm * w).coerceAtLeast(18f)
                pieceStrokePaint.color = Color.rgb(34, 197, 94)
                canvas.drawCircle(cx, cy, r, pieceStrokePaint)
                canvas.drawText("STRIKER", cx - 36f, cy - r - 8f, labelPaint)
            }
        }

        // Aiming lines & pull vector
        if (tuning.showAimingLines) {
            shotPlan?.let { plan ->
                val sx = plan.strikerTargetPos.x * w
                val sy = plan.strikerTargetPos.y * h
                val gx = plan.ghostImpactPos.x * w
                val gy = plan.ghostImpactPos.y * h
                val tx = plan.targetPiece.normX * w
                val ty = plan.targetPiece.normY * h
                val px = plan.pocketPoint.x * w
                val py = plan.pocketPoint.y * h
                val pullX = plan.pullEndPos.x * w
                val pullY = plan.pullEndPos.y * h

                canvas.drawLine(sx, sy, gx, gy, aimLinePaint)
                canvas.drawLine(tx, ty, px, py, aimLinePaint)
                canvas.drawCircle(gx, gy, 14f, pocketPaint)
                canvas.drawLine(sx, sy, pullX, pullY, pullArrowPaint)
                canvas.drawCircle(pullX, pullY, 10f, pullArrowPaint)
                canvas.drawText(
                    "${(plan.appliedPower * 100).toInt()}% | ${plan.cutAngleDeg.toInt()}°",
                    sx - 50f,
                    sy + 48f,
                    labelPaint
                )
            }
        }

        // Before/After displacement markers
        if (tuning.showBeforeAfterGhost) {
            comparison?.let { cmp ->
                val bx = cmp.targetBefore.x * w
                val by = cmp.targetBefore.y * h
                canvas.drawCircle(bx, by, 18f, ghostPaint)
                cmp.targetAfter?.let { after ->
                    val ax = after.x * w
                    val ay = after.y * h
                    canvas.drawLine(bx, by, ax, ay, ghostPaint)
                }
            }
        }

        // Interactive calibration handles
        if (calibrationMode) {
            calibrationHandles(w, h).forEach { (handle, pt) ->
                val radius = if (handle == activeHandle) 24f else 18f
                canvas.drawCircle(pt.x, pt.y, radius, handleFillPaint)
                canvas.drawText(handle.label, pt.x + 22f, pt.y + 8f, labelPaint)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!calibrationMode) return false
        val w = width.toFloat().coerceAtLeast(1f)
        val h = height.toFloat().coerceAtLeast(1f)

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val handles = calibrationHandles(w, h)
                activeHandle = handles.minByOrNull { (_, pt) ->
                    hypot(pt.x - event.x, pt.y - event.y)
                }?.takeIf { (_, pt) ->
                    hypot(pt.x - event.x, pt.y - event.y) < 75f
                }?.first ?: CalibrationHandle.NONE
                return activeHandle != CalibrationHandle.NONE
            }
            MotionEvent.ACTION_MOVE -> {
                if (activeHandle == CalibrationHandle.NONE) return false
                val nx = (event.x / w).coerceIn(0.01f, 0.99f)
                val ny = (event.y / h).coerceIn(0.05f, 0.96f)
                calibration = applyHandleMove(calibration, activeHandle, nx, ny)
                onCalibrationDragged(calibration)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activeHandle = CalibrationHandle.NONE
                return true
            }
        }
        return false
    }

    private fun calibrationHandles(w: Float, h: Float): List<Pair<CalibrationHandle, PointF>> = listOf(
        CalibrationHandle.BOARD_TOP_LEFT to PointF(calibration.topLeft.x * w, calibration.topLeft.y * h),
        CalibrationHandle.BOARD_TOP_RIGHT to PointF(calibration.topRight.x * w, calibration.topRight.y * h),
        CalibrationHandle.BOARD_BOTTOM_LEFT to PointF(calibration.bottomLeft.x * w, calibration.bottomLeft.y * h),
        CalibrationHandle.BOARD_BOTTOM_RIGHT to PointF(calibration.bottomRight.x * w, calibration.bottomRight.y * h),
        CalibrationHandle.POCKET_TOP_LEFT to PointF(calibration.pocketTopLeft.x * w, calibration.pocketTopLeft.y * h),
        CalibrationHandle.POCKET_TOP_RIGHT to PointF(calibration.pocketTopRight.x * w, calibration.pocketTopRight.y * h),
        CalibrationHandle.POCKET_BOTTOM_LEFT to PointF(calibration.pocketBottomLeft.x * w, calibration.pocketBottomLeft.y * h),
        CalibrationHandle.POCKET_BOTTOM_RIGHT to PointF(calibration.pocketBottomRight.x * w, calibration.pocketBottomRight.y * h),
        CalibrationHandle.BASELINE_LEFT to PointF(calibration.baselineLeftX * w, calibration.strikerBaselineY * h),
        CalibrationHandle.BASELINE_RIGHT to PointF(calibration.baselineRightX * w, calibration.strikerBaselineY * h),
        CalibrationHandle.SLIDER_LEFT to PointF(calibration.sliderLeftX * w, calibration.sliderY * h),
        CalibrationHandle.SLIDER_RIGHT to PointF(calibration.sliderRightX * w, calibration.sliderY * h)
    )

    private fun applyHandleMove(
        cal: BoardCalibration,
        handle: CalibrationHandle,
        nx: Float,
        ny: Float
    ): BoardCalibration = when (handle) {
        CalibrationHandle.BOARD_TOP_LEFT -> cal.copy(
            topLeft = NormalizedPoint(nx, ny),
            topRight = cal.topRight.copy(y = ny),
            bottomLeft = cal.bottomLeft.copy(x = nx)
        )
        CalibrationHandle.BOARD_TOP_RIGHT -> cal.copy(
            topRight = NormalizedPoint(nx, ny),
            topLeft = cal.topLeft.copy(y = ny),
            bottomRight = cal.bottomRight.copy(x = nx)
        )
        CalibrationHandle.BOARD_BOTTOM_LEFT -> cal.copy(
            bottomLeft = NormalizedPoint(nx, ny),
            bottomRight = cal.bottomRight.copy(y = ny),
            topLeft = cal.topLeft.copy(x = nx)
        )
        CalibrationHandle.BOARD_BOTTOM_RIGHT -> cal.copy(
            bottomRight = NormalizedPoint(nx, ny),
            bottomLeft = cal.bottomLeft.copy(y = ny),
            topRight = cal.topRight.copy(x = nx)
        )
        CalibrationHandle.POCKET_TOP_LEFT -> cal.copy(pocketTopLeft = NormalizedPoint(nx, ny))
        CalibrationHandle.POCKET_TOP_RIGHT -> cal.copy(pocketTopRight = NormalizedPoint(nx, ny))
        CalibrationHandle.POCKET_BOTTOM_LEFT -> cal.copy(pocketBottomLeft = NormalizedPoint(nx, ny))
        CalibrationHandle.POCKET_BOTTOM_RIGHT -> cal.copy(pocketBottomRight = NormalizedPoint(nx, ny))
        CalibrationHandle.BASELINE_LEFT -> cal.copy(baselineLeftX = nx, strikerBaselineY = ny)
        CalibrationHandle.BASELINE_RIGHT -> cal.copy(baselineRightX = nx, strikerBaselineY = ny)
        CalibrationHandle.SLIDER_LEFT -> cal.copy(sliderLeftX = nx, sliderY = ny)
        CalibrationHandle.SLIDER_RIGHT -> cal.copy(sliderRightX = nx, sliderY = ny)
        CalibrationHandle.NONE -> cal
    }
}
