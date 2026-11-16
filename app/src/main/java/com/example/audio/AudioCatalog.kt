package com.example.audio

import com.example.garage.HeroVehicleCatalog

/**
 * The five contract-mandated audio mix buses owned by the single managed [DripRaceAudioManager].
 */
enum class AudioBus(val id: String, val displayName: String, val defaultCeilingLinear: Float) {
  MASTER("MASTER", "Master", 0.90f),
  ENGINE("ENGINE", "Engine", 0.50f),
  GAMEPLAY_SFX("GAMEPLAY_SFX", "Gameplay SFX", 0.66f),
  AMBIENCE("AMBIENCE", "Ambience", 0.36f),
  MUSIC("MUSIC", "Music", 0.46f),
}

enum class AudioFormatKind {
  WAV_LOOP_PCM16,
  MP3_ONESHOT,
}

/**
 * Authoritative metadata entry for each of the 23 user-created, prepared audio files in
 * `app/src/main/assets/audio/` (`AssetManager` path `audio/<fileName>`).
 *
 * IMPORTANT: `historicalPrepGainDb`, `historicalTrimStartSec`, and `historicalOverlapSec` record
 * offline preparation already baked into these exported files (`references/audio/manifest.json` &
 * `ANDROID-PATH-MAPPING.json`). Runtime playback MUST NOT reapply those preparation transforms.
 */
data class PreparedAudioAssetSpec(
  val id: String,
  val fileName: String,
  val format: AudioFormatKind,
  val bus: AudioBus,
  val isLoop: Boolean,
  val expectedBytes: Long,
  val expectedSha256: String,
  val sampleRateHz: Int = 48_000,
  val channels: Int = 2,
  val expectedPcmFrames: Int,
  val durationSeconds: Double,
  val exportedPeakDbfs: Double,
  val defaultClipGain: Float,
  val minRetriggerIntervalMs: Long = 45L,
  val priority: Int = 1,
  val listeningLoopApprovedStandalone: Boolean = false,
) {
  val assetPath: String
    get() = "audio/$fileName"

  /** Explicit guard confirming offline preparation is never re-applied at runtime. */
  val preparationAlreadyApplied: Boolean = true
}

/**
 * Persisted player audio, accessibility, and optional haptics settings.
 */
data class AudioSettingsState(
  val masterVolume: Float = 0.85f,
  val musicEnabled: Boolean = true,
  val musicVolume: Float = 0.72f,
  val sfxEnabled: Boolean = true,
  val sfxVolume: Float = 0.85f,
  val muted: Boolean = false,
  val hapticsEnabled: Boolean = true,
  val reducedMotion: Boolean = false,
) {
  fun canonicalized(): AudioSettingsState =
    copy(
      masterVolume = if (masterVolume.isFinite()) masterVolume.coerceIn(0f, 1f) else 0.85f,
      musicVolume = if (musicVolume.isFinite()) musicVolume.coerceIn(0f, 1f) else 0.72f,
      sfxVolume = if (sfxVolume.isFinite()) sfxVolume.coerceIn(0f, 1f) else 0.85f,
    )
}

/**
 * Per-vehicle acoustic tuning profile for the 5 DRIP RACE hero cars.
 *
 * Uses the single original 3-stem engine recording set (`engine-idle.wav`, `engine-steady.wav`,
 * `engine-high.wav`) plus a clearly and honestly labeled procedural harmonic character layer
 * (`synthesizedLayerLabel`) to distinguish each car's timbre without claiming 5 separate studio
 * recordings exist. Virtual gear/RPM shifts are strictly audio-only and never alter physics.
 */
data class CarEngineAudioProfile(
  val vehicleId: String,
  val profileName: String,
  val characterSummary: String,
  val gearCount: Int,
  val idleRpm: Double,
  val redlineRpm: Double,
  val shiftDurationSeconds: Double,
  val idleBasePitch: Float,
  val steadyBasePitch: Float,
  val highBasePitch: Float,
  val maxPitchMultiplier: Float,
  val idleStemWeightBias: Float,
  val steadyStemWeightBias: Float,
  val highStemWeightBias: Float,
  val loadSensitivity: Float,
  val nitroPitchBoost: Float,
  val harmonicOrder: Float,
  val subOctaveBlend: Float,
  val maxSynthCharacterGain: Float,
  val synthesizedLayerLabel: String,
)

