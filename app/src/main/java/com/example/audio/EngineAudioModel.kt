package com.example.audio

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

data class EngineAudioFrameState(
  val vehicleId: String,
  val profileName: String,
  val virtualGear: Int,
  val gearCount: Int,
  val virtualRpm: Int,
  val redlineRpm: Int,
  val throttleLoad: Float,
  val isShiftingAudioOnly: Boolean,
  val idleStemGain: Float,
  val steadyStemGain: Float,
  val highStemGain: Float,
  val idlePitch: Float,
  val steadyPitch: Float,
  val highPitch: Float,
  val synthCharacterFreqHz: Float,
  val synthSubOctaveBlend: Float,
  val synthCharacterGain: Float,
  val roadNoiseGain: Float,
  val roadNoisePitch: Float,
  val windGain: Float,
  val windPitch: Float,
  val nitroSustainGain: Float,
  val nitroSustainPitch: Float,
  val synthesizedLayerLabel: String,
)

data class SpatialPassByCue(
  val leftGain: Float,
  val rightGain: Float,
  val pitchRate: Float,
  val normalizedPan: Float, // -1.0 left .. +1.0 right
)

/**
 * Pure deterministic 1-way telemetry-to-audio synthesizer for DRIP RACE's five hero vehicles.
 *
 * Reads actual simulation `speedMetersPerSecond`, acceleration, braking, and Nitro state without
 * ever writing back to or altering `DrivingSimulation` physics, speed, or collision dimensions.
 */
class EngineAudioModel {
  private var activeVehicleId: String = ""
  private var currentGear: Int = 1
  private var shiftRemainingSeconds: Double = 0.0
  private var smoothedRpmRatio: Double = 0.0
  private var smoothedLoad: Float = 0.18f
  private var previousSpeedMps: Double = 0.0
  private var initialized: Boolean = false

  fun reset(vehicleId: String) {
    activeVehicleId = vehicleId
    currentGear = 1
    shiftRemainingSeconds = 0.0
    smoothedRpmRatio = 0.0
    smoothedLoad = 0.18f
    previousSpeedMps = 0.0
    initialized = false
  }

