package ai.genwhy.nobonk.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import ai.genwhy.nobonk.ui.theme.NB
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import ai.genwhy.nobonk.viewmodel.AccuracyMode
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Production controls: shared scope, explicit Settings, history navigation and reachable Stop. */
class ScanControlsRegressionTest {
    @get:Rule val compose = createComposeRule()
    private var starts = 0
    private var stops = 0
    private var history = 0
    private var walkingSetup = 0
    private var backgroundStarts = 0
    private var permissionRequests = 0
    private val overlayAllowed = mutableStateOf(true)
    private val everything = mutableStateOf(false)
    private val scanning = mutableStateOf(false)

    private fun show(fontScale: Float? = null, active: Boolean = false) {
        scanning.value = active
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale ?: density.fontScale)) {
                MaterialTheme {
                    Column(Modifier.width(320.dp).fillMaxHeight().background(NB.Night).systemBarsPadding()) {
                        TopStatusBar(Modifier, 80, "XNNPACK", AccuracyMode.entries.first(), active,
                            everything.value, { everything.value = it })
                        Spacer(Modifier.weight(1f))
                        ControlDock(Modifier, emptyList(), 1f, {}, { backgroundStarts++ },
                            { stops++; scanning.value = false }, scanning.value, { starts++ }, overlayAllowed.value, { permissionRequests++ },
                            everything.value, { everything.value = it }, AccuracyMode.entries.first(), {},
                            false, {}, false, {}, false, {}, onShowHistory = { history++ }, onWalkingSetup = { walkingSetup++ })
                    }
                }
            }
        }
    }

    private fun screenshot(name: String) {
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        // Test-only shell output survives Gradle uninstalling the instrumented app.
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.executeShellCommand("screencap -p /data/local/tmp/nobonk-ui-$name.png").use {
            java.io.FileInputStream(it.fileDescriptor).readBytes()
        }
    }

    @Test fun bothScopeControlsAgreeWithoutStartingAndHistoryLivesInSettings() {
        show()
        compose.onNodeWithText("Detection history").assertDoesNotExist()
        compose.onNodeWithText("Start scanning").assertIsDisplayed()
        screenshot("stopped-320")
        compose.onNodeWithContentDescription("Detection mode: People. Change detection mode").performClick()
        compose.onNodeWithText("People only").assertIsSelected()
        compose.onNodeWithText("Everything").performClick()
        compose.onNodeWithContentDescription("Detection mode: Everything. Change detection mode").assertIsDisplayed()
        compose.onNodeWithText("Settings").performClick()
        compose.onNode(hasText("Everything") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)).assertIsSelected()
        compose.onNode(hasText("People") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)).performScrollTo().assertIsDisplayed().performClick()
        compose.onNode(hasText("People") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)).assertIsSelected()
        compose.onNodeWithText("Detection history").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Done").assertIsDisplayed()
        screenshot("settings-history")
        compose.onNodeWithText("Detection history").performClick()
        compose.onNodeWithText("Detection history").assertDoesNotExist()
        compose.onNodeWithContentDescription("Detection mode: People. Change detection mode").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, history); assertEquals(0, starts); assertEquals(0, stops) }
    }

    @Test fun largeTextSettingsKeepsStopReachableAfterScrolling() {
        show(fontScale = 2f, active = true)
        compose.onNodeWithText("Settings").assertIsDisplayed().performClick()
        compose.onNodeWithText("Detection history").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Done").assertIsDisplayed()
        // Only the modal's Stop is reachable: the dock is behind the sheet's scrim.
        compose.onNodeWithTag("settings-stop").assertIsDisplayed()
        screenshot("settings-large-text")
        compose.onNodeWithTag("settings-stop").performClick()
        compose.onNodeWithText("Start scanning").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, stops); assertEquals(0, starts) }
    }

    @Test fun walkingSetupIsVisibleWithoutOpeningSettingsAndDoesNotStartCamera() {
        show()
        compose.onNodeWithText("Walking reminder · optional").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, walkingSetup); assertEquals(0, starts); assertEquals(0, stops) }
    }

    @Test fun largeTextKeepsActionsVisibleWhenSensitivityScrolls() {
        show(fontScale = 2f)
        compose.onNodeWithText("Start scanning").assertIsDisplayed()
        compose.onNodeWithText("Settings").assertIsDisplayed()
        compose.onNodeWithText("Walking reminder · optional").assertIsDisplayed()
        compose.onNodeWithText("3.5 m").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Start scanning").assertIsDisplayed()
        compose.onNodeWithText("Settings").assertIsDisplayed()
        compose.onNodeWithText("Walking reminder · optional").assertIsDisplayed()
        screenshot("walking-large-text")
    }

    @Test fun compactStartRemainsAFullTouchTargetAndOnlyStartsOnTap() {
        show(fontScale = 1f)
        val start = compose.onNodeWithText("Start scanning").assertIsDisplayed()
        start.assertHeightIsAtLeast(48.dp)
        val width = start.fetchSemanticsNode().boundsInRoot.width
        val rootWidth = compose.onRoot().fetchSemanticsNode().boundsInRoot.width
        assertTrue("Start should no longer fill the dock", width < rootWidth * .75f)
        start.performClick()
        compose.runOnIdle { assertEquals(1, starts); assertEquals(0, stops) }
    }
    @Test fun feedbackStopsScanningAndOpensReviewableDraftWithoutSending() {
        show(fontScale = 2f, active = true)
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Send feedback").performScrollTo().performClick()
        compose.onNodeWithText("Help improve NoBonk").assertIsDisplayed()
        compose.onNodeWithText("Open email draft").assertIsNotEnabled()
        compose.onNodeWithText("What happened?").performScrollTo().performTextInput("Too many alerts while standing still.")
        compose.onNodeWithText("Open email draft").assertIsEnabled()
        compose.onNodeWithText("Copy feedback").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Close", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Start scanning").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, stops); assertEquals(0, starts) }
    }

    @Test fun backgroundHelpExplainsSinglePermissionAndCameraDirection() {
        show()
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Camera angle & background help").performScrollTo().performClick()
        compose.onNodeWithText("Use NoBonk with other apps").assertIsDisplayed()
        compose.onNodeWithText("1. Stand still", substring = true).assertExists()
        compose.onNodeWithText("3. Allow display over other apps for NoBonk only", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Got it").performClick()
        compose.onNodeWithText("Start scanning").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, starts) }
    }

    @Test fun overlayPermissionExplanationNeverStartsBackgroundUntilExplicitTap() {
        overlayAllowed.value = false
        show(active = true)
        compose.onNodeWithText("Set up background").performClick()
        compose.onNodeWithText("Use NoBonk with other apps").assertIsDisplayed()
        compose.onNodeWithText("Open NoBonk permission").performClick()
        compose.runOnIdle { assertEquals(1, permissionRequests); assertEquals(0, backgroundStarts) }
        // Returning without permission keeps setup available and does not start a service.
        compose.onNodeWithText("Set up background").assertIsDisplayed().performClick()
        compose.onNodeWithText("Open NoBonk permission").performClick()
        compose.runOnIdle { overlayAllowed.value = true }
        // Granting permission still requires the user's explicit background action.
        compose.onNodeWithText("Run in background").assertIsDisplayed()
        compose.runOnIdle { assertEquals(2, permissionRequests); assertEquals(0, backgroundStarts) }
        compose.onNodeWithText("Run in background").performClick()
        compose.runOnIdle { assertEquals(1, backgroundStarts); assertEquals(0, starts) }
    }

}
