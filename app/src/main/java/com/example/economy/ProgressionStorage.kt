package com.example.economy

import android.content.Context
import com.example.audio.AudioSettingsState
import com.example.garage.HeroVehicleCatalog
import com.example.garage.PaintCatalog
import com.example.garage.VehicleUpgradeLevels
import com.example.progression.ProgressionCatalog
import com.example.scenery.GraphicsQualityPreset
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import org.json.JSONArray
import org.json.JSONObject

enum class SaveHealthStatus {
  DURABLE_CONFIRMED,
  PENDING_FLUSH,
  WRITE_FAILED_UNCONFIRMED,
  RECOVERED_FROM_BACKUP,
  CORRUPT_RESET_WARNING,
}

data class ActiveRaceCheckpoint(
  val raceId: String,
  val attributedDistanceMeters: Double = 0.0,
  val attributedActiveSeconds: Double = 0.0,
  val attributedOvertakes: Int = 0,
  val attributedNearMisses: Int = 0,
  val attributedManualNitroUses: Int = 0,
  val attributedShieldBlocks: Int = 0,
  val segmentDateKey: String = "",
  val segmentStartActiveSeconds: Double = 0.0,
  val endSettled: Boolean = false,
)

data class PlayerProgression(
  val confirmedWalletBalance: Long = 0L,
  val bestScore: Int = 0,
  val totalPhysicalCoinPickups: Long = 0L,
  val totalCollectibleCurrencyEarned: Long = 0L,
  val totalPowerUpsCollected: Long = 0L,
  val totalShieldBlocks: Long = 0L,
  val recentProcessedOperationIds: List<String> = emptyList(),
  val updatedEpochMillis: Long = 0L,
  // Phase 5 authoritative Garage progression
  val ownedVehicleIds: List<String> = listOf(HeroVehicleCatalog.STARTER_ID),
  val selectedVehicleId: String = HeroVehicleCatalog.STARTER_ID,
  val vehiclePaintIds: Map<String, String> =
    mapOf(HeroVehicleCatalog.STARTER_ID to HeroVehicleCatalog.STARTER_SPEC.defaultPaintId),
  val vehicleUpgrades: Map<String, VehicleUpgradeLevels> = emptyMap(),
  // Phase 6 authoritative Missions, Achievements, Environments, Daily Login & Lifetime Stats
  val profileTimeZoneId: String = ProgressionCatalog.defaultSystemTimeZoneId(),
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
  val unlockedAchievementIds: List<String> = emptyList(),
  val claimedAchievementIds: List<String> = emptyList(),
  val unlockedEnvironmentIds: List<String> = listOf(ProgressionCatalog.BEGINNER_HIGHWAY_ID),
  val selectedEnvironmentId: String = ProgressionCatalog.BEGINNER_HIGHWAY_ID,
  val dailyMissionDateKey: String = "",
  val dailyCoinUnitsEarned: Long = 0L,
  val dailyDistanceMeters: Double = 0.0,
  val dailyBestEnduranceSegmentSeconds: Double = 0.0,
  val dailyUniqueOvertakes: Long = 0L,
  val dailyQualifiedManualNitroUses: Long = 0L,
  val dailyCompletedCountedMissionIds: List<String> = emptyList(),
  val dailyClaimedMissionIds: List<String> = emptyList(),
  val previousDayDateKey: String = "",
  val previousDayExpiredMissionIds: List<String> = emptyList(),
  val expiredUnclaimedMissionCount: Long = 0L,
  val dailyRewardStreakDay: Int = 1,
  val dailyRewardLastClaimDateKey: String? = null,
  val dailyRewardLastClaimEpochMillis: Long = 0L,
  val highestObservedDateKey: String = "",
  val highestObservedEpochMillis: Long = 0L,
  val activeRaceCheckpoint: ActiveRaceCheckpoint? = null,
  // Phase 7 Graphics Quality preset ("auto", "high", "medium", "low")
  val graphicsQualityPresetId: String = GraphicsQualityPreset.AUTO.id,
  // Phase 8 Audio, Reduced Motion & Optional Haptics Settings
  val audioSettings: AudioSettingsState = AudioSettingsState(),
)

