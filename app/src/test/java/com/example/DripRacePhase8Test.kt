package com.example

import android.app.Application
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.example.assets.BestScoreRepository
import com.example.assets.HeroAssetRepository
import com.example.assets.SceneryAssetRepository
import com.example.assets.StarterAssetRepository
import com.example.assets.TrafficAssetRepository
import com.example.audio.AudioAssetRepository
import com.example.audio.AudioBus
import com.example.audio.AudioCatalog
import com.example.audio.AudioFocusStatus
import com.example.audio.AudioFormatKind
import com.example.audio.AudioSettingsState
import com.example.audio.DripRaceAudioManager
import com.example.audio.EngineAudioModel
import com.example.audio.HapticCueKind
import com.example.audio.HapticsBackend
import com.example.audio.MusicPlaybackMode
import com.example.audio.NativeAudioBackend
import com.example.audio.PreparedAudioAssetSpec
import com.example.economy.AtomicFileProgressionPersistence
import com.example.economy.EconomyService
import com.example.economy.PlayerProgression
import com.example.economy.SaveEnvelope
import com.example.garage.HeroVehicleCatalog
import com.example.garage.VehicleUpgradeLevels
import com.example.render.CoordinateMapper
import com.example.sim.ActiveObstacleSnapshot
import com.example.sim.CoinTier
import com.example.sim.DrivingInputSnapshot
import com.example.sim.DrivingSimulation
import com.example.sim.PickupCollectedEvent
import com.example.sim.PowerUpType
import com.example.sim.VehiclePhysicsConfig
import com.example.state.DripRaceViewModel
import com.example.state.GamePhase
import com.example.traffic.TrafficCatalog
import com.example.ui.DripRaceRootScreen
import com.example.ui.theme.DripRaceTheme
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DripRacePhase8Test {

  @get:Rule val composeTestRule = createComposeRule()

  private data class RecordedOneShot(
    val clipId: String,
    val leftGain: Float,
    val rightGain: Float,
    val priority: Int,
    val pitchRate: Float,
  )

  private data class RecordedMusicCall(
    val musicLoopId: String?,
    val playing: Boolean,
    val gain: Float,
  )

  private class RecordingAudioBackend : NativeAudioBackend {
    var initialized = false
    var released = false
    var focusRequestCount = 0
    var focusAbandonCount = 0
    var stopAllCount = 0
    var focusCallback: ((AudioFocusStatus) -> Unit)? = null

    val shortLoopStates = mutableMapOf<String, Triple<Boolean, Float, Float>>()
    val musicHistory = mutableListOf<RecordedMusicCall>()
    val oneShots = mutableListOf<RecordedOneShot>()
    var synthActive = false
    var synthFreqHz = 0f
    var synthGain = 0f

    override fun initialize(
      loopSpecs: List<PreparedAudioAssetSpec>,
      oneShotSpecs: List<PreparedAudioAssetSpec>,
      onFocusChanged: (AudioFocusStatus) -> Unit,
    ) {
      initialized = true
      released = false
      focusCallback = onFocusChanged
    }

    override fun requestPlaybackFocus(): AudioFocusStatus {
      focusRequestCount += 1
      return AudioFocusStatus.GRANTED
    }

    override fun abandonPlaybackFocus() {
      focusAbandonCount += 1
    }

    override fun setShortLoopState(
      loopId: String,
      active: Boolean,
      leftGain: Float,
      rightGain: Float,
      pitchRate: Float,
    ) {
      shortLoopStates[loopId] = Triple(active, leftGain, pitchRate)
    }

    override fun setEngineSynthCharacterState(
      active: Boolean,
      freqHz: Float,
      subOctaveBlend: Float,
      gain: Float,
    ) {
      synthActive = active
      synthFreqHz = freqHz
      synthGain = gain
    }

    override fun setMusicLoopState(
      musicLoopId: String?,
      playing: Boolean,
      gain: Float,
    ) {
      musicHistory += RecordedMusicCall(musicLoopId, playing, gain)
    }

    override fun playOneShot(
      clipId: String,
      leftGain: Float,
      rightGain: Float,
      priority: Int,
      pitchRate: Float,
    ): Int {
      oneShots += RecordedOneShot(clipId, leftGain, rightGain, priority, pitchRate)
      return oneShots.size
    }

    override fun stopAllActiveSounds() {
      stopAllCount += 1
      shortLoopStates.keys.toList().forEach { key ->
        shortLoopStates[key] = Triple(false, 0f, 1f)
      }
      synthActive = false
      synthGain = 0f
    }

    override fun release() {
      released = true
      stopAllActiveSounds()
    }
  }

  private class RecordingHapticsBackend(
    override val hasHardwareVibrator: Boolean,
  ) : HapticsBackend {
    val cues = mutableListOf<HapticCueKind>()

    override fun performHaptic(cue: HapticCueKind) {
      if (hasHardwareVibrator) {
        cues += cue
      }
    }
  }

  @Test
  fun preparedAudioPackageVerifiesAll23ManifestFilesByteSizesSha256HeadersAndHeadroom() {
    val context = ApplicationProvider.getApplicationContext<Application>()
    val repo = AudioAssetRepository(context)
    val report = repo.auditAudioPackage(scanWavHeadroom = true)

    assertEquals(23, AudioCatalog.ALL_AUDIO_ASSETS.size)
    assertEquals(8, AudioCatalog.LOOP_ASSETS.size)
    assertEquals(15, AudioCatalog.ONESHOT_ASSETS.size)
    assertTrue("Expected all 23 prepared audio files verified: ${report.summaryLine}", report.allVerified)
    assertEquals(23, report.verifiedCount)
    assertEquals(8, report.loopVerifiedCount)
    assertEquals(15, report.oneShotVerifiedCount)
    assertTrue(report.missingOrInvalidIds.isEmpty())

    // Verify every runtime asset path resolves strictly under audio/<filename>, never Mac sourceFile,
    // and offline preparation is flagged as already applied.
    for (entry in report.entries) {
      assertTrue(entry.present)
      assertTrue(entry.bytesMatch)
      assertTrue(entry.sha256Verified)
      assertTrue(entry.decodeHeaderVerified)
      assertEquals("audio/${entry.spec.fileName}", entry.spec.assetPath)
      assertFalse(entry.spec.assetPath.contains("/Users/"))
      assertTrue(entry.spec.preparationAlreadyApplied)
      if (entry.spec.format == AudioFormatKind.WAV_LOOP_PCM16) {
        assertEquals(entry.spec.expectedPcmFrames, entry.actualPcmFrames)
        assertEquals("WAV loop ${entry.spec.id} must have zero full-scale clipped samples", 0, entry.fullScaleSampleCount)
      }
    }

    // Cross-verify against references/audio/manifest.json on disk
    val manifestFile =
      listOf(
          File("../references/audio/manifest.json"),
          File("references/audio/manifest.json"),
        )
        .firstOrNull { it.exists() }
    assertNotNull("references/audio/manifest.json must exist in project workspace", manifestFile)
    val manifestRoot = JSONObject(manifestFile!!.readText(Charsets.UTF_8))
    val filesArray = manifestRoot.getJSONArray("files")
    assertEquals(23, filesArray.length())

    val auditedById = report.entries.associateBy { it.spec.id }
    for (i in 0 until filesArray.length()) {
      val obj = filesArray.getJSONObject(i)
      val id = obj.getString("id")
      val fileName = obj.getString("file")
      val expectedBytes = obj.getLong("bytes")
      val expectedSha256 = obj.getString("sha256")
      val isLoop = obj.getBoolean("loop")
      val sampleRate = obj.getInt("sampleRate")
      val channels = obj.getInt("channels")
      val busName = obj.getString("bus")

      val entry = auditedById[id]
      assertNotNull("Catalog/audit missing manifest id=$id", entry)
      assertEquals(fileName, entry!!.spec.fileName)
      assertEquals(expectedBytes, entry.actualBytes)
      assertEquals(expectedSha256, entry.actualSha256)
      assertEquals(isLoop, entry.spec.isLoop)
      assertEquals(48_000, sampleRate)
      assertEquals(2, channels)
      assertEquals(busName, entry.spec.bus.id)
    }
  }

  @Test
  fun fiveBusAudioManagerEnforcesHeadroomPersistenceAndSchemaV4ToV5MigrationWithoutWalletMutation() {
    val context = ApplicationProvider.getApplicationContext<Application>()
    val persistenceDir = File(context.filesDir, AtomicFileProgressionPersistence.DIR_NAME)
    persistenceDir.deleteRecursively()

    // 1. Verify lossless SaveEnvelope v4 -> v5 migration preserves wallet, bestScore, cars & quality preset
    val v4Payload =
      PlayerProgression(
        confirmedWalletBalance = 2_450L,
        bestScore = 1_890,
        ownedVehicleIds = listOf(HeroVehicleCatalog.STARTER_ID, HeroVehicleCatalog.MUSCLE_ID),
        selectedVehicleId = HeroVehicleCatalog.MUSCLE_ID,
        vehicleUpgrades = mapOf(HeroVehicleCatalog.MUSCLE_ID to VehicleUpgradeLevels(engine = 2, handling = 1)),
        graphicsQualityPresetId = "medium",
      )
    val v4Envelope =
      SaveEnvelope(
        schemaVersion = SaveEnvelope.SCHEMA_VERSION_V4,
        revision = 7L,
        payload = v4Payload,
      )
    val v4Json = SaveEnvelope.toJsonString(v4Envelope)
    persistenceDir.mkdirs()
    File(persistenceDir, AtomicFileProgressionPersistence.PRIMARY_FILE_NAME).writeText(v4Json, Charsets.UTF_8)

    val persistence = AtomicFileProgressionPersistence(context)
    val economy = EconomyService(persistence = persistence, initialTimeZoneSupplier = { "UTC" })
    val migratedSnap = economy.currentSnapshot()
    assertEquals(2_450L, migratedSnap.confirmedBalance)
    assertEquals(1_890, migratedSnap.bestScore)
    assertEquals(HeroVehicleCatalog.MUSCLE_ID, migratedSnap.selectedVehicleId)
    assertEquals("medium", migratedSnap.graphicsQualityPresetId)
    assertEquals(AudioSettingsState(), migratedSnap.audioSettings)

    // 2. Verify 5 buses, headroom ceiling < 0 dBFS, and zero wallet/score mutation across audio updates
    var fakeClockMs = 10_000L
    val backend = RecordingAudioBackend()
    val haptics = RecordingHapticsBackend(hasHardwareVibrator = true)
    val manager =
      DripRaceAudioManager(
        assetRepository = AudioAssetRepository(context),
        backend = backend,
        haptics = haptics,
        wallClockMs = { fakeClockMs },
      )
    manager.initialize(migratedSnap.audioSettings)
    manager.onPhaseOrVehicleChanged(GamePhase.Running, HeroVehicleCatalog.MUSCLE_ID, raceId = "race_headroom")

    val sim = DrivingSimulation(enableTraffic = false, enablePickups = false)
    val muscleSpec = HeroVehicleCatalog.findById(HeroVehicleCatalog.MUSCLE_ID)!!
    val muscleConfig = muscleSpec.computeEffectivePhysics()
    sim.configureActiveVehicle(muscleConfig, resetState = true)
    repeat(90) {
      sim.stepFixedTick(DrivingInputSnapshot(nitroHeld = true))
    }
    val runningSnap = manager.onSimulationTick(sim.state, muscleConfig)
    assertEquals(5, runningSnap.busEffectiveGains.size)
    assertTrue(runningSnap.busEffectiveGains.keys.containsAll(AudioBus.entries))
    assertTrue(
      "Combined peak (${runningSnap.estimatedCombinedPeakLinear}) must remain <= ${AudioCatalog.MIX_HEADROOM_CEILING_LINEAR}",
      runningSnap.estimatedCombinedPeakLinear <= AudioCatalog.MIX_HEADROOM_CEILING_LINEAR + 1e-4f,
    )
    assertTrue("Headroom margin (${runningSnap.headroomMarginDb} dB) must remain >= 1.0 dB below 0 dBFS", runningSnap.headroomMarginDb >= 1.0f)

    // Disable Music only -> MUSIC bus drops to 0 while ENGINE/SFX/AMBIENCE stay active
    val customSettings =
      AudioSettingsState(
        masterVolume = 0.70f,
        musicEnabled = false,
        musicVolume = 0.50f,
        sfxEnabled = true,
        sfxVolume = 0.60f,
        muted = false,
        hapticsEnabled = false,
        reducedMotion = true,
      )
    economy.updateAudioSettings(customSettings)
    val afterMusicOff = manager.updateSettings(economy.currentSnapshot().audioSettings)
    assertEquals(0f, afterMusicOff.busEffectiveGains.getValue(AudioBus.MUSIC), 1e-5f)
    assertTrue(afterMusicOff.busEffectiveGains.getValue(AudioBus.ENGINE) > 0.05f)
    assertTrue(afterMusicOff.busEffectiveGains.getValue(AudioBus.GAMEPLAY_SFX) > 0.05f)
    assertTrue(afterMusicOff.busEffectiveGains.getValue(AudioBus.AMBIENCE) > 0.05f)

    // Mute All -> all 5 buses drop strictly to 0
    economy.updateAudioSettings(customSettings.copy(muted = true))
    val mutedSnap = manager.updateSettings(economy.currentSnapshot().audioSettings)
    AudioBus.entries.forEach { bus ->
      assertEquals(0f, mutedSnap.busEffectiveGains.getValue(bus), 1e-6f)
    }

    // Reload from disk -> settings persist under schema v5 and wallet/bestScore are untouched
    val reloadedEconomy = EconomyService(persistence = AtomicFileProgressionPersistence(context), initialTimeZoneSupplier = { "UTC" })
    val reloadedSnap = reloadedEconomy.currentSnapshot()
    assertEquals(2_450L, reloadedSnap.confirmedBalance)
    assertEquals(1_890, reloadedSnap.bestScore)
    assertTrue(reloadedSnap.audioSettings.muted)
    assertTrue(reloadedSnap.audioSettings.reducedMotion)
    assertFalse(reloadedSnap.audioSettings.musicEnabled)
    assertFalse(reloadedSnap.audioSettings.hapticsEnabled)
    assertEquals(0.70f, reloadedSnap.audioSettings.masterVolume, 1e-3f)
    manager.release()
  }

  @Test
  fun fiveCarEngineProfilesDifferentiateTimbrePitchAndGearsWhilePreservingExactPhysicsInvariance() {
    val allCarIds = HeroVehicleCatalog.ALL_CARS.map { it.id }
    assertEquals(5, allCarIds.size)

    val steadyFramesAt42Mps = mutableMapOf<String, com.example.audio.EngineAudioFrameState>()
    for (carSpec in HeroVehicleCatalog.ALL_CARS) {
      val profile = AudioCatalog.profileForVehicle(carSpec.id)
      assertEquals(carSpec.id, profile.vehicleId)
      assertTrue(
        "Synthesized layer for ${carSpec.id} must be honestly labeled",
        profile.synthesizedLayerLabel.contains("Synthesized", ignoreCase = true) &&
          profile.synthesizedLayerLabel.contains("3 original engine WAVs", ignoreCase = true),
      )

      val model = EngineAudioModel()
      val cfg = carSpec.computeEffectivePhysics()
      val nearRedlineSpeedMps = cfg.topSpeedMps * 0.96
      var frame = model.stepTelemetry(carSpec.id, 0.0, cfg.topSpeedMps, cfg.boostedTopSpeedMps, false, false, 1.0 / 60.0)
      repeat(30) {
        frame = model.stepTelemetry(carSpec.id, nearRedlineSpeedMps, cfg.topSpeedMps, cfg.boostedTopSpeedMps, false, false, 1.0 / 60.0)
      }
      steadyFramesAt42Mps[carSpec.id] = frame
    }

    val starter = steadyFramesAt42Mps.getValue(HeroVehicleCatalog.STARTER_ID)
    val superRacing = steadyFramesAt42Mps.getValue(HeroVehicleCatalog.SUPER_RACING_ID)
    val muscle = steadyFramesAt42Mps.getValue(HeroVehicleCatalog.MUSCLE_ID)
    val hyper = steadyFramesAt42Mps.getValue(HeroVehicleCatalog.HYPER_SPORTS_ID)
    val legendary = steadyFramesAt42Mps.getValue(HeroVehicleCatalog.LEGENDARY_ID)

    // Gear counts & acoustic ordering match vehicle personalities
    assertEquals(6, starter.gearCount)
    assertEquals(7, superRacing.gearCount)
    assertEquals(5, muscle.gearCount)
    assertEquals(7, hyper.gearCount)
    assertEquals(8, legendary.gearCount)

    // Muscle car has deeper pitch and stronger sub-octave blend than Starter, Super Racing & Legendary
    assertTrue(muscle.highPitch < starter.highPitch)
    assertTrue(starter.highPitch < superRacing.highPitch)
    assertTrue(superRacing.highPitch < legendary.highPitch)
    assertTrue(muscle.synthSubOctaveBlend > superRacing.synthSubOctaveBlend)
    assertNotEquals(hyper.profileName, superRacing.profileName)
    assertNotEquals(legendary.synthCharacterFreqHz, starter.synthCharacterFreqHz)

    // Nitro increases pitch & activates nitroSustainGain; braking lowers load & pitch
    val nitroModel = EngineAudioModel()
    val legCfg = HeroVehicleCatalog.findById(HeroVehicleCatalog.LEGENDARY_ID)!!.computeEffectivePhysics()
    var cruiseFrame = nitroModel.stepTelemetry(legCfg.id, 50.0, legCfg.topSpeedMps, legCfg.boostedTopSpeedMps, false, false, 1.0 / 60.0)
    repeat(20) {
      cruiseFrame = nitroModel.stepTelemetry(legCfg.id, 50.0, legCfg.topSpeedMps, legCfg.boostedTopSpeedMps, false, false, 1.0 / 60.0)
    }
    val nitroFrame = nitroModel.stepTelemetry(legCfg.id, 54.0, legCfg.topSpeedMps, legCfg.boostedTopSpeedMps, false, true, 1.0 / 60.0)
    assertTrue(nitroFrame.highPitch > cruiseFrame.highPitch)
    assertEquals(1.0f, nitroFrame.nitroSustainGain, 1e-5f)

    val brakeFrame = nitroModel.stepTelemetry(legCfg.id, 46.0, legCfg.topSpeedMps, legCfg.boostedTopSpeedMps, true, false, 1.0 / 60.0)
    assertTrue(brakeFrame.highPitch < nitroFrame.highPitch)

    // Strict physics invariance proof: audio shifts and telemetry stepping NEVER alter simulation physics
    val context = ApplicationProvider.getApplicationContext<Application>()
    val manager =
      DripRaceAudioManager(
        assetRepository = AudioAssetRepository(context),
        backend = RecordingAudioBackend(),
        haptics = RecordingHapticsBackend(hasHardwareVibrator = false),
      )
    manager.initialize()
    manager.onPhaseOrVehicleChanged(GamePhase.Running, legCfg.id, raceId = "physics_invariance")

    val simWithAudio = DrivingSimulation(enableTraffic = false, enablePickups = false)
    val simWithoutAudio = DrivingSimulation(enableTraffic = false, enablePickups = false)
    simWithAudio.configureActiveVehicle(legCfg, resetState = true)
    simWithoutAudio.configureActiveVehicle(legCfg, resetState = true)

    var sawAudioShift = false
    for (tick in 0 until 360) {
      if (tick == 60) {
        simWithAudio.requestLaneChange(1)
        simWithoutAudio.requestLaneChange(1)
      }
      val input = DrivingInputSnapshot(brakeHeld = tick in 220..245, nitroHeld = tick in 120..180)
      simWithAudio.stepFixedTick(input)
      simWithoutAudio.stepFixedTick(input)
      val stateA = simWithAudio.state
      val stateB = simWithoutAudio.state
      val snap = manager.onSimulationTick(stateA, legCfg)
      if (snap.engineFrame?.isShiftingAudioOnly == true) {
        sawAudioShift = true
      }
      assertEquals(stateB.speedMetersPerSecond, stateA.speedMetersPerSecond, 0.0)
      assertEquals(stateB.distanceMeters, stateA.distanceMeters, 0.0)
      assertEquals(stateB.carX, stateA.carX, 0.0f)
      assertEquals(stateB.score, stateA.score)
    }
    assertTrue("Expected at least one virtual audio-only gear upshift during acceleration sweep", sawAudioShift)
    manager.release()
  }

  @Test
  fun authoritativeEventsSpatialTrafficPassByAndDeduplicationTriggerExactOnceCues() {
    val context = ApplicationProvider.getApplicationContext<Application>()
    var fakeClockMs = 50_000L
    val backend = RecordingAudioBackend()
    val haptics = RecordingHapticsBackend(hasHardwareVibrator = true)
    val manager =
      DripRaceAudioManager(
        assetRepository = AudioAssetRepository(context),
        backend = backend,
        haptics = haptics,
        wallClockMs = { fakeClockMs },
      )
    manager.initialize()
    val starterCfg = HeroVehicleCatalog.STARTER_SPEC.computeEffectivePhysics()
    manager.onPhaseOrVehicleChanged(GamePhase.Running, starterCfg.id, raceId = "race_events_1")

    val sim = DrivingSimulation(enableTraffic = false, enablePickups = false)
    sim.configureActiveVehicle(starterCfg, resetState = true)

    // 1. Race launch 3-2-1-GO! cadence driven by simulation seconds
    val s3 = manager.onSimulationTick(sim.state.copy(totalSimSeconds = 0.10), starterCfg)
    assertEquals("3", s3.launchBannerText)
    val s2 = manager.onSimulationTick(sim.state.copy(totalSimSeconds = 0.65), starterCfg)
    assertEquals("2", s2.launchBannerText)
    val s1 = manager.onSimulationTick(sim.state.copy(totalSimSeconds = 1.25), starterCfg)
    assertEquals("1", s1.launchBannerText)
    val sGo = manager.onSimulationTick(sim.state.copy(totalSimSeconds = 1.80), starterCfg)
    assertEquals("GO!", sGo.launchBannerText)
    val sClear = manager.onSimulationTick(sim.state.copy(totalSimSeconds = 2.60), starterCfg)
    assertEquals("", sClear.launchBannerText)

    assertEquals(3, backend.oneShots.count { it.clipId == AudioCatalog.COUNTDOWN_BEEP_ID })
    assertEquals(1, backend.oneShots.count { it.clipId == AudioCatalog.COUNTDOWN_GO_ID })

    // 2. Authoritative pickups & exact-once operationId deduplication
    val coinEvent =
      PickupCollectedEvent.CoinCollected(
        logicalId = 1L,
        operationId = "coin_op_1",
        coinTier = CoinTier.RARE,
        baseValue = 50,
        multiplierApplied = 1,
        awardedAmount = 50,
        collectedViaMagnet = false,
      )
    manager.onPickupCollected(coinEvent)
    manager.onPickupCollected(coinEvent) // duplicate operationId -> ignored!
    assertEquals(1, backend.oneShots.count { it.clipId == AudioCatalog.COIN_PICKUP_ID })

    manager.onPickupCollected(PickupCollectedEvent.PowerUpCollected(2L, "pu_2x", PowerUpType.DOUBLE_COINS))
    manager.onPickupCollected(PickupCollectedEvent.PowerUpCollected(3L, "pu_mag", PowerUpType.MAGNET))
    manager.onPickupCollected(PickupCollectedEvent.PowerUpCollected(4L, "pu_shd", PowerUpType.SHIELD))
    assertEquals(1, backend.oneShots.count { it.clipId == AudioCatalog.DOUBLE_COINS_PICKUP_ID })
    assertEquals(1, backend.oneShots.count { it.clipId == AudioCatalog.MAGNET_ACTIVATE_ID })
    assertEquals(1, backend.oneShots.count { it.clipId == AudioCatalog.SHIELD_ACTIVATE_ID })

    // 3. Nitro activate + sustain, brake skid, shield break, and collision impact
    fakeClockMs += 1_000L
    manager.onSimulationTick(
      sim.state.copy(totalSimSeconds = 3.0, speedMetersPerSecond = 38.0, isNitroActive = true),
      starterCfg,
    )
    assertEquals(1, backend.oneShots.count { it.clipId == AudioCatalog.NITRO_ACTIVATE_ID })
    assertTrue(backend.shortLoopStates[AudioCatalog.NITRO_SUSTAIN_ID]?.first == true)

    fakeClockMs += 1_000L
    manager.onSimulationTick(
      sim.state.copy(
        totalSimSeconds = 4.0,
        speedMetersPerSecond = 30.0,
        isNitroActive = false,
        isBraking = true,
        shieldBlocksCount = 1,
        hearts = 2,
      ),
      starterCfg,
    )
    assertEquals(1, backend.oneShots.count { it.clipId == AudioCatalog.BRAKE_SKID_ID })
    assertEquals(1, backend.oneShots.count { it.clipId == AudioCatalog.SHIELD_BREAK_ID })
    assertEquals(1, backend.oneShots.count { it.clipId == AudioCatalog.COLLISION_IMPACT_ID })

    // 4. Spatial stereo traffic pass-bys (left lane vs right lane relative to center player X=0)
    val leftObstacle =
      ActiveObstacleSnapshot(
        slotIndex = 0,
        logicalId = 501L,
        archetypeId = TrafficCatalog.APPROVED_TRAFFIC_VEHICLES[1].id,
        isStaticHazard = false,
        trafficSpec = TrafficCatalog.APPROVED_TRAFFIC_VEHICLES[1],
        hazardKind = null,
        laneIndex = 0,
        x = -3.6f,
        widthMeters = 1.85f,
        heightMeters = 1.45f,
        lengthMeters = 4.5f,
        relativeZ = 0.0,
        speedMetersPerSecond = 18.0,
        wheelRollRadians = 0.0,
      )
    fakeClockMs += 1_000L
    manager.onSimulationTick(
      sim.state.copy(
        totalSimSeconds = 5.0,
        carX = 0f,
        speedMetersPerSecond = 40.0,
        hearts = 2,
        shieldBlocksCount = 1,
        activeObstacles = listOf(leftObstacle),
      ),
      starterCfg,
    )
    val leftPassShot = backend.oneShots.last { it.clipId == AudioCatalog.TRAFFIC_PASS_ID }
    assertTrue("Left-lane pass-by must pan louder to left channel", leftPassShot.leftGain > leftPassShot.rightGain)

    // Same obstacle logicalId 501L on next tick must NOT re-fire traffic-pass
    fakeClockMs += 500L
    manager.onSimulationTick(
      sim.state.copy(
        totalSimSeconds = 5.1,
        carX = 0f,
        speedMetersPerSecond = 40.0,
        hearts = 2,
        shieldBlocksCount = 1,
        activeObstacles = listOf(leftObstacle.copy(relativeZ = -2.0)),
      ),
      starterCfg,
    )
    assertEquals(1, backend.oneShots.count { it.clipId == AudioCatalog.TRAFFIC_PASS_ID })

    // Right-lane obstacle 502L pans louder to right channel
    fakeClockMs += 500L
    val rightObstacle = leftObstacle.copy(logicalId = 502L, laneIndex = 2, x = 3.6f)
    manager.onSimulationTick(
      sim.state.copy(
        totalSimSeconds = 5.5,
        carX = 0f,
        speedMetersPerSecond = 40.0,
        hearts = 2,
        shieldBlocksCount = 1,
        activeObstacles = listOf(rightObstacle),
      ),
      starterCfg,
    )
    val rightPassShot = backend.oneShots.last { it.clipId == AudioCatalog.TRAFFIC_PASS_ID }
    assertTrue("Right-lane pass-by must pan louder to right channel", rightPassShot.rightGain > rightPassShot.leftGain)

    // 5. Purchase, Achievement/Mission claim, UI click, and GameOver exact-once
    fakeClockMs += 1_000L
    manager.onPurchaseConfirmed("buy_op_1")
    manager.onPurchaseConfirmed("buy_op_1")
    assertEquals(1, backend.oneShots.count { it.clipId == AudioCatalog.PURCHASE_CONFIRM_ID })

    manager.onProgressionUnlockedOrClaimed("claim_op_1")
    manager.onProgressionUnlockedOrClaimed("claim_op_1")
    assertEquals(1, backend.oneShots.count { it.clipId == AudioCatalog.ACHIEVEMENT_UNLOCK_ID })

    manager.playUiClick(withHapticTick = true)
    assertEquals(1, backend.oneShots.count { it.clipId == AudioCatalog.UI_CLICK_ID })

    val gameOverPhase =
      GamePhase.GameOver(
        finalScore = 400,
        bestScore = 400,
        isNewBest = true,
        distanceMeters = 500.0,
        validUniqueOvertakes = 0,
        validUniqueNearMisses = 0,
        stageLabel = "Stage 1",
        shieldBlocksCount = 0,
      )
    manager.onPhaseOrVehicleChanged(gameOverPhase, starterCfg.id)
    manager.onPhaseOrVehicleChanged(gameOverPhase, starterCfg.id)
    assertEquals(1, backend.oneShots.count { it.clipId == AudioCatalog.GAME_OVER_ID })
    assertTrue(haptics.cues.isNotEmpty())
    manager.release()
  }

  @Test
  fun musicStateMachineBoundedVoicesAudioFocusLifecycleAndReleaseAreSafe() {
    val context = ApplicationProvider.getApplicationContext<Application>()
    var fakeClockMs = 100_000L
    val backend = RecordingAudioBackend()
    val haptics = RecordingHapticsBackend(hasHardwareVibrator = false)
    val manager =
      DripRaceAudioManager(
        assetRepository = AudioAssetRepository(context),
        backend = backend,
        haptics = haptics,
        wallClockMs = { fakeClockMs },
      )
    manager.initialize()

    // Home -> Garage keeps single coherent music-menu loop
    manager.onPhaseOrVehicleChanged(GamePhase.Home, HeroVehicleCatalog.STARTER_ID)
    assertEquals(MusicPlaybackMode.MENU_LOOP, manager.currentSnapshot().musicMode)
    assertEquals(AudioCatalog.MUSIC_MENU_ID, manager.currentSnapshot().activeMusicTrackId)

    manager.onPhaseOrVehicleChanged(GamePhase.Garage, HeroVehicleCatalog.MUSCLE_ID)
    assertEquals(MusicPlaybackMode.MENU_LOOP, manager.currentSnapshot().musicMode)
    assertEquals(AudioCatalog.MUSIC_MENU_ID, manager.currentSnapshot().activeMusicTrackId)
    assertTrue(manager.currentSnapshot().activeLoopIds.none { it.startsWith("engine-") })

    // Running -> switches to music-race + driving loops
    val cfg = HeroVehicleCatalog.STARTER_SPEC.computeEffectivePhysics()
    val sim = DrivingSimulation(enableTraffic = false, enablePickups = false)
    sim.stepFixedTick(DrivingInputSnapshot())
    manager.onPhaseOrVehicleChanged(GamePhase.Running, cfg.id, raceId = "race_music_1")
    manager.onSimulationTick(sim.state.copy(speedMetersPerSecond = 35.0, totalSimSeconds = 3.0), cfg)
    val runSnap = manager.currentSnapshot()
    assertEquals(MusicPlaybackMode.RACE_LOOP, runSnap.musicMode)
    assertEquals(AudioCatalog.MUSIC_RACE_ID, runSnap.activeMusicTrackId)
    assertEquals(1, runSnap.activeLoopIds.count { it.startsWith("music-") })
    assertTrue(runSnap.activeLoopIds.contains(AudioCatalog.ENGINE_STEADY_ID))

    // Burst 20 distinct one-shots -> bounded voice pool never exceeds 8 concurrent voices
    for (i in 1..20) {
      fakeClockMs += 60L
      manager.onPickupCollected(
        PickupCollectedEvent.CoinCollected(
          logicalId = i.toLong(),
          operationId = "burst_coin_$i",
          coinTier = CoinTier.REGULAR,
          baseValue = 10,
          multiplierApplied = 1,
          awardedAmount = 10,
          collectedViaMagnet = false,
        )
      )
      assertTrue(
        "Active one-shot voices must never exceed ${AudioCatalog.MAX_CONCURRENT_ONESHOT_VOICES}",
        manager.currentSnapshot().activeOneShotVoices <= AudioCatalog.MAX_CONCURRENT_ONESHOT_VOICES,
      )
    }

    // Paused -> driving stems silenced immediately & music suspended cleanly
    manager.onPhaseOrVehicleChanged(GamePhase.Paused, cfg.id)
    val pausedSnap = manager.currentSnapshot()
    assertEquals(MusicPlaybackMode.RACE_PAUSED_SUSPENDED, pausedSnap.musicMode)
    assertTrue(pausedSnap.activeLoopIds.isEmpty())

    // Resume Running -> AudioFocus ducking & transient loss
    manager.onPhaseOrVehicleChanged(GamePhase.Running, cfg.id, raceId = "race_music_1")
    val normalMaster = manager.currentSnapshot().busEffectiveGains.getValue(AudioBus.MASTER)
    manager.onAudioFocusChanged(AudioFocusStatus.TRANSIENT_DUCK)
    val duckedMaster = manager.currentSnapshot().busEffectiveGains.getValue(AudioBus.MASTER)
    assertTrue("TRANSIENT_DUCK must attenuate master bus", duckedMaster < normalMaster * 0.5f)

    manager.onAudioFocusChanged(AudioFocusStatus.TRANSIENT_LOSS)
    assertEquals(0f, manager.currentSnapshot().busEffectiveGains.getValue(AudioBus.MASTER), 1e-6f)

    manager.onAudioFocusChanged(AudioFocusStatus.GRANTED)
    assertEquals(normalMaster, manager.currentSnapshot().busEffectiveGains.getValue(AudioBus.MASTER), 1e-4f)

    // Lifecycle backgrounding suspends audio & abandons focus; foregrounding restores cleanly
    manager.onLifecycleActiveChanged(false)
    assertTrue(manager.currentSnapshot().isBackgroundSuspended)
    assertEquals(0f, manager.currentSnapshot().busEffectiveGains.getValue(AudioBus.MASTER), 1e-6f)
    assertTrue(backend.focusAbandonCount >= 1)

    manager.onLifecycleActiveChanged(true)
    assertFalse(manager.currentSnapshot().isBackgroundSuspended)

    // Release cleans up backend and prevents any post-release playback
    manager.release()
    assertTrue(backend.released)
    val countBeforePostRelease = backend.oneShots.size
    manager.playUiClick()
    assertEquals(countBeforePostRelease, backend.oneShots.size)
  }

  @Test
  fun multiTouchSimultaneousPointersSafeInputReleaseAndReducedMotionCameraDampening() {
    // 1. Verify Reduced Motion camera dampening eliminates impact shake & caps Nitro FOV surge
    val fullMotionPose =
      CoordinateMapper.computeChaseCameraPose(
        widthPx = 1080,
        heightPx = 2400,
        cameraFollowX = 2.4f,
        carX = 3.6f,
        speedNormalized = 1.0f,
        isNitroActive = true,
        isBraking = false,
        cameraShakeSeconds = 0.24,
        reducedMotion = false,
      )
    val reducedMotionPose =
      CoordinateMapper.computeChaseCameraPose(
        widthPx = 1080,
        heightPx = 2400,
        cameraFollowX = 2.4f,
        carX = 3.6f,
        speedNormalized = 1.0f,
        isNitroActive = true,
        isBraking = false,
        cameraShakeSeconds = 0.24,
        reducedMotion = true,
      )
    val reducedMotionZeroShakePose =
      CoordinateMapper.computeChaseCameraPose(
        widthPx = 1080,
        heightPx = 2400,
        cameraFollowX = 2.4f,
        carX = 3.6f,
        speedNormalized = 1.0f,
        isNitroActive = true,
        isBraking = false,
        cameraShakeSeconds = 0.0,
        reducedMotion = true,
      )
    assertEquals("Reduced motion must suppress impact camera shake X completely", reducedMotionZeroShakePose.eyeX, reducedMotionPose.eyeX, 1e-9)
    assertEquals("Reduced motion must suppress impact camera shake Y completely", reducedMotionZeroShakePose.eyeY, reducedMotionPose.eyeY, 1e-9)
    assertTrue("Reduced motion must dampen high-speed + Nitro FOV surge", reducedMotionPose.verticalFovDegrees < fullMotionPose.verticalFovDegrees - 4.0)

    // 2. Verify simultaneous multi-touch pointer tracking (2 Brake pointers + 1 Nitro pointer + steer)
    val context = ApplicationProvider.getApplicationContext<Application>()
    val fakeRenderer = DripRacePhase1Test.FakeRendererPort()
    val vm = DripRaceViewModel(context, SavedStateHandle())
    vm.overrideDependenciesForTest(
      repository = StarterAssetRepository(context),
      renderer = fakeRenderer,
    )
    vm.onPlayClicked()
    assertTrue(vm.uiState.value.phase is GamePhase.Running)

    vm.onBrakePointerDown(101L)
    vm.onBrakePointerDown(102L)
    vm.onNitroPointerDown(201L)
    vm.onSteerRight()
    assertTrue(fakeRenderer.lastInput.brakeHeld)
    assertTrue(fakeRenderer.lastInput.nitroHeld)
    assertEquals(2, vm.simulation.state.targetLaneIndex)

    // Releasing first brake pointer keeps brake held while second finger (102L) remains pressed
    vm.onBrakePointerUpOrCancel(101L)
    assertTrue(fakeRenderer.lastInput.brakeHeld)
    assertTrue(fakeRenderer.lastInput.nitroHeld)

    // Releasing second brake pointer clears brake without dropping active Nitro finger (201L)
    vm.onBrakePointerUpOrCancel(102L)
    assertFalse(fakeRenderer.lastInput.brakeHeld)
    assertTrue(fakeRenderer.lastInput.nitroHeld)

    // Pause clears all stuck pointers safely and requires deliberate Resume
    vm.onPauseClicked()
    assertEquals(GamePhase.Paused, vm.uiState.value.phase)
    assertFalse(fakeRenderer.lastInput.brakeHeld)
    assertFalse(fakeRenderer.lastInput.nitroHeld)
  }

  @Test
  fun composePart8V2HudExactBrandingQuickMuteAndPausedSettingsPreservePausedPhase() {
    val context = ApplicationProvider.getApplicationContext<Application>()
    val persistenceDir = File(context.filesDir, AtomicFileProgressionPersistence.DIR_NAME)
    persistenceDir.deleteRecursively()

    val economy =
      EconomyService(
        persistence = AtomicFileProgressionPersistence(context),
        initialTimeZoneSupplier = { "UTC" },
      )
    val backend = RecordingAudioBackend()
    val haptics = RecordingHapticsBackend(hasHardwareVibrator = false)
    val audioManager =
      DripRaceAudioManager(
        assetRepository = AudioAssetRepository(context),
        backend = backend,
        haptics = haptics,
      )

    val vm = DripRaceViewModel(context, SavedStateHandle())
    vm.overrideDependenciesForTest(
      repository = StarterAssetRepository(context),
      renderer = DripRacePhase1Test.FakeRendererPort(),
      trafficRepository = TrafficAssetRepository(context),
      scoreRepository = BestScoreRepository(context),
      customEconomyService = economy,
      heroRepository = HeroAssetRepository(context),
      sceneryRepository = SceneryAssetRepository(context),
      customAudioManager = audioManager,
    )

    composeTestRule.setContent {
      val uiState by vm.uiState.collectAsState()
      val telemetry by vm.hudTelemetry.collectAsState()
      DripRaceTheme {
        DripRaceRootScreen(
          uiState = uiState,
          telemetry = telemetry,
          onPlay = vm::onPlayClicked,
          onPlayAgain = vm::onPlayAgainClicked,
          onPause = vm::onPauseClicked,
          onResume = vm::onResumeClicked,
          onRestart = vm::onRestartClicked,
          onHome = vm::onHomeClicked,
          onRetry = vm::onRetryClicked,
          onPickGlbPreview = {},
          onOpenGarage = vm::onOpenGarageClicked,
          onExitGarageToHome = vm::onExitGarageToHomeClicked,
          onOpenProgressionTab = vm::onOpenProgressionTab,
          onSelectProgressionTab = vm::onSelectProgressionTab,
          onCloseProgressionOverlay = vm::onCloseProgressionOverlay,
          onClaimDailyMission = vm::onClaimDailyMission,
          onClaimAchievement = vm::onClaimAchievement,
          onClaimDailyLogin = vm::onClaimDailyLoginReward,
          onSelectEnvironment = vm::onSelectEnvironment,
          onSelectGraphicsQuality = vm::onSelectGraphicsQualityPreset,
          onUpdateAudioSettings = vm::onUpdateAudioSettings,
          onToggleQuickMute = vm::onToggleQuickMute,
          onSyncCalendarClock = vm::onSyncCalendarClock,
          onBackFromRunning = { vm.onBackPressed() },
          onSteerLeft = vm::onSteerLeft,
          onSteerRight = vm::onSteerRight,
          onBrakePointerDown = vm::onBrakePointerDown,
          onBrakePointerUpOrCancel = vm::onBrakePointerUpOrCancel,
          onNitroPointerDown = vm::onNitroPointerDown,
          onNitroPointerUpOrCancel = vm::onNitroPointerUpOrCancel,
          onBrakeKeyChanged = vm::onBrakeKeyChanged,
          onNitroKeyChanged = vm::onNitroKeyChanged,
          onAttachSurface = {},
          onDetachSurface = {},
          render3DSurface = false,
        )
      }
    }

    composeTestRule.waitForIdle()

    // 1. Exact DRIP RACE title & verbatim subtitle on Home
    composeTestRule.onNodeWithText("DRIP RACE").assertIsDisplayed()
    composeTestRule
      .onNodeWithText("A Property of Sound Of Drip Musictech Private Limited.")
      .assertIsDisplayed()

    // 2. Open Settings from Home -> verify 23/23 audio summary & honest synthesized engine layer disclosure
    composeTestRule.onNodeWithTag("settings_button").performScrollTo().performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("progression_hub_overlay").assertIsDisplayed()
    composeTestRule
      .onNodeWithTag("settings_audio_status_text")
      .performScrollTo()
      .assertTextContains("23/23 verified", substring = true)
    composeTestRule
      .onNodeWithTag("settings_engine_synth_label_text")
      .performScrollTo()
      .assertTextContains("Synthesized", substring = true)
    composeTestRule
      .onNodeWithTag("settings_haptics_capability_text")
      .performScrollTo()
      .assertTextContains("No hardware vibrator", substring = true)

    composeTestRule.onNodeWithTag("progression_close_button").performClick()
    composeTestRule.waitForIdle()

    // 3. Start Race -> verify Part 8 V2 HUD elements & non-blocking layout
    composeTestRule.onNodeWithTag("play_button").performScrollTo().performClick()
    composeTestRule.waitForIdle()
    assertEquals(GamePhase.Running, vm.uiState.value.phase)

    composeTestRule.onNodeWithTag("hud_hearts_row").assertIsDisplayed()
    composeTestRule.onNodeWithTag("hud_score_text").assertIsDisplayed()
    composeTestRule.onNodeWithTag("hud_distance_text").assertIsDisplayed()
    composeTestRule.onNodeWithTag("hud_wallet_text").assertIsDisplayed()
    composeTestRule.onNodeWithTag("hud_run_coins_text").assertIsDisplayed()
    composeTestRule.onNodeWithTag("hud_speed_text").assertIsDisplayed()
    composeTestRule.onNodeWithTag("hud_gear_rpm_text").assertIsDisplayed()
    composeTestRule.onNodeWithTag("hud_speedometer_bar").assertIsDisplayed()
    composeTestRule.onNodeWithTag("hud_nitro_text").assertIsDisplayed()
    composeTestRule.onNodeWithTag("hud_mute_button").assertIsDisplayed()
    composeTestRule.onNodeWithTag("pause_button").assertIsDisplayed()
    composeTestRule.onNodeWithTag("steer_left_button").assertIsDisplayed()
    composeTestRule.onNodeWithTag("steer_right_button").assertIsDisplayed()
    composeTestRule.onNodeWithTag("brake_button").assertIsDisplayed()
    composeTestRule.onNodeWithTag("nitro_button").assertIsDisplayed()

    // Quick Mute toggle on HUD updates persistent mute state
    assertFalse(vm.hudTelemetry.value.audioMuted)
    composeTestRule.onNodeWithTag("hud_mute_button").performClick()
    composeTestRule.waitForIdle()
    assertTrue(vm.hudTelemetry.value.audioMuted)
    composeTestRule.onNodeWithTag("hud_mute_button").performClick()
    composeTestRule.waitForIdle()
    assertFalse(vm.hudTelemetry.value.audioMuted)

    // 4. Pause -> Open Settings from Paused -> Toggle Reduced Motion & Music -> Close Settings -> MUST remain Paused!
    composeTestRule.onNodeWithTag("pause_button").performClick()
    composeTestRule.waitForIdle()
    assertEquals(GamePhase.Paused, vm.uiState.value.phase)

    composeTestRule.onNodeWithTag("paused_settings_button").assertIsDisplayed().performClick()
    composeTestRule.waitForIdle()
    assertEquals(GamePhase.Paused, vm.uiState.value.phase)
    assertTrue(vm.uiState.value.progressionOverlay.isOpen)

    composeTestRule.onNodeWithTag("settings_reduced_motion_toggle").performScrollTo().performClick()
    composeTestRule.waitForIdle()
    assertTrue(vm.uiState.value.walletSnapshot.audioSettings.reducedMotion)

    composeTestRule.onNodeWithTag("settings_music_toggle").performScrollTo().performClick()
    composeTestRule.waitForIdle()
    assertFalse(vm.uiState.value.walletSnapshot.audioSettings.musicEnabled)

    // Close Settings -> must stay strictly in GamePhase.Paused (never auto-resume Running!)
    composeTestRule.onNodeWithTag("progression_close_button").performClick()
    composeTestRule.waitForIdle()
    assertFalse(vm.uiState.value.progressionOverlay.isOpen)
    assertEquals(GamePhase.Paused, vm.uiState.value.phase)
    composeTestRule.onNodeWithTag("resume_button").assertIsDisplayed()

    // Deliberate Resume returns to Running
    composeTestRule.onNodeWithTag("resume_button").performClick()
    composeTestRule.waitForIdle()
    assertEquals(GamePhase.Running, vm.uiState.value.phase)
    assertTrue(vm.hudTelemetry.value.reducedMotionActive)
  }

  @Test
  fun emulatorStartupLargeHeapDownsampledBitmapsAndStagedAsyncInitSucceed() {
    val context = ApplicationProvider.getApplicationContext<Application>()

    // 1. Verify AndroidManifest declares android:largeHeap="true" so emulators allocate large ART heap
    val appFlags = context.applicationInfo.flags
    assertTrue(
      "AndroidManifest <application> must enable android:largeHeap=\"true\"",
      (appFlags and android.content.pm.ApplicationInfo.FLAG_LARGE_HEAP) != 0,
    )

    // 2. Verify 2048x2048 asphalt maps are bounded to <= 1024px (12 MB vs 48 MB RGBA)
    val starterReady =
      StarterAssetRepository(context).loadBundledStarter() as com.example.assets.StarterLoadResult.Ready
    val baseBmp = starterReady.asphaltMaps.baseColorBitmap
    assertNotNull(baseBmp)
    assertTrue("Asphalt bitmap width must be bounded <= 1024", baseBmp!!.width <= 1024)
    assertTrue("Asphalt bitmap height must be bounded <= 1024", baseBmp.height <= 1024)

    // 3. Verify staged non-blocking startup pipeline transitions Loading -> Home cleanly with all verified diagnostics
    val backend = RecordingAudioBackend()
    val haptics = RecordingHapticsBackend(hasHardwareVibrator = false)
    val audioManager =
      DripRaceAudioManager(
        assetRepository = AudioAssetRepository(context),
        backend = backend,
        haptics = haptics,
      )
    val vm = DripRaceViewModel(context, SavedStateHandle())
    vm.overrideDependenciesForTest(
      repository = StarterAssetRepository(context),
      renderer = DripRacePhase1Test.FakeRendererPort(),
      trafficRepository = TrafficAssetRepository(context),
      scoreRepository = BestScoreRepository(context),
      heroRepository = HeroAssetRepository(context),
      sceneryRepository = SceneryAssetRepository(context),
      customAudioManager = audioManager,
    )

    kotlinx.coroutines.runBlocking {
      vm.runStagedStartupForTest(ioDispatcher = kotlinx.coroutines.Dispatchers.Default)
    }

    val state = vm.uiState.value
    assertEquals(GamePhase.Home, state.phase)
    assertTrue(state.diagnostics.sha256Verified)
    assertTrue(state.diagnostics.audioSummary.contains("23/23 verified"))
    assertTrue(state.diagnostics.sceneryInventorySummary.contains("18/19 verified"))
    assertEquals(4, state.diagnostics.heroAssetStatuses.count { it.sha256Verified })
    assertEquals(MusicPlaybackMode.MENU_LOOP, state.audioMixSnapshot.musicMode)
  }
}
