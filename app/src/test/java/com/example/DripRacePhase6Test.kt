package com.example

import android.app.Application
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.example.assets.BestScoreRepository
import com.example.assets.BundledAsphaltMaps
import com.example.assets.HeroAssetRepository
import com.example.assets.StarterAssetRepository
import com.example.assets.TrafficAssetRepository
import com.example.economy.AtomicFileProgressionPersistence
import com.example.economy.EconomyService
import com.example.economy.GarageMutationResult
import com.example.economy.PersistenceLoadOutcome
import com.example.economy.PersistenceWriteOutcome
import com.example.economy.PlayerProgression
import com.example.economy.ProgressionMutationResult
import com.example.economy.ProgressionPersistencePort
import com.example.economy.SaveEnvelope
import com.example.economy.SaveHealthStatus
import com.example.garage.HeroVehicleCatalog
import com.example.garage.UpgradeTrack
import com.example.garage.VehicleUpgradeLevels
import com.example.progression.AchievementType
import com.example.progression.DailyMissionType
import com.example.progression.EnvironmentLevelSpec
import com.example.progression.MissionStatus
import com.example.progression.ProgressionCatalog
import com.example.render.EngineRendererPort
import com.example.sim.CoinTier
import com.example.sim.DrivingInputSnapshot
import com.example.sim.DrivingSimState
import com.example.sim.DrivingSimulation
import com.example.sim.PowerUpType
import com.example.state.DripRaceViewModel
import com.example.state.GamePhase
import com.example.ui.DripRaceRootScreen
import com.example.ui.theme.DripRaceTheme
import java.io.File
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone
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
class DripRacePhase6Test {

  @get:Rule val composeTestRule = createComposeRule()

  private class Phase6TrackingRenderer : EngineRendererPort {
    private val delegate = DripRacePhase1Test.FakeRendererPort()
    val appliedEnvironments = mutableListOf<String>()

    override fun loadSceneAssets(
      starterGlbBytes: ByteArray,
      asphaltMaps: BundledAsphaltMaps,
      onViewportChanged: (String) -> Unit,
    ) = delegate.loadSceneAssets(starterGlbBytes, asphaltMaps, onViewportChanged)

    override fun applyEnvironmentConfig(environment: EnvironmentLevelSpec) {
      appliedEnvironments += environment.id
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

    override fun destroy() = delegate.destroy()
  }

  private class ControllableMemoryPersistence(
    var storedJson: String? = null,
    var failWrites: Boolean = false,
  ) : ProgressionPersistencePort {
    override fun loadValidated(): PersistenceLoadOutcome {
      val raw = storedJson ?: return PersistenceLoadOutcome.EmptyNoSaveYet
      return SaveEnvelope.parseAndValidate(raw).fold(
        onSuccess = { PersistenceLoadOutcome.LoadedPrimary(it) },
        onFailure = { PersistenceLoadOutcome.CorruptUnrecoverable(it.message ?: "corrupt") },
      )
    }

    override fun writeAtomicWithBackup(envelope: SaveEnvelope): PersistenceWriteOutcome {
      if (failWrites) {
        return PersistenceWriteOutcome.Failure("Simulated storage I/O failure")
      }
      val json = SaveEnvelope.toJsonString(envelope)
      SaveEnvelope.parseAndValidate(json).getOrThrow()
      storedJson = json
      return PersistenceWriteOutcome.Success
    }
  }

  private fun epochMillis(
    zoneId: String,
    year: Int,
    month: Int,
    day: Int,
    hour: Int,
    minute: Int,
    second: Int = 0,
  ): Long {
    val cal = GregorianCalendar(TimeZone.getTimeZone(zoneId), Locale.US)
    cal.set(year, month - 1, day, hour, minute, second)
    cal.set(java.util.Calendar.MILLISECOND, 0)
    return cal.timeInMillis
  }

  @Test
  fun contractTablesMatchFiveDailyMissionsEightAchievementsFiveLevelsAndSevenDayCalendar() {
    // 1. Five Daily Missions (sum = 375)
    assertEquals(5, ProgressionCatalog.DAILY_MISSIONS.size)
    assertEquals(375L, ProgressionCatalog.TOTAL_DAILY_MISSION_REWARDS)
    val missionsById = ProgressionCatalog.DAILY_MISSIONS.associateBy { it.id }
    assertEquals(100 to 50L, missionsById.getValue("coin_collector").let { it.targetValue to it.rewardCoins })
    assertEquals(2_000 to 75L, missionsById.getValue("highway_traveler").let { it.targetValue to it.rewardCoins })
    assertEquals(180 to 100L, missionsById.getValue("endurance_racer").let { it.targetValue to it.rewardCoins })
    assertEquals(20 to 75L, missionsById.getValue("traffic_expert").let { it.targetValue to it.rewardCoins })
    assertEquals(5 to 75L, missionsById.getValue("nitro_master").let { it.targetValue to it.rewardCoins })

    // 2. Eight Permanent Achievements
    assertEquals(8, ProgressionCatalog.ACHIEVEMENTS.size)
    val achById = ProgressionCatalog.ACHIEVEMENTS.associateBy { it.id }
    assertEquals(100 to 50L, achById.getValue("first_ride").let { it.targetValue to it.rewardCoins })
    assertEquals(10_000 to 100L, achById.getValue("road_explorer").let { it.targetValue to it.rewardCoins })
    assertEquals(100 to 125L, achById.getValue("traffic_specialist").let { it.targetValue to it.rewardCoins })
    assertEquals(25 to 100L, achById.getValue("close_call_expert").let { it.targetValue to it.rewardCoins })
    assertEquals(100 to 125L, achById.getValue("treasure_hunter").let { it.targetValue to it.rewardCoins })
    assertEquals(50 to 100L, achById.getValue("nitro_champion").let { it.targetValue to it.rewardCoins })
    assertEquals(3 to 150L, achById.getValue("garage_collector").let { it.targetValue to it.rewardCoins })
    assertEquals(5_000 to 200L, achById.getValue("marathon_racer").let { it.targetValue to it.rewardCoins })

    // 3. Five Free Unlockable Environments
    assertEquals(5, ProgressionCatalog.ENVIRONMENTS.size)
    val envThresholds = ProgressionCatalog.ENVIRONMENTS.map { it.id to it.requiredLifetimeMeters }
    assertEquals(
      listOf(
        "beginner_highway" to 0.0,
        "city_highway" to 2_000.0,
        "desert_highway" to 8_000.0,
        "mountain_highway" to 20_000.0,
        "night_highway" to 40_000.0,
      ),
      envThresholds,
    )
    // Distinct skybox RGB per environment
    assertEquals(5, ProgressionCatalog.ENVIRONMENTS.map { it.skyboxRgb.joinToString(",") }.toSet().size)

    // 4. Seven-Day Login Sequence (sum = 1,375)
    assertEquals(listOf(50L, 75L, 100L, 150L, 200L, 300L, 500L), ProgressionCatalog.DAILY_REWARD_SEQUENCE)
    assertEquals(1_375L, ProgressionCatalog.DAILY_REWARD_CYCLE_TOTAL)
  }

