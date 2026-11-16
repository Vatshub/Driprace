package com.example.assets

import android.content.Context

/**
 * Persists validated best score privately on-device.
 * Score is strictly NOT wallet currency and is updated only when exceeded.
 */
open class BestScoreRepository(context: Context) {
  private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

  open fun loadBestScore(): Int {
    val stored = prefs.getInt(KEY_BEST_SCORE, 0)
    return stored.coerceAtLeast(0)
  }

  /**
   * Updates persisted best score only if [candidateScore] strictly exceeds stored best score.
   * Returns Pair(updatedBestScore, isNewBest).
   */
  open fun recordRunScore(candidateScore: Int): Pair<Int, Boolean> {
    val safeCandidate = candidateScore.coerceAtLeast(0)
    val currentBest = loadBestScore()
    return if (safeCandidate > currentBest) {
      prefs.edit().putInt(KEY_BEST_SCORE, safeCandidate).commit()
      safeCandidate to true
    } else {
      currentBest to false
    }
  }

  companion object {
    private const val PREFS_NAME = "drip_race_phase3_scores"
    private const val KEY_BEST_SCORE = "best_score_v1"
  }
}