  fun stepTelemetry(
    vehicleId: String,
    speedMetersPerSecond: Double,
    topSpeedMetersPerSecond: Double,
    boostedTopSpeedMetersPerSecond: Double,
    isBraking: Boolean,
    isNitroActive: Boolean,
    dtSeconds: Double,
  ): EngineAudioFrameState {
    val profile = AudioCatalog.profileForVehicle(vehicleId)
    if (vehicleId != activeVehicleId) {
      reset(vehicleId)
    }

    val safeDt = dtSeconds.coerceIn(1.0 / 240.0, 0.25)
    val safeSpeed = speedMetersPerSecond.coerceAtLeast(0.0)
    val safeNormalTop = topSpeedMetersPerSecond.coerceAtLeast(25.0)
    val safeBoostTop = max(safeNormalTop * 1.05, boostedTopSpeedMetersPerSecond)

    val rawAccelMps2 =
      if (initialized) (safeSpeed - previousSpeedMps) / safeDt else 0.0
    previousSpeedMps = safeSpeed
    initialized = true

    // Normalize speed against normal top speed (allows >1.0 up to ~1.25 during Nitro boost)
    val normalRatio = (safeSpeed / safeNormalTop).coerceIn(0.0, 1.30)
    val targetGear = selectGearWithHysteresis(normalRatio, profile.gearCount, currentGear)
    if (targetGear != currentGear) {
      if (targetGear > currentGear && safeSpeed > 4.0) {
        shiftRemainingSeconds = profile.shiftDurationSeconds
      }
      currentGear = targetGear
    } else if (shiftRemainingSeconds > 0.0) {
      shiftRemainingSeconds = max(0.0, shiftRemainingSeconds - safeDt)
    }

    val isShifting = shiftRemainingSeconds > 0.0
    val gearFloor = gearLowerRatio(currentGear, profile.gearCount)
    val gearCeil = gearUpperRatio(currentGear, profile.gearCount)
    val inGearSpan = (gearCeil - gearFloor).coerceAtLeast(0.08)

    val rawGearRpmRatio =
      if (safeSpeed <= 0.25) {
        0.0
      } else {
        val baseInGear = ((normalRatio - gearFloor) / inGearSpan).coerceIn(0.14, 1.0)
        val nitroOverrev =
          if (isNitroActive && currentGear == profile.gearCount) {
            ((safeSpeed - safeNormalTop) / (safeBoostTop - safeNormalTop).coerceAtLeast(1.0))
              .coerceIn(0.0, 1.0) * 0.10
          } else {
            0.0
          }
        (baseInGear + nitroOverrev).coerceIn(0.0, 1.06)
      }

    val shiftDipFactor = if (isShifting) 0.80 else 1.0
    val targetRpmRatio = (rawGearRpmRatio * shiftDipFactor).coerceIn(0.0, 1.05)
    val rpmAlpha = (safeDt * 14.0).coerceIn(0.08, 1.0)
    smoothedRpmRatio += (targetRpmRatio - smoothedRpmRatio) * rpmAlpha

    val rpmSpan = (profile.redlineRpm - profile.idleRpm).coerceAtLeast(2_000.0)
    val virtualRpm =
      (profile.idleRpm + rpmSpan * smoothedRpmRatio)
        .toInt()
        .coerceIn(profile.idleRpm.toInt(), (profile.redlineRpm * 1.05).toInt())

    // Load responds to actual positive acceleration, Nitro boost, braking, and audio shift dip
    val accelNorm = (rawAccelMps2 / 8.5).toFloat().coerceIn(-1f, 1f)
    val rawLoad =
      when {
        isShifting -> 0.16f
        isBraking -> 0.12f
        isNitroActive -> (0.85f + 0.15f * max(0f, accelNorm)).coerceIn(0f, 1f)
        safeSpeed < 0.4 -> 0.15f
        else ->
          (0.32f +
              0.42f * accelNorm.coerceAtLeast(0f) * profile.loadSensitivity +
              0.26f * smoothedRpmRatio.toFloat().coerceIn(0f, 1f))
            .coerceIn(0.14f, 1.0f)
      }
    val loadAlpha = (safeDt * 11.0).toFloat().coerceIn(0.06f, 1f)
    smoothedLoad += (rawLoad - smoothedLoad) * loadAlpha

    // 3-stem normalized crossfade across engine-idle, engine-steady, and engine-high
    val r = smoothedRpmRatio.toFloat().coerceIn(0f, 1.05f)
    val rawIdle =
      ((1f - (r / 0.44f)).coerceIn(0.06f, 1f) * profile.idleStemWeightBias) *
        (if (isBraking) 1.15f else 1f)
    val rawSteady =
      ((1f - abs(r - 0.50f) / 0.52f).coerceIn(0.10f, 1f) * profile.steadyStemWeightBias)
    val rawHigh =
      (((r - 0.30f) / 0.68f).coerceIn(0f, 1f).pow(1.15f) *
        profile.highStemWeightBias *
        (0.55f + 0.55f * smoothedLoad))

    val weightSum = (rawIdle + rawSteady + rawHigh).coerceAtLeast(0.001f)
    // Normalize so combined stem envelope never exceeds 1.0 (protects ENGINE bus headroom!)
    val envIntensity = (0.72f + 0.28f * smoothedLoad).coerceIn(0.68f, 1.0f)
    val idleGain = (rawIdle / weightSum) * envIntensity
    val steadyGain = (rawSteady / weightSum) * envIntensity
    val highGain = (rawHigh / weightSum) * envIntensity

    val nitroPitchAdd = if (isNitroActive) profile.nitroPitchBoost else 0f
    val brakePitchDrop = if (isBraking) -0.05f else 0f
    val pitchSpan = (profile.maxPitchMultiplier - profile.steadyBasePitch).coerceAtLeast(0.12f)

    val idlePitch =
      (profile.idleBasePitch + r * 0.26f + brakePitchDrop).coerceIn(0.65f, 1.60f)
    val steadyPitch =
      (profile.steadyBasePitch + r * pitchSpan * 0.85f + nitroPitchAdd + brakePitchDrop)
        .coerceIn(0.68f, 1.85f)
    val highPitch =
      (profile.highBasePitch + r * pitchSpan + nitroPitchAdd + brakePitchDrop)
        .coerceIn(0.72f, 1.92f)

    // Honestly labeled procedural harmonic character layer (fundamental firing frequency from virtual RPM)
    val baseCylinderFreqHz = ((virtualRpm / 60.0) * profile.harmonicOrder).toFloat().coerceIn(28f, 520f)
    val synthGain =
      (profile.maxSynthCharacterGain * (0.35f + 0.65f * smoothedLoad) * (0.45f + 0.55f * r.coerceIn(0f, 1f)))
        .coerceIn(0f, profile.maxSynthCharacterGain)

    // Speed-reactive road-noise & aero wind ambience
    val speedNorm65 = (safeSpeed / 65.0).toFloat().coerceIn(0f, 1.15f)
    val roadNoiseGain =
      if (safeSpeed < 0.8) 0f
      else (0.16f + 0.78f * sqrt(speedNorm65.coerceIn(0f, 1f))).coerceIn(0f, 0.95f)
    val roadNoisePitch = (0.82f + 0.36f * speedNorm65).coerceIn(0.80f, 1.30f)

    val windNorm = ((safeSpeed - 12.0) / 68.0).toFloat().coerceIn(0f, 1.15f)
    val windGain = (windNorm * windNorm * 0.92f).coerceIn(0f, 0.95f)
    val windPitch = (0.88f + 0.34f * windNorm).coerceIn(0.85f, 1.32f)

    val nitroGain = if (isNitroActive) 1.0f else 0.0f
    val nitroPitch = (0.96f + 0.16f * r.coerceIn(0f, 1f)).coerceIn(0.92f, 1.18f)

    return EngineAudioFrameState(
      vehicleId = profile.vehicleId,
      profileName = profile.profileName,
      virtualGear = currentGear,
      gearCount = profile.gearCount,
      virtualRpm = virtualRpm,
      redlineRpm = profile.redlineRpm.toInt(),
      throttleLoad = smoothedLoad,
      isShiftingAudioOnly = isShifting,
      idleStemGain = idleGain,
      steadyStemGain = steadyGain,
      highStemGain = highGain,
      idlePitch = idlePitch,
      steadyPitch = steadyPitch,
      highPitch = highPitch,
      synthCharacterFreqHz = baseCylinderFreqHz,
      synthSubOctaveBlend = profile.subOctaveBlend,
      synthCharacterGain = synthGain,
      roadNoiseGain = roadNoiseGain,
      roadNoisePitch = roadNoisePitch,
      windGain = windGain,
      windPitch = windPitch,
      nitroSustainGain = nitroGain,
      nitroSustainPitch = nitroPitch,
      synthesizedLayerLabel = profile.synthesizedLayerLabel,
    )
  }

