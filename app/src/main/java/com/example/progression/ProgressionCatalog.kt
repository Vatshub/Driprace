package com.example.progression

import java.util.Calendar
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone

enum class MissionStatus {
  IN_PROGRESS,
  COMPLETED_UNCLAIMED,
  CLAIMED,
  EXPIRED,
}

enum class DailyMissionType(val id: String) {
  COIN_COLLECTOR("coin_collector"),
  HIGHWAY_TRAVELER("highway_traveler"),
  ENDURANCE_RACER("endurance_racer"),
  TRAFFIC_EXPERT("traffic_expert"),
  NITRO_MASTER("nitro_master"),
}

data class DailyMissionSpec(
  val type: DailyMissionType,
  val id: String,
  val title: String,
  val description: String,
  val targetValue: Int,
  val unitLabel: String,
  val rewardCoins: Long,
)

enum class AchievementType(val id: String) {
  FIRST_RIDE("first_ride"),
  ROAD_EXPLORER("road_explorer"),
  TRAFFIC_SPECIALIST("traffic_specialist"),
  CLOSE_CALL_EXPERT("close_call_expert"),
  TREASURE_HUNTER("treasure_hunter"),
  NITRO_CHAMPION("nitro_champion"),
  GARAGE_COLLECTOR("garage_collector"),
  MARATHON_RACER("marathon_racer"),
}

data class AchievementSpec(
  val type: AchievementType,
  val id: String,
  val title: String,
  val description: String,
  val targetValue: Int,
  val unitLabel: String,
  val rewardCoins: Long,
)

data class EnvironmentLevelSpec(
  val levelNumber: Int,
  val id: String,
  val displayName: String,
  val themeSummary: String,
  val requiredLifetimeMeters: Double,
  val skyboxRgb: FloatArray,
  val keySunColorRgb: FloatArray,
  val keySunIntensityLux: Float,
  val fillColorRgb: FloatArray,
  val fillIntensityLux: Float,
  val iblIntensityLux: Float,
)

data class DailyMissionItemStatus(
  val spec: DailyMissionSpec,
  val currentValue: Int,
  val status: MissionStatus,
) {
  val progressFraction: Float
    get() =
      if (spec.targetValue <= 0) 1f
      else (currentValue.toFloat() / spec.targetValue.toFloat()).coerceIn(0f, 1f)
}

data class AchievementItemStatus(
  val spec: AchievementSpec,
  val currentValue: Int,
  val unlocked: Boolean,
  val claimed: Boolean,
) {
  val canClaim: Boolean
    get() = unlocked && !claimed

  val progressFraction: Float
    get() =
      if (spec.targetValue <= 0) 1f
      else (currentValue.toFloat() / spec.targetValue.toFloat()).coerceIn(0f, 1f)
}

data class EnvironmentItemStatus(
  val spec: EnvironmentLevelSpec,
  val unlocked: Boolean,
  val selectedForNextRace: Boolean,
  val activeInCurrentScene: Boolean,
)

data class DailyLoginDaySlot(
  val dayNumber: Int,
  val rewardCoins: Long,
  val isClaimedInCycle: Boolean,
  val isClaimableToday: Boolean,
  val isNextTarget: Boolean,
)

data class DailyLoginStatus(
  val todayDateKey: String,
  val profileTimeZoneId: String,
  val eligibleDayNumber: Int,
  val eligibleRewardCoins: Long,
  val canClaimToday: Boolean,
  val alreadyClaimedToday: Boolean,
  val streakResetAfterSkip: Boolean,
  val clockRollbackDetected: Boolean,
  val clockNotice: String?,
  val slots: List<DailyLoginDaySlot>,
)

object ProgressionCatalog {
  const val DEFAULT_FALLBACK_TIMEZONE = "UTC"
  const val BEGINNER_HIGHWAY_ID = "beginner_highway"
  const val CITY_HIGHWAY_ID = "city_highway"
  const val DESERT_HIGHWAY_ID = "desert_highway"
  const val MOUNTAIN_HIGHWAY_ID = "mountain_highway"
  const val NIGHT_HIGHWAY_ID = "night_highway"

  const val PHASE_7_ENVIRONMENT_HONEST_NOTE =
    "Phase 7 active 3D scenery: lazy single-environment loading, Radiance .hdr -> 3-band SH IndirectLight + SAMPLER_2D sky dome, 3-LOD Pine hysteresis, 10x Mars-derived desert sand candidate, and mountain peak candidate (valley-complete-atlas-far-lod-candidate.glb >30 MB auto-loads when copied into Android Studio assets/scenery/)."