object AudioCatalog {
  const val PROVENANCE_SUMMARY =
    "23 user-created prepared audio files (8 WAV loops + 15 MP3 one-shots, 48 kHz stereo). " +
      "Standalone 8-loop transition review completed 2026-11-16; live Android mix & 5 car profiles require on-device listening check."

  const val MAX_CONCURRENT_ONESHOT_VOICES = 8
  const val MIX_HEADROOM_CEILING_LINEAR = 0.89f // ~ -1.01 dBFS anti-clipping ceiling

  // --- Loop IDs (8 WAVs) ---
  const val ENGINE_IDLE_ID = "engine-idle"
  const val ENGINE_STEADY_ID = "engine-steady"
  const val ENGINE_HIGH_ID = "engine-high"
  const val NITRO_SUSTAIN_ID = "nitro-sustain"
  const val ROAD_NOISE_ID = "road-noise"
  const val WIND_ID = "wind"
  const val MUSIC_MENU_ID = "music-menu"
  const val MUSIC_RACE_ID = "music-race"

  // --- One-Shot IDs (15 MP3s) ---
  const val BRAKE_SKID_ID = "brake-skid"
  const val UI_CLICK_ID = "ui-click"
  const val COIN_PICKUP_ID = "coin-pickup"
  const val COLLISION_IMPACT_ID = "collision-impact"
  const val COUNTDOWN_BEEP_ID = "countdown-beep"
  const val COUNTDOWN_GO_ID = "countdown-go"
  const val DOUBLE_COINS_PICKUP_ID = "double-coins-pickup"
  const val GAME_OVER_ID = "game-over"
  const val MAGNET_ACTIVATE_ID = "magnet-activate"
  const val NITRO_ACTIVATE_ID = "nitro-activate"
  const val PURCHASE_CONFIRM_ID = "purchase-confirm"
  const val SHIELD_ACTIVATE_ID = "shield-activate"
  const val SHIELD_BREAK_ID = "shield-break"
  const val TRAFFIC_PASS_ID = "traffic-pass"
  const val ACHIEVEMENT_UNLOCK_ID = "achievement-unlock"

