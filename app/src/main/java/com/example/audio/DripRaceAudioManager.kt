package com.example.audio

import com.example.sim.DrivingSimState
import com.example.sim.PickupCollectedEvent
import com.example.sim.PowerUpType
import com.example.sim.VehiclePhysicsConfig
import com.example.state.GamePhase
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

enum class MusicPlaybackMode {
  SILENT,
  MENU_LOOP,
  RACE_LOOP,
  RACE_PAUSED_SUSPENDED,
}

data class ActiveOneShotVoice(
  val clipId: String,
  val bus: AudioBus,
  val expiresAtWallMs: Long,
  val leftGain: Float,
  val rightGain: Float,
  val pitchRate: Float,
)

data class AudioMixSnapshot(
  val auditSummary: String = "Audio package pending audit",
  val all23Verified: Boolean = false,
  val settings: AudioSettingsState = AudioSettingsState(),
  val hasHardwareVibrator: Boolean = false,
  val focusStatus: AudioFocusStatus = AudioFocusStatus.GRANTED,
  val isBackgroundSuspended: Boolean = false,
  val musicMode: MusicPlaybackMode = MusicPlaybackMode.SILENT,
  val activeMusicTrackId: String? = null,
  val activeLoopIds: Set<String> = emptySet(),
  val activeOneShotVoices: Int = 0,
  val busEffectiveGains: Map<AudioBus, Float> = emptyMap(),
  val estimatedCombinedPeakLinear: Float = 0f,
  val headroomMarginDb: Float = 20f,
  val engineFrame: EngineAudioFrameState? = null,
  val launchBannerText: String = "",
)

/**
 * Single canonical native AudioManager for DRIP RACE Phase 8.
 *
 * Owns the five contract-mandated buses (`MASTER`, `ENGINE`, `GAMEPLAY_SFX`, `AMBIENCE`, `MUSIC`),
 * enforces conservative mix headroom below `0 dBFS` without reapplying offline export `gainDb`/trims,
 * prevents duplicate engine/music loops, bounds concurrent one-shot voices (`<= 8`), handles Android
 * audio focus & lifecycle interruptions, and NEVER mutates wallet/score/physics state.
 */