  @Test
  fun manualNitroQualificationRequires350msUninterruptedResourceDrainOncePerHoldAndExcludesAutoNitro() {
    val sim = DrivingSimulation(enableTraffic = false, enablePickups = false)

    // 1. Short tap: 20 ticks at 60 Hz = 0.3333 s (< 0.35 s) -> does NOT qualify
    repeat(20) {
      sim.stepFixedTick(DrivingInputSnapshot(nitroHeld = true))
    }
    assertEquals(0, sim.state.qualifiedManualNitroUses)

    // Release 1 tick -> resets continuous hold timer
    sim.stepFixedTick(DrivingInputSnapshot(nitroHeld = false))
    assertEquals(0, sim.state.qualifiedManualNitroUses)

    // 2. Exact 21 ticks at 60 Hz = 0.3500 s -> qualifies ONCE
    repeat(21) {
      sim.stepFixedTick(DrivingInputSnapshot(nitroHeld = true))
    }
    assertEquals(1, sim.state.qualifiedManualNitroUses)

    // Holding continuously for another 90 ticks (1.5 s more) still counts ONLY ONCE for this activation!
    repeat(90) {
      sim.stepFixedTick(DrivingInputSnapshot(nitroHeld = true))
    }
    assertEquals(1, sim.state.qualifiedManualNitroUses)

    // 3. Drain completely to lockout, then spam-tap while locked out / insufficient resource -> still 1
    repeat(200) {
      sim.stepFixedTick(DrivingInputSnapshot(nitroHeld = true))
    }
    assertEquals(0.0, sim.state.nitroMeter, 1e-6)
    repeat(15) {
      sim.stepFixedTick(DrivingInputSnapshot(nitroHeld = true))
    }
    assertEquals(1, sim.state.qualifiedManualNitroUses)

    // 4. Release to let manual resource recharge above re-engage threshold, then hold >= 0.35 s -> 2
    repeat(120) {
      sim.stepFixedTick(DrivingInputSnapshot(nitroHeld = false))
    }
    assertTrue(sim.state.nitroMeter >= 20.0)
    repeat(21) {
      sim.stepFixedTick(DrivingInputSnapshot(nitroHeld = true))
    }
    assertEquals(2, sim.state.qualifiedManualNitroUses)
    sim.stepFixedTick(DrivingInputSnapshot(nitroHeld = false))

    // 5. Automatic Nitro power-up (even if player holds Nitro button during the 3 s auto boost!) -> NEVER increments manual count
    sim.activatePowerUp(PowerUpType.AUTOMATIC_NITRO)
    repeat(120) { // 2.0 s inside 3.0 s auto boost
      sim.stepFixedTick(DrivingInputSnapshot(nitroHeld = true))
    }
    assertEquals(2, sim.state.qualifiedManualNitroUses)
  }

