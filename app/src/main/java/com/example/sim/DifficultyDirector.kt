package com.example.sim

data class DifficultyStageConfig(
  val stageNumber: Int,
  val label: String,
  val minDistanceMeters: Double,
  val spawnIntervalSeconds: Double,
  val maxActiveEntities: Int,
  val speedVarianceFactor: Double,
  val staticHazardProbability: Double,
  val minReactionTimeSeconds: Double,
)

/**
 * Contract §6 five in-race distance-based difficulty stages.
 * Distinct from Phase 6 environment unlock levels; bounds density and reaction margins so
 * fairness never disappears at high distances.
 */
object DifficultyDirector {
  val STAGES: List<DifficultyStageConfig> =
    listOf(
      DifficultyStageConfig(
        stageNumber = 1,
        label = "Stage 1 • Introductory",
        minDistanceMeters = 0.0,
        spawnIntervalSeconds = 1.65,
        maxActiveEntities = 5,
        speedVarianceFactor = 0.35,
        staticHazardProbability = 0.14,
        minReactionTimeSeconds = 1.25,
      ),
      DifficultyStageConfig(
        stageNumber = 2,
        label = "Stage 2 • Warm-Up",
        minDistanceMeters = 500.0,
        spawnIntervalSeconds = 1.38,
        maxActiveEntities = 6,
        speedVarianceFactor = 0.55,
        staticHazardProbability = 0.20,
        minReactionTimeSeconds = 1.15,
      ),
      DifficultyStageConfig(
        stageNumber = 3,
        label = "Stage 3 • Challenging",
        minDistanceMeters = 1_500.0,
        spawnIntervalSeconds = 1.15,
        maxActiveEntities = 7,
        speedVarianceFactor = 0.75,
        staticHazardProbability = 0.25,
        minReactionTimeSeconds = 1.05,
      ),
      DifficultyStageConfig(
        stageNumber = 4,
        label = "Stage 4 • Advanced",
        minDistanceMeters = 3_000.0,
        spawnIntervalSeconds = 0.98,
        maxActiveEntities = 8,
        speedVarianceFactor = 0.90,
        staticHazardProbability = 0.28,
        minReactionTimeSeconds = 0.98,
      ),
      DifficultyStageConfig(
        stageNumber = 5,
        label = "Stage 5 • Endless",
        minDistanceMeters = 5_000.0,
        spawnIntervalSeconds = 0.86,
        maxActiveEntities = 9,
        speedVarianceFactor = 1.00,
        staticHazardProbability = 0.30,
        minReactionTimeSeconds = 0.90,
      ),
    )

  fun stageForDistance(distanceMeters: Double): DifficultyStageConfig {
    val d = distanceMeters.coerceAtLeast(0.0)
    for (i in STAGES.indices.reversed()) {
      if (d >= STAGES[i].minDistanceMeters) {
        return STAGES[i]
      }
    }
    return STAGES.first()
  }
}
