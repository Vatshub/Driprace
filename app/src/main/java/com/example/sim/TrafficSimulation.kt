package com.example.sim

import com.example.render.CoordinateMapper
import com.example.traffic.ApprovedTrafficSpec
import com.example.traffic.StaticHazardKind
import com.example.traffic.TrafficCatalog
import com.example.traffic.TrafficCategory
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

enum class TrafficEventKind {
  NONE,
  OVERTAKE,
  NEAR_MISS,
  IMPACT,
  SHIELD_BLOCKED,
  GAME_OVER,
}

data class ActiveObstacleSnapshot(
  val slotIndex: Int,
  val logicalId: Long,
  val archetypeId: String,
  val isStaticHazard: Boolean,
  val trafficSpec: ApprovedTrafficSpec?,
  val hazardKind: StaticHazardKind?,
  val laneIndex: Int,
  val x: Float,
  val widthMeters: Float,
  val heightMeters: Float,
  val lengthMeters: Float,
  val relativeZ: Double,
  val speedMetersPerSecond: Double,
  val wheelRollRadians: Double,
)

class TrafficPoolSlot(val slotIndex: Int) {
  var active: Boolean = false
  var logicalId: Long = 0L
  var archetypeId: String = ""
  var isStaticHazard: Boolean = false
  var trafficSpec: ApprovedTrafficSpec? = null
  var hazardKind: StaticHazardKind? = null
  var laneIndex: Int = 1
  var x: Float = 0f
  var widthMeters: Float = 1.85f
  var heightMeters: Float = 1.45f
  var lengthMeters: Float = 4.5f
  var relativeZ: Double = 0.0
  var speedMetersPerSecond: Double = 0.0
  var wheelRollRadians: Double = 0.0

  // Per-instance gameplay state cleared on every recycle
  var impactHandled: Boolean = false
  var wasAheadOfPlayer: Boolean = false
  var overtakenAwarded: Boolean = false
  var nearMissAwarded: Boolean = false
  var closeDodgeTriggered: Boolean = false
  var minEdgeLateralGap: Float = Float.POSITIVE_INFINITY

  val halfWidth: Float
    get() = widthMeters * 0.5f

  val halfLength: Double
    get() = lengthMeters * 0.5

  fun resetForSpawn(
    newLogicalId: Long,
    archetypeId: String,
    isStaticHazard: Boolean,
    trafficSpec: ApprovedTrafficSpec?,
    hazardKind: StaticHazardKind?,
    laneIndex: Int,
    widthMeters: Float,
    heightMeters: Float,
    lengthMeters: Float,
    relativeZ: Double,
    speedMetersPerSecond: Double,
  ) {
    this.active = true
    this.logicalId = newLogicalId
    this.archetypeId = archetypeId
    this.isStaticHazard = isStaticHazard
    this.trafficSpec = trafficSpec
    this.hazardKind = hazardKind
    this.laneIndex = laneIndex
    this.x = CoordinateMapper.LANE_CENTERS_X[laneIndex]
    this.widthMeters = widthMeters
    this.heightMeters = heightMeters
    this.lengthMeters = lengthMeters
    this.relativeZ = relativeZ
    this.speedMetersPerSecond = speedMetersPerSecond
    this.wheelRollRadians = 0.0
    this.impactHandled = false
    this.wasAheadOfPlayer = relativeZ >= 0.0
    this.overtakenAwarded = false
    this.nearMissAwarded = false
    this.closeDodgeTriggered = false
    this.minEdgeLateralGap = Float.POSITIVE_INFINITY
  }

  fun releaseToPool() {
    active = false
    archetypeId = ""
    trafficSpec = null
    hazardKind = null
    impactHandled = false
    wasAheadOfPlayer = false
    overtakenAwarded = false
    nearMissAwarded = false
    closeDodgeTriggered = false
    minEdgeLateralGap = Float.POSITIVE_INFINITY
  }

  fun toSnapshot(): ActiveObstacleSnapshot =
    ActiveObstacleSnapshot(
      slotIndex = slotIndex,
      logicalId = logicalId,
      archetypeId = archetypeId,
      isStaticHazard = isStaticHazard,
      trafficSpec = trafficSpec,
      hazardKind = hazardKind,
      laneIndex = laneIndex,
      x = x,
      widthMeters = widthMeters,
      heightMeters = heightMeters,
      lengthMeters = lengthMeters,
      relativeZ = relativeZ,
      speedMetersPerSecond = speedMetersPerSecond,
      wheelRollRadians = wheelRollRadians,
    )
}

