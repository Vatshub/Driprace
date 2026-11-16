package com.example

import android.app.Application
import android.opengl.Matrix
import android.view.SurfaceView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.example.assets.BundledAsphaltMaps
import com.example.assets.StarterAssetContract
import com.example.assets.StarterAssetRepository
import com.example.render.CoordinateMapper
import com.example.render.EngineRendererPort
import com.example.render.ProceduralRoadGlbBuilder
import com.example.render.StarterVehicleNormalizer
import com.example.render.VehicleBounds
import com.example.render.VehicleNormalizationPlan
import com.example.sim.DrivingInputSnapshot
import com.example.sim.DrivingSimState
import com.example.sim.DrivingSimulation
import com.example.state.DripRaceViewModel
import com.example.state.GamePhase
import com.example.state.HudTelemetryState
import com.example.ui.DripRaceRootScreen
import com.example.ui.theme.DripRaceTheme
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
class DripRacePhase1Test {

  @get:Rule val composeTestRule = createComposeRule()

  internal class FakeRendererPort : EngineRendererPort {
    var loadCount = 0
    var destroyed = false
    var activeStateFlag = false
    var simRunning = false
    var lastInput = DrivingInputSnapshot()
    var lastSyncedState: DrivingSimState? = null
    private var sim: DrivingSimulation? = null
    private var onStep: ((DrivingSimState) -> Unit)? = null

    override fun loadSceneAssets(
      starterGlbBytes: ByteArray,
      asphaltMaps: BundledAsphaltMaps,
      onViewportChanged: (String) -> Unit,
    ): Result<VehicleNormalizationPlan> {
      loadCount++
      onViewportChanged("Portrait 1080x2400 (FOV 56°)")
      return Result.success(
        StarterVehicleNormalizer.computeNormalizationPlan(
          VehicleBounds(-1f, -0.5f, -2f, 1f, 1.0f, 2f)
        )
      )
    }

    override fun bindSimulation(
      simulation: DrivingSimulation,
      onSimulationStepped: (DrivingSimState) -> Unit,
    ) {
      sim = simulation
      onStep = onSimulationStepped
    }

    override fun setSimulationRunning(running: Boolean) {
      simRunning = running
      if (!running) {
        sim?.clearPendingFrameAccumulator()
      }
    }

    override fun setDrivingInput(input: DrivingInputSnapshot) {
      lastInput = input
    }

    override fun syncVisualState(state: DrivingSimState) {
      lastSyncedState = state
    }

    fun simulateRenderFrame(dtSeconds: Double) {
      val s = sim ?: return
      if (!simRunning || !activeStateFlag) return
      val steps = s.advanceRenderFrame(dtSeconds, lastInput)
      if (steps > 0) {
        onStep?.invoke(s.state)
      }
    }

    override fun attachSurfaceView(surfaceView: SurfaceView) {}

    override fun detachSurfaceView(surfaceView: SurfaceView) {}

    override fun setRenderingActive(active: Boolean) {
      activeStateFlag = active
    }

    override fun destroy() {
      destroyed = true
    }
  }

  @Test
  fun `starter vehicle normalization grounds Y at 0, centers X Z, and scales Z to 4_357386m`() {
    val bounds = VehicleBounds(minX = -0.8f, minY = 0.2f, minZ = -1.5f, maxX = 1.2f, maxY = 1.7f, maxZ = 2.5f)
    val plan = StarterVehicleNormalizer.computeNormalizationPlan(bounds)

    val minCorner = floatArrayOf(bounds.minX, bounds.minY, bounds.minZ, 1f)
    val maxCorner = floatArrayOf(bounds.maxX, bounds.maxY, bounds.maxZ, 1f)
    val outMin = FloatArray(4)
    val outMax = FloatArray(4)
    Matrix.multiplyMV(outMin, 0, plan.matrixColumnMajor, 0, minCorner, 0)
    Matrix.multiplyMV(outMax, 0, plan.matrixColumnMajor, 0, maxCorner, 0)

    assertEquals(0f, outMin[1], 1e-4f)
    assertEquals(0f, (outMin[0] + outMax[0]) * 0.5f, 1e-4f)
    assertEquals(0f, (outMin[2] + outMax[2]) * 0.5f, 1e-4f)
    assertEquals(4.357386f, outMax[2] - outMin[2], 1e-4f)
    assertTrue(plan.steeringCorrectionApplied)

    val secondPass =
      StarterVehicleNormalizer.computeNormalizationPlan(bounds, steeringCorrectionAlreadyApplied = true)
    assertFalse(secondPass.steeringCorrectionApplied)
  }

