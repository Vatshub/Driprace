package com.example.scenery

/**
 * Native Kotlin port of `references/scenery/prepare-pine-lod.mjs` & `pine-lod-bindings.json`.
 *
 * `pine-medium-1-lod-set.glb` bundles three LOD subtrees (`Pine_medium_1_LOD0`,
 * `Pine_medium_1_LOD1`, `Pine_medium_1_LOD2`) at the same origin. Showing all three simultaneously
 * causes severe z-fighting/overdraw. This selector enforces:
 * 1. Validated monotonically increasing switch distances (default `[0m, 35m, 90m]`).
 * 2. Distance hysteresis (`0.15f` default) so trees near 35 m or 90 m never flicker/pop every tick.
 * 3. Strict mutual exclusion: **exactly ONE** LOD (`0`, `1`, or `2`) is active at any distance.
 * 4. Once-only preparation guard per loaded pine asset instance.
 */
object PineLodSelector {
  const val ROOT_NODE_NAME = "Pine_medium_1"
  val LOD_GROUP_NODE_NAMES: List<String> =
    listOf(
      "Pine_medium_1_LOD0",
      "Pine_medium_1_LOD1",
      "Pine_medium_1_LOD2",
    )

  val DEFAULT_DISTANCES_METERS: FloatArray = floatArrayOf(0f, 35f, 90f)
  const val DEFAULT_HYSTERESIS_RATIO = 0.15f

  data class ThresholdBand(
    val lod0OutwardTo1Meters: Float,
    val lod1InwardTo0Meters: Float,
    val lod1OutwardTo2Meters: Float,
    val lod2InwardTo1Meters: Float,
  )

  fun validateAndBuildThresholds(
    distancesMeters: FloatArray = DEFAULT_DISTANCES_METERS,
    distanceScale: Float = 1.0f,
    hysteresisRatio: Float = DEFAULT_HYSTERESIS_RATIO,
  ): ThresholdBand {
    require(distancesMeters.size == 3) {
      "Pine LOD requires exactly 3 LOD switch distances [0, d1, d2], got ${distancesMeters.size}."
    }
    require(distancesMeters[0] == 0f && distancesMeters[1] > 0f && distancesMeters[2] > distancesMeters[1]) {
      "Invalid Pine LOD distances [${distancesMeters.joinToString()}]; expected 0 < d1 < d2."
    }
    require(distanceScale.isFinite() && distanceScale > 0.1f) {
      "Invalid Pine LOD distanceScale $distanceScale."
    }
    require(hysteresisRatio.isFinite() && hysteresisRatio in 0.0f..0.45f) {
      "Invalid Pine LOD hysteresisRatio $hysteresisRatio."
    }

    val d1 = distancesMeters[1] * distanceScale
    val d2 = distancesMeters[2] * distanceScale
    return ThresholdBand(
      lod0OutwardTo1Meters = d1 * (1.0f + hysteresisRatio),
      lod1InwardTo0Meters = d1 * (1.0f - hysteresisRatio),
      lod1OutwardTo2Meters = d2 * (1.0f + hysteresisRatio),
      lod2InwardTo1Meters = d2 * (1.0f - hysteresisRatio),
    )
  }

  /**
   * Computes initial LOD index (`0`, `1`, or `2`) when spawning/placing a tree without prior state.
   */
  fun initialLodForDistance(
    distanceMeters: Float,
    distancesMeters: FloatArray = DEFAULT_DISTANCES_METERS,
    distanceScale: Float = 1.0f,
  ): Int {
    val safeDist = distanceMeters.coerceAtLeast(0f)
    val d1 = distancesMeters[1] * distanceScale
    val d2 = distancesMeters[2] * distanceScale
    return when {
      safeDist >= d2 -> 2
      safeDist >= d1 -> 1
      else -> 0
    }
  }

  /**
   * Computes next active LOD (`0`, `1`, or `2`) from [currentLod] and [distanceMeters] using
   * symmetric hysteresis bands around `d1` and `d2`.
   */
  fun selectActiveLodWithHysteresis(
    currentLod: Int,
    distanceMeters: Float,
    thresholds: ThresholdBand,
  ): Int {
    val d = distanceMeters.coerceAtLeast(0f)
    val clampedCurrent = currentLod.coerceIn(0, 2)
    return when (clampedCurrent) {
      0 -> {
        when {
          d >= thresholds.lod1OutwardTo2Meters -> 2
          d >= thresholds.lod0OutwardTo1Meters -> 1
          else -> 0
        }
      }
      1 -> {
        when {
          d >= thresholds.lod1OutwardTo2Meters -> 2
          d <= thresholds.lod1InwardTo0Meters -> 0
          else -> 1
        }
      }
      else -> {
        when {
          d <= thresholds.lod1InwardTo0Meters -> 0
          d <= thresholds.lod2InwardTo1Meters -> 1
          else -> 2
        }
      }
    }
  }

  /**
   * Returns a 3-element boolean visibility mask `[lod0Visible, lod1Visible, lod2Visible]` where
   * `mask.count { it } == 1` is strictly guaranteed.
   */
  fun visibilityMaskForLod(activeLod: Int): BooleanArray {
    val safe = activeLod.coerceIn(0, 2)
    return booleanArrayOf(safe == 0, safe == 1, safe == 2)
  }
}

/**
 * Per-instance stateful Pine LOD tracker with once-only preparation guard.
 */
class PineInstanceLodTracker(
  distancesMeters: FloatArray = PineLodSelector.DEFAULT_DISTANCES_METERS,
  distanceScale: Float = 1.0f,
  hysteresisRatio: Float = PineLodSelector.DEFAULT_HYSTERESIS_RATIO,
) {
  private var preparedOnce = false
  private var thresholds: PineLodSelector.ThresholdBand =
    PineLodSelector.validateAndBuildThresholds(distancesMeters, distanceScale, hysteresisRatio)

  var activeLodIndex: Int = 0
    private set

  fun markPreparedOnce() {
    check(!preparedOnce) {
      "Pine LOD instance has already been prepared; preparePineLod must run once per instance."
    }
    preparedOnce = true
  }

  fun updateDistanceScale(
    distanceScale: Float,
    distancesMeters: FloatArray = PineLodSelector.DEFAULT_DISTANCES_METERS,
    hysteresisRatio: Float = PineLodSelector.DEFAULT_HYSTERESIS_RATIO,
  ) {
    thresholds =
      PineLodSelector.validateAndBuildThresholds(distancesMeters, distanceScale, hysteresisRatio)
  }

  fun resetAtDistance(
    distanceMeters: Float,
    distanceScale: Float = 1.0f,
  ): Int {
    activeLodIndex =
      PineLodSelector.initialLodForDistance(
        distanceMeters = distanceMeters,
        distanceScale = distanceScale,
      )
    return activeLodIndex
  }

  fun updateForCameraDistance(distanceMeters: Float): Int {
    activeLodIndex =
      PineLodSelector.selectActiveLodWithHysteresis(
        currentLod = activeLodIndex,
        distanceMeters = distanceMeters,
        thresholds = thresholds,
      )
    return activeLodIndex
  }

  fun currentVisibilityMask(): BooleanArray =
    PineLodSelector.visibilityMaskForLod(activeLodIndex)
}
