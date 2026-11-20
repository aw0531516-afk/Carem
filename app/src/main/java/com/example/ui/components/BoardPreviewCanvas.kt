package com.example.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.BeforeAfterComparison
import com.example.model.BoardCalibration
import com.example.model.BoardDetectionResult
import com.example.model.CalibrationHandle
import com.example.model.NormalizedPoint
import com.example.model.PieceColor
import com.example.model.ShotPlan
import com.example.ui.theme.BurnishedBrass
import com.example.ui.theme.CyanTelemetry
import com.example.ui.theme.ObsidianEmerald
import com.example.ui.theme.QueenCrimson
import com.example.ui.theme.TournamentEmerald
import kotlin.math.hypot

@Composable
fun BoardPreviewCanvas(
    bitmap: Bitmap?,
    calibration: BoardCalibration,
    detection: BoardDetectionResult?,
    shotPlan: ShotPlan?,
    comparison: BeforeAfterComparison? = null,
    showCalibrationHandles: Boolean = false,
    showDebugMarkers: Boolean = true,
    onCalibrationChanged: ((BoardCalibration) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val textMeasurer = rememberTextMeasurer()
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var draggingHandle by remember { mutableStateOf(CalibrationHandle.NONE) }

    val aspect = remember(bitmap) {
        if (bitmap != null && bitmap.height > 0) {
            (bitmap.width.toFloat() / bitmap.height.toFloat()).coerceIn(0.4f, 1.8f)
        } else {
            9f / 20f
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(aspect)
            .clip(RoundedCornerShape(18.dp))
            .background(ObsidianEmerald)
            .onSizeChanged { canvasSize = it }
            .then(
                if (showCalibrationHandles && onCalibrationChanged != null) {
                    Modifier.pointerInput(calibration, canvasSize) {
                        detectDragGestures(
                            onDragStart = { startOffset ->
                                val w = canvasSize.width.toFloat().coerceAtLeast(1f)
                                val h = canvasSize.height.toFloat().coerceAtLeast(1f)
                                val handles = buildHandleOffsets(calibration, w, h)
                                draggingHandle = handles.minByOrNull { (_, pt) ->
                                    hypot(pt.x - startOffset.x, pt.y - startOffset.y)
                                }?.takeIf { (_, pt) ->
                                    hypot(pt.x - startOffset.x, pt.y - startOffset.y) < 64f
                                }?.first ?: CalibrationHandle.NONE
                            },
                            onDrag = { change, _ ->
                                if (draggingHandle != CalibrationHandle.NONE) {
                                    change.consume()
                                    val w = canvasSize.width.toFloat().coerceAtLeast(1f)
                                    val h = canvasSize.height.toFloat().coerceAtLeast(1f)
                                    val nx = (change.position.x / w).coerceIn(0.01f, 0.99f)
                                    val ny = (change.position.y / h).coerceIn(0.03f, 0.97f)
                                    onCalibrationChanged(moveHandle(calibration, draggingHandle, nx, ny))
                                }
                            },
                            onDragEnd = { draggingHandle = CalibrationHandle.NONE },
                            onDragCancel = { draggingHandle = CalibrationHandle.NONE }
                        )
                    }
                } else Modifier
            )
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Carrom board live preview",
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize()
            )
        }

        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height

            if (showDebugMarkers || showCalibrationHandles) {
                // Board box
                drawRect(
                    color = CyanTelemetry.copy(alpha = 0.85f),
                    topLeft = Offset(calibration.boardLeft * w, calibration.boardTop * h),
                    size = Size(calibration.boardWidth * w, calibration.boardHeight * h),
                    style = Stroke(width = 2.5.dp.toPx())
                )

                // Pockets
                val pocketRad = calibration.pocketRadiusNorm * w
                calibration.allPockets().forEach { (_, pt) ->
                    drawCircle(
                        color = BurnishedBrass.copy(alpha = 0.85f),
                        radius = pocketRad,
                        center = Offset(pt.x * w, pt.y * h),
                        style = Stroke(width = 2.dp.toPx())
                    )
                }

                // Striker baseline
                drawLine(
                    color = TournamentEmerald,
                    start = Offset(calibration.baselineLeftX * w, calibration.strikerBaselineY * h),
                    end = Offset(calibration.baselineRightX * w, calibration.strikerBaselineY * h),
                    strokeWidth = 2.5.dp.toPx()
                )

                // Bottom slider track
                drawLine(
                    color = BurnishedBrass,
                    start = Offset(calibration.sliderLeftX * w, calibration.sliderY * h),
                    end = Offset(calibration.sliderRightX * w, calibration.sliderY * h),
                    strokeWidth = 3.dp.toPx()
                )
            }

            // Detected pieces
            if (showDebugMarkers && detection != null) {
                detection.pieces.forEach { piece ->
                    val center = Offset(piece.normX * w, piece.normY * h)
                    val radius = (piece.radiusNorm * w).coerceAtLeast(10f)
                    val ringColor = when (piece.color) {
                        PieceColor.WHITE -> CyanTelemetry
                        PieceColor.BLACK -> BurnishedBrass
                        PieceColor.QUEEN -> QueenCrimson
                        PieceColor.STRIKER -> TournamentEmerald
                    }
                    drawCircle(
                        color = ringColor,
                        radius = radius,
                        center = center,
                        style = Stroke(width = 2.5.dp.toPx())
                    )
                    val label = when (piece.color) {
                        PieceColor.WHITE -> "W"
                        PieceColor.BLACK -> "B"
                        PieceColor.QUEEN -> "Q"
                        PieceColor.STRIKER -> "S"
                    }
                    val layout = textMeasurer.measure(
                        text = label,
                        style = TextStyle(color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    )
                    drawText(layout, topLeft = Offset(center.x - layout.size.width / 2f, center.y - radius - layout.size.height))
                }

                detection.striker?.let { st ->
                    val center = Offset(st.normX * w, st.normY * h)
                    val radius = (st.radiusNorm * w).coerceAtLeast(12f)
                    drawCircle(
                        color = TournamentEmerald,
                        radius = radius,
                        center = center,
                        style = Stroke(width = 3.dp.toPx())
                    )
                }
            }

            // Aiming lines + pull-back vector
            if (showDebugMarkers && shotPlan != null) {
                val strikerPt = Offset(shotPlan.strikerTargetPos.x * w, shotPlan.strikerTargetPos.y * h)
                val ghostPt = Offset(shotPlan.ghostImpactPos.x * w, shotPlan.ghostImpactPos.y * h)
                val targetPt = Offset(shotPlan.targetPiece.normX * w, shotPlan.targetPiece.normY * h)
                val pocketPt = Offset(shotPlan.pocketPoint.x * w, shotPlan.pocketPoint.y * h)
                val pullEnd = Offset(shotPlan.pullEndPos.x * w, shotPlan.pullEndPos.y * h)

                val dash = PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f)
                drawLine(color = Color.White, start = strikerPt, end = ghostPt, strokeWidth = 2.5.dp.toPx(), pathEffect = dash)
                drawLine(color = BurnishedBrass, start = targetPt, end = pocketPt, strokeWidth = 2.5.dp.toPx(), pathEffect = dash)
                drawCircle(color = BurnishedBrass, radius = 9.dp.toPx(), center = ghostPt, style = Stroke(width = 2.dp.toPx()))
                drawCircle(color = TournamentEmerald, radius = 11.dp.toPx(), center = strikerPt, style = Stroke(width = 2.dp.toPx()))
                // Slingshot pull arrow
                drawLine(color = QueenCrimson, start = strikerPt, end = pullEnd, strokeWidth = 3.5.dp.toPx())
                drawCircle(color = QueenCrimson, radius = 6.dp.toPx(), center = pullEnd)
            }

            // Before / After ghost arrow
            if (showDebugMarkers && comparison != null) {
                val beforePt = Offset(comparison.targetBefore.x * w, comparison.targetBefore.y * h)
                val ghostDash = PathEffect.dashPathEffect(floatArrayOf(8f, 6f), 0f)
                drawCircle(
                    color = Color(0xFFA855F7),
                    radius = 12.dp.toPx(),
                    center = beforePt,
                    style = Stroke(width = 2.dp.toPx(), pathEffect = ghostDash)
                )
                comparison.targetAfter?.let { after ->
                    val afterPt = Offset(after.x * w, after.y * h)
                    drawLine(
                        color = Color(0xFFA855F7),
                        start = beforePt,
                        end = afterPt,
                        strokeWidth = 2.5.dp.toPx(),
                        pathEffect = ghostDash
                    )
                }
            }

            // Calibration drag handles
            if (showCalibrationHandles) {
                buildHandleOffsets(calibration, w, h).forEach { (handle, pt) ->
                    val isDragging = handle == draggingHandle
                    drawCircle(
                        color = if (isDragging) BurnishedBrass else CyanTelemetry,
                        radius = if (isDragging) 11.dp.toPx() else 8.dp.toPx(),
                        center = pt
                    )
                    drawCircle(
                        color = Color.White,
                        radius = if (isDragging) 11.dp.toPx() else 8.dp.toPx(),
                        center = pt,
                        style = Stroke(width = 1.5.dp.toPx())
                    )
                }
            }
        }
    }
}