data class EncounterCandidate(
  val laneIndex: Int,
  val relativeZ: Double,
  val widthMeters: Float,
  val heightMeters: Float,
  val lengthMeters: Float,
  val speedMetersPerSecond: Double,
  val isStaticHazard: Boolean,
  val trafficSpec: ApprovedTrafficSpec? = null,
  val hazardKind: StaticHazardKind? = null,
) {
  val halfLength: Double
    get() = lengthMeters * 0.5
}

/**
 * Pure continuous swept-AABB + reachability solver for DRIP RACE Phase 3.
 */
object CollisionAndReachabilityMath {
  /**
   * Exact Minkowski swept 2D AABB collision test over normalized sub-step time t in [0, 1].
   * Prevents high-speed tunnelling even against short static barriers or mid-lane-change sweeps.
   */
  fun intersectsSwept2d(
    prevPlayerX: Float,
    nextPlayerX: Float,
    playerHalfW: Float,
    playerHalfZ: Double,
    obsX: Float,
    obsHalfW: Float,
    prevObsRelZ: Double,
    nextObsRelZ: Double,
    obsHalfZ: Double,
  ): Boolean {
    val sumW = (playerHalfW + obsHalfW).toDouble()
    val sumZ = playerHalfZ + obsHalfZ

    // Relative vector P_rel(t) = PlayerCenter(t) - ObstacleCenter(t) in world coordinates
    // Player Z is fixed at 0; Obstacle Z goes prevObsRelZ -> nextObsRelZ
    val p0x = (prevPlayerX - obsX).toDouble()
    val vx = (nextPlayerX - prevPlayerX).toDouble()

    val p0z = -prevObsRelZ
    val vz = -(nextObsRelZ - prevObsRelZ)

    val (tMinX, tMaxX) = slabInterval(p0x, vx, sumW) ?: return false
    val (tMinZ, tMaxZ) = slabInterval(p0z, vz, sumZ) ?: return false

    val tEnter = max(0.0, max(tMinX, tMinZ))
    val tExit = min(1.0, min(tMaxX, tMaxZ))
    return tEnter <= tExit
  }

  private fun slabInterval(p0: Double, v: Double, halfExtent: Double): Pair<Double, Double>? {
    if (abs(v) < 1e-9) {
      return if (abs(p0) <= halfExtent) 0.0 to 1.0 else null
    }
    var t0 = (-halfExtent - p0) / v
    var t1 = (halfExtent - p0) / v
    if (t0 > t1) {
      val tmp = t0
      t0 = t1
      t1 = tmp
    }
    if (t1 < 0.0 || t0 > 1.0) return null
    return max(0.0, t0) to min(1.0, t1)
  }