  val ALL_AUDIO_ASSETS: List<PreparedAudioAssetSpec> =
    listOf(
      // 8 Listening-Approved 48 kHz 16-bit Stereo WAV Loops
      PreparedAudioAssetSpec(
        id = ENGINE_IDLE_ID,
        fileName = "engine-idle.wav",
        format = AudioFormatKind.WAV_LOOP_PCM16,
        bus = AudioBus.ENGINE,
        isLoop = true,
        expectedBytes = 2_127_404L,
        expectedSha256 = "1a54b21c6089972914fee289466531ab5b3edef132c33f8a0cfda80cb897a1cf",
        expectedPcmFrames = 531_840,
        durationSeconds = 11.08,
        exportedPeakDbfs = -10.755440945714481,
        defaultClipGain = 0.86f,
        priority = 3,
        listeningLoopApprovedStandalone = true,
      ),
      PreparedAudioAssetSpec(
        id = ENGINE_STEADY_ID,
        fileName = "engine-steady.wav",
        format = AudioFormatKind.WAV_LOOP_PCM16,
        bus = AudioBus.ENGINE,
        isLoop = true,
        expectedBytes = 1_897_004L,
        expectedSha256 = "4175afc5d44c331c8125edd55bd3abbd6446655b3dff0b712592cfdfa6c4aa48",
        expectedPcmFrames = 474_240,
        durationSeconds = 9.88,
        exportedPeakDbfs = -10.03708822844857,
        defaultClipGain = 0.88f,
        priority = 3,
        listeningLoopApprovedStandalone = true,
      ),
      PreparedAudioAssetSpec(
        id = ENGINE_HIGH_ID,
        fileName = "engine-high.wav",
        format = AudioFormatKind.WAV_LOOP_PCM16,
        bus = AudioBus.ENGINE,
        isLoop = true,
        expectedBytes = 1_850_924L,
        expectedSha256 = "e5a517f1f5adf5b3322991e4a3f4fe6e27fd183a1c9ef6139b9f86ca67b802d2",
        expectedPcmFrames = 462_720,
        durationSeconds = 9.64,
        exportedPeakDbfs = -9.107135022704991,
        defaultClipGain = 0.84f,
        priority = 3,
        listeningLoopApprovedStandalone = true,
      ),
      PreparedAudioAssetSpec(
        id = NITRO_SUSTAIN_ID,
        fileName = "nitro-sustain.wav",
        format = AudioFormatKind.WAV_LOOP_PCM16,
        bus = AudioBus.GAMEPLAY_SFX,
        isLoop = true,
        expectedBytes = 1_187_624L,
        expectedSha256 = "fa2654d9d30f16d46b2ebbe69e556397c5b3ddbdca48bad5ff44308437779a96",
        expectedPcmFrames = 296_895,
        durationSeconds = 6.1853125,
        exportedPeakDbfs = -5.99994884152935,
        defaultClipGain = 0.64f,
        priority = 3,
        listeningLoopApprovedStandalone = true,
      ),
      PreparedAudioAssetSpec(
        id = ROAD_NOISE_ID,
        fileName = "road-noise.wav",
        format = AudioFormatKind.WAV_LOOP_PCM16,
        bus = AudioBus.AMBIENCE,
        isLoop = true,
        expectedBytes = 1_590_796L,
        expectedSha256 = "843fda9f3e623b573847b9f101977d2165d25bab2e1da00cdfc10004bc349e25",
        expectedPcmFrames = 397_688,
        durationSeconds = 8.285166666666667,
        exportedPeakDbfs = -10.059847135178885,
        defaultClipGain = 0.78f,
        priority = 2,
        listeningLoopApprovedStandalone = true,
      ),
      PreparedAudioAssetSpec(
        id = WIND_ID,
        fileName = "wind.wav",
        format = AudioFormatKind.WAV_LOOP_PCM16,
        bus = AudioBus.AMBIENCE,
        isLoop = true,
        expectedBytes = 1_729_964L,
        expectedSha256 = "b74563f2a6bbf763c203ac8ff0960e8a57fa8675a5c20ab8efb988741b041b3a",
        expectedPcmFrames = 432_480,
        durationSeconds = 9.01,
        exportedPeakDbfs = -13.93920225511009,
        defaultClipGain = 0.85f,
        priority = 2,
        listeningLoopApprovedStandalone = true,
      ),
      PreparedAudioAssetSpec(
        id = MUSIC_MENU_ID,
        fileName = "music-menu.wav",
        format = AudioFormatKind.WAV_LOOP_PCM16,
        bus = AudioBus.MUSIC,
        isLoop = true,
        expectedBytes = 17_192_028L,
        expectedSha256 = "2b10ef32d83593b39c5ed25f5256a2e4b4dfdd3891357435399022e28f39c60c",
        expectedPcmFrames = 4_297_996,
        durationSeconds = 89.54158333333334,
        exportedPeakDbfs = -12.395024160008246,
        defaultClipGain = 0.82f,
        priority = 2,
        listeningLoopApprovedStandalone = true,
      ),
      PreparedAudioAssetSpec(
        id = MUSIC_RACE_ID,
        fileName = "music-race.wav",
        format = AudioFormatKind.WAV_LOOP_PCM16,
        bus = AudioBus.MUSIC,
        isLoop = true,
        expectedBytes = 22_637_564L,
        expectedSha256 = "cef72c08140edf5efbb450f77dc6fda5cdd82fd62e1483d2126fa5b0e01784f5",
        expectedPcmFrames = 5_659_380,
        durationSeconds = 117.90375,
        exportedPeakDbfs = -11.096729546236181,
        defaultClipGain = 0.78f,
        priority = 2,
        listeningLoopApprovedStandalone = true,
      ),
      // 15 Prepared 48 kHz Stereo MP3 One-Shots
      PreparedAudioAssetSpec(
        id = BRAKE_SKID_ID,
        fileName = "brake-skid.mp3",
        format = AudioFormatKind.MP3_ONESHOT,
        bus = AudioBus.GAMEPLAY_SFX,
        isLoop = false,
        expectedBytes = 37_484L,
        expectedSha256 = "8f8e2d86130fd42ba9e166e9a5114aace4d2402a6b3ca104adf6246e042ef6da",
        expectedPcmFrames = 87_510,
        durationSeconds = 1.823125,
        exportedPeakDbfs = -6.39211220424872,
        defaultClipGain = 0.62f,
        minRetriggerIntervalMs = 650L,
        priority = 2,
      ),
      PreparedAudioAssetSpec(
        id = UI_CLICK_ID,
        fileName = "ui-click.mp3",
        format = AudioFormatKind.MP3_ONESHOT,
        bus = AudioBus.GAMEPLAY_SFX,
        isLoop = false,
        expectedBytes = 13_964L,
        expectedSha256 = "1a9ab3a33062963f61465445032dac75bff9b3afc7bbe9fc195ad141476fec1b",
        expectedPcmFrames = 30_769,
        durationSeconds = 0.6410208333333334,
        exportedPeakDbfs = -6.469365083187768,
        defaultClipGain = 0.55f,
        minRetriggerIntervalMs = 45L,
        priority = 1,
      ),
      PreparedAudioAssetSpec(
        id = COIN_PICKUP_ID,
        fileName = "coin-pickup.mp3",
        format = AudioFormatKind.MP3_ONESHOT,
        bus = AudioBus.GAMEPLAY_SFX,
        isLoop = false,
        expectedBytes = 34_604L,
        expectedSha256 = "b48891724aab02826a39279f95f73c907c695f8e7808f02e7e71f5e65f30f9bb",
        expectedPcmFrames = 80_218,
        durationSeconds = 1.6712083333333334,
        exportedPeakDbfs = -6.544495245088773,
        defaultClipGain = 0.72f,
        minRetriggerIntervalMs = 35L,
        priority = 2,
      ),
      PreparedAudioAssetSpec(
        id = COLLISION_IMPACT_ID,
        fileName = "collision-impact.mp3",
        format = AudioFormatKind.MP3_ONESHOT,
        bus = AudioBus.GAMEPLAY_SFX,
        isLoop = false,
        expectedBytes = 36_044L,
        expectedSha256 = "8e8374a859f0d507fcae23514c18fda29c287a6daef33638bdce8590c50bb82f",
        expectedPcmFrames = 83_147,
        durationSeconds = 1.7322291666666667,
        exportedPeakDbfs = -6.436490329336771,
        defaultClipGain = 0.85f,
        minRetriggerIntervalMs = 180L,
        priority = 3,
      ),
      PreparedAudioAssetSpec(
        id = COUNTDOWN_BEEP_ID,
        fileName = "countdown-beep.mp3",
        format = AudioFormatKind.MP3_ONESHOT,
        bus = AudioBus.GAMEPLAY_SFX,
        isLoop = false,
        expectedBytes = 14_924L,
        expectedSha256 = "0f8dcc6aeaa75277bdc053852d44c52bf49b77f8de1bf4449df4c59bf3cda300",
        expectedPcmFrames = 32_511,
        durationSeconds = 0.6773125,
        exportedPeakDbfs = -6.4548625669371855,
        defaultClipGain = 0.66f,
        minRetriggerIntervalMs = 200L,
        priority = 2,
      ),
      PreparedAudioAssetSpec(
        id = COUNTDOWN_GO_ID,
        fileName = "countdown-go.mp3",
        format = AudioFormatKind.MP3_ONESHOT,
        bus = AudioBus.GAMEPLAY_SFX,
        isLoop = false,
        expectedBytes = 18_764L,
        expectedSha256 = "cd0ba3751c09c894feee2a7882512573ea0f1cdafa58f2b523f3ee30bb821290",
        expectedPcmFrames = 42_234,
        durationSeconds = 0.879875,
        exportedPeakDbfs = -6.421487349550117,
        defaultClipGain = 0.74f,
        minRetriggerIntervalMs = 250L,
        priority = 2,
      ),
      PreparedAudioAssetSpec(
        id = DOUBLE_COINS_PICKUP_ID,
        fileName = "double-coins-pickup.mp3",
        format = AudioFormatKind.MP3_ONESHOT,
        bus = AudioBus.GAMEPLAY_SFX,
        isLoop = false,
        expectedBytes = 15_884L,
        expectedSha256 = "5cd3c36184096e041720e9251cdd4a9709c536e3f8a2862105f99cb973117ba0",
        expectedPcmFrames = 35_033,
        durationSeconds = 0.7298541666666667,
        exportedPeakDbfs = -6.248885204624694,
        defaultClipGain = 0.70f,
        minRetriggerIntervalMs = 120L,
        priority = 2,
      ),
      PreparedAudioAssetSpec(
        id = GAME_OVER_ID,
        fileName = "game-over.mp3",
        format = AudioFormatKind.MP3_ONESHOT,
        bus = AudioBus.GAMEPLAY_SFX,
        isLoop = false,
        expectedBytes = 35_564L,
        expectedSha256 = "4e3285abe4ec4c58a66b236d2999b1e278fa0b3f7a26f32a5af01d0814d7d652",
        expectedPcmFrames = 82_769,
        durationSeconds = 1.7243541666666666,
        exportedPeakDbfs = -6.398200768207998,
        defaultClipGain = 0.82f,
        minRetriggerIntervalMs = 500L,
        priority = 3,
      ),
      PreparedAudioAssetSpec(
        id = MAGNET_ACTIVATE_ID,
        fileName = "magnet-activate.mp3",
        format = AudioFormatKind.MP3_ONESHOT,
        bus = AudioBus.GAMEPLAY_SFX,
        isLoop = false,
        expectedBytes = 43_724L,
        expectedSha256 = "885b3bd58d9c9f3ca7d892512beeebe6f25a40189a08fa95990d3387e59e72f9",
        expectedPcmFrames = 102_166,
        durationSeconds = 2.128458333333333,
        exportedPeakDbfs = -6.478301759058685,
        defaultClipGain = 0.74f,
        minRetriggerIntervalMs = 150L,
        priority = 2,
      ),
      PreparedAudioAssetSpec(
        id = NITRO_ACTIVATE_ID,
        fileName = "nitro-activate.mp3",
        format = AudioFormatKind.MP3_ONESHOT,
        bus = AudioBus.GAMEPLAY_SFX,
        isLoop = false,
        expectedBytes = 65_804L,
        expectedSha256 = "251ff46ac85ad355d8eb148e9f613bf61c3c63994fbb272b3d8e0e64062170d0",
        expectedPcmFrames = 154_939,
        durationSeconds = 3.227895833333333,
        exportedPeakDbfs = -6.440384224657451,
        defaultClipGain = 0.76f,
        minRetriggerIntervalMs = 220L,
        priority = 3,
      ),
      PreparedAudioAssetSpec(
        id = PURCHASE_CONFIRM_ID,
        fileName = "purchase-confirm.mp3",
        format = AudioFormatKind.MP3_ONESHOT,
        bus = AudioBus.GAMEPLAY_SFX,
        isLoop = false,
        expectedBytes = 27_884L,
        expectedSha256 = "88b346c35f3e0c42a7a29a0bda69fdb868e079656dce474f06067e192f743bfc",
        expectedPcmFrames = 64_477,
        durationSeconds = 1.3432708333333334,
        exportedPeakDbfs = -6.266864471389637,
        defaultClipGain = 0.78f,
        minRetriggerIntervalMs = 150L,
        priority = 2,
      ),
      PreparedAudioAssetSpec(
        id = SHIELD_ACTIVATE_ID,
        fileName = "shield-activate.mp3",
        format = AudioFormatKind.MP3_ONESHOT,
        bus = AudioBus.GAMEPLAY_SFX,
        isLoop = false,
        expectedBytes = 49_484L,
        expectedSha256 = "3dcb8051e8725dba1c02b6e89e96ed2492e2871a58306577a681c2d60146e31f",
        expectedPcmFrames = 116_089,
        durationSeconds = 2.4185208333333335,
        exportedPeakDbfs = -6.1176261424755465,
        defaultClipGain = 0.72f,
        minRetriggerIntervalMs = 150L,
        priority = 2,
      ),
      PreparedAudioAssetSpec(
        id = SHIELD_BREAK_ID,
        fileName = "shield-break.mp3",
        format = AudioFormatKind.MP3_ONESHOT,
        bus = AudioBus.GAMEPLAY_SFX,
        isLoop = false,
        expectedBytes = 18_284L,
        expectedSha256 = "226a3392c051dafa4e437b37887d12e9b6342c0da97bffef6bcfaa192e18a838",
        expectedPcmFrames = 41_014,
        durationSeconds = 0.8544583333333333,
        exportedPeakDbfs = -6.296912866303314,
        defaultClipGain = 0.84f,
        minRetriggerIntervalMs = 150L,
        priority = 3,
      ),
      PreparedAudioAssetSpec(
        id = TRAFFIC_PASS_ID,
        fileName = "traffic-pass.mp3",
        format = AudioFormatKind.MP3_ONESHOT,
        bus = AudioBus.GAMEPLAY_SFX,
        isLoop = false,
        expectedBytes = 68_684L,
        expectedSha256 = "0194964abd2798f7fe83410f26c271b2f29e92d4883beee71bfcb07ae41dcd0e",
        expectedPcmFrames = 162_333,
        durationSeconds = 3.3819375,
        exportedPeakDbfs = -6.422597793125915,
        defaultClipGain = 0.62f,
        minRetriggerIntervalMs = 160L,
        priority = 2,
      ),
      PreparedAudioAssetSpec(
        id = ACHIEVEMENT_UNLOCK_ID,
        fileName = "achievement-unlock.mp3",
        format = AudioFormatKind.MP3_ONESHOT,
        bus = AudioBus.GAMEPLAY_SFX,
        isLoop = false,
        expectedBytes = 69_644L,
        expectedSha256 = "b566c399dacf68d40daeb76d4cd983853b822219c96d4bfc53291c18a6b95211",
        expectedPcmFrames = 164_100,
        durationSeconds = 3.41875,
        exportedPeakDbfs = -6.42037704792032,
        defaultClipGain = 0.78f,
        minRetriggerIntervalMs = 200L,
        priority = 2,
      ),
    )