private fun buildHandleOffsets(cal: BoardCalibration, w: Float, h: Float): List<Pair<CalibrationHandle, Offset>> = listOf(
    CalibrationHandle.BOARD_TOP_LEFT to Offset(cal.topLeft.x * w, cal.topLeft.y * h),
    CalibrationHandle.BOARD_TOP_RIGHT to Offset(cal.topRight.x * w, cal.topRight.y * h),
    CalibrationHandle.BOARD_BOTTOM_LEFT to Offset(cal.bottomLeft.x * w, cal.bottomLeft.y * h),
    CalibrationHandle.BOARD_BOTTOM_RIGHT to Offset(cal.bottomRight.x * w, cal.bottomRight.y * h),
    CalibrationHandle.POCKET_TOP_LEFT to Offset(cal.pocketTopLeft.x * w, cal.pocketTopLeft.y * h),
    CalibrationHandle.POCKET_TOP_RIGHT to Offset(cal.pocketTopRight.x * w, cal.pocketTopRight.y * h),
    CalibrationHandle.POCKET_BOTTOM_LEFT to Offset(cal.pocketBottomLeft.x * w, cal.pocketBottomLeft.y * h),
    CalibrationHandle.POCKET_BOTTOM_RIGHT to Offset(cal.pocketBottomRight.x * w, cal.pocketBottomRight.y * h),
    CalibrationHandle.BASELINE_LEFT to Offset(cal.baselineLeftX * w, cal.strikerBaselineY * h),
    CalibrationHandle.BASELINE_RIGHT to Offset(cal.baselineRightX * w, cal.strikerBaselineY * h),
    CalibrationHandle.SLIDER_LEFT to Offset(cal.sliderLeftX * w, cal.sliderY * h),
    CalibrationHandle.SLIDER_RIGHT to Offset(cal.sliderRightX * w, cal.sliderY * h)
)

private fun moveHandle(cal: BoardCalibration, handle: CalibrationHandle, nx: Float, ny: Float): BoardCalibration {
    return when (handle) {
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