  /**
   * Verifies that adding [candidate] alongside [activeObstacles] leaves a safe, reachable
   * trajectory from [playerTargetLane] without creating unavoidable 3-lane blockages, same-lane
   * overlaps, or unreachable pinch corridors under either current speed or Nitro boosted speed.
   */
  fun isCandidateFairAndReachable(
    candidate: EncounterCandidate,
    activeObstacles: List<ActiveObstacleSnapshot>,
    playerTargetLane: Int,
    playerSpeedMps: Double,
    vehicleConfig: VehiclePhysicsConfig,
    minReactionTimeSeconds: Double,
  ): Boolean {
    val playerHalfZ = vehicleConfig.collisionLengthMeters * 0.5
    val horizonSeconds = 6.0
    val stepDt = 0.25
    val numSteps = (horizonSeconds / stepDt).toInt()
    val reactionMarginMeters = max(4.0, playerSpeedMps * minReactionTimeSeconds * 0.35)

    // 1. Candidate must appear cleanly ahead of player reaction & braking zone
    val minSafeSpawnFrontZ =
      max(
        75.0,
        playerSpeedMps * minReactionTimeSeconds + vehicleConfig.collisionLengthMeters + 24.0,
      )
    if (candidate.relativeZ - candidate.halfLength < minSafeSpawnFrontZ) {
      return false
    }

    // 2. Same-lane non-overlap & follow-up horizon separation
    val lookaheadTime = 4.2
    for (existing in activeObstacles) {
      if (existing.laneIndex == candidate.laneIndex) {
        val existingHalfZ = existing.lengthMeters * 0.5
        val currentGap = abs(candidate.relativeZ - existing.relativeZ)
        val closingSpeed = abs(existing.speedMetersPerSecond - candidate.speedMetersPerSecond)
        val requiredGap =
          existingHalfZ + candidate.halfLength + 18.0 + closingSpeed * lookaheadTime
        if (currentGap < requiredGap) {
          return false
        }
        // Prevent faster trailing vehicle catching slower lead vehicle within visible window
        val futureExistingZ =
          existing.relativeZ - (playerSpeedMps - existing.speedMetersPerSecond) * lookaheadTime
        val futureCandidateZ =
          candidate.relativeZ - (playerSpeedMps - candidate.speedMetersPerSecond) * lookaheadTime
        if (abs(futureCandidateZ - futureExistingZ) < existingHalfZ + candidate.halfLength + 10.0) {
          return false
        }
      }
    }

    // 3. No 3-lane simultaneous/diagonal wall within reachability corridor
    val allObstacles =
      buildList(activeObstacles.size + 1) {
        activeObstacles.forEach {
          add(
            EncounterCandidate(
              laneIndex = it.laneIndex,
              relativeZ = it.relativeZ,
              widthMeters = it.widthMeters,
              heightMeters = it.heightMeters,
              lengthMeters = it.lengthMeters,
              speedMetersPerSecond = it.speedMetersPerSecond,
              isStaticHazard = it.isStaticHazard,
            )
          )
        }
        add(candidate)
      }

    // Check reachability at BOTH current speed and potential Nitro boosted speed
    val testSpeeds =
      listOf(
        max(playerSpeedMps, 20.0),
        max(playerSpeedMps, vehicleConfig.boostedTopSpeedMps),
      ).distinct()

    val laneSwitchDelaySteps =
      ceil((vehicleConfig.laneChangeDurationSeconds + minReactionTimeSeconds * 0.45) / stepDt)
        .toInt()
        .coerceAtLeast(1)

    for (testSpeed in testSpeeds) {
      // Blocked matrix [step][lane]
      val blocked = Array(numSteps + 1) { BooleanArray(3) }
      val longitudinalSafetyPad =
        playerHalfZ + (testSpeed * vehicleConfig.laneChangeDurationSeconds * 0.55) + reactionMarginMeters * 0.35

      for (k in 0..numSteps) {
        val t = k * stepDt
        for (obs in allObstacles) {
          val relZAtT = obs.relativeZ - (testSpeed - obs.speedMetersPerSecond) * t
          if (abs(relZAtT) <= obs.halfLength + longitudinalSafetyPad) {
            blocked[k][obs.laneIndex] = true
          }
        }
        // Reject if all 3 lanes are blocked in the same longitudinal reachability slice
        if (blocked[k][0] && blocked[k][1] && blocked[k][2]) {
          return false
        }
      }

      // Dynamic reachability DP: reachable[step][lane]
      val clampedStartLane = playerTargetLane.coerceIn(0, 2)
      val reachableAt = Array(numSteps + 1) { BooleanArray(3) }
      reachableAt[0][clampedStartLane] = !blocked[0][clampedStartLane] || !blocked[min(numSteps, 1)][clampedStartLane]

      for (k in 0 until numSteps) {
        for (lane in 0..2) {
          if (!reachableAt[k][lane]) continue
          if (!blocked[k + 1][lane]) {
            reachableAt[k + 1][lane] = true
          }
          val arriveStep = min(numSteps, k + laneSwitchDelaySteps)
          val midStep = (k + arriveStep) / 2
          for (adj in listOf(lane - 1, lane + 1)) {
            if (adj in 0..2 && !blocked[arriveStep][adj] && !blocked[midStep][adj]) {
              reachableAt[arriveStep][adj] = true
            }
          }
        }
      }

      val anySafeEnd = reachableAt[numSteps][0] || reachableAt[numSteps][1] || reachableAt[numSteps][2]
      if (!anySafeEnd) {
        return false
      }
    }

    return true
  }
}

/**
 * Authoritative Phase 3 traffic, static hazards, swept collisions, hearts/grace/shield hook,
 * validated unique bonuses, difficulty progression, and scoring subsystem.
 * Driven strictly by DrivingSimulation fixed 60 Hz ticks (freezes completely on Pause/GameOver).
 */
