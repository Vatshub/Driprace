package com.example.sim

import com.example.render.CoordinateMapper
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min

data class VehiclePhysicsConfig(
  val id: String,
  val displayName: String,
  val topSpeedKmh: Double,
  val accelerationMps2: Double,
  val brakeDecelerationMps2: Double,
  val laneChangeDurationSeconds: Double,
  val nitroDrainPerSecond: Double,
  val nitroRegenPerSecond: Double,
  val nitroSpeedMultiplier: Double,
  val nitroAccelMultiplier: Double,
  val collisionWidthMeters: Float = 1.88f,
  val collisionHeightMeters: Float = 1.38f,
  val collisionLengthMeters: Float = 4.357386f,
) {
  val topSpeedMps: Double
    get() = topSpeedKmh / 3.6

  val boostedTopSpeedMps: Double
    get() = topSpeedMps * nitroSpeedMultiplier

  companion object {
    val STARTER_SPORTS =
      VehiclePhysicsConfig(
        id = "starter_sports",
        displayName = "Starter Sports Car",
        topSpeedKmh = 180.0,
        accelerationMps2 = 5.5,
        brakeDecelerationMps2 = 12.0,
        laneChangeDurationSeconds = 0.48,
        nitroDrainPerSecond = 28.0,
        nitroRegenPerSecond = 12.0,
        nitroSpeedMultiplier = 1.25,
        nitroAccelMultiplier = 1.65,
        collisionWidthMeters = 1.88f,
        collisionHeightMeters = 1.38f,
        collisionLengthMeters = 4.357386f,
      )
  }
}

data class DrivingInputSnapshot(
  val brakeHeld: Boolean = false,
  val nitroHeld: Boolean = false,
)

data class DrivingSimState(
  val speedMetersPerSecond: Double = 0.0,
  val distanceMeters: Double = 0.0,
  val nitroMeter: Double = 100.0,
  val isBraking: Boolean = false,
  val isNitroActive: Boolean = false,
  val isAutoNitroActive: Boolean = false,
  val isManualNitroActive: Boolean = false,
  val currentLaneIndex: Int = 1,
  val targetLaneIndex: Int = 1,
  val isChangingLane: Boolean = false,
  val laneTransitionProgress: Double = 0.0,
  val carX: Float = 0f,
  val cameraFollowX: Float = 0f,
  val steerVisualRatio: Float = 0f,
  val bodyRollDegrees: Float = 0f,
  val bodyPitchDegrees: Float = 0f,
  val wheelRollRadians: Double = 0.0,
  val totalSimSeconds: Double = 0.0,
  val fixedStepsExecuted: Long = 0L,
  // Phase 3 authoritative state
  val hearts: Int = TrafficSimulation.MAX_HEARTS,
  val collisionGraceRemainingSeconds: Double = 0.0,
  val shieldCharges: Int = 0,
  val shieldRemainingSeconds: Double = 0.0,
  val shieldBlocksCount: Int = 0,
  val validUniqueOvertakes: Int = 0,
  val validUniqueNearMisses: Int = 0,
  val score: Int = 0,
  val difficultyStage: DifficultyStageConfig = DifficultyDirector.STAGES.first(),
  val isGameOver: Boolean = false,
  val activeObstacles: List<ActiveObstacleSnapshot> = emptyList(),
  val lastTrafficEvent: TrafficEventKind = TrafficEventKind.NONE,
  val lastTrafficEventMessage: String = "",
  val cameraShakeSeconds: Double = 0.0,
  // Phase 4 authoritative pickup, power-up & run economy telemetry
  val autoNitroRemainingSeconds: Double = 0.0,
  val magnetRemainingSeconds: Double = 0.0,
  val doubleCoinsRemainingSeconds: Double = 0.0,
  val qualifiedManualNitroUses: Int = 0,
  val runCoinsEarned: Int = 0,
  val runCoinPickupsCollected: Int = 0,
  val runPowerUpsCollected: Int = 0,
  val activePickups: List<ActivePickupSnapshot> = emptyList(),
  val lastPickupEventMessage: String = "",
) {
  val speedKmh: Double
    get() = speedMetersPerSecond * 3.6
}