  private fun gearLowerRatio(gear: Int, gearCount: Int): Double {
    if (gear <= 1) return 0.0
    val step = 1.0 / gearCount.toDouble()
    return ((gear - 1) * step - 0.04).coerceAtLeast(0.0)
  }

  private fun gearUpperRatio(gear: Int, gearCount: Int): Double {
    val step = 1.0 / gearCount.toDouble()
    return min(1.04, gear * step + 0.02)
  }

  private fun selectGearWithHysteresis(
    normalSpeedRatio: Double,
    gearCount: Int,
    prevGear: Int,
  ): Int {
    val step = 1.0 / gearCount.toDouble()
    var gear = prevGear.coerceIn(1, gearCount)
    // Upshift threshold includes +0.025 hysteresis; downshift includes -0.045 hysteresis
    while (gear < gearCount && normalSpeedRatio > gear * step + 0.025) {
      gear++
    }
    while (gear > 1 && normalSpeedRatio < (gear - 1) * step - 0.045) {
      gear--
    }
    return gear
  }

  companion object {
    /**
     * Computes stereo pan (`leftGain`, `rightGain`) and subtle relative-speed Doppler pitch for
     * nearby traffic pass-bys. Lateral offset < 0 (obstacle to left of player) emphasizes left channel;
     * lateral offset > 0 (obstacle to right of player) emphasizes right channel.
     */
    fun computeSpatialPassBy(
      obstacleX: Float,
      playerCarX: Float,
      relativeClosingSpeedMps: Double,
    ): SpatialPassByCue {
      val dx = obstacleX - playerCarX
      val pan = (dx / 4.0f).coerceIn(-0.88f, 0.88f)
      // Constant-power-inspired stereo balance keeping sum headroom bounded <= 1.0
      val leftRaw = sqrt(0.5f * (1f - pan))
      val rightRaw = sqrt(0.5f * (1f + pan))
      val proximityAtten = (1.0f - (abs(dx) / 9.5f)).coerceIn(0.35f, 1.0f)
      val closingFactor = (relativeClosingSpeedMps / 45.0).toFloat().coerceIn(0.45f, 1.25f)
      val pitch = (0.90f + 0.20f * closingFactor).coerceIn(0.86f, 1.22f)
      return SpatialPassByCue(
        leftGain = (leftRaw * proximityAtten).coerceIn(0f, 1f),
        rightGain = (rightRaw * proximityAtten).coerceIn(0f, 1f),
        pitchRate = pitch,
        normalizedPan = pan,
      )
    }
  }
}