data class SaveEnvelope(
  val format: String = FORMAT_ID,
  val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
  val revision: Long = 0L,
  val payload: PlayerProgression = PlayerProgression(),
  val integrity: String = "",
) {
  companion object {
    const val FORMAT_ID = "DRIP_RACE_SAVE"
    const val SCHEMA_VERSION_V1 = 1
    const val SCHEMA_VERSION_V2 = 2
    const val SCHEMA_VERSION_V3 = 3
    const val SCHEMA_VERSION_V4 = 4
    const val CURRENT_SCHEMA_VERSION = 5
    const val MAX_RECENT_OPERATION_IDS = 128

    fun canonicalizeDouble(value: Double): Double {
      if (!value.isFinite() || value <= 0.0) return 0.0
      return (value * 10_000.0).roundToLong() / 10_000.0
    }

    private fun doubleUnits(value: Double): Long {
      if (!value.isFinite() || value <= 0.0) return 0L
      return (value * 10_000.0).roundToLong()
    }

    fun canonicalizeGarageFields(payload: PlayerProgression): PlayerProgression {
      val orderedOwned =
        HeroVehicleCatalog.ALL_CARS
          .map { it.id }
          .filter { id -> id == HeroVehicleCatalog.STARTER_ID || id in payload.ownedVehicleIds }

      val validSelected =
        if (payload.selectedVehicleId in orderedOwned) {
          payload.selectedVehicleId
        } else {
          HeroVehicleCatalog.STARTER_ID
        }

      val cleanPaints = LinkedHashMap<String, String>()
      for (carId in orderedOwned) {
        val spec = HeroVehicleCatalog.findById(carId) ?: continue
        val candidatePaint = payload.vehiclePaintIds[carId]
        val validPaint = PaintCatalog.findById(candidatePaint)?.id ?: spec.defaultPaintId
        cleanPaints[carId] = validPaint
      }

      val cleanUpgrades = LinkedHashMap<String, VehicleUpgradeLevels>()
      for (carId in orderedOwned) {
        val levels = payload.vehicleUpgrades[carId]
        if (levels != null &&
          (levels.engine > 0 || levels.handling > 0 || levels.brakes > 0 || levels.nitro > 0)
        ) {
          cleanUpgrades[carId] = levels
        }
      }

      val validZone = ProgressionCatalog.resolveValidTimeZoneId(payload.profileTimeZoneId)
      val lifeDist = canonicalizeDouble(payload.lifetimeDistanceMeters)
      val lifeSec = canonicalizeDouble(payload.lifetimeActiveRaceSeconds)
      val bestDist = canonicalizeDouble(payload.longestSingleRaceDistanceMeters)
      val bestSurv = canonicalizeDouble(payload.longestSingleRaceSurvivalSeconds)
      val dailyDist = canonicalizeDouble(payload.dailyDistanceMeters)
      val dailyEndurance = canonicalizeDouble(payload.dailyBestEnduranceSegmentSeconds)

      val orderedClaimedAch =
        ProgressionCatalog.ACHIEVEMENTS
          .map { it.id }
          .filter { it in payload.claimedAchievementIds }
      val orderedUnlockedAch =
        ProgressionCatalog.ACHIEVEMENTS
          .map { it.id }
          .filter { it in payload.unlockedAchievementIds || it in orderedClaimedAch }

      val orderedUnlockedEnvs =
        ProgressionCatalog.ENVIRONMENTS
          .filter { spec ->
            spec.id == ProgressionCatalog.BEGINNER_HIGHWAY_ID ||
              spec.id in payload.unlockedEnvironmentIds ||
              lifeDist + 1e-6 >= spec.requiredLifetimeMeters
          }
          .map { it.id }

      val validEnvId =
        if (payload.selectedEnvironmentId in orderedUnlockedEnvs) {
          payload.selectedEnvironmentId
        } else {
          ProgressionCatalog.BEGINNER_HIGHWAY_ID
        }

      val orderedDailyClaimed =
        ProgressionCatalog.DAILY_MISSIONS
          .map { it.id }
          .filter { it in payload.dailyClaimedMissionIds }
      val orderedDailyCompletedCounted =
        ProgressionCatalog.DAILY_MISSIONS
          .map { it.id }
          .filter { it in payload.dailyCompletedCountedMissionIds || it in orderedDailyClaimed }
      val orderedPrevExpired =
        ProgressionCatalog.DAILY_MISSIONS
          .map { it.id }
          .filter { it in payload.previousDayExpiredMissionIds }

      val cleanCheckpoint =
        payload.activeRaceCheckpoint?.takeIf { it.raceId.isNotBlank() }?.let { cp ->
          cp.copy(
            attributedDistanceMeters = canonicalizeDouble(cp.attributedDistanceMeters),
            attributedActiveSeconds = canonicalizeDouble(cp.attributedActiveSeconds),
            attributedOvertakes = cp.attributedOvertakes.coerceAtLeast(0),
            attributedNearMisses = cp.attributedNearMisses.coerceAtLeast(0),
            attributedManualNitroUses = cp.attributedManualNitroUses.coerceAtLeast(0),
            attributedShieldBlocks = cp.attributedShieldBlocks.coerceAtLeast(0),
            segmentDateKey =
              if (ProgressionCatalog.isValidDateKey(cp.segmentDateKey)) cp.segmentDateKey else "",
            segmentStartActiveSeconds = canonicalizeDouble(cp.segmentStartActiveSeconds),
          )
        }

      val minUpgradesFromState =
        cleanUpgrades.values.sumOf {
          (it.engine + it.handling + it.brakes + it.nitro).toLong()
        }

      return payload.copy(
        ownedVehicleIds = orderedOwned,
        selectedVehicleId = validSelected,
        vehiclePaintIds = cleanPaints,
        vehicleUpgrades = cleanUpgrades,
        profileTimeZoneId = validZone,
        lifetimeDistanceMeters = lifeDist,
        lifetimeActiveRaceSeconds = lifeSec,
        lifetimeRacesCompleted = payload.lifetimeRacesCompleted.coerceAtLeast(0L),
        longestSingleRaceDistanceMeters = bestDist,
        longestSingleRaceSurvivalSeconds = bestSurv,
        lifetimeUniqueOvertakes = payload.lifetimeUniqueOvertakes.coerceAtLeast(0L),
        lifetimeUniqueNearMisses = payload.lifetimeUniqueNearMisses.coerceAtLeast(0L),
        lifetimeQualifiedManualNitroUses = payload.lifetimeQualifiedManualNitroUses.coerceAtLeast(0L),
        lifetimeRewardCoinsClaimed = payload.lifetimeRewardCoinsClaimed.coerceAtLeast(0L),
        lifetimeUpgradesPurchased = max(payload.lifetimeUpgradesPurchased.coerceAtLeast(0L), minUpgradesFromState),
        lifetimeMissionsCompleted = payload.lifetimeMissionsCompleted.coerceAtLeast(0L),
        lifetimeMissionsClaimed = payload.lifetimeMissionsClaimed.coerceAtLeast(0L),
        lifetimeDailyRewardsClaimed = payload.lifetimeDailyRewardsClaimed.coerceAtLeast(0L),
        unlockedAchievementIds = orderedUnlockedAch,
        claimedAchievementIds = orderedClaimedAch,
        unlockedEnvironmentIds = orderedUnlockedEnvs,
        selectedEnvironmentId = validEnvId,
        dailyMissionDateKey =
          if (ProgressionCatalog.isValidDateKey(payload.dailyMissionDateKey)) payload.dailyMissionDateKey else "",
        dailyCoinUnitsEarned = payload.dailyCoinUnitsEarned.coerceAtLeast(0L),
        dailyDistanceMeters = dailyDist,
        dailyBestEnduranceSegmentSeconds = dailyEndurance,
        dailyUniqueOvertakes = payload.dailyUniqueOvertakes.coerceAtLeast(0L),
        dailyQualifiedManualNitroUses = payload.dailyQualifiedManualNitroUses.coerceAtLeast(0L),
        dailyCompletedCountedMissionIds = orderedDailyCompletedCounted,
        dailyClaimedMissionIds = orderedDailyClaimed,
        previousDayDateKey =
          if (ProgressionCatalog.isValidDateKey(payload.previousDayDateKey)) payload.previousDayDateKey else "",
        previousDayExpiredMissionIds = orderedPrevExpired,
        expiredUnclaimedMissionCount = payload.expiredUnclaimedMissionCount.coerceAtLeast(0L),
        dailyRewardStreakDay = payload.dailyRewardStreakDay.coerceIn(1, 7),
        dailyRewardLastClaimDateKey =
          payload.dailyRewardLastClaimDateKey?.takeIf { ProgressionCatalog.isValidDateKey(it) },
        dailyRewardLastClaimEpochMillis = payload.dailyRewardLastClaimEpochMillis.coerceAtLeast(0L),
        highestObservedDateKey =
          if (ProgressionCatalog.isValidDateKey(payload.highestObservedDateKey)) payload.highestObservedDateKey else "",
        highestObservedEpochMillis = payload.highestObservedEpochMillis.coerceAtLeast(0L),
        activeRaceCheckpoint = cleanCheckpoint,
        graphicsQualityPresetId = GraphicsQualityPreset.fromId(payload.graphicsQualityPresetId).id,
        audioSettings = canonicalizeAudioSettings(payload.audioSettings),
      )
    }

    private fun floatUnits(value: Float): Int {
      if (!value.isFinite()) return 0
      return (value.coerceIn(0f, 1f) * 10_000f).roundToInt()
    }

    private fun canonicalizeAudioSettings(raw: AudioSettingsState): AudioSettingsState {
      val c = raw.canonicalized()
      return c.copy(
        masterVolume = floatUnits(c.masterVolume) / 10_000f,
        musicVolume = floatUnits(c.musicVolume) / 10_000f,
        sfxVolume = floatUnits(c.sfxVolume) / 10_000f,
      )
    }

    fun computeIntegrityMarker(
      format: String,
      schemaVersion: Int,
      revision: Long,
      payload: PlayerProgression,
    ): String {
      val clean = canonicalizeGarageFields(payload)
      val canonical =
        buildString {
          append(format).append('|')
          append(schemaVersion).append('|')
          append(revision).append('|')
          append(payload.confirmedWalletBalance).append('|')
          append(payload.bestScore).append('|')
          append(payload.totalPhysicalCoinPickups).append('|')
          append(payload.totalCollectibleCurrencyEarned).append('|')
          append(payload.totalPowerUpsCollected).append('|')
          append(payload.totalShieldBlocks).append('|')
          append(payload.recentProcessedOperationIds.joinToString(",")).append('|')
          append(payload.updatedEpochMillis)
          if (schemaVersion >= SCHEMA_VERSION_V2) {
            append('|')
            append(clean.ownedVehicleIds.joinToString(",")).append('|')
            append(clean.selectedVehicleId).append('|')
            append(
              clean.vehiclePaintIds.entries
                .sortedBy { it.key }
                .joinToString(",") { "${it.key}=${it.value}" }
            ).append('|')
            append(
              clean.vehicleUpgrades.entries
                .sortedBy { it.key }
                .joinToString(",") { (k, v) -> "$k:${v.engine}:${v.handling}:${v.brakes}:${v.nitro}" }
            )
          }
          if (schemaVersion >= SCHEMA_VERSION_V3) {
            append('|')
            append(clean.profileTimeZoneId).append('|')
            append(doubleUnits(clean.lifetimeDistanceMeters)).append('|')
            append(doubleUnits(clean.lifetimeActiveRaceSeconds)).append('|')
            append(clean.lifetimeRacesCompleted).append('|')
            append(doubleUnits(clean.longestSingleRaceDistanceMeters)).append('|')
            append(doubleUnits(clean.longestSingleRaceSurvivalSeconds)).append('|')
            append(clean.lifetimeUniqueOvertakes).append('|')
            append(clean.lifetimeUniqueNearMisses).append('|')
            append(clean.lifetimeQualifiedManualNitroUses).append('|')
            append(clean.lifetimeRewardCoinsClaimed).append('|')
            append(clean.lifetimeUpgradesPurchased).append('|')
            append(clean.lifetimeMissionsCompleted).append('|')
            append(clean.lifetimeMissionsClaimed).append('|')
            append(clean.lifetimeDailyRewardsClaimed).append('|')
            append(clean.unlockedAchievementIds.joinToString(",")).append('|')
            append(clean.claimedAchievementIds.joinToString(",")).append('|')
            append(clean.unlockedEnvironmentIds.joinToString(",")).append('|')
            append(clean.selectedEnvironmentId).append('|')
            append(clean.dailyMissionDateKey).append('|')
            append(clean.dailyCoinUnitsEarned).append('|')
            append(doubleUnits(clean.dailyDistanceMeters)).append('|')
            append(doubleUnits(clean.dailyBestEnduranceSegmentSeconds)).append('|')
            append(clean.dailyUniqueOvertakes).append('|')
            append(clean.dailyQualifiedManualNitroUses).append('|')
            append(clean.dailyCompletedCountedMissionIds.joinToString(",")).append('|')
            append(clean.dailyClaimedMissionIds.joinToString(",")).append('|')
            append(clean.previousDayDateKey).append('|')
            append(clean.previousDayExpiredMissionIds.joinToString(",")).append('|')
            append(clean.expiredUnclaimedMissionCount).append('|')
            append(clean.dailyRewardStreakDay).append('|')
            append(clean.dailyRewardLastClaimDateKey ?: "").append('|')
            append(clean.dailyRewardLastClaimEpochMillis).append('|')
            append(clean.highestObservedDateKey).append('|')
            append(clean.highestObservedEpochMillis).append('|')
            val cp = clean.activeRaceCheckpoint
            if (cp == null) {
              append("none")
            } else {
              append(cp.raceId).append(':')
              append(doubleUnits(cp.attributedDistanceMeters)).append(':')
              append(doubleUnits(cp.attributedActiveSeconds)).append(':')
              append(cp.attributedOvertakes).append(':')
              append(cp.attributedNearMisses).append(':')
              append(cp.attributedManualNitroUses).append(':')
              append(cp.attributedShieldBlocks).append(':')
              append(cp.segmentDateKey).append(':')
              append(doubleUnits(cp.segmentStartActiveSeconds)).append(':')
              append(if (cp.endSettled) 1 else 0)
            }
          }
          if (schemaVersion >= SCHEMA_VERSION_V4) {
            append('|')
            append(clean.graphicsQualityPresetId)
          }
          if (schemaVersion >= CURRENT_SCHEMA_VERSION) {
            val a = clean.audioSettings
            append('|')
            append(floatUnits(a.masterVolume)).append(':')
            append(if (a.musicEnabled) 1 else 0).append(':')
            append(floatUnits(a.musicVolume)).append(':')
            append(if (a.sfxEnabled) 1 else 0).append(':')
            append(floatUnits(a.sfxVolume)).append(':')
            append(if (a.muted) 1 else 0).append(':')
            append(if (a.hapticsEnabled) 1 else 0).append(':')
            append(if (a.reducedMotion) 1 else 0)
          }
        }
      val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
      return digest.take(8).joinToString("") { "%02x".format(it) }
    }

    fun toJsonString(envelope: SaveEnvelope): String {
      val cleanPayload = canonicalizeGarageFields(envelope.payload)
      val canonicalEnvelope = envelope.copy(payload = cleanPayload)
      val marker =
        computeIntegrityMarker(
          format = canonicalEnvelope.format,
          schemaVersion = canonicalEnvelope.schemaVersion,
          revision = canonicalEnvelope.revision,
          payload = cleanPayload,
        )
      val payloadObj =
        JSONObject().apply {
          put("confirmedWalletBalance", cleanPayload.confirmedWalletBalance)
          put("bestScore", cleanPayload.bestScore)
          put("totalPhysicalCoinPickups", cleanPayload.totalPhysicalCoinPickups)
          put("totalCollectibleCurrencyEarned", cleanPayload.totalCollectibleCurrencyEarned)
          put("totalPowerUpsCollected", cleanPayload.totalPowerUpsCollected)
          put("totalShieldBlocks", cleanPayload.totalShieldBlocks)
          put(
            "recentProcessedOperationIds",
            JSONArray(cleanPayload.recentProcessedOperationIds.takeLast(MAX_RECENT_OPERATION_IDS)),
          )
          put("updatedEpochMillis", cleanPayload.updatedEpochMillis)
          if (canonicalEnvelope.schemaVersion >= SCHEMA_VERSION_V2) {
            put("ownedVehicleIds", JSONArray(cleanPayload.ownedVehicleIds))
            put("selectedVehicleId", cleanPayload.selectedVehicleId)
            val paintsObj = JSONObject()
            cleanPayload.vehiclePaintIds.entries.sortedBy { it.key }.forEach { (k, v) ->
              paintsObj.put(k, v)
            }
            put("vehiclePaintIds", paintsObj)
            val upgradesObj = JSONObject()
            cleanPayload.vehicleUpgrades.entries.sortedBy { it.key }.forEach { (k, v) ->
              upgradesObj.put(
                k,
                JSONObject().apply {
                  put("engine", v.engine)
                  put("handling", v.handling)
                  put("brakes", v.brakes)
                  put("nitro", v.nitro)
                },
              )
            }
            put("vehicleUpgrades", upgradesObj)
          }
          if (canonicalEnvelope.schemaVersion >= SCHEMA_VERSION_V3) {
            put("profileTimeZoneId", cleanPayload.profileTimeZoneId)
            put("lifetimeDistanceMeters", cleanPayload.lifetimeDistanceMeters)
            put("lifetimeActiveRaceSeconds", cleanPayload.lifetimeActiveRaceSeconds)
            put("lifetimeRacesCompleted", cleanPayload.lifetimeRacesCompleted)
            put("longestSingleRaceDistanceMeters", cleanPayload.longestSingleRaceDistanceMeters)
            put("longestSingleRaceSurvivalSeconds", cleanPayload.longestSingleRaceSurvivalSeconds)
            put("lifetimeUniqueOvertakes", cleanPayload.lifetimeUniqueOvertakes)
            put("lifetimeUniqueNearMisses", cleanPayload.lifetimeUniqueNearMisses)
            put("lifetimeQualifiedManualNitroUses", cleanPayload.lifetimeQualifiedManualNitroUses)
            put("lifetimeRewardCoinsClaimed", cleanPayload.lifetimeRewardCoinsClaimed)
            put("lifetimeUpgradesPurchased", cleanPayload.lifetimeUpgradesPurchased)
            put("lifetimeMissionsCompleted", cleanPayload.lifetimeMissionsCompleted)
            put("lifetimeMissionsClaimed", cleanPayload.lifetimeMissionsClaimed)
            put("lifetimeDailyRewardsClaimed", cleanPayload.lifetimeDailyRewardsClaimed)
            put("unlockedAchievementIds", JSONArray(cleanPayload.unlockedAchievementIds))
            put("claimedAchievementIds", JSONArray(cleanPayload.claimedAchievementIds))
            put("unlockedEnvironmentIds", JSONArray(cleanPayload.unlockedEnvironmentIds))
            put("selectedEnvironmentId", cleanPayload.selectedEnvironmentId)
            put("dailyMissionDateKey", cleanPayload.dailyMissionDateKey)
            put("dailyCoinUnitsEarned", cleanPayload.dailyCoinUnitsEarned)
            put("dailyDistanceMeters", cleanPayload.dailyDistanceMeters)
            put("dailyBestEnduranceSegmentSeconds", cleanPayload.dailyBestEnduranceSegmentSeconds)
            put("dailyUniqueOvertakes", cleanPayload.dailyUniqueOvertakes)
            put("dailyQualifiedManualNitroUses", cleanPayload.dailyQualifiedManualNitroUses)
            put("dailyCompletedCountedMissionIds", JSONArray(cleanPayload.dailyCompletedCountedMissionIds))
            put("dailyClaimedMissionIds", JSONArray(cleanPayload.dailyClaimedMissionIds))
            put("previousDayDateKey", cleanPayload.previousDayDateKey)
            put("previousDayExpiredMissionIds", JSONArray(cleanPayload.previousDayExpiredMissionIds))
            put("expiredUnclaimedMissionCount", cleanPayload.expiredUnclaimedMissionCount)
            put("dailyRewardStreakDay", cleanPayload.dailyRewardStreakDay)
            if (cleanPayload.dailyRewardLastClaimDateKey != null) {
              put("dailyRewardLastClaimDateKey", cleanPayload.dailyRewardLastClaimDateKey)
            } else {
              put("dailyRewardLastClaimDateKey", JSONObject.NULL)
            }
            put("dailyRewardLastClaimEpochMillis", cleanPayload.dailyRewardLastClaimEpochMillis)
            put("highestObservedDateKey", cleanPayload.highestObservedDateKey)
            put("highestObservedEpochMillis", cleanPayload.highestObservedEpochMillis)
            val cp = cleanPayload.activeRaceCheckpoint
            if (cp == null) {
              put("activeRaceCheckpoint", JSONObject.NULL)
            } else {
              put(
                "activeRaceCheckpoint",
                JSONObject().apply {
                  put("raceId", cp.raceId)
                  put("attributedDistanceMeters", cp.attributedDistanceMeters)
                  put("attributedActiveSeconds", cp.attributedActiveSeconds)
                  put("attributedOvertakes", cp.attributedOvertakes)
                  put("attributedNearMisses", cp.attributedNearMisses)
                  put("attributedManualNitroUses", cp.attributedManualNitroUses)
                  put("attributedShieldBlocks", cp.attributedShieldBlocks)
                  put("segmentDateKey", cp.segmentDateKey)
                  put("segmentStartActiveSeconds", cp.segmentStartActiveSeconds)
                  put("endSettled", cp.endSettled)
                },
              )
            }
          }
          if (canonicalEnvelope.schemaVersion >= SCHEMA_VERSION_V4) {
            put("graphicsQualityPresetId", cleanPayload.graphicsQualityPresetId)
          }
          if (canonicalEnvelope.schemaVersion >= CURRENT_SCHEMA_VERSION) {
            val a = cleanPayload.audioSettings
            put(
              "audioSettings",
              JSONObject().apply {
                put("masterVolume", a.masterVolume.toDouble())
                put("musicEnabled", a.musicEnabled)
                put("musicVolume", a.musicVolume.toDouble())
                put("sfxEnabled", a.sfxEnabled)
                put("sfxVolume", a.sfxVolume.toDouble())
                put("muted", a.muted)
                put("hapticsEnabled", a.hapticsEnabled)
                put("reducedMotion", a.reducedMotion)
              },
            )
          }
        }
      val root =
        JSONObject().apply {
          put("format", canonicalEnvelope.format)
          put("schemaVersion", canonicalEnvelope.schemaVersion)
          put("revision", canonicalEnvelope.revision)
          put("payload", payloadObj)
          put("integrity", marker)
        }
      return root.toString()
    }

    fun parseAndValidate(rawJson: String): Result<SaveEnvelope> = runCatching {
      require(rawJson.isNotBlank()) { "Save payload is blank." }
      val root = JSONObject(rawJson)
      val format = root.optString("format", "")
      require(format == FORMAT_ID) { "Unsupported save format '$format'." }

      val schemaObj = root.opt("schemaVersion")
      require(schemaObj is Number) { "Invalid schemaVersion." }
      val schemaVersion = schemaObj.toInt()
      require(schemaVersion in SCHEMA_VERSION_V1..CURRENT_SCHEMA_VERSION) {
        "Unsupported save schemaVersion $schemaVersion."
      }

      val revObj = root.opt("revision")
      require(revObj is Number) { "Invalid revision." }
      val revision = revObj.toLong()
      require(revision >= 0L) { "Negative save revision $revision." }

      val payloadObj = root.optJSONObject("payload") ?: error("Missing payload object.")
      val wallet = readNonNegativeLong(payloadObj, "confirmedWalletBalance")
      val bestScoreLong = readNonNegativeLong(payloadObj, "bestScore")
      require(bestScoreLong <= Int.MAX_VALUE) { "bestScore overflow." }
      val pickups = readNonNegativeLong(payloadObj, "totalPhysicalCoinPickups")
      val earned = readNonNegativeLong(payloadObj, "totalCollectibleCurrencyEarned")
      val powerUps = readNonNegativeLong(payloadObj, "totalPowerUpsCollected")
      val shieldBlocks = readNonNegativeLong(payloadObj, "totalShieldBlocks")
      val updatedMillis = readNonNegativeLong(payloadObj, "updatedEpochMillis")

      val opsArr = payloadObj.optJSONArray("recentProcessedOperationIds") ?: error("Missing operation ledger.")
      val ops = ArrayList<String>(opsArr.length())
      for (i in 0 until opsArr.length()) {
        val opId = opsArr.optString(i, "")
        require(opId.isNotBlank()) { "Invalid blank operationId at index $i." }
        ops += opId
      }

      val ownedList: List<String>
      val selectedId: String
      val paintsMap: Map<String, String>
      val upgradesMap: Map<String, VehicleUpgradeLevels>

      if (schemaVersion >= SCHEMA_VERSION_V2) {
        val ownedArr = payloadObj.optJSONArray("ownedVehicleIds") ?: error("Missing ownedVehicleIds.")
        val rawOwned = LinkedHashSet<String>()
        for (i in 0 until ownedArr.length()) {
          val id = ownedArr.optString(i, "")
          require(HeroVehicleCatalog.findById(id) != null) { "Unknown ownedVehicleId '$id'." }
          rawOwned += id
        }
        require(HeroVehicleCatalog.STARTER_ID in rawOwned) { "Starter vehicle must always remain owned." }
        ownedList = HeroVehicleCatalog.ALL_CARS.map { it.id }.filter { it in rawOwned }

        selectedId = payloadObj.optString("selectedVehicleId", "")
        require(selectedId in ownedList) { "selectedVehicleId '$selectedId' is not owned." }

        val paintsObj = payloadObj.optJSONObject("vehiclePaintIds") ?: error("Missing vehiclePaintIds.")
        val parsedPaints = LinkedHashMap<String, String>()
        val paintKeys = paintsObj.keys()
        while (paintKeys.hasNext()) {
          val carId = paintKeys.next()
          require(carId in ownedList) { "Paint entry for unowned vehicle '$carId'." }
          val colorId = paintsObj.optString(carId, "")
          require(PaintCatalog.findById(colorId) != null) { "Invalid paint color '$colorId'." }
          parsedPaints[carId] = colorId
        }
        paintsMap = parsedPaints

        val upgradesObj = payloadObj.optJSONObject("vehicleUpgrades") ?: error("Missing vehicleUpgrades.")
        val parsedUpgrades = LinkedHashMap<String, VehicleUpgradeLevels>()
        val upKeys = upgradesObj.keys()
        while (upKeys.hasNext()) {
          val carId = upKeys.next()
          require(carId in ownedList) { "Upgrade entry for unowned vehicle '$carId'." }
          val uObj = upgradesObj.optJSONObject(carId) ?: error("Malformed upgrade object for '$carId'.")
          val eng = readBoundedInt(uObj, "engine", 0, VehicleUpgradeLevels.MAX_LEVEL)
          val han = readBoundedInt(uObj, "handling", 0, VehicleUpgradeLevels.MAX_LEVEL)
          val brk = readBoundedInt(uObj, "brakes", 0, VehicleUpgradeLevels.MAX_LEVEL)
          val nit = readBoundedInt(uObj, "nitro", 0, VehicleUpgradeLevels.MAX_LEVEL)
          parsedUpgrades[carId] = VehicleUpgradeLevels(eng, han, brk, nit)
        }
        upgradesMap = parsedUpgrades
      } else {
        ownedList = listOf(HeroVehicleCatalog.STARTER_ID)
        selectedId = HeroVehicleCatalog.STARTER_ID
        paintsMap = mapOf(HeroVehicleCatalog.STARTER_ID to HeroVehicleCatalog.STARTER_SPEC.defaultPaintId)
        upgradesMap = emptyMap()
      }

      val basePayload =
        if (schemaVersion >= SCHEMA_VERSION_V3) {
          val tzRaw = payloadObj.optString("profileTimeZoneId", "")
          require(tzRaw.isNotBlank()) { "Missing profileTimeZoneId." }
          val tzValid = ProgressionCatalog.resolveValidTimeZoneId(tzRaw)
          require(tzValid == tzRaw) { "Invalid IANA timezone ID '$tzRaw'." }

          val lifeDist = readNonNegativeDouble(payloadObj, "lifetimeDistanceMeters")
          val lifeSec = readNonNegativeDouble(payloadObj, "lifetimeActiveRaceSeconds")
          val lifeRaces = readNonNegativeLong(payloadObj, "lifetimeRacesCompleted")
          val bestDist = readNonNegativeDouble(payloadObj, "longestSingleRaceDistanceMeters")
          val bestSurv = readNonNegativeDouble(payloadObj, "longestSingleRaceSurvivalSeconds")
          val lifeOvertakes = readNonNegativeLong(payloadObj, "lifetimeUniqueOvertakes")
          val lifeNearMisses = readNonNegativeLong(payloadObj, "lifetimeUniqueNearMisses")
          val lifeManualNitro = readNonNegativeLong(payloadObj, "lifetimeQualifiedManualNitroUses")
          val lifeRewardCoins = readNonNegativeLong(payloadObj, "lifetimeRewardCoinsClaimed")
          val lifeUpgrades = readNonNegativeLong(payloadObj, "lifetimeUpgradesPurchased")
          val lifeMissionsDone = readNonNegativeLong(payloadObj, "lifetimeMissionsCompleted")
          val lifeMissionsClaimed = readNonNegativeLong(payloadObj, "lifetimeMissionsClaimed")
          val lifeDailyClaimed = readNonNegativeLong(payloadObj, "lifetimeDailyRewardsClaimed")

          val unlockedAch =
            readValidatedIdArray(payloadObj, "unlockedAchievementIds") {
              ProgressionCatalog.findAchievement(it) != null
            }
          val claimedAch =
            readValidatedIdArray(payloadObj, "claimedAchievementIds") {
              ProgressionCatalog.findAchievement(it) != null
            }
          require(claimedAch.all { it in unlockedAch }) {
            "Claimed achievement is missing from unlockedAchievementIds."
          }

          val unlockedEnvs =
            readValidatedIdArray(payloadObj, "unlockedEnvironmentIds") {
              ProgressionCatalog.findEnvironment(it) != null
            }
          require(ProgressionCatalog.BEGINNER_HIGHWAY_ID in unlockedEnvs) {
            "Beginner Highway must always remain unlocked."
          }
          val selectedEnv = payloadObj.optString("selectedEnvironmentId", "")
          require(selectedEnv in unlockedEnvs) {
            "selectedEnvironmentId '$selectedEnv' is not unlocked."
          }

          val dailyDateKey = readOptionalDateKey(payloadObj, "dailyMissionDateKey")
          val dailyCoins = readNonNegativeLong(payloadObj, "dailyCoinUnitsEarned")
          val dailyDist = readNonNegativeDouble(payloadObj, "dailyDistanceMeters")
          val dailyEndurance = readNonNegativeDouble(payloadObj, "dailyBestEnduranceSegmentSeconds")
          val dailyOvertakes = readNonNegativeLong(payloadObj, "dailyUniqueOvertakes")
          val dailyNitros = readNonNegativeLong(payloadObj, "dailyQualifiedManualNitroUses")
          val dailyDoneIds =
            readValidatedIdArray(payloadObj, "dailyCompletedCountedMissionIds") {
              ProgressionCatalog.findDailyMission(it) != null
            }
          val dailyClaimedIds =
            readValidatedIdArray(payloadObj, "dailyClaimedMissionIds") {
              ProgressionCatalog.findDailyMission(it) != null
            }
          val prevDayKey = readOptionalDateKey(payloadObj, "previousDayDateKey")
          val prevExpiredIds =
            readValidatedIdArray(payloadObj, "previousDayExpiredMissionIds") {
              ProgressionCatalog.findDailyMission(it) != null
            }
          val expiredCount = readNonNegativeLong(payloadObj, "expiredUnclaimedMissionCount")
          val streakDay = readBoundedInt(payloadObj, "dailyRewardStreakDay", 1, 7)

          val lastClaimKeyRaw =
            if (payloadObj.isNull("dailyRewardLastClaimDateKey")) {
              null
            } else {
              val s = payloadObj.optString("dailyRewardLastClaimDateKey", "")
              require(ProgressionCatalog.isValidDateKey(s)) {
                "Invalid dailyRewardLastClaimDateKey '$s'."
              }
              s
            }
          val lastClaimMillis = readNonNegativeLong(payloadObj, "dailyRewardLastClaimEpochMillis")
          val highestDateKey = readOptionalDateKey(payloadObj, "highestObservedDateKey")
          val highestMillis = readNonNegativeLong(payloadObj, "highestObservedEpochMillis")

          val cp =
            if (payloadObj.isNull("activeRaceCheckpoint") || !payloadObj.has("activeRaceCheckpoint")) {
              null
            } else {
              val cpObj = payloadObj.optJSONObject("activeRaceCheckpoint")
                ?: error("Invalid activeRaceCheckpoint object.")
              val raceId = cpObj.optString("raceId", "")
              require(raceId.isNotBlank()) { "Blank checkpoint raceId." }
              val cpSegDate = readOptionalDateKey(cpObj, "segmentDateKey")
              ActiveRaceCheckpoint(
                raceId = raceId,
                attributedDistanceMeters = readNonNegativeDouble(cpObj, "attributedDistanceMeters"),
                attributedActiveSeconds = readNonNegativeDouble(cpObj, "attributedActiveSeconds"),
                attributedOvertakes = readBoundedInt(cpObj, "attributedOvertakes", 0, 1_000_000),
                attributedNearMisses = readBoundedInt(cpObj, "attributedNearMisses", 0, 1_000_000),
                attributedManualNitroUses = readBoundedInt(cpObj, "attributedManualNitroUses", 0, 1_000_000),
                attributedShieldBlocks = readBoundedInt(cpObj, "attributedShieldBlocks", 0, 1_000_000),
                segmentDateKey = cpSegDate,
                segmentStartActiveSeconds = readNonNegativeDouble(cpObj, "segmentStartActiveSeconds"),
                endSettled = cpObj.optBoolean("endSettled", false),
              )
            }

          PlayerProgression(
            confirmedWalletBalance = wallet,
            bestScore = bestScoreLong.toInt(),
            totalPhysicalCoinPickups = pickups,
            totalCollectibleCurrencyEarned = earned,
            totalPowerUpsCollected = powerUps,
            totalShieldBlocks = shieldBlocks,
            recentProcessedOperationIds = ops.takeLast(MAX_RECENT_OPERATION_IDS),
            updatedEpochMillis = updatedMillis,
            ownedVehicleIds = ownedList,
            selectedVehicleId = selectedId,
            vehiclePaintIds = paintsMap,
            vehicleUpgrades = upgradesMap,
            profileTimeZoneId = tzValid,
            lifetimeDistanceMeters = lifeDist,
            lifetimeActiveRaceSeconds = lifeSec,
            lifetimeRacesCompleted = lifeRaces,
            longestSingleRaceDistanceMeters = bestDist,
            longestSingleRaceSurvivalSeconds = bestSurv,
            lifetimeUniqueOvertakes = lifeOvertakes,
            lifetimeUniqueNearMisses = lifeNearMisses,
            lifetimeQualifiedManualNitroUses = lifeManualNitro,
            lifetimeRewardCoinsClaimed = lifeRewardCoins,
            lifetimeUpgradesPurchased = lifeUpgrades,
            lifetimeMissionsCompleted = lifeMissionsDone,
            lifetimeMissionsClaimed = lifeMissionsClaimed,
            lifetimeDailyRewardsClaimed = lifeDailyClaimed,
            unlockedAchievementIds = unlockedAch,
            claimedAchievementIds = claimedAch,
            unlockedEnvironmentIds = unlockedEnvs,
            selectedEnvironmentId = selectedEnv,
            dailyMissionDateKey = dailyDateKey,
            dailyCoinUnitsEarned = dailyCoins,
            dailyDistanceMeters = dailyDist,
            dailyBestEnduranceSegmentSeconds = dailyEndurance,
            dailyUniqueOvertakes = dailyOvertakes,
            dailyQualifiedManualNitroUses = dailyNitros,
            dailyCompletedCountedMissionIds = dailyDoneIds,
            dailyClaimedMissionIds = dailyClaimedIds,
            previousDayDateKey = prevDayKey,
            previousDayExpiredMissionIds = prevExpiredIds,
            expiredUnclaimedMissionCount = expiredCount,
            dailyRewardStreakDay = streakDay,
            dailyRewardLastClaimDateKey = lastClaimKeyRaw,
            dailyRewardLastClaimEpochMillis = lastClaimMillis,
            highestObservedDateKey = highestDateKey,
            highestObservedEpochMillis = highestMillis,
            activeRaceCheckpoint = cp,
            graphicsQualityPresetId =
              if (schemaVersion >= SCHEMA_VERSION_V4) {
                val rawQ = payloadObj.optString("graphicsQualityPresetId", "")
                require(GraphicsQualityPreset.entries.any { it.id == rawQ }) {
                  "Invalid graphicsQualityPresetId '$rawQ'."
                }
                rawQ
              } else {
                GraphicsQualityPreset.AUTO.id
              },
            audioSettings =
              if (schemaVersion >= CURRENT_SCHEMA_VERSION) {
                val aObj =
                  payloadObj.optJSONObject("audioSettings")
                    ?: error("Missing audioSettings object.")
                require(
                  aObj.has("musicEnabled") &&
                    aObj.has("sfxEnabled") &&
                    aObj.has("muted") &&
                    aObj.has("hapticsEnabled") &&
                    aObj.has("reducedMotion")
                ) {
                  "Missing boolean flag in audioSettings."
                }
                AudioSettingsState(
                  masterVolume = readUnitFloat(aObj, "masterVolume"),
                  musicEnabled = aObj.getBoolean("musicEnabled"),
                  musicVolume = readUnitFloat(aObj, "musicVolume"),
                  sfxEnabled = aObj.getBoolean("sfxEnabled"),
                  sfxVolume = readUnitFloat(aObj, "sfxVolume"),
                  muted = aObj.getBoolean("muted"),
                  hapticsEnabled = aObj.getBoolean("hapticsEnabled"),
                  reducedMotion = aObj.getBoolean("reducedMotion"),
                )
              } else {
                AudioSettingsState()
              },
          )
        } else {
          PlayerProgression(
            confirmedWalletBalance = wallet,
            bestScore = bestScoreLong.toInt(),
            totalPhysicalCoinPickups = pickups,
            totalCollectibleCurrencyEarned = earned,
            totalPowerUpsCollected = powerUps,
            totalShieldBlocks = shieldBlocks,
            recentProcessedOperationIds = ops.takeLast(MAX_RECENT_OPERATION_IDS),
            updatedEpochMillis = updatedMillis,
            ownedVehicleIds = ownedList,
            selectedVehicleId = selectedId,
            vehiclePaintIds = paintsMap,
            vehicleUpgrades = upgradesMap,
          )
        }

      val payload = canonicalizeGarageFields(basePayload)

      val storedIntegrity = root.optString("integrity", "")
      require(storedIntegrity.isNotBlank()) { "Missing integrity marker." }
      val expectedIntegrity =
        computeIntegrityMarker(format, schemaVersion, revision, payload)
      require(storedIntegrity.equals(expectedIntegrity, ignoreCase = true)) {
        "Save integrity check failed (corrupt or truncated snapshot)."
      }

      SaveEnvelope(
        format = format,
        schemaVersion = schemaVersion,
        revision = revision,
        payload = payload,
        integrity = expectedIntegrity,
      )
    }

    private fun readValidatedIdArray(
      obj: JSONObject,
      key: String,
      validator: (String) -> Boolean,
    ): List<String> {
      val arr = obj.optJSONArray(key) ?: error("Missing required array '$key'.")
      val result = LinkedHashSet<String>()
      for (i in 0 until arr.length()) {
        val id = arr.optString(i, "")
        require(id.isNotBlank() && validator(id)) { "Invalid id '$id' in '$key'." }
        result += id
      }
      return result.toList()
    }

    private fun readOptionalDateKey(obj: JSONObject, key: String): String {
      require(obj.has(key)) { "Missing required date field '$key'." }
      val raw = obj.optString(key, "")
      if (raw.isEmpty()) return ""
      require(ProgressionCatalog.isValidDateKey(raw)) {
        "Malformed YYYY-MM-DD date '$raw' in '$key'."
      }
      return raw
    }

    private fun readBoundedInt(obj: JSONObject, key: String, minVal: Int, maxVal: Int): Int {
      val v = readNonNegativeLong(obj, key)
      require(v in minVal.toLong()..maxVal.toLong()) {
        "Field '$key' value $v outside allowed [$minVal, $maxVal]."
      }
      return v.toInt()
    }

    private fun readUnitFloat(obj: JSONObject, key: String): Float {
      val v = readNonNegativeDouble(obj, key)
      require(v <= 1.0) { "Field '$key' must be within [0.0, 1.0] ($v)." }
      return v.toFloat()
    }

    private fun readNonNegativeDouble(obj: JSONObject, key: String): Double {
      require(obj.has(key)) { "Missing required numeric field '$key'." }
      val raw = obj.opt(key)
      require(raw is Number) { "Field '$key' is not a valid number." }
      val value = raw.toDouble()
      require(value.isFinite() && value >= 0.0) {
        "Field '$key' must be a finite non-negative number ($value)."
      }
      return canonicalizeDouble(value)
    }

    private fun readNonNegativeLong(obj: JSONObject, key: String): Long {
      require(obj.has(key)) { "Missing required numeric field '$key'." }
      val raw = obj.opt(key)
      require(raw is Number) { "Field '$key' is not a valid number." }
      if (raw is Double) {
        require(raw.isFinite() && raw >= 0.0 && raw % 1.0 == 0.0) {
          "Field '$key' must be a non-negative integer."
        }
      }
      val value = raw.toLong()
      require(value >= 0L) { "Field '$key' cannot be negative ($value)." }
      return value
    }
  }
}

