package com.example

import android.app.Application
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.example.assets.BundledAsphaltMaps
import com.example.assets.StarterAssetContract
import com.example.assets.StarterAssetRepository
import com.example.render.CoordinateMapper
import com.example.render.ProceduralRoadGlbBuilder
import com.example.sim.DrivingInputSnapshot
import com.example.sim.DrivingSimulation
import com.example.state.DripRaceViewModel
import com.example.state.GamePhase
import com.example.ui.DripRaceRootScreen
import com.example.ui.theme.DripRaceTheme
import kotlin.math.PI
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DripRacePhase2Test {

  @get:Rule val composeTestRule = createComposeRule()

  private fun createTestViewModel(): Pair<DripRaceViewModel, DripRacePhase1Test.FakeRendererPort> {
    val app = ApplicationProvider.getApplicationContext<Application>()
    val vm = DripRaceViewModel(app, SavedStateHandle())
    val fakeRenderer = DripRacePhase1Test.FakeRendererPort()
    val fakeRepo =
      object : StarterAssetRepository(app) {
        override fun loadBundledStarter() =
          com.example.assets.StarterLoadResult.Ready(
            glbBytes = ProceduralRoadGlbBuilder.buildRoadGlb(),
            sha256Hex = StarterAssetContract.EXPECTED_STARTER_SHA256,
            sourceLabel = "test-bundled",
            isBundledOfflineAsset = true,
            asphaltMaps = BundledAsphaltMaps(null, null, null, null, null, null),
            hdrAssetPath = null,
            prepareVehicleMjsPath = null,
          )
      }
    vm.overrideDependenciesForTest(fakeRepo, fakeRenderer)
    vm.onActivityStartedOrResumed(active = true)
    return vm to fakeRenderer
  }

  @Test
  fun `starter acceleration, speedKmh conversion, distance integration, and wheel roll match contract units`() {
    val wheelRadius = 0.34
    val sim = DrivingSimulation(wheelRadiusMeters = wheelRadius)

    // 10.0 seconds of auto-cruise acceleration (5.5 m/s^2 up to 180 km/h = 50.0 m/s)
    var expectedDistance = 0.0
    var expectedSpeed = 0.0
    val dt = DrivingSimulation.FIXED_DT_SECONDS
    repeat(600) {
      sim.stepFixedTick(DrivingInputSnapshot(), dt)
      expectedSpeed = (expectedSpeed + 5.5 * dt).coerceAtMost(50.0)
      expectedDistance += expectedSpeed * dt
    }

    assertEquals(600L, sim.state.fixedStepsExecuted)
    assertEquals(50.0, sim.state.speedMetersPerSecond, 1e-6)
    assertEquals(180.0, sim.state.speedKmh, 1e-5)
    assertEquals(sim.state.speedMetersPerSecond * 3.6, sim.state.speedKmh, 1e-9)
    assertEquals(expectedDistance, sim.state.distanceMeters, 1e-6)

    // Distance-driven wheel roll matches distance / wheelRadius (mod 2*PI)
    val expectedRollMod = (expectedDistance / wheelRadius) % (2.0 * PI)
    assertEquals(expectedRollMod, sim.state.wheelRollRadians, 1e-6)
  }

  @Test
  fun `steering interpolates smoothly over 0_48s, stays bounded in -3_6 to +3_6, and never teleports`() {
    val sim = DrivingSimulation()
    assertEquals(1, sim.state.currentLaneIndex)
    assertEquals(0f, sim.state.carX, 1e-6f)

    // Spam 3 left taps immediately; must queue at most 1 extra lane and stay >= -3.6 m
    sim.requestLaneChange(-1)
    sim.requestLaneChange(-1)
    sim.requestLaneChange(-1)

    // Step once (16.67 ms): car must move only a tiny smooth increment, NOT teleport to -3.6
    sim.stepFixedTick(DrivingInputSnapshot())
    assertTrue(sim.state.isChangingLane)
    assertTrue(abs(sim.state.carX) < 0.05f)

    var prevX = sim.state.carX
    // 0.48s = 28.8 fixed ticks -> after 29 ticks reaches lane 0 (X = -3.6)
    repeat(28) {
      sim.stepFixedTick(DrivingInputSnapshot())
      assertTrue(sim.state.carX <= prevX + 1e-6f)
      assertTrue(sim.state.carX >= -3.6f - 1e-5f)
      prevX = sim.state.carX
    }
    assertEquals(0, sim.state.currentLaneIndex)
    assertEquals(-3.6f, sim.state.carX, 1e-4f)
    assertFalse(sim.state.isChangingLane)

    // Now test queued single step across 2 -> 1 -> 0
    sim.resetForNewRace()
    sim.requestLaneChange(+1)
    repeat(30) { sim.stepFixedTick(DrivingInputSnapshot()) }
    assertEquals(2, sim.state.currentLaneIndex)
    assertEquals(3.6f, sim.state.carX, 1e-4f)

    // Queue 2 steps left while moving right->center
    sim.requestLaneChange(-1)
    sim.requestLaneChange(-1)
    repeat(60) {
      sim.stepFixedTick(DrivingInputSnapshot())
      assertTrue(sim.state.carX in -3.6001f..3.6001f)
    }
    assertEquals(0, sim.state.currentLaneIndex)
    assertEquals(-3.6f, sim.state.carX, 1e-4f)

    // Extra left taps at lane 0 cannot leave left lane boundary
    sim.requestLaneChange(-1)
    repeat(30) { sim.stepFixedTick(DrivingInputSnapshot()) }
    assertEquals(0, sim.state.currentLaneIndex)
    assertEquals(-3.6f, sim.state.carX, 1e-4f)
  }

  @Test
  fun `manual Nitro boosts to 225 km_h, drains 28 per s, regenerates 12 per s, and Brake overrides Nitro`() {
    val sim = DrivingSimulation()
    assertEquals(100.0, sim.state.nitroMeter, 1e-9)

    // Boost for 3.0s (drains 28 * 3 = 84 -> ~16 left) and keep accelerating further up toward 225 km/h
    repeat(180) { sim.stepFixedTick(DrivingInputSnapshot(nitroHeld = true)) }
    assertTrue(sim.state.isNitroActive)
    assertEquals(16.0, sim.state.nitroMeter, 1e-4)

    // Continue holding Nitro 35 more ticks (35 * 28/60 = 16.33 >= 16.0) -> clamps cleanly to 0.0
    repeat(35) { sim.stepFixedTick(DrivingInputSnapshot(nitroHeld = true)) }
    assertEquals(0.0, sim.state.nitroMeter, 1e-9)
    assertFalse(sim.state.isNitroActive)

    // Still holding Nitro for 25 ticks while locked out below 8.0 -> regens +5.0 without micro-flickering
    repeat(25) { sim.stepFixedTick(DrivingInputSnapshot(nitroHeld = true)) }
    assertFalse(sim.state.isNitroActive)
    assertEquals(5.0, sim.state.nitroMeter, 1e-4)

    // Release Nitro while driving for 2.0s -> regenerates at 12 units/s (+24.0 -> 29.0)
    repeat(120) { sim.stepFixedTick(DrivingInputSnapshot(nitroHeld = false)) }
    assertEquals(29.0, sim.state.nitroMeter, 1e-4)

    // Full charge check: reach 225 km/h (62.5 m/s) under Nitro
    repeat(600) { sim.stepFixedTick(DrivingInputSnapshot(nitroHeld = false)) }
    assertEquals(100.0, sim.state.nitroMeter, 1e-6)
    repeat(180) { sim.stepFixedTick(DrivingInputSnapshot(nitroHeld = true)) }
    assertTrue(sim.state.speedKmh > 205.0)
    assertTrue(sim.state.speedKmh <= 225.0 + 1e-6)

    // Hold BOTH Brake and Nitro simultaneously -> Brake MUST override Nitro
    val speedBeforeBrake = sim.state.speedMetersPerSecond
    val nitroBeforeBrake = sim.state.nitroMeter
    repeat(60) { sim.stepFixedTick(DrivingInputSnapshot(brakeHeld = true, nitroHeld = true)) }
    assertTrue(sim.state.isBraking)
    assertFalse(sim.state.isNitroActive)
    assertEquals(speedBeforeBrake - 12.0, sim.state.speedMetersPerSecond, 1e-4)
    assertTrue(sim.state.nitroMeter >= nitroBeforeBrake) // Did not drain Nitro while Braking
  }

  @Test
  fun `deterministic 30 FPS vs 60 FPS render schedules produce identical fixed-step simulation results`() {
    val sim60 = DrivingSimulation()
    val sim30 = DrivingSimulation()

    fun driveSchedule(sim: DrivingSimulation, framesPerSecond: Int) {
      val renderDt = 1.0 / framesPerSecond.toDouble()
      val totalFrames = 12 * framesPerSecond
      for (frame in 0 until totalFrames) {
        val elapsedSec = frame * renderDt
        // Scripted identical input events aligned cleanly on 0.5s boundaries
        if (abs(elapsedSec - 2.0) < 1e-6) sim.requestLaneChange(-1)
        if (abs(elapsedSec - 5.0) < 1e-6) sim.requestLaneChange(+1)
        if (abs(elapsedSec - 5.5) < 1e-6) sim.requestLaneChange(+1)

        val input =
          DrivingInputSnapshot(
            nitroHeld = elapsedSec in 3.0..<5.0,
            brakeHeld = elapsedSec in 8.0..<9.5,
          )
        sim.advanceRenderFrame(renderDt, input)
      }
    }

    driveSchedule(sim60, 60)
    driveSchedule(sim30, 30)

    assertEquals(720L, sim60.state.fixedStepsExecuted)
    assertEquals(sim60.state.fixedStepsExecuted, sim30.state.fixedStepsExecuted)
    assertEquals(sim60.state.speedMetersPerSecond, sim30.state.speedMetersPerSecond, 1e-9)
    assertEquals(sim60.state.distanceMeters, sim30.state.distanceMeters, 1e-9)
    assertEquals(sim60.state.nitroMeter, sim30.state.nitroMeter, 1e-9)
    assertEquals(sim60.state.carX, sim30.state.carX, 1e-6f)
    assertEquals(sim60.state.currentLaneIndex, sim30.state.currentLaneIndex)
    assertEquals(sim60.state.wheelRollRadians, sim30.state.wheelRollRadians, 1e-9)
  }

  @Test
  fun `pause freezes all mechanics, releases held inputs, and Restart or Home cleanly resets race`() {
    val (vm, fakeRenderer) = createTestViewModel()
    vm.onPlayClicked()

    // Drive for 2 seconds with Nitro held and Steer Left
    vm.onNitroPointerDown(7L)
    vm.onSteerLeft()
    repeat(120) { fakeRenderer.simulateRenderFrame(1.0 / 60.0) }

    val distanceAtPause = vm.simulation.state.distanceMeters
    val speedAtPause = vm.simulation.state.speedMetersPerSecond
    val nitroAtPause = vm.simulation.state.nitroMeter
    val stepsAtPause = vm.simulation.state.fixedStepsExecuted
    assertTrue(distanceAtPause > 10.0)
    assertTrue(nitroAtPause < 100.0)

    // Pause freezes simulation and releases held pointers
    vm.onPauseClicked()
    assertTrue(vm.uiState.value.phase is GamePhase.Paused)
    assertEquals(0, vm.hudTelemetry.value.activeHoldPointerCount)
    assertFalse(fakeRenderer.lastInput.nitroHeld)
    assertFalse(fakeRenderer.simRunning)

    // Render ticks while paused must do zero work
    repeat(120) { fakeRenderer.simulateRenderFrame(1.0 / 60.0) }
    assertEquals(stepsAtPause, vm.simulation.state.fixedStepsExecuted)
    assertEquals(distanceAtPause, vm.simulation.state.distanceMeters, 1e-9)
    assertEquals(speedAtPause, vm.simulation.state.speedMetersPerSecond, 1e-9)
    assertEquals(nitroAtPause, vm.simulation.state.nitroMeter, 1e-9)

    // Restart from Paused resets speed/distance/nitro/lane and enters Running cleanly
    vm.onRestartClicked()
    assertTrue(vm.uiState.value.phase is GamePhase.Running)
    assertEquals(0L, vm.simulation.state.fixedStepsExecuted)
    assertEquals(0.0, vm.simulation.state.distanceMeters, 1e-9)
    assertEquals(0.0, vm.simulation.state.speedMetersPerSecond, 1e-9)
    assertEquals(100.0, vm.simulation.state.nitroMeter, 1e-9)
    assertEquals(1, vm.simulation.state.currentLaneIndex)

    // Advance 1s then Pause -> Home resets cleanly and retains 1 model load
    repeat(60) { fakeRenderer.simulateRenderFrame(1.0 / 60.0) }
    assertTrue(vm.simulation.state.distanceMeters > 0.0)
    vm.onPauseClicked()
    vm.onHomeClicked()
    assertTrue(vm.uiState.value.phase is GamePhase.Home)
    assertEquals(0.0, vm.simulation.state.distanceMeters, 1e-9)
    assertEquals(0.0, vm.simulation.state.speedMetersPerSecond, 1e-9)
    assertEquals(100.0, vm.simulation.state.nitroMeter, 1e-9)
    assertEquals(1, fakeRenderer.loadCount)
  }

  @Test
  fun `multi-touch independent pointers and Phase 2 HUD controls update Compose UI cleanly`() {
    val (vm, fakeRenderer) = createTestViewModel()

    composeTestRule.setContent {
      val uiState by vm.uiState.collectAsState()
      val telemetry by vm.hudTelemetry.collectAsState()
      DripRaceTheme {
        DripRaceRootScreen(
          uiState = uiState,
          telemetry = telemetry,
          onPlay = vm::onPlayClicked,
          onPause = vm::onPauseClicked,
          onResume = vm::onResumeClicked,
          onRestart = vm::onRestartClicked,
          onHome = vm::onHomeClicked,
          onRetry = vm::onRetryClicked,
          onPickGlbPreview = {},
          onBackFromRunning = { vm.onBackPressed() },
          onSteerLeft = vm::onSteerLeft,
          onSteerRight = vm::onSteerRight,
          onBrakePointerDown = vm::onBrakePointerDown,
          onBrakePointerUpOrCancel = vm::onBrakePointerUpOrCancel,
          onNitroPointerDown = vm::onNitroPointerDown,
          onNitroPointerUpOrCancel = vm::onNitroPointerUpOrCancel,
          onBrakeKeyChanged = vm::onBrakeKeyChanged,
          onNitroKeyChanged = vm::onNitroKeyChanged,
          onAttachSurface = {},
          onDetachSurface = {},
          render3DSurface = false,
        )
      }
    }

    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("play_button").performClick()
    composeTestRule.waitForIdle()

    // Running controls & honest HUD tags displayed
    composeTestRule.onNodeWithTag("hud_speed_text").assertIsDisplayed()
    composeTestRule.onNodeWithTag("hud_distance_text").assertIsDisplayed()
    composeTestRule.onNodeWithTag("hud_nitro_text").assertIsDisplayed()
    composeTestRule.onNodeWithTag("steer_left_button").assertIsDisplayed()
    composeTestRule.onNodeWithTag("steer_right_button").assertIsDisplayed()
    composeTestRule.onNodeWithTag("brake_button").assertIsDisplayed()
    composeTestRule.onNodeWithTag("nitro_button").assertIsDisplayed()

    // Simultaneous independent pointers: hold Nitro (#10), tap Steer Right, hold Brake (#20), release #20
    vm.onNitroPointerDown(10L)
    composeTestRule.onNodeWithTag("steer_right_button").performClick()
    vm.onBrakePointerDown(20L)
    assertEquals(2, vm.hudTelemetry.value.activeHoldPointerCount)
    assertTrue(fakeRenderer.lastInput.nitroHeld)
    assertTrue(fakeRenderer.lastInput.brakeHeld)

    // Release Brake (#20) -> Nitro (#10) remains active
    vm.onBrakePointerUpOrCancel(20L)
    assertEquals(1, vm.hudTelemetry.value.activeHoldPointerCount)
    assertFalse(fakeRenderer.lastInput.brakeHeld)
    assertTrue(fakeRenderer.lastInput.nitroHeld)

    // Step 1.5s of fixed simulation while turning right with Nitro
    repeat(90) { fakeRenderer.simulateRenderFrame(1.0 / 60.0) }
    composeTestRule.waitForIdle()

    val speedTextExpected = "${vm.hudTelemetry.value.speedKmh.toInt()} km/h"
    composeTestRule.onNodeWithTag("hud_speed_text").assertTextContains(speedTextExpected)
    composeTestRule.onNodeWithTag("hud_lane_text").assertTextContains("R")

    // Pause -> verify frozen snapshot & Restart button
    composeTestRule.onNodeWithTag("pause_button").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("paused_snapshot_text").assertIsDisplayed()
    composeTestRule.onNodeWithTag("restart_button").assertIsDisplayed().performClick()
    composeTestRule.waitForIdle()
    assertTrue(vm.uiState.value.phase is GamePhase.Running)
    assertEquals(0, vm.hudTelemetry.value.distanceMeters.toInt())
    assertEquals(100, vm.hudTelemetry.value.nitroMeter.toInt())
  }

  @Test
  fun `transient window focus loss on Play immersive transition clears held inputs without auto-pausing`() {
    val (vm, fakeRenderer) = createTestViewModel()

    // Tap Play -> Running (entering immersive mode may trigger transient onWindowFocusChanged(false))
    vm.onPlayClicked()
    assertTrue(vm.uiState.value.phase is GamePhase.Running)
    assertTrue(fakeRenderer.simRunning)
    assertTrue(fakeRenderer.activeStateFlag)

    // Hold both Nitro and Brake, then simulate transient window focus loss
    vm.onNitroPointerDown(1L)
    vm.onBrakePointerDown(2L)
    assertEquals(2, vm.hudTelemetry.value.activeHoldPointerCount)
    assertTrue(fakeRenderer.lastInput.nitroHeld)
    assertTrue(fakeRenderer.lastInput.brakeHeld)

    vm.onWindowFocusChanged(false)

    // Held inputs must be released, but phase MUST stay Running and rendering/sim must stay active
    assertTrue(vm.uiState.value.phase is GamePhase.Running)
    assertTrue(fakeRenderer.simRunning)
    assertTrue(fakeRenderer.activeStateFlag)
    assertEquals(0, vm.hudTelemetry.value.activeHoldPointerCount)
    assertFalse(fakeRenderer.lastInput.nitroHeld)
    assertFalse(fakeRenderer.lastInput.brakeHeld)

    // Regaining focus keeps Running cleanly and advances simulation
    vm.onWindowFocusChanged(true)
    repeat(30) { fakeRenderer.simulateRenderFrame(1.0 / 60.0) }
    assertTrue(vm.uiState.value.phase is GamePhase.Running)
    assertTrue(vm.simulation.state.distanceMeters > 0.0)

    // Actual backgrounding (Activity.onPause -> onActivityStartedOrResumed(false)) MUST pause Running
    vm.onNitroPointerDown(3L)
    vm.onActivityStartedOrResumed(false)
    assertTrue(vm.uiState.value.phase is GamePhase.Paused)
    assertFalse(fakeRenderer.simRunning)
    assertFalse(fakeRenderer.activeStateFlag)
    assertEquals(0, vm.hudTelemetry.value.activeHoldPointerCount)
    assertFalse(fakeRenderer.lastInput.nitroHeld)

    // Returning from background must stay Paused until deliberate Resume
    vm.onActivityStartedOrResumed(true)
    assertTrue(vm.uiState.value.phase is GamePhase.Paused)
    assertTrue(fakeRenderer.activeStateFlag)
    assertFalse(fakeRenderer.simRunning)
  }

  @Test
  fun `left and right steer map to actual screen-left and screen-right in right-handed -Z camera view`() {
    val sim = DrivingSimulation()

    // Forward looking along +Z with up (0, 1, 0): screen-right = forward x up = (-1, 0, 0)
    val camForwardX = 0.0
    val camForwardY = 0.0
    val camForwardZ = 1.0
    val upX = 0.0
    val upY = 1.0
    val upZ = 0.0
    val screenRightX = camForwardY * upZ - camForwardZ * upY // = -1.0

    // Steer Left -> logical lane 0 (-3.6) -> renderer X (+3.6) -> dot(screenRight) < 0 => SCREEN LEFT
    sim.requestLaneChange(-1)
    repeat(30) { sim.stepFixedTick(DrivingInputSnapshot()) }
    assertEquals(0, sim.state.currentLaneIndex)
    assertEquals(-3.6f, sim.state.carX, 1e-4f)
    val leftRendererX = CoordinateMapper.gameToRendererX(sim.state.carX)
    assertEquals(3.6f, leftRendererX, 1e-4f)
    assertTrue("Left turn must project left of screen center", leftRendererX * screenRightX < 0.0)
    val leftCam =
      CoordinateMapper.computeChaseCameraPose(
        widthPx = 1080,
        heightPx = 2400,
        cameraFollowX = sim.state.cameraFollowX,
        carX = sim.state.carX,
      )
    assertTrue(leftCam.eyeX * screenRightX < 0.0)
    assertTrue(leftCam.centerX * screenRightX < 0.0)

    // Steer Right twice -> logical lane 2 (+3.6) -> renderer X (-3.6) -> dot(screenRight) > 0 => SCREEN RIGHT
    sim.requestLaneChange(+1)
    repeat(30) { sim.stepFixedTick(DrivingInputSnapshot()) }
    sim.requestLaneChange(+1)
    repeat(30) { sim.stepFixedTick(DrivingInputSnapshot()) }
    assertEquals(2, sim.state.currentLaneIndex)
    assertEquals(3.6f, sim.state.carX, 1e-4f)
    val rightRendererX = CoordinateMapper.gameToRendererX(sim.state.carX)
    assertEquals(-3.6f, rightRendererX, 1e-4f)
    assertTrue("Right turn must project right of screen center", rightRendererX * screenRightX > 0.0)
    val rightCam =
      CoordinateMapper.computeChaseCameraPose(
        widthPx = 1080,
        heightPx = 2400,
        cameraFollowX = sim.state.cameraFollowX,
        carX = sim.state.carX,
      )
    assertTrue(rightCam.eyeX * screenRightX > 0.0)
    assertTrue(rightCam.centerX * screenRightX > 0.0)
  }
}
