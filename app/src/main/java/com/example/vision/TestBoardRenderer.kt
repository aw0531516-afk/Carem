package com.example.vision

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.example.model.BoardCalibration
import com.example.model.DetectedPiece
import com.example.model.NormalizedPoint
import com.example.model.PieceColor

object TestBoardRenderer {

    enum class TestScenario(val label: String, val description: String) {
        OPEN_BOARD("Clean Open Board", "Balanced layout with clear straight & cut shots"),
        STRAIGHT_SHOT("Straight Corner Shot", "High-confidence straight pocket setup"),
        QUEEN_BEFORE_LAST("Queen Rule Priority", "1 target puck left + Queen on board"),
        POPUP_OBSTRUCTION("Pop-up Obstruction", "Simulates mid-board microphone/emoji alert"),
        UNREALISTIC_NO_BOARD("Invalid Lobby Screen", "Simulates menu screen with no board"),
        AFTER_CLEAN_POCKET("After: Clean Pocket", "Target puck pocketed and striker moved"),
        AFTER_TOO_LITTLE_FORCE("After: Too Little Force", "Target stopped short along pocket line"),
        AFTER_NO_MOVEMENT("After: No Movement Error", "Identical board simulating ignored touch")
    }

    fun renderScenarioBitmap(
        scenario: TestScenario,
        calibration: BoardCalibration,
        width: Int = 720,
        height: Int = 1600
    ): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        if (scenario == TestScenario.UNREALISTIC_NO_BOARD) {
            canvas.drawColor(Color.rgb(22, 65, 118))
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(245, 190, 40)
                style = Paint.Style.FILL
            }
            canvas.drawRoundRect(
                RectF(width * 0.2f, height * 0.65f, width * 0.8f, height * 0.72f),
                28f, 28f, p
            )
            return bmp
        }

        // Green felt background
        canvas.drawColor(Color.rgb(16, 110, 86))

        // Top HUD player timer ring (left player active)
        val hudGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(52, 235, 100)
            style = Paint.Style.STROKE
            strokeWidth = 10f
        }
        canvas.drawCircle(width * 0.20f, height * 0.17f, width * 0.06f, hudGlow)

        // Left player black target icon below avatar
        val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(28, 28, 30)
            style = Paint.Style.FILL
        }
        canvas.drawCircle(width * 0.18f, height * 0.245f, 12f, iconPaint)

        // Wooden frame
        val left = calibration.boardLeft * width
        val top = calibration.boardTop * height
        val right = calibration.boardRight * width
        val bottom = calibration.boardBottom * height
        val bw = right - left
        val bh = bottom - top

        val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(168, 108, 48)
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(RectF(left, top, right, bottom), 28f, 28f, framePaint)

        // Tan playing surface
        val inset = bw * 0.045f
        val surfacePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(212, 178, 138)
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(
            RectF(left + inset, top + inset, right - inset, bottom - inset),
            16f, 16f, surfacePaint
        )

        // Baseline tracks + subtle terracotta circles
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(150, 86, 68)
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
        }
        val bLeft = calibration.baselineLeftX * width
        val bRight = calibration.baselineRightX * width
        val bY = calibration.strikerBaselineY * height
        canvas.drawLine(bLeft, bY - 12f, bRight, bY - 12f, linePaint)
        canvas.drawLine(bLeft, bY + 12f, bRight, bY + 12f, linePaint)

        // Corner pockets
        val pocketPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(24, 14, 10)
            style = Paint.Style.FILL
        }
        val pRad = calibration.pocketRadiusNorm * width
        calibration.allPockets().forEach { (_, pt) ->
            canvas.drawCircle(pt.x * width, pt.y * height, pRad, pocketPaint)
        }

        // Bottom slider bar
        val sliderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(164, 140, 100)
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(
            RectF(
                calibration.sliderLeftX * width - 25f,
                calibration.sliderY * height - 18f,
                calibration.sliderRightX * width + 25f,
                calibration.sliderY * height + 18f
            ),
            18f, 18f, sliderPaint
        )

        // Pucks for scenario
        val pieces = piecesForScenario(scenario, calibration)
        val puckR = calibration.puckRadiusNorm * bw
        val strikerR = calibration.strikerRadiusNorm * bw

        pieces.forEach { piece ->
            val cx = piece.normX * width
            val cy = piece.normY * height
            when (piece.color) {
                PieceColor.WHITE -> drawPuck(canvas, cx, cy, puckR, Color.rgb(244, 240, 232), Color.rgb(200, 195, 185))
                PieceColor.BLACK -> drawPuck(canvas, cx, cy, puckR, Color.rgb(28, 30, 34), Color.rgb(65, 68, 72))
                PieceColor.QUEEN -> drawPuck(canvas, cx, cy, puckR, Color.rgb(228, 38, 42), Color.rgb(165, 22, 26))
                PieceColor.STRIKER -> {
                    val gold = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(232, 188, 52) }
                    val green = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(42, 178, 76) }
                    val star = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(236, 248, 232) }
                    canvas.drawCircle(cx, cy, strikerR, gold)
                    canvas.drawCircle(cx, cy, strikerR * 0.84f, green)
                    canvas.drawCircle(cx, cy, strikerR * 0.36f, star)
                }
            }
        }

        if (scenario == TestScenario.POPUP_OBSTRUCTION) {
            val bannerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(215, 20, 20, 22)
                style = Paint.Style.FILL
            }
            canvas.drawRect(
                left + bw * 0.08f,
                top + bh * 0.43f,
                right - bw * 0.08f,
                top + bh * 0.56f,
                bannerPaint
            )
            val txtPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 26f
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText("Please check the setting of microphone.", width * 0.5f, top + bh * 0.505f, txtPaint)
        }

        return bmp
    }

    fun piecesForScenario(scenario: TestScenario, cal: BoardCalibration): List<DetectedPiece> {
        val bx = cal.boardLeft
        val by = cal.boardTop
        val bw = cal.boardWidth
        val bh = cal.boardHeight

        fun p(id: String, c: PieceColor, rx: Float, ry: Float) = DetectedPiece(
            id = id,
            color = c,
            normX = bx + bw * rx,
            normY = by + bh * ry,
            radiusNorm = cal.puckRadiusNorm,
            confidence = 1f
        )

        val striker = DetectedPiece(
            id = "striker_0",
            color = PieceColor.STRIKER,
            normX = (cal.baselineLeftX + cal.baselineRightX) * 0.5f,
            normY = cal.strikerBaselineY,
            radiusNorm = cal.strikerRadiusNorm,
            confidence = 1f
        )

        return when (scenario) {
            TestScenario.OPEN_BOARD, TestScenario.POPUP_OBSTRUCTION, TestScenario.AFTER_NO_MOVEMENT -> listOf(
                p("b1", PieceColor.BLACK, 0.74f, 0.31f),
                p("b2", PieceColor.BLACK, 0.25f, 0.36f),
                p("b3", PieceColor.BLACK, 0.62f, 0.58f),
                p("w1", PieceColor.WHITE, 0.40f, 0.47f),
                p("w2", PieceColor.WHITE, 0.58f, 0.43f),
                p("w3", PieceColor.WHITE, 0.34f, 0.62f),
                p("q1", PieceColor.QUEEN, 0.50f, 0.50f),
                striker
            )
            TestScenario.STRAIGHT_SHOT -> listOf(
                p("b1", PieceColor.BLACK, 0.76f, 0.29f),
                p("w1", PieceColor.WHITE, 0.38f, 0.48f),
                p("q1", PieceColor.QUEEN, 0.48f, 0.43f),
                striker
            )
            TestScenario.QUEEN_BEFORE_LAST -> listOf(
                p("b1", PieceColor.BLACK, 0.30f, 0.42f),
                p("w1", PieceColor.WHITE, 0.68f, 0.55f),
                p("q1", PieceColor.QUEEN, 0.71f, 0.33f),
                striker
            )
            TestScenario.AFTER_CLEAN_POCKET -> listOf(
                // b1 was pocketed! striker deflected slightly right
                p("b2", PieceColor.BLACK, 0.25f, 0.36f),
                p("b3", PieceColor.BLACK, 0.62f, 0.58f),
                p("w1", PieceColor.WHITE, 0.40f, 0.47f),
                p("w2", PieceColor.WHITE, 0.58f, 0.43f),
                p("w3", PieceColor.WHITE, 0.34f, 0.62f),
                p("q1", PieceColor.QUEEN, 0.50f, 0.50f),
                striker.copy(normX = bx + bw * 0.65f, normY = by + bh * 0.42f)
            )
            TestScenario.AFTER_TOO_LITTLE_FORCE -> listOf(
                // b1 moved forward-right toward top-right pocket, stopped near pocket
                p("b1", PieceColor.BLACK, 0.85f, 0.17f),
                p("b2", PieceColor.BLACK, 0.25f, 0.36f),
                p("b3", PieceColor.BLACK, 0.62f, 0.58f),
                p("w1", PieceColor.WHITE, 0.40f, 0.47f),
                p("w2", PieceColor.WHITE, 0.58f, 0.43f),
                p("w3", PieceColor.WHITE, 0.34f, 0.62f),
                p("q1", PieceColor.QUEEN, 0.50f, 0.50f),
                striker.copy(normX = bx + bw * 0.67f, normY = by + bh * 0.39f)
            )
            TestScenario.UNREALISTIC_NO_BOARD -> emptyList()
        }
    }

    private fun drawPuck(canvas: Canvas, cx: Float, cy: Float, r: Float, fill: Int, ring: Int) {
        val pFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill }
        val pRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ring
            style = Paint.Style.STROKE
            strokeWidth = maxOf(2f, r * 0.16f)
        }
        canvas.drawCircle(cx, cy, r, pFill)
        canvas.drawCircle(cx, cy, r * 0.65f, pRing)
    }
}
