package com.example.vision

import android.graphics.Bitmap
import android.graphics.Color
import com.example.model.AutomationTuning
import com.example.model.BoardCalibration
import com.example.model.BoardDetectionResult
import com.example.model.DetectedPiece
import com.example.model.FrameValidity
import com.example.model.NormalizedPoint
import com.example.model.PieceColor
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class BoardVisionAnalyzer {

    fun analyzeFrame(
        bitmap: Bitmap,
        calibration: BoardCalibration,
        tuning: AutomationTuning = AutomationTuning()
    ): BoardDetectionResult {
        val w = bitmap.width
        val h = bitmap.height
        if (w < 100 || h < 100) {
            return BoardDetectionResult(
                frameWidth = w,
                frameHeight = h,
                validity = FrameValidity.INVALID_NO_BOARD,
                validationDetail = "Frame resolution too small (${w}x${h})"
            )
        }

        // Work on a downscaled copy (~360px wide) for fast real-time vision (<18ms)
        val targetW = 360
        val scale = targetW.toFloat() / w.toFloat()
        val targetH = (h * scale).roundToInt().coerceAtLeast(100)
        val scaled = if (w > targetW + 40) {
            Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
        } else {
            bitmap
        }

        val sw = scaled.width
        val sh = scaled.height
        val pixels = IntArray(sw * sh)
        scaled.getPixels(pixels, 0, sw, 0, 0, sw, sh)
        if (scaled !== bitmap) {
            scaled.recycle()
        }

        // Compute board ROI in scaled pixel coordinates
        val marginInsetX = calibration.boardWidth * 0.06f
        val marginInsetY = calibration.boardHeight * 0.06f
        val roiLeft = ((calibration.boardLeft + marginInsetX) * sw).roundToInt().coerceIn(0, sw - 2)
        val roiRight = ((calibration.boardRight - marginInsetX) * sw).roundToInt().coerceIn(roiLeft + 1, sw - 1)
        val roiTop = ((calibration.boardTop + marginInsetY) * sh).roundToInt().coerceIn(0, sh - 2)
        val roiBottom = ((calibration.boardBottom - marginInsetY) * sh).roundToInt().coerceIn(roiTop + 1, sh - 1)
        val roiW = (roiRight - roiLeft).coerceAtLeast(10)
        val roiH = (roiBottom - roiTop).coerceAtLeast(10)

        // 1. Sample board interior reference color (median-like warm tan check)
        val boardStats = sampleBoardSurface(pixels, sw, sh, roiLeft, roiTop, roiRight, roiBottom)
        if (!boardStats.looksLikeCarromBoard) {
            return BoardDetectionResult(
                timestampMs = System.currentTimeMillis(),
                frameWidth = w,
                frameHeight = h,
                boardBrightness = boardStats.avgLuminance,
                validity = FrameValidity.INVALID_NO_BOARD,
                validationDetail = "Carrom board surface not recognized (${boardStats.tanRatioPercent}% tan match)"
            )
        }

        // 2. Obstruction / pop-up detection inside board & banner bands
        val obstructions = detectObstructions(pixels, sw, sh, calibration, boardStats)
        if (obstructions.isNotEmpty()) {
            return BoardDetectionResult(
                timestampMs = System.currentTimeMillis(),
                frameWidth = w,
                frameHeight = h,
                boardBrightness = boardStats.avgLuminance,
                obstructionZones = obstructions,
                validity = FrameValidity.INVALID_OBSTRUCTION,
                validationDetail = "Obstruction / pop-up detected: ${obstructions.joinToString(", ")}"
            )
        }

        // 3. Classify pixels inside playable area into piece masks
        val cellCount = roiW * roiH
        val classMap = ByteArray(cellCount) // 0=none, 1=white, 2=black, 3=queen, 4=striker
        val hsv = FloatArray(3)
        val pocketCenters = calibration.allPockets().map { (_, pt) ->
            Pair(pt.x * sw, pt.y * sh)
        }
        val pocketIgnoreRadius = (calibration.pocketRadiusNorm * sw * 1.12f)

        for (py in roiTop until roiBottom) {
            val rowOffset = py * sw
            val roiRow = (py - roiTop) * roiW
            for (px in roiLeft until roiRight) {
                // Skip pixels inside corner pocket holes
                var inPocket = false
                for ((cx, cy) in pocketCenters) {
                    if (hypot((px - cx).toDouble(), (py - cy).toDouble()) < pocketIgnoreRadius) {
                        inPocket = true
                        break
                    }
                }
                if (inPocket) continue

                val c = pixels[rowOffset + px]
                val r = Color.red(c)
                val g = Color.green(c)
                val b = Color.blue(c)
                val lum = (0.299f * r + 0.587f * g + 0.114f * b)

                Color.RGBToHSV(r, g, b, hsv)
                val hue = hsv[0]
                val sat = hsv[1]
                val valV = hsv[2]

                val idx = roiRow + (px - roiLeft)
                val normY = py.toFloat() / sh.toFloat()
                val nearBaseline = abs(normY - calibration.strikerBaselineY) < (calibration.strikerRadiusNorm * 1.9f)

                // Striker detection: vivid green/teal/gold striker OR striker-hued disc near baseline
                val isStrikerHue = (hue in tuning.customStrikerHueMin..tuning.customStrikerHueMax && sat > 0.28f && valV > 0.30f) ||
                    (nearBaseline && hue in 35f..70f && sat > 0.42f && valV > 0.52f)
                if (isStrikerHue) {
                    classMap[idx] = CLASS_STRIKER
                    continue
                }

                // Queen (Red piece): vivid crimson red, distinctly higher saturation & R-G gap than printed terracotta baseline circles
                if ((hue <= 16f || hue >= 342f) && sat > 0.58f && valV > 0.35f && (r - g) > 95) {
                    classMap[idx] = CLASS_QUEEN
                    continue
                }

                // White piece: crisp bright ivory significantly brighter than tan board
                val lumDiff = lum - boardStats.avgLuminance
                if (valV > 0.76f && sat < 0.22f && lumDiff > 24f) {
                    classMap[idx] = CLASS_WHITE
                    continue
                }

                // Black piece: dark charcoal/black (including molded highlight ring) significantly darker than tan board
                if (valV < 0.38f && sat < 0.40f && lumDiff < -48f) {
                    classMap[idx] = CLASS_BLACK
                }
            }
        }

        // 4. Connected component clustering for each piece class
        val expectedPuckRadiusPx = (calibration.puckRadiusNorm * calibration.boardWidth * sw).coerceAtLeast(4f)
        val expectedStrikerRadiusPx = (calibration.strikerRadiusNorm * calibration.boardWidth * sw).coerceAtLeast(5f)
        val singlePuckArea = (Math.PI * expectedPuckRadiusPx * expectedPuckRadiusPx).toFloat()
        val singleStrikerArea = (Math.PI * expectedStrikerRadiusPx * expectedStrikerRadiusPx).toFloat()

        val detectedPieces = mutableListOf<DetectedPiece>()
        var striker: DetectedPiece? = null

        val whiteBlobs = extractBlobs(classMap, roiW, roiH, CLASS_WHITE, minArea = (singlePuckArea * 0.38f).toInt())
        val blackBlobs = extractBlobs(classMap, roiW, roiH, CLASS_BLACK, minArea = (singlePuckArea * 0.38f).toInt())
        val queenBlobs = extractBlobs(classMap, roiW, roiH, CLASS_QUEEN, minArea = (singlePuckArea * 0.35f).toInt())
        val strikerBlobs = extractBlobs(classMap, roiW, roiH, CLASS_STRIKER, minArea = (singleStrikerArea * 0.28f).toInt())

        addFilteredPieces(whiteBlobs, PieceColor.WHITE, roiLeft, roiTop, sw, sh, expectedPuckRadiusPx, singlePuckArea, detectedPieces)
        addFilteredPieces(blackBlobs, PieceColor.BLACK, roiLeft, roiTop, sw, sh, expectedPuckRadiusPx, singlePuckArea, detectedPieces)
        addFilteredPieces(queenBlobs, PieceColor.QUEEN, roiLeft, roiTop, sw, sh, expectedPuckRadiusPx, singlePuckArea, detectedPieces)

        // Pick best striker candidate (prefer one resting near calibrated baseline)
        val strikerCandidates = mutableListOf<DetectedPiece>()
        addFilteredPieces(strikerBlobs, PieceColor.STRIKER, roiLeft, roiTop, sw, sh, expectedStrikerRadiusPx, singleStrikerArea, strikerCandidates)
        striker = strikerCandidates
            .minByOrNull { abs(it.normY - calibration.strikerBaselineY) }

        val whiteCount = detectedPieces.count { it.color == PieceColor.WHITE }
        val blackCount = detectedPieces.count { it.color == PieceColor.BLACK }
        val queenCount = detectedPieces.count { it.color == PieceColor.QUEEN }

        // 5. Inspect HUD above board for turn & target piece color
        val hudInfo = inspectTopHudTurn(pixels, sw, sh, calibration)

        // 6. Strict frame validity & unrealistic count checks
        val (validity, detail) = validateCountsAndTurn(
            whiteCount = whiteCount,
            blackCount = blackCount,
            queenCount = queenCount,
            striker = striker,
            calibration = calibration,
            hudInfo = hudInfo
        )

        return BoardDetectionResult(
            timestampMs = System.currentTimeMillis(),
            frameWidth = w,
            frameHeight = h,
            pieces = detectedPieces,
            striker = striker,
            whiteCount = whiteCount,
            blackCount = blackCount,
            queenCount = queenCount,
            detectedPlayerColor = hudInfo.playerTargetColor,
            isPlayerTurnDetected = hudInfo.isLeftPlayerTurn,
            validity = validity,
            validationDetail = detail,
            obstructionZones = emptyList(),
            boardBrightness = boardStats.avgLuminance
        )
    }

    /**
     * Scans a frame to estimate the square tan wooden Carrom board boundaries and slider bar.
     */
    fun autoDetectCalibration(bitmap: Bitmap, current: BoardCalibration): BoardCalibration {
        val w = bitmap.width
        val h = bitmap.height
        val sw = 240
        val sh = (h * (sw.toFloat() / w)).roundToInt().coerceAtLeast(100)
        val scaled = Bitmap.createScaledBitmap(bitmap, sw, sh, true)
        val pixels = IntArray(sw * sh)
        scaled.getPixels(pixels, 0, sw, 0, 0, sw, sh)
        scaled.recycle()

        var minX = sw
        var maxX = 0
        var minY = sh
        var maxY = 0
        var tanMatches = 0

        for (y in (sh * 0.15f).toInt() until (sh * 0.85f).toInt()) {
            var rowTan = 0
            for (x in 0 until sw) {
                val c = pixels[y * sw + x]
                if (isWarmBoardTan(Color.red(c), Color.green(c), Color.blue(c))) {
                    rowTan++
                }
            }
            if (rowTan > sw * 0.45f) {
                minY = min(minY, y)
                maxY = max(maxY, y)
                for (x in 0 until sw) {
                    val c = pixels[y * sw + x]
                    if (isWarmBoardTan(Color.red(c), Color.green(c), Color.blue(c))) {
                        minX = min(minX, x)
                        maxX = max(maxX, x)
                        tanMatches++
                    }
                }
            }
        }

        if (tanMatches < 200 || maxX - minX < sw * 0.4f || maxY - minY < sh * 0.2f) {
            return current
        }

        // Expand slightly from inner tan felt to wooden outer frame
        val bLeft = (minX.toFloat() / sw - 0.025f).coerceIn(0.01f, 0.25f)
        val bRight = (maxX.toFloat() / sw + 0.025f).coerceIn(0.75f, 0.99f)
        val bTop = (minY.toFloat() / sh - 0.025f).coerceIn(0.10f, 0.45f)
        val bBottom = (maxY.toFloat() / sh + 0.025f).coerceIn(0.55f, 0.88f)
        val bw = bRight - bLeft
        val bh = bBottom - bTop

        return current.copy(
            topLeft = NormalizedPoint(bLeft, bTop),
            topRight = NormalizedPoint(bRight, bTop),
            bottomLeft = NormalizedPoint(bLeft, bBottom),
            bottomRight = NormalizedPoint(bRight, bBottom),
            pocketTopLeft = NormalizedPoint(bLeft + bw * 0.065f, bTop + bh * 0.075f),
            pocketTopRight = NormalizedPoint(bRight - bw * 0.065f, bTop + bh * 0.075f),
            pocketBottomLeft = NormalizedPoint(bLeft + bw * 0.065f, bBottom - bh * 0.075f),
            pocketBottomRight = NormalizedPoint(bRight - bw * 0.065f, bBottom - bh * 0.075f),
            baselineLeftX = bLeft + bw * 0.21f,
            baselineRightX = bRight - bw * 0.21f,
            strikerBaselineY = bBottom - bh * 0.14f,
            sliderLeftX = bLeft + bw * 0.19f,
            sliderRightX = bRight - bw * 0.19f,
            sliderY = (bBottom + 0.065f).coerceAtMost(0.92f),
            screenWidthPx = w,
            screenHeightPx = h,
            updatedAtMs = System.currentTimeMillis()
        )
    }

    private fun sampleBoardSurface(
        pixels: IntArray,
        sw: Int,
        sh: Int,
        roiLeft: Int,
        roiTop: Int,
        roiRight: Int,
        roiBottom: Int
    ): BoardSurfaceStats {
        var tanCount = 0
        var totalSamples = 0
        var lumSum = 0f
        var tanLumSum = 0f

        val stepX = max(2, (roiRight - roiLeft) / 28)
        val stepY = max(2, (roiBottom - roiTop) / 28)

        for (y in roiTop until roiBottom step stepY) {
            for (x in roiLeft until roiRight step stepX) {
                val c = pixels[y * sw + x]
                val r = Color.red(c)
                val g = Color.green(c)
                val b = Color.blue(c)
                val lum = 0.299f * r + 0.587f * g + 0.114f * b
                lumSum += lum
                totalSamples++
                if (isWarmBoardTan(r, g, b)) {
                    tanCount++
                    tanLumSum += lum
                }
            }
        }

        val tanRatio = if (totalSamples > 0) tanCount.toFloat() / totalSamples else 0f
        val avgLum = if (tanCount > 10) tanLumSum / tanCount else (lumSum / max(1, totalSamples))
        return BoardSurfaceStats(
            looksLikeCarromBoard = tanRatio >= 0.34f,
            tanRatioPercent = (tanRatio * 100).roundToInt(),
            avgLuminance = avgLum
        )
    }

    private fun detectObstructions(
        pixels: IntArray,
        sw: Int,
        sh: Int,
        calibration: BoardCalibration,
        boardStats: BoardSurfaceStats
    ): List<String> {
        val alerts = mutableListOf<String>()

        // Check center horizontal banner band ("Please check the setting of microphone.", "Foul", "You Go First")
        val midTop = ((calibration.boardTop + calibration.boardHeight * 0.42f) * sh).roundToInt().coerceIn(0, sh - 1)
        val midBot = ((calibration.boardTop + calibration.boardHeight * 0.58f) * sh).roundToInt().coerceIn(midTop + 1, sh)
        val bandLeft = ((calibration.boardLeft + calibration.boardWidth * 0.12f) * sw).roundToInt().coerceIn(0, sw - 1)
        val bandRight = ((calibration.boardRight - calibration.boardWidth * 0.12f) * sw).roundToInt().coerceIn(bandLeft + 1, sw)

        var darkBannerPixels = 0
        var tealBannerPixels = 0
        var bandSamples = 0
        for (y in midTop until midBot step 2) {
            for (x in bandLeft until bandRight step 3) {
                val c = pixels[y * sw + x]
                val r = Color.red(c)
                val g = Color.green(c)
                val b = Color.blue(c)
                val lum = 0.299f * r + 0.587f * g + 0.114f * b
                bandSamples++
                // Dark translucent toast/banner or teal "Foul"/"You Go First" ribbon
                if (lum < boardStats.avgLuminance * 0.52f && abs(r - b) < 30) {
                    darkBannerPixels++
                } else if (g > r + 25 && b > r + 15 && g > 110) {
                    tealBannerPixels++
                }
            }
        }
        if (bandSamples > 0) {
            val darkRatio = darkBannerPixels.toFloat() / bandSamples
            val tealRatio = tealBannerPixels.toFloat() / bandSamples
            if (darkRatio > 0.36f) {
                alerts.add("Center banner/toast overlay (${(darkRatio * 100).toInt()}%)")
            }
            if (tealRatio > 0.30f) {
                alerts.add("Turn/Foul banner across board")
            }
        }

        // Check top-half modal overlay (emoji / gift drawer covering top half of board)
        val modalTop = ((calibration.boardTop + calibration.boardHeight * 0.04f) * sh).roundToInt().coerceIn(0, sh - 1)
        val modalBot = ((calibration.boardTop + calibration.boardHeight * 0.36f) * sh).roundToInt().coerceIn(modalTop + 1, sh)
        var nonBoardModalPixels = 0
        var modalSamples = 0
        for (y in modalTop until modalBot step 3) {
            for (x in bandLeft until bandRight step 3) {
                val c = pixels[y * sw + x]
                val r = Color.red(c)
                val g = Color.green(c)
                val b = Color.blue(c)
                modalSamples++
                // Bright mint/greenish popup card or heavy blue UI modal
                if ((g > r + 18 && g > 140) || (b > r + 25 && b > 140)) {
                    nonBoardModalPixels++
                }
            }
        }
        if (modalSamples > 0 && nonBoardModalPixels.toFloat() / modalSamples > 0.35f) {
            alerts.add("Popup drawer covering upper board")
        }

        return alerts
    }

    private fun extractBlobs(
        classMap: ByteArray,
        roiW: Int,
        roiH: Int,
        targetClass: Byte,
        minArea: Int
    ): List<Blob> {
        val visited = BooleanArray(classMap.size)
        val blobs = mutableListOf<Blob>()
        val queue = IntArray(classMap.size)

        for (i in classMap.indices) {
            if (classMap[i] != targetClass || visited[i]) continue
            var head = 0
            var tail = 0
            queue[tail++] = i
            visited[i] = true

            var sumX = 0L
            var sumY = 0L
            var count = 0
            var minX = roiW
            var maxX = 0
            var minY = roiH
            var maxY = 0

            while (head < tail) {
                val curr = queue[head++]
                val cx = curr % roiW
                val cy = curr / roiW
                sumX += cx
                sumY += cy
                count++
                if (cx < minX) minX = cx
                if (cx > maxX) maxX = cx
                if (cy < minY) minY = cy
                if (cy > maxY) maxY = cy

                // 4-neighbors
                if (cx > 0) {
                    val left = curr - 1
                    if (!visited[left] && classMap[left] == targetClass) {
                        visited[left] = true
                        queue[tail++] = left
                    }
                }
                if (cx + 1 < roiW) {
                    val right = curr + 1
                    if (!visited[right] && classMap[right] == targetClass) {
                        visited[right] = true
                        queue[tail++] = right
                    }
                }
                if (cy > 0) {
                    val up = curr - roiW
                    if (!visited[up] && classMap[up] == targetClass) {
                        visited[up] = true
                        queue[tail++] = up
                    }
                }
                if (cy + 1 < roiH) {
                    val down = curr + roiW
                    if (!visited[down] && classMap[down] == targetClass) {
                        visited[down] = true
                        queue[tail++] = down
                    }
                }
            }

            if (count >= minArea) {
                blobs.add(
                    Blob(
                        centerX = sumX.toFloat() / count,
                        centerY = sumY.toFloat() / count,
                        area = count,
                        minX = minX,
                        maxX = maxX,
                        minY = minY,
                        maxY = maxY
                    )
                )
            }
        }
        return blobs
    }

    private fun addFilteredPieces(
        blobs: List<Blob>,
        color: PieceColor,
        roiLeft: Int,
        roiTop: Int,
        sw: Int,
        sh: Int,
        expectedRadiusPx: Float,
        singleArea: Float,
        outList: MutableList<DetectedPiece>
    ) {
        var idx = 0
        for (blob in blobs) {
            val bw = (blob.maxX - blob.minX + 1).toFloat()
            val bh = (blob.maxY - blob.minY + 1).toFloat()
            val aspect = bw / bh.coerceAtLeast(1f)

            // If 2 touching pucks of the same color merged into one elongated blob, split along major axis
            if (color != PieceColor.STRIKER && blob.area > singleArea * 1.65f && blob.area < singleArea * 3.1f) {
                val splitOffset = expectedRadiusPx * 0.85f
                val dx = if (bw >= bh) splitOffset else 0f
                val dy = if (bh > bw) splitOffset else 0f
                listOf(-1f, 1f).forEach { sign ->
                    val nx = (roiLeft + blob.centerX + sign * dx) / sw.toFloat()
                    val ny = (roiTop + blob.centerY + sign * dy) / sh.toFloat()
                    outList.add(
                        DetectedPiece(
                            id = "${color.name.lowercase()}_${idx++}",
                            color = color,
                            normX = nx,
                            normY = ny,
                            radiusNorm = expectedRadiusPx / sw.toFloat(),
                            confidence = 0.78f
                        )
                    )
                }
                continue
            }

            // Reject excessively elongated lines/banners or huge non-puck regions
            if (aspect < 0.55f || aspect > 1.85f) continue
            if (blob.area > singleArea * 3.4f) continue

            val nx = (roiLeft + blob.centerX) / sw.toFloat()
            val ny = (roiTop + blob.centerY) / sh.toFloat()
            outList.add(
                DetectedPiece(
                    id = "${color.name.lowercase()}_${idx++}",
                    color = color,
                    normX = nx,
                    normY = ny,
                    radiusNorm = expectedRadiusPx / sw.toFloat(),
                    confidence = 0.92f
                )
            )
        }
    }

    private fun inspectTopHudTurn(
        pixels: IntArray,
        sw: Int,
        sh: Int,
        calibration: BoardCalibration
    ): HudTurnInfo {
        if (calibration.boardTop < 0.18f) {
            return HudTurnInfo(isLeftPlayerTurn = true, playerTargetColor = null)
        }

        // Check left player avatar glow ring (~x: 0.14..0.28, y: 0.13..0.21) vs right (~x: 0.72..0.86)
        val yStart = (calibration.boardTop * 0.52f * sh).roundToInt().coerceIn(0, sh - 1)
        val yEnd = (calibration.boardTop * 0.78f * sh).roundToInt().coerceIn(yStart + 1, sh)
        var leftGreenGlow = 0
        var rightGreenGlow = 0
        val hsv = FloatArray(3)

        for (y in yStart until yEnd step 2) {
            for (x in (sw * 0.12f).toInt() until (sw * 0.30f).toInt() step 2) {
                val c = pixels[y * sw + x]
                Color.RGBToHSV(Color.red(c), Color.green(c), Color.blue(c), hsv)
                if (hsv[0] in 75f..145f && hsv[1] > 0.55f && hsv[2] > 0.65f) leftGreenGlow++
            }
            for (x in (sw * 0.70f).toInt() until (sw * 0.88f).toInt() step 2) {
                val c = pixels[y * sw + x]
                Color.RGBToHSV(Color.red(c), Color.green(c), Color.blue(c), hsv)
                if (hsv[0] in 75f..145f && hsv[1] > 0.55f && hsv[2] > 0.65f) rightGreenGlow++
            }
        }

        // Check left player target color icon below avatar (~x: 0.15..0.21, y: 0.82..0.93 of boardTop)
        val iconYStart = (calibration.boardTop * 0.84f * sh).roundToInt().coerceIn(0, sh - 1)
        val iconYEnd = (calibration.boardTop * 0.94f * sh).roundToInt().coerceIn(iconYStart + 1, sh)
        var whiteIconPx = 0
        var blackIconPx = 0
        for (y in iconYStart until iconYEnd) {
            for (x in (sw * 0.14f).toInt() until (sw * 0.22f).toInt()) {
                val c = pixels[y * sw + x]
                val lum = 0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)
                val sat = max(Color.red(c), max(Color.green(c), Color.blue(c))) - min(Color.red(c), min(Color.green(c), Color.blue(c)))
                if (sat < 35 && lum > 205f) whiteIconPx++
                if (sat < 35 && lum < 55f) blackIconPx++
            }
        }

        val targetColor = when {
            blackIconPx > whiteIconPx + 8 && blackIconPx > 12 -> PieceColor.BLACK
            whiteIconPx > blackIconPx + 8 && whiteIconPx > 12 -> PieceColor.WHITE
            else -> null
        }

        // If neither glows brightly (e.g., synthetic test frame), treat as player turn
        val isPlayerTurn = if (leftGreenGlow < 8 && rightGreenGlow < 8) {
            true
        } else {
            leftGreenGlow >= rightGreenGlow
        }

        return HudTurnInfo(isLeftPlayerTurn = isPlayerTurn, playerTargetColor = targetColor)
    }

    private fun validateCountsAndTurn(
        whiteCount: Int,
        blackCount: Int,
        queenCount: Int,
        striker: DetectedPiece?,
        calibration: BoardCalibration,
        hudInfo: HudTurnInfo
    ): Pair<FrameValidity, String> {
        if (whiteCount > 9 || blackCount > 9 || queenCount > 1) {
            return FrameValidity.INVALID_UNREALISTIC_COUNT to
                "Unrealistic count detected (W=$whiteCount, B=$blackCount, Q=$queenCount)"
        }
        if (whiteCount + blackCount + queenCount == 0) {
            return FrameValidity.INVALID_UNREALISTIC_COUNT to
                "No target pucks detected inside calibrated board"
        }
        if (!hudInfo.isLeftPlayerTurn) {
            return FrameValidity.NOT_YOUR_TURN to "Opponent turn timer active — holding fire"
        }
        if (striker == null) {
            return FrameValidity.INVALID_NO_STRIKER to "Striker not detected on baseline"
        }
        if (abs(striker.normY - calibration.strikerBaselineY) > calibration.boardHeight * 0.18f) {
            return FrameValidity.INVALID_MOVING to "Striker is away from baseline (still moving)"
        }
        return FrameValidity.VALID to
            "Valid board: $whiteCount white, $blackCount black, $queenCount queen + striker ready"
    }

    private fun isWarmBoardTan(r: Int, g: Int, b: Int): Boolean {
        return r in 150..245 && g in 115..215 && b in 75..175 && r >= g - 5 && g >= b + 10
    }

    private data class BoardSurfaceStats(
        val looksLikeCarromBoard: Boolean,
        val tanRatioPercent: Int,
        val avgLuminance: Float
    )

    private data class HudTurnInfo(
        val isLeftPlayerTurn: Boolean,
        val playerTargetColor: PieceColor?
    )

    private data class Blob(
        val centerX: Float,
        val centerY: Float,
        val area: Int,
        val minX: Int,
        val maxX: Int,
        val minY: Int,
        val maxY: Int
    )

    companion object {
        private const val CLASS_WHITE: Byte = 1
        private const val CLASS_BLACK: Byte = 2
        private const val CLASS_QUEEN: Byte = 3
        private const val CLASS_STRIKER: Byte = 4
    }
}