  const val PHASE_6_ENVIRONMENT_HONEST_NOTE = PHASE_7_ENVIRONMENT_HONEST_NOTE

  const val OFFLINE_CLOCK_LIMIT_NOTE =
    "Offline device clock note: calendar progression uses your profile IANA timezone locally; rolling the device clock backward blocks duplicate claims."

  private const val CLOCK_ROLLBACK_TOLERANCE_MS = 60_000L

  private val AVAILABLE_IANA_IDS: Set<String> by lazy {
    TimeZone.getAvailableIDs().toHashSet()
  }

  val DAILY_MISSIONS: List<DailyMissionSpec> =
    listOf(
      DailyMissionSpec(
        type = DailyMissionType.COIN_COLLECTOR,
        id = DailyMissionType.COIN_COLLECTOR.id,
        title = "Coin Collector",
        description = "Earn 100 coin units from physical in-race coin pickups today (includes 2x; excludes reward claims)",
        targetValue = 100,
        unitLabel = "coins",
        rewardCoins = 50L,
      ),
      DailyMissionSpec(
        type = DailyMissionType.HIGHWAY_TRAVELER,
        id = DailyMissionType.HIGHWAY_TRAVELER.id,
        title = "Highway Traveler",
        description = "Accumulate 2,000 m of driving today",
        targetValue = 2_000,
        unitLabel = "m",
        rewardCoins = 75L,
      ),
      DailyMissionSpec(
        type = DailyMissionType.ENDURANCE_RACER,
        id = DailyMissionType.ENDURANCE_RACER.id,
        title = "Endurance Racer",
        description = "Survive 180 active seconds in one race's daily eligibility segment",
        targetValue = 180,
        unitLabel = "s",
        rewardCoins = 100L,
      ),
      DailyMissionSpec(
        type = DailyMissionType.TRAFFIC_EXPERT,
        id = DailyMissionType.TRAFFIC_EXPERT.id,
        title = "Traffic Expert",
        description = "Complete 20 unique validated overtakes today",
        targetValue = 20,
        unitLabel = "overtakes",
        rewardCoins = 75L,
      ),
      DailyMissionSpec(
        type = DailyMissionType.NITRO_MASTER,
        id = DailyMissionType.NITRO_MASTER.id,
        title = "Nitro Master",
        description = "Complete 5 qualified manual Nitro boosts (>= 0.35 s each) today",
        targetValue = 5,
        unitLabel = "boosts",
        rewardCoins = 75L,
      ),
    )

  val TOTAL_DAILY_MISSION_REWARDS: Long = DAILY_MISSIONS.sumOf { it.rewardCoins } // 375L

  val ACHIEVEMENTS: List<AchievementSpec> =
    listOf(
      AchievementSpec(
        type = AchievementType.FIRST_RIDE,
        id = AchievementType.FIRST_RIDE.id,
        title = "First Ride",
        description = "End one valid race of at least 100 m",
        targetValue = 100,
        unitLabel = "m",
        rewardCoins = 50L,
      ),
      AchievementSpec(
        type = AchievementType.ROAD_EXPLORER,
        id = AchievementType.ROAD_EXPLORER.id,
        title = "Road Explorer",
        description = "Accumulate 10,000 m lifetime driving distance",
        targetValue = 10_000,
        unitLabel = "m",
        rewardCoins = 100L,
      ),
      AchievementSpec(
        type = AchievementType.TRAFFIC_SPECIALIST,
        id = AchievementType.TRAFFIC_SPECIALIST.id,
        title = "Traffic Specialist",
        description = "Complete 100 unique lifetime overtakes",
        targetValue = 100,
        unitLabel = "overtakes",
        rewardCoins = 125L,
      ),
      AchievementSpec(
        type = AchievementType.CLOSE_CALL_EXPERT,
        id = AchievementType.CLOSE_CALL_EXPERT.id,
        title = "Close Call Expert",
        description = "Complete 25 valid lifetime near misses",
        targetValue = 25,
        unitLabel = "near misses",
        rewardCoins = 100L,
      ),
      AchievementSpec(
        type = AchievementType.TREASURE_HUNTER,
        id = AchievementType.TREASURE_HUNTER.id,
        title = "Treasure Hunter",
        description = "Collect 100 physical coin pickups lifetime",
        targetValue = 100,
        unitLabel = "pickups",
        rewardCoins = 125L,
      ),
      AchievementSpec(
        type = AchievementType.NITRO_CHAMPION,
        id = AchievementType.NITRO_CHAMPION.id,
        title = "Nitro Champion",
        description = "Complete 50 qualified manual Nitro uses lifetime",
        targetValue = 50,
        unitLabel = "boosts",
        rewardCoins = 100L,
      ),
      AchievementSpec(
        type = AchievementType.GARAGE_COLLECTOR,
        id = AchievementType.GARAGE_COLLECTOR.id,
        title = "Garage Collector",
        description = "Own 3 distinct vehicles (includes Starter)",
        targetValue = 3,
        unitLabel = "cars",
        rewardCoins = 150L,
      ),
      AchievementSpec(
        type = AchievementType.MARATHON_RACER,
        id = AchievementType.MARATHON_RACER.id,
        title = "Marathon Racer",
        description = "Drive 5,000 m in a single race",
        targetValue = 5_000,
        unitLabel = "m",
        rewardCoins = 200L,
      ),
    )

