package com.example.economy

import com.example.assets.BestScoreRepository
import com.example.audio.AudioSettingsState
import com.example.garage.HeroVehicleCatalog
import com.example.garage.PaintCatalog
import com.example.garage.UpgradeTrack
import com.example.garage.VehicleUpgradeLevels
import com.example.progression.AchievementItemStatus
import com.example.progression.AchievementType
import com.example.progression.DailyLoginStatus
import com.example.progression.DailyMissionItemStatus
import com.example.progression.DailyMissionSpec
import com.example.progression.DailyMissionType
import com.example.progression.EnvironmentItemStatus
import com.example.progression.MissionStatus
import com.example.progression.ProgressionCatalog
import com.example.scenery.GraphicsQualityPreset
import com.example.sim.CoinTier
import kotlin.math.floor
import kotlin.math.max

data class WalletSnapshot(
  val confirmedBalance: Long = 0L,
  val pendingCredit: Long = 0L,
  val bestScore: Int = 0,
  val totalPhysicalCoinPickups: Long = 0L,
  val totalCollectibleCurrencyEarned: Long = 0L,
  val totalPowerUpsCollected: Long = 0L,
  val totalShieldBlocks: Long = 0L,
  val revision: Long = 0L,
  val saveStatus: SaveHealthStatus = SaveHealthStatus.DURABLE_CONFIRMED,
  val lastSaveError: String? = null,
  val ownedVehicleIds: Set<String> = setOf(HeroVehicleCatalog.STARTER_ID),
  val selectedVehicleId: String = HeroVehicleCatalog.STARTER_ID,
  val vehiclePaintIds: Map<String, String> =
    mapOf(HeroVehicleCatalog.STARTER_ID to HeroVehicleCatalog.STARTER_SPEC.defaultPaintId),
  val vehicleUpgrades: Map<String, VehicleUpgradeLevels> = emptyMap(),
  // Phase 6 authoritative progression & profile snapshot
  val profileTimeZoneId: String = ProgressionCatalog.DEFAULT_FALLBACK_TIMEZONE,
  val currentDateKey: String = "",
  val lifetimeDistanceMeters: Double = 0.0,
  val lifetimeActiveRaceSeconds: Double = 0.0,
  val lifetimeRacesCompleted: Long = 0L,
  val longestSingleRaceDistanceMeters: Double = 0.0,
  val longestSingleRaceSurvivalSeconds: Double = 0.0,
  val lifetimeUniqueOvertakes: Long = 0L,
  val lifetimeUniqueNearMisses: Long = 0L,
  val lifetimeQualifiedManualNitroUses: Long = 0L,
  val lifetimeRewardCoinsClaimed: Long = 0L,
  val lifetimeUpgradesPurchased: Long = 0L,
  val lifetimeMissionsCompleted: Long = 0L,
  val lifetimeMissionsClaimed: Long = 0L,
  val lifetimeDailyRewardsClaimed: Long = 0L,
  val dailyMissions: List<DailyMissionItemStatus> = emptyList(),
  val previousDayDateKey: String = "",
  val previousDayExpiredMissions: List<DailyMissionItemStatus> = emptyList(),
  val expiredUnclaimedMissionCount: Long = 0L,
  val achievements: List<AchievementItemStatus> = emptyList(),
  val unlockedEnvironmentIds: Set<String> = setOf(ProgressionCatalog.BEGINNER_HIGHWAY_ID),
  val selectedEnvironmentId: String = ProgressionCatalog.BEGINNER_HIGHWAY_ID,
  val dailyLogin: DailyLoginStatus? = null,
  val graphicsQualityPresetId: String = GraphicsQualityPreset.AUTO.id,
  val audioSettings: AudioSettingsState = AudioSettingsState(),
) {
  val displayBalance: Long
    get() = confirmedBalance + pendingCredit

  val spendableBalance: Long
    get() = confirmedBalance

  val hasUnconfirmedPending: Boolean
    get() = pendingCredit > 0L

  val claimableMissionsCount: Int
    get() = dailyMissions.count { it.status == MissionStatus.COMPLETED_UNCLAIMED }

  val claimableAchievementsCount: Int
    get() = achievements.count { it.canClaim }

  val totalActionableClaimsCount: Int
    get() =
      claimableMissionsCount +
        claimableAchievementsCount +
        (if (dailyLogin?.canClaimToday == true) 1 else 0)

  fun isOwned(vehicleId: String): Boolean = vehicleId in ownedVehicleIds

  fun isEnvironmentUnlocked(environmentId: String): Boolean = environmentId in unlockedEnvironmentIds

  fun paintIdFor(vehicleId: String): String {
    val saved = vehiclePaintIds[vehicleId]
    if (saved != null && PaintCatalog.findById(saved) != null) return saved
    return HeroVehicleCatalog.findById(vehicleId)?.defaultPaintId
      ?: HeroVehicleCatalog.STARTER_SPEC.defaultPaintId
  }

  fun upgradesFor(vehicleId: String): VehicleUpgradeLevels =
    vehicleUpgrades[vehicleId] ?: VehicleUpgradeLevels()

  fun environmentsWithActiveScene(activeSceneEnvironmentId: String): List<EnvironmentItemStatus> =
    ProgressionCatalog.ENVIRONMENTS.map { spec ->
      EnvironmentItemStatus(
        spec = spec,
        unlocked = spec.id in unlockedEnvironmentIds,
        selectedForNextRace = spec.id == selectedEnvironmentId,
        activeInCurrentScene = spec.id == activeSceneEnvironmentId,
      )
    }
}

sealed interface GarageMutationResult {
  val walletSnapshot: WalletSnapshot

  data class Succeeded(
    val message: String,
    override val walletSnapshot: WalletSnapshot,
  ) : GarageMutationResult

  data class DuplicateIgnored(
    val operationId: String,
    override val walletSnapshot: WalletSnapshot,
  ) : GarageMutationResult

  data class Rejected(
    val reason: String,
    override val walletSnapshot: WalletSnapshot,
  ) : GarageMutationResult
}

sealed interface ProgressionMutationResult {
  val walletSnapshot: WalletSnapshot

  data class Claimed(
    val operationId: String,
    val rewardCoins: Long,
    val message: String,
    override val walletSnapshot: WalletSnapshot,
  ) : ProgressionMutationResult

  data class Updated(
    val message: String,
    override val walletSnapshot: WalletSnapshot,
  ) : ProgressionMutationResult

  data class DuplicateIgnored(
    val operationId: String,
    override val walletSnapshot: WalletSnapshot,
  ) : ProgressionMutationResult

  data class Rejected(
    val reason: String,
    override val walletSnapshot: WalletSnapshot,
  ) : ProgressionMutationResult
}

data class RaceCheckpointOutcome(
  val bestScore: Int,
  val isNewBestScore: Boolean,
  val dateRolledOverMidRace: Boolean,
  val newlyCompletedMissionTitles: List<String>,
  val newlyUnlockedAchievementTitles: List<String>,
  val newlyUnlockedEnvironmentNames: List<String>,
  val walletSnapshot: WalletSnapshot,
)

sealed interface EconomyTransactionResult {
  data class Credited(
    val operationId: String,
    val awardedAmount: Long,
    val multiplierApplied: Int,
    val walletSnapshot: WalletSnapshot,
  ) : EconomyTransactionResult

  data class Debited(
    val operationId: String,
    val debitedAmount: Long,
    val walletSnapshot: WalletSnapshot,
  ) : EconomyTransactionResult

  data class DuplicateIgnored(
    val operationId: String,
    val walletSnapshot: WalletSnapshot,
  ) : EconomyTransactionResult

  data class Rejected(
    val operationId: String,
    val reason: String,
    val walletSnapshot: WalletSnapshot,
  ) : EconomyTransactionResult
}

/**
 * Single authoritative Economy & Progression Service controlling DRIP RACE wallet,
 * idempotent coin/reward transactions, pending-vs-confirmed durability, spend gating,
 * daily missions, permanent achievements, environment unlocks, 7-day manual login calendar,
 * per-race high-water checkpoints, and versioned save recovery.
 */
