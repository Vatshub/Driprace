package com.example.sim

import com.example.render.CoordinateMapper
import com.example.traffic.TrafficCatalog
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

enum class CoinTier(
  val id: String,
  val displayName: String,
  val baseValue: Int,
  val defaultSpawnWeightPercent: Int,
  val radiusMeters: Float,
  val thicknessMeters: Float,
) {
  REGULAR(
    id = "coin_regular",
    displayName = "Regular Coin",
    baseValue = 10,
    defaultSpawnWeightPercent = 70,
    radiusMeters = 0.44f,
    thicknessMeters = 0.14f,
  ),
  BONUS(
    id = "coin_bonus",
    displayName = "Bonus Coin",
    baseValue = 25,
    defaultSpawnWeightPercent = 25,
    radiusMeters = 0.50f,
    thicknessMeters = 0.16f,
  ),
  RARE(
    id = "coin_rare",
    displayName = "Rare Coin",
    baseValue = 50,
    defaultSpawnWeightPercent = 5,
    radiusMeters = 0.56f,
    thicknessMeters = 0.18f,
  ),
}

data class CoinWeightConfig(
  val regularWeight: Int = CoinTier.REGULAR.defaultSpawnWeightPercent,
  val bonusWeight: Int = CoinTier.BONUS.defaultSpawnWeightPercent,
  val rareWeight: Int = CoinTier.RARE.defaultSpawnWeightPercent,
) {
  init {
    require(regularWeight >= 0 && bonusWeight >= 0 && rareWeight >= 0) {
      "Coin weights must be non-negative."
    }
    require(regularWeight + bonusWeight + rareWeight > 0) {
      "At least one coin tier must have positive weight."
    }
  }

  val totalWeight: Int
    get() = regularWeight + bonusWeight + rareWeight

  fun pickWeightedTier(rng: Random): CoinTier {
    val roll = rng.nextInt(totalWeight)
    return when {
      roll < regularWeight -> CoinTier.REGULAR
      roll < regularWeight + bonusWeight -> CoinTier.BONUS
      else -> CoinTier.RARE
    }
  }
}

enum class PowerUpType(
  val id: String,
  val displayName: String,
  val durationSeconds: Double,
  val radiusMeters: Float,
) {
  AUTOMATIC_NITRO(
    id = "powerup_auto_nitro",
    displayName = "Auto Nitro",
    durationSeconds = 3.0,
    radiusMeters = 0.58f,
  ),
  MAGNET(
    id = "powerup_magnet",
    displayName = "Magnet",
    durationSeconds = 15.0,
    radiusMeters = 0.58f,
  ),
  SHIELD(
    id = "powerup_shield",
    displayName = "Shield",
    durationSeconds = 20.0,
    radiusMeters = 0.58f,
  ),
  DOUBLE_COINS(
    id = "powerup_double_coins",
    displayName = "2x Coins",
    durationSeconds = 20.0,
    radiusMeters = 0.58f,
  ),
}

enum class PickupKind {
  COIN,
  POWER_UP,
}

data class ActivePickupSnapshot(
  val slotIndex: Int,
  val logicalId: Long,
  val operationId: String,
  val archetypeId: String,
  val kind: PickupKind,
  val coinTier: CoinTier?,
  val powerUpType: PowerUpType?,
  val laneIndex: Int,
  val x: Float,
  val y: Float,
  val relativeZ: Double,
  val radiusMeters: Float,
  val reservedByMagnet: Boolean,
)

sealed interface PickupCollectedEvent {
  val logicalId: Long
  val operationId: String

  data class CoinCollected(
    override val logicalId: Long,
    override val operationId: String,
    val coinTier: CoinTier,
    val baseValue: Int,
    val multiplierApplied: Int,
    val awardedAmount: Int,
    val collectedViaMagnet: Boolean,
  ) : PickupCollectedEvent

  data class PowerUpCollected(
    override val logicalId: Long,
    override val operationId: String,
    val powerUpType: PowerUpType,
  ) : PickupCollectedEvent
}

class PickupPoolSlot(val slotIndex: Int) {
  var active: Boolean = false
  var logicalId: Long = 0L
  var operationId: String = ""
  var kind: PickupKind = PickupKind.COIN
  var coinTier: CoinTier? = null
  var powerUpType: PowerUpType? = null
  var laneIndex: Int = 1
  var x: Float = 0f
  var y: Float = ROAD_PICKUP_Y_METERS
  var relativeZ: Double = 0.0
  var radiusMeters: Float = 0.44f
  var collected: Boolean = false
  var reservedByMagnet: Boolean = false

