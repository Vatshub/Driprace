package com.example

import android.app.Application
import android.content.Context
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.example.assets.BestScoreRepository
import com.example.assets.BundledAsphaltMaps
import com.example.assets.StarterAssetContract
import com.example.assets.StarterAssetRepository
import com.example.assets.TrafficAssetRepository
import com.example.economy.AtomicFileProgressionPersistence
import com.example.economy.EconomyService
import com.example.economy.EconomyTransactionResult
import com.example.economy.PersistenceLoadOutcome
import com.example.economy.PersistenceWriteOutcome
import com.example.economy.ProgressionPersistencePort
import com.example.economy.SaveEnvelope
import com.example.economy.SaveHealthStatus
import com.example.render.ProceduralPickupGlbBuilder
import com.example.render.ProceduralRoadGlbBuilder
import com.example.sim.CoinTier
import com.example.sim.CoinWeightConfig
import com.example.sim.DrivingInputSnapshot
import com.example.sim.DrivingSimulation
import com.example.sim.EncounterCandidate
import com.example.sim.PickupCollectedEvent
import com.example.sim.PickupSimulation
import com.example.sim.PowerUpType
import com.example.traffic.StaticHazardKind
import com.example.ui.DripRaceRootScreen
import com.example.ui.theme.DripRaceTheme
import java.io.File
import kotlin.math.abs
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DripRacePhase4Test {

  @get:Rule val composeTestRule = createComposeRule()

  private fun createIsolatedPhase4ViewModel(
    customEconomy: EconomyService? = null,
  ): Triple<com.example.state.DripRaceViewModel, DripRacePhase1Test.FakeRendererPort, AtomicFileProgressionPersistence> {
    val app = ApplicationProvider.getApplicationContext<Application>()
    app.getSharedPreferences("drip_race_phase3_scores", Context.MODE_PRIVATE).edit().clear().commit()
    File(app.filesDir, AtomicFileProgressionPersistence.DIR_NAME).deleteRecursively()

    val persistence = AtomicFileProgressionPersistence(app)
    val bestScoreRepo = BestScoreRepository(app)
    val economy =
      customEconomy ?: EconomyService(persistence = persistence, bestScoreRepository = bestScoreRepo)

    val vm = com.example.state.DripRaceViewModel(app, SavedStateHandle())
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
    vm.overrideDependenciesForTest(
      repository = fakeRepo,
      renderer = fakeRenderer,
      trafficRepository = TrafficAssetRepository(app),
      scoreRepository = bestScoreRepo,
      customEconomyService = economy,
    )
    vm.onActivityStartedOrResumed(active = true)
    return Triple(vm, fakeRenderer, persistence)
  }

  @Test
  fun `coin catalog values, 70_25_5 weights, and procedural 3D pickup GLBs match contract`() {
    assertEquals(10, CoinTier.REGULAR.baseValue)
    assertEquals(70, CoinTier.REGULAR.defaultSpawnWeightPercent)
    assertEquals(25, CoinTier.BONUS.baseValue)
    assertEquals(25, CoinTier.BONUS.defaultSpawnWeightPercent)
    assertEquals(50, CoinTier.RARE.baseValue)
    assertEquals(5, CoinTier.RARE.defaultSpawnWeightPercent)

    val weights = CoinWeightConfig()
    assertEquals(100, weights.totalWeight)
    val rng = Random(4242L)
    val counts = mutableMapOf(CoinTier.REGULAR to 0, CoinTier.BONUS to 0, CoinTier.RARE to 0)
    val sampleSize = 10_000
    repeat(sampleSize) {
      val picked = weights.pickWeightedTier(rng)
      counts[picked] = counts.getValue(picked) + 1
    }
    assertTrue(counts.getValue(CoinTier.REGULAR) in 6700..7300)
    assertTrue(counts.getValue(CoinTier.BONUS) in 2250..2750)
    assertTrue(counts.getValue(CoinTier.RARE) in 350..650)

    // Verify all 3 coin tiers and 4 power-up procedural 3D GLBs build valid glTF 2.0 binaries
    CoinTier.entries.forEach { tier ->
      val glb = ProceduralPickupGlbBuilder.buildCoinGlb(tier)
      assertTrue(StarterAssetRepository.hasValidGlbHeader(glb))
    }
    PowerUpType.entries.forEach { type ->
      val glb = ProceduralPickupGlbBuilder.buildPowerUpGlb(type)
      assertTrue(StarterAssetRepository.hasValidGlbHeader(glb))
    }
  }

  @Test
  fun `swept 3D pickup detection, fair hazard-free placement, and bounded pool ID reuse work cleanly`() {
    val sim = DrivingSimulation(enableTraffic = false, enablePickups = false)
    // Ramp player up to 50 m/s -> moves 0.833 m per 60 Hz tick
    repeat(400) { sim.stepFixedTick(DrivingInputSnapshot()) }

    // Fair placement rejects spawning on top of a static barrier in lane 1
    val hazardId =
      sim.traffic.trySpawnCandidate(
        candidate =
          EncounterCandidate(
            laneIndex = 1,
            relativeZ = 150.0,
            widthMeters = StaticHazardKind.ROAD_BARRIER.widthMeters,
            heightMeters = StaticHazardKind.ROAD_BARRIER.heightMeters,
            lengthMeters = StaticHazardKind.ROAD_BARRIER.lengthMeters,
            speedMetersPerSecond = 0.0,
            isStaticHazard = true,
            hazardKind = StaticHazardKind.ROAD_BARRIER,
          ),
        playerTargetLane = 1,
        playerSpeedMps = sim.state.speedMetersPerSecond,
        vehicleConfig = sim.config,
        enforceReachability = false,
      )!!
    assertTrue(hazardId > 0L)
    val obstacles = sim.traffic.activeSnapshots()
    assertEquals(null, sim.pickups.trySpawnCoin(CoinTier.REGULAR, laneIndex = 1, relativeZ = 152.0, activeObstacles = obstacles))
    assertEquals(null, sim.pickups.trySpawnPowerUp(PowerUpType.SHIELD, laneIndex = 1, relativeZ = 148.0, activeObstacles = obstacles))
    // Adjacent lane at 152 m IS fair
    val safeCoin = sim.pickups.trySpawnCoin(CoinTier.BONUS, laneIndex = 0, relativeZ = 152.0, activeObstacles = obstacles)
    assertTrue(safeCoin != null)

    // Swept lane-change pickup at boosted Nitro speed must not tunnel past coin in lane 0
    sim.resetForNewRace()
    repeat(400) { sim.stepFixedTick(DrivingInputSnapshot(nitroHeld = true)) }
    val fastCoin = sim.pickups.trySpawnCoin(CoinTier.RARE, laneIndex = 0, relativeZ = 26.0, enforceFairPlacement = false)!!
    assertTrue(fastCoin.logicalId > 0L)
    sim.requestLaneChange(-1)
    repeat(35) { sim.stepFixedTick(DrivingInputSnapshot(nitroHeld = true)) }
    val events = sim.drainCollectedPickupEvents()
    assertEquals(1, events.size)
    assertEquals(50, (events.first() as PickupCollectedEvent.CoinCollected).awardedAmount)

    // Bounded pool recycles slots cleanly with strictly fresh logicalIds & operationIds
    sim.resetForNewRace()
    val spawnedIds = mutableSetOf<Long>()
    val spawnedOpIds = mutableSetOf<String>()
    repeat(PickupSimulation.MAX_COIN_POOL_SIZE + 8) { idx ->
      val coin = sim.pickups.trySpawnCoin(CoinTier.REGULAR, laneIndex = idx % 3, relativeZ = -19.0 - idx, enforceFairPlacement = false)
      if (coin != null) {
        assertTrue(spawnedIds.add(coin.logicalId))
        assertTrue(spawnedOpIds.add(coin.operationId))
        sim.stepFixedTick(DrivingInputSnapshot()) // triggers recycle behind camera (-18m)
      }
    }
    assertTrue(sim.pickups.activeCoinCount() <= PickupSimulation.MAX_COIN_POOL_SIZE)
    assertTrue(spawnedIds.size >= PickupSimulation.MAX_COIN_POOL_SIZE + 5)
  }

  @Test
  fun `Magnet plus Double Coins Rare coin gives exact 100 currency, 1 physical pickup, and never duplicates`() {
    val app = ApplicationProvider.getApplicationContext<Application>()
    val persistence = AtomicFileProgressionPersistence(app)
    val economy = EconomyService(persistence, BestScoreRepository(app))

    val sim = DrivingSimulation(enableTraffic = false, enablePickups = false)
    sim.addPickupCollectedListener { event ->
      when (event) {
        is PickupCollectedEvent.CoinCollected -> {
          // Intentionally invoke twice to verify exact-once operationId protection!
          economy.creditCoinPickup(event.operationId, event.coinTier, event.multiplierApplied > 1)
          val dup = economy.creditCoinPickup(event.operationId, event.coinTier, event.multiplierApplied > 1)
          assertTrue(dup is EconomyTransactionResult.DuplicateIgnored)
        }
        is PickupCollectedEvent.PowerUpCollected -> {
          economy.recordPowerUpPickup(event.operationId)
        }
      }
    }

    // Bring player up to steady cruise speed
    repeat(400) { sim.stepFixedTick(DrivingInputSnapshot()) }

    // Activate Magnet (15s) and Double Coins (20s) together
    sim.activatePowerUp(PowerUpType.MAGNET)
    sim.activatePowerUp(PowerUpType.DOUBLE_COINS)
    assertEquals(15.0, sim.state.magnetRemainingSeconds, 1e-6)
    assertEquals(20.0, sim.state.doubleCoinsRemainingSeconds, 1e-6)

    // Repeat Double Coins pickup refreshes to 20s only — NEVER stacks 4x/8x!
    repeat(60) { sim.stepFixedTick(DrivingInputSnapshot()) }
    assertEquals(19.0, sim.state.doubleCoinsRemainingSeconds, 0.05)
    sim.activatePowerUp(PowerUpType.DOUBLE_COINS)
    assertEquals(20.0, sim.state.doubleCoinsRemainingSeconds, 1e-6)

    // Place a Rare (50) coin in adjacent lane 0 (3.6m lateral <= 5.5m magnet radius) ahead by 14m (<= 15m forward)
    val rareCoin = sim.pickups.trySpawnCoin(CoinTier.RARE, laneIndex = 0, relativeZ = 14.0, enforceFairPlacement = false)!!
    // Place another coin OUTSIDE magnet lateral check (impossible here across 3 lanes: 3.6m < 5.5m) -> test beyond 15m until entering 15m
    repeat(40) { sim.stepFixedTick(DrivingInputSnapshot()) }

    val events = sim.drainCollectedPickupEvents()
    assertEquals(1, events.size)
    val coinEvent = events.single() as PickupCollectedEvent.CoinCollected
    assertEquals(rareCoin.logicalId, coinEvent.logicalId)
    assertEquals(2, coinEvent.multiplierApplied)
    assertEquals(100, coinEvent.awardedAmount)
    assertTrue(coinEvent.collectedViaMagnet)

    assertEquals(100, sim.state.runCoinsEarned)
    assertEquals(1, sim.state.runCoinPickupsCollected)
    assertEquals(100L, economy.currentSnapshot().displayBalance)

    // Double Coins must NEVER multiply score or non-coin rewards!
    val rewardRes = economy.grantNonCoinReward("mission_daily_test_1", 75L)
    assertTrue(rewardRes is EconomyTransactionResult.Credited)
    assertEquals(75L, (rewardRes as EconomyTransactionResult.Credited).awardedAmount)
    assertEquals(175L, economy.currentSnapshot().displayBalance)
  }

  @Test
  fun `Automatic Nitro lasts 3s, refreshes cleanly, never stacks multiplier or drains manual meter, and Brake cancels`() {
    val sim = DrivingSimulation(enableTraffic = false, enablePickups = false)
    // Bring car to base top speed 180 km/h (50 m/s)
    repeat(600) { sim.stepFixedTick(DrivingInputSnapshot()) }
    assertEquals(50.0, sim.state.speedMetersPerSecond, 1e-6)

    // Drain manual Nitro partway so meter is ~72
    repeat(60) { sim.stepFixedTick(DrivingInputSnapshot(nitroHeld = true)) }
    val meterBeforeAuto = sim.state.nitroMeter
    assertTrue(meterBeforeAuto in 70.0..75.0)
    assertEquals(1, sim.state.qualifiedManualNitroUses)

    // Activate 3s Automatic Nitro AND hold manual Nitro key -> must NOT stack speed above 225 km/h,
    // must NOT drain manual meter, and must NOT increment qualifiedManualNitroUses!
    sim.activatePowerUp(PowerUpType.AUTOMATIC_NITRO)
    repeat(90) { sim.stepFixedTick(DrivingInputSnapshot(nitroHeld = true)) } // 1.5s
    assertTrue(sim.state.isAutoNitroActive)
    assertFalse(sim.state.isManualNitroActive)
    assertEquals(1.5, sim.state.autoNitroRemainingSeconds, 0.05)
    assertEquals(meterBeforeAuto, sim.state.nitroMeter, 1e-6)
    assertTrue(sim.state.speedKmh <= 225.0 + 1e-5)
    assertEquals(1, sim.state.qualifiedManualNitroUses)

    // Refresh duration mid-boost: resets back to 3.0s (never stacks to 4.5s or 6.0s)
    sim.activatePowerUp(PowerUpType.AUTOMATIC_NITRO)
    assertEquals(3.0, sim.state.autoNitroRemainingSeconds, 1e-6)

    // Pressing Brake immediately cancels Automatic Nitro
    repeat(10) { sim.stepFixedTick(DrivingInputSnapshot(brakeHeld = true)) }
    assertFalse(sim.state.isAutoNitroActive)
    assertEquals(0.0, sim.state.autoNitroRemainingSeconds, 1e-9)
    assertTrue(sim.state.speedKmh < 225.0)
  }

  @Test
  fun `Shield caps at 1 charge, expires in 20s, refreshes, blocks one hit with grace, and all 4 power-ups coexist`() {
    val sim = DrivingSimulation(enableTraffic = false, enablePickups = false)
    repeat(400) { sim.stepFixedTick(DrivingInputSnapshot()) }

    // Coexistence of all 4 power-up types
    sim.activatePowerUp(PowerUpType.AUTOMATIC_NITRO)
    sim.activatePowerUp(PowerUpType.MAGNET)
    sim.activatePowerUp(PowerUpType.SHIELD)
    sim.activatePowerUp(PowerUpType.DOUBLE_COINS)
    assertEquals(1, sim.state.shieldCharges)
    assertTrue(sim.state.autoNitroRemainingSeconds > 2.9)
    assertTrue(sim.state.magnetRemainingSeconds > 14.9)
    assertTrue(sim.state.shieldRemainingSeconds > 19.9)
    assertTrue(sim.state.doubleCoinsRemainingSeconds > 19.9)

    // Repeat Shield pickup caps at 1 charge and refreshes timer back to 20s
    repeat(120) { sim.stepFixedTick(DrivingInputSnapshot()) } // 2s
    assertEquals(18.0, sim.state.shieldRemainingSeconds, 0.05)
    sim.activatePowerUp(PowerUpType.SHIELD)
    assertEquals(1, sim.state.shieldCharges)
    assertEquals(20.0, sim.state.shieldRemainingSeconds, 1e-6)

    // Impact consumes shield + grants ~1s collision grace without heart loss
    sim.traffic.trySpawnCandidate(
      candidate =
        EncounterCandidate(
          laneIndex = 1,
          relativeZ = 3.0,
          widthMeters = StaticHazardKind.ROAD_BARRIER.widthMeters,
          heightMeters = StaticHazardKind.ROAD_BARRIER.heightMeters,
          lengthMeters = StaticHazardKind.ROAD_BARRIER.lengthMeters,
          speedMetersPerSecond = 0.0,
          isStaticHazard = true,
          hazardKind = StaticHazardKind.ROAD_BARRIER,
        ),
      playerTargetLane = 1,
      playerSpeedMps = sim.state.speedMetersPerSecond,
      vehicleConfig = sim.config,
      enforceReachability = false,
    )
    repeat(15) { sim.stepFixedTick(DrivingInputSnapshot()) }
    assertEquals(3, sim.state.hearts)
    assertEquals(0, sim.state.shieldCharges)
    assertEquals(0.0, sim.state.shieldRemainingSeconds, 1e-9)
    assertEquals(1, sim.state.shieldBlocksCount)
    assertTrue(sim.state.collisionGraceRemainingSeconds > 0.5)

    // Unhit Shield expires cleanly at 20.0s of active simulation time
    sim.activatePowerUp(PowerUpType.SHIELD)
    repeat(1205) { sim.stepFixedTick(DrivingInputSnapshot()) } // 20.08s
    assertEquals(0, sim.state.shieldCharges)
    assertEquals(0.0, sim.state.shieldRemainingSeconds, 1e-9)
  }

  @Test
  fun `durable save, pending vs confirmed spend gate, write failure honesty, and corrupt or backup recovery`() {
    val app = ApplicationProvider.getApplicationContext<Application>()
    app.getSharedPreferences("drip_race_phase3_scores", Context.MODE_PRIVATE).edit().clear().commit()
    File(app.filesDir, AtomicFileProgressionPersistence.DIR_NAME).deleteRecursively()

    val legacyScores = BestScoreRepository(app)
    legacyScores.recordRunScore(777)

    // 1. Failing persistence blocks spending unconfirmed pending credit and reports WRITE_FAILED_UNCONFIRMED
    var shouldFailWrites = true
    val realPersistence = AtomicFileProgressionPersistence(app)
    val togglePersistence =
      object : ProgressionPersistencePort {
        override fun loadValidated(): PersistenceLoadOutcome = realPersistence.loadValidated()

        override fun writeAtomicWithBackup(envelope: SaveEnvelope): PersistenceWriteOutcome =
          if (shouldFailWrites) {
            PersistenceWriteOutcome.Failure("Simulated disk full")
          } else {
            realPersistence.writeAtomicWithBackup(envelope)
          }
      }

    val economy = EconomyService(togglePersistence, legacyScores)
    assertEquals(777, economy.currentSnapshot().bestScore)

    economy.creditCoinPickup("op_coin_1", CoinTier.RARE, doubleCoinsActive = true) // +100 pending
    val pendingSnap = economy.currentSnapshot()
    assertEquals(0L, pendingSnap.confirmedBalance)
    assertEquals(100L, pendingSnap.pendingCredit)
    assertEquals(100L, pendingSnap.displayBalance)
    assertTrue(pendingSnap.hasUnconfirmedPending)

    // Attempt to spend pending balance while writes fail -> MUST reject spending unconfirmed credit!
    val rejectedSpend = economy.tryDebitConfirmedBalance("buy_car_1", 50L)
    assertTrue(rejectedSpend is EconomyTransactionResult.Rejected)
    assertEquals(SaveHealthStatus.WRITE_FAILED_UNCONFIRMED, economy.currentSnapshot().saveStatus)
    assertTrue(economy.currentSnapshot().lastSaveError?.contains("disk full", ignoreCase = true) == true)

    // 2. Restore healthy disk -> flush confirms balance and preserves bestScore across full reload
    shouldFailWrites = false
    assertTrue(economy.flushPending())
    assertEquals(100L, economy.currentSnapshot().confirmedBalance)
    assertEquals(0L, economy.currentSnapshot().pendingCredit)
    assertEquals(SaveHealthStatus.DURABLE_CONFIRMED, economy.currentSnapshot().saveStatus)

    val debitOk = economy.tryDebitConfirmedBalance("buy_upgrade_1", 40L)
    assertTrue(debitOk is EconomyTransactionResult.Debited)
    assertEquals(60L, economy.currentSnapshot().confirmedBalance)

    // Reload fresh EconomyService -> confirms durable balance + best score + opId idempotency
    val reloaded = EconomyService(realPersistence, legacyScores)
    assertEquals(60L, reloaded.currentSnapshot().confirmedBalance)
    assertEquals(777, reloaded.currentSnapshot().bestScore)
    val replayAttempt = reloaded.creditCoinPickup("op_coin_1", CoinTier.RARE, doubleCoinsActive = true)
    assertTrue(replayAttempt is EconomyTransactionResult.DuplicateIgnored)
    assertEquals(60L, reloaded.currentSnapshot().confirmedBalance)

    // 3. Write another coin so backup has rev(60) and primary has rev(85), then corrupt primary -> recovers backup!
    reloaded.creditCoinPickup("op_coin_2", CoinTier.BONUS, doubleCoinsActive = false)
    assertTrue(reloaded.flushPending())
    realPersistence.primaryFile.writeText("{broken_json_corrupt_primary")
    val backupRecovered = EconomyService(realPersistence, legacyScores)
    assertEquals(SaveHealthStatus.RECOVERED_FROM_BACKUP, backupRecovered.currentSnapshot().saveStatus)
    assertEquals(60L, backupRecovered.currentSnapshot().confirmedBalance)
    assertEquals(777, backupRecovered.currentSnapshot().bestScore)

    // 4. Corrupt both primary and backup -> quarantines safely & preserves legacy BestScore with honest warning
    realPersistence.primaryFile.writeText("{\"format\":\"BAD\",\"schemaVersion\":99}")
    realPersistence.backupFile.writeText("corrupt-backup")
    val corruptRecovered = EconomyService(realPersistence, legacyScores)
    assertEquals(SaveHealthStatus.CORRUPT_RESET_WARNING, corruptRecovered.currentSnapshot().saveStatus)
    assertEquals(0L, corruptRecovered.currentSnapshot().confirmedBalance)
    assertEquals(777, corruptRecovered.currentSnapshot().bestScore)
  }

  @Test
  fun `pause freezes power-ups and coins, Game Over never recredits, and Compose HUD updates cleanly`() {
    val (vm, fakeRenderer, _) = createIsolatedPhase4ViewModel()

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
          onImportSedanGlb = {},
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
    composeTestRule.onNodeWithTag("home_wallet_text").assertTextContains("WALLET: 0")
    composeTestRule.onNodeWithTag("play_button").performClick()
    composeTestRule.waitForIdle()

    // Drive forward 3s and activate Magnet + Double Coins, then collect a spawned Rare (50) coin -> +100
    repeat(180) { fakeRenderer.simulateRenderFrame(1.0 / 60.0) }
    vm.simulation.activatePowerUp(PowerUpType.MAGNET)
    vm.simulation.activatePowerUp(PowerUpType.DOUBLE_COINS)
    vm.simulation.activatePowerUp(PowerUpType.SHIELD)
    vm.simulation.pickups.trySpawnCoin(CoinTier.RARE, laneIndex = 0, relativeZ = 10.0, enforceFairPlacement = false)
    repeat(30) { fakeRenderer.simulateRenderFrame(1.0 / 60.0) }
    composeTestRule.waitForIdle()

    assertTrue(vm.hudTelemetry.value.runCoinsEarned >= 100)
    assertTrue(vm.hudTelemetry.value.runCoinPickupsCollected >= 1)
    assertEquals(vm.hudTelemetry.value.runCoinsEarned.toLong(), vm.hudTelemetry.value.walletBalance)
    composeTestRule.onNodeWithTag("hud_wallet_text").assertIsDisplayed()
    composeTestRule.onNodeWithTag("hud_run_coins_text").assertIsDisplayed()
    composeTestRule.onNodeWithTag("hud_powerups_row").assertIsDisplayed()

    // Pause MUST freeze power-up countdowns and coins
    val magBeforePause = vm.simulation.state.magnetRemainingSeconds
    val doubleBeforePause = vm.simulation.state.doubleCoinsRemainingSeconds
    val walletBeforePause = vm.hudTelemetry.value.walletBalance
    vm.onPauseClicked()
    composeTestRule.waitForIdle()
    repeat(180) { fakeRenderer.simulateRenderFrame(1.0 / 60.0) }
    assertEquals(magBeforePause, vm.simulation.state.magnetRemainingSeconds, 1e-9)
    assertEquals(doubleBeforePause, vm.simulation.state.doubleCoinsRemainingSeconds, 1e-9)
    assertEquals(walletBeforePause, vm.economyService.currentSnapshot().confirmedBalance)

    // Resume and drive into 4 hazards (first breaks shield, next 3 drain 3 hearts -> Game Over once)
    composeTestRule.onNodeWithTag("resume_button").performScrollTo().performClick()
    composeTestRule.waitForIdle()
    repeat(4) { idx ->
      if (idx > 0) {
        repeat(70) { fakeRenderer.simulateRenderFrame(1.0 / 60.0) }
      }
      vm.simulation.traffic.trySpawnCandidate(
        candidate =
          EncounterCandidate(
            laneIndex = vm.simulation.state.targetLaneIndex,
            relativeZ = 4.0,
            widthMeters = StaticHazardKind.ROAD_BARRIER.widthMeters,
            heightMeters = StaticHazardKind.ROAD_BARRIER.heightMeters,
            lengthMeters = StaticHazardKind.ROAD_BARRIER.lengthMeters,
            speedMetersPerSecond = 0.0,
            isStaticHazard = true,
            hazardKind = StaticHazardKind.ROAD_BARRIER,
          ),
        playerTargetLane = vm.simulation.state.targetLaneIndex,
        playerSpeedMps = vm.simulation.state.speedMetersPerSecond,
        vehicleConfig = vm.simulation.config,
        enforceReachability = false,
      )
      repeat(20) { fakeRenderer.simulateRenderFrame(1.0 / 60.0) }
    }
    composeTestRule.waitForIdle()

    val gameOver = vm.uiState.value.phase as com.example.state.GamePhase.GameOver
    val earnedAtGameOver = vm.simulation.state.runCoinsEarned
    assertTrue(earnedAtGameOver >= 100)
    assertEquals(earnedAtGameOver, gameOver.runCoinsEarned)
    // Game Over NEVER re-credits run coins!
    assertEquals(earnedAtGameOver.toLong(), vm.economyService.currentSnapshot().confirmedBalance)
    vm.publishTelemetryFromSimForTest()
    repeat(60) { fakeRenderer.simulateRenderFrame(1.0 / 60.0) }
    assertEquals(earnedAtGameOver.toLong(), vm.economyService.currentSnapshot().confirmedBalance)

    composeTestRule.onNodeWithTag("game_over_run_coins_text").assertIsDisplayed()
    composeTestRule.onNodeWithTag("game_over_wallet_text").assertTextContains("Wallet Balance: $earnedAtGameOver")
    composeTestRule.onNodeWithTag("game_over_save_status_text").assertTextContains("Confirmed durable", substring = true)

    // Play Again clears run earnings & power-ups while preserving permanent wallet
    composeTestRule.onNodeWithTag("play_again_button").performScrollTo().performClick()
    composeTestRule.waitForIdle()
    assertEquals(0, vm.hudTelemetry.value.runCoinsEarned)
    assertEquals(0, vm.hudTelemetry.value.runCoinPickupsCollected)
    assertEquals(0.0, vm.hudTelemetry.value.doubleCoinsRemainingSeconds, 1e-9)
    assertEquals(earnedAtGameOver.toLong(), vm.hudTelemetry.value.walletBalance)

    // Home preserves confirmed wallet cleanly
    vm.onPauseClicked()
    vm.onHomeClicked()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("home_wallet_text").assertTextContains("WALLET: $earnedAtGameOver")
  }
}