  @Test
  fun `starter front wheel premultiply +30deg Z aligns actual glTF WheelFrontL axle and preserves translation`() {
    val wheelFrontL =
      floatArrayOf(
        0.8660254f, -0.5f, 0f, 0f,
        -0.25000003f, -0.43301275f, 0.8660254f, 0f,
        -0.4330127f, -0.75f, -0.50000006f, 0f,
        0.9754932f, -1.4856806f, 0.38375902f, 1f,
      )
    val corrected = StarterVehicleNormalizer.premultiplyLocalZRotation(wheelFrontL, 30f)

    assertEquals(1.0f, corrected[0], 1e-4f)
    assertEquals(0.0f, corrected[1], 1e-4f)
    assertEquals(0.0f, corrected[2], 1e-4f)
    assertEquals(0.9754932f, corrected[12], 1e-6f)
    assertEquals(-1.4856806f, corrected[13], 1e-6f)
    assertEquals(0.38375902f, corrected[14], 1e-6f)
  }

  @Test
  fun `bounds accumulator transforms rotated and translated local bounding boxes accurately`() {
    val acc = StarterVehicleNormalizer.BoundsAccumulator()
    val rotAndTranslate = FloatArray(16)
    Matrix.setIdentityM(rotAndTranslate, 0)
    Matrix.translateM(rotAndTranslate, 0, 2f, 3f, 4f)
    Matrix.rotateM(rotAndTranslate, 0, 90f, 0f, 1f, 0f)

    acc.includeTransformedBox(0f, 0f, 0f, 1f, 0.5f, 2f, rotAndTranslate)
    val bounds = acc.toVehicleBoundsOrNull()!!

    assertEquals(0f, bounds.minX, 1e-4f)
    assertEquals(4f, bounds.maxX, 1e-4f)
    assertEquals(2.5f, bounds.minY, 1e-4f)
    assertEquals(3.5f, bounds.maxY, 1e-4f)
    assertEquals(3f, bounds.minZ, 1e-4f)
    assertEquals(5f, bounds.maxZ, 1e-4f)
  }

  @Test
  fun `chase camera sits behind car at negative Z in both portrait and landscape`() {
    val portrait = CoordinateMapper.computeChaseCameraPose(1080, 2400)
    val landscape = CoordinateMapper.computeChaseCameraPose(2400, 1080)
    assertTrue(portrait.eyeZ < 0.0)
    assertTrue(portrait.centerZ > 0.0)
    assertTrue(landscape.eyeZ < 0.0)
    assertTrue(landscape.centerZ > 0.0)
    assertTrue(portrait.verticalFovDegrees > landscape.verticalFovDegrees)
  }

  @Test
  fun `procedural road glb builder produces valid glTF 2_0 binary header`() {
    val bytes = ProceduralRoadGlbBuilder.buildRoadGlb(includeAsphaltTextureSlots = true)
    assertTrue(StarterAssetRepository.hasValidGlbHeader(bytes))
    val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    assertEquals(0x46546C67, bb.int)
    assertEquals(2, bb.int)
    assertEquals(bytes.size, bb.int)
    val jsonLen = bb.int
    assertEquals(0x4E4F534A, bb.int)
    val jsonBytes = ByteArray(jsonLen)
    bb.get(jsonBytes)
    val jsonStr = String(jsonBytes, Charsets.UTF_8)
    assertTrue(jsonStr.contains("\"TANGENT\""))
    assertTrue(jsonStr.contains("\"AsphaltSurface\""))
    assertTrue(jsonStr.contains("\"normalTexture\""))
    assertTrue(jsonStr.contains("\"metallicRoughnessTexture\""))
  }

  @Test
  fun `bundled starter glb and asphalt maps load with exact approved SHA-256`() {
    val app = ApplicationProvider.getApplicationContext<Application>()
    val result = StarterAssetRepository(app).loadBundledStarter()
    assertTrue(result is com.example.assets.StarterLoadResult.Ready)
    val ready = result as com.example.assets.StarterLoadResult.Ready
    assertEquals(StarterAssetContract.EXPECTED_STARTER_SHA256, ready.sha256Hex)
    assertTrue(ready.isBundledOfflineAsset)
    assertEquals("textures/asphalt-color.jpg", ready.asphaltMaps.baseColorPath)
    assertEquals("textures/asphalt-normal.jpg", ready.asphaltMaps.normalPath)
    assertEquals("textures/asphalt-roughness.jpg", ready.asphaltMaps.roughnessPath)

    // Verify glTF JSON node hierarchy exposes all 4 wheel assemblies with BrakePad + rolling tire/rim children
    val bb = ByteBuffer.wrap(ready.glbBytes).order(ByteOrder.LITTLE_ENDIAN)
    bb.position(12)
    val jsonLen = bb.int
    bb.int // JSON chunk type
    val jsonBytes = ByteArray(jsonLen)
    bb.get(jsonBytes)
    val rootJson = org.json.JSONObject(String(jsonBytes, Charsets.UTF_8))
    val nodes = rootJson.getJSONArray("nodes")
    val nodesByName = mutableMapOf<String, org.json.JSONObject>()
    for (i in 0 until nodes.length()) {
      val n = nodes.getJSONObject(i)
      val name = n.optString("name", "")
      if (name.isNotEmpty()) nodesByName[name] = n
    }
    for (wheelName in listOf("WheelFrontL", "WheelFrontR", "WheelRearL", "WheelRearR")) {
      val wheelNode = nodesByName[wheelName]
      assertTrue("Missing wheel node $wheelName", wheelNode != null)
      val children = wheelNode!!.optJSONArray("children")
      assertTrue("$wheelName must have child meshes", children != null && children.length() >= 4)
    }
  }

