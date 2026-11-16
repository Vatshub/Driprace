package com.example

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.example.assets.BestScoreRepository
import com.example.assets.BundledAsphaltMaps
import com.example.assets.HeroAssetRepository
import com.example.assets.HeroImportResult
import com.example.assets.HeroModelOrigin
import com.example.assets.LoadedHeroModelBytes
import com.example.assets.StarterAssetContract
import com.example.assets.StarterAssetRepository
import com.example.assets.TrafficAssetRepository
import com.example.economy.AtomicFileProgressionPersistence
import com.example.economy.EconomyService
import com.example.economy.GarageMutationResult
import com.example.economy.PersistenceLoadOutcome
import com.example.economy.PersistenceWriteOutcome
import com.example.economy.PlayerProgression
import com.example.economy.ProgressionPersistencePort
import com.example.economy.SaveEnvelope
import com.example.economy.SaveHealthStatus
import com.example.garage.BodyPaintColor
import com.example.garage.HeroVehicleCatalog
import com.example.garage.PaintCatalog
import com.example.garage.UpgradeTrack
import com.example.garage.VehicleUpgradeLevels
import com.example.render.CoordinateMapper
import com.example.render.EngineRendererPort
import com.example.render.HeroGlbRuntimeAdapter
import com.example.render.ProceduralRoadGlbBuilder
import com.example.render.VehicleNormalizationPlan
import com.example.sim.ActiveObstacleSnapshot
import com.example.sim.CoinTier
import com.example.sim.CollisionAndReachabilityMath
import com.example.sim.DrivingInputSnapshot
import com.example.sim.DrivingSimState
import com.example.sim.DrivingSimulation
import com.example.sim.EncounterCandidate
import com.example.sim.TrafficSimulation
import com.example.state.DripRaceViewModel
import com.example.state.GamePhase
import com.example.ui.DripRaceRootScreen
import com.example.ui.theme.DripRaceTheme
import java.io.File
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DripRacePhase5Test {

  @get:Rule val composeTestRule = createComposeRule()

  private class Phase5TrackingRenderer : EngineRendererPort {
    private val delegate = DripRacePhase1Test.FakeRendererPort()
    val heroLoadHistory = mutableListOf<Triple<String, HeroModelOrigin, String>>()
    val paintHistory = mutableListOf<String>()
    val showroomHistory = mutableListOf<Triple<Boolean, Float, Float>>()

    override fun loadSceneAssets(
      starterGlbBytes: ByteArray,
      asphaltMaps: BundledAsphaltMaps,
      onViewportChanged: (String) -> Unit,
    ) = delegate.loadSceneAssets(starterGlbBytes, asphaltMaps, onViewportChanged)

    override fun bindSimulation(
      simulation: DrivingSimulation,
      onSimulationStepped: (DrivingSimState) -> Unit,
    ) = delegate.bindSimulation(simulation, onSimulationStepped)

    override fun setSimulationRunning(running: Boolean) = delegate.setSimulationRunning(running)

    override fun setDrivingInput(input: DrivingInputSnapshot) = delegate.setDrivingInput(input)

    override fun syncVisualState(state: DrivingSimState) = delegate.syncVisualState(state)

    override fun loadHeroVehicle(
      loadedHero: LoadedHeroModelBytes,
      paintColor: BodyPaintColor,
    ): Result<VehicleNormalizationPlan> {
      heroLoadHistory += Triple(loadedHero.spec.id, loadedHero.origin, paintColor.id)
      return delegate.loadHeroVehicle(loadedHero, paintColor)
    }

    override fun applyHeroBodyPaint(paintColor: BodyPaintColor) {
      paintHistory += paintColor.id
    }

    override fun setShowroomCamera(
      enabled: Boolean,
      yawDegrees: Float,
      pitchDegrees: Float,
      distanceMeters: Float,
    ) {
      showroomHistory += Triple(enabled, yawDegrees, distanceMeters)
    }

    override fun attachSurfaceView(surfaceView: android.view.SurfaceView) =
      delegate.attachSurfaceView(surfaceView)

    override fun detachSurfaceView(surfaceView: android.view.SurfaceView) =
      delegate.detachSurfaceView(surfaceView)

    override fun setRenderingActive(active: Boolean) = delegate.setRenderingActive(active)

    override fun destroy() = delegate.destroy()
  }

  private fun createIsolatedPhase5ViewModel(
    customEconomy: EconomyService? = null,
    useRealAssetsHeroRepo: Boolean = true,
  ): Triple<DripRaceViewModel, Phase5TrackingRenderer, AtomicFileProgressionPersistence> {
    val app = ApplicationProvider.getApplicationContext<Application>()
    app.getSharedPreferences("drip_race_phase3_scores", Context.MODE_PRIVATE).edit().clear().commit()
    File(app.filesDir, AtomicFileProgressionPersistence.DIR_NAME).deleteRecursively()
    File(app.filesDir, HeroAssetRepository.IMPORT_DIR_NAME).deleteRecursively()

    val persistence = AtomicFileProgressionPersistence(app)
    val bestScoreRepo = BestScoreRepository(app)
    val economy =
      customEconomy ?: EconomyService(persistence = persistence, bestScoreRepository = bestScoreRepo)

    val vm = DripRaceViewModel(app, SavedStateHandle())
    val renderer = Phase5TrackingRenderer()
    val fakeStarterRepo =
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
    val heroRepo =
      if (useRealAssetsHeroRepo) {
        HeroAssetRepository(app)
      } else {
        object : HeroAssetRepository(app) {
          override fun loadHeroModel(vehicleId: String): LoadedHeroModelBytes {
            val spec = HeroVehicleCatalog.findById(vehicleId) ?: HeroVehicleCatalog.STARTER_SPEC
            return LoadedHeroModelBytes(
              spec = spec,
              glbBytes = null,
              origin =
                if (spec.onDeviceImportOnly) {
                  HeroModelOrigin.IMPORT_REQUIRED_PROCEDURAL_FALLBACK
                } else {
                  HeroModelOrigin.BUNDLED_OFFLINE_GLB
                },
              sha256Verified = !spec.onDeviceImportOnly,
              statusSummary = if (spec.onDeviceImportOnly) "Import required" else "Test stub bundled",
            )
          }
        }
      }
    vm.overrideDependenciesForTest(
      repository = fakeStarterRepo,
      renderer = renderer,
      trafficRepository = TrafficAssetRepository(app),
      scoreRepository = bestScoreRepo,
      customEconomyService = economy,
      heroRepository = heroRepo,
    )
    vm.onActivityStartedOrResumed(active = true)
    return Triple(vm, renderer, persistence)
  }

  @Test
  fun `exact 5-car contract prices, base physics, 3 hearts invariance, and upgrade formulas match spec`() {
    assertEquals(5, HeroVehicleCatalog.ALL_CARS.size)
    assertEquals(7, PaintCatalog.SEVEN_FREE_COLORS.size)

    val starter = HeroVehicleCatalog.findById(HeroVehicleCatalog.STARTER_ID)!!
    val superCar = HeroVehicleCatalog.findById(HeroVehicleCatalog.SUPER_RACING_ID)!!
    val muscle = HeroVehicleCatalog.findById(HeroVehicleCatalog.MUSCLE_ID)!!
    val hyper = HeroVehicleCatalog.findById(HeroVehicleCatalog.HYPER_SPORTS_ID)!!
    val legendary = HeroVehicleCatalog.findById(HeroVehicleCatalog.LEGENDARY_ID)!!

    // Unlock prices
    assertEquals(0L, starter.unlockPrice)
    assertEquals(500L, superCar.unlockPrice)
    assertEquals(1000L, muscle.unlockPrice)
    assertEquals(2000L, hyper.unlockPrice)
    assertEquals(5000L, legendary.unlockPrice)

    // Upgrade prices
    assertEquals(listOf(100L, 200L, 350L), starter.upgradeLevelPrices)
    assertEquals(listOf(175L, 350L, 600L), superCar.upgradeLevelPrices)
    assertEquals(listOf(250L, 500L, 850L), muscle.upgradeLevelPrices)
    assertEquals(listOf(400L, 800L, 1400L), hyper.upgradeLevelPrices)
    assertEquals(listOf(600L, 1200L, 2000L), legendary.upgradeLevelPrices)

    // Exact base physics & formulas
    assertEquals(180.0, starter.baseTopSpeedKmh, 1e-6)
    assertEquals(5.5, starter.baseAccelerationMps2, 1e-6)
    assertEquals(12.0, starter.baseBrakeDecelerationMps2, 1e-6)
    assertEquals(0.48, starter.baseLaneChangeDurationSeconds, 1e-6)
    assertEquals(28.0, starter.baseNitroDrainPerSecond, 1e-6)

    // Fully upgraded Legendary caps: 258 km/h normal and 322.5 km/h Nitro
    val maxUpgrades = VehicleUpgradeLevels(engine = 3, handling = 3, brakes = 3, nitro = 3)
    val maxLegendPhysics = legendary.computeEffectivePhysics(maxUpgrades)
    assertEquals(258.0, maxLegendPhysics.topSpeedKmh, 1e-6)
    assertEquals(322.5, maxLegendPhysics.topSpeedKmh * maxLegendPhysics.nitroSpeedMultiplier, 1e-6)
    assertEquals(8.0 + 3 * 0.35, maxLegendPhysics.accelerationMps2, 1e-6)
    assertEquals(13.5 + 3 * 0.8, maxLegendPhysics.brakeDecelerationMps2, 1e-6)
    // Handling floor max(0.30, 0.35 - 0.075) = 0.30
    assertEquals(0.30, maxLegendPhysics.laneChangeDurationSeconds, 1e-6)
    // Nitro drain max(15, 22 - 4.5) = 17.5, regen = 12 + 1.5 = 13.5
    assertEquals(17.5, maxLegendPhysics.nitroDrainPerSecond, 1e-6)
    assertEquals(13.5, maxLegendPhysics.nitroRegenPerSecond, 1e-6)
    assertEquals(1.25, maxLegendPhysics.nitroSpeedMultiplier, 1e-6)

    // Verify 3 hearts invariant across every vehicle simulation configuration
    val sim = DrivingSimulation(enableTraffic = true, enablePickups = false)
    HeroVehicleCatalog.ALL_CARS.forEach { car ->
      sim.configureActiveVehicle(car.computeEffectivePhysics(maxUpgrades), resetState = true)
      assertEquals(TrafficSimulation.MAX_HEARTS, sim.state.hearts)
      assertEquals(100.0, sim.state.nitroMeter, 1e-6)
    }

    // Verify Legendary Lv3 reaches real 258.0 km/h normal & 322.5 km/h Nitro via fixed-60Hz acceleration (open highway)
    val accelSim = DrivingSimulation(enableTraffic = false, enablePickups = false)
    accelSim.configureActiveVehicle(maxLegendPhysics, resetState = true)
    repeat(60 * 30) {
      accelSim.stepFixedTick(DrivingInputSnapshot())
    }
    assertEquals(258.0, accelSim.state.speedKmh, 0.05)

    // Hold Nitro for 5.0 s -> accelerates cleanly up to 322.5 km/h
    repeat(60 * 5) {
      accelSim.stepFixedTick(DrivingInputSnapshot(nitroHeld = true))
    }
    assertEquals(322.5, accelSim.state.speedKmh, 0.25)
  }

  @Test
  fun `economy purchase, insufficient funds, pending-credit block, duplicate opId, no unowned equip, and per-car upgrade isolation`() {
    val app = ApplicationProvider.getApplicationContext<Application>()
    File(app.filesDir, AtomicFileProgressionPersistence.DIR_NAME).deleteRecursively()
    val persistence = AtomicFileProgressionPersistence(app)
    val economy = EconomyService(persistence = persistence)

    // Unconfirmed pending coins CANNOT be spent via tryDebitConfirmedBalance before durable flush
    repeat(50) { idx ->
      economy.creditCoinPickup("pending_coin_$idx", CoinTier.REGULAR, doubleCoinsActive = false)
    }
    assertEquals(500L, economy.currentSnapshot().displayBalance)
    assertEquals(0L, economy.currentSnapshot().confirmedBalance)

    // Cannot buy Muscle (1000) with only 500
    val insufficientBeforeReward =
      economy.purchaseVehicle(HeroVehicleCatalog.MUSCLE_ID, operationId = "buy_muscle_fail")
    assertTrue(insufficientBeforeReward is GarageMutationResult.Rejected)
    assertFalse(economy.currentSnapshot().isOwned(HeroVehicleCatalog.MUSCLE_ID))

    // Unowned vehicle equip, paint, and upgrade MUST be strictly rejected
    assertTrue(economy.selectVehicle(HeroVehicleCatalog.MUSCLE_ID) is GarageMutationResult.Rejected)
    assertTrue(
      economy.setOwnedVehiclePaint(HeroVehicleCatalog.MUSCLE_ID, "racing_yellow")
        is GarageMutationResult.Rejected
    )
    assertTrue(
      economy.purchaseVehicleUpgrade(
        vehicleId = HeroVehicleCatalog.MUSCLE_ID,
        track = UpgradeTrack.ENGINE,
        operationId = "upg_locked",
      ) is GarageMutationResult.Rejected
    )

    // Grant confirmed funds (1,000 -> total 1,500 after flush)
    economy.grantNonCoinReward("seed_funds", 1000L)
    assertEquals(1500L, economy.currentSnapshot().confirmedBalance)

    // Purchase Super Racing (500) with idempotent operationId
    val buy1 = economy.purchaseVehicle(HeroVehicleCatalog.SUPER_RACING_ID, operationId = "buy_super_1")
    assertTrue(buy1 is GarageMutationResult.Succeeded)
    assertEquals(1000L, economy.currentSnapshot().confirmedBalance)
    assertTrue(economy.currentSnapshot().isOwned(HeroVehicleCatalog.SUPER_RACING_ID))
    assertEquals(HeroVehicleCatalog.SUPER_RACING_ID, economy.currentSnapshot().selectedVehicleId)

    // Duplicate operationId / rapid double click never double charges!
    val buyDupOp =
      economy.purchaseVehicle(HeroVehicleCatalog.SUPER_RACING_ID, operationId = "buy_super_1")
    assertTrue(buyDupOp is GarageMutationResult.DuplicateIgnored)
    val buyAlreadyOwned =
      economy.purchaseVehicle(HeroVehicleCatalog.SUPER_RACING_ID, operationId = "buy_super_2")
    assertTrue(buyAlreadyOwned is GarageMutationResult.Rejected)
    assertEquals(1000L, economy.currentSnapshot().confirmedBalance)

    // Sequential upgrades on Super Racing (175, 350, 600). Lv 1 (175) & Lv 2 (350) succeed -> 475 left; Lv 3 (600) rejected!
    val upg1 =
      economy.purchaseVehicleUpgrade(HeroVehicleCatalog.SUPER_RACING_ID, UpgradeTrack.ENGINE, "u1")
    assertTrue(upg1 is GarageMutationResult.Succeeded)
    val upg1Dup =
      economy.purchaseVehicleUpgrade(HeroVehicleCatalog.SUPER_RACING_ID, UpgradeTrack.ENGINE, "u1")
    assertTrue(upg1Dup is GarageMutationResult.DuplicateIgnored)
    val upg2 =
      economy.purchaseVehicleUpgrade(HeroVehicleCatalog.SUPER_RACING_ID, UpgradeTrack.ENGINE, "u2")
    assertTrue(upg2 is GarageMutationResult.Succeeded)
    assertEquals(475L, economy.currentSnapshot().confirmedBalance)

    // Race-in-progress upgrade MUST be rejected cleanly
    val upgDuringRace =
      economy.purchaseVehicleUpgrade(
        HeroVehicleCatalog.SUPER_RACING_ID,
        UpgradeTrack.BRAKES,
        "u_race",
        raceInProgress = true,
      )
    assertTrue(upgDuringRace is GarageMutationResult.Rejected)
    assertEquals(475L, economy.currentSnapshot().confirmedBalance)

    val upg3Insufficient =
      economy.purchaseVehicleUpgrade(HeroVehicleCatalog.SUPER_RACING_ID, UpgradeTrack.ENGINE, "u3")
    assertTrue(upg3Insufficient is GarageMutationResult.Rejected)
    assertEquals(2, economy.currentSnapshot().upgradesFor(HeroVehicleCatalog.SUPER_RACING_ID).engine)
    // Starter car upgrades remain cleanly isolated at 0
    assertEquals(0, economy.currentSnapshot().upgradesFor(HeroVehicleCatalog.STARTER_ID).engine)

    // Paint owned Super Racing & verify persistence across storage reload + v2 checksum
    val paintRes = economy.setOwnedVehiclePaint(HeroVehicleCatalog.SUPER_RACING_ID, "deep_emerald_green")
    assertTrue(paintRes is GarageMutationResult.Succeeded)

    val reloadedEconomy = EconomyService(persistence = persistence)
    val reloadedSnap = reloadedEconomy.currentSnapshot()
    assertEquals(475L, reloadedSnap.confirmedBalance)
    assertTrue(reloadedSnap.isOwned(HeroVehicleCatalog.SUPER_RACING_ID))
    assertEquals(HeroVehicleCatalog.SUPER_RACING_ID, reloadedSnap.selectedVehicleId)
    assertEquals("deep_emerald_green", reloadedSnap.paintIdFor(HeroVehicleCatalog.SUPER_RACING_ID))
    assertEquals(2, reloadedSnap.upgradesFor(HeroVehicleCatalog.SUPER_RACING_ID).engine)
    assertEquals(0, reloadedSnap.upgradesFor(HeroVehicleCatalog.STARTER_ID).engine)
  }

  @Test
  fun `save v1 migrates cleanly preserving balance and write failures rollback garage mutation cleanly`() {
    var failWrites = false
    val v1Payload =
      PlayerProgression(
        confirmedWalletBalance = 1200L,
        bestScore = 9999,
      )
    val v1EnvelopeJson =
      SaveEnvelope.toJsonString(
        SaveEnvelope(
          format = SaveEnvelope.FORMAT_ID,
          schemaVersion = SaveEnvelope.SCHEMA_VERSION_V1,
          revision = 4L,
          payload = v1Payload,
          integrity =
            SaveEnvelope.computeIntegrityMarker(
              SaveEnvelope.FORMAT_ID,
              SaveEnvelope.SCHEMA_VERSION_V1,
              4L,
              v1Payload,
            ),
        )
      )

    var storedJson: String? = v1EnvelopeJson
    val memoryPersistence =
      object : ProgressionPersistencePort {
        override fun loadValidated(): PersistenceLoadOutcome {
          val raw = storedJson ?: return PersistenceLoadOutcome.EmptyNoSaveYet
          val parsed = SaveEnvelope.parseAndValidate(raw)
          return PersistenceLoadOutcome.LoadedPrimary(parsed.getOrThrow())
        }

        override fun writeAtomicWithBackup(envelope: SaveEnvelope): PersistenceWriteOutcome {
          if (failWrites) return PersistenceWriteOutcome.Failure("Simulated I/O disk full")
          storedJson = SaveEnvelope.toJsonString(envelope)
          return PersistenceWriteOutcome.Success
        }
      }

    val economy = EconomyService(persistence = memoryPersistence)
    val snap = economy.currentSnapshot()
    assertEquals(1200L, snap.confirmedBalance)
    assertEquals(9999, snap.bestScore)
    assertEquals(setOf(HeroVehicleCatalog.STARTER_ID), snap.ownedVehicleIds)
    assertEquals(HeroVehicleCatalog.STARTER_ID, snap.selectedVehicleId)

    // Simulate storage failure during purchase -> state & wallet stay intact without false success!
    failWrites = true
    val failedBuy = economy.purchaseVehicle(HeroVehicleCatalog.MUSCLE_ID, "buy_fail_io")
    assertTrue(failedBuy is GarageMutationResult.Rejected)
    val postFail = economy.currentSnapshot()
    assertEquals(1200L, postFail.confirmedBalance)
    assertFalse(postFail.isOwned(HeroVehicleCatalog.MUSCLE_ID))
    assertEquals(SaveHealthStatus.WRITE_FAILED_UNCONFIRMED, postFail.saveStatus)

    // Storage recovers -> purchase succeeds cleanly
    failWrites = false
    val okBuy = economy.purchaseVehicle(HeroVehicleCatalog.MUSCLE_ID, "buy_retry_io")
    assertTrue(okBuy is GarageMutationResult.Succeeded)
    assertEquals(200L, economy.currentSnapshot().confirmedBalance)
    assertTrue(economy.currentSnapshot().isOwned(HeroVehicleCatalog.MUSCLE_ID))
    assertEquals(SaveHealthStatus.DURABLE_CONFIRMED, economy.currentSnapshot().saveStatus)
  }

  @Test
  fun `all 4 bundled hero GLBs verify SHA-256 and adapt cleanly while Nissan remains import-required until valid SAF pick`() {
    val app = ApplicationProvider.getApplicationContext<Application>()
    File(app.filesDir, HeroAssetRepository.IMPORT_DIR_NAME).deleteRecursively()
    val repo = HeroAssetRepository(app)

    val bundledIds =
      listOf(
        HeroVehicleCatalog.STARTER_ID,
        HeroVehicleCatalog.SUPER_RACING_ID,
        HeroVehicleCatalog.MUSCLE_ID,
        HeroVehicleCatalog.LEGENDARY_ID,
      )
    for (id in bundledIds) {
      val spec = HeroVehicleCatalog.findById(id)!!
      val loaded = repo.loadHeroModel(id)
      assertEquals(HeroModelOrigin.BUNDLED_OFFLINE_GLB, loaded.origin)
      assertTrue("SHA-256 should verify for $id", loaded.sha256Verified)
      val bytes = loaded.glbBytes
      assertNotNull("GLB bytes must exist for $id", bytes)

      val prepared = HeroGlbRuntimeAdapter.prepareGlbForFilament(spec, bytes!!)
      // glTF magic bytes "glTF"
      val magic = String(prepared.glbBytes.copyOfRange(0, 4), StandardCharsets.US_ASCII)
      assertEquals("glTF", magic)
      assertTrue("Isolated paint materials expected for $id", prepared.paintMaterialNames.isNotEmpty())
      if (id == HeroVehicleCatalog.MUSCLE_ID) {
        assertEquals(listOf("__DRIP_CARRIER_0__", "__DRIP_CARRIER_1__"), prepared.carrierNodeNames)
      }
      if (id == HeroVehicleCatalog.LEGENDARY_ID) {
        assertEquals(4, prepared.wheelNodeNames.size)
      }
    }

    // Hyper Sports (Nissan) MUST report import-required (never fake bundled)
    val nissanStatus = repo.loadHeroModel(HeroVehicleCatalog.HYPER_SPORTS_ID)
    assertEquals(HeroModelOrigin.IMPORT_REQUIRED_PROCEDURAL_FALLBACK, nissanStatus.origin)
    assertFalse(nissanStatus.sha256Verified)

    // Test SAF validation preserving an earlier valid file in imported_vehicles/ when bad import arrives
    val importDir = File(app.filesDir, HeroAssetRepository.IMPORT_DIR_NAME).apply { mkdirs() }
    val starterBundled = repo.loadHeroModel(HeroVehicleCatalog.STARTER_ID).glbBytes!!
    val seededFile = File(importDir, HeroVehicleCatalog.STARTER_SPEC.fileName).apply { writeBytes(starterBundled) }
    assertTrue(repo.hasValidImportedFile(HeroVehicleCatalog.STARTER_ID))

    // Bad URI pointing to invalid bytes -> Rejected & preserves earlier valid file & does not unlock Nissan
    val tempBad = File(app.cacheDir, "invalid_nissan.glb").apply { writeText("NOT_A_GLB") }
    val badResult = repo.importHeroGlbFromUri(HeroVehicleCatalog.STARTER_ID, Uri.fromFile(tempBad))
    assertTrue(badResult is HeroImportResult.Rejected)
    assertTrue((badResult as HeroImportResult.Rejected).previousValidPreserved)
    assertTrue(seededFile.exists())
    assertTrue(repo.hasValidImportedFile(HeroVehicleCatalog.STARTER_ID))

    // Valid SAF file test against Starter SHA -> Imported cleanly without affecting Economy ownership
    val tempGood = File(app.cacheDir, "valid_starter.glb").apply { writeBytes(starterBundled) }
    val goodResult = repo.importHeroGlbFromUri(HeroVehicleCatalog.STARTER_ID, Uri.fromFile(tempGood))
    assertTrue(goodResult is HeroImportResult.Imported)
  }

  @Test
  fun `vehicle-aware traffic fairness and reachability scale safely for wide Muscle and 322_5 kmh Legendary`() {
    val musclePhysics = HeroVehicleCatalog.findById(HeroVehicleCatalog.MUSCLE_ID)!!.computeEffectivePhysics()
    val maxLegendPhysics =
      HeroVehicleCatalog.findById(HeroVehicleCatalog.LEGENDARY_ID)!!.computeEffectivePhysics(
        VehicleUpgradeLevels(3, 3, 3, 3)
      )

    // Wider Muscle car (1.98 m, 4.97 m) configures active simulation bounds
    val startSim = DrivingSimulation(enableTraffic = true, enablePickups = false)
    startSim.configureActiveVehicle(musclePhysics, resetState = true)
    assertEquals(1.98f, startSim.config.collisionWidthMeters, 1e-6f)
    assertEquals(4.97f, startSim.config.collisionLengthMeters, 1e-6f)

    // 3-lane blockade MUST be rejected regardless of vehicle speed
    val activeNeighbors =
      listOf(
        ActiveObstacleSnapshot(
          slotIndex = 0,
          logicalId = 1L,
          archetypeId = "bus",
          isStaticHazard = false,
          trafficSpec = null,
          hazardKind = null,
          laneIndex = 0,
          x = -3.6f,
          widthMeters = 1.9f,
          heightMeters = 1.5f,
          lengthMeters = 4.5f,
          relativeZ = 150.0,
          speedMetersPerSecond = 20.0,
          wheelRollRadians = 0.0,
        ),
        ActiveObstacleSnapshot(
          slotIndex = 1,
          logicalId = 2L,
          archetypeId = "suv",
          isStaticHazard = false,
          trafficSpec = null,
          hazardKind = null,
          laneIndex = 2,
          x = 3.6f,
          widthMeters = 1.9f,
          heightMeters = 1.5f,
          lengthMeters = 4.5f,
          relativeZ = 150.0,
          speedMetersPerSecond = 20.0,
          wheelRollRadians = 0.0,
        ),
      )
    val wallCenterCandidate = EncounterCandidate(1, 150.0, 1.9f, 1.5f, 4.5f, 20.0, false)
    assertFalse(
      CollisionAndReachabilityMath.isCandidateFairAndReachable(
        candidate = wallCenterCandidate,
        activeObstacles = activeNeighbors,
        playerTargetLane = 1,
        playerSpeedMps = maxLegendPhysics.topSpeedKmh / 3.6,
        vehicleConfig = maxLegendPhysics,
        minReactionTimeSeconds = 1.35,
      )
    )

    // Open adjacent lanes at 175 m ahead must remain fair & reachable even for 322.5 km/h Legendary
    val openLaneCandidate = EncounterCandidate(1, 175.0, 1.9f, 1.5f, 4.5f, 22.0, false)
    assertTrue(
      CollisionAndReachabilityMath.isCandidateFairAndReachable(
        candidate = openLaneCandidate,
        activeObstacles = emptyList(),
        playerTargetLane = 1,
        playerSpeedMps = (maxLegendPhysics.topSpeedKmh * 1.25) / 3.6,
        vehicleConfig = maxLegendPhysics,
        minReactionTimeSeconds = 1.35,
      )
    )
  }

  @Test
  fun `compose Robolectric CUJ covers Home, Garage showroom, locked preview non-persistence, purchase, paint, upgrade, and Race HUD gauge`() {
    val (vm, renderer, _) = createIsolatedPhase5ViewModel(useRealAssetsHeroRepo = false)
    vm.economyService.grantNonCoinReward("test_grant", 2000L)

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
          onGarageRace = vm::onGarageRaceClicked,
          onGarageSelectVehicle = vm::onGarageSelectFocusVehicle,
          onGarageSelectPaint = vm::onGarageSelectPaintColor,
          onGarageRequestUnlock = vm::onGarageRequestUnlockVehicle,
          onGarageConfirmUnlock = vm::onGarageConfirmUnlockVehicle,
          onGarageCancelUnlock = vm::onGarageCancelUnlockVehicle,
          onGarageEquip = vm::onGarageEquipFocusedVehicle,
          onGarageUpgrade = vm::onGarageUpgradeTrack,
          onImportNissanHeroGlb = {},
          onShowroomOrbitDelta = vm::onShowroomOrbitDelta,
          onShowroomZoomDelta = vm::onShowroomZoomDelta,
          onShowroomResetView = vm::onShowroomResetView,
          onBackFromRunning = { vm.onBackPressed() },
          onSteerLeft = vm::onSteerLeft,
          onSteerRight = vm::onSteerRight,
          onBrakePointerDown = vm::onBrakePointerDown,
          onBrakePointerUpOrCancel = vm::onBrakePointerUpOrCancel,
          onNitroPointerDown = vm::onNitroPointerDown,
          onNitroPointerUpOrCancel = vm::onNitroPointerUpOrCancel,
          onBrakeKeyChanged = vm::onBrakeKeyChanged,
          onNitroKeyChanged = vm::onNitroKeyChanged,
          onAttachSurface = vm::attachSurfaceView,
          onDetachSurface = vm::detachSurfaceView,
          render3DSurface = false,
        )
      }
    }

    composeTestRule.onNodeWithTag("garage_button").assertIsDisplayed().performClick()
    assertTrue(vm.uiState.value.phase is GamePhase.Garage)
    composeTestRule.onNodeWithTag("garage_wallet_text").assertTextContains("2000")

    // Zoom & Reset view buttons work cleanly
    composeTestRule.onNodeWithTag("garage_zoom_in_button").performClick()
    composeTestRule.onNodeWithTag("garage_reset_view_button").performClick()

    // Select locked Hyper Sports (Nissan): shows IMPORT REQUIRED, Upgrade disabled, paint is preview-only
    composeTestRule.onNodeWithTag("garage_car_hyper_sports").performScrollTo().performClick()
    composeTestRule.onNodeWithTag("garage_model_origin_text").performScrollTo().assertTextContains("Import required")
    composeTestRule.onNodeWithTag("garage_import_nissan_button").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("garage_upgrade_engine").performScrollTo().assertIsNotEnabled()
    composeTestRule.onNodeWithTag("garage_paint_racing_yellow").performScrollTo().performClick()

    // Exit to Home -> equipped stays Starter, locked Nissan paint never persisted!
    composeTestRule.onNodeWithTag("garage_home_button").performClick()
    assertTrue(vm.uiState.value.phase is GamePhase.Home)
    assertEquals(HeroVehicleCatalog.STARTER_ID, vm.economyService.currentSnapshot().selectedVehicleId)
    assertEquals("pearl_white", vm.economyService.currentSnapshot().paintIdFor(HeroVehicleCatalog.HYPER_SPORTS_ID))

    // Back into Garage -> buy Super Racing (500) -> paint Electric Blue -> upgrade Engine Lv 1 (175) -> Race!
    composeTestRule.onNodeWithTag("garage_button").performClick()
    composeTestRule.onNodeWithTag("garage_car_super_racing").performScrollTo().performClick()
    composeTestRule.onNodeWithTag("garage_unlock_button").performScrollTo().performClick()
    composeTestRule.onNodeWithTag("garage_confirm_unlock_button").performScrollTo().performClick()
    assertEquals(1500L, vm.economyService.currentSnapshot().confirmedBalance)
    assertEquals(HeroVehicleCatalog.SUPER_RACING_ID, vm.economyService.currentSnapshot().selectedVehicleId)

    composeTestRule.onNodeWithTag("garage_paint_electric_blue").performScrollTo().performClick()
    assertEquals("electric_blue", vm.economyService.currentSnapshot().paintIdFor(HeroVehicleCatalog.SUPER_RACING_ID))
    composeTestRule.onNodeWithTag("garage_upgrade_engine").performScrollTo().performClick()
    assertEquals(1325L, vm.economyService.currentSnapshot().confirmedBalance)
    assertEquals(1, vm.economyService.currentSnapshot().upgradesFor(HeroVehicleCatalog.SUPER_RACING_ID).engine)
    assertEquals(206.0, vm.simulation.config.topSpeedKmh, 1e-6)

    // Launch Race directly from Garage -> check HUD speedometer bar & active car label!
    composeTestRule.onNodeWithTag("garage_race_button").performClick()
    assertTrue(vm.uiState.value.phase is GamePhase.Running)
    composeTestRule.onNodeWithTag("hud_active_car_text").assertTextContains("Super Racing Car")
    composeTestRule.onNodeWithTag("hud_speedometer_bar").assertIsDisplayed()

    // Pause -> Resume -> Restart -> Home cycle keeps renderer clean & zero corrupt transitions
    composeTestRule.onNodeWithTag("pause_button").performClick()
    assertTrue(vm.uiState.value.phase is GamePhase.Paused)
    composeTestRule.onNodeWithTag("resume_button").performClick()
    assertTrue(vm.uiState.value.phase is GamePhase.Running)
    composeTestRule.onNodeWithTag("pause_button").performClick()
    composeTestRule.onNodeWithTag("home_button").performClick()
    assertTrue(vm.uiState.value.phase is GamePhase.Home)
    assertTrue(renderer.paintHistory.contains("electric_blue"))
    assertTrue(renderer.showroomHistory.any { it.first } && renderer.showroomHistory.any { !it.first })
  }

  @Test
  fun `showroom camera frames vehicle smaller above portrait menu and toggle button collapses menu`() {
    // Portrait framing: pulled back distance + downward target Y lifts car into upper half above menu
    val portraitPose = CoordinateMapper.computeShowroomCameraPose(widthPx = 1080, heightPx = 2400)
    val portraitDistance =
      kotlin.math.sqrt(
        portraitPose.eyeX * portraitPose.eyeX +
          portraitPose.eyeY * portraitPose.eyeY +
          portraitPose.eyeZ * portraitPose.eyeZ
      )
    assertTrue("Portrait showroom camera should pull back for side margin", portraitDistance >= 10.0)
    assertTrue("Portrait look target must sit below car to raise car above menu", portraitPose.centerY < -1.0)
    assertEquals(56.0, portraitPose.verticalFovDegrees, 1e-6)

    // Landscape framing: shifts target camera-right to center car in open left showroom stage
    val landscapePose = CoordinateMapper.computeShowroomCameraPose(widthPx = 2400, heightPx = 1080)
    assertTrue("Landscape showroom target shifts left-screen clear of right menu", landscapePose.centerX > 1.0)
    assertEquals(42.0, landscapePose.verticalFovDegrees, 1e-6)

    val (vm, _, _) = createIsolatedPhase5ViewModel(useRealAssetsHeroRepo = false)
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
          onGarageRace = vm::onGarageRaceClicked,
          onGarageSelectVehicle = vm::onGarageSelectFocusVehicle,
          onGarageSelectPaint = vm::onGarageSelectPaintColor,
          onGarageRequestUnlock = vm::onGarageRequestUnlockVehicle,
          onGarageConfirmUnlock = vm::onGarageConfirmUnlockVehicle,
          onGarageCancelUnlock = vm::onGarageCancelUnlockVehicle,
          onGarageEquip = vm::onGarageEquipFocusedVehicle,
          onGarageUpgrade = vm::onGarageUpgradeTrack,
          onImportNissanHeroGlb = {},
          onShowroomOrbitDelta = vm::onShowroomOrbitDelta,
          onShowroomZoomDelta = vm::onShowroomZoomDelta,
          onShowroomResetView = vm::onShowroomResetView,
          onBackFromRunning = { vm.onBackPressed() },
          onSteerLeft = vm::onSteerLeft,
          onSteerRight = vm::onSteerRight,
          onBrakePointerDown = vm::onBrakePointerDown,
          onBrakePointerUpOrCancel = vm::onBrakePointerUpOrCancel,
          onNitroPointerDown = vm::onNitroPointerDown,
          onNitroPointerUpOrCancel = vm::onNitroPointerUpOrCancel,
          onBrakeKeyChanged = vm::onBrakeKeyChanged,
          onNitroKeyChanged = vm::onNitroKeyChanged,
          onAttachSurface = vm::attachSurfaceView,
          onDetachSurface = vm::detachSurfaceView,
          render3DSurface = false,
        )
      }
    }

    composeTestRule.onNodeWithTag("garage_button").performClick()
    composeTestRule.onNodeWithTag("garage_car_starter_sports").assertExists()
    // Collapse menu -> car drawer disappears for unobstructed full-car inspection
    composeTestRule.onNodeWithTag("garage_toggle_menu_button").performClick()
    composeTestRule.onNodeWithTag("garage_car_starter_sports").assertDoesNotExist()
    // Expand menu back
    composeTestRule.onNodeWithTag("garage_toggle_menu_button").performClick()
    composeTestRule.onNodeWithTag("garage_car_starter_sports").assertExists()
  }

  @Test
  fun `crash-free SH studio IBL and in-memory GLB PBR tuning prevent black metallic=1_0 cars without recoloring glass or tires`() {
    val sh = com.example.render.StudioIblBuilder.STUDIO_DAYLIGHT_SH
    assertEquals(27, sh.size)
    assertTrue(sh.all { it.isFinite() })

    // Verify 3-band SH irradiance is strictly positive (> 0.55) across all 6 cardinal normals (+Y, -Y, +X, -X, +Z, -Z)
    val cardinals =
      listOf(
        floatArrayOf(0f, 1f, 0f),
        floatArrayOf(0f, -1f, 0f),
        floatArrayOf(1f, 0f, 0f),
        floatArrayOf(-1f, 0f, 0f),
        floatArrayOf(0f, 0f, 1f),
        floatArrayOf(0f, 0f, -1f),
      )
    for (n in cardinals) {
      val nx = n[0]; val ny = n[1]; val nz = n[2]
      for (ch in 0..2) {
        val irr =
          sh[0 + ch] +
            sh[3 + ch] * ny +
            sh[6 + ch] * nz +
            sh[9 + ch] * nx +
            sh[12 + ch] * (nx * ny) +
            sh[15 + ch] * (ny * nz) +
            sh[18 + ch] * (3f * nz * nz - 1f) +
            sh[21 + ch] * (nx * nz) +
            sh[24 + ch] * (nx * nx - ny * ny)
        assertTrue("SH irradiance must remain bright (>0.55), got $irr", irr > 0.55f)
      }
    }

    // Showroom key/fill vectors shine downward and oppose each other laterally/longitudinally
    val keyDir = com.example.render.StudioIblBuilder.computeShowroomKeyDirection(36f)
    val fillDir = com.example.render.StudioIblBuilder.computeShowroomFillDirection(36f)
    assertTrue(keyDir[1] < -0.5f && fillDir[1] < -0.4f)
    val horizDot = keyDir[0] * fillDir[0] + keyDir[2] * fillDir[2]
    assertTrue("Key and fill lights should illuminate opposing flanks", horizDot < 0f)

    // Verify all 4 bundled hero GLBs have paint metallicFactor <= 0.26 (never 1.0 black-out)
    // and non-paint baseColorFactor values (glass, tires, lights) remain untouched.
    val app = ApplicationProvider.getApplicationContext<Application>()
    val repo = HeroAssetRepository(app)
    fun readGlbJsonMaterials(glbBytes: ByteArray): org.json.JSONArray {
      val bb = java.nio.ByteBuffer.wrap(glbBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
      bb.int; bb.int; bb.int
      val jsonLen = bb.int
      bb.int
      val jsonBytes = ByteArray(jsonLen)
      bb.get(jsonBytes)
      return org.json.JSONObject(String(jsonBytes, StandardCharsets.UTF_8)).getJSONArray("materials")
    }

    val bundledIds =
      listOf(
        HeroVehicleCatalog.STARTER_ID,
        HeroVehicleCatalog.SUPER_RACING_ID,
        HeroVehicleCatalog.MUSCLE_ID,
        HeroVehicleCatalog.LEGENDARY_ID,
      )
    for (id in bundledIds) {
      val spec = HeroVehicleCatalog.findById(id)!!
      val rawBytes = repo.loadHeroModel(id).glbBytes!!
      val rawMats = readGlbJsonMaterials(rawBytes)
      val prepared = HeroGlbRuntimeAdapter.prepareGlbForFilament(spec, rawBytes)
      val tunedMats = readGlbJsonMaterials(prepared.glbBytes)

      for (matIdx in 0 until tunedMats.length()) {
        val rawPbr = rawMats.getJSONObject(matIdx).optJSONObject("pbrMetallicRoughness")
        val tunedMat = tunedMats.getJSONObject(matIdx)
        val tunedPbr = tunedMat.optJSONObject("pbrMetallicRoughness")
        val isUnlit = tunedMat.optJSONObject("extensions")?.has("KHR_materials_unlit") == true
        if (matIdx in spec.paintMaterialIndices) {
          assertNotNull(tunedPbr)
          val met = tunedPbr!!.getDouble("metallicFactor")
          assertTrue("Hero $id paint[$matIdx] metallicFactor must be <= 0.26, got $met", met <= 0.2601)
        } else if (!isUnlit && tunedPbr != null) {
          val met = tunedPbr.optDouble("metallicFactor", 1.0)
          assertTrue("Hero $id trim[$matIdx] metallicFactor must be <= 0.35, got $met", met <= 0.3501)
          // Verify non-paint baseColorFactor was NOT recolored
          assertEquals(
            rawPbr?.optJSONArray("baseColorFactor")?.toString(),
            tunedPbr.optJSONArray("baseColorFactor")?.toString(),
          )
        }
      }
    }

    // Verify all 5 bundled traffic GLBs normalize metallicFactor <= 0.22 while keeping BIN image bytes 100% intact
    val (trafficLoaded, _) = com.example.assets.TrafficAssetRepository(app).loadAllTrafficAssets()
    val bundledTraffic = trafficLoaded.filter { it.glbBytes != null }
    assertTrue("Expected bundled traffic GLBs to be loaded", bundledTraffic.size >= 5)
    fun readGlbBinLen(glbBytes: ByteArray): Int {
      val bb = java.nio.ByteBuffer.wrap(glbBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
      bb.int; bb.int
      val total = bb.int
      val jsonLen = bb.int
      bb.position(20 + jsonLen)
      return if (bb.position() + 8 <= total) bb.int else 0
    }
    for (traffic in bundledTraffic) {
      val rawBytes = traffic.glbBytes!!
      val normalized = HeroGlbRuntimeAdapter.normalizeGlbMaterialsForDirectIbl(rawBytes)
      assertEquals(
        "BIN chunk length (all PNG/JPEG textures) must stay identical for ${traffic.spec.id}",
        readGlbBinLen(rawBytes),
        readGlbBinLen(normalized),
      )
      val mats = readGlbJsonMaterials(normalized)
      for (mIdx in 0 until mats.length()) {
        val mObj = mats.getJSONObject(mIdx)
        val isUnlit = mObj.optJSONObject("extensions")?.has("KHR_materials_unlit") == true
        val pbr = mObj.optJSONObject("pbrMetallicRoughness")
        if (!isUnlit && pbr != null) {
          val met = pbr.optDouble("metallicFactor", 1.0)
          assertTrue(
            "Traffic ${traffic.spec.id} mat[$mIdx] metallicFactor must be <= 0.22, got $met",
            met <= 0.2201,
          )
        }
      }
    }
  }

  @Test
  fun `muscle car GLB repairs unindexed primitives, synthesizes orthonormal VEC4 tangents, and strips unsupported ubershader extensions`() {
    val app = ApplicationProvider.getApplicationContext<Application>()
    val repo = HeroAssetRepository(app)
    val muscleSpec = HeroVehicleCatalog.findById(HeroVehicleCatalog.MUSCLE_ID)!!
    val rawBytes = repo.loadHeroModel(HeroVehicleCatalog.MUSCLE_ID).glbBytes!!

    fun unpackGlb(bytes: ByteArray): Pair<org.json.JSONObject, ByteArray> {
      val bb = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
      bb.int; bb.int
      val total = bb.int
      val jsonLen = bb.int
      bb.int
      val jBytes = ByteArray(jsonLen)
      bb.get(jBytes)
      val root = org.json.JSONObject(String(jBytes, StandardCharsets.UTF_8))
      val bin =
        if (bb.position() + 8 <= total) {
          val binLen = bb.int
          bb.int
          ByteArray(binLen).also { bb.get(it) }
        } else {
          ByteArray(0)
        }
      return root to bin
    }

    val (rawRoot, _) = unpackGlb(rawBytes)
    val rawMeshes = rawRoot.getJSONArray("meshes")
    var rawUnindexedCount = 0
    for (m in 0 until rawMeshes.length()) {
      val prims = rawMeshes.getJSONObject(m).getJSONArray("primitives")
      for (p in 0 until prims.length()) {
        val prim = prims.getJSONObject(p)
        if (!prim.has("indices")) rawUnindexedCount++
      }
    }
    assertTrue("Raw Crimson GLB has unindexed body/fender primitives", rawUnindexedCount >= 8)

    val bluePaint = PaintCatalog.findById("electric_blue")!!
    val prepared =
      HeroGlbRuntimeAdapter.prepareGlbForFilament(
        spec = muscleSpec,
        rawGlbBytes = rawBytes,
        initialPaintLinearRgb = bluePaint.linearRgb,
      )
    val (prepRoot, prepBin) = unpackGlb(prepared.glbBytes)
    val prepBinBuf = java.nio.ByteBuffer.wrap(prepBin).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    val prepMeshes = prepRoot.getJSONArray("meshes")
    val prepAccessors = prepRoot.getJSONArray("accessors")
    val prepViews = prepRoot.getJSONArray("bufferViews")
    val prepMaterials = prepRoot.getJSONArray("materials")

    // 1. Every triangle primitive now has explicit indices and valid unit orthonormal VEC4 TANGENTs
    var checkedPrimitives = 0
    for (m in 0 until prepMeshes.length()) {
      val prims = prepMeshes.getJSONObject(m).getJSONArray("primitives")
      for (p in 0 until prims.length()) {
        val prim = prims.getJSONObject(p)
        if (prim.optInt("mode", 4) != 4) continue
        checkedPrimitives++
        assertTrue("Prepared Muscle primitive[$m:$p] must have indices", prim.has("indices"))
        val attrs = prim.getJSONObject("attributes")
        if (attrs.has("NORMAL")) {
          assertTrue("Prepared Muscle primitive[$m:$p] must have TANGENT", attrs.has("TANGENT"))
          val normAcc = prepAccessors.getJSONObject(attrs.getInt("NORMAL"))
          val tanAcc = prepAccessors.getJSONObject(attrs.getInt("TANGENT"))
          assertEquals("VEC4", tanAcc.getString("type"))
          assertEquals(5126, tanAcc.getInt("componentType"))
          assertEquals(normAcc.getInt("count"), tanAcc.getInt("count"))

          val nBv = prepViews.getJSONObject(normAcc.getInt("bufferView"))
          val tBv = prepViews.getJSONObject(tanAcc.getInt("bufferView"))
          val nOff = nBv.optInt("byteOffset", 0) + normAcc.optInt("byteOffset", 0)
          val tOff = tBv.optInt("byteOffset", 0) + tanAcc.optInt("byteOffset", 0)
          val nx = prepBinBuf.getFloat(nOff)
          val ny = prepBinBuf.getFloat(nOff + 4)
          val nz = prepBinBuf.getFloat(nOff + 8)
          val tx = prepBinBuf.getFloat(tOff)
          val ty = prepBinBuf.getFloat(tOff + 4)
          val tz = prepBinBuf.getFloat(tOff + 8)
          val tw = prepBinBuf.getFloat(tOff + 12)
          val tLen = kotlin.math.sqrt(tx * tx + ty * ty + tz * tz)
          val dot = kotlin.math.abs(nx * tx + ny * ty + nz * tz)
          assertEquals(1.0f, tLen, 1e-3f)
          assertEquals(1.0f, tw, 1e-4f)
          assertTrue("Tangent must be orthogonal to normal, got dot=$dot", dot < 1e-2f)
        }
      }
    }
    assertEquals(230, checkedPrimitives)

    // 2. Zero unsupported Ubershader extensions remain, and paint mat[0] has baked initial paint RGB
    val unsupported = setOf("KHR_materials_sheen", "KHR_materials_anisotropy", "KHR_materials_iridescence")
    for (i in 0 until prepMaterials.length()) {
      val mat = prepMaterials.getJSONObject(i)
      val exts = mat.optJSONObject("extensions") ?: continue
      for (bad in unsupported) {
        assertFalse("Material[$i] must not retain $bad", exts.has(bad))
      }
      if (exts.has("KHR_materials_clearcoat")) {
        assertFalse("Clearcoat material[$i] must not retain KHR_materials_ior", exts.has("KHR_materials_ior"))
        assertFalse("Clearcoat material[$i] must not retain KHR_materials_volume", exts.has("KHR_materials_volume"))
      }
    }
    val paintMat0 = prepMaterials.getJSONObject(0)
    assertFalse("Clearcoat paint mat[0] drops conflicting base normalTexture", paintMat0.has("normalTexture"))
    val bakedFactor = paintMat0.getJSONObject("pbrMetallicRoughness").getJSONArray("baseColorFactor")
    assertEquals(bluePaint.linearRgb[0].toDouble(), bakedFactor.getDouble(0), 1e-5)
    assertEquals(bluePaint.linearRgb[1].toDouble(), bakedFactor.getDouble(1), 1e-5)
    assertEquals(bluePaint.linearRgb[2].toDouble(), bakedFactor.getDouble(2), 1e-5)
  }
}
