package com.example.render

import android.opengl.Matrix
import kotlin.math.abs

/**
 * Explicit coordinate mapping between DRIP RACE Game World and Filament Renderer World.
 *
 * Game World (authoritative contract):
 * - X: lateral across the 3 lanes centered at -3.6 m (lane 0), 0.0 m (lane 1), +3.6 m (lane 2)
 * - Y: vertical up, road surface at Y = 0.0 m
 * - Z: forward driving direction (+Z), chase camera behind car at negative Z (e.g. Z = -8.2 m)
 *
 * Filament Renderer World:
 * - Right-handed Y-up coordinate space.
 * - Camera positioned at (0, eyeY, -8.2) looking toward (0, lookY, +12.0) with up=(0, 1, 0).
 * - Keeps 1:1 coordinate alignment (Y=0 road, X=-3.6/0/+3.6 lane centers, +Z forward, det=+1 so
 *   no GLB triangle winding or normal map handedness is inverted).
 */
object CoordinateMapper {
  const val LANE_WIDTH_METERS = 3.6f
  val LANE_CENTERS_X = floatArrayOf(-3.6f, 0.0f, 3.6f)
  const val TARGET_STARTER_Z_LENGTH_METERS = 4.357386f

  /**
   * Maps Game World lateral X (-3.6 = Left lane 0, +3.6 = Right lane 2) to Filament renderer world X.
   * Because Filament is right-handed with +Y up and the chase camera at -Z looks toward +Z,
   * screen-right is (0, 0, +1) x (0, 1, 0) = (-1, 0, 0), so +X_renderer is screen-left and
   * -X_renderer is screen-right.
   */
  fun gameToRendererX(gameX: Float): Float = -gameX

  fun gameToRendererX(gameX: Double): Double = -gameX

  data class ChaseCameraPose(
    val eyeX: Double,
    val eyeY: Double,
    val eyeZ: Double,
    val centerX: Double,
    val centerY: Double,
    val centerZ: Double,
    val verticalFovDegrees: Double,
  )

  fun computeChaseCameraPose(
    widthPx: Int,
    heightPx: Int,
    cameraFollowX: Float = 0f,
    carX: Float = 0f,
    speedNormalized: Float = 0f,
    isNitroActive: Boolean = false,
    isBraking: Boolean = false,
    cameraShakeSeconds: Double = 0.0,
    reducedMotion: Boolean = false,
  ): ChaseCameraPose {
    val safeW = widthPx.coerceAtLeast(1)
    val safeH = heightPx.coerceAtLeast(1)
    val aspect = safeW.toDouble() / safeH.toDouble()
    val isPortrait = aspect < 1.0
    val clampedSpeed = speedNormalized.coerceIn(0f, 1.3f).toDouble()
    val nitroFov =
      when {
        !isNitroActive -> 0.0
        reducedMotion -> 0.8
        else -> 4.2
      }
    val nitroZPull = if (isNitroActive && !reducedMotion) -0.45 else 0.0
    val brakeZPush = if (isBraking && clampedSpeed > 0.05 && !reducedMotion) 0.35 else 0.0
    val shakeIntensity = if (reducedMotion) 0.0 else (cameraShakeSeconds / 0.32).coerceIn(0.0, 1.0)
    val shakeX =
      if (shakeIntensity > 0.0) kotlin.math.sin(cameraShakeSeconds * 70.0) * 0.16 * shakeIntensity else 0.0
    val shakeY =
      if (shakeIntensity > 0.0) kotlin.math.cos(cameraShakeSeconds * 92.0) * 0.09 * shakeIntensity else 0.0
    val followScale = if (reducedMotion) 0.35 else 0.65
    val lookScale = if (reducedMotion) 0.28 else 0.42
    val speedFovScale = if (reducedMotion) 1.2 else 5.0
    val speedFovScaleLandscape = if (reducedMotion) 1.0 else 4.5
    val eyeX = gameToRendererX(cameraFollowX * followScale.toFloat()).toDouble() + shakeX
    val centerX = gameToRendererX(carX * lookScale.toFloat()).toDouble()

    return if (isPortrait) {
      // Portrait-first: slightly wider vertical FOV & higher eye so all 3 lanes + car fit comfortably
      ChaseCameraPose(
        eyeX = eyeX,
        eyeY = 3.45 - (if (isNitroActive && !reducedMotion) 0.12 else 0.0) + shakeY,
        eyeZ = -8.6 + nitroZPull + brakeZPush,
        centerX = centerX,
        centerY = 0.85,
        centerZ = 14.0,
        verticalFovDegrees = 56.0 + clampedSpeed * speedFovScale + nitroFov,
      )
    } else {
      // Landscape: sleeker lower-horizon chase framing
      ChaseCameraPose(
        eyeX = eyeX,
        eyeY = 2.85 - (if (isNitroActive && !reducedMotion) 0.10 else 0.0) + shakeY,
        eyeZ = -7.8 + nitroZPull + brakeZPush,
        centerX = centerX,
        centerY = 0.90,
        centerZ = 14.0,
        verticalFovDegrees = 45.0 + clampedSpeed * speedFovScaleLandscape + nitroFov,
      )
    }
  }

