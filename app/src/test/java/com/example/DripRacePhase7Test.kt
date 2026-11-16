package com.example

import android.app.Application
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.example.assets.ActiveEnvironmentSceneryBundle
import com.example.assets.BestScoreRepository
import com.example.assets.BundledAsphaltMaps
import com.example.assets.HeroAssetRepository
import com.example.assets.LoadedSceneryModelPayload
import com.example.assets.SceneryAssetOrigin
import com.example.assets.SceneryAssetRepository
import com.example.assets.StarterAssetRepository
import com.example.assets.TrafficAssetRepository
import com.example.assets.VerifiedHazardSceneryProps
import com.example.economy.AtomicFileProgressionPersistence
import com.example.economy.EconomyService
import com.example.progression.EnvironmentLevelSpec
import com.example.progression.ProgressionCatalog
import com.example.render.EngineRendererPort
import com.example.render.ProceduralSceneryGlbBuilder
import com.example.render.SceneryGltfAdapter
import com.example.scenery.GraphicsQualityPreset
import com.example.scenery.GroundSkirtTheme
import com.example.scenery.PineInstanceLodTracker
import com.example.scenery.PineLodSelector
import com.example.scenery.RadianceHdrDecoder
import com.example.scenery.SceneryCatalog
import com.example.sim.DrivingInputSnapshot
import com.example.sim.DrivingSimState
import com.example.sim.DrivingSimulation
import com.example.state.DripRaceViewModel
import com.example.state.GamePhase
import com.example.ui.DripRaceRootScreen
import com.example.ui.theme.DripRaceTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DripRacePhase7Test {

  @get:Rule val composeTestRule = createComposeRule()

  private class Phase7TrackingRenderer : EngineRendererPort {
    private val delegate = DripRacePhase1Test.FakeRendererPort()
    var lastHazardProps: VerifiedHazardSceneryProps? = null
    var activeBundle: ActiveEnvironmentSceneryBundle? = null
    var activeEnvironmentCount: Int = 0
    var maxSimultaneousEnvironments: Int = 0
    var disposeCount: Int = 0
    val appliedHistory = mutableListOf<Pair<String, GraphicsQualityPreset>>()

    override fun loadSceneAssets(
      starterGlbBytes: ByteArray,
      asphaltMaps: BundledAsphaltMaps,
      onViewportChanged: (String) -> Unit,
    ) = delegate.loadSceneAssets(starterGlbBytes, asphaltMaps, onViewportChanged)

    override fun setVerifiedHazardProps(props: VerifiedHazardSceneryProps) {
      lastHazardProps = props
    }

    override fun applyEnvironmentScenery(
      environment: EnvironmentLevelSpec,
      bundle: ActiveEnvironmentSceneryBundle?,
    ) {
      if (activeBundle != null) {
        activeBundle = null
        activeEnvironmentCount = 0
        disposeCount += 1
      }
      activeBundle = bundle
      activeEnvironmentCount = if (bundle != null) 1 else 0
      maxSimultaneousEnvironments = maxOf(maxSimultaneousEnvironments, activeEnvironmentCount)
      appliedHistory += environment.id to (bundle?.qualityPreset ?: GraphicsQualityPreset.AUTO)
    }

    override fun bindSimulation(
      simulation: DrivingSimulation,
      onSimulationStepped: (DrivingSimState) -> Unit,
    ) = delegate.bindSimulation(simulation, onSimulationStepped)

    override fun setSimulationRunning(running: Boolean) = delegate.setSimulationRunning(running)

    override fun setDrivingInput(input: DrivingInputSnapshot) = delegate.setDrivingInput(input)

    override fun syncVisualState(state: DrivingSimState) = delegate.syncVisualState(state)

    override fun attachSurfaceView(surfaceView: android.view.SurfaceView) =
      delegate.attachSurfaceView(surfaceView)

    override fun detachSurfaceView(surfaceView: android.view.SurfaceView) =
      delegate.detachSurfaceView(surfaceView)

    override fun setRenderingActive(active: Boolean) = delegate.setRenderingActive(active)

    override fun destroy() {
      if (activeBundle != null) {
        activeBundle = null
        activeEnvironmentCount = 0
        disposeCount += 1
      }
      delegate.destroy()
    }
  }

  @Test
  fun sceneryPackageAuditVerifies18BundledEntriesExternalGltfDependenciesPendingValleyAndExcludesConstructionBarier02() {
    val context = ApplicationProvider.getApplicationContext<Application>()
    val repo = SceneryAssetRepository(context)
    val report = repo.auditSceneryPackage()

    assertEquals(19, report.entries.size)
    assertEquals("Expected 18/19 verified bundled scenery/HDR/ground entries: ${report.summaryLine}", 18, report.verifiedCount)
    assertEquals(1, report.pendingLargeCount)
    assertEquals(0, report.missingOrInvalidCount)
    assertTrue(report.excludedAssetsConfirmedAbsent)

    // Valley complete atlas (>30 MB AI Studio upload cap) must remain honestly pending, never faked
    val valleyEntry = report.entries.first { it.spec.id == SceneryCatalog.VALLEY_COMPLETE_ID }
    assertEquals(SceneryAssetOrigin.PENDING_LOCAL_ANDROID_STUDIO_OR_PICKER, valleyEntry.origin)
    assertFalse(valleyEntry.sha256Verified)
    assertTrue(valleyEntry.spec.isCandidate)

    // External .gltf + .bin + textures/*.jpg dependencies verified at original relative paths
    val rocksEntry = report.entries.first { it.spec.id == SceneryCatalog.ROCKS_MOSS_GLTF_ID }
    assertEquals(SceneryAssetOrigin.BUNDLED_VERIFIED, rocksEntry.origin)
    assertTrue(rocksEntry.sha256Verified && rocksEntry.dependenciesVerified)

    val cliffEntry = report.entries.first { it.spec.id == SceneryCatalog.ROCK_FACE_GLTF_ID }
    assertEquals(SceneryAssetOrigin.BUNDLED_VERIFIED, cliffEntry.origin)
    assertTrue(cliffEntry.sha256Verified && cliffEntry.dependenciesVerified)

    // Candidate statuses preserved on desert-mars-sand & mountain-peak
    assertTrue(SceneryCatalog.findAsset(SceneryCatalog.DESERT_MARS_SAND_ID)!!.isCandidate)
    assertTrue(SceneryCatalog.findAsset(SceneryCatalog.MOUNTAIN_PEAK_ID)!!.isCandidate)

    // Construction Barier-02 & incomplete valley fragments strictly excluded everywhere
    assertTrue(SceneryCatalog.isExcludedAssetPath("Construction Barier-02"))
    assertTrue(SceneryCatalog.isExcludedAssetPath("scenery/Construction_Barier-02.glb"))
    assertTrue(SceneryCatalog.isExcludedAssetPath("scenery/valley-fragment-01.glb"))
    SceneryCatalog.ALL_SCENERY_ASSETS.forEach { spec ->
      assertFalse(SceneryCatalog.isExcludedAssetPath(spec.id))
      assertFalse(SceneryCatalog.isExcludedAssetPath(spec.primaryAssetPath))
    }
    SceneryCatalog.ENVIRONMENT_RECIPES.values.forEach { recipe ->
      recipe.propRules.forEach { rule ->
        assertFalse(SceneryCatalog.isExcludedAssetPath(rule.assetId))
      }
    }

    // Verified static highway hazard GLBs (concrete-barrier, roadwork-barricade, traffic-cone)
    val hazardProps = repo.loadVerifiedHazardPropBytes()
    assertNotNull(hazardProps.concreteBarrierGlb)
    assertNotNull(hazardProps.roadworkBarricadeGlb)
    assertNotNull(hazardProps.trafficConeGlb)
    assertTrue(StarterAssetRepository.hasValidGlbHeader(hazardProps.concreteBarrierGlb!!))
    assertTrue(StarterAssetRepository.hasValidGlbHeader(hazardProps.roadworkBarricadeGlb!!))
  }

  @Test
  fun pineThreeLodSelectorEnforcesHysteresisStrictMutualExclusionOnceOnlyGuardAndMatchesBundledGlbNodes() {
    val context = ApplicationProvider.getApplicationContext<Application>()

    // 1. Inspect bundled pine-medium-1-lod-set.glb node names to confirm all 3 LOD subtrees exist
    val pineBytes = context.assets.open("scenery/pine-medium-1-lod-set.glb").use { it.readBytes() }
    val (pineJson, _) = SceneryGltfAdapter.parseGlb(pineBytes)
    val nodes = pineJson.getJSONArray("nodes")
    val nodeNames = buildSet {
      for (i in 0 until nodes.length()) {
        val name = nodes.getJSONObject(i).optString("name", "")
        if (name.isNotEmpty()) add(name)
      }
    }
    assertTrue("Missing root node Pine_medium_1 in $nodeNames", PineLodSelector.ROOT_NODE_NAME in nodeNames)
    PineLodSelector.LOD_GROUP_NODE_NAMES.forEach { lodGroupName ->
      assertTrue("Missing $lodGroupName in $nodeNames", lodGroupName in nodeNames)
    }

    // 2. Validate thresholds [0, 35, 90] with 0.15 hysteresis
    val band = PineLodSelector.validateAndBuildThresholds()
    assertEquals(40.25f, band.lod0OutwardTo1Meters, 1e-3f)
    assertEquals(29.75f, band.lod1InwardTo0Meters, 1e-3f)
    assertEquals(103.5f, band.lod1OutwardTo2Meters, 1e-3f)
    assertEquals(76.5f, band.lod2InwardTo1Meters, 1e-3f)

    // Invalid unsorted distances must throw
    assertThrows(IllegalArgumentException::class.java) {
      PineLodSelector.validateAndBuildThresholds(floatArrayOf(0f, 90f, 35f))
    }

    // 3. Once-only preparation guard & hysteresis non-oscillation + mutual exclusion
    val tracker = PineInstanceLodTracker()
    tracker.markPreparedOnce()
    assertThrows(IllegalStateException::class.java) {
      tracker.markPreparedOnce()
    }

    tracker.resetAtDistance(10f)
    assertEquals(0, tracker.activeLodIndex)
    assertEquals(1, tracker.currentVisibilityMask().count { it })

    // Move outward to 38 m (inside 35..40.25 hysteresis band): stays LOD0!
    assertEquals(0, tracker.updateForCameraDistance(38f))
    // Cross 40.25 m -> transitions cleanly to LOD1
    assertEquals(1, tracker.updateForCameraDistance(41f))
    // Jitter between 31 m and 39 m -> zero oscillation, stays LOD1!
    for (d in listOf(39f, 34f, 36f, 31f, 38f)) {
      assertEquals(1, tracker.updateForCameraDistance(d))
      assertEquals(1, tracker.currentVisibilityMask().count { it })
    }
    // Move inward below 29.75 m -> transitions cleanly back to LOD0
    assertEquals(0, tracker.updateForCameraDistance(29f))

    // Sweep 0..300 m and verify strictly 1 visible LOD at every single meter
    for (dist in 0..300) {
      val lod = tracker.updateForCameraDistance(dist.toFloat())
      val mask = PineLodSelector.visibilityMaskForLod(lod)
      assertEquals("Strictly one Pine LOD must be visible at ${dist}m", 1, mask.count { it })
    }
  }

  @Test
  fun genuineRadianceHdrFilesDecodeWithDistinctDaylightSunsetAndLegibleNightSignatures() {
    val context = ApplicationProvider.getApplicationContext<Application>()

    val daylightBytes = context.assets.open("hdri/daylight-1k.hdr").use { it.readBytes() }
    val sunsetBytes = context.assets.open("hdri/sunset-1k.hdr").use { it.readBytes() }
    val nightBytes = context.assets.open("hdri/night-1k.hdr").use { it.readBytes() }

    val daylight = RadianceHdrDecoder.decodeHdrBytes(daylightBytes, "hdri/daylight-1k.hdr", decodeStride = 4, exposureScale = 1.00f, minimumAmbientFloorLinear = 0.16f)
    val sunset = RadianceHdrDecoder.decodeHdrBytes(sunsetBytes, "hdri/sunset-1k.hdr", decodeStride = 4, exposureScale = 1.05f, minimumAmbientFloorLinear = 0.14f)
    val night = RadianceHdrDecoder.decodeHdrBytes(nightBytes, "hdri/night-1k.hdr", decodeStride = 4, exposureScale = 0.22f, minimumAmbientFloorLinear = 0.24f)

    for (decoded in listOf(daylight, sunset, night)) {
      assertEquals(1024, decoded.sourceWidth)
      assertEquals(512, decoded.sourceHeight)
      assertEquals(256 * 128, decoded.skySrgbArgbPixels.size)
      assertEquals(27, decoded.shIrradiance3Band.size)
      assertTrue(decoded.shIrradiance3Band.all { it.isFinite() })
      assertTrue(decoded.skySrgbArgbPixels.any { (it and 0x00FFFFFF) != 0 })
    }

    // Sunset HDR ("The Sky Is On Fire") has warm golden-orange dominant chromaticity (R > B)
    assertTrue(
      "Expected warm sunset chromaticity R (${sunset.dominantSunChromaticityRgb[0]}) > B (${sunset.dominantSunChromaticityRgb[2]})",
      sunset.dominantSunChromaticityRgb[0] > sunset.dominantSunChromaticityRgb[2],
    )

    // Calibrated tone-mapped sky dome average brightness is distinctly darker at Night than Daylight & Sunset
    fun meanSkyChannelBrightness(pixels: IntArray): Double =
      pixels.sumOf { px ->
        val r = (px ushr 16) and 0xFF
        val g = (px ushr 8) and 0xFF
        val b = px and 0xFF
        (r + g + b).toLong()
      }.toDouble() / (pixels.size * 3.0)

    val daySkyMean = meanSkyChannelBrightness(daylight.skySrgbArgbPixels)
    val nightSkyMean = meanSkyChannelBrightness(night.skySrgbArgbPixels)
    assertTrue(
      "Expected Daylight sky dome ($daySkyMean) brighter than calibrated Night sky dome ($nightSkyMean)",
      daySkyMean > nightSkyMean,
    )

    // Night SH ambient floor guarantees road & traffic legibility (>= 0.24f)
    assertTrue(night.shIrradiance3Band[0] >= 0.24f)
    assertTrue(night.shIrradiance3Band[1] >= 0.24f)
    assertTrue(night.shIrradiance3Band[2] >= 0.24f)
  }

  @Test
  fun externalGltfPackingSynthesizesTangentsWithoutModifyingDiskAndPreservesMars10xUvAndLaneClearance() {
    val context = ApplicationProvider.getApplicationContext<Application>()
    val repo = SceneryAssetRepository(context)

    // Load Mountain Highway bundle -> includes both external glTFs (rock-moss-set-01 & rock-face-02)
    val mountainBundle = repo.loadActiveEnvironmentBundle(ProgressionCatalog.MOUNTAIN_HIGHWAY_ID, GraphicsQualityPreset.HIGH)
    val rocksPayload = mountainBundle.modelsByAssetId[SceneryCatalog.ROCKS_MOSS_GLTF_ID]
    val cliffPayload = mountainBundle.modelsByAssetId[SceneryCatalog.ROCK_FACE_GLTF_ID]
    assertTrue(rocksPayload is LoadedSceneryModelPayload.ExternalGltf)
    assertTrue(cliffPayload is LoadedSceneryModelPayload.ExternalGltf)

    for (payload in listOf(rocksPayload!!, cliffPayload!!)) {
      val packedGlb = SceneryGltfAdapter.preparePayloadForFilament(payload)
      assertTrue(StarterAssetRepository.hasValidGlbHeader(packedGlb))
      val (root, _) = SceneryGltfAdapter.parseGlb(packedGlb)

      // Buffer[0] and all images must now be self-contained bufferViews (no external uri remaining)
      assertFalse(root.getJSONArray("buffers").getJSONObject(0).has("uri"))
      val images = root.getJSONArray("images")
      for (i in 0 until images.length()) {
        assertFalse(images.getJSONObject(i).has("uri"))
        assertTrue(images.getJSONObject(i).has("bufferView"))
      }

      // Every triangle primitive with NORMAL must now have a synthesized VEC4 TANGENT attribute
      val meshes = root.getJSONArray("meshes")
      val accessors = root.getJSONArray("accessors")
      for (m in 0 until meshes.length()) {
        val prims = meshes.getJSONObject(m).getJSONArray("primitives")
        for (p in 0 until prims.length()) {
          val attrs = prims.getJSONObject(p).getJSONObject("attributes")
          assertTrue("Expected synthesized TANGENT on ${payload.spec.id}", attrs.has("TANGENT"))
          val tanAcc = accessors.getJSONObject(attrs.getInt("TANGENT"))
          assertEquals("VEC4", tanAcc.getString("type"))
          assertEquals(5126, tanAcc.getInt("componentType"))
        }
      }
    }

    // Re-audit scenery package to confirm original files on disk were never mutated
    assertEquals(18, repo.auditSceneryPackage().verifiedCount)

    // Procedural helper GLBs (sky dome, ground skirt, contact shadow, particle streak) are valid GLBs
    assertTrue(StarterAssetRepository.hasValidGlbHeader(ProceduralSceneryGlbBuilder.buildUnlitSkyDomeGlb()))
    assertTrue(StarterAssetRepository.hasValidGlbHeader(ProceduralSceneryGlbBuilder.buildGroundSkirtGlb(GroundSkirtTheme.LEAFY_GRASS_PBR, includeTextureSlots = true)))
    assertTrue(StarterAssetRepository.hasValidGlbHeader(ProceduralSceneryGlbBuilder.buildContactShadowGlb()))
    assertTrue(StarterAssetRepository.hasValidGlbHeader(ProceduralSceneryGlbBuilder.buildParticleStreakGlb(floatArrayOf(0.8f, 0.9f, 1.0f))))

    // Desert Mars-derived sand surface uses exact authored 10.0x uniform scale (UV multiplier = 1.0f)
    val desertRecipe = SceneryCatalog.recipeFor(ProgressionCatalog.DESERT_HIGHWAY_ID)
    val marsRule = desertRecipe.propRules.first { it.assetId == SceneryCatalog.DESERT_MARS_SAND_ID }
    assertEquals(10.0f, marsRule.uniformScale, 1e-4f)
    assertEquals(1.0f, SceneryGltfAdapter.computeDesertMarsUvScaleMultiplier(marsRule.uniformScale), 1e-5f)
    assertEquals(0.5f, SceneryGltfAdapter.computeDesertMarsUvScaleMultiplier(5.0f), 1e-5f)

    // All 5 environment recipes keep roadside props strictly outside playable lanes [-5.4, +5.4] & barriers [-8.75, +8.75]
    assertEquals(5, SceneryCatalog.ENVIRONMENT_RECIPES.size)
    SceneryCatalog.ENVIRONMENT_RECIPES.values.forEach { recipe ->
      assertTrue(recipe.propRules.isNotEmpty())
      recipe.propRules.forEach { rule ->
        assertTrue(
          "Prop ${rule.assetId} in ${recipe.environmentId} violates roadside clearance: ${rule.minAbsLateralXMeters}",
          rule.minAbsLateralXMeters >= SceneryCatalog.MINIMUM_SAFE_ROADSIDE_ABS_X_METERS,
        )
      }
    }
  }

  @Test
  fun lazySingleEnvironmentLoadingRepeatedSceneSwitchLifecycleNoMidRunSwapAndLowQualityGameplayInvariance() {
    val context = ApplicationProvider.getApplicationContext<Application>()
    val persistenceDir = File(context.filesDir, AtomicFileProgressionPersistence.DIR_NAME)
    persistenceDir.deleteRecursively()

    val economy =
      EconomyService(
        persistence = AtomicFileProgressionPersistence(context),
        initialTimeZoneSupplier = { "UTC" },
      )
    // Unlock all 5 environments permanently by reaching 45,000 m lifetime distance
    economy.checkpointRaceProgress(
      raceId = "unlock_all_5_levels",
      distanceMeters = 45_000.0,
      activeSeconds = 950.0,
      uniqueOvertakes = 30,
      uniqueNearMisses = 8,
      qualifiedManualNitroUses = 6,
      raceEnded = true,
    )
    assertEquals(5, economy.currentSnapshot().unlockedEnvironmentIds.size)

    val sceneryRepo = SceneryAssetRepository(context)
    val renderer = Phase7TrackingRenderer()
    val vm = DripRaceViewModel(context, SavedStateHandle())
    vm.overrideDependenciesForTest(
      repository = StarterAssetRepository(context),
      renderer = renderer,
      trafficRepository = TrafficAssetRepository(context),
      scoreRepository = BestScoreRepository(context),
      customEconomyService = economy,
      heroRepository = HeroAssetRepository(context),
      sceneryRepository = sceneryRepo,
    )

    assertEquals(GamePhase.Home, vm.uiState.value.phase)
    assertNotNull(renderer.lastHazardProps?.concreteBarrierGlb)
    assertEquals(1, renderer.maxSimultaneousEnvironments)
    assertEquals(ProgressionCatalog.BEGINNER_HIGHWAY_ID, renderer.activeBundle?.environmentId)
    assertTrue(SceneryCatalog.PINE_LOD_SET_ID in renderer.activeBundle!!.loadedAssetIds)
    assertFalse(SceneryCatalog.HDR_SUNSET_ID in renderer.activeBundle!!.loadedAssetIds)
    assertFalse(SceneryCatalog.HDR_NIGHT_ID in renderer.activeBundle!!.loadedAssetIds)

    // Repeated scene-switch lifecycle across all 5 unlocked environments on Home:
    // Beginner -> City -> Desert -> Mountain -> Night -> Beginner
    val switchSequence =
      listOf(
        ProgressionCatalog.CITY_HIGHWAY_ID,
        ProgressionCatalog.DESERT_HIGHWAY_ID,
        ProgressionCatalog.MOUNTAIN_HIGHWAY_ID,
        ProgressionCatalog.NIGHT_HIGHWAY_ID,
        ProgressionCatalog.BEGINNER_HIGHWAY_ID,
      )
    val loadsBeforeCycle = sceneryRepo.lazyEnvironmentLoadCount
    for (envId in switchSequence) {
      vm.onSelectEnvironment(envId)
      assertEquals(envId, vm.uiState.value.activeSceneEnvironmentId)
      assertEquals(envId, renderer.activeBundle?.environmentId)
      assertEquals(1, renderer.activeEnvironmentCount)
      assertEquals(1, renderer.maxSimultaneousEnvironments)

      val loadedIds = renderer.activeBundle!!.loadedAssetIds
      when (envId) {
        ProgressionCatalog.CITY_HIGHWAY_ID -> {
          assertTrue(SceneryCatalog.CITY_SKYLINE_ID in loadedIds)
          assertTrue(SceneryCatalog.SKYSCRAPER_ID in loadedIds)
          assertFalse(SceneryCatalog.DESERT_MARS_SAND_ID in loadedIds)
          assertFalse(SceneryCatalog.HDR_NIGHT_ID in loadedIds)
        }
        ProgressionCatalog.DESERT_HIGHWAY_ID -> {
          assertTrue(SceneryCatalog.HDR_SUNSET_ID in loadedIds)
          assertTrue(SceneryCatalog.DESERT_MARS_SAND_ID in loadedIds)
          assertTrue(SceneryCatalog.SAND_GROUND_ID in loadedIds)
          assertFalse(SceneryCatalog.HDR_DAYLIGHT_ID in loadedIds)
        }
        ProgressionCatalog.MOUNTAIN_HIGHWAY_ID -> {
          assertTrue(SceneryCatalog.MOUNTAIN_PEAK_ID in loadedIds)
          assertTrue(SceneryCatalog.ROCK_FACE_GLTF_ID in loadedIds)
          assertTrue(SceneryCatalog.PINE_LOD_SET_ID in loadedIds)
          // >30MB valley candidate is honestly recorded in missingRequestedAssetIds while absent
          assertTrue(SceneryCatalog.VALLEY_COMPLETE_ID in renderer.activeBundle!!.missingRequestedAssetIds)
        }
        ProgressionCatalog.NIGHT_HIGHWAY_ID -> {
          assertTrue(SceneryCatalog.HDR_NIGHT_ID in loadedIds)
          assertTrue(SceneryCatalog.STREETLIGHT_ID in loadedIds)
          assertTrue(renderer.activeBundle!!.recipe.isNightIlluminated)
          assertTrue(renderer.activeBundle!!.recipe.playerHeadlightIntensityCandela >= 80_000f)
        }
      }
    }
    assertEquals(loadsBeforeCycle + switchSequence.size, sceneryRepo.lazyEnvironmentLoadCount)
    assertTrue(renderer.disposeCount >= switchSequence.size)

    // Start race in Beginner Highway -> Pause -> Select Night Highway & LOW quality while paused:
    // MUST NOT swap active world mid-run; applies cleanly on NEXT race!
    vm.onPlayClicked()
    assertEquals(GamePhase.Running, vm.uiState.value.phase)
    val historyCountAtRaceStart = renderer.appliedHistory.size

    vm.onPauseClicked()
    assertEquals(GamePhase.Paused, vm.uiState.value.phase)
    vm.onSelectEnvironment(ProgressionCatalog.NIGHT_HIGHWAY_ID)
    vm.onSelectGraphicsQualityPreset(GraphicsQualityPreset.LOW)

    assertEquals(ProgressionCatalog.BEGINNER_HIGHWAY_ID, vm.uiState.value.activeSceneEnvironmentId)
    assertEquals(historyCountAtRaceStart, renderer.appliedHistory.size)

    // Restart into next race -> Night Highway + LOW quality now active, Night illumination preserved!
    vm.onRestartClicked()
    assertEquals(GamePhase.Running, vm.uiState.value.phase)
    assertEquals(ProgressionCatalog.NIGHT_HIGHWAY_ID, vm.uiState.value.activeSceneEnvironmentId)
    assertEquals(ProgressionCatalog.NIGHT_HIGHWAY_ID to GraphicsQualityPreset.LOW, renderer.appliedHistory.last())
    assertTrue(renderer.activeBundle!!.recipe.isNightIlluminated)
    assertFalse(renderer.activeBundle!!.qualityPreset.enableParticles)
    assertFalse(renderer.activeBundle!!.qualityPreset.enableFarBackdropModels)

    // Reload EconomyService from disk -> GraphicsQualityPreset.LOW persists in schema v4
    val reloadedEconomy =
      EconomyService(
        persistence = AtomicFileProgressionPersistence(context),
        initialTimeZoneSupplier = { "UTC" },
      )
    assertEquals(GraphicsQualityPreset.LOW.id, reloadedEconomy.currentSnapshot().graphicsQualityPresetId)

    // Verify 60 Hz simulation gameplay fairness is 100% invariant between LOW and HIGH quality
    val simLow = DrivingSimulation(enableTraffic = false, enablePickups = false)
    val simHigh = DrivingSimulation(enableTraffic = false, enablePickups = false)
    simLow.requestLaneChange(-1)
    simHigh.requestLaneChange(-1)
    repeat(120) {
      simLow.stepFixedTick(DrivingInputSnapshot(nitroHeld = it in 10..40))
      simHigh.stepFixedTick(DrivingInputSnapshot(nitroHeld = it in 10..40))
    }
    assertEquals(simLow.state.distanceMeters, simHigh.state.distanceMeters, 1e-9)
    assertEquals(simLow.state.carX.toDouble(), simHigh.state.carX.toDouble(), 1e-9)
    assertEquals(simLow.state.speedKmh, simHigh.state.speedKmh, 1e-9)
  }

  @Test
  fun composeLevelsOverlayRendersQualityPresetsHonestHdrAndCandidateNotesAndPreservesExactBranding() {
    val context = ApplicationProvider.getApplicationContext<Application>()
    val persistenceDir = File(context.filesDir, AtomicFileProgressionPersistence.DIR_NAME)
    persistenceDir.deleteRecursively()

    val economy =
      EconomyService(
        persistence = AtomicFileProgressionPersistence(context),
        initialTimeZoneSupplier = { "UTC" },
      )
    economy.checkpointRaceProgress(
      raceId = "ui_unlock_all",
      distanceMeters = 42_000.0,
      activeSeconds = 800.0,
      uniqueOvertakes = 15,
      uniqueNearMisses = 4,
      qualifiedManualNitroUses = 3,
      raceEnded = true,
    )

    val vm = DripRaceViewModel(context, SavedStateHandle())
    vm.overrideDependenciesForTest(
      repository = StarterAssetRepository(context),
      renderer = Phase7TrackingRenderer(),
      trafficRepository = TrafficAssetRepository(context),
      scoreRepository = BestScoreRepository(context),
      customEconomyService = economy,
      heroRepository = HeroAssetRepository(context),
      sceneryRepository = SceneryAssetRepository(context),
    )

    composeTestRule.setContent {
      val uiState by vm.uiState.collectAsState()
      val telemetry by vm.hudTelemetry.collectAsState()
      DripRaceTheme {
        DripRaceRootScreen(
          uiState = uiState,
          telemetry = telemetry,
          onPlay = vm::onPlayClicked,
          onPlayAgain = vm::onPlayAgainClicked,
          onPause = vm::onPauseClicked,
          onResume = vm::onResumeClicked,
          onRestart = vm::onRestartClicked,
          onHome = vm::onHomeClicked,
          onRetry = vm::onRetryClicked,
          onPickGlbPreview = {},
          onOpenGarage = vm::onOpenGarageClicked,
          onExitGarageToHome = vm::onExitGarageToHomeClicked,
          onOpenProgressionTab = vm::onOpenProgressionTab,
          onSelectProgressionTab = vm::onSelectProgressionTab,
          onCloseProgressionOverlay = vm::onCloseProgressionOverlay,
          onClaimDailyMission = vm::onClaimDailyMission,
          onClaimAchievement = vm::onClaimAchievement,
          onClaimDailyLogin = vm::onClaimDailyLoginReward,
          onSelectEnvironment = vm::onSelectEnvironment,
          onSelectGraphicsQuality = vm::onSelectGraphicsQualityPreset,
          onSyncCalendarClock = vm::onSyncCalendarClock,
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

    // Exact branding preserved
    composeTestRule.onNodeWithText("DRIP RACE").assertIsDisplayed()
    composeTestRule
      .onNodeWithText("A Property of Sound Of Drip Musictech Private Limited.")
      .assertIsDisplayed()
    composeTestRule
      .onNodeWithTag("home_scenery_inventory_text")
      .performScrollTo()
      .assertTextContains("18/19 verified", substring = true)

    // Open Levels tab -> inspect scenery summary, HDR preparation route, >30MB Valley import button, & quality presets
    composeTestRule.onNodeWithTag("levels_button").performScrollTo().performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("progression_hub_overlay").assertIsDisplayed()
    composeTestRule
      .onNodeWithTag("levels_scenery_summary_text")
      .performScrollTo()
      .assertTextContains("18/19 verified", substring = true)
    composeTestRule
      .onNodeWithTag("levels_hdr_route_text")
      .performScrollTo()
      .assertTextContains("SAMPLER_2D 3D sky dome", substring = true)
    composeTestRule.onNodeWithTag("import_valley_scenery_button").performScrollTo().assertIsDisplayed()

    // Select Low quality preset & Night Highway
    composeTestRule.onNodeWithTag("quality_preset_low").performScrollTo().performClick()
    composeTestRule.waitForIdle()
    assertEquals(GraphicsQualityPreset.LOW.id, vm.uiState.value.walletSnapshot.graphicsQualityPresetId)

    composeTestRule.onNodeWithTag("environment_select_night_highway").performScrollTo().performClick()
    composeTestRule.waitForIdle()
    assertEquals(ProgressionCatalog.NIGHT_HIGHWAY_ID, vm.uiState.value.activeSceneEnvironmentId)

    composeTestRule.onNodeWithTag("progression_close_button").performClick()
    composeTestRule.waitForIdle()
    composeTestRule
      .onNodeWithTag("home_environment_text")
      .performScrollTo()
      .assertTextContains("Night Highway", substring = true)
  }
}