class EconomyService(
  private val persistence: ProgressionPersistencePort,
  private val bestScoreRepository: BestScoreRepository? = null,
  private val clockMillis: () -> Long = { System.currentTimeMillis() },
  private val initialTimeZoneSupplier: () -> String = { ProgressionCatalog.defaultSystemTimeZoneId() },
) {
  private val lock = Any()

  private var confirmedProgression: PlayerProgression = PlayerProgression()
  private var pendingProgression: PlayerProgression = PlayerProgression()
  private var currentRevision: Long = 0L
  private var pendingCredit: Long = 0L
  private var pendingDirtyNonWallet: Boolean = false
  private val pendingOperationIds = LinkedHashSet<String>()
  private val confirmedOperationIds = LinkedHashSet<String>()

  private var saveStatus: SaveHealthStatus = SaveHealthStatus.DURABLE_CONFIRMED
  private var lastSaveError: String? = null

  init {
    reloadFromStorage()
  }

  fun currentSnapshot(): WalletSnapshot =
    synchronized(lock) {
      syncCalendarDateLocked(clockMillis())
      buildSnapshotLocked()
    }

  /** Synchronizes calendar day rollover (including during Pause or idle menus) and returns snapshot. */
  fun syncCalendarClock(): WalletSnapshot =
    synchronized(lock) {
      val rolled = syncCalendarDateLocked(clockMillis())
      if (rolled) {
        flushPendingLocked()
      }
      buildSnapshotLocked()
    }

  fun peekCurrentLocalDateKey(): String =
    synchronized(lock) {
      ProgressionCatalog.localDateKeyForEpochMillis(
        clockMillis(),
        pendingProgression.profileTimeZoneId,
      )
    }

  fun reloadFromStorage(): WalletSnapshot =
    synchronized(lock) {
      pendingCredit = 0L
      pendingDirtyNonWallet = false
      pendingOperationIds.clear()

      val nowMillis = clockMillis()
      val defaultZone = ProgressionCatalog.resolveValidTimeZoneId(initialTimeZoneSupplier())
      val legacyBest = bestScoreRepository?.loadBestScore() ?: 0

      when (val load = persistence.loadValidated()) {
        is PersistenceLoadOutcome.EmptyNoSaveYet -> {
          val todayKey = ProgressionCatalog.localDateKeyForEpochMillis(nowMillis, defaultZone)
          confirmedProgression =
            SaveEnvelope.canonicalizeGarageFields(
              PlayerProgression(
                bestScore = legacyBest.coerceAtLeast(0),
                updatedEpochMillis = nowMillis,
                profileTimeZoneId = defaultZone,
                dailyMissionDateKey = todayKey,
                highestObservedDateKey = todayKey,
                highestObservedEpochMillis = nowMillis,
              )
            )
          pendingProgression = confirmedProgression
          currentRevision = 0L
          confirmedOperationIds.clear()
          saveStatus = SaveHealthStatus.DURABLE_CONFIRMED
          lastSaveError = null
        }
        is PersistenceLoadOutcome.LoadedPrimary -> {
          val migrated = migrateLoadedPayload(load.envelope, defaultZone, legacyBest)
          confirmedProgression = migrated
          pendingProgression = migrated
          currentRevision = load.envelope.revision
          confirmedOperationIds.clear()
          confirmedOperationIds.addAll(migrated.recentProcessedOperationIds)
          saveStatus = SaveHealthStatus.DURABLE_CONFIRMED
          lastSaveError = null
          if (migrated.bestScore > legacyBest) {
            bestScoreRepository?.recordRunScore(migrated.bestScore)
          }
          syncCalendarDateLocked(nowMillis)
        }
        is PersistenceLoadOutcome.RecoveredFromBackup -> {
          val migrated = migrateLoadedPayload(load.envelope, defaultZone, legacyBest)
          confirmedProgression = migrated
          pendingProgression = migrated
          currentRevision = load.envelope.revision
          confirmedOperationIds.clear()
          confirmedOperationIds.addAll(migrated.recentProcessedOperationIds)
          saveStatus = SaveHealthStatus.RECOVERED_FROM_BACKUP
          lastSaveError = "Recovered from backup: ${load.primaryFailureReason}"
          if (migrated.bestScore > legacyBest) {
            bestScoreRepository?.recordRunScore(migrated.bestScore)
          }
          syncCalendarDateLocked(nowMillis)
        }
        is PersistenceLoadOutcome.CorruptUnrecoverable -> {
          val todayKey = ProgressionCatalog.localDateKeyForEpochMillis(nowMillis, defaultZone)
          confirmedProgression =
            SaveEnvelope.canonicalizeGarageFields(
              PlayerProgression(
                bestScore = legacyBest.coerceAtLeast(0),
                updatedEpochMillis = nowMillis,
                profileTimeZoneId = defaultZone,
                dailyMissionDateKey = todayKey,
                highestObservedDateKey = todayKey,
                highestObservedEpochMillis = nowMillis,
              )
            )
          pendingProgression = confirmedProgression
          currentRevision = 0L
          confirmedOperationIds.clear()
          saveStatus = SaveHealthStatus.CORRUPT_RESET_WARNING
          lastSaveError = load.detail
        }
      }
      buildSnapshotLocked()
    }

  private fun migrateLoadedPayload(
    envelope: SaveEnvelope,
    fallbackZone: String,
    legacyBest: Int,
  ): PlayerProgression {
    val raw = envelope.payload
    val zone =
      if (envelope.schemaVersion < SaveEnvelope.SCHEMA_VERSION_V3) {
        fallbackZone
      } else {
        ProgressionCatalog.resolveValidTimeZoneId(raw.profileTimeZoneId)
      }
    val mergedBest = max(raw.bestScore, legacyBest)
    val base =
      SaveEnvelope.canonicalizeGarageFields(
        raw.copy(
          bestScore = mergedBest,
          profileTimeZoneId = zone,
        )
      )
    return evaluateUnlocksAndAchievements(base, raceEndedForCheckpoint = false).updated
  }

  /**
   * Immediately credits an accepted physical coin pickup in canonical in-memory state.
   * Double Coins (2x) applies ONLY here, never to score, purchases, or claim rewards.
   */
  fun creditCoinPickup(
    operationId: String,
    coinTier: CoinTier,
    doubleCoinsActive: Boolean,
  ): EconomyTransactionResult =
    synchronized(lock) {
      if (operationId.isBlank()) {
        return@synchronized EconomyTransactionResult.Rejected(
          operationId = operationId,
          reason = "Blank pickup operationId.",
          walletSnapshot = buildSnapshotLocked(),
        )
      }
      if (isAlreadyProcessedLocked(operationId)) {
        return@synchronized EconomyTransactionResult.DuplicateIgnored(
          operationId = operationId,
          walletSnapshot = buildSnapshotLocked(),
        )
      }

      syncCalendarDateLocked(clockMillis())

      val multiplier = if (doubleCoinsActive) 2 else 1
      val awarded = coinTier.baseValue.toLong() * multiplier.toLong()
      pendingOperationIds.add(operationId)
      pendingCredit += awarded

      val nextPending =
        pendingProgression.copy(
          totalPhysicalCoinPickups = pendingProgression.totalPhysicalCoinPickups + 1L,
          totalCollectibleCurrencyEarned = pendingProgression.totalCollectibleCurrencyEarned + awarded,
          dailyCoinUnitsEarned = pendingProgression.dailyCoinUnitsEarned + awarded,
        )
      pendingProgression =
        evaluateUnlocksAndAchievements(
          evaluateDailyMissionCompletions(nextPending).updated,
          raceEndedForCheckpoint = false,
        ).updated
      pendingDirtyNonWallet = true

      saveStatus = SaveHealthStatus.PENDING_FLUSH
      EconomyTransactionResult.Credited(
        operationId = operationId,
        awardedAmount = awarded,
        multiplierApplied = multiplier,
        walletSnapshot = buildSnapshotLocked(),
      )
    }

  /**
   * Records a validated power-up pickup idempotently without ever crediting coins or applying Double Coins.
   */
  fun recordPowerUpPickup(operationId: String): EconomyTransactionResult =
    synchronized(lock) {
      if (operationId.isBlank()) {
        return@synchronized EconomyTransactionResult.Rejected(
          operationId = operationId,
          reason = "Blank power-up operationId.",
          walletSnapshot = buildSnapshotLocked(),
        )
      }
      if (isAlreadyProcessedLocked(operationId)) {
        return@synchronized EconomyTransactionResult.DuplicateIgnored(
          operationId = operationId,
          walletSnapshot = buildSnapshotLocked(),
        )
      }
      syncCalendarDateLocked(clockMillis())
      pendingOperationIds.add(operationId)
      pendingProgression =
        pendingProgression.copy(
          totalPowerUpsCollected = pendingProgression.totalPowerUpsCollected + 1L
        )
      pendingDirtyNonWallet = true
      saveStatus =
        if (pendingCredit > 0L) SaveHealthStatus.PENDING_FLUSH else saveStatus
      EconomyTransactionResult.Credited(
        operationId = operationId,
        awardedAmount = 0L,
        multiplierApplied = 1,
        walletSnapshot = buildSnapshotLocked(),
      )
    }

  /**
   * Non-coin reward hook: NEVER affected by Double Coins, never counted as physical coin pickups
   * or Coin Collector collectible currency, and never double-claimed with the same [operationId].
   */
  fun grantNonCoinReward(operationId: String, baseAmount: Long): EconomyTransactionResult =
    synchronized(lock) {
      if (operationId.isBlank() || baseAmount <= 0L) {
        return@synchronized EconomyTransactionResult.Rejected(
          operationId = operationId,
          reason = "Invalid reward request.",
          walletSnapshot = buildSnapshotLocked(),
        )
      }
      if (isAlreadyProcessedLocked(operationId)) {
        return@synchronized EconomyTransactionResult.DuplicateIgnored(
          operationId = operationId,
          walletSnapshot = buildSnapshotLocked(),
        )
      }
      syncCalendarDateLocked(clockMillis())
      pendingOperationIds.add(operationId)
      pendingCredit += baseAmount
      pendingProgression =
        pendingProgression.copy(
          lifetimeRewardCoinsClaimed = pendingProgression.lifetimeRewardCoinsClaimed + baseAmount
        )
      pendingDirtyNonWallet = true
      saveStatus = SaveHealthStatus.PENDING_FLUSH
      if (flushPendingLocked()) {
        EconomyTransactionResult.Credited(
          operationId = operationId,
          awardedAmount = baseAmount,
          multiplierApplied = 1,
          walletSnapshot = buildSnapshotLocked(),
        )
      } else {
        saveStatus = SaveHealthStatus.WRITE_FAILED_UNCONFIRMED
        EconomyTransactionResult.Credited(
          operationId = operationId,
          awardedAmount = baseAmount,
          multiplierApplied = 1,
          walletSnapshot = buildSnapshotLocked(),
        )
      }
    }

  /**
   * Bounded per-race high-water checkpoint & race-end settlement authority.
   *
   * Credits ONLY positive unaccounted deltas (`current - highWater`) for [raceId] so periodic
   * 5-second checkpoints, Pause, Restart, Home, and Game Over can never double-count race
   * distance, survival time, overtakes, near misses, qualified manual Nitro uses, or shield blocks.
   */
  fun checkpointRaceProgress(
    raceId: String,
    distanceMeters: Double,
    activeSeconds: Double,
    uniqueOvertakes: Int,
    uniqueNearMisses: Int,
    qualifiedManualNitroUses: Int,
    shieldBlocks: Int = 0,
    currentScore: Int = 0,
    raceEnded: Boolean = false,
    flushToDisk: Boolean = true,
  ): RaceCheckpointOutcome =
    synchronized(lock) {
      require(raceId.isNotBlank()) { "raceId must not be blank." }
      val nowMillis = clockMillis()
      val rolledOver = syncCalendarDateLocked(nowMillis)
      val todayKey = pendingProgression.dailyMissionDateKey

      val existingCp =
        pendingProgression.activeRaceCheckpoint?.takeIf { it.raceId == raceId }
          ?: ActiveRaceCheckpoint(
            raceId = raceId,
            segmentDateKey = todayKey,
            segmentStartActiveSeconds = 0.0,
          )

      val safeDist = max(existingCp.attributedDistanceMeters, distanceMeters.coerceAtLeast(0.0))
      val deltaDist = max(0.0, safeDist - existingCp.attributedDistanceMeters)

      val safeSec = max(existingCp.attributedActiveSeconds, activeSeconds.coerceAtLeast(0.0))
      val deltaSec = max(0.0, safeSec - existingCp.attributedActiveSeconds)

      val safeOvertakes = max(existingCp.attributedOvertakes, uniqueOvertakes.coerceAtLeast(0))
      val deltaOvertakes = (safeOvertakes - existingCp.attributedOvertakes).toLong()

      val safeNearMisses = max(existingCp.attributedNearMisses, uniqueNearMisses.coerceAtLeast(0))
      val deltaNearMisses = (safeNearMisses - existingCp.attributedNearMisses).toLong()

      val safeManualNitro = max(existingCp.attributedManualNitroUses, qualifiedManualNitroUses.coerceAtLeast(0))
      val deltaManualNitro = (safeManualNitro - existingCp.attributedManualNitroUses).toLong()

      val safeShieldBlocks = max(existingCp.attributedShieldBlocks, shieldBlocks.coerceAtLeast(0))
      val deltaShieldBlocks = (safeShieldBlocks - existingCp.attributedShieldBlocks).toLong()

      val segStart =
        if (existingCp.segmentDateKey == todayKey || existingCp.segmentDateKey.isEmpty()) {
          existingCp.segmentStartActiveSeconds
        } else {
          existingCp.attributedActiveSeconds
        }
      val currentSegmentSeconds = max(0.0, safeSec - segStart)

      val justEndedValidRace = raceEnded && !existingCp.endSettled && (safeDist > 0.0 || safeSec > 0.0)
      val nextEndSettled = existingCp.endSettled || raceEnded

      val safeScore = currentScore.coerceAtLeast(0)
      val prevBest = max(confirmedProgression.bestScore, pendingProgression.bestScore)
      val isNewBest = safeScore > prevBest
      val nextBest = max(prevBest, safeScore)
      if (isNewBest) {
        bestScoreRepository?.recordRunScore(safeScore)
      }

      val updatedCp =
        ActiveRaceCheckpoint(
          raceId = raceId,
          attributedDistanceMeters = safeDist,
          attributedActiveSeconds = safeSec,
          attributedOvertakes = safeOvertakes,
          attributedNearMisses = safeNearMisses,
          attributedManualNitroUses = safeManualNitro,
          attributedShieldBlocks = safeShieldBlocks,
          segmentDateKey = todayKey,
          segmentStartActiveSeconds = segStart,
          endSettled = nextEndSettled,
        )

      val withDeltas =
        pendingProgression.copy(
          bestScore = nextBest,
          totalShieldBlocks = pendingProgression.totalShieldBlocks + deltaShieldBlocks,
          lifetimeDistanceMeters = pendingProgression.lifetimeDistanceMeters + deltaDist,
          lifetimeActiveRaceSeconds = pendingProgression.lifetimeActiveRaceSeconds + deltaSec,
          lifetimeRacesCompleted =
            pendingProgression.lifetimeRacesCompleted + (if (justEndedValidRace) 1L else 0L),
          longestSingleRaceDistanceMeters =
            max(pendingProgression.longestSingleRaceDistanceMeters, safeDist),
          longestSingleRaceSurvivalSeconds =
            max(pendingProgression.longestSingleRaceSurvivalSeconds, safeSec),
          lifetimeUniqueOvertakes = pendingProgression.lifetimeUniqueOvertakes + deltaOvertakes,
          lifetimeUniqueNearMisses = pendingProgression.lifetimeUniqueNearMisses + deltaNearMisses,
          lifetimeQualifiedManualNitroUses =
            pendingProgression.lifetimeQualifiedManualNitroUses + deltaManualNitro,
          dailyDistanceMeters = pendingProgression.dailyDistanceMeters + deltaDist,
          dailyBestEnduranceSegmentSeconds =
            max(pendingProgression.dailyBestEnduranceSegmentSeconds, currentSegmentSeconds),
          dailyUniqueOvertakes = pendingProgression.dailyUniqueOvertakes + deltaOvertakes,
          dailyQualifiedManualNitroUses =
            pendingProgression.dailyQualifiedManualNitroUses + deltaManualNitro,
          activeRaceCheckpoint = updatedCp,
        )

      val missionEval = evaluateDailyMissionCompletions(withDeltas)
      val unlockEval =
        evaluateUnlocksAndAchievements(
          missionEval.updated,
          raceEndedForCheckpoint = nextEndSettled,
          endedRaceDistanceMeters = if (nextEndSettled) safeDist else 0.0,
        )

      if (unlockEval.updated != pendingProgression || rolledOver) {
        pendingProgression = unlockEval.updated
        pendingDirtyNonWallet = true
      }

      if (flushToDisk) {
        flushPendingLocked()
      }

      RaceCheckpointOutcome(
        bestScore = max(confirmedProgression.bestScore, pendingProgression.bestScore),
        isNewBestScore = isNewBest,
        dateRolledOverMidRace = rolledOver,
        newlyCompletedMissionTitles = missionEval.newlyCompletedTitles,
        newlyUnlockedAchievementTitles = unlockEval.newlyUnlockedAchievementTitles,
        newlyUnlockedEnvironmentNames = unlockEval.newlyUnlockedEnvironmentNames,
        walletSnapshot = buildSnapshotLocked(),
      )
    }

  /**
   * Legacy Phase 4 end-of-run settlement entry point preserved for full regression compatibility.
   */
  fun settleRunCompletion(
    runOperationId: String,
    finalScore: Int,
    runShieldBlocks: Int,
  ): Pair<Int, Boolean> =
    synchronized(lock) {
      syncCalendarDateLocked(clockMillis())
      val safeScore = finalScore.coerceAtLeast(0)
      val currentBest = max(confirmedProgression.bestScore, pendingProgression.bestScore)
      val isNewBest = safeScore > currentBest
      if (isNewBest) {
        pendingProgression = pendingProgression.copy(bestScore = safeScore)
        pendingDirtyNonWallet = true
        bestScoreRepository?.recordRunScore(safeScore)
      }
      if (runOperationId.isNotBlank() && !isAlreadyProcessedLocked(runOperationId)) {
        pendingOperationIds.add(runOperationId)
        if (runShieldBlocks > 0) {
          val cpBlocks = pendingProgression.activeRaceCheckpoint?.attributedShieldBlocks ?: 0
          val unaccountedBlocks = max(0, runShieldBlocks - cpBlocks)
          if (unaccountedBlocks > 0) {
            pendingProgression =
              pendingProgression.copy(
                totalShieldBlocks = pendingProgression.totalShieldBlocks + unaccountedBlocks.toLong()
              )
          }
        }
        pendingDirtyNonWallet = true
      }
      flushPendingLocked()
      max(confirmedProgression.bestScore, pendingProgression.bestScore) to isNewBest
    }

  // --- Phase 6 Authoritative Explicit Claim & Environment Selection Mutations ---

  /**
   * Explicitly claims a completed daily mission for the current local calendar day.
   * Persists wallet credit + CLAIMED status atomically BEFORE committing state.
   */
  fun claimDailyMission(
    missionId: String,
    operationId: String,
  ): ProgressionMutationResult =
    synchronized(lock) {
      val spec =
        ProgressionCatalog.findDailyMission(missionId)
          ?: return@synchronized ProgressionMutationResult.Rejected(
            reason = "Unknown daily mission '$missionId'.",
            walletSnapshot = buildSnapshotLocked(),
          )
      if (operationId.isBlank()) {
        return@synchronized ProgressionMutationResult.Rejected(
          reason = "Blank mission claim operationId.",
          walletSnapshot = buildSnapshotLocked(),
        )
      }
      if (operationId in confirmedOperationIds) {
        return@synchronized ProgressionMutationResult.DuplicateIgnored(
          operationId = operationId,
          walletSnapshot = buildSnapshotLocked(),
        )
      }

      syncCalendarDateLocked(clockMillis())
      if (hasAnyPendingLocked() && !flushPendingLocked()) {
        return@synchronized ProgressionMutationResult.Rejected(
          reason = "Mission claim aborted because durable save failed: ${lastSaveError ?: "storage error"}",
          walletSnapshot = buildSnapshotLocked(),
        )
      }

      if (spec.id in confirmedProgression.dailyClaimedMissionIds) {
        return@synchronized ProgressionMutationResult.Rejected(
          reason = "${spec.title} was already claimed today.",
          walletSnapshot = buildSnapshotLocked(),
        )
      }

      val currentProgress = dailyMissionProgressValue(confirmedProgression, spec.type)
      if (currentProgress < spec.targetValue) {
        val reason =
          if (spec.id in confirmedProgression.previousDayExpiredMissionIds) {
            "Previous day's ${spec.title} reward expired unclaimed at daily reset."
          } else {
            "${spec.title} is still in progress ($currentProgress/${spec.targetValue} ${spec.unitLabel})."
          }
        return@synchronized ProgressionMutationResult.Rejected(
          reason = reason,
          walletSnapshot = buildSnapshotLocked(),
        )
      }

      val updatedClaimed = (confirmedProgression.dailyClaimedMissionIds + spec.id).distinct()
      val updatedCompletedCounted =
        (confirmedProgression.dailyCompletedCountedMissionIds + spec.id).distinct()

      val candidatePayload =
        SaveEnvelope.canonicalizeGarageFields(
          buildMergedPayloadLocked(
            extraWalletDelta = spec.rewardCoins,
            extraOperationId = operationId,
          ).copy(
            dailyClaimedMissionIds = updatedClaimed,
            dailyCompletedCountedMissionIds = updatedCompletedCounted,
            lifetimeMissionsClaimed = confirmedProgression.lifetimeMissionsClaimed + 1L,
            lifetimeRewardCoinsClaimed =
              confirmedProgression.lifetimeRewardCoinsClaimed + spec.rewardCoins,
          )
        )
      return@synchronized persistAtomicProgressionMutationLocked(
        candidatePayload = candidatePayload,
        operationId = operationId,
        rewardCoins = spec.rewardCoins,
        successMessage = "Claimed ${spec.title}: +${spec.rewardCoins} coins.",
      )
    }

  /**
   * Explicitly claims a permanently unlocked achievement once.
   * Lifetime achievement claims never expire and never count as physical coin pickups.
   */
  fun claimAchievement(
    achievementId: String,
    operationId: String,
  ): ProgressionMutationResult =
    synchronized(lock) {
      val spec =
        ProgressionCatalog.findAchievement(achievementId)
          ?: return@synchronized ProgressionMutationResult.Rejected(
            reason = "Unknown achievement '$achievementId'.",
            walletSnapshot = buildSnapshotLocked(),
          )
      if (operationId.isBlank()) {
        return@synchronized ProgressionMutationResult.Rejected(
          reason = "Blank achievement claim operationId.",
          walletSnapshot = buildSnapshotLocked(),
        )
      }
      if (operationId in confirmedOperationIds) {
        return@synchronized ProgressionMutationResult.DuplicateIgnored(
          operationId = operationId,
          walletSnapshot = buildSnapshotLocked(),
        )
      }

      syncCalendarDateLocked(clockMillis())
      if (hasAnyPendingLocked() && !flushPendingLocked()) {
        return@synchronized ProgressionMutationResult.Rejected(
          reason = "Achievement claim aborted because durable save failed: ${lastSaveError ?: "storage error"}",
          walletSnapshot = buildSnapshotLocked(),
        )
      }

      if (spec.id in confirmedProgression.claimedAchievementIds) {
        return@synchronized ProgressionMutationResult.Rejected(
          reason = "${spec.title} has already been claimed.",
          walletSnapshot = buildSnapshotLocked(),
        )
      }
      if (spec.id !in confirmedProgression.unlockedAchievementIds) {
        val currentVal = achievementProgressValue(confirmedProgression, spec.type)
        return@synchronized ProgressionMutationResult.Rejected(
          reason = "${spec.title} is locked ($currentVal/${spec.targetValue} ${spec.unitLabel}).",
          walletSnapshot = buildSnapshotLocked(),
        )
      }

      val updatedClaimed = (confirmedProgression.claimedAchievementIds + spec.id).distinct()
      val candidatePayload =
        SaveEnvelope.canonicalizeGarageFields(
          buildMergedPayloadLocked(
            extraWalletDelta = spec.rewardCoins,
            extraOperationId = operationId,
          ).copy(
            claimedAchievementIds = updatedClaimed,
            lifetimeRewardCoinsClaimed =
              confirmedProgression.lifetimeRewardCoinsClaimed + spec.rewardCoins,
          )
        )
      return@synchronized persistAtomicProgressionMutationLocked(
        candidatePayload = candidatePayload,
        operationId = operationId,
        rewardCoins = spec.rewardCoins,
        successMessage = "Claimed achievement ${spec.title}: +${spec.rewardCoins} coins.",
      )
    }

  /**
   * Explicitly claims today's manual 7-day login calendar reward.
   * Enforces at most one claim per local calendar date in profile IANA timezone, consecutive-day
   * advance, Day 7 -> Day 1 rollover, skipped-day reset to Day 1, and clock-rollback blocking.
   */
  fun claimDailyLoginReward(
    operationId: String,
  ): ProgressionMutationResult =
    synchronized(lock) {
      if (operationId.isBlank()) {
        return@synchronized ProgressionMutationResult.Rejected(
          reason = "Blank daily login operationId.",
          walletSnapshot = buildSnapshotLocked(),
        )
      }
      if (operationId in confirmedOperationIds) {
        return@synchronized ProgressionMutationResult.DuplicateIgnored(
          operationId = operationId,
          walletSnapshot = buildSnapshotLocked(),
        )
      }

      val nowMillis = clockMillis()
      syncCalendarDateLocked(nowMillis)
      if (hasAnyPendingLocked() && !flushPendingLocked()) {
        return@synchronized ProgressionMutationResult.Rejected(
          reason = "Daily reward claim aborted because durable save failed: ${lastSaveError ?: "storage error"}",
          walletSnapshot = buildSnapshotLocked(),
        )
      }

      val loginStatus = evaluateLoginStatusFor(confirmedProgression, nowMillis)
      if (loginStatus.clockRollbackDetected) {
        return@synchronized ProgressionMutationResult.Rejected(
          reason = loginStatus.clockNotice
            ?: "Device clock rollback detected; daily login claim blocked.",
          walletSnapshot = buildSnapshotLocked(),
        )
      }
      if (loginStatus.alreadyClaimedToday || !loginStatus.canClaimToday) {
        return@synchronized ProgressionMutationResult.Rejected(
          reason = "Daily reward already claimed for ${loginStatus.todayDateKey}.",
          walletSnapshot = buildSnapshotLocked(),
        )
      }

      val claimedDay = loginStatus.eligibleDayNumber
      val reward = loginStatus.eligibleRewardCoins
      val newHighestDate =
        if (confirmedProgression.highestObservedDateKey.isEmpty() ||
          ProgressionCatalog.daysBetweenDateKeys(
            confirmedProgression.highestObservedDateKey,
            loginStatus.todayDateKey,
          ) > 0L
        ) {
          loginStatus.todayDateKey
        } else {
          confirmedProgression.highestObservedDateKey
        }
      val newHighestMillis = max(confirmedProgression.highestObservedEpochMillis, nowMillis)

      val candidatePayload =
        SaveEnvelope.canonicalizeGarageFields(
          buildMergedPayloadLocked(
            extraWalletDelta = reward,
            extraOperationId = operationId,
          ).copy(
            dailyRewardStreakDay = claimedDay,
            dailyRewardLastClaimDateKey = loginStatus.todayDateKey,
            dailyRewardLastClaimEpochMillis = nowMillis,
            highestObservedDateKey = newHighestDate,
            highestObservedEpochMillis = newHighestMillis,
            lifetimeDailyRewardsClaimed = confirmedProgression.lifetimeDailyRewardsClaimed + 1L,
            lifetimeRewardCoinsClaimed = confirmedProgression.lifetimeRewardCoinsClaimed + reward,
          )
        )
      return@synchronized persistAtomicProgressionMutationLocked(
        candidatePayload = candidatePayload,
        operationId = operationId,
        rewardCoins = reward,
        successMessage = "Claimed Day $claimedDay login reward: +$reward coins.",
      )
    }

  /**
   * Selects an unlocked highway environment level.
   * Selection is free, works with every car (including Starter), and takes effect on the NEXT race
   * if a race is currently active/paused (never mid-run world swap).
   */
  fun selectEnvironment(
    environmentId: String,
    raceInProgress: Boolean = false,
  ): ProgressionMutationResult =
    synchronized(lock) {
      val spec =
        ProgressionCatalog.findEnvironment(environmentId)
          ?: return@synchronized ProgressionMutationResult.Rejected(
            reason = "Unknown environment '$environmentId'.",
            walletSnapshot = buildSnapshotLocked(),
          )
      syncCalendarDateLocked(clockMillis())
      if (spec.id !in pendingProgression.unlockedEnvironmentIds) {
        val needed = spec.requiredLifetimeMeters.toInt()
        val have = floor(pendingProgression.lifetimeDistanceMeters).toInt()
        return@synchronized ProgressionMutationResult.Rejected(
          reason = "${spec.displayName} unlocks permanently at $needed m lifetime ($have m driven).",
          walletSnapshot = buildSnapshotLocked(),
        )
      }

      if (hasAnyPendingLocked() && !flushPendingLocked()) {
        return@synchronized ProgressionMutationResult.Rejected(
          reason = "Environment selection failed to save: ${lastSaveError ?: "storage error"}",
          walletSnapshot = buildSnapshotLocked(),
        )
      }

      val noteSuffix =
        if (raceInProgress) " (takes effect next race — active run continues unchanged)."
        else " ready for next race."

      if (confirmedProgression.selectedEnvironmentId == spec.id) {
        return@synchronized ProgressionMutationResult.Updated(
          message = "${spec.displayName}$noteSuffix",
          walletSnapshot = buildSnapshotLocked(),
        )
      }

      val candidatePayload =
        SaveEnvelope.canonicalizeGarageFields(
          buildMergedPayloadLocked().copy(selectedEnvironmentId = spec.id)
        )
      val nextRev = currentRevision + 1L
      val envelope =
        SaveEnvelope(
          format = SaveEnvelope.FORMAT_ID,
          schemaVersion = SaveEnvelope.CURRENT_SCHEMA_VERSION,
          revision = nextRev,
          payload = candidatePayload,
        )
      return@synchronized when (val write = persistence.writeAtomicWithBackup(envelope)) {
        is PersistenceWriteOutcome.Success -> {
          commitConfirmedLocked(candidatePayload, nextRev)
          ProgressionMutationResult.Updated(
            message = "Selected ${spec.displayName}$noteSuffix",
            walletSnapshot = buildSnapshotLocked(),
          )
        }
        is PersistenceWriteOutcome.Failure -> {
          saveStatus = SaveHealthStatus.WRITE_FAILED_UNCONFIRMED
          lastSaveError = write.reason
          ProgressionMutationResult.Rejected(
            reason = "Environment selection failed to save: ${write.reason}",
            walletSnapshot = buildSnapshotLocked(),
          )
        }
      }
    }

  /**
   * Persists the player's measured Graphics Quality preset (`AUTO`, `HIGH`, `MEDIUM`, `LOW`).
   * Simulation physics and lane collision fairness remain strictly invariant across all presets.
   */
  fun setGraphicsQualityPreset(preset: GraphicsQualityPreset): ProgressionMutationResult =
    synchronized(lock) {
      syncCalendarDateLocked(clockMillis())
      if (hasAnyPendingLocked() && !flushPendingLocked()) {
        return@synchronized ProgressionMutationResult.Rejected(
          reason = "Graphics quality change failed to save: ${lastSaveError ?: "storage error"}",
          walletSnapshot = buildSnapshotLocked(),
        )
      }
      if (confirmedProgression.graphicsQualityPresetId == preset.id) {
        return@synchronized ProgressionMutationResult.Updated(
          message = "Graphics quality: ${preset.displayName}",
          walletSnapshot = buildSnapshotLocked(),
        )
      }
      val candidatePayload =
        SaveEnvelope.canonicalizeGarageFields(
          buildMergedPayloadLocked().copy(graphicsQualityPresetId = preset.id)
        )
      val nextRev = currentRevision + 1L
      val envelope =
        SaveEnvelope(
          format = SaveEnvelope.FORMAT_ID,
          schemaVersion = SaveEnvelope.CURRENT_SCHEMA_VERSION,
          revision = nextRev,
          payload = candidatePayload,
        )
      return@synchronized when (val write = persistence.writeAtomicWithBackup(envelope)) {
        is PersistenceWriteOutcome.Success -> {
          commitConfirmedLocked(candidatePayload, nextRev)
          ProgressionMutationResult.Updated(
            message = "Graphics quality set to ${preset.displayName}.",
            walletSnapshot = buildSnapshotLocked(),
          )
        }
        is PersistenceWriteOutcome.Failure -> {
          saveStatus = SaveHealthStatus.WRITE_FAILED_UNCONFIRMED
          lastSaveError = write.reason
          ProgressionMutationResult.Rejected(
            reason = "Graphics quality failed to save: ${write.reason}",
            walletSnapshot = buildSnapshotLocked(),
          )
        }
      }
    }

  /**
   * Persists player Audio, Reduced Motion, and Optional Haptics settings without mutating wallet,
   * score, or physics state.
   */
  fun updateAudioSettings(newSettings: AudioSettingsState): ProgressionMutationResult =
    synchronized(lock) {
      syncCalendarDateLocked(clockMillis())
      if (hasAnyPendingLocked() && !flushPendingLocked()) {
        return@synchronized ProgressionMutationResult.Rejected(
          reason = "Audio settings failed to save: ${lastSaveError ?: "storage error"}",
          walletSnapshot = buildSnapshotLocked(),
        )
      }
      val canonical =
        SaveEnvelope.canonicalizeGarageFields(
          PlayerProgression(audioSettings = newSettings)
        ).audioSettings
      if (confirmedProgression.audioSettings == canonical) {
        return@synchronized ProgressionMutationResult.Updated(
          message = "Audio settings saved.",
          walletSnapshot = buildSnapshotLocked(),
        )
      }
      val candidatePayload =
        SaveEnvelope.canonicalizeGarageFields(
          buildMergedPayloadLocked().copy(audioSettings = canonical)
        )
      val nextRev = currentRevision + 1L
      val envelope =
        SaveEnvelope(
          format = SaveEnvelope.FORMAT_ID,
          schemaVersion = SaveEnvelope.CURRENT_SCHEMA_VERSION,
          revision = nextRev,
          payload = candidatePayload,
        )
      return@synchronized when (val write = persistence.writeAtomicWithBackup(envelope)) {
        is PersistenceWriteOutcome.Success -> {
          commitConfirmedLocked(candidatePayload, nextRev)
          ProgressionMutationResult.Updated(
            message = "Audio & accessibility settings saved.",
            walletSnapshot = buildSnapshotLocked(),
          )
        }
        is PersistenceWriteOutcome.Failure -> {
          saveStatus = SaveHealthStatus.WRITE_FAILED_UNCONFIRMED
          lastSaveError = write.reason
          ProgressionMutationResult.Rejected(
            reason = "Audio settings failed to save: ${write.reason}",
            walletSnapshot = buildSnapshotLocked(),
          )
        }
      }
    }

  private fun persistAtomicProgressionMutationLocked(
    candidatePayload: PlayerProgression,
    operationId: String,
    rewardCoins: Long,
    successMessage: String,
  ): ProgressionMutationResult {
    val nextRev = currentRevision + 1L
    val envelope =
      SaveEnvelope(
        format = SaveEnvelope.FORMAT_ID,
        schemaVersion = SaveEnvelope.CURRENT_SCHEMA_VERSION,
        revision = nextRev,
        payload = candidatePayload,
      )
    return when (val write = persistence.writeAtomicWithBackup(envelope)) {
      is PersistenceWriteOutcome.Success -> {
        commitConfirmedLocked(candidatePayload, nextRev)
        ProgressionMutationResult.Claimed(
          operationId = operationId,
          rewardCoins = rewardCoins,
          message = successMessage,
          walletSnapshot = buildSnapshotLocked(),
        )
      }
      is PersistenceWriteOutcome.Failure -> {
        saveStatus = SaveHealthStatus.WRITE_FAILED_UNCONFIRMED
        lastSaveError = write.reason
        ProgressionMutationResult.Rejected(
          reason = "Claim aborted because durable save failed: ${write.reason}",
          walletSnapshot = buildSnapshotLocked(),
        )
      }
    }
  }

  /**
   * Spend authorization gate: attempts to flush pending coins first, and strictly forbids spending
   * unconfirmed credit!
   */
  fun tryDebitConfirmedBalance(
    operationId: String,
    cost: Long,
  ): EconomyTransactionResult =
    synchronized(lock) {
      if (operationId.isBlank() || cost <= 0L) {
        return@synchronized EconomyTransactionResult.Rejected(
          operationId = operationId,
          reason = "Invalid debit parameters.",
          walletSnapshot = buildSnapshotLocked(),
        )
      }
      if (isAlreadyProcessedLocked(operationId)) {
        return@synchronized EconomyTransactionResult.DuplicateIgnored(
          operationId = operationId,
          walletSnapshot = buildSnapshotLocked(),
        )
      }
      syncCalendarDateLocked(clockMillis())
      if (hasAnyPendingLocked()) {
        flushPendingLocked()
      }
      if (confirmedProgression.confirmedWalletBalance < cost) {
        val reason =
          if (pendingCredit > 0L) {
            "Insufficient confirmed balance: $pendingCredit coin(s) are pending/unconfirmed save."
          } else {
            "Insufficient wallet balance."
          }
        return@synchronized EconomyTransactionResult.Rejected(
          operationId = operationId,
          reason = reason,
          walletSnapshot = buildSnapshotLocked(),
        )
      }

      val candidatePayload =
        buildMergedPayloadLocked(
          extraWalletDelta = -cost,
          extraOperationId = operationId,
        )
      val nextRev = currentRevision + 1L
      val envelope =
        SaveEnvelope(
          format = SaveEnvelope.FORMAT_ID,
          schemaVersion = SaveEnvelope.CURRENT_SCHEMA_VERSION,
          revision = nextRev,
          payload = candidatePayload,
        )
      return@synchronized when (val write = persistence.writeAtomicWithBackup(envelope)) {
        is PersistenceWriteOutcome.Success -> {
          commitConfirmedLocked(candidatePayload, nextRev)
          EconomyTransactionResult.Debited(
            operationId = operationId,
            debitedAmount = cost,
            walletSnapshot = buildSnapshotLocked(),
          )
        }
        is PersistenceWriteOutcome.Failure -> {
          saveStatus = SaveHealthStatus.WRITE_FAILED_UNCONFIRMED
          lastSaveError = write.reason
          EconomyTransactionResult.Rejected(
            operationId = operationId,
            reason = "Debit aborted because durable save failed: ${write.reason}",
            walletSnapshot = buildSnapshotLocked(),
          )
        }
      }
    }

  /**
   * Authoritative Garage purchase: validates vehicle ID, non-ownership, idempotent operation ID,
   * flushes pending credits, revalidates confirmed wallet balance, evaluates Garage Collector,
   * and persists BEFORE updating state.
   */
  fun purchaseVehicle(
    vehicleId: String,
    operationId: String,
    equipOnPurchase: Boolean = true,
  ): GarageMutationResult =
    synchronized(lock) {
      val spec =
        HeroVehicleCatalog.findById(vehicleId)
          ?: return@synchronized GarageMutationResult.Rejected(
            reason = "Unknown vehicle '$vehicleId'.",
            walletSnapshot = buildSnapshotLocked(),
          )
      if (operationId.isBlank()) {
        return@synchronized GarageMutationResult.Rejected(
          reason = "Blank purchase operationId.",
          walletSnapshot = buildSnapshotLocked(),
        )
      }
      if (isAlreadyProcessedLocked(operationId)) {
        return@synchronized GarageMutationResult.DuplicateIgnored(
          operationId = operationId,
          walletSnapshot = buildSnapshotLocked(),
        )
      }
      syncCalendarDateLocked(clockMillis())
      if (spec.id in confirmedProgression.ownedVehicleIds) {
        return@synchronized GarageMutationResult.Rejected(
          reason = "${spec.displayName} is already owned.",
          walletSnapshot = buildSnapshotLocked(),
        )
      }

      if (hasAnyPendingLocked()) {
        flushPendingLocked()
      }
      if (confirmedProgression.confirmedWalletBalance < spec.unlockPrice) {
        val reason =
          if (pendingCredit > 0L) {
            "Insufficient confirmed balance: save pending coins first."
          } else {
            "Need ${spec.unlockPrice} coins to unlock ${spec.displayName}."
          }
        return@synchronized GarageMutationResult.Rejected(
          reason = reason,
          walletSnapshot = buildSnapshotLocked(),
        )
      }

      val updatedOwned = (confirmedProgression.ownedVehicleIds + spec.id).distinct()
      val updatedPaints =
        confirmedProgression.vehiclePaintIds.toMutableMap().apply {
          if (spec.id !in this) put(spec.id, spec.defaultPaintId)
        }
      val nextSelected = if (equipOnPurchase) spec.id else confirmedProgression.selectedVehicleId

      val preEval =
        SaveEnvelope.canonicalizeGarageFields(
          buildMergedPayloadLocked(
            extraWalletDelta = -spec.unlockPrice,
            extraOperationId = operationId,
          ).copy(
            ownedVehicleIds = updatedOwned,
            selectedVehicleId = nextSelected,
            vehiclePaintIds = updatedPaints,
          )
        )
      val candidatePayload =
        evaluateUnlocksAndAchievements(preEval, raceEndedForCheckpoint = false).updated
      val nextRev = currentRevision + 1L
      val envelope =
        SaveEnvelope(
          format = SaveEnvelope.FORMAT_ID,
          schemaVersion = SaveEnvelope.CURRENT_SCHEMA_VERSION,
          revision = nextRev,
          payload = candidatePayload,
        )
      return@synchronized when (val write = persistence.writeAtomicWithBackup(envelope)) {
        is PersistenceWriteOutcome.Success -> {
          commitConfirmedLocked(candidatePayload, nextRev)
          GarageMutationResult.Succeeded(
            message = "Unlocked ${spec.displayName} (-${spec.unlockPrice} coins).",
            walletSnapshot = buildSnapshotLocked(),
          )
        }
        is PersistenceWriteOutcome.Failure -> {
          saveStatus = SaveHealthStatus.WRITE_FAILED_UNCONFIRMED
          lastSaveError = write.reason
          GarageMutationResult.Rejected(
            reason = "Purchase aborted because durable save failed: ${write.reason}",
            walletSnapshot = buildSnapshotLocked(),
          )
        }
      }
    }

  /** Equips an owned vehicle durably; strictly forbids equipping locked vehicles. */
  fun selectVehicle(vehicleId: String): GarageMutationResult =
    synchronized(lock) {
      val spec =
        HeroVehicleCatalog.findById(vehicleId)
          ?: return@synchronized GarageMutationResult.Rejected(
            reason = "Unknown vehicle '$vehicleId'.",
            walletSnapshot = buildSnapshotLocked(),
          )
      syncCalendarDateLocked(clockMillis())
      if (spec.id !in confirmedProgression.ownedVehicleIds) {
        return@synchronized GarageMutationResult.Rejected(
          reason = "Purchase ${spec.displayName} before equipping.",
          walletSnapshot = buildSnapshotLocked(),
        )
      }
      if (confirmedProgression.selectedVehicleId == spec.id) {
        return@synchronized GarageMutationResult.Succeeded(
          message = "${spec.displayName} equipped.",
          walletSnapshot = buildSnapshotLocked(),
        )
      }

      if (hasAnyPendingLocked()) {
        flushPendingLocked()
      }
      val candidatePayload =
        SaveEnvelope.canonicalizeGarageFields(
          buildMergedPayloadLocked().copy(selectedVehicleId = spec.id)
        )
      val nextRev = currentRevision + 1L
      val envelope =
        SaveEnvelope(
          format = SaveEnvelope.FORMAT_ID,
          schemaVersion = SaveEnvelope.CURRENT_SCHEMA_VERSION,
          revision = nextRev,
          payload = candidatePayload,
        )
      return@synchronized when (val write = persistence.writeAtomicWithBackup(envelope)) {
        is PersistenceWriteOutcome.Success -> {
          commitConfirmedLocked(candidatePayload, nextRev)
          GarageMutationResult.Succeeded(
            message = "Equipped ${spec.displayName}.",
            walletSnapshot = buildSnapshotLocked(),
          )
        }
        is PersistenceWriteOutcome.Failure -> {
          saveStatus = SaveHealthStatus.WRITE_FAILED_UNCONFIRMED
          lastSaveError = write.reason
          GarageMutationResult.Rejected(
            reason = "Equip aborted because durable save failed: ${write.reason}",
            walletSnapshot = buildSnapshotLocked(),
          )
        }
      }
    }

  /** Persists one of the 7 free body colors for an owned vehicle only (never for locked preview). */
  fun setOwnedVehiclePaint(vehicleId: String, paintColorId: String): GarageMutationResult =
    synchronized(lock) {
      val spec =
        HeroVehicleCatalog.findById(vehicleId)
          ?: return@synchronized GarageMutationResult.Rejected(
            reason = "Unknown vehicle '$vehicleId'.",
            walletSnapshot = buildSnapshotLocked(),
          )
      val color =
        PaintCatalog.findById(paintColorId)
          ?: return@synchronized GarageMutationResult.Rejected(
            reason = "Invalid body paint color '$paintColorId'.",
            walletSnapshot = buildSnapshotLocked(),
          )
      syncCalendarDateLocked(clockMillis())
      if (spec.id !in confirmedProgression.ownedVehicleIds) {
        return@synchronized GarageMutationResult.Rejected(
          reason = "Locked preview only: own ${spec.displayName} to save paint.",
          walletSnapshot = buildSnapshotLocked(),
        )
      }
      if (confirmedProgression.vehiclePaintIds[spec.id] == color.id) {
        return@synchronized GarageMutationResult.Succeeded(
          message = "${color.displayName} applied.",
          walletSnapshot = buildSnapshotLocked(),
        )
      }

      if (hasAnyPendingLocked()) {
        flushPendingLocked()
      }
      val updatedPaints =
        confirmedProgression.vehiclePaintIds.toMutableMap().apply {
          put(spec.id, color.id)
        }
      val candidatePayload =
        SaveEnvelope.canonicalizeGarageFields(
          buildMergedPayloadLocked().copy(vehiclePaintIds = updatedPaints)
        )
      val nextRev = currentRevision + 1L
      val envelope =
        SaveEnvelope(
          format = SaveEnvelope.FORMAT_ID,
          schemaVersion = SaveEnvelope.CURRENT_SCHEMA_VERSION,
          revision = nextRev,
          payload = candidatePayload,
        )
      return@synchronized when (val write = persistence.writeAtomicWithBackup(envelope)) {
        is PersistenceWriteOutcome.Success -> {
          commitConfirmedLocked(candidatePayload, nextRev)
          GarageMutationResult.Succeeded(
            message = "${spec.displayName} painted ${color.displayName}.",
            walletSnapshot = buildSnapshotLocked(),
          )
        }
        is PersistenceWriteOutcome.Failure -> {
          saveStatus = SaveHealthStatus.WRITE_FAILED_UNCONFIRMED
          lastSaveError = write.reason
          GarageMutationResult.Rejected(
            reason = "Paint save failed: ${write.reason}",
            walletSnapshot = buildSnapshotLocked(),
          )
        }
      }
    }

  /**
   * Authoritative sequential upgrade purchase for an owned vehicle between races only.
   * Revalidates level cap (0..3), confirmed balance, and persistence before committing.
   */
  fun purchaseVehicleUpgrade(
    vehicleId: String,
    track: UpgradeTrack,
    operationId: String,
    raceInProgress: Boolean = false,
  ): GarageMutationResult =
    synchronized(lock) {
      if (raceInProgress) {
        return@synchronized GarageMutationResult.Rejected(
          reason = "Upgrades are only allowed between races.",
          walletSnapshot = buildSnapshotLocked(),
        )
      }
      if (operationId.isBlank()) {
        return@synchronized GarageMutationResult.Rejected(
          reason = "Blank upgrade operationId.",
          walletSnapshot = buildSnapshotLocked(),
        )
      }
      if (isAlreadyProcessedLocked(operationId)) {
        return@synchronized GarageMutationResult.DuplicateIgnored(
          operationId = operationId,
          walletSnapshot = buildSnapshotLocked(),
        )
      }
      syncCalendarDateLocked(clockMillis())
      val spec =
        HeroVehicleCatalog.findById(vehicleId)
          ?: return@synchronized GarageMutationResult.Rejected(
            reason = "Unknown vehicle '$vehicleId'.",
            walletSnapshot = buildSnapshotLocked(),
          )
      if (spec.id !in confirmedProgression.ownedVehicleIds) {
        return@synchronized GarageMutationResult.Rejected(
          reason = "Own ${spec.displayName} before upgrading ${track.displayName}.",
          walletSnapshot = buildSnapshotLocked(),
        )
      }
      val currentLevels = confirmedProgression.vehicleUpgrades[spec.id] ?: VehicleUpgradeLevels()
      val currentTrackLevel = currentLevels.levelFor(track)
      val nextCost =
        spec.nextUpgradeCost(currentTrackLevel)
          ?: return@synchronized GarageMutationResult.Rejected(
            reason = "${track.displayName} is already at MAX Level ${VehicleUpgradeLevels.MAX_LEVEL}.",
            walletSnapshot = buildSnapshotLocked(),
          )

      if (hasAnyPendingLocked()) {
        flushPendingLocked()
      }
      if (confirmedProgression.confirmedWalletBalance < nextCost) {
        val reason =
          if (pendingCredit > 0L) {
            "Insufficient confirmed balance: save pending coins first."
          } else {
            "Need $nextCost coins for ${track.displayName} Lv ${currentTrackLevel + 1}."
          }
        return@synchronized GarageMutationResult.Rejected(
          reason = reason,
          walletSnapshot = buildSnapshotLocked(),
        )
      }

      val nextLevels = currentLevels.withIncremented(track)
      val updatedUpgrades =
        confirmedProgression.vehicleUpgrades.toMutableMap().apply {
          put(spec.id, nextLevels)
        }
      val candidatePayload =
        SaveEnvelope.canonicalizeGarageFields(
          buildMergedPayloadLocked(
            extraWalletDelta = -nextCost,
            extraOperationId = operationId,
          ).copy(
            vehicleUpgrades = updatedUpgrades,
            lifetimeUpgradesPurchased = confirmedProgression.lifetimeUpgradesPurchased + 1L,
          )
        )
      val nextRev = currentRevision + 1L
      val envelope =
        SaveEnvelope(
          format = SaveEnvelope.FORMAT_ID,
          schemaVersion = SaveEnvelope.CURRENT_SCHEMA_VERSION,
          revision = nextRev,
          payload = candidatePayload,
        )
      return@synchronized when (val write = persistence.writeAtomicWithBackup(envelope)) {
        is PersistenceWriteOutcome.Success -> {
          commitConfirmedLocked(candidatePayload, nextRev)
          GarageMutationResult.Succeeded(
            message = "${track.displayName} upgraded to Lv ${nextLevels.levelFor(track)} (-$nextCost coins).",
            walletSnapshot = buildSnapshotLocked(),
          )
        }
        is PersistenceWriteOutcome.Failure -> {
          saveStatus = SaveHealthStatus.WRITE_FAILED_UNCONFIRMED
          lastSaveError = write.reason
          GarageMutationResult.Rejected(
            reason = "Upgrade aborted because durable save failed: ${write.reason}",
            walletSnapshot = buildSnapshotLocked(),
          )
        }
      }
    }

  fun flushPending(): Boolean =
    synchronized(lock) {
      syncCalendarDateLocked(clockMillis())
      flushPendingLocked()
    }

  // --- Internal Calendar Rollover, Mission/Achievement Evaluators & Persistence ---

  /**
   * Synchronizes `pendingProgression`'s local calendar date in `profileTimeZoneId`.
   * Returns `true` if a forward calendar day rollover occurred.
   */
  private fun syncCalendarDateLocked(nowMillis: Long): Boolean {
    val zoneId = ProgressionCatalog.resolveValidTimeZoneId(pendingProgression.profileTimeZoneId)
    val todayKey = ProgressionCatalog.localDateKeyForEpochMillis(nowMillis, zoneId)
    var p = pendingProgression
    var mutated = false
    var rolledForward = false

    if (p.highestObservedDateKey.isEmpty() ||
      (ProgressionCatalog.isValidDateKey(p.highestObservedDateKey) &&
        ProgressionCatalog.daysBetweenDateKeys(p.highestObservedDateKey, todayKey) > 0L)
    ) {
      p = p.copy(highestObservedDateKey = todayKey)
      mutated = true
    }
    if (nowMillis > p.highestObservedEpochMillis) {
      p = p.copy(highestObservedEpochMillis = nowMillis)
      mutated = true
    }

    if (p.dailyMissionDateKey.isEmpty()) {
      p = p.copy(dailyMissionDateKey = todayKey)
      mutated = true
    } else if (ProgressionCatalog.isValidDateKey(p.dailyMissionDateKey)) {
      val dayDelta = ProgressionCatalog.daysBetweenDateKeys(p.dailyMissionDateKey, todayKey)
      if (dayDelta > 0L) {
        // Expire any completed-but-unclaimed missions from the previous active mission day!
        val expiredIds =
          ProgressionCatalog.DAILY_MISSIONS
            .filter { spec ->
              spec.id !in p.dailyClaimedMissionIds &&
                dailyMissionProgressValue(p, spec.type) >= spec.targetValue
            }
            .map { it.id }

        val updatedCheckpoint =
          p.activeRaceCheckpoint?.let { cp ->
            if (!cp.endSettled && cp.segmentDateKey != todayKey) {
              // Reset the single-race daily endurance eligibility segment cleanly at midnight!
              cp.copy(
                segmentDateKey = todayKey,
                segmentStartActiveSeconds = cp.attributedActiveSeconds,
              )
            } else {
              cp
            }
          }

        p =
          p.copy(
            previousDayDateKey = p.dailyMissionDateKey,
            previousDayExpiredMissionIds = expiredIds,
            expiredUnclaimedMissionCount = p.expiredUnclaimedMissionCount + expiredIds.size.toLong(),
            dailyMissionDateKey = todayKey,
            dailyCoinUnitsEarned = 0L,
            dailyDistanceMeters = 0.0,
            dailyBestEnduranceSegmentSeconds = 0.0,
            dailyUniqueOvertakes = 0L,
            dailyQualifiedManualNitroUses = 0L,
            dailyCompletedCountedMissionIds = emptyList(),
            dailyClaimedMissionIds = emptyList(),
            activeRaceCheckpoint = updatedCheckpoint,
          )
        mutated = true
        rolledForward = true
      }
    }

    if (mutated) {
      pendingProgression = SaveEnvelope.canonicalizeGarageFields(p)
      if (rolledForward) {
        pendingDirtyNonWallet = true
      }
    }
    return rolledForward
  }

  private data class MissionEvalResult(
    val updated: PlayerProgression,
    val newlyCompletedTitles: List<String>,
  )

  private fun evaluateDailyMissionCompletions(p: PlayerProgression): MissionEvalResult {
    val counted = LinkedHashSet(p.dailyCompletedCountedMissionIds)
    val newlyCompleted = mutableListOf<String>()
    var addCompletedCount = 0L

    for (spec in ProgressionCatalog.DAILY_MISSIONS) {
      val progress = dailyMissionProgressValue(p, spec.type)
      if (progress >= spec.targetValue && spec.id !in counted) {
        counted += spec.id
        newlyCompleted += spec.title
        addCompletedCount += 1L
      }
    }
    if (addCompletedCount == 0L) {
      return MissionEvalResult(p, emptyList())
    }
    return MissionEvalResult(
      updated =
        p.copy(
          dailyCompletedCountedMissionIds = counted.toList(),
          lifetimeMissionsCompleted = p.lifetimeMissionsCompleted + addCompletedCount,
        ),
      newlyCompletedTitles = newlyCompleted,
    )
  }

  private data class UnlockEvalResult(
    val updated: PlayerProgression,
    val newlyUnlockedAchievementTitles: List<String>,
    val newlyUnlockedEnvironmentNames: List<String>,
  )

  private fun evaluateUnlocksAndAchievements(
    p: PlayerProgression,
    raceEndedForCheckpoint: Boolean,
    endedRaceDistanceMeters: Double = 0.0,
  ): UnlockEvalResult {
    val unlockedAch = LinkedHashSet(p.unlockedAchievementIds)
    val newlyUnlockedAchTitles = mutableListOf<String>()

    for (spec in ProgressionCatalog.ACHIEVEMENTS) {
      if (spec.id in unlockedAch) continue
      val qualifies =
        when (spec.type) {
          AchievementType.FIRST_RIDE ->
            (raceEndedForCheckpoint && endedRaceDistanceMeters + 1e-6 >= 100.0) ||
              (p.lifetimeRacesCompleted >= 1L && p.longestSingleRaceDistanceMeters + 1e-6 >= 100.0)
          AchievementType.ROAD_EXPLORER -> p.lifetimeDistanceMeters + 1e-6 >= 10_000.0
          AchievementType.TRAFFIC_SPECIALIST -> p.lifetimeUniqueOvertakes >= 100L
          AchievementType.CLOSE_CALL_EXPERT -> p.lifetimeUniqueNearMisses >= 25L
          AchievementType.TREASURE_HUNTER -> p.totalPhysicalCoinPickups >= 100L
          AchievementType.NITRO_CHAMPION -> p.lifetimeQualifiedManualNitroUses >= 50L
          AchievementType.GARAGE_COLLECTOR -> p.ownedVehicleIds.size >= 3
          AchievementType.MARATHON_RACER -> p.longestSingleRaceDistanceMeters + 1e-6 >= 5_000.0
        }
      if (qualifies) {
        unlockedAch += spec.id
        newlyUnlockedAchTitles += spec.title
      }
    }

    val unlockedEnvs = LinkedHashSet(p.unlockedEnvironmentIds)
    unlockedEnvs += ProgressionCatalog.BEGINNER_HIGHWAY_ID
    val newlyUnlockedEnvs = mutableListOf<String>()

    for (env in ProgressionCatalog.ENVIRONMENTS) {
      if (env.id !in unlockedEnvs && p.lifetimeDistanceMeters + 1e-6 >= env.requiredLifetimeMeters) {
        unlockedEnvs += env.id
        newlyUnlockedEnvs += env.displayName
      }
    }

    if (newlyUnlockedAchTitles.isEmpty() &&
      newlyUnlockedEnvs.isEmpty() &&
      unlockedEnvs.size == p.unlockedEnvironmentIds.size
    ) {
      return UnlockEvalResult(p, emptyList(), emptyList())
    }

    return UnlockEvalResult(
      updated =
        SaveEnvelope.canonicalizeGarageFields(
          p.copy(
            unlockedAchievementIds = unlockedAch.toList(),
            unlockedEnvironmentIds = unlockedEnvs.toList(),
          )
        ),
      newlyUnlockedAchievementTitles = newlyUnlockedAchTitles,
      newlyUnlockedEnvironmentNames = newlyUnlockedEnvs,
    )
  }

  private fun dailyMissionProgressValue(p: PlayerProgression, type: DailyMissionType): Int =
    when (type) {
      DailyMissionType.COIN_COLLECTOR -> p.dailyCoinUnitsEarned.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
      DailyMissionType.HIGHWAY_TRAVELER -> floor(p.dailyDistanceMeters + 1e-6).toInt()
      DailyMissionType.ENDURANCE_RACER -> floor(p.dailyBestEnduranceSegmentSeconds + 1e-6).toInt()
      DailyMissionType.TRAFFIC_EXPERT -> p.dailyUniqueOvertakes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
      DailyMissionType.NITRO_MASTER -> p.dailyQualifiedManualNitroUses.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

  private fun achievementProgressValue(p: PlayerProgression, type: AchievementType): Int =
    when (type) {
      AchievementType.FIRST_RIDE ->
        if (p.FIRST_RIDE_UNLOCKED_OR_COMPLETED) {
          floor(p.longestSingleRaceDistanceMeters + 1e-6).toInt().coerceAtLeast(100)
        } else if (p.lifetimeRacesCompleted > 0L) {
          floor(p.longestSingleRaceDistanceMeters + 1e-6).toInt()
        } else {
          0
        }
      AchievementType.ROAD_EXPLORER -> floor(p.lifetimeDistanceMeters + 1e-6).toInt()
      AchievementType.TRAFFIC_SPECIALIST -> p.lifetimeUniqueOvertakes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
      AchievementType.CLOSE_CALL_EXPERT -> p.lifetimeUniqueNearMisses.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
      AchievementType.TREASURE_HUNTER -> p.totalPhysicalCoinPickups.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
      AchievementType.NITRO_CHAMPION -> p.lifetimeQualifiedManualNitroUses.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
      AchievementType.GARAGE_COLLECTOR -> p.ownedVehicleIds.size
      AchievementType.MARATHON_RACER -> floor(p.longestSingleRaceDistanceMeters + 1e-6).toInt()
    }

  private val PlayerProgression.FIRST_RIDE_UNLOCKED_OR_COMPLETED: Boolean
    get() = AchievementType.FIRST_RIDE.id in unlockedAchievementIds

  private fun evaluateLoginStatusFor(p: PlayerProgression, nowMillis: Long): DailyLoginStatus =
    ProgressionCatalog.evaluateDailyLoginStatus(
      nowEpochMillis = nowMillis,
      profileTimeZoneId = p.profileTimeZoneId,
      storedStreakDay = p.dailyRewardStreakDay,
      lastClaimDateKey = p.dailyRewardLastClaimDateKey,
      lastClaimEpochMillis = p.dailyRewardLastClaimEpochMillis,
      highestObservedDateKey = p.highestObservedDateKey,
      highestObservedEpochMillis = p.highestObservedEpochMillis,
    )

  private fun hasAnyPendingLocked(): Boolean =
    pendingCredit != 0L ||
      pendingDirtyNonWallet ||
      pendingOperationIds.isNotEmpty() ||
      pendingProgression.bestScore > confirmedProgression.bestScore

  private fun flushPendingLocked(): Boolean {
    if (!hasAnyPendingLocked()) {
      if (saveStatus == SaveHealthStatus.PENDING_FLUSH) {
        saveStatus = SaveHealthStatus.DURABLE_CONFIRMED
      }
      return true
    }

    val candidatePayload = buildMergedPayloadLocked()
    val nextRev = currentRevision + 1L
    val envelope =
      SaveEnvelope(
        format = SaveEnvelope.FORMAT_ID,
        schemaVersion = SaveEnvelope.CURRENT_SCHEMA_VERSION,
        revision = nextRev,
        payload = candidatePayload,
      )
    return when (val write = persistence.writeAtomicWithBackup(envelope)) {
      is PersistenceWriteOutcome.Success -> {
        commitConfirmedLocked(candidatePayload, nextRev)
        true
      }
      is PersistenceWriteOutcome.Failure -> {
        saveStatus = SaveHealthStatus.WRITE_FAILED_UNCONFIRMED
        lastSaveError = write.reason
        false
      }
    }
  }

  private fun isAlreadyProcessedLocked(operationId: String): Boolean =
    operationId in confirmedOperationIds || operationId in pendingOperationIds

  private fun buildMergedPayloadLocked(
    extraWalletDelta: Long = 0L,
    extraOperationId: String? = null,
  ): PlayerProgression {
    val mergedOps = ArrayList<String>(confirmedOperationIds.size + pendingOperationIds.size + 1)
    mergedOps.addAll(confirmedOperationIds)
    pendingOperationIds.forEach { if (it !in confirmedOperationIds) mergedOps.add(it) }
    if (extraOperationId != null && extraOperationId !in mergedOps) {
      mergedOps.add(extraOperationId)
    }

    val nowMillis = clockMillis()
    val todayKey =
      ProgressionCatalog.localDateKeyForEpochMillis(nowMillis, pendingProgression.profileTimeZoneId)
    val highDate =
      if (pendingProgression.highestObservedDateKey.isEmpty() ||
        (ProgressionCatalog.isValidDateKey(pendingProgression.highestObservedDateKey) &&
          ProgressionCatalog.daysBetweenDateKeys(pendingProgression.highestObservedDateKey, todayKey) > 0L)
      ) {
        todayKey
      } else {
        pendingProgression.highestObservedDateKey
      }

    return SaveEnvelope.canonicalizeGarageFields(
      pendingProgression.copy(
        confirmedWalletBalance =
          (confirmedProgression.confirmedWalletBalance + pendingCredit + extraWalletDelta)
            .coerceAtLeast(0L),
        bestScore = max(confirmedProgression.bestScore, pendingProgression.bestScore),
        recentProcessedOperationIds = mergedOps.takeLast(SaveEnvelope.MAX_RECENT_OPERATION_IDS),
        updatedEpochMillis = nowMillis,
        highestObservedDateKey = highDate,
        highestObservedEpochMillis = max(pendingProgression.highestObservedEpochMillis, nowMillis),
      )
    )
  }

  private fun commitConfirmedLocked(committed: PlayerProgression, revision: Long) {
    val clean = SaveEnvelope.canonicalizeGarageFields(committed)
    confirmedProgression = clean
    pendingProgression = clean
    currentRevision = revision
    confirmedOperationIds.clear()
    confirmedOperationIds.addAll(clean.recentProcessedOperationIds)
    pendingCredit = 0L
    pendingDirtyNonWallet = false
    pendingOperationIds.clear()
    saveStatus = SaveHealthStatus.DURABLE_CONFIRMED
    lastSaveError = null
  }

  private fun buildSnapshotLocked(): WalletSnapshot {
    val nowMillis = clockMillis()
    val p = pendingProgression
    val dailyMissionItems =
      ProgressionCatalog.DAILY_MISSIONS.map { spec ->
        val progress = dailyMissionProgressValue(p, spec.type)
        val status =
          when {
            spec.id in p.dailyClaimedMissionIds -> MissionStatus.CLAIMED
            progress >= spec.targetValue -> MissionStatus.COMPLETED_UNCLAIMED
            else -> MissionStatus.IN_PROGRESS
          }
        DailyMissionItemStatus(
          spec = spec,
          currentValue = progress,
          status = status,
        )
      }

    val prevExpiredItems =
      p.previousDayExpiredMissionIds.mapNotNull { id ->
        val spec = ProgressionCatalog.findDailyMission(id) ?: return@mapNotNull null
        DailyMissionItemStatus(
          spec = spec,
          currentValue = spec.targetValue,
          status = MissionStatus.EXPIRED,
        )
      }

    val achievementItems =
      ProgressionCatalog.ACHIEVEMENTS.map { spec ->
        val unlocked = spec.id in p.unlockedAchievementIds
        val claimed = spec.id in p.claimedAchievementIds
        val currentVal =
          if (unlocked) max(spec.targetValue, achievementProgressValue(p, spec.type))
          else achievementProgressValue(p, spec.type)
        AchievementItemStatus(
          spec = spec,
          currentValue = currentVal,
          unlocked = unlocked,
          claimed = claimed,
        )
      }

    val loginStatus = evaluateLoginStatusFor(p, nowMillis)

    return WalletSnapshot(
      confirmedBalance = confirmedProgression.confirmedWalletBalance,
      pendingCredit = pendingCredit,
      bestScore = max(confirmedProgression.bestScore, p.bestScore),
      totalPhysicalCoinPickups = p.totalPhysicalCoinPickups,
      totalCollectibleCurrencyEarned = p.totalCollectibleCurrencyEarned,
      totalPowerUpsCollected = p.totalPowerUpsCollected,
      totalShieldBlocks = p.totalShieldBlocks,
      revision = currentRevision,
      saveStatus = saveStatus,
      lastSaveError = lastSaveError,
      ownedVehicleIds = confirmedProgression.ownedVehicleIds.toSet(),
      selectedVehicleId = confirmedProgression.selectedVehicleId,
      vehiclePaintIds = confirmedProgression.vehiclePaintIds.toMap(),
      vehicleUpgrades = confirmedProgression.vehicleUpgrades.toMap(),
      profileTimeZoneId = p.profileTimeZoneId,
      currentDateKey = p.dailyMissionDateKey,
      lifetimeDistanceMeters = p.lifetimeDistanceMeters,
      lifetimeActiveRaceSeconds = p.lifetimeActiveRaceSeconds,
      lifetimeRacesCompleted = p.lifetimeRacesCompleted,
      longestSingleRaceDistanceMeters = p.longestSingleRaceDistanceMeters,
      longestSingleRaceSurvivalSeconds = p.longestSingleRaceSurvivalSeconds,
      lifetimeUniqueOvertakes = p.lifetimeUniqueOvertakes,
      lifetimeUniqueNearMisses = p.lifetimeUniqueNearMisses,
      lifetimeQualifiedManualNitroUses = p.lifetimeQualifiedManualNitroUses,
      lifetimeRewardCoinsClaimed = p.lifetimeRewardCoinsClaimed,
      lifetimeUpgradesPurchased = p.lifetimeUpgradesPurchased,
      lifetimeMissionsCompleted = p.lifetimeMissionsCompleted,
      lifetimeMissionsClaimed = p.lifetimeMissionsClaimed,
      lifetimeDailyRewardsClaimed = p.lifetimeDailyRewardsClaimed,
      dailyMissions = dailyMissionItems,
      previousDayDateKey = p.previousDayDateKey,
      previousDayExpiredMissions = prevExpiredItems,
      expiredUnclaimedMissionCount = p.expiredUnclaimedMissionCount,
      achievements = achievementItems,
      unlockedEnvironmentIds = p.unlockedEnvironmentIds.toSet(),
      selectedEnvironmentId = confirmedProgression.selectedEnvironmentId,
      dailyLogin = loginStatus,
      graphicsQualityPresetId = confirmedProgression.graphicsQualityPresetId,
      audioSettings = confirmedProgression.audioSettings,
    )
  }
}