  fun computeShowroomCameraPose(
    widthPx: Int,
    heightPx: Int,
    yawDegrees: Float = 36f,
    pitchDegrees: Float = 16f,
    distanceMeters: Float = 6.4f,
  ): ChaseCameraPose {
    val safeW = widthPx.coerceAtLeast(1)
    val safeH = heightPx.coerceAtLeast(1)
    val isPortrait = safeW < safeH
    val clampedPitchDeg = pitchDegrees.coerceIn(7f, 44f).toDouble()
    val clampedDist = distanceMeters.coerceIn(4.3f, 9.5f).toDouble()
    val effectiveDist = if (isPortrait) clampedDist + 4.3 else clampedDist + 1.5

    val yawRad = Math.toRadians(yawDegrees.toDouble())
    val pitchRad = Math.toRadians(clampedPitchDeg)
    val cosP = kotlin.math.cos(pitchRad)
    val sinP = kotlin.math.sin(pitchRad)

    val relEyeX = effectiveDist * cosP * kotlin.math.sin(yawRad)
    val relEyeZ = effectiveDist * cosP * kotlin.math.cos(yawRad)
    val relEyeY = effectiveDist * sinP

    // In Portrait, shift look target downward so the car renders cleanly in the upper half above the menu.
    // In Landscape, shift look target slightly along camera-right so the car frames in the open left half.
    val targetY = if (isPortrait) -1.35 else 0.58
    var sideShiftX = 0.0
    var sideShiftZ = 0.0
    if (!isPortrait) {
      // Horizontal forward from eye to origin is (-relEyeX, 0, -relEyeZ); right = normalize(cross(fwd, up))
      val fwdLen = kotlin.math.hypot(relEyeX, relEyeZ).coerceAtLeast(1e-4)
      val rightX = relEyeZ / fwdLen
      val rightZ = -relEyeX / fwdLen
      val rightOffsetMeters = 1.65
      sideShiftX = rightX * rightOffsetMeters
      sideShiftZ = rightZ * rightOffsetMeters
    }

    return ChaseCameraPose(
      eyeX = relEyeX + sideShiftX,
      eyeY = (0.65 + relEyeY).coerceAtLeast(0.85),
      eyeZ = relEyeZ + sideShiftZ,
      centerX = sideShiftX,
      centerY = targetY,
      centerZ = sideShiftZ,
      verticalFovDegrees = if (isPortrait) 56.0 else 42.0,
    )
  }
}

data class VehicleBounds(
  val minX: Float,
  val minY: Float,
  val minZ: Float,
  val maxX: Float,
  val maxY: Float,
  val maxZ: Float,
) {
  val sizeX: Float get() = maxX - minX
  val sizeY: Float get() = maxY - minY
  val sizeZ: Float get() = maxZ - minZ
  val centerX: Float get() = (minX + maxX) * 0.5f
  val centerZ: Float get() = (minZ + maxZ) * 0.5f
}

data class VehicleNormalizationPlan(
  val uniformScale: Float,
  val translateX: Float,
  val translateY: Float,
  val translateZ: Float,
  val yawDegrees: Float,
  val steeringCorrectionApplied: Boolean,
  val matrixColumnMajor: FloatArray,
)

/**
 * Pure Kotlin math pipeline porting references/adapters/prepare-vehicle.mjs and placements.json:
 * 1. Guarded to run at most once per loaded vehicle instance.
 * 2. Starter front steering correction (WheelFrontL, WheelFrontR):
 *    premultiply local rotation by +PI/6 (+30 deg) around local Z (0, 0, 1), preserving translation.
 * 3. Apply yawRadians (0f for starter_sports per placements.json) so forward faces +Z.
 * 4. Compute exact post-steering world AABB.
 * 5. Uniformly scale so Z length equals 4.357386 m, ground minY at Y = 0.0, and center X/Z at 0.0.
 */
object StarterVehicleNormalizer {
  const val STARTER_FRONT_STEERING_CORRECTION_DEG = 30f
  const val STARTER_YAW_DEGREES = 0f

  fun premultiplyLocalZRotation(
    localMatrixColumnMajor: FloatArray,
    degrees: Float = STARTER_FRONT_STEERING_CORRECTION_DEG,
  ): FloatArray {
    require(localMatrixColumnMajor.size == 16) { "Expected 4x4 matrix" }
    val tx = localMatrixColumnMajor[12]
    val ty = localMatrixColumnMajor[13]
    val tz = localMatrixColumnMajor[14]

    val rotZ = FloatArray(16)
    Matrix.setIdentityM(rotZ, 0)
    Matrix.rotateM(rotZ, 0, degrees, 0f, 0f, 1f)

    val linearPart = localMatrixColumnMajor.copyOf()
    linearPart[12] = 0f
    linearPart[13] = 0f
    linearPart[14] = 0f
    linearPart[15] = 1f

    val out = FloatArray(16)
    Matrix.multiplyMM(out, 0, rotZ, 0, linearPart, 0)
    out[12] = tx
    out[13] = ty
    out[14] = tz
    out[15] = localMatrixColumnMajor[15]
    return out
  }