/**
 * Single authoritative fixed-60 Hz driving simulation for DRIP RACE.
 *
 * Independent of render frame cadence: accumulates clamped render deltas and steps at exact
 * 1/60 s intervals. Frozen cleanly when paused, Game Over, or inactive.
 * Owns canonical NitroController (manual + automatic 3 s boost without stacked multiplier),
 * TrafficSimulation, and PickupSimulation (3 coin tiers + 4 power-ups).
 */
class DrivingSimulation(
  initialConfig: VehiclePhysicsConfig = VehiclePhysicsConfig.STARTER_SPORTS,
  wheelRadiusMeters: Double = DEFAULT_STARTER_WHEEL_RADIUS_METERS,
  private val enableTraffic: Boolean = false,
  private val enablePickups: Boolean = enableTraffic,
  private val trafficSeed: Long = TrafficSimulation.DEFAULT_SEED,
  private val pickupSeed: Long = PickupSimulation.DEFAULT_SEED,
  private val onPickupCollected: ((PickupCollectedEvent) -> Unit)? = null,
) {
  var config: VehiclePhysicsConfig = initialConfig
    private set
  private var wheelRadiusM: Double = wheelRadiusMeters.coerceAtLeast(0.15)
  private var accumulatorSeconds: Double = 0.0
  private var laneStartX: Double = 0.0
  private var laneTargetX: Double = 0.0
  private var queuedLaneDelta: Int = 0
  private var nitroLockoutUntilRecharged: Boolean = false

  // Canonical Phase 4 NitroController state
  private var autoNitroRemainingSeconds: Double = 0.0
  private var currentManualNitroContinuousSeconds: Double = 0.0
  private var currentManualHoldCounted: Boolean = false
  private var qualifiedManualNitroUses: Int = 0

  val traffic: TrafficSimulation =
    TrafficSimulation(seed = trafficSeed, autoSpawnEnabled = enableTraffic)

  val pickups: PickupSimulation =
    PickupSimulation(seed = pickupSeed, autoSpawnEnabled = enablePickups)

  private val pickupEventListeners = mutableListOf<(PickupCollectedEvent) -> Unit>()
  private val collectedEventsQueue = ArrayDeque<PickupCollectedEvent>()

  init {
    if (onPickupCollected != null) {
      pickupEventListeners += onPickupCollected
    }
  }

  var state: DrivingSimState = DrivingSimState()
    private set

  fun addPickupCollectedListener(listener: (PickupCollectedEvent) -> Unit) {
    if (listener !in pickupEventListeners) {
      pickupEventListeners += listener
    }
  }

  fun drainCollectedPickupEvents(): List<PickupCollectedEvent> {
    if (collectedEventsQueue.isEmpty()) return emptyList()
    val drained = collectedEventsQueue.toList()
    collectedEventsQueue.clear()
    return drained
  }

  fun setWheelRadiusMeters(radiusMeters: Double) {
    if (radiusMeters > 0.10 && radiusMeters.isFinite()) {
      wheelRadiusM = radiusMeters
    }
  }

  fun setVerifiedMovingTrafficIds(ids: Set<String>?) {
    traffic.setVerifiedMovingTrafficIds(ids)
  }

  fun configureActiveVehicle(
    physicsConfig: VehiclePhysicsConfig,
    resetState: Boolean = true,
  ) {
    config = physicsConfig
    if (resetState) {
      resetForNewRace()
    }
  }

  fun resetForNewRace(
    seedOverride: Long = trafficSeed,
    pickupSeedOverride: Long = pickupSeed,
  ) {
    accumulatorSeconds = 0.0
    laneStartX = 0.0
    laneTargetX = 0.0
    queuedLaneDelta = 0
    nitroLockoutUntilRecharged = false
    autoNitroRemainingSeconds = 0.0
    currentManualNitroContinuousSeconds = 0.0
    currentManualHoldCounted = false
    qualifiedManualNitroUses = 0
    collectedEventsQueue.clear()
    traffic.resetForNewRace(seedOverride)
    pickups.resetForNewRace(pickupSeedOverride, incrementRaceSequence = true)
    state = DrivingSimState()
  }

  fun clearPendingFrameAccumulator() {
    accumulatorSeconds = 0.0
  }

  fun clearQueuedSteering() {
    queuedLaneDelta = 0
  }

  /** Phase 4 shield pickup hook: grants/refreshes single shield charge and 20 s timer cleanly. */
  fun grantShieldCharge(
    charges: Int = 1,
    durationSeconds: Double = PowerUpType.SHIELD.durationSeconds,
  ) {
    traffic.grantShieldCharge(charges, durationSeconds)
    state =
      state.copy(
        shieldCharges = traffic.shieldCharges,
        shieldRemainingSeconds = traffic.shieldRemainingSeconds,
      )
  }

  /** Phase 4 canonical power-up activation helper through authoritative simulation controllers. */
  fun activatePowerUp(type: PowerUpType) {
    when (type) {
      PowerUpType.AUTOMATIC_NITRO -> {
        autoNitroRemainingSeconds = type.durationSeconds
        state =
          state.copy(
            autoNitroRemainingSeconds = autoNitroRemainingSeconds,
            isAutoNitroActive = !state.isBraking && autoNitroRemainingSeconds > 0.0,
            isNitroActive = state.isManualNitroActive || (!state.isBraking && autoNitroRemainingSeconds > 0.0),
          )
      }
      PowerUpType.SHIELD -> {
        grantShieldCharge(1, type.durationSeconds)
      }
      PowerUpType.MAGNET -> {
        pickups.activatePowerUp(type)
        state = state.copy(magnetRemainingSeconds = pickups.magnetRemainingSeconds)
      }
      PowerUpType.DOUBLE_COINS -> {
        pickups.activatePowerUp(type)
        state = state.copy(doubleCoinsRemainingSeconds = pickups.doubleCoinsRemainingSeconds)
      }
    }
  }

  /**
   * Requests a single-step lane change (-1 = left, +1 = right).
   * Bounded to lanes [0, 2]; queues at most 1 extra step while a transition is in progress.
   */
  fun requestLaneChange(direction: Int) {
    val step = direction.coerceIn(-1, 1)
    if (step == 0) return

    if (!state.isChangingLane) {
      val nextLane = (state.currentLaneIndex + step).coerceIn(0, 2)
      if (nextLane != state.currentLaneIndex) {
        beginLaneTransition(nextLane)
      }
    } else {
      val projectedLane = (state.targetLaneIndex + step).coerceIn(0, 2)
      if (projectedLane != state.targetLaneIndex) {
        queuedLaneDelta = step
      }
    }
  }

  private fun beginLaneTransition(nextLane: Int) {
    laneStartX = state.carX.toDouble()
    laneTargetX = CoordinateMapper.LANE_CENTERS_X[nextLane].toDouble()
    state =
      state.copy(
        targetLaneIndex = nextLane,
        isChangingLane = true,
        laneTransitionProgress = 0.0,
      )
  }

  /**
   * Advances the fixed-step accumulator by [frameDeltaSeconds] (clamped to [MAX_FRAME_DELTA_SECONDS])
   * and executes zero or more fixed 1/60 s physics steps. Returns number of fixed steps executed.
   */
  fun advanceRenderFrame(
    frameDeltaSeconds: Double,
    input: DrivingInputSnapshot,
  ): Int {
    if (!frameDeltaSeconds.isFinite() || frameDeltaSeconds <= 0.0) return 0
    val clampedDelta = min(frameDeltaSeconds, MAX_FRAME_DELTA_SECONDS)
    accumulatorSeconds += clampedDelta

    var steps = 0
    while (accumulatorSeconds + EPSILON_SECONDS >= FIXED_DT_SECONDS && steps < MAX_SUBSTEPS_PER_TICK) {
      stepFixedTick(input, FIXED_DT_SECONDS)
      accumulatorSeconds = max(0.0, accumulatorSeconds - FIXED_DT_SECONDS)
      steps++
    }
    if (steps == MAX_SUBSTEPS_PER_TICK && accumulatorSeconds > FIXED_DT_SECONDS) {
      accumulatorSeconds = 0.0
    }
    return steps
  }

  /**
   * Executes one exact fixed simulation tick ([dt] defaults to 1/60 s).
   */
  fun stepFixedTick(
    input: DrivingInputSnapshot,
    dt: Double = FIXED_DT_SECONDS,
  ) {
    val prev = state
    if (prev.isGameOver) return
    var nitro = prev.nitroMeter.coerceIn(0.0, NITRO_MAX)

    // 1. Canonical NitroController: Brake overrides BOTH manual and automatic Nitro!
    val braking = input.brakeHeld
    if (braking && autoNitroRemainingSeconds > 0.0) {
      autoNitroRemainingSeconds = 0.0
    }

    val autoNitroActive = !braking && autoNitroRemainingSeconds > 0.0
    if (autoNitroActive) {
      autoNitroRemainingSeconds = max(0.0, autoNitroRemainingSeconds - dt)
    }

    // Manual Nitro only runs/drains when automatic portion is inactive and Brake is not held.
    val wantsManualNitro = input.nitroHeld && !braking && !autoNitroActive
    val canEngageManualNitro =
      if (prev.isManualNitroActive) {
        nitro > 0.0
      } else {
        !nitroLockoutUntilRecharged && nitro >= MIN_NITRO_TO_ENGAGE
      }
    val manualNitroActive = wantsManualNitro && canEngageManualNitro

    if (manualNitroActive) {
      val prevNitro = nitro
      nitro = max(0.0, nitro - config.nitroDrainPerSecond * dt)
      val consumedManualResource = prevNitro > nitro
      if (nitro <= 0.0) {
        nitroLockoutUntilRecharged = true
      }
      if (consumedManualResource) {
        currentManualNitroContinuousSeconds += dt
        if (!currentManualHoldCounted &&
          currentManualNitroContinuousSeconds + EPSILON_SECONDS >= QUALIFIED_MANUAL_NITRO_MIN_SECONDS
        ) {
          currentManualHoldCounted = true
          qualifiedManualNitroUses += 1
        }
      } else {
        currentManualNitroContinuousSeconds = 0.0
        currentManualHoldCounted = false
      }
    } else {
      currentManualNitroContinuousSeconds = 0.0
      currentManualHoldCounted = false
      // Automatic Nitro NEVER drains manual resource; normal regen resumes when manual isn't active
      if (!autoNitroActive && (prev.speedMetersPerSecond > 0.1 || !braking)) {
        nitro = min(NITRO_MAX, nitro + config.nitroRegenPerSecond * dt)
      }
      if (nitro >= NITRO_REENGAGE_THRESHOLD) {
        nitroLockoutUntilRecharged = false
      }
    }

    val nitroActive = autoNitroActive || manualNitroActive

    // 2. Longitudinal speed integration (single Nitro cap: never stacked 1.25x * 1.25x!)
    val targetSpeedCap =
      if (nitroActive) config.boostedTopSpeedMps else config.topSpeedMps
    val nextSpeed =
      when {
        braking -> max(0.0, prev.speedMetersPerSecond - config.brakeDecelerationMps2 * dt)
        nitroActive ->
          min(
            targetSpeedCap,
            prev.speedMetersPerSecond + config.accelerationMps2 * config.nitroAccelMultiplier * dt,
          )
        prev.speedMetersPerSecond < config.topSpeedMps ->
          min(config.topSpeedMps, prev.speedMetersPerSecond + config.accelerationMps2 * dt)
        prev.speedMetersPerSecond > config.topSpeedMps ->
          max(config.topSpeedMps, prev.speedMetersPerSecond - COAST_DECEL_MPS2 * dt)
        else -> config.topSpeedMps
      }

    // 3. Distance & wheel roll integration (Contract: distanceMeters += speedMetersPerSecond * dt)
    val deltaDistance = nextSpeed * dt
    val nextDistance = prev.distanceMeters + deltaDistance
    val nextWheelRollRad =
      (prev.wheelRollRadians + (deltaDistance / wheelRadiusM)) % (2.0 * PI)

    // 4. Bounded smoothstep steering & lane interpolation
    var currentLane = prev.currentLaneIndex
    val targetLane = prev.targetLaneIndex
    var changing = prev.isChangingLane
    var progress = prev.laneTransitionProgress
    var nextX: Double
    var targetSteerRatio = 0f

    if (changing) {
      progress = min(1.0, progress + dt / config.laneChangeDurationSeconds)
      val smoothT = progress * progress * (3.0 - 2.0 * progress)
      nextX = laneStartX + (laneTargetX - laneStartX) * smoothT
      val directionSign = if (laneTargetX > laneStartX) 1f else -1f
      val bell = (6.0 * progress * (1.0 - progress) / 1.5).toFloat().coerceIn(0f, 1f)
      targetSteerRatio = directionSign * bell

      if (progress >= 1.0 - 1e-6) {
        nextX = laneTargetX
        currentLane = targetLane
        changing = false
        progress = 0.0
        targetSteerRatio = 0f
      }
    } else {
      nextX = CoordinateMapper.LANE_CENTERS_X[currentLane].toDouble()
    }

    nextX =
      nextX.coerceIn(
        CoordinateMapper.LANE_CENTERS_X.first().toDouble(),
        CoordinateMapper.LANE_CENTERS_X.last().toDouble(),
      )

    val camAlpha = 1.0 - kotlin.math.exp(-CAMERA_FOLLOW_RATE * dt)
    val nextCamX = prev.cameraFollowX + (nextX - prev.cameraFollowX) * camAlpha
    val steerAlpha = (1.0 - kotlin.math.exp(-18.0 * dt)).toFloat()
    val nextSteerRatio = prev.steerVisualRatio + (targetSteerRatio - prev.steerVisualRatio) * steerAlpha

    val targetRollDeg = -nextSteerRatio * MAX_BODY_ROLL_DEGREES
    val targetPitchDeg =
      when {
        braking && nextSpeed > 0.5 -> BRAKE_DIVE_PITCH_DEGREES
        nitroActive -> NITRO_SQUAT_PITCH_DEGREES
        else -> 0f
      }
    val attAlpha = (1.0 - kotlin.math.exp(-12.0 * dt)).toFloat()
    val nextRoll = prev.bodyRollDegrees + (targetRollDeg - prev.bodyRollDegrees) * attAlpha
    val nextPitch = prev.bodyPitchDegrees + (targetPitchDeg - prev.bodyPitchDegrees) * attAlpha

    val prevCarX = prev.carX
    val nextCarX = nextX.toFloat()

    // 5. Phase 3 Traffic, swept collisions, grace, shield hook, overtakes, near misses & difficulty
    val runTraffic = enableTraffic || traffic.activeCount() > 0
    val trafficOutcome =
      if (runTraffic) {
        traffic.stepFixedTick(
          dt = dt,
          prevPlayerX = prevCarX,
          nextPlayerX = nextCarX,
          playerTargetLane = targetLane,
          playerSpeedMps = nextSpeed,
          distanceMeters = nextDistance,
          vehicleConfig = config,
        )
      } else {
        traffic.stepTimersOnly(dt)
        null
      }

    val currentObstacles = if (runTraffic) traffic.activeSnapshots() else emptyList()

    // 6. Phase 4 Pickups (Coins + 4 Power-ups) — freeze cleanly if Game Over triggered this tick
    val gameOverNow = traffic.gameOverTriggered
    val pickupEvents =
      if (!gameOverNow && (enablePickups || pickups.activeCoinCount() > 0 || pickups.activePowerUpCount() > 0 || pickups.magnetRemainingSeconds > 0.0 || pickups.doubleCoinsRemainingSeconds > 0.0)) {
        pickups.stepFixedTick(
          dt = dt,
          prevPlayerX = prevCarX,
          nextPlayerX = nextCarX,
          playerSpeedMps = nextSpeed,
          activeObstacles = currentObstacles,
          vehicleConfig = config,
        )
      } else {
        emptyList()
      }

    for (event in pickupEvents) {
      if (event is PickupCollectedEvent.PowerUpCollected) {
        when (event.powerUpType) {
          PowerUpType.AUTOMATIC_NITRO -> autoNitroRemainingSeconds = event.powerUpType.durationSeconds
          PowerUpType.SHIELD -> traffic.grantShieldCharge(1, event.powerUpType.durationSeconds)
          PowerUpType.MAGNET, PowerUpType.DOUBLE_COINS -> Unit // Already activated inside pickups
        }
      }
      collectedEventsQueue.addLast(event)
      pickupEventListeners.forEach { it.invoke(event) }
    }

    val finalSpeed =
      if (trafficOutcome != null && trafficOutcome.applyImpactSpeedPenalty) {
        nextSpeed * trafficOutcome.impactSpeedFactor
      } else {
        nextSpeed
      }

    val currentStage =
      if (runTraffic) traffic.currentStage else DifficultyDirector.stageForDistance(nextDistance)
    // Exact contract score: Double Coins affects physical coin currency ONLY, never score!
    val computedScore =
      TrafficSimulation.computeContractScore(
        distanceMeters = nextDistance,
        overtakes = traffic.validUniqueOvertakes,
        nearMisses = traffic.validUniqueNearMisses,
      )

    state =
      DrivingSimState(
        speedMetersPerSecond = finalSpeed,
        distanceMeters = nextDistance,
        nitroMeter = nitro,
        isBraking = braking,
        isNitroActive = nitroActive && (autoNitroActive || nitro > 0.0),
        isAutoNitroActive = autoNitroActive,
        isManualNitroActive = manualNitroActive && nitro > 0.0,
        currentLaneIndex = currentLane,
        targetLaneIndex = targetLane,
        isChangingLane = changing,
        laneTransitionProgress = progress,
        carX = nextCarX,
        cameraFollowX = nextCamX.toFloat(),
        steerVisualRatio = nextSteerRatio,
        bodyRollDegrees = nextRoll,
        bodyPitchDegrees = nextPitch,
        wheelRollRadians = nextWheelRollRad,
        totalSimSeconds = prev.totalSimSeconds + dt,
        fixedStepsExecuted = prev.fixedStepsExecuted + 1L,
        hearts = traffic.hearts,
        collisionGraceRemainingSeconds = traffic.collisionGraceRemainingSeconds,
        shieldCharges = traffic.shieldCharges,
        shieldRemainingSeconds = traffic.shieldRemainingSeconds,
        shieldBlocksCount = traffic.shieldBlocksCount,
        validUniqueOvertakes = traffic.validUniqueOvertakes,
        validUniqueNearMisses = traffic.validUniqueNearMisses,
        score = computedScore,
        difficultyStage = currentStage,
        isGameOver = traffic.gameOverTriggered,
        activeObstacles = currentObstacles,
        lastTrafficEvent = traffic.lastEventKind,
        lastTrafficEventMessage = traffic.lastEventMessage,
        cameraShakeSeconds = traffic.impactCameraShakeSeconds,
        autoNitroRemainingSeconds = autoNitroRemainingSeconds,
        magnetRemainingSeconds = pickups.magnetRemainingSeconds,
        doubleCoinsRemainingSeconds = pickups.doubleCoinsRemainingSeconds,
        qualifiedManualNitroUses = qualifiedManualNitroUses,
        runCoinsEarned = pickups.runCoinsEarned,
        runCoinPickupsCollected = pickups.runCoinPickupsCollected,
        runPowerUpsCollected = pickups.runPowerUpsCollected,
        activePickups = pickups.activeSnapshots(),
        lastPickupEventMessage = pickups.lastPickupBanner,
      )

    if (!state.isGameOver && !changing && queuedLaneDelta != 0) {
      val queued = queuedLaneDelta
      queuedLaneDelta = 0
      val nextTarget = (currentLane + queued).coerceIn(0, 2)
      if (nextTarget != currentLane) {
        beginLaneTransition(nextTarget)
      }
    }
  }

  companion object {
    const val FIXED_HZ = 60
    const val FIXED_DT_SECONDS = 1.0 / 60.0
    const val MAX_FRAME_DELTA_SECONDS = 0.1
    const val MAX_SUBSTEPS_PER_TICK = 8
    const val NITRO_MAX = 100.0
    const val MIN_NITRO_TO_ENGAGE = 5.0
    const val NITRO_REENGAGE_THRESHOLD = 8.0
    const val QUALIFIED_MANUAL_NITRO_MIN_SECONDS = 0.35
    const val COAST_DECEL_MPS2 = 6.5
    const val DEFAULT_STARTER_WHEEL_RADIUS_METERS = 0.34
    private const val CAMERA_FOLLOW_RATE = 9.0
    private const val MAX_BODY_ROLL_DEGREES = 3.2f
    private const val BRAKE_DIVE_PITCH_DEGREES = 2.0f
    private const val NITRO_SQUAT_PITCH_DEGREES = -1.3f
    private const val EPSILON_SECONDS = 1e-9

    fun expectedBrakeDistanceStepCount(initialSpeedMps: Double, decelMps2: Double): Int {
      if (initialSpeedMps <= 0.0 || decelMps2 <= 0.0) return 0
      return kotlin.math.ceil(initialSpeedMps / (decelMps2 * FIXED_DT_SECONDS)).toInt()
    }
  }
}