  val ENVIRONMENTS: List<EnvironmentLevelSpec> =
    listOf(
      EnvironmentLevelSpec(
        levelNumber = 1,
        id = BEGINNER_HIGHWAY_ID,
        displayName = "Beginner Highway",
        themeSummary = "Countryside daylight HDR, leafy grass shoulders, 3-LOD pines, mossy rocks & distant mountain vista",
        requiredLifetimeMeters = 0.0,
        skyboxRgb = floatArrayOf(0.14f, 0.24f, 0.38f),
        keySunColorRgb = floatArrayOf(1.00f, 0.98f, 0.94f),
        keySunIntensityLux = 110_000f,
        fillColorRgb = floatArrayOf(0.90f, 0.95f, 1.00f),
        fillIntensityLux = 46_000f,
        iblIntensityLux = 42_000f,
      ),
      EnvironmentLevelSpec(
        levelNumber = 2,
        id = CITY_HIGHWAY_ID,
        displayName = "City Highway",
        themeSummary = "Urban expressway skyline clusters, skyscrapers, concrete barriers, streetlights & signs",
        requiredLifetimeMeters = 2_000.0,
        skyboxRgb = floatArrayOf(0.16f, 0.21f, 0.31f),
        keySunColorRgb = floatArrayOf(0.95f, 0.97f, 1.00f),
        keySunIntensityLux = 102_000f,
        fillColorRgb = floatArrayOf(0.82f, 0.90f, 1.00f),
        fillIntensityLux = 48_000f,
        iblIntensityLux = 40_000f,
      ),
      EnvironmentLevelSpec(
        levelNumber = 3,
        id = DESERT_HIGHWAY_ID,
        displayName = "Desert Highway",
        themeSummary = "Golden-hour sunset HDR, 10x Mars-derived prepared sand surface candidate & cliff outcrops",
        requiredLifetimeMeters = 8_000.0,
        skyboxRgb = floatArrayOf(0.36f, 0.20f, 0.11f),
        keySunColorRgb = floatArrayOf(1.00f, 0.82f, 0.58f),
        keySunIntensityLux = 118_000f,
        fillColorRgb = floatArrayOf(0.98f, 0.78f, 0.62f),
        fillIntensityLux = 44_000f,
        iblIntensityLux = 44_000f,
      ),
      EnvironmentLevelSpec(
        levelNumber = 4,
        id = MOUNTAIN_HIGHWAY_ID,
        displayName = "Mountain Highway",
        themeSummary = "Alpine cliffs, 3-LOD pine banks, mountain peak candidate & repaired valley backdrop slot",
        requiredLifetimeMeters = 20_000.0,
        skyboxRgb = floatArrayOf(0.10f, 0.25f, 0.46f),
        keySunColorRgb = floatArrayOf(0.98f, 0.99f, 1.00f),
        keySunIntensityLux = 116_000f,
        fillColorRgb = floatArrayOf(0.84f, 0.93f, 1.00f),
        fillIntensityLux = 50_000f,
        iblIntensityLux = 45_000f,
      ),
      EnvironmentLevelSpec(
        levelNumber = 5,
        id = NIGHT_HIGHWAY_ID,
        displayName = "Night Highway",
        themeSummary = "Illuminated night HDR, active overhead streetlamps, dual headlights & high-contrast road",
        requiredLifetimeMeters = 40_000.0,
        skyboxRgb = floatArrayOf(0.03f, 0.05f, 0.11f),
        keySunColorRgb = floatArrayOf(0.78f, 0.88f, 1.00f),
        keySunIntensityLux = 72_000f,
        fillColorRgb = floatArrayOf(0.65f, 0.80f, 1.00f),
        fillIntensityLux = 38_000f,
        iblIntensityLux = 36_000f,
      ),
    )