  fun resetForSpawn(
    newLogicalId: Long,
    newOperationId: String,
    kind: PickupKind,
    coinTier: CoinTier?,
    powerUpType: PowerUpType?,
    laneIndex: Int,
    relativeZ: Double,
  ) {
    this.active = true
    this.logicalId = newLogicalId
    this.operationId = newOperationId
    this.kind = kind
    this.coinTier = coinTier
    this.powerUpType = powerUpType
    this.laneIndex = laneIndex
    this.x = CoordinateMapper.LANE_CENTERS_X[laneIndex]
    this.y = ROAD_PICKUP_Y_METERS
    this.relativeZ = relativeZ
    this.radiusMeters = coinTier?.radiusMeters ?: powerUpType?.radiusMeters ?: 0.50f
    this.collected = false
    this.reservedByMagnet = false
  }

  fun releaseToPool() {
    active = false
    operationId = ""
    coinTier = null
    powerUpType = null
    collected = false
    reservedByMagnet = false
  }

  fun toSnapshot(): ActivePickupSnapshot =
    ActivePickupSnapshot(
      slotIndex = slotIndex,
      logicalId = logicalId,
      operationId = operationId,
      archetypeId = coinTier?.id ?: powerUpType?.id ?: "coin_regular",
      kind = kind,
      coinTier = coinTier,
      powerUpType = powerUpType,
      laneIndex = laneIndex,
      x = x,
      y = y,
      relativeZ = relativeZ,
      radiusMeters = radiusMeters,
      reservedByMagnet = reservedByMagnet,
    )

  companion object {
    const val ROAD_PICKUP_Y_METERS = 0.62f
  }
}

/**
 * Authoritative Phase 4 bounded pickup pools (coins + 4 power-ups), fair obstacle-free spawning,
 * continuous 3D spatial/swept pickup collection, Magnet attraction reservation, and Double Coins rule.
 */