  val LOOP_ASSETS: List<PreparedAudioAssetSpec> = ALL_AUDIO_ASSETS.filter { it.isLoop }
  val ONESHOT_ASSETS: List<PreparedAudioAssetSpec> = ALL_AUDIO_ASSETS.filter { !it.isLoop }

  fun findAsset(id: String): PreparedAudioAssetSpec? = ALL_AUDIO_ASSETS.firstOrNull { it.id == id }

  val FIVE_CAR_ENGINE_PROFILES: Map<String, CarEngineAudioProfile> =
    mapOf(
      HeroVehicleCatalog.STARTER_ID to
        CarEngineAudioProfile(
          vehicleId = HeroVehicleCatalog.STARTER_ID,
          profileName = "Refined Sports Coupe",
          characterSummary = "Balanced 6-speed coupe blend across idle/steady/high stems",
          gearCount = 6,
          idleRpm = 850.0,
          redlineRpm = 7_200.0,
          shiftDurationSeconds = 0.11,
          idleBasePitch = 0.96f,
          steadyBasePitch = 0.94f,
          highBasePitch = 0.92f,
          maxPitchMultiplier = 1.38f,
          idleStemWeightBias = 1.00f,
          steadyStemWeightBias = 1.05f,
          highStemWeightBias = 0.92f,
          loadSensitivity = 0.55f,
          nitroPitchBoost = 0.11f,
          harmonicOrder = 2.0f,
          subOctaveBlend = 0.15f,
          maxSynthCharacterGain = 0.05f,
          synthesizedLayerLabel = "Synthesized 2nd-harmonic coupe intake layer (paired with 3 original engine WAVs)",
        ),
      HeroVehicleCatalog.SUPER_RACING_ID to
        CarEngineAudioProfile(
          vehicleId = HeroVehicleCatalog.SUPER_RACING_ID,
          profileName = "High-Rev Track Racer",
          characterSummary = "Close-ratio 7-speed high-RPM screamer favoring engine-high stem",
          gearCount = 7,
          idleRpm = 1_050.0,
          redlineRpm = 8_800.0,
          shiftDurationSeconds = 0.065,
          idleBasePitch = 1.06f,
          steadyBasePitch = 1.05f,
          highBasePitch = 1.04f,
          maxPitchMultiplier = 1.62f,
          idleStemWeightBias = 0.78f,
          steadyStemWeightBias = 0.96f,
          highStemWeightBias = 1.24f,
          loadSensitivity = 0.72f,
          nitroPitchBoost = 0.15f,
          harmonicOrder = 3.0f,
          subOctaveBlend = 0.08f,
          maxSynthCharacterGain = 0.08f,
          synthesizedLayerLabel = "Synthesized 3rd-harmonic track gear-whine overtone (paired with 3 original engine WAVs)",
        ),
      HeroVehicleCatalog.MUSCLE_ID to
        CarEngineAudioProfile(
          vehicleId = HeroVehicleCatalog.MUSCLE_ID,
          profileName = "Deep V8 Muscle Shaker",
          characterSummary = "Tall 5-speed low-end torque rumble with sub-octave V8 lope",
          gearCount = 5,
          idleRpm = 720.0,
          redlineRpm = 6_100.0,
          shiftDurationSeconds = 0.145,
          idleBasePitch = 0.80f,
          steadyBasePitch = 0.78f,
          highBasePitch = 0.79f,
          maxPitchMultiplier = 1.18f,
          idleStemWeightBias = 1.28f,
          steadyStemWeightBias = 1.18f,
          highStemWeightBias = 0.74f,
          loadSensitivity = 0.62f,
          nitroPitchBoost = 0.09f,
          harmonicOrder = 1.5f,
          subOctaveBlend = 0.68f,
          maxSynthCharacterGain = 0.11f,
          synthesizedLayerLabel = "Synthesized low-frequency sub-octave V8 rumble layer (paired with 3 original engine WAVs)",
        ),
      HeroVehicleCatalog.HYPER_SPORTS_ID to
        CarEngineAudioProfile(
          vehicleId = HeroVehicleCatalog.HYPER_SPORTS_ID,
          profileName = "Responsive Twin-Turbo Exotic",
          characterSummary = "Fast-transient 7-speed DCT profile with load-reactive induction rise",
          gearCount = 7,
          idleRpm = 980.0,
          redlineRpm = 8_400.0,
          shiftDurationSeconds = 0.050,
          idleBasePitch = 1.00f,
          steadyBasePitch = 1.01f,
          highBasePitch = 1.00f,
          maxPitchMultiplier = 1.56f,
          idleStemWeightBias = 0.84f,
          steadyStemWeightBias = 1.04f,
          highStemWeightBias = 1.18f,
          loadSensitivity = 0.85f,
          nitroPitchBoost = 0.16f,
          harmonicOrder = 2.5f,
          subOctaveBlend = 0.22f,
          maxSynthCharacterGain = 0.09f,
          synthesizedLayerLabel = "Synthesized turbo-induction harmonic layer (paired with 3 original engine WAVs)",
        ),
      HeroVehicleCatalog.LEGENDARY_ID to
        CarEngineAudioProfile(
          vehicleId = HeroVehicleCatalog.LEGENDARY_ID,
          profileName = "Flagship V10/Widebody Exotic",
          characterSummary = "8-speed flagship exotic sweep with wide dynamic pitch & harmonic resonance",
          gearCount = 8,
          idleRpm = 1_100.0,
          redlineRpm = 9_200.0,
          shiftDurationSeconds = 0.045,
          idleBasePitch = 1.02f,
          steadyBasePitch = 1.06f,
          highBasePitch = 1.08f,
          maxPitchMultiplier = 1.70f,
          idleStemWeightBias = 0.80f,
          steadyStemWeightBias = 1.08f,
          highStemWeightBias = 1.30f,
          loadSensitivity = 0.90f,
          nitroPitchBoost = 0.18f,
          harmonicOrder = 2.75f,
          subOctaveBlend = 0.28f,
          maxSynthCharacterGain = 0.10f,
          synthesizedLayerLabel = "Synthesized multi-harmonic flagship resonance layer (paired with 3 original engine WAVs)",
        ),
    )

  fun profileForVehicle(vehicleId: String?): CarEngineAudioProfile =
    FIVE_CAR_ENGINE_PROFILES[vehicleId]
      ?: FIVE_CAR_ENGINE_PROFILES.getValue(HeroVehicleCatalog.STARTER_ID)
}