  @Test
  fun physicalPickupsCollectibleCurrencyAndRewardCurrencyRemainStrictlyDistinct() {
    var now = epochMillis("UTC", 2026, 6, 1, 12, 0)
    val store = ControllableMemoryPersistence()
    val service = EconomyService(persistence = store, clockMillis = { now }, initialTimeZoneSupplier = { "UTC" })

    // 1. Claiming Day 1 login reward (+50) and First Ride (+50) must NOT advance Coin Collector or Treasure Hunter!
    val loginClaim = service.claimDailyLoginReward("login_d1")
    assertTrue(loginClaim is ProgressionMutationResult.Claimed)
    assertEquals(50L, service.currentSnapshot().confirmedBalance)

    service.checkpointRaceProgress(
      raceId = "r1",
      distanceMeters = 150.0,
      activeSeconds = 10.0,
      uniqueOvertakes = 0,
      uniqueNearMisses = 0,
      qualifiedManualNitroUses = 0,
      raceEnded = true,
    )
    val firstRideClaim = service.claimAchievement("first_ride", "ach_first_ride")
    assertTrue(firstRideClaim is ProgressionMutationResult.Claimed)

    val afterRewards = service.currentSnapshot()
    assertEquals(100L, afterRewards.confirmedBalance)
    assertEquals(100L, afterRewards.lifetimeRewardCoinsClaimed)
    assertEquals(0L, afterRewards.totalPhysicalCoinPickups)
    assertEquals(0L, afterRewards.totalCollectibleCurrencyEarned)
    val coinMissionBeforePickups =
      afterRewards.dailyMissions.first { it.spec.id == "coin_collector" }
    assertEquals(0, coinMissionBeforePickups.currentValue)
    assertEquals(MissionStatus.IN_PROGRESS, coinMissionBeforePickups.status)

    // 2. Collect ONE Rare physical coin (base 50) with Double Coins active -> +100 collectible units, +1 physical pickup!
    service.creditCoinPickup("pickup_gold_2x", CoinTier.RARE, doubleCoinsActive = true)
    service.flushPending()

    val afterOneDoubledGold = service.currentSnapshot()
    assertEquals(200L, afterOneDoubledGold.confirmedBalance)
    assertEquals(1L, afterOneDoubledGold.totalPhysicalCoinPickups) // 1 physical object!
    assertEquals(100L, afterOneDoubledGold.totalCollectibleCurrencyEarned) // 100 collectible units!
    assertEquals(100L, afterOneDoubledGold.lifetimeRewardCoinsClaimed) // Unchanged!

    // Coin Collector (100 collectible units) is now COMPLETED_UNCLAIMED, while Treasure Hunter (100 physical pickups) is 1/100 LOCKED!
    val coinMissionAfter =
      afterOneDoubledGold.dailyMissions.first { it.spec.id == "coin_collector" }
    assertEquals(100, coinMissionAfter.currentValue)
    assertEquals(MissionStatus.COMPLETED_UNCLAIMED, coinMissionAfter.status)

    val treasureHunter =
      afterOneDoubledGold.achievements.first { it.spec.id == "treasure_hunter" }
    assertEquals(1, treasureHunter.currentValue)
    assertFalse(treasureHunter.unlocked)
  }

  @Test
  fun allFiveDailyMissionsAndAllEightAchievementsCompleteClaimOnceAndRejectInvalidOrDuplicates() {
    val now = epochMillis("UTC", 2026, 6, 10, 14, 0)
    val store = ControllableMemoryPersistence()
    val service = EconomyService(persistence = store, clockMillis = { now }, initialTimeZoneSupplier = { "UTC" })

    // Invalid premature claims rejected
    assertTrue(service.claimDailyMission("coin_collector", "premature_m") is ProgressionMutationResult.Rejected)
    assertTrue(service.claimAchievement("first_ride", "premature_a") is ProgressionMutationResult.Rejected)
    assertEquals(0L, service.currentSnapshot().confirmedBalance)

    // Mid-race at 3,000 m (`raceEnded = false`): First Ride must NOT unlock until race ends!
    service.checkpointRaceProgress(
      raceId = "race_a",
      distanceMeters = 3_000.0,
      activeSeconds = 185.0,
      uniqueOvertakes = 25,
      uniqueNearMisses = 10,
      qualifiedManualNitroUses = 6,
      raceEnded = false,
    )
    assertFalse(
      service.currentSnapshot().achievements.first { it.spec.id == "first_ride" }.unlocked
    )

    // End race_a at 3,000 m: First Ride unlocks, Marathon Racer (5,000 m in ONE race) is still locked!
    service.checkpointRaceProgress(
      raceId = "race_a",
      distanceMeters = 3_000.0,
      activeSeconds = 185.0,
      uniqueOvertakes = 25,
      uniqueNearMisses = 10,
      qualifiedManualNitroUses = 6,
      raceEnded = true,
    )
    assertTrue(service.currentSnapshot().achievements.first { it.spec.id == "first_ride" }.unlocked)
    assertFalse(service.currentSnapshot().achievements.first { it.spec.id == "marathon_racer" }.unlocked)

    // Second race of 3,000 m brings lifetime distance to 6,000 m (> 5,000 m), still NO Marathon Racer because neither race reached 5,000 m!
    service.checkpointRaceProgress(
      raceId = "race_b",
      distanceMeters = 3_000.0,
      activeSeconds = 100.0,
      uniqueOvertakes = 30,
      uniqueNearMisses = 10,
      qualifiedManualNitroUses = 20,
      raceEnded = true,
    )
    assertEquals(6_000.0, service.currentSnapshot().lifetimeDistanceMeters, 1e-3)
    assertFalse(service.currentSnapshot().achievements.first { it.spec.id == "marathon_racer" }.unlocked)

    // Collect 100 physical Bonus coins (100 * 25 = 2,500 coins) -> completes Coin Collector AND Treasure Hunter!
    for (i in 1..100) {
      service.creditCoinPickup("coin_pick_$i", CoinTier.BONUS, doubleCoinsActive = false)
    }
    service.flushPending()
    assertEquals(2_500L, service.currentSnapshot().confirmedBalance)

    // Claim all 5 daily missions (+375 total)
    for (spec in ProgressionCatalog.DAILY_MISSIONS) {
      val res = service.claimDailyMission(spec.id, "claim_m_${spec.id}")
      assertTrue("Expected mission ${spec.id} claim success, got $res", res is ProgressionMutationResult.Claimed)
      // Duplicate opId -> DuplicateIgnored; new opId -> Rejected
      assertTrue(service.claimDailyMission(spec.id, "claim_m_${spec.id}") is ProgressionMutationResult.DuplicateIgnored)
      assertTrue(service.claimDailyMission(spec.id, "claim_m_dup_${spec.id}") is ProgressionMutationResult.Rejected)
    }
    assertEquals(2_875L, service.currentSnapshot().confirmedBalance)

    // Third race of 5,200 m -> brings lifetime to 11,200 m (>= 10,000 Road Explorer), single race >= 5,000 m (Marathon Racer),
    // overtakes to 105 (>= 100 Traffic Specialist), near misses to 26 (>= 25 Close Call Expert), manual nitros to 52 (>= 50 Nitro Champion)!
    service.checkpointRaceProgress(
      raceId = "race_c",
      distanceMeters = 5_200.0,
      activeSeconds = 200.0,
      uniqueOvertakes = 50,
      uniqueNearMisses = 6,
      qualifiedManualNitroUses = 26,
      raceEnded = true,
    )

    // Unlock 2 vehicles (500 + 1,000 = 1,500 coins) -> Starter + Super Racing + Muscle = 3 cars -> unlocks Garage Collector!
    assertTrue(
      service.purchaseVehicle(HeroVehicleCatalog.SUPER_RACING_ID, "buy_car_2") is GarageMutationResult.Succeeded
    )
    assertTrue(
      service.purchaseVehicle(HeroVehicleCatalog.MUSCLE_ID, "buy_car_3") is GarageMutationResult.Succeeded
    )
    assertEquals(2_875L - 1_500L, service.currentSnapshot().confirmedBalance) // 1,375L

    // Verify all 8 achievements are unlocked and claim all 8 (50+100+125+100+125+100+150+200 = 950 coins)!
    val snapBeforeAchClaims = service.currentSnapshot()
    assertEquals(8, snapBeforeAchClaims.achievements.count { it.unlocked })
    for (ach in ProgressionCatalog.ACHIEVEMENTS) {
      val claim = service.claimAchievement(ach.id, "claim_a_${ach.id}")
      assertTrue("Expected achievement ${ach.id} claim success, got $claim", claim is ProgressionMutationResult.Claimed)
      assertTrue(service.claimAchievement(ach.id, "claim_a_${ach.id}") is ProgressionMutationResult.DuplicateIgnored)
      assertTrue(service.claimAchievement(ach.id, "claim_a_second_${ach.id}") is ProgressionMutationResult.Rejected)
    }
    assertEquals(1_375L + 950L, service.currentSnapshot().confirmedBalance)
  }

