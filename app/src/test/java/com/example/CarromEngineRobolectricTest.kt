package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.TrainingDataRepository
import com.example.engine.BeforeAfterEvaluator
import com.example.engine.ShotLearner
import com.example.engine.ShotPlannerEngine
import com.example.model.AutomationTuning
import com.example.model.BoardCalibration
import com.example.model.FrameValidity
import com.example.model.PieceColor
import com.example.model.ShotOutcomeCategory
import com.example.model.ShotOutcomeDetail
import com.example.model.TurnTargetMode
import com.example.vision.BoardVisionAnalyzer
import com.example.vision.TestBoardRenderer
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CarromEngineRobolectricTest {

    private val analyzer = BoardVisionAnalyzer()
    private val planner = ShotPlannerEngine(ShotLearner())
    private val evaluator = BeforeAfterEvaluator()
    private val calibration = BoardCalibration()

    @Test
    fun `vision detects white black queen striker on open board`() {
        val bmp = TestBoardRenderer.renderScenarioBitmap(
            TestBoardRenderer.TestScenario.OPEN_BOARD,
            calibration
        )
        val result = analyzer.analyzeFrame(bmp, calibration, AutomationTuning())
        assertEquals(FrameValidity.VALID, result.validity)
        assertEquals("Pieces=${result.pieces}", 3, result.whiteCount)
        assertEquals("Pieces=${result.pieces}", 3, result.blackCount)
        assertEquals("Pieces=${result.pieces}", 1, result.queenCount)
        assertNotNull(result.striker)
    }

    @Test
    fun `vision rejects popup obstruction and invalid lobby screens`() {
        val popupBmp = TestBoardRenderer.renderScenarioBitmap(
            TestBoardRenderer.TestScenario.POPUP_OBSTRUCTION,
            calibration
        )
        val popupResult = analyzer.analyzeFrame(popupBmp, calibration, AutomationTuning())
        assertEquals(FrameValidity.INVALID_OBSTRUCTION, popupResult.validity)

        val lobbyBmp = TestBoardRenderer.renderScenarioBitmap(
            TestBoardRenderer.TestScenario.UNREALISTIC_NO_BOARD,
            calibration
        )
        val lobbyResult = analyzer.analyzeFrame(lobbyBmp, calibration, AutomationTuning())
        assertEquals(FrameValidity.INVALID_NO_BOARD, lobbyResult.validity)
    }

    @Test
    fun `planner enforces queen before last puck rule`() {
        val bmp = TestBoardRenderer.renderScenarioBitmap(
            TestBoardRenderer.TestScenario.QUEEN_BEFORE_LAST,
            calibration
        )
        val detection = analyzer.analyzeFrame(bmp, calibration, AutomationTuning())
        val plan = planner.planBestShot(
            detection = detection,
            calibration = calibration,
            tuning = AutomationTuning(turnTargetMode = TurnTargetMode.BLACK),
            history = emptyList()
        )
        assertNotNull(plan)
        assertEquals(PieceColor.QUEEN, plan!!.targetPiece.color)
    }

    @Test
    fun `before after evaluator catches clean pocket and zero movement error`() {
        val openBmp = TestBoardRenderer.renderScenarioBitmap(
            TestBoardRenderer.TestScenario.OPEN_BOARD,
            calibration
        )
        val before = analyzer.analyzeFrame(openBmp, calibration, AutomationTuning())
        val plan = planner.planBestShot(before, calibration, AutomationTuning(), emptyList())!!

        val pocketBmp = TestBoardRenderer.renderScenarioBitmap(
            TestBoardRenderer.TestScenario.AFTER_CLEAN_POCKET,
            calibration
        )
        val afterPocket = analyzer.analyzeFrame(pocketBmp, calibration, AutomationTuning())
        val pocketCmp = evaluator.compare(plan, before, afterPocket)
        assertEquals(ShotOutcomeDetail.POCKETED_CLEAN, pocketCmp.outcomeDetail)

        val noMoveBmp = TestBoardRenderer.renderScenarioBitmap(
            TestBoardRenderer.TestScenario.AFTER_NO_MOVEMENT,
            calibration
        )
        val afterNoMove = analyzer.analyzeFrame(noMoveBmp, calibration, AutomationTuning())
        val noMoveCmp = evaluator.compare(plan, before, afterNoMove)
        assertEquals(ShotOutcomeDetail.NO_MOVEMENT_ERROR, noMoveCmp.outcomeDetail)
    }

    @Test
    fun `repository merges presets and preserves user shots across updates`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = TrainingDataRepository(context)

        val firstMerge = repo.initializeAndMergeOnStartup()
        assertTrue(firstMerge.totalRecords >= 5)

        val openBmp = TestBoardRenderer.renderScenarioBitmap(TestBoardRenderer.TestScenario.OPEN_BOARD, calibration)
        val before = analyzer.analyzeFrame(openBmp, calibration, AutomationTuning())
        val plan = planner.planBestShot(before, calibration, AutomationTuning(), emptyList())!!
        val after = analyzer.analyzeFrame(openBmp, calibration, AutomationTuning())
        val cmp = evaluator.compare(plan, before, after)

        repo.recordComparisonShot(cmp, "Test Profile")
        val secondMerge = repo.initializeAndMergeOnStartup()
        assertTrue(secondMerge.totalRecords >= firstMerge.totalRecords + 1)

        val json = repo.formatShotsAsCleanJson(repo.getAllShotsSnapshot(), ShotOutcomeCategory.FAILED)
        assertTrue(json.contains("NO_MOVEMENT_ERROR"))
    }
}