  val DAILY_REWARD_SEQUENCE: List<Long> = listOf(50L, 75L, 100L, 150L, 200L, 300L, 500L)
  val DAILY_REWARD_CYCLE_TOTAL: Long = DAILY_REWARD_SEQUENCE.sum() // 1,375L

  fun findDailyMission(id: String?): DailyMissionSpec? =
    DAILY_MISSIONS.firstOrNull { it.id == id }

  fun findAchievement(id: String?): AchievementSpec? =
    ACHIEVEMENTS.firstOrNull { it.id == id }

  fun findEnvironment(id: String?): EnvironmentLevelSpec? =
    ENVIRONMENTS.firstOrNull { it.id == id }

  fun rewardForDayNumber(dayNumber: Int): Long {
    val index = (dayNumber - 1).coerceIn(0, DAILY_REWARD_SEQUENCE.lastIndex)
    return DAILY_REWARD_SEQUENCE[index]
  }

  fun resolveValidTimeZoneId(candidate: String?): String {
    val trimmed = candidate?.trim().orEmpty()
    if (trimmed.isNotEmpty() && trimmed in AVAILABLE_IANA_IDS) {
      return trimmed
    }
    return DEFAULT_FALLBACK_TIMEZONE
  }

  fun defaultSystemTimeZoneId(): String =
    resolveValidTimeZoneId(runCatching { TimeZone.getDefault().id }.getOrNull())

  fun localDateKeyForEpochMillis(epochMillis: Long, timeZoneId: String): String {
    val validZoneId = resolveValidTimeZoneId(timeZoneId)
    val cal = GregorianCalendar(TimeZone.getTimeZone(validZoneId), Locale.US)
    cal.timeInMillis = epochMillis.coerceAtLeast(0L)
    val year = cal.get(Calendar.YEAR)
    val month = cal.get(Calendar.MONTH) + 1
    val day = cal.get(Calendar.DAY_OF_MONTH)
    return "%04d-%02d-%02d".format(Locale.US, year, month, day)
  }

  fun isValidDateKey(dateKey: String): Boolean = parseCivilDateOrNull(dateKey) != null

  /**
   * Computes exact civil calendar days from [fromDateKey] to [toDateKey] (`to - from`),
   * independent of 23-hour or 25-hour DST days.
   */
  fun daysBetweenDateKeys(fromDateKey: String, toDateKey: String): Long {
    val fromEpochDay = epochDayFromDateKey(fromDateKey)
    val toEpochDay = epochDayFromDateKey(toDateKey)
    return toEpochDay - fromEpochDay
  }

  fun epochDayFromDateKey(dateKey: String): Long {
    val triple = parseCivilDateOrNull(dateKey) ?: error("Invalid YYYY-MM-DD date key '$dateKey'.")
    return civilToEpochDay(triple.first, triple.second, triple.third)
  }

  private fun parseCivilDateOrNull(dateKey: String): Triple<Int, Int, Int>? {
    if (dateKey.length != 10 || dateKey[4] != '-' || dateKey[7] != '-') return null
    val y = dateKey.substring(0, 4).toIntOrNull() ?: return null
    val m = dateKey.substring(5, 7).toIntOrNull() ?: return null
    val d = dateKey.substring(8, 10).toIntOrNull() ?: return null
    if (y !in 1970..9999 || m !in 1..12) return null
    val maxDay =
      when (m) {
        1, 3, 5, 7, 8, 10, 12 -> 31
        4, 6, 9, 11 -> 30
        2 -> if (isLeapYear(y)) 29 else 28
        else -> return null
      }
    if (d !in 1..maxDay) return null
    return Triple(y, m, d)
  }

  private fun isLeapYear(year: Int): Boolean =
    (year % 4 == 0) && (year % 100 != 0 || year % 400 == 0)