class TrafficSimulation(
  private val seed: Long = DEFAULT_SEED,
  private val autoSpawnEnabled: Boolean = true,
) {
  private val pool: List<TrafficPoolSlot> = List(MAX_POOL_SIZE) { TrafficPoolSlot(it) }
  private var rng = Random(seed)
  private var nextLogicalId: Long = 1L
  private var categoryDeckIndex: Int = 0
  private var hazardDeckIndex: Int = 0
  private var verifiedMovingTrafficIds: Set<String>? = null

  private var spawnCooldownSeconds: Double = INITIAL_SPAWN_DELAY_SECONDS

  /**
   * Restricts scheduled highway moving-traffic spawns to vehicles backed by a verified genuine
   * `.glb` model (bundled or SAF-imported) so races never flood lanes with procedural block
   * placeholders. Pass `null` (default) to rotate across all 9 catalog entries in standalone tests.
   */
  fun setVerifiedMovingTrafficIds(ids: Set<String>?) {
    verifiedMovingTrafficIds = ids?.takeIf { it.isNotEmpty() }
  }

  var hearts: Int = MAX_HEARTS
    private set
  var collisionGraceRemainingSeconds: Double = 0.0
    private set
  var shieldCharges: Int = 0
    private set
  var shieldRemainingSeconds: Double = 0.0
    private set
  var shieldBlocksCount: Int = 0
    private set
  var validUniqueOvertakes: Int = 0
    private set
  var validUniqueNearMisses: Int = 0
    private set
  var score: Int = 0
    private set
  var currentStage: DifficultyStageConfig = DifficultyDirector.STAGES.first()
    private set
  var gameOverTriggered: Boolean = false
    private set
  var lastEventKind: TrafficEventKind = TrafficEventKind.NONE
    private set
  var lastEventMessage: String = ""
    private set
  var impactCameraShakeSeconds: Double = 0.0
    private set

  fun resetForNewRace(newSeed: Long = seed) {
    rng = Random(newSeed)
    nextLogicalId = 1L
    categoryDeckIndex = 0
    hazardDeckIndex = 0
    spawnCooldownSeconds = INITIAL_SPAWN_DELAY_SECONDS
    pool.forEach { it.releaseToPool() }
    hearts = MAX_HEARTS
    collisionGraceRemainingSeconds = 0.0
    shieldCharges = 0
    shieldRemainingSeconds = 0.0
    shieldBlocksCount = 0
    validUniqueOvertakes = 0
    validUniqueNearMisses = 0
    score = 0
    currentStage = DifficultyDirector.STAGES.first()
    gameOverTriggered = false
    lastEventKind = TrafficEventKind.NONE
    lastEventMessage = ""
    impactCameraShakeSeconds = 0.0
  }

  /** Phase 4 shield pickup hook: caps at 1 charge and sets/refreshes 20s simulation duration. */
  fun grantShieldCharge(
    charges: Int = 1,
    durationSeconds: Double = PowerUpType.SHIELD.durationSeconds,
  ) {
    val clamped = charges.coerceIn(0, 1)
    shieldCharges = clamped
    shieldRemainingSeconds = if (clamped > 0) durationSeconds.coerceAtLeast(0.0) else 0.0
  }

  /**
   * Deterministic test/encounter helper for spawning a specific moving traffic vehicle or static hazard.
   * Returns assigned fresh logicalId, or null if pool is full or rejected by reachability planner.
   */
  fun trySpawnCandidate(
    candidate: EncounterCandidate,
    playerTargetLane: Int,
    playerSpeedMps: Double,
    vehicleConfig: VehiclePhysicsConfig,
    enforceReachability: Boolean = true,
  ): Long? {
    val freeSlot = pool.firstOrNull { !it.active } ?: return null
    if (enforceReachability) {
      val fair =
        CollisionAndReachabilityMath.isCandidateFairAndReachable(
          candidate = candidate,
          activeObstacles = activeSnapshots(),
          playerTargetLane = playerTargetLane,
          playerSpeedMps = playerSpeedMps,
          vehicleConfig = vehicleConfig,
          minReactionTimeSeconds = currentStage.minReactionTimeSeconds,
        )
      if (!fair) return null
    }
    val id = nextLogicalId++
    freeSlot.resetForSpawn(
      newLogicalId = id,
      archetypeId =
        candidate.trafficSpec?.id
          ?: candidate.hazardKind?.id
          ?: "custom_obstacle",
      isStaticHazard = candidate.isStaticHazard,
      trafficSpec = candidate.trafficSpec,
      hazardKind = candidate.hazardKind,
      laneIndex = candidate.laneIndex,
      widthMeters = candidate.widthMeters,
      heightMeters = candidate.heightMeters,
      lengthMeters = candidate.lengthMeters,
      relativeZ = candidate.relativeZ,
      speedMetersPerSecond = candidate.speedMetersPerSecond,
    )
    return id
  }

  fun activeSnapshots(): List<ActiveObstacleSnapshot> =
    pool.filter { it.active }.map { it.toSnapshot() }

  fun activeCount(): Int = pool.count { it.active }

  data class TickStepOutcome(
    val applyImpactSpeedPenalty: Boolean,
    val impactSpeedFactor: Double,
    val triggeredGameOverNow: Boolean,
  )

  fun stepTimersOnly(dt: Double) {
    if (gameOverTriggered) return
    if (collisionGraceRemainingSeconds > 0.0) {
      collisionGraceRemainingSeconds = max(0.0, collisionGraceRemainingSeconds - dt)
    }
    if (shieldCharges > 0 && shieldRemainingSeconds > 0.0) {
      shieldRemainingSeconds = max(0.0, shieldRemainingSeconds - dt)
      if (shieldRemainingSeconds <= 0.0) {
        shieldCharges = 0
      }
    }
    if (impactCameraShakeSeconds > 0.0) {
      impactCameraShakeSeconds = max(0.0, impactCameraShakeSeconds - dt)
    }
  }

  fun stepFixedTick(
    dt: Double,
    prevPlayerX: Float,
    nextPlayerX: Float,
    playerTargetLane: Int,
    playerSpeedMps: Double,
    distanceMeters: Double,
    vehicleConfig: VehiclePhysicsConfig,
  ): TickStepOutcome {
    if (gameOverTriggered) {
      return TickStepOutcome(false, 1.0, false)
    }

    currentStage = DifficultyDirector.stageForDistance(distanceMeters)
    stepTimersOnly(dt)

    val playerHalfW = vehicleConfig.collisionWidthMeters * 0.5f
    val playerHalfZ = vehicleConfig.collisionLengthMeters * 0.5

    var impactSlowdownNeeded = false
    var gameOverNow = false

    // 1. Advance relative movement, check swept collisions, near-misses, overtakes, and recycle
    for (slot in pool) {
      if (!slot.active) continue

      val prevRelZ = slot.relativeZ
      // Contract §5: relativeZ -= (playerSpeed - trafficSpeed) * dt
      val nextRelZ = prevRelZ - (playerSpeedMps - slot.speedMetersPerSecond) * dt
      slot.relativeZ = nextRelZ

      if (!slot.isStaticHazard && slot.speedMetersPerSecond > 0.0) {
        slot.wheelRollRadians =
          (slot.wheelRollRadians + (slot.speedMetersPerSecond * dt / DEFAULT_TRAFFIC_WHEEL_RADIUS_M)) %
            (2.0 * PI)
      }

      if (prevRelZ >= -playerHalfZ - slot.halfLength) {
        slot.wasAheadOfPlayer = true
      }

      // Close same-lane dodge detection before passing
      if (!slot.impactHandled &&
        prevRelZ > playerHalfZ + slot.halfLength &&
        prevRelZ <= playerHalfZ + slot.halfLength + CLOSE_DODGE_TRIGGER_Z_METERS &&
        abs(prevPlayerX - slot.x) < (playerHalfW + slot.halfWidth) &&
        abs(nextPlayerX - slot.x) >= (playerHalfW + slot.halfWidth) * 0.85f
      ) {
        slot.closeDodgeTriggered = true
      }

      // Continuous swept collision detection over [prev, next]
      val hit =
        CollisionAndReachabilityMath.intersectsSwept2d(
          prevPlayerX = prevPlayerX,
          nextPlayerX = nextPlayerX,
          playerHalfW = playerHalfW,
          playerHalfZ = playerHalfZ,
          obsX = slot.x,
          obsHalfW = slot.halfWidth,
          prevObsRelZ = prevRelZ,
          nextObsRelZ = nextRelZ,
          obsHalfZ = slot.halfLength,
        )

      if (hit && !slot.impactHandled) {
        slot.impactHandled = true
        if (collisionGraceRemainingSeconds > 0.0) {
          // Inside ~1s collision grace: prevent repeated damage on continuous/subsequent overlap
        } else if (shieldCharges > 0) {
          shieldCharges = 0
          shieldRemainingSeconds = 0.0
          shieldBlocksCount += 1
          collisionGraceRemainingSeconds = COLLISION_GRACE_SECONDS
          lastEventKind = TrafficEventKind.SHIELD_BLOCKED
          lastEventMessage = "SHIELD BLOCKED!"
        } else {
          hearts = max(0, hearts - 1)
          collisionGraceRemainingSeconds = COLLISION_GRACE_SECONDS
          impactCameraShakeSeconds = 0.32
          impactSlowdownNeeded = true
          if (hearts == 0 && !gameOverTriggered) {
            gameOverTriggered = true
            gameOverNow = true
            lastEventKind = TrafficEventKind.GAME_OVER
            lastEventMessage = "GAME OVER"
          } else {
            lastEventKind = TrafficEventKind.IMPACT
            lastEventMessage = "IMPACT! -1 HEART"
          }
        }
      }

      // Track collision-free lateral closeness while overlapping longitudinal pass zone
      if (!slot.impactHandled && abs(nextRelZ) <= playerHalfZ + slot.halfLength + 1.2) {
        val edgeGap = abs(nextPlayerX - slot.x) - (playerHalfW + slot.halfWidth)
        if (edgeGap >= 0f && edgeGap < slot.minEdgeLateralGap) {
          slot.minEdgeLateralGap = edgeGap
        }
      }

      // Validated unique overtake & near miss once obstacle clears behind player
      if (slot.wasAheadOfPlayer && nextRelZ + slot.halfLength < -playerHalfZ) {
        if (!slot.impactHandled) {
          if (!slot.isStaticHazard && !slot.overtakenAwarded) {
            slot.overtakenAwarded = true
            validUniqueOvertakes += 1
            if (!impactSlowdownNeeded && !gameOverNow) {
              lastEventKind = TrafficEventKind.OVERTAKE
              lastEventMessage = "OVERTAKE +30"
            }
          }
          if (!slot.nearMissAwarded &&
            (slot.minEdgeLateralGap <= NEAR_MISS_EDGE_GAP_METERS || slot.closeDodgeTriggered)
          ) {
            slot.nearMissAwarded = true
            validUniqueNearMisses += 1
            lastEventKind = TrafficEventKind.NEAR_MISS
            lastEventMessage = "NEAR MISS +50"
          }
        }
      }

      // Recycle cleanly when well behind camera or far out of range
      if (nextRelZ < RECYCLE_BEHIND_Z || nextRelZ > RECYCLE_FAR_AHEAD_Z) {
        slot.releaseToPool()
      }
    }

    // 2. Fair encounter spawning when auto-spawn is active
    if (autoSpawnEnabled && !gameOverNow) {
      spawnCooldownSeconds -= dt
      if (spawnCooldownSeconds <= 0.0) {
        val activeNow = activeCount()
        if (activeNow < currentStage.maxActiveEntities) {
          val spawned =
            attemptFairScheduledSpawn(
              playerTargetLane = playerTargetLane,
              playerSpeedMps = playerSpeedMps,
              vehicleConfig = vehicleConfig,
            )
          spawnCooldownSeconds =
            if (spawned) {
              currentStage.spawnIntervalSeconds * rng.nextDouble(0.85, 1.15)
            } else {
              // Deferred retry when fairness check rejects current candidates
              0.30
            }
        } else {
          spawnCooldownSeconds = 0.25
        }
      }
    }

    // 3. Exact Contract §6 score calculation
    score = computeContractScore(distanceMeters, validUniqueOvertakes, validUniqueNearMisses)

    return TickStepOutcome(
      applyImpactSpeedPenalty = impactSlowdownNeeded,
      impactSpeedFactor = IMPACT_SPEED_FACTOR,
      triggeredGameOverNow = gameOverNow,
    )
  }

  private fun attemptFairScheduledSpawn(
    playerTargetLane: Int,
    playerSpeedMps: Double,
    vehicleConfig: VehiclePhysicsConfig,
  ): Boolean {
    val lanes = listOf(0, 1, 2).shuffled(rng)
    val spawnStaticHazard = rng.nextDouble() < currentStage.staticHazardProbability

    val candidateBase =
      if (spawnStaticHazard) {
        val kind = StaticHazardKind.entries[hazardDeckIndex % StaticHazardKind.entries.size]
        hazardDeckIndex++
        EncounterCandidate(
          laneIndex = 1,
          relativeZ = SPAWN_MIN_Z + rng.nextDouble(0.0, SPAWN_JITTER_Z),
          widthMeters = kind.widthMeters,
          heightMeters = kind.heightMeters,
          lengthMeters = kind.lengthMeters,
          speedMetersPerSecond = 0.0,
          isStaticHazard = true,
          hazardKind = kind,
        )
      } else {
        val verifiedIds = verifiedMovingTrafficIds
        val verifiedSpecs =
          if (verifiedIds != null) {
            TrafficCatalog.APPROVED_TRAFFIC_VEHICLES.filter { it.id in verifiedIds }
          } else {
            emptyList()
          }
        val chosenSpec =
          if (verifiedSpecs.isNotEmpty()) {
            // Rotate fairly across all genuine verified 3D traffic models (SUV, Van, Pickup, Truck, Bus + any SAF imports)
            verifiedSpecs[categoryDeckIndex % verifiedSpecs.size]
          } else {
            // Standalone simulation path: rotate across all 6 categories and all 9 approved vehicles
            val categories = TrafficCategory.entries
            val cat = categories[categoryDeckIndex % categories.size]
            categoryDeckIndex++
            val specsInCategory =
              TrafficCatalog.APPROVED_TRAFFIC_VEHICLES.filter { it.category == cat }
            specsInCategory[rng.nextInt(specsInCategory.size)]
          }

        val baseRange = chosenSpec.maxCruiseSpeedMps - chosenSpec.minCruiseSpeedMps
        val scaledRange = baseRange * currentStage.speedVarianceFactor
        val minSpd = chosenSpec.minCruiseSpeedMps
        val speedMps = minSpd + rng.nextDouble(0.0, max(0.5, scaledRange))

        EncounterCandidate(
          laneIndex = 1,
          relativeZ = SPAWN_MIN_Z + rng.nextDouble(0.0, SPAWN_JITTER_Z),
          widthMeters = chosenSpec.collisionWidthMeters,
          heightMeters = chosenSpec.collisionHeightMeters,
          lengthMeters = chosenSpec.targetLengthMeters,
          speedMetersPerSecond = speedMps,
          isStaticHazard = false,
          trafficSpec = chosenSpec,
        )
      }

    for (lane in lanes) {
      val placed =
        trySpawnCandidate(
          candidate = candidateBase.copy(laneIndex = lane),
          playerTargetLane = playerTargetLane,
          playerSpeedMps = playerSpeedMps,
          vehicleConfig = vehicleConfig,
          enforceReachability = true,
        )
      if (placed != null) {
        if (!spawnStaticHazard && verifiedMovingTrafficIds != null) {
          categoryDeckIndex++
        }
        return true
      }
    }
    return false
  }

  companion object {
    const val DEFAULT_SEED = 20261116L
    const val MAX_POOL_SIZE = 12
    const val MAX_HEARTS = 3
    const val COLLISION_GRACE_SECONDS = 1.0
    const val IMPACT_SPEED_FACTOR = 0.65
    const val INITIAL_SPAWN_DELAY_SECONDS = 0.65
    const val SPAWN_MIN_Z = 155.0
    const val SPAWN_JITTER_Z = 35.0
    const val RECYCLE_BEHIND_Z = -26.0
    const val RECYCLE_FAR_AHEAD_Z = 275.0
    const val NEAR_MISS_EDGE_GAP_METERS = 1.15f
    const val CLOSE_DODGE_TRIGGER_Z_METERS = 6.5
    const val DEFAULT_TRAFFIC_WHEEL_RADIUS_M = 0.35

    fun computeContractScore(
      distanceMeters: Double,
      overtakes: Int,
      nearMisses: Int,
    ): Int = floor(distanceMeters.coerceAtLeast(0.0)).toInt() + 30 * overtakes + 50 * nearMisses
  }
}