class PickupSimulation(
  private val seed: Long = DEFAULT_SEED,
  private val autoSpawnEnabled: Boolean = true,
  val coinWeights: CoinWeightConfig = CoinWeightConfig(),
) {
  private val coinPool = List(MAX_COIN_POOL_SIZE) { PickupPoolSlot(it) }
  private val powerUpPool = List(MAX_POWERUP_POOL_SIZE) { PickupPoolSlot(it) }
  private var rng = Random(seed)
  private var raceSequenceId: Long = 1L
  private var nextLogicalId: Long = 1L
  private var powerUpDeckIndex: Int = 0

  private var coinSpawnCooldownSeconds: Double = INITIAL_COIN_DELAY_SECONDS
  private var powerUpSpawnCooldownSeconds: Double = INITIAL_POWERUP_DELAY_SECONDS

  var doubleCoinsRemainingSeconds: Double = 0.0
    private set
  var magnetRemainingSeconds: Double = 0.0
    private set

  var runCoinsEarned: Int = 0
    private set
  var runCoinPickupsCollected: Int = 0
    private set
  var runPowerUpsCollected: Int = 0
    private set
  var lastPickupBanner: String = ""
    private set

  private val processedLogicalIds = HashSet<Long>()

  fun resetForNewRace(newSeed: Long = seed, incrementRaceSequence: Boolean = true) {
    rng = Random(newSeed)
    if (incrementRaceSequence) {
      raceSequenceId += 1L
    }
    nextLogicalId = 1L
    powerUpDeckIndex = 0
    coinSpawnCooldownSeconds = INITIAL_COIN_DELAY_SECONDS
    powerUpSpawnCooldownSeconds = INITIAL_POWERUP_DELAY_SECONDS
    coinPool.forEach { it.releaseToPool() }
    powerUpPool.forEach { it.releaseToPool() }
    doubleCoinsRemainingSeconds = 0.0
    magnetRemainingSeconds = 0.0
    runCoinsEarned = 0
    runCoinPickupsCollected = 0
    runPowerUpsCollected = 0
    lastPickupBanner = ""
    processedLogicalIds.clear()
  }

  fun activatePowerUp(type: PowerUpType) {
    when (type) {
      PowerUpType.MAGNET -> magnetRemainingSeconds = type.durationSeconds
      PowerUpType.DOUBLE_COINS -> doubleCoinsRemainingSeconds = type.durationSeconds
      PowerUpType.AUTOMATIC_NITRO, PowerUpType.SHIELD -> Unit // Handled by NitroController / TrafficSimulation
    }
  }

  fun activeSnapshots(): List<ActivePickupSnapshot> =
    buildList(MAX_COIN_POOL_SIZE + MAX_POWERUP_POOL_SIZE) {
      coinPool.forEach { if (it.active && !it.collected) add(it.toSnapshot()) }
      powerUpPool.forEach { if (it.active && !it.collected) add(it.toSnapshot()) }
    }

  fun activeCoinCount(): Int = coinPool.count { it.active && !it.collected }

  fun activePowerUpCount(): Int = powerUpPool.count { it.active && !it.collected }

  /**
   * Fair placement check: rejects placing pickups inside or dangerously close to active hazards/traffic
   * or overlapping already-active pickups in the same lane.
   */
  fun isPlacementFair(
    laneIndex: Int,
    relativeZ: Double,
    activeObstacles: List<ActiveObstacleSnapshot>,
  ): Boolean {
    if (laneIndex !in 0..2) return false
    for (obs in activeObstacles) {
      if (obs.laneIndex == laneIndex) {
        val requiredHalfClearance =
          obs.lengthMeters * 0.5 +
            if (obs.isStaticHazard) STATIC_HAZARD_PICKUP_CLEARANCE_METERS else TRAFFIC_PICKUP_CLEARANCE_METERS
        if (abs(obs.relativeZ - relativeZ) < requiredHalfClearance) {
          return false
        }
      }
    }
    for (slot in coinPool) {
      if (slot.active && !slot.collected && slot.laneIndex == laneIndex) {
        if (abs(slot.relativeZ - relativeZ) < MIN_SAME_LANE_PICKUP_GAP_METERS) {
          return false
        }
      }
    }
    for (slot in powerUpPool) {
      if (slot.active && !slot.collected && slot.laneIndex == laneIndex) {
        if (abs(slot.relativeZ - relativeZ) < MIN_SAME_LANE_PICKUP_GAP_METERS) {
          return false
        }
      }
    }
    return true
  }

  fun trySpawnCoin(
    tier: CoinTier,
    laneIndex: Int,
    relativeZ: Double,
    activeObstacles: List<ActiveObstacleSnapshot> = emptyList(),
    enforceFairPlacement: Boolean = true,
  ): ActivePickupSnapshot? {
    if (enforceFairPlacement && !isPlacementFair(laneIndex, relativeZ, activeObstacles)) {
      return null
    }
    val freeSlot = coinPool.firstOrNull { !it.active } ?: return null
    val id = nextLogicalId++
    val opId = "race_${raceSequenceId}_coin_${id}"
    freeSlot.resetForSpawn(
      newLogicalId = id,
      newOperationId = opId,
      kind = PickupKind.COIN,
      coinTier = tier,
      powerUpType = null,
      laneIndex = laneIndex.coerceIn(0, 2),
      relativeZ = relativeZ,
    )
    return freeSlot.toSnapshot()
  }

  fun trySpawnPowerUp(
    type: PowerUpType,
    laneIndex: Int,
    relativeZ: Double,
    activeObstacles: List<ActiveObstacleSnapshot> = emptyList(),
    enforceFairPlacement: Boolean = true,
  ): ActivePickupSnapshot? {
    if (enforceFairPlacement && !isPlacementFair(laneIndex, relativeZ, activeObstacles)) {
      return null
    }
    val freeSlot = powerUpPool.firstOrNull { !it.active } ?: return null
    val id = nextLogicalId++
    val opId = "race_${raceSequenceId}_powerup_${id}"
    freeSlot.resetForSpawn(
      newLogicalId = id,
      newOperationId = opId,
      kind = PickupKind.POWER_UP,
      coinTier = null,
      powerUpType = type,
      laneIndex = laneIndex.coerceIn(0, 2),
      relativeZ = relativeZ,
    )
    return freeSlot.toSnapshot()
  }

  fun stepFixedTick(
    dt: Double,
    prevPlayerX: Float,
    nextPlayerX: Float,
    playerSpeedMps: Double,
    activeObstacles: List<ActiveObstacleSnapshot>,
    vehicleConfig: VehiclePhysicsConfig = VehiclePhysicsConfig.STARTER_SPORTS,
  ): List<PickupCollectedEvent> {
    if (doubleCoinsRemainingSeconds > 0.0) {
      doubleCoinsRemainingSeconds = max(0.0, doubleCoinsRemainingSeconds - dt)
    }
    if (magnetRemainingSeconds > 0.0) {
      magnetRemainingSeconds = max(0.0, magnetRemainingSeconds - dt)
    }

    val collectedEvents = mutableListOf<PickupCollectedEvent>()
    val playerHalfW = vehicleConfig.collisionWidthMeters * 0.5f
    val playerHalfZ = vehicleConfig.collisionLengthMeters * 0.5
    val playerHeightM = vehicleConfig.collisionHeightMeters

    // 1. Advance coins, reserve under Magnet attraction, and collect via swept 3D AABB
    for (slot in coinPool) {
      if (!slot.active) continue
      if (slot.collected) {
        slot.releaseToPool()
        continue
      }

      val prevX = slot.x
      val prevRelZ = slot.relativeZ
      var nextRelZ = prevRelZ - playerSpeedMps * dt
      var nextX = prevX

      // Check initial Magnet attraction region (~5.5 m lateral, ~15 m forward)
      if (!slot.reservedByMagnet && magnetRemainingSeconds > 0.0) {
        val lateralDist = abs(prevX - nextPlayerX)
        if (lateralDist <= MAGNET_LATERAL_RANGE_METERS &&
          nextRelZ >= -playerHalfZ &&
          nextRelZ <= MAGNET_FORWARD_RANGE_METERS
        ) {
          slot.reservedByMagnet = true
        }
      }

      // Visual + spatial fly-to-car pull when reserved by Magnet
      if (slot.reservedByMagnet) {
        val dx = nextPlayerX - prevX
        val dz = (0.0 - nextRelZ).toFloat()
        val dist = sqrt(dx * dx + dz * dz)
        val pullStep = (MAGNET_PULL_SPEED_MPS * dt).toFloat()
        if (dist <= pullStep + 0.55f) {
          nextX = nextPlayerX
          nextRelZ = 0.0
        } else if (dist > 1e-4f) {
          val ratio = pullStep / dist
          nextX = prevX + dx * ratio
          nextRelZ = nextRelZ + dz * ratio
        }
      }

      slot.x = nextX
      slot.relativeZ = nextRelZ

      val verticalOverlap =
        slot.y + slot.radiusMeters >= 0f &&
          slot.y - slot.radiusMeters <= playerHeightM

      val sweptHit =
        verticalOverlap &&
          CollisionAndReachabilityMath.intersectsSwept2d(
            prevPlayerX = prevPlayerX,
            nextPlayerX = nextPlayerX,
            playerHalfW = playerHalfW,
            playerHalfZ = playerHalfZ,
            obsX = nextX,
            obsHalfW = slot.radiusMeters,
            prevObsRelZ = prevRelZ,
            nextObsRelZ = nextRelZ,
            obsHalfZ = slot.radiusMeters.toDouble(),
          )

      // Reserved magnet coins that sweep past player origin are collected cleanly at most once
      val magnetCapture =
        slot.reservedByMagnet &&
          verticalOverlap &&
          (sweptHit || nextRelZ <= playerHalfZ * 0.5)

      if ((sweptHit || magnetCapture) && processedLogicalIds.add(slot.logicalId)) {
        slot.collected = true
        val tier = slot.coinTier ?: CoinTier.REGULAR
        val multiplier = if (doubleCoinsRemainingSeconds > 0.0) 2 else 1
        val awarded = tier.baseValue * multiplier
        runCoinsEarned += awarded
        runCoinPickupsCollected += 1
        lastPickupBanner =
          if (multiplier > 1) "+$awarded ${tier.displayName} (2x)" else "+$awarded ${tier.displayName}"
        collectedEvents +=
          PickupCollectedEvent.CoinCollected(
            logicalId = slot.logicalId,
            operationId = slot.operationId,
            coinTier = tier,
            baseValue = tier.baseValue,
            multiplierApplied = multiplier,
            awardedAmount = awarded,
            collectedViaMagnet = slot.reservedByMagnet,
          )
        slot.releaseToPool()
      } else if (nextRelZ < RECYCLE_BEHIND_Z || nextRelZ > RECYCLE_FAR_AHEAD_Z) {
        slot.releaseToPool()
      }
    }

    // 2. Advance power-ups and collect via continuous 3D spatial/swept bounds
    for (slot in powerUpPool) {
      if (!slot.active) continue
      if (slot.collected) {
        slot.releaseToPool()
        continue
      }

      val prevRelZ = slot.relativeZ
      val nextRelZ = prevRelZ - playerSpeedMps * dt
      slot.relativeZ = nextRelZ

      val verticalOverlap =
        slot.y + slot.radiusMeters >= 0f &&
          slot.y - slot.radiusMeters <= playerHeightM

      val hit =
        verticalOverlap &&
          CollisionAndReachabilityMath.intersectsSwept2d(
            prevPlayerX = prevPlayerX,
            nextPlayerX = nextPlayerX,
            playerHalfW = playerHalfW,
            playerHalfZ = playerHalfZ,
            obsX = slot.x,
            obsHalfW = slot.radiusMeters,
            prevObsRelZ = prevRelZ,
            nextObsRelZ = nextRelZ,
            obsHalfZ = slot.radiusMeters.toDouble(),
          )

      if (hit && processedLogicalIds.add(slot.logicalId)) {
        slot.collected = true
        val type = slot.powerUpType ?: PowerUpType.SHIELD
        activatePowerUp(type)
        runPowerUpsCollected += 1
        lastPickupBanner = "${type.displayName} (${type.durationSeconds.toInt()}s)"
        collectedEvents +=
          PickupCollectedEvent.PowerUpCollected(
            logicalId = slot.logicalId,
            operationId = slot.operationId,
            powerUpType = type,
          )
        slot.releaseToPool()
      } else if (nextRelZ < RECYCLE_BEHIND_Z || nextRelZ > RECYCLE_FAR_AHEAD_Z) {
        slot.releaseToPool()
      }
    }

    // 3. Fair auto-spawning for coins & power-ups
    if (autoSpawnEnabled) {
      coinSpawnCooldownSeconds -= dt
      if (coinSpawnCooldownSeconds <= 0.0) {
        val spawnedLine = spawnFairCoinLine(activeObstacles)
        coinSpawnCooldownSeconds = if (spawnedLine) rng.nextDouble(2.2, 3.8) else 0.55
      }

      powerUpSpawnCooldownSeconds -= dt
      if (powerUpSpawnCooldownSeconds <= 0.0) {
        val spawnedPower = spawnFairPowerUp(activeObstacles)
        powerUpSpawnCooldownSeconds = if (spawnedPower) rng.nextDouble(11.0, 16.5) else 1.0
      }
    }

    return collectedEvents
  }

  private fun spawnFairCoinLine(activeObstacles: List<ActiveObstacleSnapshot>): Boolean {
    val lanes = listOf(0, 1, 2).shuffled(rng)
    val baseZ = PICKUP_SPAWN_MIN_Z + rng.nextDouble(0.0, 24.0)
    val lineCount = rng.nextInt(3, 6)
    for (lane in lanes) {
      var allSafe = true
      for (i in 0 until lineCount) {
        val z = baseZ + i * COIN_LINE_SPACING_METERS
        if (!isPlacementFair(lane, z, activeObstacles)) {
          allSafe = false
          break
        }
      }
      if (allSafe) {
        var anySpawned = false
        for (i in 0 until lineCount) {
          val tier = coinWeights.pickWeightedTier(rng)
          val placed =
            trySpawnCoin(
              tier = tier,
              laneIndex = lane,
              relativeZ = baseZ + i * COIN_LINE_SPACING_METERS,
              activeObstacles = activeObstacles,
              enforceFairPlacement = false,
            )
          if (placed != null) anySpawned = true
        }
        return anySpawned
      }
    }
    return false
  }

  private fun spawnFairPowerUp(activeObstacles: List<ActiveObstacleSnapshot>): Boolean {
    val lanes = listOf(0, 1, 2).shuffled(rng)
    val z = PICKUP_SPAWN_MIN_Z + rng.nextDouble(8.0, 32.0)
    val type = PowerUpType.entries[powerUpDeckIndex % PowerUpType.entries.size]
    for (lane in lanes) {
      val placed =
        trySpawnPowerUp(
          type = type,
          laneIndex = lane,
          relativeZ = z,
          activeObstacles = activeObstacles,
          enforceFairPlacement = true,
        )
      if (placed != null) {
        powerUpDeckIndex++
        return true
      }
    }
    return false
  }

  companion object {
    const val DEFAULT_SEED = 20261117L
    const val MAX_COIN_POOL_SIZE = 24
    const val MAX_POWERUP_POOL_SIZE = 6
    const val MAGNET_LATERAL_RANGE_METERS = 5.5f
    const val MAGNET_FORWARD_RANGE_METERS = 15.0
    const val MAGNET_PULL_SPEED_MPS = 42.0
    const val STATIC_HAZARD_PICKUP_CLEARANCE_METERS = 14.0
    const val TRAFFIC_PICKUP_CLEARANCE_METERS = 10.0
    const val MIN_SAME_LANE_PICKUP_GAP_METERS = 3.2
    const val COIN_LINE_SPACING_METERS = 4.2
    const val INITIAL_COIN_DELAY_SECONDS = 1.2
    const val INITIAL_POWERUP_DELAY_SECONDS = 6.5
    const val PICKUP_SPAWN_MIN_Z = 148.0
    const val RECYCLE_BEHIND_Z = -18.0
    const val RECYCLE_FAR_AHEAD_Z = 275.0
  }
}