  class BoundsAccumulator {
    var minX = Float.POSITIVE_INFINITY
    var minY = Float.POSITIVE_INFINITY
    var minZ = Float.POSITIVE_INFINITY
    var maxX = Float.NEGATIVE_INFINITY
    var maxY = Float.NEGATIVE_INFINITY
    var maxZ = Float.NEGATIVE_INFINITY
    private var hasAnyPoint = false

    fun includeTransformedBox(
      localCenterX: Float,
      localCenterY: Float,
      localCenterZ: Float,
      halfX: Float,
      halfY: Float,
      halfZ: Float,
      worldMatrixColumnMajor: FloatArray,
    ) {
      val corner = FloatArray(4)
      val out = FloatArray(4)
      for (sx in -1..1 step 2) {
        for (sy in -1..1 step 2) {
          for (sz in -1..1 step 2) {
            corner[0] = localCenterX + sx * halfX
            corner[1] = localCenterY + sy * halfY
            corner[2] = localCenterZ + sz * halfZ
            corner[3] = 1f
            Matrix.multiplyMV(out, 0, worldMatrixColumnMajor, 0, corner, 0)
            val x = out[0]
            val y = out[1]
            val z = out[2]
            if (x < minX) minX = x
            if (y < minY) minY = y
            if (z < minZ) minZ = z
            if (x > maxX) maxX = x
            if (y > maxY) maxY = y
            if (z > maxZ) maxZ = z
            hasAnyPoint = true
          }
        }
      }
    }

    fun toVehicleBoundsOrNull(): VehicleBounds? =
      if (hasAnyPoint) VehicleBounds(minX, minY, minZ, maxX, maxY, maxZ) else null
  }

  fun computeNormalizationPlan(
    bounds: VehicleBounds,
    targetZLengthMeters: Float = CoordinateMapper.TARGET_STARTER_Z_LENGTH_METERS,
    yawCorrectionDegrees: Float = STARTER_YAW_DEGREES,
    steeringCorrectionAlreadyApplied: Boolean = false,
  ): VehicleNormalizationPlan {
    val rawZ = abs(bounds.sizeZ).coerceAtLeast(1e-4f)
    val scale = targetZLengthMeters / rawZ

    // Column-major 4x4 matrix: M = T_post * R_y * S * T_pre
    val matrix = FloatArray(16)
    Matrix.setIdentityM(matrix, 0)
    if (yawCorrectionDegrees != 0f) {
      Matrix.rotateM(matrix, 0, yawCorrectionDegrees, 0f, 1f, 0f)
    }
    Matrix.scaleM(matrix, 0, scale, scale, scale)
    Matrix.translateM(matrix, 0, -bounds.centerX, -bounds.minY, -bounds.centerZ)

    return VehicleNormalizationPlan(
      uniformScale = scale,
      translateX = -bounds.centerX * scale,
      translateY = -bounds.minY * scale,
      translateZ = -bounds.centerZ * scale,
      yawDegrees = yawCorrectionDegrees,
      steeringCorrectionApplied = !steeringCorrectionAlreadyApplied,
      matrixColumnMajor = matrix,
    )
  }

  /**
   * Computes normalization matching placements.json & prepare-vehicle.mjs:
   * rotates bounds by [yawDegrees] around +Y first, then scales Z length to [targetZLengthMeters],
   * grounds minY at 0, and centers X/Z at 0.
   */
  fun computeYawAlignedNormalizationPlan(
    postYawBounds: VehicleBounds,
    targetZLengthMeters: Float,
    yawDegrees: Float,
  ): VehicleNormalizationPlan {
    val rawZ = abs(postYawBounds.sizeZ).coerceAtLeast(1e-4f)
    val scale = targetZLengthMeters / rawZ

    // M = T(-centerX*scale, -minY*scale, -centerZ*scale) * S(scale) * R_y(yawDegrees)
    val matrix = FloatArray(16)
    Matrix.setIdentityM(matrix, 0)
    Matrix.translateM(
      matrix,
      0,
      -postYawBounds.centerX * scale,
      -postYawBounds.minY * scale,
      -postYawBounds.centerZ * scale,
    )
    Matrix.scaleM(matrix, 0, scale, scale, scale)
    if (yawDegrees != 0f) {
      Matrix.rotateM(matrix, 0, yawDegrees, 0f, 1f, 0f)
    }

    return VehicleNormalizationPlan(
      uniformScale = scale,
      translateX = -postYawBounds.centerX * scale,
      translateY = -postYawBounds.minY * scale,
      translateZ = -postYawBounds.centerZ * scale,
      yawDegrees = yawDegrees,
      steeringCorrectionApplied = false,
      matrixColumnMajor = matrix,
    )
  }
}