  @Test
  fun atomicClaimPersistenceAbortsWhenWriteFailsAndSucceedsExactlyOnceOnSaveRetry() {
    val now = epochMillis("UTC", 2026, 7, 1, 9, 0)
    val store = ControllableMemoryPersistence()
    val service = EconomyService(persistence = store, clockMillis = { now }, initialTimeZoneSupplier = { "UTC" })

    // Complete Highway Traveler (2,000 m -> +75) and First Ride (+50)
    service.checkpointRaceProgress(
      raceId = "retry_race",
      distanceMeters = 2_100.0,
      activeSeconds = 95.0,
      uniqueOvertakes = 2,
      uniqueNearMisses = 1,
      qualifiedManualNitroUses = 1,
      raceEnded = true,
    )
    assertEquals(0L, service.currentSnapshot().confirmedBalance)

    // Inject disk write failure BEFORE claiming
    store.failWrites = true

    val failedMission = service.claimDailyMission("highway_traveler", "op_m_retry")
    val failedAch = service.claimAchievement("first_ride", "op_a_retry")
    val failedDaily = service.claimDailyLoginReward("op_d_retry")

    assertTrue(failedMission is ProgressionMutationResult.Rejected)
    assertTrue(failedAch is ProgressionMutationResult.Rejected)
    assertTrue(failedDaily is ProgressionMutationResult.Rejected)
    assertEquals(SaveHealthStatus.WRITE_FAILED_UNCONFIRMED, service.currentSnapshot().saveStatus)
    assertEquals(0L, service.currentSnapshot().confirmedBalance)
    assertEquals(
      MissionStatus.COMPLETED_UNCLAIMED,
      service.currentSnapshot().dailyMissions.first { it.spec.id == "highway_traveler" }.status,
    )
    assertTrue(service.currentSnapshot().achievements.first { it.spec.id == "first_ride" }.canClaim)
    assertTrue(service.currentSnapshot().dailyLogin!!.canClaimToday)

    // Restore storage health and retry with the SAME operationIds -> each credits exactly once!
    store.failWrites = false

    assertTrue(service.claimDailyMission("highway_traveler", "op_m_retry") is ProgressionMutationResult.Claimed)
    assertTrue(service.claimAchievement("first_ride", "op_a_retry") is ProgressionMutationResult.Claimed)
    assertTrue(service.claimDailyLoginReward("op_d_retry") is ProgressionMutationResult.Claimed)

    assertEquals(75L + 50L + 50L, service.currentSnapshot().confirmedBalance)
    assertEquals(SaveHealthStatus.DURABLE_CONFIRMED, service.currentSnapshot().saveStatus)

    // Reload into a brand-new EconomyService instance and verify atomic durability
    val reloaded = EconomyService(persistence = store, clockMillis = { now }, initialTimeZoneSupplier = { "UTC" }).currentSnapshot()
    assertEquals(175L, reloaded.confirmedBalance)
    assertEquals(
      MissionStatus.CLAIMED,
      reloaded.dailyMissions.first { it.spec.id == "highway_traveler" }.status,
    )
    assertTrue(reloaded.achievements.first { it.spec.id == "first_ride" }.claimed)
    assertTrue(reloaded.dailyLogin!!.alreadyClaimedToday)
  }