  @Test
  fun `missing or invalid starter glb transitions honestly to Error state with Retry`() {
    val errorState =
      com.example.state.DripRaceUiState(
        phase =
          GamePhase.Error(
            title = "Bundled starter-sports.glb not supplied",
            detail = "Test error check",
            expectedAssetPath = "app/src/main/assets/models/starter-sports.glb",
            expectedSha256 = StarterAssetContract.EXPECTED_STARTER_SHA256,
            actualSha256 = null,
            missingItems = listOf("prepare-vehicle.mjs"),
          )
      )

    composeTestRule.setContent {
      DripRaceTheme {
        DripRaceRootScreen(
          uiState = errorState,
          telemetry = HudTelemetryState(),
          onPlay = {},
          onPause = {},
          onResume = {},
          onRestart = {},
          onHome = {},
          onRetry = {},
          onPickGlbPreview = {},
          onBackFromRunning = {},
          onSteerLeft = {},
          onSteerRight = {},
          onBrakePointerDown = {},
          onBrakePointerUpOrCancel = {},
          onNitroPointerDown = {},
          onNitroPointerUpOrCancel = {},
          onBrakeKeyChanged = {},
          onNitroKeyChanged = {},
          onAttachSurface = {},
          onDetachSurface = {},
          render3DSurface = false,
        )
      }
    }
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithText("DRIP RACE").assertIsDisplayed()
    composeTestRule.onNodeWithText("A Property of Sound Of Drip Musictech Private Limited.").assertIsDisplayed()
    composeTestRule.onNodeWithTag("retry_button").performScrollTo().assertIsDisplayed().performClick()
    composeTestRule.onNodeWithTag("pick_glb_preview_button").performScrollTo().assertIsDisplayed()
  }

  @Test
  fun `state transitions, Back pause, background input release, and single-owner invariant work deterministically`() {
    val app = ApplicationProvider.getApplicationContext<Application>()
    val vm = DripRaceViewModel(app, SavedStateHandle())
    val fakeRenderer = FakeRendererPort()
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
    assertTrue(vm.uiState.value.phase is GamePhase.Home)
    assertEquals(1, fakeRenderer.loadCount)

    // Home -> Running
    vm.onPlayClicked()
    assertTrue(vm.uiState.value.phase is GamePhase.Running)
    assertTrue(fakeRenderer.simRunning)

    // Multi-touch holds while Running
    vm.onNitroPointerDown(10L)
    vm.onBrakePointerDown(20L)
    assertEquals(2, vm.hudTelemetry.value.activeHoldPointerCount)
    assertTrue(fakeRenderer.lastInput.brakeHeld)
    assertTrue(fakeRenderer.lastInput.nitroHeld)

    // Back pauses Running -> Paused and clears held inputs
    assertTrue(vm.onBackPressed())
    assertTrue(vm.uiState.value.phase is GamePhase.Paused)
    assertFalse(fakeRenderer.simRunning)
    assertEquals(0, vm.hudTelemetry.value.activeHoldPointerCount)
    assertFalse(fakeRenderer.lastInput.brakeHeld)
    assertFalse(fakeRenderer.lastInput.nitroHeld)

    // Resume -> Running
    vm.onResumeClicked()
    assertTrue(vm.uiState.value.phase is GamePhase.Running)
    assertTrue(fakeRenderer.simRunning)

    // Backgrounding clears held pointers and pauses Running; returning stays Paused
    vm.onNitroPointerDown(33L)
    assertEquals(1, vm.hudTelemetry.value.activeHoldPointerCount)
    vm.onActivityStartedOrResumed(active = false)
    assertTrue(vm.uiState.value.phase is GamePhase.Paused)
    assertEquals(0, vm.hudTelemetry.value.activeHoldPointerCount)
    vm.onActivityStartedOrResumed(active = true)
    assertTrue(vm.uiState.value.phase is GamePhase.Paused) // Never auto-resumes

    // Paused -> Home, still only 1 model load and 1 renderer owner
    vm.onHomeClicked()
    assertTrue(vm.uiState.value.phase is GamePhase.Home)
    assertEquals(1, fakeRenderer.loadCount)
    assertFalse(fakeRenderer.destroyed)

    // Compose UI check on Home
    composeTestRule.setContent {
      DripRaceTheme {
        DripRaceRootScreen(
          uiState = vm.uiState.value,
          telemetry = vm.hudTelemetry.value,
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
    composeTestRule.onNodeWithText("DRIP RACE").assertIsDisplayed()
    composeTestRule.onNodeWithText("A Property of Sound Of Drip Musictech Private Limited.").assertIsDisplayed()
    composeTestRule.onNodeWithTag("play_button").assertIsDisplayed().performClick()
    assertTrue(vm.uiState.value.phase is GamePhase.Running)
  }
}