sealed interface PersistenceLoadOutcome {
  data object EmptyNoSaveYet : PersistenceLoadOutcome

  data class LoadedPrimary(val envelope: SaveEnvelope) : PersistenceLoadOutcome

  data class RecoveredFromBackup(
    val envelope: SaveEnvelope,
    val primaryFailureReason: String,
  ) : PersistenceLoadOutcome

  data class CorruptUnrecoverable(val detail: String) : PersistenceLoadOutcome
}

sealed interface PersistenceWriteOutcome {
  data object Success : PersistenceWriteOutcome

  data class Failure(val reason: String) : PersistenceWriteOutcome
}

interface ProgressionPersistencePort {
  fun loadValidated(): PersistenceLoadOutcome

  fun writeAtomicWithBackup(envelope: SaveEnvelope): PersistenceWriteOutcome
}

/**
 * Validated versioned native Android private file store with atomic temp staging,
 * primary + backup snapshots, and corrupt-snapshot recovery/quarantine.
 */
class AtomicFileProgressionPersistence(context: Context) : ProgressionPersistencePort {
  private val storageDir = File(context.filesDir, DIR_NAME)
  val primaryFile: File
    get() = File(storageDir, PRIMARY_FILE_NAME)
  val backupFile: File
    get() = File(storageDir, BACKUP_FILE_NAME)
  private val tempFile: File
    get() = File(storageDir, TEMP_FILE_NAME)