  @Test
  fun boundedHighWaterCheckpointsOnlyApplyUnaccountedDeltasAtGameOverAndAcrossRetries() {
    val now = epochMillis("UTC", 2026, 7, 5, 16, 0)
    val store = ControllableMemoryPersistence()
    val service = EconomyService(persistence = store, clockMillis = { now }, initialTimeZoneSupplier = { "UTC" })

    // Periodic 5-second checkpoints during race_hw
    service.checkpointRaceProgress("race_hw", 120.0, 5.0, 1, 0, 0, shieldBlocks = 0, raceEnded = false)
    service.checkpointRaceProgress("race_hw", 1_450.0, 50.0, 7, 2, 2, shieldBlocks = 1, raceEnded = false)
    service.checkpointRaceProgress("race_hw", 4_800.0, 165.0, 18, 5, 4, shieldBlocks = 2, raceEnded = false)

    // Game Over at 5,100 m / 175 s (plus duplicate Game Over / settle retries!)
    service.checkpointRaceProgress("race_hw", 5_100.0, 175.0, 20, 6, 5, shieldBlocks = 2, currentScore = 6_000, raceEnded = true)
    service.checkpointRaceProgress("race_hw", 5_100.0, 175.0, 20, 6, 5, shieldBlocks = 2, currentScore = 6_000, raceEnded = true)
    service.settleRunCompletion("run_complete_hw", finalScore = 6_000, runShieldBlocks = 2)
    service.settleRunCompletion("run_complete_hw", finalScore = 6_000, runShieldBlocks = 2)

    val snap = service.currentSnapshot()
    assertEquals(5_100.0, snap.lifetimeDistanceMeters, 1e-3)
    assertEquals(175.0, snap.lifetimeActiveRaceSeconds, 1e-3)
    assertEquals(1L, snap.lifetimeRacesCompleted)
    assertEquals(20L, snap.lifetimeUniqueOvertakes)
    assertEquals(6L, snap.lifetimeUniqueNearMisses)
    assertEquals(5L, snap.lifetimeQualifiedManualNitroUses)
    assertEquals(2L, snap.totalShieldBlocks)
  }

  @Test
  fun midnightRolloverMidRaceAndDuringPauseAcrossDstResetsDailySegmentAndExpiresUnclaimedDailyMissions() {
    // Invalid timezone falls back cleanly to UTC
    assertEquals("UTC", ProgressionCatalog.resolveValidTimeZoneId("Invalid/NoSuchZone"))

    // Test America/New_York across 2026-03-08 (23-hour DST spring-forward day!)
    var now = epochMillis("America/New_York", 2026, 3, 8, 23, 57, 0)
    val store = ControllableMemoryPersistence()
    val service =
      EconomyService(
        persistence = store,
        clockMillis = { now },
        initialTimeZoneSupplier = { "America/New_York" },
      )
    assertEquals("2026-03-08", service.currentSnapshot().currentDateKey)

    // Pre-midnight progress in race_midnight:
    // Complete Traffic Expert (20 overtakes) WITHOUT claiming; drive 140 active seconds (< 180 s)
    service.checkpointRaceProgress(
      raceId = "race_midnight",
      distanceMeters = 1_500.0,
      activeSeconds = 140.0,
      uniqueOvertakes = 20,
      uniqueNearMisses = 2,
      qualifiedManualNitroUses = 3,
      raceEnded = false,
    )
    assertEquals(
      MissionStatus.COMPLETED_UNCLAIMED,
      service.currentSnapshot().dailyMissions.first { it.spec.id == "traffic_expert" }.status,
    )

    // Cross midnight into 2026-03-09 00:00:30 EDT while race_midnight continues uninterrupted!
    now = epochMillis("America/New_York", 2026, 3, 9, 0, 0, 30)
    val midRollOutcome =
      service.checkpointRaceProgress(
        raceId = "race_midnight",
        distanceMeters = 2_100.0, // +600 m post-midnight
        activeSeconds = 190.0, // +50 s post-midnight (190 total race s, ONLY 50 s in new day!)
        uniqueOvertakes = 24, // +4 post-midnight
        uniqueNearMisses = 2,
        qualifiedManualNitroUses = 4, // +1 post-midnight
        raceEnded = false,
      )

    assertTrue(midRollOutcome.dateRolledOverMidRace)
    val newDaySnap = midRollOutcome.walletSnapshot
    assertEquals("2026-03-09", newDaySnap.currentDateKey)
    assertEquals("2026-03-08", newDaySnap.previousDayDateKey)

    // 1. Pre-midnight unclaimed Traffic Expert is EXPIRED and cannot be claimed!
    assertEquals(1, newDaySnap.previousDayExpiredMissions.size)
    assertEquals("traffic_expert", newDaySnap.previousDayExpiredMissions.first().spec.id)
    assertEquals(MissionStatus.EXPIRED, newDaySnap.previousDayExpiredMissions.first().status)
    assertTrue(service.claimDailyMission("traffic_expert", "claim_expired") is ProgressionMutationResult.Rejected)

    // 2. Post-midnight deltas assigned strictly to 2026-03-09, and Endurance Racer segment reset at midnight (50 s, NOT 190 s)!
    assertEquals(600, newDaySnap.dailyMissions.first { it.spec.id == "highway_traveler" }.currentValue)
    assertEquals(4, newDaySnap.dailyMissions.first { it.spec.id == "traffic_expert" }.currentValue)
    assertEquals(1, newDaySnap.dailyMissions.first { it.spec.id == "nitro_master" }.currentValue)
    val enduranceAfter50s = newDaySnap.dailyMissions.first { it.spec.id == "endurance_racer" }
    assertEquals(50, enduranceAfter50s.currentValue)
    assertEquals(MissionStatus.IN_PROGRESS, enduranceAfter50s.status)

    // 3. Survive another 130 active seconds in the SAME race (totalSimSeconds = 320.0 -> 180.0 s post-midnight!) -> Endurance Racer completes!
    now = epochMillis("America/New_York", 2026, 3, 9, 0, 3, 0)
    service.checkpointRaceProgress(
      raceId = "race_midnight",
      distanceMeters = 5_100.0,
      activeSeconds = 320.0,
      uniqueOvertakes = 25,
      uniqueNearMisses = 3,
      qualifiedManualNitroUses = 4,
      raceEnded = true,
    )
    assertEquals(
      MissionStatus.COMPLETED_UNCLAIMED,
      service.currentSnapshot().dailyMissions.first { it.spec.id == "endurance_racer" }.status,
    )
    // Marathon Racer unlocked permanently; verify it NEVER expires across subsequent midnights!
    assertTrue(service.currentSnapshot().achievements.first { it.spec.id == "marathon_racer" }.canClaim)

    // 4. Advance across 25-hour DST fall-back day (2026-11-01 in America/New_York):
    // 2026-11-01 00:30 EDT + 24 hours (86_400_000 ms) is 2026-11-01 23:30 EST -> STILL same calendar date!
    now = epochMillis("America/New_York", 2026, 11, 1, 0, 30, 0)
    service.syncCalendarClock()
    assertEquals("2026-11-01", service.currentSnapshot().currentDateKey)
    assertTrue(service.claimDailyLoginReward("dst_fall_d1") is ProgressionMutationResult.Claimed)

    now += 24L * 3_600_000L // +24h on a 25h DST day -> 23:30 EST on 2026-11-01!
    assertEquals("2026-11-01", service.syncCalendarClock().currentDateKey)
    assertTrue(service.claimDailyLoginReward("dst_fall_same_day") is ProgressionMutationResult.Rejected)

    now += 3_600_000L // +25h -> 00:30 EST on 2026-11-02 -> next consecutive calendar day!
    val nov2 = service.syncCalendarClock()
    assertEquals("2026-11-02", nov2.currentDateKey)
    assertEquals(2, nov2.dailyLogin!!.eligibleDayNumber)
    // Permanent achievement from March is STILL claimable in November!
    assertTrue(service.claimAchievement("marathon_racer", "claim_marathon_nov") is ProgressionMutationResult.Claimed)
  }

