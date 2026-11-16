package com.example.render

import com.google.android.filament.Engine
import com.google.android.filament.IndirectLight
import kotlin.math.cos
import kotlin.math.sin

/**
 * Crash-free 3-band Spherical Harmonic studio + highway daylight `IndirectLight` configuration.
 *
 * Uses Filament's native `IndirectLight.Builder().irradiance(3, sh)` path exclusively without
 * `SAMPLER_CUBEMAP` `Texture.setImage` or `IBLPrefilterContext` GPU prefiltering, eliminating
 * native OpenGL ES / Vulkan texture precondition aborts while providing bright, wrap-around
 * sky + studio bounce illumination from every viewing angle.
 */
object StudioIblBuilder {
  internal const val STUDIO_IBL_INTENSITY_LUX = 38_000.0f
  internal const val KEY_SUN_INTENSITY_LUX = 105_000.0f
  internal const val FILL_LIGHT_INTENSITY_LUX = 48_000.0f

  /**
   * 9 RGB (27 floats) pre-scaled 3-band Spherical Harmonics irradiance coefficients (`E(n)`).
   * Guarantees positive wrap-around diffuse irradiance across zenith, horizon, and ground bounce
   * so car panels, wheel arches, and lower rocker sills never crush to black.
   */
  internal val STUDIO_DAYLIGHT_SH: FloatArray =
    floatArrayOf(
      // Band 0: L00 (strong neutral-warm studio + sky ambient base)
      0.86f, 0.90f, 0.96f,
      // Band 1: L1,-1 (Y: sky zenith brighter than asphalt bounce, never negative at Y=-1)
      0.14f, 0.18f, 0.24f,
      // Band 1: L1,0 (Z: gentle forward horizon fill)
      0.04f, 0.05f, 0.07f,
      // Band 1: L1,1 (X: subtle lateral studio softbox asymmetry)
      -0.04f, -0.03f, -0.02f,
      // Band 2: L2,-2 (XY)
      0.00f, 0.00f, 0.00f,
      // Band 2: L2,-1 (YZ)
      0.01f, 0.02f, 0.03f,
      // Band 2: L2,0 (3Z^2 - 1)
      -0.02f, -0.02f, -0.01f,
      // Band 2: L2,1 (XZ)
      0.02f, 0.02f, 0.02f,
      // Band 2: L2,2 (X^2 - Y^2)
      0.03f, 0.03f, 0.02f,
    )

  fun buildDaylightStudioIbl(eng: Engine): IndirectLight =
    IndirectLight.Builder()
      .irradiance(3, STUDIO_DAYLIGHT_SH)
      .intensity(STUDIO_IBL_INTENSITY_LUX)
      .build(eng)

  /**
   * Computes camera-relative key & fill light travel directions in Showroom mode so the side of
   * the vehicle facing the user is always directly lit with clearcoat specular highlights.
   */
  internal fun computeShowroomKeyDirection(yawDegrees: Float): FloatArray {
    val keyRad = Math.toRadians(yawDegrees.toDouble() + 22.0)
    return floatArrayOf(
      (-sin(keyRad) * 0.64).toFloat(),
      -0.76f,
      (-cos(keyRad) * 0.64).toFloat(),
    )
  }

  internal fun computeShowroomFillDirection(yawDegrees: Float): FloatArray {
    val fillRad = Math.toRadians(yawDegrees.toDouble() - 135.0)
    return floatArrayOf(
      (-sin(fillRad) * 0.72).toFloat(),
      -0.56f,
      (-cos(fillRad) * 0.72).toFloat(),
    )
  }
}