  override fun loadValidated(): PersistenceLoadOutcome {
    val hasPrimary = primaryFile.exists()
    val hasBackup = backupFile.exists()
    if (!hasPrimary && !hasBackup) {
      return PersistenceLoadOutcome.EmptyNoSaveYet
    }

    var primaryError: String? = null
    if (hasPrimary) {
      val primaryResult =
        runCatching { primaryFile.readText(Charsets.UTF_8) }
          .mapCatching { SaveEnvelope.parseAndValidate(it).getOrThrow() }
      if (primaryResult.isSuccess) {
        return PersistenceLoadOutcome.LoadedPrimary(primaryResult.getOrThrow())
      }
      primaryError = primaryResult.exceptionOrNull()?.message ?: "Malformed primary save."
    } else {
      primaryError = "Primary save missing."
    }

    if (hasBackup) {
      val backupResult =
        runCatching { backupFile.readText(Charsets.UTF_8) }
          .mapCatching { SaveEnvelope.parseAndValidate(it).getOrThrow() }
      if (backupResult.isSuccess) {
        return PersistenceLoadOutcome.RecoveredFromBackup(
          envelope = backupResult.getOrThrow(),
          primaryFailureReason = primaryError,
        )
      }
      val backupErr = backupResult.exceptionOrNull()?.message ?: "Malformed backup save."
      quarantineCorruptFiles()
      return PersistenceLoadOutcome.CorruptUnrecoverable(
        "Primary invalid ($primaryError); backup invalid ($backupErr)."
      )
    }

    quarantineCorruptFiles()
    return PersistenceLoadOutcome.CorruptUnrecoverable(
      "Primary save invalid ($primaryError) and no valid backup exists."
    )
  }