  @Test
  fun sevenDayLoginCalendarConsecutiveAdvanceCycleWrapSkippedDayResetAndClockRollbackProtection() {
    var now = epochMillis("Asia/Kolkata", 2026, 5, 1, 10, 0)
    val store = ControllableMemoryPersistence()
    val service =
      EconomyService(
        persistence = store,
        clockMillis = { now },
        initialTimeZoneSupplier = { "Asia/Kolkata" },
      )

    // Merely opening/querying never auto-claims
    assertEquals(0L, service.currentSnapshot().confirmedBalance)
    assertEquals(1, service.currentSnapshot().dailyLogin!!.eligibleDayNumber)

    // Claim Days 1..7 on consecutive calendar days -> 1,375 total coins
    val expectedRewards = listOf(50L, 75L, 100L, 150L, 200L, 300L, 500L)
    var expectedTotal = 0L
    for (day in 1..7) {
      now = epochMillis("Asia/Kolkata", 2026, 5, day, 10, 0)
      val snapBefore = service.currentSnapshot()
      assertEquals(day, snapBefore.dailyLogin!!.eligibleDayNumber)
      val claim = service.claimDailyLoginReward("login_seq_$day")
      assertTrue(claim is ProgressionMutationResult.Claimed)
      expectedTotal += expectedRewards[day - 1]
      assertEquals(expectedTotal, service.currentSnapshot().confirmedBalance)

      // Second claim on the same calendar date is strictly rejected
      assertTrue(service.claimDailyLoginReward("login_dup_same_day_$day") is ProgressionMutationResult.Rejected)
    }
    assertEquals(1_375L, service.currentSnapshot().confirmedBalance)

    // Next consecutive calendar day (May 8) cycles Day 7 -> Day 1 (50 coins)!
    now = epochMillis("Asia/Kolkata", 2026, 5, 8, 10, 0)
    assertEquals(1, service.currentSnapshot().dailyLogin!!.eligibleDayNumber)
    assertEquals(50L, service.currentSnapshot().dailyLogin!!.eligibleRewardCoins)
    assertTrue(service.claimDailyLoginReward("login_day8_cycle1") is ProgressionMutationResult.Claimed)
    assertEquals(1_425L, service.currentSnapshot().confirmedBalance)

    // Advance to May 9 -> Day 2 (75 coins)
    now = epochMillis("Asia/Kolkata", 2026, 5, 9, 10, 0)
    assertEquals(2, service.currentSnapshot().dailyLogin!!.eligibleDayNumber)
    assertTrue(service.claimDailyLoginReward("login_day9_d2") is ProgressionMutationResult.Claimed)
    assertEquals(1_500L, service.currentSnapshot().confirmedBalance)

    // Skip May 10 completely; open on May 11 -> resets to Day 1 (50 coins), NO coin deduction!
    now = epochMillis("Asia/Kolkata", 2026, 5, 11, 10, 0)
    val afterSkip = service.currentSnapshot()
    assertEquals(1_500L, afterSkip.confirmedBalance)
    assertTrue(afterSkip.dailyLogin!!.streakResetAfterSkip)
    assertEquals(1, afterSkip.dailyLogin!!.eligibleDayNumber)
    assertEquals(50L, afterSkip.dailyLogin!!.eligibleRewardCoins)

    // Roll device clock backward to May 10 -> clockRollbackDetected = true, claims blocked!
    now = epochMillis("Asia/Kolkata", 2026, 5, 10, 22, 0)
    val rolledBack = service.currentSnapshot()
    assertTrue(rolledBack.dailyLogin!!.clockRollbackDetected)
    assertFalse(rolledBack.dailyLogin!!.canClaimToday)
    assertTrue(service.claimDailyLoginReward("login_rollback_attempt") is ProgressionMutationResult.Rejected)
    assertEquals(1_500L, service.currentSnapshot().confirmedBalance)

    // Restore accurate forward date (May 12) -> claim succeeds cleanly!
    now = epochMillis("Asia/Kolkata", 2026, 5, 12, 11, 0)
    assertFalse(service.currentSnapshot().dailyLogin!!.clockRollbackDetected)
    assertTrue(service.claimDailyLoginReward("login_restored_may12") is ProgressionMutationResult.Claimed)
    assertEquals(1_550L, service.currentSnapshot().confirmedBalance)
  }