  /** Howard Hinnant's exact civil-from-days / days-from-civil algorithm (1970-01-01 -> 0). */
  private fun civilToEpochDay(year: Int, month: Int, day: Int): Long {
    val y = year.toLong() - if (month <= 2) 1L else 0L
    val era = if (y >= 0L) y / 400L else (y - 399L) / 400L
    val yoe = y - era * 400L
    val m = month.toLong()
    val doy = (153L * (m + if (m > 2L) -3L else 9L) + 2L) / 5L + day.toLong() - 1L
    val doe = yoe * 365L + yoe / 4L - yoe / 100L + doy
    return era * 146097L + doe - 719468L
  }

  fun evaluateDailyLoginStatus(
    nowEpochMillis: Long,
    profileTimeZoneId: String,
    storedStreakDay: Int,
    lastClaimDateKey: String?,
    lastClaimEpochMillis: Long,
    highestObservedDateKey: String,
    highestObservedEpochMillis: Long,
  ): DailyLoginStatus {
    val validZone = resolveValidTimeZoneId(profileTimeZoneId)
    val todayKey = localDateKeyForEpochMillis(nowEpochMillis, validZone)
    val safeStoredDay = storedStreakDay.coerceIn(1, 7)

    val dateBehindHighest =
      highestObservedDateKey.isNotEmpty() &&
        isValidDateKey(highestObservedDateKey) &&
        daysBetweenDateKeys(highestObservedDateKey, todayKey) < 0L
    val dateBehindLastClaim =
      lastClaimDateKey != null &&
        isValidDateKey(lastClaimDateKey) &&
        daysBetweenDateKeys(lastClaimDateKey, todayKey) < 0L
    val epochRolledBack =
      (lastClaimEpochMillis > 0L && nowEpochMillis + CLOCK_ROLLBACK_TOLERANCE_MS < lastClaimEpochMillis) ||
        (highestObservedEpochMillis > 0L && nowEpochMillis + CLOCK_ROLLBACK_TOLERANCE_MS < highestObservedEpochMillis)

    val clockRollback = dateBehindHighest || dateBehindLastClaim || epochRolledBack

    var alreadyClaimedToday = false
    var streakReset = false
    val eligibleDay: Int =
      if (lastClaimDateKey == null || !isValidDateKey(lastClaimDateKey)) {
        1
      } else {
        val deltaDays = daysBetweenDateKeys(lastClaimDateKey, todayKey)
        when {
          deltaDays == 0L -> {
            alreadyClaimedToday = true
            safeStoredDay
          }
          deltaDays == 1L -> {
            (safeStoredDay % 7) + 1
          }
          deltaDays >= 2L -> {
            streakReset = true
            1
          }
          else -> {
            // Rolled back behind lastClaimDateKey
            safeStoredDay
          }
        }
      }

    val canClaim = !alreadyClaimedToday && !clockRollback
    val displayTargetDay =
      if (alreadyClaimedToday) (safeStoredDay % 7) + 1 else eligibleDay
    val rewardToday = rewardForDayNumber(eligibleDay)

    val slots =
      DAILY_REWARD_SEQUENCE.mapIndexed { idx, coins ->
        val dayNum = idx + 1
        val isClaimedInCycle =
          when {
            lastClaimDateKey == null || streakReset -> false
            alreadyClaimedToday -> dayNum <= safeStoredDay
            else -> dayNum < eligibleDay
          }
        DailyLoginDaySlot(
          dayNumber = dayNum,
          rewardCoins = coins,
          isClaimedInCycle = isClaimedInCycle,
          isClaimableToday = canClaim && dayNum == eligibleDay,
          isNextTarget = !canClaim && dayNum == displayTargetDay,
        )
      }

    val notice =
      when {
        clockRollback ->
          "Clock rollback detected: local clock is behind recorded progression time ($highestObservedDateKey). Duplicate/rolled-back daily claims are blocked."
        streakReset ->
          "Streak reset to Day 1 after a skipped calendar day (previous coin balance preserved)."
        else -> null
      }

    return DailyLoginStatus(
      todayDateKey = todayKey,
      profileTimeZoneId = validZone,
      eligibleDayNumber = eligibleDay,
      eligibleRewardCoins = rewardToday,
      canClaimToday = canClaim,
      alreadyClaimedToday = alreadyClaimedToday,
      streakResetAfterSkip = streakReset,
      clockRollbackDetected = clockRollback,
      clockNotice = notice,
      slots = slots,
    )
  }
}