  override fun writeAtomicWithBackup(envelope: SaveEnvelope): PersistenceWriteOutcome {
    return runCatching {
      if (!storageDir.exists() && !storageDir.mkdirs()) {
        error("Unable to create progression directory.")
      }
      val json = SaveEnvelope.toJsonString(envelope)
      // Verify round-trip before promoting to primary
      SaveEnvelope.parseAndValidate(json).getOrThrow()

      // Preserve last known-good primary as backup before overwriting primary
      if (primaryFile.exists()) {
        val prevText = runCatching { primaryFile.readText(Charsets.UTF_8) }.getOrNull()
        if (prevText != null && SaveEnvelope.parseAndValidate(prevText).isSuccess) {
          FileOutputStream(backupFile, false).use { fos ->
            fos.write(prevText.toByteArray(Charsets.UTF_8))
            fos.fd.sync()
          }
        }
      }

      FileOutputStream(tempFile, false).use { fos ->
        fos.write(json.toByteArray(Charsets.UTF_8))
        fos.fd.sync()
      }

      if (primaryFile.exists() && !tempFile.renameTo(primaryFile)) {
        primaryFile.writeText(json, Charsets.UTF_8)
        tempFile.delete()
      } else if (!primaryFile.exists() && !tempFile.renameTo(primaryFile)) {
        primaryFile.writeText(json, Charsets.UTF_8)
        tempFile.delete()
      }

      // Readback verification so write failures cannot report false durability
      val readBack = primaryFile.readText(Charsets.UTF_8)
      SaveEnvelope.parseAndValidate(readBack).getOrThrow()
    }.fold(
      onSuccess = { PersistenceWriteOutcome.Success },
      onFailure = { err ->
        PersistenceWriteOutcome.Failure(err.message ?: err.javaClass.simpleName)
      },
    )
  }

  private fun quarantineCorruptFiles() {
    runCatching {
      val stamp = System.currentTimeMillis()
      if (primaryFile.exists()) {
        primaryFile.renameTo(File(storageDir, "corrupt_primary_$stamp.json"))
      }
      if (backupFile.exists()) {
        backupFile.renameTo(File(storageDir, "corrupt_backup_$stamp.json"))
      }
    }
  }

  companion object {
    const val DIR_NAME = "progression"
    const val PRIMARY_FILE_NAME = "drip_race_progression.primary.json"
    const val BACKUP_FILE_NAME = "drip_race_progression.backup.json"
    const val TEMP_FILE_NAME = "drip_race_progression.tmp.json"
  }
}