  @Test
  fun environmentUnlocksStarterCompatibilityNextRaceApplicationAndSchemaV2Migration() {
    val context = ApplicationProvider.getApplicationContext<Application>()
    val v2Payload =
      PlayerProgression(
        confirmedWalletBalance = 900L,
        bestScore = 4_200,
        totalPhysicalCoinPickups = 30L,
        totalCollectibleCurrencyEarned = 600L,
        ownedVehicleIds =
          listOf(
            HeroVehicleCatalog.STARTER_ID,
            HeroVehicleCatalog.SUPER_RACING_ID,
            HeroVehicleCatalog.MUSCLE_ID,
          ),
        selectedVehicleId = HeroVehicleCatalog.STARTER_ID,
        vehicleUpgrades =
          mapOf(HeroVehicleCatalog.STARTER_ID to VehicleUpgradeLevels(engine = 2, handling = 1)),
      )
    val v2Json =
      SaveEnvelope.toJsonString(
        SaveEnvelope(
          format = SaveEnvelope.FORMAT_ID,
          schemaVersion = SaveEnvelope.SCHEMA_VERSION_V2,
          revision = 4L,
          payload = v2Payload,
        )
      )
    val store = ControllableMemoryPersistence(storedJson = v2Json)
    var now = epochMillis("UTC", 2026, 8, 1, 12, 0)
    val economy = EconomyService(persistence = store, clockMillis = { now }, initialTimeZoneSupplier = { "UTC" })

    // Migrated v2 -> v3 preserves wallet, Starter equipped, upgrades count (3), and unlocks Garage Collector (3 owned cars)!
    val migrated = economy.currentSnapshot()
    assertEquals(900L, migrated.confirmedBalance)
    assertEquals(3L, migrated.lifetimeUpgradesPurchased)
    assertTrue(migrated.achievements.first { it.spec.id == "garage_collector" }.unlocked)

    // Wire ViewModel with tracking renderer to verify NO mid-run world swap!
    val renderer = Phase6TrackingRenderer()
    val vm = DripRaceViewModel(context, SavedStateHandle())
    vm.overrideDependenciesForTest(
      repository = StarterAssetRepository(context),
      renderer = renderer,
      trafficRepository = TrafficAssetRepository(context),
      scoreRepository = BestScoreRepository(context),
      customEconomyService = economy,
      heroRepository = HeroAssetRepository(context),
    )

    assertEquals(GamePhase.Home, vm.uiState.value.phase)
    assertEquals("beginner_highway", vm.uiState.value.activeSceneEnvironmentId)

    // Locked environment cannot be selected yet
    vm.onSelectEnvironment("city_highway")
    assertEquals("beginner_highway", vm.uiState.value.walletSnapshot.selectedEnvironmentId)

    // Start race with Starter Sports Car
    vm.onPlayClicked()
    assertEquals(GamePhase.Running, vm.uiState.value.phase)
    val envCallsAtRaceStart = renderer.appliedEnvironments.size

    // Cross 2,000 m mid-race via simulation checkpoint -> unlocks City Highway, does NOT swap active world!
    economy.checkpointRaceProgress("race_1", 2_500.0, 60.0, 5, 1, 1, raceEnded = false)
    vm.publishTelemetryFromSimForTest()
    assertTrue("city_highway" in vm.uiState.value.walletSnapshot.unlockedEnvironmentIds)
    assertEquals(envCallsAtRaceStart, renderer.appliedEnvironments.size)
    assertEquals("beginner_highway", vm.uiState.value.activeSceneEnvironmentId)

    // Pause race and select City Highway while paused -> saved for NEXT race, still NO mid-run swap!
    vm.onPauseClicked()
    assertEquals(GamePhase.Paused, vm.uiState.value.phase)
    vm.onSelectEnvironment("city_highway")
    assertEquals("city_highway", vm.uiState.value.walletSnapshot.selectedEnvironmentId)
    assertEquals("beginner_highway", vm.uiState.value.activeSceneEnvironmentId)
    assertEquals(envCallsAtRaceStart, renderer.appliedEnvironments.size)

    // Advance clock across midnight WHILE PAUSED -> simulation stays frozen while calendar advances!
    val pausedDist = vm.simulation.state.distanceMeters
    val pausedSimSec = vm.simulation.state.totalSimSeconds
    now = epochMillis("UTC", 2026, 8, 2, 0, 5)
    vm.onSyncCalendarClock()
    assertEquals("2026-08-02", vm.uiState.value.walletSnapshot.currentDateKey)
    assertEquals(pausedDist, vm.simulation.state.distanceMeters, 1e-9)
    assertEquals(pausedSimSec, vm.simulation.state.totalSimSeconds, 1e-9)

    // Restarting into a new race NOW applies City Highway with Starter Sports Car still equipped!
    vm.onRestartClicked()
    assertEquals(GamePhase.Running, vm.uiState.value.phase)
    assertEquals("city_highway", vm.uiState.value.activeSceneEnvironmentId)
    assertEquals("city_highway", renderer.appliedEnvironments.last())
    assertEquals(HeroVehicleCatalog.STARTER_ID, vm.uiState.value.walletSnapshot.selectedVehicleId)

    // Unlock remaining environments up to 40,000 m (Desert 8k, Mountain 20k, Night 40k) for FREE
    economy.checkpointRaceProgress("race_long", 40_500.0, 900.0, 20, 5, 5, raceEnded = true)
    val allFive = economy.currentSnapshot()
    assertEquals(5, allFive.unlockedEnvironmentIds.size)
    assertEquals(900L, allFive.confirmedBalance) // Zero coins spent on any environment unlock!
  }