class DripRaceAudioManager(
  private val assetRepository: AudioAssetRepository,
  private val backend: NativeAudioBackend,
  private val haptics: HapticsBackend,
  private val wallClockMs: () -> Long = { System.currentTimeMillis() },
) {
  private val lock = Any()
  private val engineModel = EngineAudioModel()

  private var initialized = false
  private var released = false
  private var auditReport: AudioPackageAuditReport? = null
  private var settings: AudioSettingsState = AudioSettingsState()
  private var focusStatus: AudioFocusStatus = AudioFocusStatus.GRANTED
  private var backgroundSuspended: Boolean = false
  private var currentPhase: GamePhase = GamePhase.Loading("")
  private var selectedVehicleId: String = "starter_sports"

  private var musicMode: MusicPlaybackMode = MusicPlaybackMode.SILENT
  private var activeMusicTrackId: String? = null
  private val activeShortLoops = linkedSetOf<String>()
  private var lastEngineFrame: EngineAudioFrameState? = null
  private var lastEstimatedPeakLinear: Float = 0f

  // Bounded concurrent one-shot voice tracker (<= 8) & per-clip rate limiting
  private val activeOneShots = ArrayDeque<ActiveOneShotVoice>()
  private val lastClipTriggerWallMs = HashMap<String, Long>()

  // Authoritative exact-once deduplication sets (bounded LRU + per-race reset)
  private val processedEventIds = LinkedHashSet<String>()
  private val passedTrafficObstacleIds = HashSet<Long>()
  private var prevHearts: Int = 3
  private var prevShieldBlocks: Int = 0
  private var prevNitroActive: Boolean = false
  private var prevBraking: Boolean = false
  private var lastRaceIdForAudio: String = ""
  private var countdownStageFired: Int = 0 // 0=none, 1="3", 2="2", 3="1", 4="GO!"
  private var currentLaunchBannerText: String = ""
  private var transientDuckUntilWallMs: Long = 0L

  val hasHardwareVibrator: Boolean
    get() = haptics.hasHardwareVibrator

  fun initialize(initialSettings: AudioSettingsState = AudioSettingsState()): AudioPackageAuditReport =
    synchronized(lock) {
      settings = initialSettings.canonicalized()
      val report = assetRepository.auditAudioPackage(scanWavHeadroom = false)
      auditReport = report
      if (!initialized) {
        released = false
        backend.initialize(
          loopSpecs = AudioCatalog.LOOP_ASSETS,
          oneShotSpecs = AudioCatalog.ONESHOT_ASSETS,
          onFocusChanged = ::onAudioFocusChanged,
        )
        initialized = true
      }
      refreshMusicAndLoopsLocked()
      report
    }

  fun updateSettings(newSettings: AudioSettingsState): AudioMixSnapshot =
    synchronized(lock) {
      settings = newSettings.canonicalized()
      if (settings.muted || (!settings.musicEnabled && !settings.sfxEnabled)) {
        backend.stopAllActiveSounds()
        activeShortLoops.clear()
      } else if (!backgroundSuspended && currentPhase !is GamePhase.Loading && currentPhase !is GamePhase.Error) {
        ensureFocusIfNeededLocked()
      }
      refreshMusicAndLoopsLocked()
      buildSnapshotLocked()
    }

  fun currentSettings(): AudioSettingsState = synchronized(lock) { settings }

  fun currentSnapshot(): AudioMixSnapshot = synchronized(lock) { buildSnapshotLocked() }

  fun onAudioFocusChanged(newFocus: AudioFocusStatus) {
    synchronized(lock) {
      if (released) return
      focusStatus = newFocus
      when (newFocus) {
        AudioFocusStatus.PERMANENT_LOSS,
        AudioFocusStatus.TRANSIENT_LOSS -> {
          silenceContinuousLoopsLocked(preservePhase = true)
          backend.stopAllActiveSounds()
        }
        AudioFocusStatus.TRANSIENT_DUCK,
        AudioFocusStatus.GRANTED,
        AudioFocusStatus.UNAVAILABLE -> {
          refreshMusicAndLoopsLocked()
        }
      }
    }
  }

  fun onLifecycleActiveChanged(active: Boolean) {
    synchronized(lock) {
      if (released) return
      backgroundSuspended = !active
      if (!active) {
        silenceContinuousLoopsLocked(preservePhase = true)
        backend.stopAllActiveSounds()
        backend.abandonPlaybackFocus()
      } else {
        ensureFocusIfNeededLocked()
        refreshMusicAndLoopsLocked()
      }
    }
  }

  fun onPhaseOrVehicleChanged(
    phase: GamePhase,
    vehicleId: String,
    raceId: String = "",
  ) {
    synchronized(lock) {
      if (released) return
      val prevPhase = currentPhase
      currentPhase = phase
      if (vehicleId != selectedVehicleId) {
        selectedVehicleId = vehicleId
        engineModel.reset(vehicleId)
      }

      if (phase is GamePhase.Running && raceId.isNotEmpty() && raceId != lastRaceIdForAudio) {
        // Fresh race started -> reset per-race exact-once audio trackers & fire first countdown beep
        lastRaceIdForAudio = raceId
        passedTrafficObstacleIds.clear()
        prevHearts = 3
        prevShieldBlocks = 0
        prevNitroActive = false
        prevBraking = false
        countdownStageFired = 0
        currentLaunchBannerText = "3"
        engineModel.reset(vehicleId)
      }

      if (phase is GamePhase.GameOver && prevPhase !is GamePhase.GameOver) {
        currentLaunchBannerText = ""
        val goKey = "gameover_${lastRaceIdForAudio.ifEmpty { "run" }}"
        triggerAuthoritativeOneShotLocked(
          clipId = AudioCatalog.GAME_OVER_ID,
          dedupeKey = goKey,
          haptic = HapticCueKind.HEAVY_COLLISION,
        )
      } else if (phase !is GamePhase.Running) {
        currentLaunchBannerText = ""
      }

      ensureFocusIfNeededLocked()
      refreshMusicAndLoopsLocked()
    }
  }

  /**
   * Steps continuous engine, road-noise, wind, nitro-sustain, race launch cadence, braking skid,
   * shield break, impact, and spatial stereo traffic pass-bys from authoritative simulation state.
   */
  fun onSimulationTick(
    simState: DrivingSimState,
    physicsConfig: VehiclePhysicsConfig,
    dtSeconds: Double = 1.0 / 60.0,
  ): AudioMixSnapshot =
    synchronized(lock) {
      if (released || currentPhase !is GamePhase.Running || simState.isGameOver) {
        return@synchronized buildSnapshotLocked()
      }

      val nowMs = wallClockMs()
      pruneExpiredOneShotsLocked(nowMs)

      // 1. Non-blocking 3 - 2 - 1 - GO! race launch cadence driven by simulation clock
      updateRaceLaunchCadenceLocked(simState.totalSimSeconds)

      // 2. Authoritative discrete simulation state edges
      if (simState.shieldBlocksCount > prevShieldBlocks) {
        prevShieldBlocks = simState.shieldBlocksCount
        transientDuckUntilWallMs = nowMs + 420L
        triggerAuthoritativeOneShotLocked(
          clipId = AudioCatalog.SHIELD_BREAK_ID,
          dedupeKey = "shield_break_${lastRaceIdForAudio}_${simState.shieldBlocksCount}",
          haptic = HapticCueKind.SHIELD_DEFLECT,
        )
      }

      if (simState.hearts < prevHearts) {
        prevHearts = simState.hearts
        transientDuckUntilWallMs = nowMs + 550L
        triggerAuthoritativeOneShotLocked(
          clipId = AudioCatalog.COLLISION_IMPACT_ID,
          dedupeKey = "impact_${lastRaceIdForAudio}_hearts_${simState.hearts}",
          haptic = HapticCueKind.HEAVY_COLLISION,
        )
      } else if (simState.hearts > prevHearts) {
        prevHearts = simState.hearts
      }

      if (simState.isNitroActive && !prevNitroActive) {
        triggerOneShotInternalLocked(
          clipId = AudioCatalog.NITRO_ACTIVATE_ID,
          leftPan = 1f,
          rightPan = 1f,
          pitchRate = 1.0f,
          bypassRateLimit = false,
        )
        triggerHapticLocked(HapticCueKind.NITRO_SURGE)
      }
      prevNitroActive = simState.isNitroActive

      if (simState.isBraking && (!prevBraking || simState.speedMetersPerSecond >= 22.0) && simState.speedMetersPerSecond >= 15.0) {
        triggerOneShotInternalLocked(
          clipId = AudioCatalog.BRAKE_SKID_ID,
          leftPan = 1f,
          rightPan = 1f,
          pitchRate = (0.92f + (simState.speedMetersPerSecond / 80.0).toFloat() * 0.22f).coerceIn(0.90f, 1.16f),
          bypassRateLimit = false,
        )
      }
      prevBraking = simState.isBraking

      // 3. Spatial stereo traffic pass-bys as nearby moving vehicles cross player Z=0
      for (obs in simState.activeObstacles) {
        if (obs.isStaticHazard) continue
        if (obs.relativeZ <= 1.2 && obs.relativeZ >= -6.5 && obs.logicalId !in passedTrafficObstacleIds) {
          val lateralDist = kotlin.math.abs(obs.x - simState.carX)
          if (lateralDist <= 4.6f) {
            passedTrafficObstacleIds += obs.logicalId
            val relSpeed = max(5.0, simState.speedMetersPerSecond - obs.speedMetersPerSecond)
            val spatial =
              EngineAudioModel.computeSpatialPassBy(
                obstacleX = obs.x,
                playerCarX = simState.carX,
                relativeClosingSpeedMps = relSpeed,
              )
            triggerOneShotInternalLocked(
              clipId = AudioCatalog.TRAFFIC_PASS_ID,
              leftPan = spatial.leftGain,
              rightPan = spatial.rightGain,
              pitchRate = spatial.pitchRate,
              bypassRateLimit = false,
            )
          }
        }
      }

      // 4. Five-profile engine & ambience continuous stems
      val engineFrame =
        engineModel.stepTelemetry(
          vehicleId = physicsConfig.id,
          speedMetersPerSecond = simState.speedMetersPerSecond,
          topSpeedMetersPerSecond = physicsConfig.topSpeedMps,
          boostedTopSpeedMetersPerSecond = physicsConfig.boostedTopSpeedMps,
          isBraking = simState.isBraking,
          isNitroActive = simState.isNitroActive,
          dtSeconds = dtSeconds,
        )
      lastEngineFrame = engineFrame
      applyContinuousDrivingLoopsLocked(engineFrame, nowMs)
      buildSnapshotLocked()
    }

  fun onPickupCollected(event: PickupCollectedEvent) {
    synchronized(lock) {
      if (released) return
      when (event) {
        is PickupCollectedEvent.CoinCollected -> {
          val pitch =
            when {
              event.baseValue >= 50 -> 1.16f
              event.baseValue >= 25 -> 1.08f
              else -> 1.00f
            }
          triggerAuthoritativeOneShotLocked(
            clipId = AudioCatalog.COIN_PICKUP_ID,
            dedupeKey = "pickup_${event.operationId}",
            pitchRate = pitch,
            haptic = HapticCueKind.PICKUP_PULSE,
          )
        }
        is PickupCollectedEvent.PowerUpCollected -> {
          val clipId =
            when (event.powerUpType) {
              PowerUpType.DOUBLE_COINS -> AudioCatalog.DOUBLE_COINS_PICKUP_ID
              PowerUpType.MAGNET -> AudioCatalog.MAGNET_ACTIVATE_ID
              PowerUpType.SHIELD -> AudioCatalog.SHIELD_ACTIVATE_ID
              PowerUpType.AUTOMATIC_NITRO -> AudioCatalog.NITRO_ACTIVATE_ID
            }
          triggerAuthoritativeOneShotLocked(
            clipId = clipId,
            dedupeKey = "powerup_${event.operationId}",
            haptic = HapticCueKind.PICKUP_PULSE,
          )
        }
      }
    }
  }

  fun onPurchaseConfirmed(operationId: String) {
    synchronized(lock) {
      if (released || operationId.isBlank()) return
      triggerAuthoritativeOneShotLocked(
        clipId = AudioCatalog.PURCHASE_CONFIRM_ID,
        dedupeKey = "purchase_$operationId",
        haptic = HapticCueKind.PICKUP_PULSE,
      )
    }
  }

  fun onProgressionUnlockedOrClaimed(dedupeKey: String) {
    synchronized(lock) {
      if (released || dedupeKey.isBlank()) return
      triggerAuthoritativeOneShotLocked(
        clipId = AudioCatalog.ACHIEVEMENT_UNLOCK_ID,
        dedupeKey = "unlock_$dedupeKey",
        haptic = HapticCueKind.PICKUP_PULSE,
      )
    }
  }

  fun playUiClick(withHapticTick: Boolean = false) {
    synchronized(lock) {
      if (released) return
      ensureFocusIfNeededLocked()
      triggerOneShotInternalLocked(
        clipId = AudioCatalog.UI_CLICK_ID,
        leftPan = 1f,
        rightPan = 1f,
        pitchRate = 1.0f,
        bypassRateLimit = false,
      )
      if (withHapticTick) {
        triggerHapticLocked(HapticCueKind.LIGHT_TICK)
      }
    }
  }

  fun onSteerCue(direction: Int) {
    synchronized(lock) {
      if (released) return
      val panLeft = if (direction < 0) 1.0f else 0.72f
      val panRight = if (direction > 0) 1.0f else 0.72f
      triggerOneShotInternalLocked(
        clipId = AudioCatalog.UI_CLICK_ID,
        leftPan = panLeft,
        rightPan = panRight,
        pitchRate = if (direction < 0) 0.96f else 1.04f,
        bypassRateLimit = false,
      )
      triggerHapticLocked(HapticCueKind.LIGHT_TICK)
    }
  }

  fun release() {
    synchronized(lock) {
      if (released) return
      released = true
      initialized = false
      activeShortLoops.clear()
      activeOneShots.clear()
      activeMusicTrackId = null
      musicMode = MusicPlaybackMode.SILENT
      backend.release()
    }
  }

  private fun updateRaceLaunchCadenceLocked(simSeconds: Double) {
    when {
      simSeconds < 0.55 && countdownStageFired < 1 -> {
        countdownStageFired = 1
        currentLaunchBannerText = "3"
        triggerOneShotInternalLocked(AudioCatalog.COUNTDOWN_BEEP_ID, 1f, 1f, 0.96f, bypassRateLimit = true)
      }
      simSeconds in 0.55..<1.10 && countdownStageFired < 2 -> {
        countdownStageFired = 2
        currentLaunchBannerText = "2"
        triggerOneShotInternalLocked(AudioCatalog.COUNTDOWN_BEEP_ID, 1f, 1f, 1.00f, bypassRateLimit = true)
      }
      simSeconds in 1.10..<1.65 && countdownStageFired < 3 -> {
        countdownStageFired = 3
        currentLaunchBannerText = "1"
        triggerOneShotInternalLocked(AudioCatalog.COUNTDOWN_BEEP_ID, 1f, 1f, 1.05f, bypassRateLimit = true)
      }
      simSeconds in 1.65..<2.45 && countdownStageFired < 4 -> {
        countdownStageFired = 4
        currentLaunchBannerText = "GO!"
        triggerOneShotInternalLocked(AudioCatalog.COUNTDOWN_GO_ID, 1f, 1f, 1.0f, bypassRateLimit = true)
        triggerHapticLocked(HapticCueKind.PICKUP_PULSE)
      }
      simSeconds >= 2.45 -> {
        currentLaunchBannerText = ""
      }
    }
  }

  private fun ensureFocusIfNeededLocked() {
    if (!initialized || backgroundSuspended || settings.muted) return
    if (!settings.musicEnabled && !settings.sfxEnabled) return
    if (focusStatus == AudioFocusStatus.PERMANENT_LOSS || focusStatus == AudioFocusStatus.TRANSIENT_LOSS) {
      focusStatus = backend.requestPlaybackFocus()
    } else if (focusStatus == AudioFocusStatus.GRANTED) {
      val granted = backend.requestPlaybackFocus()
      if (granted != AudioFocusStatus.UNAVAILABLE) {
        focusStatus = granted
      }
    }
  }

  private fun computeBusGainsLocked(nowMs: Long): Map<AudioBus, Float> {
    val focusAudible =
      !backgroundSuspended &&
        !settings.muted &&
        focusStatus != AudioFocusStatus.PERMANENT_LOSS &&
        focusStatus != AudioFocusStatus.TRANSIENT_LOSS
    if (!focusAudible) {
      return AudioBus.entries.associateWith { 0f }
    }

    val focusDuck = if (focusStatus == AudioFocusStatus.TRANSIENT_DUCK) 0.25f else 1.0f
    val master = settings.masterVolume * AudioBus.MASTER.defaultCeilingLinear * focusDuck

    val sfxMaster = if (settings.sfxEnabled) master * settings.sfxVolume else 0f
    val musicTransientDuck =
      when {
        nowMs < transientDuckUntilWallMs -> 0.62f
        lastEngineFrame?.nitroSustainGain ?: 0f > 0.1f -> 0.80f
        else -> 1.0f
      }
    val musicGain =
      if (settings.musicEnabled) {
        master * settings.musicVolume * AudioBus.MUSIC.defaultCeilingLinear * musicTransientDuck
      } else {
        0f
      }

    return mapOf(
      AudioBus.MASTER to master,
      AudioBus.ENGINE to (sfxMaster * AudioBus.ENGINE.defaultCeilingLinear),
      AudioBus.GAMEPLAY_SFX to (sfxMaster * AudioBus.GAMEPLAY_SFX.defaultCeilingLinear),
      AudioBus.AMBIENCE to (sfxMaster * AudioBus.AMBIENCE.defaultCeilingLinear),
      AudioBus.MUSIC to musicGain,
    )
  }

  private fun refreshMusicAndLoopsLocked() {
    if (!initialized || released) return
    val nowMs = wallClockMs()
    val buses = computeBusGainsLocked(nowMs)
    val musicBusGain = buses[AudioBus.MUSIC] ?: 0f

    // Determine single coherent music state
    when (currentPhase) {
      is GamePhase.Home,
      is GamePhase.Garage -> {
        silenceDrivingStemsOnlyLocked()
        if (musicBusGain > 0.001f) {
          musicMode = MusicPlaybackMode.MENU_LOOP
          activeMusicTrackId = AudioCatalog.MUSIC_MENU_ID
          val clipGain = AudioCatalog.findAsset(AudioCatalog.MUSIC_MENU_ID)?.defaultClipGain ?: 0.8f
          backend.setMusicLoopState(AudioCatalog.MUSIC_MENU_ID, playing = true, gain = musicBusGain * clipGain)
        } else {
          musicMode = MusicPlaybackMode.SILENT
          activeMusicTrackId = AudioCatalog.MUSIC_MENU_ID
          backend.setMusicLoopState(AudioCatalog.MUSIC_MENU_ID, playing = false, gain = 0f)
        }
      }
      is GamePhase.Running -> {
        if (musicBusGain > 0.001f) {
          musicMode = MusicPlaybackMode.RACE_LOOP
          activeMusicTrackId = AudioCatalog.MUSIC_RACE_ID
          val clipGain = AudioCatalog.findAsset(AudioCatalog.MUSIC_RACE_ID)?.defaultClipGain ?: 0.78f
          backend.setMusicLoopState(AudioCatalog.MUSIC_RACE_ID, playing = true, gain = musicBusGain * clipGain)
        } else {
          musicMode = MusicPlaybackMode.SILENT
          activeMusicTrackId = AudioCatalog.MUSIC_RACE_ID
          backend.setMusicLoopState(AudioCatalog.MUSIC_RACE_ID, playing = false, gain = 0f)
        }
        lastEngineFrame?.let { applyContinuousDrivingLoopsLocked(it, nowMs) }
      }
      is GamePhase.Paused -> {
        // Suspend engine/nitro/ambience immediately on Pause; pause race music cleanly without destroying position
        silenceDrivingStemsOnlyLocked()
        musicMode = MusicPlaybackMode.RACE_PAUSED_SUSPENDED
        activeMusicTrackId = AudioCatalog.MUSIC_RACE_ID
        backend.setMusicLoopState(AudioCatalog.MUSIC_RACE_ID, playing = false, gain = 0f)
      }
      is GamePhase.GameOver,
      is GamePhase.Loading,
      is GamePhase.Error -> {
        silenceDrivingStemsOnlyLocked()
        musicMode = MusicPlaybackMode.SILENT
        activeMusicTrackId = null
        backend.setMusicLoopState(null, playing = false, gain = 0f)
      }
    }
    recomputePeakHeadroomLocked(buses)
  }

  private fun applyContinuousDrivingLoopsLocked(frame: EngineAudioFrameState, nowMs: Long) {
    val buses = computeBusGainsLocked(nowMs)
    val engineBus = buses[AudioBus.ENGINE] ?: 0f
    val ambienceBus = buses[AudioBus.AMBIENCE] ?: 0f
    val sfxBus = buses[AudioBus.GAMEPLAY_SFX] ?: 0f

    if (engineBus <= 0.001f || currentPhase !is GamePhase.Running) {
      silenceDrivingStemsOnlyLocked()
      recomputePeakHeadroomLocked(buses)
      return
    }

    val idleSpec = AudioCatalog.findAsset(AudioCatalog.ENGINE_IDLE_ID)!!
    val steadySpec = AudioCatalog.findAsset(AudioCatalog.ENGINE_STEADY_ID)!!
    val highSpec = AudioCatalog.findAsset(AudioCatalog.ENGINE_HIGH_ID)!!
    val roadSpec = AudioCatalog.findAsset(AudioCatalog.ROAD_NOISE_ID)!!
    val windSpec = AudioCatalog.findAsset(AudioCatalog.WIND_ID)!!
    val nitroSpec = AudioCatalog.findAsset(AudioCatalog.NITRO_SUSTAIN_ID)!!

    updateLoopVoiceLocked(
      id = AudioCatalog.ENGINE_IDLE_ID,
      gain = engineBus * idleSpec.defaultClipGain * frame.idleStemGain,
      pitch = frame.idlePitch,
    )
    updateLoopVoiceLocked(
      id = AudioCatalog.ENGINE_STEADY_ID,
      gain = engineBus * steadySpec.defaultClipGain * frame.steadyStemGain,
      pitch = frame.steadyPitch,
    )
    updateLoopVoiceLocked(
      id = AudioCatalog.ENGINE_HIGH_ID,
      gain = engineBus * highSpec.defaultClipGain * frame.highStemGain,
      pitch = frame.highPitch,
    )
    backend.setEngineSynthCharacterState(
      active = true,
      freqHz = frame.synthCharacterFreqHz,
      subOctaveBlend = frame.synthSubOctaveBlend,
      gain = engineBus * frame.synthCharacterGain,
    )

    updateLoopVoiceLocked(
      id = AudioCatalog.ROAD_NOISE_ID,
      gain = ambienceBus * roadSpec.defaultClipGain * frame.roadNoiseGain,
      pitch = frame.roadNoisePitch,
    )
    updateLoopVoiceLocked(
      id = AudioCatalog.WIND_ID,
      gain = ambienceBus * windSpec.defaultClipGain * frame.windGain,
      pitch = frame.windPitch,
    )
    updateLoopVoiceLocked(
      id = AudioCatalog.NITRO_SUSTAIN_ID,
      gain = sfxBus * nitroSpec.defaultClipGain * frame.nitroSustainGain,
      pitch = frame.nitroSustainPitch,
    )

    // Keep music ducking synced with Nitro/impacts
    val musicBus = buses[AudioBus.MUSIC] ?: 0f
    if (musicMode == MusicPlaybackMode.RACE_LOOP && musicBus > 0.001f) {
      val raceMusicClip = AudioCatalog.findAsset(AudioCatalog.MUSIC_RACE_ID)?.defaultClipGain ?: 0.78f
      backend.setMusicLoopState(AudioCatalog.MUSIC_RACE_ID, playing = true, gain = musicBus * raceMusicClip)
    }

    recomputePeakHeadroomLocked(buses)
  }

  private fun updateLoopVoiceLocked(id: String, gain: Float, pitch: Float) {
    val g = gain.coerceIn(0f, AudioCatalog.MIX_HEADROOM_CEILING_LINEAR)
    if (g > 0.0015f) {
      activeShortLoops += id
      backend.setShortLoopState(id, active = true, leftGain = g, rightGain = g, pitchRate = pitch)
    } else {
      activeShortLoops -= id
      backend.setShortLoopState(id, active = false, leftGain = 0f, rightGain = 0f, pitchRate = pitch)
    }
  }

  private fun silenceDrivingStemsOnlyLocked() {
    val drivingIds =
      listOf(
        AudioCatalog.ENGINE_IDLE_ID,
        AudioCatalog.ENGINE_STEADY_ID,
        AudioCatalog.ENGINE_HIGH_ID,
        AudioCatalog.NITRO_SUSTAIN_ID,
        AudioCatalog.ROAD_NOISE_ID,
        AudioCatalog.WIND_ID,
      )
    for (id in drivingIds) {
      activeShortLoops -= id
      backend.setShortLoopState(id, active = false, leftGain = 0f, rightGain = 0f, pitchRate = 1f)
    }
    backend.setEngineSynthCharacterState(active = false, freqHz = 90f, subOctaveBlend = 0f, gain = 0f)
  }

  private fun silenceContinuousLoopsLocked(preservePhase: Boolean) {
    silenceDrivingStemsOnlyLocked()
    backend.setMusicLoopState(activeMusicTrackId, playing = false, gain = 0f)
    if (!preservePhase) {
      activeMusicTrackId = null
      musicMode = MusicPlaybackMode.SILENT
    }
  }

  private fun triggerAuthoritativeOneShotLocked(
    clipId: String,
    dedupeKey: String,
    pitchRate: Float = 1.0f,
    haptic: HapticCueKind? = null,
  ) {
    if (dedupeKey.isNotEmpty() && !recordEventIdOnceLocked(dedupeKey)) {
      return
    }
    triggerOneShotInternalLocked(
      clipId = clipId,
      leftPan = 1f,
      rightPan = 1f,
      pitchRate = pitchRate,
      bypassRateLimit = true,
    )
    if (haptic != null) {
      triggerHapticLocked(haptic)
    }
  }

  private fun triggerOneShotInternalLocked(
    clipId: String,
    leftPan: Float,
    rightPan: Float,
    pitchRate: Float,
    bypassRateLimit: Boolean,
  ) {
    val spec = AudioCatalog.findAsset(clipId) ?: return
    val nowMs = wallClockMs()
    pruneExpiredOneShotsLocked(nowMs)

    if (!bypassRateLimit) {
      val prevMs = lastClipTriggerWallMs[clipId]
      if (prevMs != null && nowMs - prevMs < spec.minRetriggerIntervalMs) {
        return
      }
    }

    val buses = computeBusGainsLocked(nowMs)
    val busGain = buses[spec.bus] ?: 0f
    if (busGain <= 0.001f) return

    // Enforce bounded one-shot voice pool (<= 8 concurrent)
    while (activeOneShots.size >= AudioCatalog.MAX_CONCURRENT_ONESHOT_VOICES) {
      activeOneShots.removeFirst()
    }

    // Multi-voice soft headroom scaler prevents clipping when multiple one-shots overlap
    val overlapDensity = (activeOneShots.size + 1).coerceAtLeast(1)
    val polyphonyHeadroom = 1.0f / sqrt(overlapDensity.toFloat())
    val baseScalar = (busGain * spec.defaultClipGain * polyphonyHeadroom)
      .coerceIn(0f, AudioCatalog.MIX_HEADROOM_CEILING_LINEAR)

    val left = (baseScalar * leftPan).coerceIn(0f, AudioCatalog.MIX_HEADROOM_CEILING_LINEAR)
    val right = (baseScalar * rightPan).coerceIn(0f, AudioCatalog.MIX_HEADROOM_CEILING_LINEAR)
    if (left <= 0.001f && right <= 0.001f) return

    lastClipTriggerWallMs[clipId] = nowMs
    backend.playOneShot(
      clipId = clipId,
      leftGain = left,
      rightGain = right,
      priority = spec.priority,
      pitchRate = pitchRate,
    )

    val durMs = (spec.durationSeconds * 1000.0 / pitchRate.coerceAtLeast(0.6f)).toLong().coerceIn(80L, 4_000L)
    activeOneShots.addLast(
      ActiveOneShotVoice(
        clipId = clipId,
        bus = spec.bus,
        expiresAtWallMs = nowMs + durMs,
        leftGain = left,
        rightGain = right,
        pitchRate = pitchRate,
      )
    )
    recomputePeakHeadroomLocked(buses)
  }

  private fun triggerHapticLocked(cue: HapticCueKind) {
    if (!settings.hapticsEnabled || backgroundSuspended || !haptics.hasHardwareVibrator) return
    haptics.performHaptic(cue)
  }

  private fun pruneExpiredOneShotsLocked(nowMs: Long) {
    val iter = activeOneShots.iterator()
    while (iter.hasNext()) {
      if (iter.next().expiresAtWallMs <= nowMs) {
        iter.remove()
      }
    }
  }

  private fun recordEventIdOnceLocked(key: String): Boolean {
    if (key in processedEventIds) return false
    processedEventIds += key
    while (processedEventIds.size > MAX_RECENT_AUDIO_EVENT_IDS) {
      val first = processedEventIds.first()
      processedEventIds.remove(first)
    }
    return true
  }

  private fun recomputePeakHeadroomLocked(buses: Map<AudioBus, Float>) {
    // Estimate worst-case combined linear amplitude using exported file peak dBFS from decode-checks.json
    var peakSum = 0f
    if (activeShortLoops.isNotEmpty()) {
      val engineBus = buses[AudioBus.ENGINE] ?: 0f
      // Normalized 3-stem engine peak (-9.1 dBFS = 0.35 linear) + synth layer
      peakSum += engineBus * 0.35f + engineBus * (lastEngineFrame?.synthCharacterGain ?: 0f)
      val ambBus = buses[AudioBus.AMBIENCE] ?: 0f
      peakSum += ambBus * 0.31f // road-noise (-10.06 dBFS) + wind (-13.94 dBFS)
      if (AudioCatalog.NITRO_SUSTAIN_ID in activeShortLoops) {
        peakSum += (buses[AudioBus.GAMEPLAY_SFX] ?: 0f) * 0.64f * 0.50f // -6.0 dBFS = 0.501 linear
      }
    }
    if (musicMode == MusicPlaybackMode.MENU_LOOP || musicMode == MusicPlaybackMode.RACE_LOOP) {
      peakSum += (buses[AudioBus.MUSIC] ?: 0f) * 0.78f * 0.28f // -11.1 dBFS = 0.279 linear
    }
    if (activeOneShots.isNotEmpty()) {
      val maxOneShotGain = activeOneShots.maxOf { max(it.leftGain, it.rightGain) }
      // Exported MP3 peaks are ~ -6.1 to -6.5 dBFS (~0.49 linear), multiplied by sqrt(N) bound
      peakSum += maxOneShotGain * 0.50f * sqrt(activeOneShots.size.coerceAtMost(3).toFloat())
    }
    lastEstimatedPeakLinear = peakSum.coerceAtMost(AudioCatalog.MIX_HEADROOM_CEILING_LINEAR)
  }

  private fun dbfsToLinear(dbfs: Double): Float = 10.0.pow(dbfs / 20.0).toFloat()

  private fun buildSnapshotLocked(): AudioMixSnapshot {
    val nowMs = wallClockMs()
    pruneExpiredOneShotsLocked(nowMs)
    val buses = computeBusGainsLocked(nowMs)
    recomputePeakHeadroomLocked(buses)
    val activeLoopsAll = buildSet {
      addAll(activeShortLoops)
      if ((musicMode == MusicPlaybackMode.MENU_LOOP || musicMode == MusicPlaybackMode.RACE_LOOP) &&
        activeMusicTrackId != null &&
        (buses[AudioBus.MUSIC] ?: 0f) > 0.001f
      ) {
        add(activeMusicTrackId!!)
      }
    }
    val marginDb =
      if (lastEstimatedPeakLinear <= 0.0001f) 40f
      else (-20.0 * kotlin.math.log10(lastEstimatedPeakLinear.toDouble())).toFloat()
    return AudioMixSnapshot(
      auditSummary = auditReport?.summaryLine ?: "Audio package pending audit",
      all23Verified = auditReport?.allVerified == true,
      settings = settings,
      hasHardwareVibrator = haptics.hasHardwareVibrator,
      focusStatus = focusStatus,
      isBackgroundSuspended = backgroundSuspended,
      musicMode = musicMode,
      activeMusicTrackId = activeMusicTrackId,
      activeLoopIds = activeLoopsAll,
      activeOneShotVoices = activeOneShots.size,
      busEffectiveGains = buses,
      estimatedCombinedPeakLinear = lastEstimatedPeakLinear,
      headroomMarginDb = marginDb,
      engineFrame = lastEngineFrame,
      launchBannerText = currentLaunchBannerText,
    )
  }

  companion object {
    private const val MAX_RECENT_AUDIO_EVENT_IDS = 256
  }
}