  @Test
  fun composeProgressionHubUiJourneyClaimsRewardsSelectsLevelAndReturnsFromPauseToPaused() {
    val context = ApplicationProvider.getApplicationContext<Application>()
    val persistenceDir = File(context.filesDir, AtomicFileProgressionPersistence.DIR_NAME)
    persistenceDir.deleteRecursively()

    val now = epochMillis("UTC", 2026, 9, 15, 12, 0)
    val economy =
      EconomyService(
        persistence = AtomicFileProgressionPersistence(context),
        clockMillis = { now },
        initialTimeZoneSupplier = { "UTC" },
      )
    // Seed a completed daily mission + unlocked achievement + unlocked City Highway
    economy.checkpointRaceProgress(
      raceId = "seed_race",
      distanceMeters = 2_200.0,
      activeSeconds = 80.0,
      uniqueOvertakes = 4,
      uniqueNearMisses = 1,
      qualifiedManualNitroUses = 1,
      raceEnded = true,
    )

    val vm = DripRaceViewModel(context, SavedStateHandle())
    vm.overrideDependenciesForTest(
      repository = StarterAssetRepository(context),
      renderer = Phase6TrackingRenderer(),
      trafficRepository = TrafficAssetRepository(context),
      scoreRepository = BestScoreRepository(context),
      customEconomyService = economy,
      heroRepository = HeroAssetRepository(context),
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

    // 1. Exact branding preserved on Home
    composeTestRule.onNodeWithText("DRIP RACE").assertIsDisplayed()
    composeTestRule
      .onNodeWithText("A Property of Sound Of Drip Musictech Private Limited.")
      .assertIsDisplayed()

    // 2. Open Missions from Home & claim Highway Traveler (+75)
    composeTestRule.onNodeWithTag("missions_button").performScrollTo().performClick()
    composeTestRule.onNodeWithTag("progression_hub_overlay").assertIsDisplayed()
    composeTestRule.onNodeWithTag("mission_claim_highway_traveler").performScrollTo().assertIsEnabled()
    composeTestRule.onNodeWithTag("mission_claim_highway_traveler").performClick()
    composeTestRule.onNodeWithTag("mission_claim_highway_traveler").assertIsNotEnabled()
    assertEquals(75L, vm.uiState.value.confirmedWalletBalance)

    // 3. Switch to Achievements tab & claim First Ride (+50 -> 125)
    composeTestRule.onNodeWithTag("progression_tab_achievements").performScrollTo().performClick()
    composeTestRule.onNodeWithTag("achievement_claim_first_ride").performScrollTo().assertIsEnabled()
    composeTestRule.onNodeWithTag("achievement_claim_first_ride").performClick()
    assertEquals(125L, vm.uiState.value.confirmedWalletBalance)

    // 4. Switch to Levels tab & select unlocked City Highway
    composeTestRule.onNodeWithTag("progression_tab_levels").performScrollTo().performClick()
    composeTestRule.onNodeWithTag("environment_select_city_highway").performScrollTo().performClick()
    assertEquals("city_highway", vm.uiState.value.walletSnapshot.selectedEnvironmentId)

    // 5. Switch to Daily Rewards tab & claim Day 1 (+50 -> 175)
    composeTestRule.onNodeWithTag("progression_tab_daily_rewards").performScrollTo().performClick()
    composeTestRule.onNodeWithTag("daily_claim_button").performScrollTo().assertIsEnabled()
    composeTestRule.onNodeWithTag("daily_claim_button").performClick()
    composeTestRule.onNodeWithTag("daily_claim_button").assertIsNotEnabled()
    assertEquals(175L, vm.uiState.value.confirmedWalletBalance)

    // 6. Switch to Profile tab & verify lifetime stats render
    composeTestRule.onNodeWithTag("progression_tab_profile").performScrollTo().performClick()
    composeTestRule.onNodeWithTag("profile_lifetime_distance_text").performScrollTo().assertTextContains("2200.0 m")
    composeTestRule.onNodeWithTag("profile_reward_claimed_text").performScrollTo().assertTextContains("175 coins")

    // Close overlay back to Home
    composeTestRule.onNodeWithTag("progression_close_button").performClick()
    composeTestRule
      .onNodeWithTag("home_environment_text")
      .performScrollTo()
      .assertTextContains("City Highway", substring = true)

    // 7. Start race -> Pause -> Open Progression from Pause -> Close Progression -> MUST stay in Paused (NOT Running!)
    composeTestRule.onNodeWithTag("play_button").performScrollTo().performClick()
    assertEquals(GamePhase.Running, vm.uiState.value.phase)

    composeTestRule.onNodeWithTag("pause_button").performClick()
    assertEquals(GamePhase.Paused, vm.uiState.value.phase)

    composeTestRule.onNodeWithTag("paused_progression_button").performScrollTo().performClick()
    composeTestRule.onNodeWithTag("progression_hub_overlay").assertIsDisplayed()

    composeTestRule.onNodeWithTag("progression_close_button").performClick()
    assertEquals(GamePhase.Paused, vm.uiState.value.phase)
    composeTestRule.onNodeWithTag("resume_button").assertIsDisplayed()
  }
}
