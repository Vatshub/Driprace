package com.example.state

import android.app.Application
import android.net.Uri
import android.os.Build
import android.view.SurfaceView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.example.assets.BestScoreRepository
import com.example.assets.HeroAssetRepository
import com.example.assets.HeroImportResult
import com.example.assets.HeroModelOrigin
import com.example.assets.SceneryAssetRepository
import com.example.assets.StarterAssetContract
import com.example.assets.StarterAssetRepository
import com.example.assets.StarterLoadResult
import com.example.assets.TrafficAssetRepository
import com.example.assets.TrafficImportResult
import com.example.audio.AndroidHapticsBackend
import com.example.audio.AndroidNativeAudioBackend
import com.example.audio.AudioAssetRepository
import com.example.audio.AudioCatalog
import com.example.audio.AudioSettingsState
import com.example.audio.DripRaceAudioManager
import com.example.audio.NoOpHapticsBackend
import com.example.audio.NoOpNativeAudioBackend
import com.example.economy.AtomicFileProgressionPersistence
import com.example.economy.EconomyService
import com.example.economy.GarageMutationResult
import com.example.economy.ProgressionMutationResult
import com.example.economy.RaceCheckpointOutcome
import com.example.economy.SaveHealthStatus
import com.example.economy.WalletSnapshot
import com.example.garage.BodyPaintColor
import com.example.garage.HeroVehicleCatalog
import com.example.garage.PaintCatalog
import com.example.garage.UpgradeTrack
import com.example.progression.MissionStatus
import com.example.progression.ProgressionCatalog
import com.example.render.DripRaceEngineOwner
import com.example.render.EngineRendererPort
import com.example.scenery.GraphicsQualityPreset
import com.example.scenery.RadianceHdrDecoder
import com.example.sim.DrivingInputSnapshot
import com.example.sim.DrivingSimState
import com.example.sim.DrivingSimulation
import com.example.sim.PickupCollectedEvent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

class DripRaceViewModel(
  application: Application,
  private val savedStateHandle: SavedStateHandle,
) : AndroidViewModel(application) {
  private var assetRepository = StarterAssetRepository(application.applicationContext)
  private var heroAssetRepository = HeroAssetRepository(application.applicationContext)
  private var trafficAssetRepository = TrafficAssetRepository(application.applicationContext)
  private var sceneryAssetRepository = SceneryAssetRepository(application.applicationContext)
  private var audioAssetRepository = AudioAssetRepository(application.applicationContext)
  private var bestScoreRepository = BestScoreRepository(application.applicationContext)
  var economyService: EconomyService =
    EconomyService(
      persistence = AtomicFileProgressionPersistence(application.applicationContext),
      bestScoreRepository = bestScoreRepository,
    )
    private set

  var audioManager: DripRaceAudioManager = createDefaultAudioManager(application.applicationContext)
    private set

  private var rendererPort: EngineRendererPort = DripRaceEngineOwner()
  val simulation: DrivingSimulation = DrivingSimulation(enableTraffic = true, enablePickups = true)

  private val _uiState = MutableStateFlow(DripRaceUiState())
  val uiState: StateFlow<DripRaceUiState> = _uiState.asStateFlow()

  private val _hudTelemetry = MutableStateFlow(HudTelemetryState())
  val hudTelemetry: StateFlow<HudTelemetryState> = _hudTelemetry.asStateFlow()

  private val brakePointers = mutableSetOf<Long>()
  private val nitroPointers = mutableSetOf<Long>()
  private var brakeKeyHeld = false
  private var nitroKeyHeld = false
  private var lastInputNote = "Auto-cruise active • Tap/Swipe L/R • Hold Brake/Nitro"
  private var lastHudPublishSimSeconds = -1.0
  private var lastPendingFlushSimSeconds = 0.0
  private var pendingCoinsSinceFlush = 0
  private var runSequenceCounter = 0L
  private var garageMutationSeq = 0L
  private var progressionMutationSeq = 0L
  private var activeRaceId: String = ""
  private var lastMemoryCheckpointSimSeconds = 0.0
  private var lastDiskCheckpointSimSeconds = 0.0
  private var lastObservedRaceDateKey = ""
  private var latestProgressionNotice = ""
  private var activeSceneEnvironmentId: String = ProgressionCatalog.BEGINNER_HIGHWAY_ID
  private var activeLoadedSceneryEnvId: String = ""
  private var activeLoadedSceneryPresetId: String = ""
  private var loadedDisplayVehicleId: String? = null
  private var loadedDisplayOrigin: HeroModelOrigin? = null
  private var gameOverHandledForRun = false
  private val isRobolectricJvm: Boolean = Build.FINGERPRINT.equals("robolectric", ignoreCase = true)
  private var forceSynchronousStartup: Boolean = isRobolectricJvm
  private var startupJob: Job? = null

  init {
    // Process recreation safety: never auto-resume Running; restart at Loading -> Home/Error
    savedStateHandle[KEY_RECREATED_SAFE_HOME] = true
    simulation.addPickupCollectedListener(::handlePickupEvent)
    bindSimulationToRenderer()
    syncWalletToUiState(economyService.currentSnapshot())
    loadBundledAssets()
  }

  private fun bindSimulationToRenderer() {
    rendererPort.bindSimulation(simulation) { simState ->
      // Drain any fallback events if needed; listener handles real-time exact-once credit
      if (!simState.isGameOver &&
        economyService.currentSnapshot().hasUnconfirmedPending &&
        (pendingCoinsSinceFlush >= PERIODIC_FLUSH_COIN_THRESHOLD ||
          simState.totalSimSeconds - lastPendingFlushSimSeconds >= PERIODIC_FLUSH_INTERVAL_SECONDS)
      ) {
        flushPendingEconomyAndSyncUi(simState.totalSimSeconds)
      }
      if (!simState.isGameOver && _uiState.value.phase is GamePhase.Running) {
        audioManager.onSimulationTick(simState, simulation.config)
      }
      publishTelemetryIfNeeded(simState, force = false)
    }
  }

  private fun handlePickupEvent(event: PickupCollectedEvent) {
    when (event) {
      is PickupCollectedEvent.CoinCollected -> {
        val beforeSnap = economyService.currentSnapshot()
        val prevCoinDone =
          beforeSnap.dailyMissions.any {
            it.spec.id == "coin_collector" && it.status != MissionStatus.IN_PROGRESS
          }
        val prevTreasureUnlocked =
          beforeSnap.achievements.any { it.spec.id == "treasure_hunter" && it.unlocked }

        economyService.creditCoinPickup(
          operationId = event.operationId,
          coinTier = event.coinTier,
          doubleCoinsActive = event.multiplierApplied > 1,
        )
        audioManager.onPickupCollected(event)
        pendingCoinsSinceFlush++
        val afterSnap = economyService.currentSnapshot()
        val nowCoinDone =
          afterSnap.dailyMissions.any {
            it.spec.id == "coin_collector" && it.status != MissionStatus.IN_PROGRESS
          }
        val nowTreasureUnlocked =
          afterSnap.achievements.any { it.spec.id == "treasure_hunter" && it.unlocked }
        if (!prevTreasureUnlocked && nowTreasureUnlocked) {
          latestProgressionNotice = "ACHIEVEMENT UNLOCKED: Treasure Hunter"
          audioManager.onProgressionUnlockedOrClaimed("ach_treasure_hunter")
        } else if (!prevCoinDone && nowCoinDone) {
          latestProgressionNotice = "MISSION COMPLETE: Coin Collector"
        }
        syncWalletToUiState(afterSnap)
      }
      is PickupCollectedEvent.PowerUpCollected -> {
        economyService.recordPowerUpPickup(event.operationId)
        audioManager.onPickupCollected(event)
        syncWalletToUiState(economyService.currentSnapshot())
      }
    }
  }

  private fun flushPendingEconomyAndSyncUi(simTimeSeconds: Double = simulation.state.totalSimSeconds) {
    economyService.flushPending()
    lastPendingFlushSimSeconds = simTimeSeconds
    pendingCoinsSinceFlush = 0
    syncWalletToUiState(economyService.currentSnapshot())
  }

  private fun syncWalletToUiState(snapshot: WalletSnapshot) {
    val warning =
      when (snapshot.saveStatus) {
        SaveHealthStatus.WRITE_FAILED_UNCONFIRMED ->
          snapshot.lastSaveError ?: "Save failed — wallet credit is unconfirmed"
        SaveHealthStatus.RECOVERED_FROM_BACKUP ->
          snapshot.lastSaveError ?: "Recovered save from backup snapshot"
        SaveHealthStatus.CORRUPT_RESET_WARNING ->
          snapshot.lastSaveError ?: "Corrupt save detected; safe recovery applied"
        else -> null
      }
    _uiState.update { state ->
      val focused = state.garage.focusedVehicleId
      val syncedPaint =
        if (focused in snapshot.ownedVehicleIds) {
          snapshot.paintIdFor(focused)
        } else {
          state.garage.previewPaintColorId
        }
      state.copy(
        walletSnapshot = snapshot,
        activeSceneEnvironmentId = activeSceneEnvironmentId,
        bestScore = maxOf(state.bestScore, snapshot.bestScore),
        walletBalance = snapshot.displayBalance,
        confirmedWalletBalance = snapshot.confirmedBalance,
        pendingWalletBalance = snapshot.pendingCredit,
        saveStatus = snapshot.saveStatus,
        saveWarning = warning,
        garage =
          state.garage.copy(
            ownedVehicleIds = snapshot.ownedVehicleIds,
            equippedVehicleId = snapshot.selectedVehicleId,
            vehiclePaintIds = snapshot.vehiclePaintIds,
            vehicleUpgrades = snapshot.vehicleUpgrades,
            previewPaintColorId = syncedPaint,
          ),
      )
    }
  }

  private fun createDefaultAudioManager(context: android.content.Context): DripRaceAudioManager {
    val isRobolectricJvm = Build.FINGERPRINT.equals("robolectric", ignoreCase = true)
    return if (isRobolectricJvm) {
      DripRaceAudioManager(
        assetRepository = audioAssetRepository,
        backend = NoOpNativeAudioBackend(),
        haptics = NoOpHapticsBackend,
      )
    } else {
      DripRaceAudioManager(
        assetRepository = audioAssetRepository,
        backend = AndroidNativeAudioBackend(context, audioAssetRepository),
        haptics = AndroidHapticsBackend(context),
      )
    }
  }

  /** Test hook for Robolectric JVM verification without native JNI libraries. */
  internal fun overrideDependenciesForTest(
    repository: StarterAssetRepository,
    renderer: EngineRendererPort,
    trafficRepository: TrafficAssetRepository = trafficAssetRepository,
    scoreRepository: BestScoreRepository = bestScoreRepository,
    customEconomyService: EconomyService? = null,
    heroRepository: HeroAssetRepository = heroAssetRepository,
    sceneryRepository: SceneryAssetRepository = sceneryAssetRepository,
    customAudioManager: DripRaceAudioManager? = null,
  ) {
    startupJob?.cancel()
    startupJob = null
    forceSynchronousStartup = true
    audioManager.release()
    assetRepository = repository
    heroAssetRepository = heroRepository
    trafficAssetRepository = trafficRepository
    sceneryAssetRepository = sceneryRepository
    bestScoreRepository = scoreRepository
    economyService =
      customEconomyService
        ?: EconomyService(
          persistence = AtomicFileProgressionPersistence(getApplication<Application>().applicationContext),
          bestScoreRepository = scoreRepository,
        )
    audioManager = customAudioManager ?: createDefaultAudioManager(getApplication<Application>().applicationContext)
    rendererPort = renderer
    gameOverHandledForRun = false
    activeRaceId = ""
    lastMemoryCheckpointSimSeconds = 0.0
    lastDiskCheckpointSimSeconds = 0.0
    lastObservedRaceDateKey = ""
    latestProgressionNotice = ""
    activeSceneEnvironmentId = ProgressionCatalog.BEGINNER_HIGHWAY_ID
    activeLoadedSceneryEnvId = ""
    activeLoadedSceneryPresetId = ""
    pendingCoinsSinceFlush = 0
    lastPendingFlushSimSeconds = 0.0
    loadedDisplayVehicleId = null
    loadedDisplayOrigin = null
    simulation.resetForNewRace()
    clearAllHeldInputs("Reset for test")
    bindSimulationToRenderer()
    syncWalletToUiState(economyService.currentSnapshot())
    loadBundledAssets()
  }

  fun loadBundledAssets() {
    startupJob?.cancel()
    startupJob = null
    rendererPort.setSimulationRunning(false)
    _uiState.update {
      it.copy(phase = GamePhase.Loading("Verifying starter-sports.glb & initializing 3D scene..."))
    }
    if (forceSynchronousStartup) {
      loadBundledAssetsSynchronous()
    } else {
      startupJob =
        viewModelScope.launch {
          loadBundledAssetsStagedAsync(ioDispatcher = Dispatchers.IO)
        }
    }
  }

  /** Exposed so Robolectric tests can deterministically verify the staged non-blocking startup path. */
  internal suspend fun runStagedStartupForTest(
    ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
  ) {
    startupJob?.cancel()
    startupJob = null
    rendererPort.setSimulationRunning(false)
    _uiState.update {
      it.copy(phase = GamePhase.Loading("Verifying starter-sports.glb & initializing 3D scene..."))
    }
    loadBundledAssetsStagedAsync(ioDispatcher = ioDispatcher)
  }

  private fun loadBundledAssetsSynchronous() {
    when (val result = assetRepository.loadBundledStarter()) {
      is StarterLoadResult.MissingOrInvalid -> applyStarterMissingOrInvalidError(result)
      is StarterLoadResult.Ready -> bindReadyModelToRenderer(result)
    }
  }

  private suspend fun loadBundledAssetsStagedAsync(ioDispatcher: CoroutineDispatcher) {
    // Yield so MainActivity draws the branded LoadingScreen on Frame 1 (< 16 ms, zero launch timeout)
    yield()

    var starterReady: StarterLoadResult.Ready? =
      when (val starterOutcome = withContext(ioDispatcher) { assetRepository.loadBundledStarter() }) {
        is StarterLoadResult.MissingOrInvalid -> {
          applyStarterMissingOrInvalidError(starterOutcome)
          return
        }
        is StarterLoadResult.Ready -> starterOutcome
      }

    val readyMeta = starterReady!!
    val sourceLabel = readyMeta.sourceLabel
    val isBundledOffline = readyMeta.isBundledOfflineAsset
    val sha256Hex = readyMeta.sha256Hex
    val hdrAssetPath = readyMeta.hdrAssetPath
    val asphaltDesc = formatAsphaltStatusDescription(readyMeta.asphaltMaps)

    _uiState.update {
      it.copy(phase = GamePhase.Loading("Building 3D highway & starter sports car..."))
    }
    yield()

    val renderResult =
      rendererPort.loadSceneAssets(
        starterGlbBytes = readyMeta.glbBytes,
        asphaltMaps = readyMeta.asphaltMaps,
        onViewportChanged = { summary ->
          _uiState.update { it.copy(viewportSummary = summary) }
        },
      )
    // Release 10.75 MB starter GLB ByteArray & recycled bitmap references BEFORE loading traffic GLBs
    starterReady = null

    val plan =
      renderResult.getOrElse { err ->
        applyRendererInitializationError(err, sha256Hex)
        return
      }

    _uiState.update {
      it.copy(phase = GamePhase.Loading("Loading highway traffic & roadside props..."))
    }
    yield()

    var hazardAndTraffic:
      Pair<
        com.example.assets.VerifiedHazardSceneryProps,
        Pair<List<com.example.assets.LoadedTrafficModelBytes>, com.example.assets.TrafficInventoryReport>,
      >? =
      withContext(ioDispatcher) {
        val props = sceneryAssetRepository.loadVerifiedHazardPropBytes()
        val trafficPair = trafficAssetRepository.loadAllTrafficAssets()
        props to trafficPair
      }
    val trafficReport = hazardAndTraffic!!.second.second
    rendererPort.setVerifiedHazardProps(hazardAndTraffic.first)
    syncTrafficPoolAndVerifiedSpawns(hazardAndTraffic.second.first)
    // Release ~50 MB temporary traffic GLB ByteArrays immediately after uploading to Filament
    hazardAndTraffic = null

    _uiState.update {
      it.copy(phase = GamePhase.Loading("Verifying environments, garage & 23 audio tracks..."))
    }
    yield()

    val walletSnap = economyService.currentSnapshot()
    val envSpec =
      ProgressionCatalog.findEnvironment(walletSnap.selectedEnvironmentId)
        ?: ProgressionCatalog.ENVIRONMENTS.first()
    val qualityPreset = GraphicsQualityPreset.fromId(walletSnap.graphicsQualityPresetId)
    val equippedId = walletSnap.selectedVehicleId

    val ioAuditStage =
      withContext(ioDispatcher) {
        val sceneryRep = sceneryAssetRepository.auditSceneryPackage()
        val heroStats = heroAssetRepository.loadAllHeroStatuses()
        val audioRep = audioManager.initialize(walletSnap.audioSettings)
        val nonStarterHero =
          if (equippedId != HeroVehicleCatalog.STARTER_ID) {
            heroAssetRepository.loadHeroModel(equippedId)
          } else {
            null
          }
        val envBundle = sceneryAssetRepository.loadActiveEnvironmentBundle(envSpec.id, qualityPreset)
        StagedIoAuditBundle(
          sceneryReport = sceneryRep,
          heroStatuses = heroStats,
          audioReport = audioRep,
          preloadedNonStarterHero = nonStarterHero,
          envBundle = envBundle,
        )
      }

    loadedDisplayVehicleId = HeroVehicleCatalog.STARTER_ID
    loadedDisplayOrigin = HeroModelOrigin.BUNDLED_OFFLINE_GLB
    activeSceneEnvironmentId = envSpec.id
    activeLoadedSceneryEnvId = envSpec.id
    activeLoadedSceneryPresetId = qualityPreset.id
    gameOverHandledForRun = false

    rendererPort.setReducedMotion(walletSnap.audioSettings.reducedMotion)

    // Configure equipped vehicle physics & visual model without reloading starter-sports.glb twice
    val equippedSpec = HeroVehicleCatalog.findById(equippedId) ?: HeroVehicleCatalog.STARTER_SPEC
    val effectivePhysics = equippedSpec.computeEffectivePhysics(walletSnap.upgradesFor(equippedSpec.id))
    simulation.configureActiveVehicle(effectivePhysics, resetState = true)
    val paintColor =
      PaintCatalog.findById(walletSnap.paintIdFor(equippedSpec.id))
        ?: PaintCatalog.SEVEN_FREE_COLORS.first()
    if (ioAuditStage.preloadedNonStarterHero != null) {
      val loadRes = rendererPort.loadHeroVehicle(ioAuditStage.preloadedNonStarterHero, paintColor)
      if (loadRes.isSuccess) {
        loadedDisplayVehicleId = equippedSpec.id
        loadedDisplayOrigin = ioAuditStage.preloadedNonStarterHero.origin
      }
    } else {
      rendererPort.applyHeroBodyPaint(paintColor)
    }
    rendererPort.syncVisualState(simulation.state)

    rendererPort.applyEnvironmentScenery(envSpec, ioAuditStage.envBundle)
    audioManager.onPhaseOrVehicleChanged(GamePhase.Home, walletSnap.selectedVehicleId)

    publishHomeReadyUiState(
      walletSnap = walletSnap,
      isBundledOfflineAsset = isBundledOffline,
      sourceLabel = sourceLabel,
      sha256Hex = sha256Hex,
      hdrAssetPath = hdrAssetPath,
      asphaltDesc = asphaltDesc,
      uniformScale = plan.uniformScale,
      trafficReport = trafficReport,
      sceneryReport = ioAuditStage.sceneryReport,
      heroStatuses = ioAuditStage.heroStatuses,
      audioReport = ioAuditStage.audioReport,
    )
    publishTelemetryIfNeeded(simulation.state, force = true)
  }

  private data class StagedIoAuditBundle(
    val sceneryReport: com.example.assets.SceneryPackageAuditReport,
    val heroStatuses: List<com.example.assets.HeroModelAssetStatus>,
    val audioReport: com.example.audio.AudioPackageAuditReport,
    val preloadedNonStarterHero: com.example.assets.LoadedHeroModelBytes?,
    val envBundle: com.example.assets.ActiveEnvironmentSceneryBundle,
  )

  private fun applyStarterMissingOrInvalidError(result: StarterLoadResult.MissingOrInvalid) {
    _uiState.update {
      it.copy(
        phase =
          GamePhase.Error(
            title = result.title,
            detail = result.detail,
            expectedAssetPath = result.expectedAssetPath,
            expectedSha256 = result.expectedSha256,
            actualSha256 = result.actualSha256,
            missingItems = result.missingItems,
          ),
        diagnostics =
          it.diagnostics.copy(
            vehicleOrigin = VehicleAssetOrigin.MISSING,
            vehicleSourceLabel = "Missing bundled starter-sports.glb",
            sha256Verified = false,
            sha256Hex = result.actualSha256,
            isOfflinePackagedReady = false,
          ),
      )
    }
  }

  private fun applyRendererInitializationError(err: Throwable, sha256Hex: String) {
    _uiState.update {
      it.copy(
        phase =
          GamePhase.Error(
            title = "3D renderer initialization failed",
            detail = err.message ?: err.javaClass.simpleName,
            expectedAssetPath = "app/src/main/assets/models/starter-sports.glb",
            expectedSha256 = StarterAssetContract.EXPECTED_STARTER_SHA256,
            actualSha256 = sha256Hex,
            missingItems = listOf("Supported OpenGL ES 3.0 / Filament GPU surface"),
          )
      )
    }
  }

  private fun formatAsphaltStatusDescription(
    asphaltMaps: com.example.assets.BundledAsphaltMaps,
  ): String =
    if (asphaltMaps.hasAnySuppliedMap) {
      buildString {
        append("Loaded supplied maps: ")
        listOfNotNull(
          asphaltMaps.baseColorPath?.let { "baseColor(sRGB)" },
          asphaltMaps.normalPath?.let { "normal(linear)" },
          asphaltMaps.roughnessPath?.let { "roughness(linear)" },
        ).joinTo(this, ", ")
      }
    } else {
      "Fallback procedural road surface (supplied asphalt maps not yet imported)"
    }

  private fun publishHomeReadyUiState(
    walletSnap: WalletSnapshot,
    isBundledOfflineAsset: Boolean,
    sourceLabel: String,
    sha256Hex: String,
    hdrAssetPath: String?,
    asphaltDesc: String,
    uniformScale: Float,
    trafficReport: com.example.assets.TrafficInventoryReport,
    sceneryReport: com.example.assets.SceneryPackageAuditReport,
    heroStatuses: List<com.example.assets.HeroModelAssetStatus>,
    audioReport: com.example.audio.AudioPackageAuditReport,
  ) {
    val verifiedCount = heroStatuses.count { it.sha256Verified }
    val heroSummary = "5-Car Garage: $verifiedCount/5 genuine GLBs verified (Nissan local SAF import ready)"
    val hdrDesc =
      if (hdrAssetPath != null) {
        "Genuine HDR active (${RadianceHdrDecoder.INTEGRATION_ROUTE_LABEL})"
      } else {
        "Daylight HDR fallback active (directional sun + hemisphere IBL; HDRI not yet imported)"
      }
    val steeringDesc =
      "Ported prepare-vehicle.mjs (WheelFrontL/R +30° Z premultiply once-only, yaw 0°, scale=${"%.4f".format(uniformScale)}x)"

    _uiState.update {
      it.copy(
        phase = GamePhase.Home,
        progressionOverlay = ProgressionOverlayState(),
        walletSnapshot = walletSnap,
        audioMixSnapshot = audioManager.currentSnapshot(),
        activeSceneEnvironmentId = activeSceneEnvironmentId,
        bestScore = walletSnap.bestScore,
        walletBalance = walletSnap.displayBalance,
        confirmedWalletBalance = walletSnap.confirmedBalance,
        pendingWalletBalance = walletSnap.pendingCredit,
        saveStatus = walletSnap.saveStatus,
        saveWarning = walletSnap.lastSaveError,
        garage =
          it.garage.copy(
            focusedVehicleId = walletSnap.selectedVehicleId,
            previewPaintColorId = walletSnap.paintIdFor(walletSnap.selectedVehicleId),
            ownedVehicleIds = walletSnap.ownedVehicleIds,
            equippedVehicleId = walletSnap.selectedVehicleId,
            vehiclePaintIds = walletSnap.vehiclePaintIds,
            vehicleUpgrades = walletSnap.vehicleUpgrades,
            heroAssetStatuses = heroStatuses,
          ),
        diagnostics =
          AssetDiagnosticStatus(
            vehicleOrigin =
              if (isBundledOfflineAsset) {
                VehicleAssetOrigin.BUNDLED_OFFLINE
              } else {
                VehicleAssetOrigin.DEV_DOCUMENT_PICKER_PREVIEW
              },
            vehicleSourceLabel = sourceLabel,
            sha256Verified = true,
            sha256Hex = sha256Hex,
            steeringCorrectionStatus = steeringDesc,
            normalizedPlacementSummary =
              "Grounded Y=0, X/Z centered, +Z forward, Z length 4.357386 m (scale ${"%.4f".format(uniformScale)}x)",
            asphaltMapsStatus = asphaltDesc,
            hdrLightingStatus = hdrDesc,
            isOfflinePackagedReady = isBundledOfflineAsset,
            trafficInventorySummary = trafficReport.summaryLine,
            sedanOnDevicePending = trafficReport.sedanOnDevicePending,
            trafficStates = trafficReport.states,
            heroInventorySummary = heroSummary,
            heroAssetStatuses = heroStatuses,
            sceneryInventorySummary = sceneryReport.summaryLine,
            audioSummary = audioReport.summaryLine,
            hasHardwareVibrator = audioManager.hasHardwareVibrator,
          ),
      )
    }
  }

  fun loadPreviewDocumentUri(uri: Uri) {
    rendererPort.setSimulationRunning(false)
    _uiState.update {
      it.copy(phase = GamePhase.Loading("Verifying selected GLB & SHA-256 for dev preview..."))
    }
    when (val result = assetRepository.loadFromDocumentUri(uri)) {
      is StarterLoadResult.MissingOrInvalid -> {
        _uiState.update {
          it.copy(
            phase =
              GamePhase.Error(
                title = result.title,
                detail = result.detail,
                expectedAssetPath = result.expectedAssetPath,
                expectedSha256 = result.expectedSha256,
                actualSha256 = result.actualSha256,
                missingItems = result.missingItems,
              )
          )
        }
      }
      is StarterLoadResult.Ready -> {
        bindReadyModelToRenderer(result)
      }
    }
  }

  /**
   * Imports any approved traffic GLB directly on-device via SAF picker (specifically used for
   * NoAI-restricted generic-sedan-mmcworks.glb and >30 MB traffic GLBs without any AI backend).
   */
  fun importAnyTrafficGlb(uri: Uri) {
    handleTrafficImportResult(trafficAssetRepository.importAnyTrafficGlbFromUri(uri))
  }

  fun importMultipleTrafficGlbs(uris: List<Uri>) {
    if (uris.isEmpty()) return
    if (uris.size == 1) {
      importAnyTrafficGlb(uris.first())
      return
    }
    val imported = mutableListOf<TrafficImportResult.Imported>()
    val rejected = mutableListOf<TrafficImportResult.Rejected>()
    uris.forEach { uri ->
      when (val result = trafficAssetRepository.importAnyTrafficGlbFromUri(uri)) {
        is TrafficImportResult.Imported -> imported.add(result)
        is TrafficImportResult.Rejected -> rejected.add(result)
      }
    }
    val notice =
      buildString {
        if (imported.isNotEmpty()) {
          append("Imported ${imported.size} GLB(s): ${imported.joinToString(", ") { it.spec.id }}")
        }
        if (rejected.isNotEmpty()) {
          if (isNotEmpty()) append(" | ")
          append("Rejected ${rejected.size}: ${rejected.first().title}")
        }
      }.ifBlank { "No traffic GLBs imported." }

    if (imported.isNotEmpty()) {
      val (loadedTraffic, report) = trafficAssetRepository.loadAllTrafficAssets()
      syncTrafficPoolAndVerifiedSpawns(loadedTraffic)
      _uiState.update { state ->
        state.copy(
          diagnostics =
            state.diagnostics.copy(
              trafficInventorySummary = report.summaryLine,
              sedanOnDevicePending = report.sedanOnDevicePending,
              trafficStates = report.states,
              lastTrafficImportNotice = notice,
            )
        )
      }
    } else {
      _uiState.update { state ->
        state.copy(
          diagnostics = state.diagnostics.copy(lastTrafficImportNotice = notice)
        )
      }
    }
    lastInputNote = notice
    publishTelemetryIfNeeded(simulation.state, force = true)
  }

  fun importTrafficGlb(specId: String = SEDAN_SPEC_ID, uri: Uri) {
    handleTrafficImportResult(trafficAssetRepository.importTrafficGlbFromUri(specId, uri))
  }

  private fun handleTrafficImportResult(result: TrafficImportResult) {
    val notice =
      when (result) {
        is TrafficImportResult.Imported -> {
          val (loadedTraffic, report) = trafficAssetRepository.loadAllTrafficAssets()
          syncTrafficPoolAndVerifiedSpawns(loadedTraffic)
          _uiState.update { state ->
            state.copy(
              diagnostics =
                state.diagnostics.copy(
                  trafficInventorySummary = report.summaryLine,
                  sedanOnDevicePending = report.sedanOnDevicePending,
                  trafficStates = report.states,
                  lastTrafficImportNotice = result.message,
                )
            )
          }
          result.message
        }
        is TrafficImportResult.Rejected -> {
          val rejectedMsg = "${result.title}: ${result.detail}"
          _uiState.update { state ->
            state.copy(
              diagnostics =
                state.diagnostics.copy(lastTrafficImportNotice = rejectedMsg)
            )
          }
          rejectedMsg
        }
      }
    lastInputNote = notice
    publishTelemetryIfNeeded(simulation.state, force = true)
  }

  private fun bindReadyModelToRenderer(result: StarterLoadResult.Ready) {
    val asphaltDesc = formatAsphaltStatusDescription(result.asphaltMaps)
    val renderResult =
      rendererPort.loadSceneAssets(
        starterGlbBytes = result.glbBytes,
        asphaltMaps = result.asphaltMaps,
        onViewportChanged = { summary ->
          _uiState.update { it.copy(viewportSummary = summary) }
        },
      )

    renderResult.fold(
      onSuccess = { plan ->
        rendererPort.setVerifiedHazardProps(sceneryAssetRepository.loadVerifiedHazardPropBytes())
        val (loadedTraffic, trafficReport) = trafficAssetRepository.loadAllTrafficAssets()
        syncTrafficPoolAndVerifiedSpawns(loadedTraffic)
        val sceneryReport = sceneryAssetRepository.auditSceneryPackage()
        val walletSnap = economyService.currentSnapshot()
        val heroStatuses = heroAssetRepository.loadAllHeroStatuses()

        loadedDisplayVehicleId = HeroVehicleCatalog.STARTER_ID
        loadedDisplayOrigin = HeroModelOrigin.BUNDLED_OFFLINE_GLB
        activeLoadedSceneryEnvId = ""
        activeLoadedSceneryPresetId = ""
        gameOverHandledForRun = false

        val audioReport = audioManager.initialize(walletSnap.audioSettings)
        rendererPort.setReducedMotion(walletSnap.audioSettings.reducedMotion)
        applyEquippedVehicleToSimulationAndRenderer(walletSnap, resetSimulation = true)
        applySelectedEnvironmentBetweenRaces(walletSnap, forceReload = true)
        audioManager.onPhaseOrVehicleChanged(GamePhase.Home, walletSnap.selectedVehicleId)

        publishHomeReadyUiState(
          walletSnap = walletSnap,
          isBundledOfflineAsset = result.isBundledOfflineAsset,
          sourceLabel = result.sourceLabel,
          sha256Hex = result.sha256Hex,
          hdrAssetPath = result.hdrAssetPath,
          asphaltDesc = asphaltDesc,
          uniformScale = plan.uniformScale,
          trafficReport = trafficReport,
          sceneryReport = sceneryReport,
          heroStatuses = heroStatuses,
          audioReport = audioReport,
        )
        publishTelemetryIfNeeded(simulation.state, force = true)
      },
      onFailure = { err ->
        applyRendererInitializationError(err, result.sha256Hex)
      },
    )
  }

  private fun applyEquippedVehicleToSimulationAndRenderer(
    walletSnap: WalletSnapshot,
    resetSimulation: Boolean,
  ) {
    val equippedId = walletSnap.selectedVehicleId
    val spec = HeroVehicleCatalog.findById(equippedId) ?: HeroVehicleCatalog.STARTER_SPEC
    val upgrades = walletSnap.upgradesFor(spec.id)
    val effectivePhysics = spec.computeEffectivePhysics(upgrades)
    simulation.configureActiveVehicle(effectivePhysics, resetState = resetSimulation)

    val paintId = walletSnap.paintIdFor(spec.id)
    val paintColor = PaintCatalog.findById(paintId) ?: PaintCatalog.SEVEN_FREE_COLORS.first()
    displayHeroModelInRenderer(spec.id, paintColor)
  }

  private fun applySelectedEnvironmentBetweenRaces(
    walletSnap: WalletSnapshot,
    forceReload: Boolean = false,
  ) {
    val spec =
      ProgressionCatalog.findEnvironment(walletSnap.selectedEnvironmentId)
        ?: ProgressionCatalog.ENVIRONMENTS.first()
    val preset = GraphicsQualityPreset.fromId(walletSnap.graphicsQualityPresetId)
    activeSceneEnvironmentId = spec.id
    if (!forceReload &&
      activeLoadedSceneryEnvId == spec.id &&
      activeLoadedSceneryPresetId == preset.id
    ) {
      return
    }
    val bundle = sceneryAssetRepository.loadActiveEnvironmentBundle(spec.id, preset)
    activeLoadedSceneryEnvId = spec.id
    activeLoadedSceneryPresetId = preset.id
    rendererPort.applyEnvironmentScenery(spec, bundle)
  }

  private fun syncTrafficPoolAndVerifiedSpawns(
    loadedTraffic: List<com.example.assets.LoadedTrafficModelBytes>,
  ) {
    rendererPort.loadTrafficPool(loadedTraffic)
    val verifiedIds =
      loadedTraffic
        .filter { it.sha256Verified && it.glbBytes != null }
        .map { it.spec.id }
        .toSet()
    simulation.setVerifiedMovingTrafficIds(verifiedIds.takeIf { it.isNotEmpty() })
  }

  private fun displayHeroModelInRenderer(vehicleId: String, paintColor: BodyPaintColor) {
    val loadedHero = heroAssetRepository.loadHeroModel(vehicleId)
    if (loadedDisplayVehicleId == vehicleId && loadedDisplayOrigin == loadedHero.origin) {
      rendererPort.applyHeroBodyPaint(paintColor)
    } else {
      val loadResult = rendererPort.loadHeroVehicle(loadedHero, paintColor)
      if (loadResult.isSuccess) {
        loadedDisplayVehicleId = vehicleId
        loadedDisplayOrigin = loadedHero.origin
      }
    }
    rendererPort.syncVisualState(simulation.state)
  }

  // --- Phase 5 Interactive 3D Garage Actions (Strictly between races) ---

  fun onOpenGarageClicked() {
    val current = _uiState.value.phase
    if (current !is GamePhase.Home && current !is GamePhase.GameOver) return
    audioManager.playUiClick()
    rendererPort.setSimulationRunning(false)
    flushPendingEconomyAndSyncUi()
    val snap = economyService.currentSnapshot()
    val heroStatuses = heroAssetRepository.loadAllHeroStatuses()
    val equippedSpec = HeroVehicleCatalog.findById(snap.selectedVehicleId) ?: HeroVehicleCatalog.STARTER_SPEC
    val paintId = snap.paintIdFor(equippedSpec.id)
    val paintColor = PaintCatalog.findById(paintId) ?: PaintCatalog.SEVEN_FREE_COLORS.first()

    simulation.resetForNewRace()
    displayHeroModelInRenderer(equippedSpec.id, paintColor)
    rendererPort.setShowroomCamera(
      enabled = true,
      yawDegrees = DEFAULT_SHOWROOM_YAW_DEG,
      pitchDegrees = DEFAULT_SHOWROOM_PITCH_DEG,
      distanceMeters = DEFAULT_SHOWROOM_DIST_METERS,
    )
    audioManager.onPhaseOrVehicleChanged(GamePhase.Garage, equippedSpec.id)
    _uiState.update { state ->
      state.copy(
        phase = GamePhase.Garage,
        audioMixSnapshot = audioManager.currentSnapshot(),
        garage =
          state.garage.copy(
            focusedVehicleId = equippedSpec.id,
            previewPaintColorId = paintColor.id,
            ownedVehicleIds = snap.ownedVehicleIds,
            equippedVehicleId = snap.selectedVehicleId,
            vehiclePaintIds = snap.vehiclePaintIds,
            vehicleUpgrades = snap.vehicleUpgrades,
            heroAssetStatuses = heroStatuses,
            showroomYawDegrees = DEFAULT_SHOWROOM_YAW_DEG,
            showroomPitchDegrees = DEFAULT_SHOWROOM_PITCH_DEG,
            showroomDistanceMeters = DEFAULT_SHOWROOM_DIST_METERS,
            pendingConfirmVehicleId = null,
            statusMessage = null,
          ),
        transitionCount = state.transitionCount + 1,
      )
    }
  }

  fun onExitGarageToHomeClicked() {
    if (_uiState.value.phase !is GamePhase.Garage) return
    audioManager.playUiClick()
    val snap = economyService.currentSnapshot()
    rendererPort.setShowroomCamera(enabled = false)
    applyEquippedVehicleToSimulationAndRenderer(snap, resetSimulation = true)
    audioManager.onPhaseOrVehicleChanged(GamePhase.Home, snap.selectedVehicleId)
    publishTelemetryIfNeeded(simulation.state, force = true)
    _uiState.update { state ->
      state.copy(
        phase = GamePhase.Home,
        audioMixSnapshot = audioManager.currentSnapshot(),
        garage =
          state.garage.copy(
            focusedVehicleId = snap.selectedVehicleId,
            previewPaintColorId = snap.paintIdFor(snap.selectedVehicleId),
            pendingConfirmVehicleId = null,
          ),
        transitionCount = state.transitionCount + 1,
      )
    }
  }

  fun onGarageRaceClicked() {
    if (_uiState.value.phase !is GamePhase.Garage) return
    audioManager.playUiClick(withHapticTick = true)
    val snap = economyService.currentSnapshot()
    rendererPort.setShowroomCamera(enabled = false)
    applyEquippedVehicleToSimulationAndRenderer(snap, resetSimulation = true)
    startFreshRun()
  }

  fun onGarageSelectFocusVehicle(vehicleId: String) {
    if (_uiState.value.phase !is GamePhase.Garage) return
    val spec = HeroVehicleCatalog.findById(vehicleId) ?: return
    audioManager.playUiClick()
    val snap = economyService.currentSnapshot()
    val targetPaintId =
      if (snap.isOwned(spec.id)) snap.paintIdFor(spec.id) else spec.defaultPaintId
    val paintColor = PaintCatalog.findById(targetPaintId) ?: PaintCatalog.SEVEN_FREE_COLORS.first()
    displayHeroModelInRenderer(spec.id, paintColor)
    audioManager.onPhaseOrVehicleChanged(GamePhase.Garage, spec.id)

    _uiState.update { state ->
      state.copy(
        audioMixSnapshot = audioManager.currentSnapshot(),
        garage =
          state.garage.copy(
            focusedVehicleId = spec.id,
            previewPaintColorId = paintColor.id,
            pendingConfirmVehicleId = null,
            statusMessage = null,
          ),
      )
    }
  }

  fun onGarageSelectPaintColor(paintColorId: String) {
    if (_uiState.value.phase !is GamePhase.Garage) return
    val color = PaintCatalog.findById(paintColorId) ?: return
    audioManager.playUiClick()
    val focusedId = _uiState.value.garage.focusedVehicleId
    rendererPort.applyHeroBodyPaint(color)

    val snap = economyService.currentSnapshot()
    if (snap.isOwned(focusedId)) {
      val result = economyService.setOwnedVehiclePaint(focusedId, color.id)
      syncWalletToUiState(result.walletSnapshot)
      val msg =
        when (result) {
          is GarageMutationResult.Succeeded -> result.message
          is GarageMutationResult.Rejected -> result.reason
          is GarageMutationResult.DuplicateIgnored -> null
        }
      _uiState.update { state ->
        state.copy(
          garage =
            state.garage.copy(
              previewPaintColorId = color.id,
              statusMessage = msg,
            )
        )
      }
    } else {
      // Transient preview ONLY on locked vehicle — never persisted, never unlocks
      _uiState.update { state ->
        state.copy(
          garage =
            state.garage.copy(
              previewPaintColorId = color.id,
              statusMessage = "Previewing ${color.displayName} (unlock car to save paint)",
            )
        )
      }
    }
  }

  fun onGarageRequestUnlockVehicle(vehicleId: String) {
    if (_uiState.value.phase !is GamePhase.Garage) return
    val spec = HeroVehicleCatalog.findById(vehicleId) ?: return
    val snap = economyService.currentSnapshot()
    if (snap.isOwned(spec.id)) return
    _uiState.update { state ->
      state.copy(
        garage = state.garage.copy(pendingConfirmVehicleId = spec.id, statusMessage = null)
      )
    }
  }

  fun onGarageCancelUnlockVehicle() {
    _uiState.update { state ->
      state.copy(garage = state.garage.copy(pendingConfirmVehicleId = null))
    }
  }

  fun onGarageConfirmUnlockVehicle(
    vehicleId: String,
    customOperationId: String? = null,
  ) {
    if (_uiState.value.phase !is GamePhase.Garage) return
    val opId =
      customOperationId ?: run {
        garageMutationSeq += 1L
        "garage_buy_${vehicleId}_${garageMutationSeq}"
      }
    val result = economyService.purchaseVehicle(vehicleId, operationId = opId, equipOnPurchase = true)
    syncWalletToUiState(result.walletSnapshot)
    if (result is GarageMutationResult.Succeeded) {
      audioManager.onPurchaseConfirmed(opId)
      audioManager.onPhaseOrVehicleChanged(GamePhase.Garage, result.walletSnapshot.selectedVehicleId)
      applyEquippedVehicleToSimulationAndRenderer(result.walletSnapshot, resetSimulation = true)
    }
    val statusText =
      when (result) {
        is GarageMutationResult.Succeeded -> result.message
        is GarageMutationResult.DuplicateIgnored -> "Purchase already processed."
        is GarageMutationResult.Rejected -> result.reason
      }
    _uiState.update { state ->
      state.copy(
        audioMixSnapshot = audioManager.currentSnapshot(),
        garage =
          state.garage.copy(
            pendingConfirmVehicleId = null,
            statusMessage = statusText,
          ),
      )
    }
    publishTelemetryIfNeeded(simulation.state, force = true)
  }

  fun onGarageEquipFocusedVehicle() {
    if (_uiState.value.phase !is GamePhase.Garage) return
    val focusedId = _uiState.value.garage.focusedVehicleId
    val result = economyService.selectVehicle(focusedId)
    syncWalletToUiState(result.walletSnapshot)
    if (result is GarageMutationResult.Succeeded) {
      audioManager.playUiClick(withHapticTick = true)
      audioManager.onPhaseOrVehicleChanged(GamePhase.Garage, focusedId)
      applyEquippedVehicleToSimulationAndRenderer(result.walletSnapshot, resetSimulation = true)
    }
    val msg =
      when (result) {
        is GarageMutationResult.Succeeded -> result.message
        is GarageMutationResult.Rejected -> result.reason
        is GarageMutationResult.DuplicateIgnored -> null
      }
    _uiState.update { state ->
      state.copy(
        audioMixSnapshot = audioManager.currentSnapshot(),
        garage = state.garage.copy(pendingConfirmVehicleId = null, statusMessage = msg),
      )
    }
    publishTelemetryIfNeeded(simulation.state, force = true)
  }

  fun onGarageUpgradeTrack(
    track: UpgradeTrack,
    customOperationId: String? = null,
  ) {
    val currentPhase = _uiState.value.phase
    val raceInProgress = currentPhase is GamePhase.Running || currentPhase is GamePhase.Paused
    val focusedId = _uiState.value.garage.focusedVehicleId
    val opId =
      customOperationId ?: run {
        garageMutationSeq += 1L
        "garage_upg_${focusedId}_${track.id}_${garageMutationSeq}"
      }
    val result =
      economyService.purchaseVehicleUpgrade(
        vehicleId = focusedId,
        track = track,
        operationId = opId,
        raceInProgress = raceInProgress,
      )
    syncWalletToUiState(result.walletSnapshot)
    if (result is GarageMutationResult.Succeeded) {
      audioManager.onPurchaseConfirmed(opId)
      applyEquippedVehicleToSimulationAndRenderer(result.walletSnapshot, resetSimulation = true)
    }
    val msg =
      when (result) {
        is GarageMutationResult.Succeeded -> result.message
        is GarageMutationResult.Rejected -> result.reason
        is GarageMutationResult.DuplicateIgnored -> "Upgrade already processed."
      }
    _uiState.update { state ->
      state.copy(
        audioMixSnapshot = audioManager.currentSnapshot(),
        garage = state.garage.copy(statusMessage = msg),
      )
    }
    publishTelemetryIfNeeded(simulation.state, force = true)
  }

  fun onImportNissanHeroGlb(uri: Uri) {
    importHeroGlbForVehicle(HeroVehicleCatalog.HYPER_SPORTS_ID, uri)
  }

  fun importHeroGlbForVehicle(vehicleId: String, uri: Uri) {
    val result = heroAssetRepository.importHeroGlbFromUri(vehicleId, uri)
    val statuses = heroAssetRepository.loadAllHeroStatuses()
    val snap = economyService.currentSnapshot()
    val verifiedCount = statuses.count { it.sha256Verified }
    val heroSummary = "5-Car Garage: $verifiedCount/5 genuine GLBs verified"

    val msg =
      when (result) {
        is HeroImportResult.Imported -> {
          if (_uiState.value.garage.focusedVehicleId == vehicleId && _uiState.value.phase is GamePhase.Garage) {
            val paint = PaintCatalog.findById(_uiState.value.garage.previewPaintColorId) ?: PaintCatalog.SEVEN_FREE_COLORS.first()
            loadedDisplayVehicleId = null
            displayHeroModelInRenderer(vehicleId, paint)
          } else if (snap.selectedVehicleId == vehicleId) {
            loadedDisplayVehicleId = null
            applyEquippedVehicleToSimulationAndRenderer(snap, resetSimulation = false)
          }
          result.message
        }
        is HeroImportResult.Rejected -> "${result.title}: ${result.detail}"
      }

    _uiState.update { state ->
      state.copy(
        garage = state.garage.copy(heroAssetStatuses = statuses, statusMessage = msg),
        diagnostics =
          state.diagnostics.copy(
            heroAssetStatuses = statuses,
            heroInventorySummary = heroSummary,
          ),
      )
    }
  }

  fun onShowroomOrbitDelta(deltaYawDegrees: Float, deltaPitchDegrees: Float) {
    if (_uiState.value.phase !is GamePhase.Garage) return
    val cur = _uiState.value.garage
    val nextYaw = (cur.showroomYawDegrees + deltaYawDegrees) % 360f
    val nextPitch = (cur.showroomPitchDegrees + deltaPitchDegrees).coerceIn(7f, 44f)
    rendererPort.setShowroomCamera(
      enabled = true,
      yawDegrees = nextYaw,
      pitchDegrees = nextPitch,
      distanceMeters = cur.showroomDistanceMeters,
    )
    _uiState.update { state ->
      state.copy(
        garage = state.garage.copy(showroomYawDegrees = nextYaw, showroomPitchDegrees = nextPitch)
      )
    }
  }

  fun onShowroomZoomDelta(deltaDistanceMeters: Float) {
    if (_uiState.value.phase !is GamePhase.Garage) return
    val cur = _uiState.value.garage
    val nextDist = (cur.showroomDistanceMeters + deltaDistanceMeters).coerceIn(4.3f, 9.5f)
    rendererPort.setShowroomCamera(
      enabled = true,
      yawDegrees = cur.showroomYawDegrees,
      pitchDegrees = cur.showroomPitchDegrees,
      distanceMeters = nextDist,
    )
    _uiState.update { state ->
      state.copy(garage = state.garage.copy(showroomDistanceMeters = nextDist))
    }
  }

  fun onShowroomResetView() {
    if (_uiState.value.phase !is GamePhase.Garage) return
    rendererPort.setShowroomCamera(
      enabled = true,
      yawDegrees = DEFAULT_SHOWROOM_YAW_DEG,
      pitchDegrees = DEFAULT_SHOWROOM_PITCH_DEG,
      distanceMeters = DEFAULT_SHOWROOM_DIST_METERS,
    )
    _uiState.update { state ->
      state.copy(
        garage =
          state.garage.copy(
            showroomYawDegrees = DEFAULT_SHOWROOM_YAW_DEG,
            showroomPitchDegrees = DEFAULT_SHOWROOM_PITCH_DEG,
            showroomDistanceMeters = DEFAULT_SHOWROOM_DIST_METERS,
          )
      )
    }
  }

  // --- Phase 6 Progression Hub Subview & Explicit Claim Actions ---

  fun onOpenProgressionTab(tab: ProgressionTab) {
    val current = _uiState.value.phase
    if (current !is GamePhase.Home && current !is GamePhase.Paused && current !is GamePhase.GameOver) {
      return
    }
    audioManager.playUiClick()
    val snap = economyService.syncCalendarClock()
    syncWalletToUiState(snap)
    _uiState.update { state ->
      state.copy(
        audioMixSnapshot = audioManager.currentSnapshot(),
        progressionOverlay =
          ProgressionOverlayState(
            isOpen = true,
            activeTab = tab,
            statusMessage = null,
          ),
      )
    }
  }

  fun onSelectProgressionTab(tab: ProgressionTab) {
    if (!_uiState.value.progressionOverlay.isOpen) return
    audioManager.playUiClick()
    val snap = economyService.syncCalendarClock()
    syncWalletToUiState(snap)
    _uiState.update { state ->
      state.copy(
        audioMixSnapshot = audioManager.currentSnapshot(),
        progressionOverlay =
          state.progressionOverlay.copy(
            activeTab = tab,
            statusMessage = null,
          ),
      )
    }
  }

  fun onCloseProgressionOverlay() {
    if (!_uiState.value.progressionOverlay.isOpen) return
    audioManager.playUiClick()
    val snap = economyService.syncCalendarClock()
    syncWalletToUiState(snap)
    _uiState.update { state ->
      // Preserves current GamePhase (e.g. Paused stays Paused — never auto-resumes Running)
      state.copy(
        audioMixSnapshot = audioManager.currentSnapshot(),
        progressionOverlay = ProgressionOverlayState(isOpen = false, statusMessage = null),
      )
    }
  }

  fun onSyncCalendarClock() {
    val snap = economyService.syncCalendarClock()
    syncWalletToUiState(snap)
  }

  fun onClaimDailyMission(
    missionId: String,
    customOperationId: String? = null,
  ) {
    val opId =
      customOperationId ?: run {
        progressionMutationSeq += 1L
        "claim_mission_${missionId}_${progressionMutationSeq}"
      }
    val result = economyService.claimDailyMission(missionId = missionId, operationId = opId)
    applyProgressionMutationResult(result)
  }

  fun onClaimAchievement(
    achievementId: String,
    customOperationId: String? = null,
  ) {
    val opId =
      customOperationId ?: run {
        progressionMutationSeq += 1L
        "claim_ach_${achievementId}_${progressionMutationSeq}"
      }
    val result = economyService.claimAchievement(achievementId = achievementId, operationId = opId)
    applyProgressionMutationResult(result)
  }

  fun onClaimDailyLoginReward(
    customOperationId: String? = null,
  ) {
    val opId =
      customOperationId ?: run {
        progressionMutationSeq += 1L
        "claim_daily_login_${progressionMutationSeq}"
      }
    val result = economyService.claimDailyLoginReward(operationId = opId)
    applyProgressionMutationResult(result)
  }

  fun onSelectEnvironment(environmentId: String) {
    audioManager.playUiClick()
    val currentPhase = _uiState.value.phase
    val raceInProgress = currentPhase is GamePhase.Running || currentPhase is GamePhase.Paused
    val result =
      economyService.selectEnvironment(
        environmentId = environmentId,
        raceInProgress = raceInProgress,
      )
    if (!raceInProgress && result is ProgressionMutationResult.Updated) {
      applySelectedEnvironmentBetweenRaces(result.walletSnapshot)
    }
    applyProgressionMutationResult(result)
    publishTelemetryIfNeeded(simulation.state, force = true)
  }

  fun onSelectGraphicsQualityPreset(preset: GraphicsQualityPreset) {
    audioManager.playUiClick()
    val currentPhase = _uiState.value.phase
    val raceInProgress = currentPhase is GamePhase.Running || currentPhase is GamePhase.Paused
    val result = economyService.setGraphicsQualityPreset(preset)
    if (!raceInProgress && result is ProgressionMutationResult.Updated) {
      applySelectedEnvironmentBetweenRaces(result.walletSnapshot, forceReload = true)
    }
    applyProgressionMutationResult(result)
  }

  fun onUpdateAudioSettings(newSettings: AudioSettingsState) {
    val result = economyService.updateAudioSettings(newSettings)
    val savedSettings = result.walletSnapshot.audioSettings
    val mixSnap = audioManager.updateSettings(savedSettings)
    rendererPort.setReducedMotion(savedSettings.reducedMotion)
    applyProgressionMutationResult(result)
    _uiState.update { it.copy(audioMixSnapshot = mixSnap) }
    publishTelemetryIfNeeded(simulation.state, force = true)
  }

  fun onToggleQuickMute() {
    val cur = economyService.currentSnapshot().audioSettings
    onUpdateAudioSettings(cur.copy(muted = !cur.muted))
  }

  fun onImportSceneryCandidateGlb(uri: Uri) {
    val importOutcome = sceneryAssetRepository.importSceneryGlbFromUri(uri)
    val sceneryReport = sceneryAssetRepository.auditSceneryPackage()
    val currentPhase = _uiState.value.phase
    val raceInProgress = currentPhase is GamePhase.Running || currentPhase is GamePhase.Paused
    val notice =
      importOutcome.fold(
        onSuccess = { spec ->
          if (!raceInProgress) {
            applySelectedEnvironmentBetweenRaces(
              economyService.currentSnapshot(),
              forceReload = true,
            )
          }
          "Verified & imported ${spec.displayName} locally on-device."
        },
        onFailure = { err ->
          "Scenery import rejected: ${err.message ?: err.javaClass.simpleName}"
        },
      )
    _uiState.update { state ->
      state.copy(
        diagnostics =
          state.diagnostics.copy(
            sceneryInventorySummary = sceneryReport.summaryLine,
            lastSceneryImportNotice = notice,
          ),
        progressionOverlay =
          state.progressionOverlay.copy(statusMessage = notice),
      )
    }
  }

  private fun applyProgressionMutationResult(result: ProgressionMutationResult) {
    syncWalletToUiState(result.walletSnapshot)
    if (result is ProgressionMutationResult.Claimed) {
      audioManager.onProgressionUnlockedOrClaimed(result.operationId)
    }
    val msg =
      when (result) {
        is ProgressionMutationResult.Claimed -> result.message
        is ProgressionMutationResult.Updated -> result.message
        is ProgressionMutationResult.DuplicateIgnored -> "Claim already processed."
        is ProgressionMutationResult.Rejected -> result.reason
      }
    _uiState.update { state ->
      state.copy(
        audioMixSnapshot = audioManager.currentSnapshot(),
        progressionOverlay = state.progressionOverlay.copy(statusMessage = msg),
      )
    }
  }

  private fun checkpointCurrentRace(
    simState: DrivingSimState,
    raceEnded: Boolean,
    flushToDisk: Boolean,
  ): RaceCheckpointOutcome? {
    val raceId = activeRaceId
    if (raceId.isBlank()) return null
    val outcome =
      economyService.checkpointRaceProgress(
        raceId = raceId,
        distanceMeters = simState.distanceMeters,
        activeSeconds = simState.totalSimSeconds,
        uniqueOvertakes = simState.validUniqueOvertakes,
        uniqueNearMisses = simState.validUniqueNearMisses,
        qualifiedManualNitroUses = simState.qualifiedManualNitroUses,
        shieldBlocks = simState.shieldBlocksCount,
        currentScore = simState.score,
        raceEnded = raceEnded,
        flushToDisk = flushToDisk,
      )
    lastMemoryCheckpointSimSeconds = simState.totalSimSeconds
    if (flushToDisk) {
      lastDiskCheckpointSimSeconds = simState.totalSimSeconds
    }
    lastObservedRaceDateKey = outcome.walletSnapshot.currentDateKey

    when {
      outcome.newlyUnlockedEnvironmentNames.isNotEmpty() -> {
        latestProgressionNotice =
          "LEVEL UNLOCKED: ${outcome.newlyUnlockedEnvironmentNames.joinToString()} (available next race)"
        audioManager.onProgressionUnlockedOrClaimed(
          "env_${raceId}_${outcome.newlyUnlockedEnvironmentNames.joinToString("_")}"
        )
      }
      outcome.newlyUnlockedAchievementTitles.isNotEmpty() -> {
        latestProgressionNotice =
          "ACHIEVEMENT UNLOCKED: ${outcome.newlyUnlockedAchievementTitles.joinToString()}"
        audioManager.onProgressionUnlockedOrClaimed(
          "ach_${raceId}_${outcome.newlyUnlockedAchievementTitles.joinToString("_")}"
        )
      }
      outcome.newlyCompletedMissionTitles.isNotEmpty() -> {
        latestProgressionNotice =
          "MISSION COMPLETE: ${outcome.newlyCompletedMissionTitles.joinToString()}"
      }
      outcome.dateRolledOverMidRace -> {
        latestProgressionNotice = "NEW DAY: Daily missions & endurance segment reset"
      }
    }
    syncWalletToUiState(outcome.walletSnapshot)
    return outcome
  }

  fun onPlayClicked() {
    if (_uiState.value.phase !is GamePhase.Home) return
    audioManager.playUiClick(withHapticTick = true)
    val snap = economyService.currentSnapshot()
    rendererPort.setShowroomCamera(enabled = false)
    applyEquippedVehicleToSimulationAndRenderer(snap, resetSimulation = true)
    startFreshRun()
  }

  fun onPlayAgainClicked() {
    if (_uiState.value.phase !is GamePhase.GameOver) return
    audioManager.playUiClick(withHapticTick = true)
    val snap = economyService.currentSnapshot()
    applyEquippedVehicleToSimulationAndRenderer(snap, resetSimulation = true)
    startFreshRun()
  }

  fun onRestartClicked() {
    val current = _uiState.value.phase
    if (current !is GamePhase.Paused && current !is GamePhase.GameOver) return
    audioManager.playUiClick(withHapticTick = true)
    if (current is GamePhase.Paused && !gameOverHandledForRun) {
      checkpointCurrentRace(simulation.state, raceEnded = true, flushToDisk = true)
    }
    val snap = economyService.currentSnapshot()
    applyEquippedVehicleToSimulationAndRenderer(snap, resetSimulation = true)
    startFreshRun()
  }

  private fun startFreshRun() {
    flushPendingEconomyAndSyncUi()
    val snap = economyService.syncCalendarClock()
    rendererPort.setReducedMotion(snap.audioSettings.reducedMotion)
    applySelectedEnvironmentBetweenRaces(snap)
    runSequenceCounter += 1L
    activeRaceId = "race_${runSequenceCounter}"
    lastMemoryCheckpointSimSeconds = 0.0
    lastDiskCheckpointSimSeconds = 0.0
    lastObservedRaceDateKey = snap.currentDateKey
    latestProgressionNotice = ""
    gameOverHandledForRun = false
    pendingCoinsSinceFlush = 0
    lastPendingFlushSimSeconds = 0.0
    simulation.resetForNewRace()
    clearAllHeldInputs("Race started")
    rendererPort.syncVisualState(simulation.state)
    rendererPort.setSimulationRunning(true)
    audioManager.onPhaseOrVehicleChanged(GamePhase.Running, snap.selectedVehicleId, raceId = activeRaceId)
    audioManager.onSimulationTick(simulation.state, simulation.config)
    syncWalletToUiState(snap)
    _uiState.update { state ->
      state.copy(
        phase = GamePhase.Running,
        audioMixSnapshot = audioManager.currentSnapshot(),
        progressionOverlay = ProgressionOverlayState(),
        transitionCount = state.transitionCount + 1,
      )
    }
    publishTelemetryIfNeeded(simulation.state, force = true)
  }

  fun onPauseClicked() {
    if (_uiState.value.phase !is GamePhase.Running) return
    audioManager.playUiClick()
    pauseActiveRun("Paused")
  }

  private fun pauseActiveRun(reason: String) {
    rendererPort.setSimulationRunning(false)
    clearAllHeldInputs(reason)
    checkpointCurrentRace(simulation.state, raceEnded = false, flushToDisk = true)
    flushPendingEconomyAndSyncUi()
    val snap = economyService.currentSnapshot()
    audioManager.onPhaseOrVehicleChanged(GamePhase.Paused, snap.selectedVehicleId, raceId = activeRaceId)
    publishTelemetryIfNeeded(simulation.state, force = true)
    _uiState.update { state ->
      if (state.phase is GamePhase.Running) {
        state.copy(
          phase = GamePhase.Paused,
          audioMixSnapshot = audioManager.currentSnapshot(),
          transitionCount = state.transitionCount + 1,
        )
      } else {
        state
      }
    }
  }

  fun onResumeClicked() {
    if (_uiState.value.phase !is GamePhase.Paused) return
    audioManager.playUiClick(withHapticTick = true)
    val snap = economyService.syncCalendarClock()
    lastObservedRaceDateKey = snap.currentDateKey
    syncWalletToUiState(snap)
    clearAllHeldInputs("Resumed")
    rendererPort.setSimulationRunning(true)
    audioManager.onPhaseOrVehicleChanged(GamePhase.Running, snap.selectedVehicleId, raceId = activeRaceId)
    audioManager.onSimulationTick(simulation.state, simulation.config)
    _uiState.update { state ->
      state.copy(
        phase = GamePhase.Running,
        audioMixSnapshot = audioManager.currentSnapshot(),
        progressionOverlay = ProgressionOverlayState(),
        transitionCount = state.transitionCount + 1,
      )
    }
    publishTelemetryIfNeeded(simulation.state, force = true)
  }

  fun onHomeClicked() {
    val current = _uiState.value.phase
    if (current is GamePhase.Garage) {
      onExitGarageToHomeClicked()
      return
    }
    if (current !is GamePhase.Paused && current !is GamePhase.Running && current !is GamePhase.GameOver) return
    audioManager.playUiClick()
    rendererPort.setSimulationRunning(false)
    if (!gameOverHandledForRun && (current is GamePhase.Paused || current is GamePhase.Running)) {
      checkpointCurrentRace(simulation.state, raceEnded = true, flushToDisk = true)
    }
    flushPendingEconomyAndSyncUi()
    val snap = economyService.syncCalendarClock()
    applySelectedEnvironmentBetweenRaces(snap)
    activeRaceId = ""
    gameOverHandledForRun = false
    pendingCoinsSinceFlush = 0
    lastPendingFlushSimSeconds = 0.0
    latestProgressionNotice = ""
    simulation.resetForNewRace()
    clearAllHeldInputs("Returned Home")
    rendererPort.syncVisualState(simulation.state)
    audioManager.onPhaseOrVehicleChanged(GamePhase.Home, snap.selectedVehicleId)
    syncWalletToUiState(snap)
    publishTelemetryIfNeeded(simulation.state, force = true)
    _uiState.update { state ->
      state.copy(
        phase = GamePhase.Home,
        audioMixSnapshot = audioManager.currentSnapshot(),
        progressionOverlay = ProgressionOverlayState(),
        transitionCount = state.transitionCount + 1,
      )
    }
  }

  fun onRetryClicked() {
    loadBundledAssets()
  }

  /** Returns true if Back was consumed (Subview close, Running -> Paused, Garage -> Home, or GameOver -> Home). */
  fun onBackPressed(): Boolean {
    if (_uiState.value.progressionOverlay.isOpen) {
      onCloseProgressionOverlay()
      return true
    }
    return when (_uiState.value.phase) {
      is GamePhase.Running -> {
        pauseActiveRun("Paused via Back")
        true
      }
      is GamePhase.Garage -> {
        onExitGarageToHomeClicked()
        true
      }
      is GamePhase.GameOver -> {
        onHomeClicked()
        true
      }
      else -> false
    }
  }

  fun onActivityStartedOrResumed(active: Boolean) {
    rendererPort.setRenderingActive(active)
    if (!active) {
      if (_uiState.value.phase is GamePhase.Running) {
        pauseActiveRun("Paused on background")
      } else {
        clearAllHeldInputs("Cleared on background")
        flushPendingEconomyAndSyncUi()
      }
      audioManager.onLifecycleActiveChanged(false)
    } else {
      audioManager.onLifecycleActiveChanged(true)
    }
    _uiState.update { it.copy(audioMixSnapshot = audioManager.currentSnapshot()) }
  }

  fun onWindowFocusChanged(hasFocus: Boolean) {
    if (!hasFocus) {
      val hadHeld =
        brakePointers.isNotEmpty() || nitroPointers.isNotEmpty() || brakeKeyHeld || nitroKeyHeld
      if (hadHeld) {
        clearAllHeldInputs("Cleared on focus loss")
      } else {
        simulation.clearQueuedSteering()
        rendererPort.setDrivingInput(DrivingInputSnapshot())
      }
      simulation.clearPendingFrameAccumulator()
    }
  }

  // --- Driving Controls (Multi-touch & Keyboard) ---

  fun onSteerLeft() {
    if (_uiState.value.phase !is GamePhase.Running) return
    simulation.requestLaneChange(-1)
    audioManager.onSteerCue(-1)
    lastInputNote = "Steer Left"
    publishTelemetryIfNeeded(simulation.state, force = true)
  }

  fun onSteerRight() {
    if (_uiState.value.phase !is GamePhase.Running) return
    simulation.requestLaneChange(+1)
    audioManager.onSteerCue(+1)
    lastInputNote = "Steer Right"
    publishTelemetryIfNeeded(simulation.state, force = true)
  }

  fun onBrakePointerDown(pointerId: Long) {
    if (_uiState.value.phase !is GamePhase.Running) return
    if (brakePointers.add(pointerId)) {
      syncDrivingInput("Brake held (#$pointerId)")
    }
  }

  fun onBrakePointerUpOrCancel(pointerId: Long) {
    if (brakePointers.remove(pointerId)) {
      syncDrivingInput("Brake released (#$pointerId)")
    }
  }

  fun onNitroPointerDown(pointerId: Long) {
    if (_uiState.value.phase !is GamePhase.Running) return
    if (nitroPointers.add(pointerId)) {
      syncDrivingInput("Nitro held (#$pointerId)")
    }
  }

  fun onNitroPointerUpOrCancel(pointerId: Long) {
    if (nitroPointers.remove(pointerId)) {
      syncDrivingInput("Nitro released (#$pointerId)")
    }
  }

  fun onBrakeKeyChanged(held: Boolean) {
    if (_uiState.value.phase !is GamePhase.Running && held) return
    if (brakeKeyHeld != held) {
      brakeKeyHeld = held
      syncDrivingInput(if (held) "Brake key down" else "Brake key up")
    }
  }

  fun onNitroKeyChanged(held: Boolean) {
    if (_uiState.value.phase !is GamePhase.Running && held) return
    if (nitroKeyHeld != held) {
      nitroKeyHeld = held
      syncDrivingInput(if (held) "Nitro key down" else "Nitro key up")
    }
  }

  private fun syncDrivingInput(note: String) {
    lastInputNote = note
    val snapshot = currentInputSnapshot()
    rendererPort.setDrivingInput(snapshot)
    publishTelemetryIfNeeded(simulation.state, force = true)
  }

  private fun clearAllHeldInputs(reason: String) {
    val hadHeld =
      brakePointers.isNotEmpty() || nitroPointers.isNotEmpty() || brakeKeyHeld || nitroKeyHeld
    brakePointers.clear()
    nitroPointers.clear()
    brakeKeyHeld = false
    nitroKeyHeld = false
    simulation.clearQueuedSteering()
    lastInputNote = if (hadHeld) "$reason (held inputs released)" else reason
    rendererPort.setDrivingInput(DrivingInputSnapshot())
    publishTelemetryIfNeeded(simulation.state, force = true)
  }

  private fun currentInputSnapshot(): DrivingInputSnapshot =
    DrivingInputSnapshot(
      brakeHeld = brakePointers.isNotEmpty() || brakeKeyHeld,
      nitroHeld = nitroPointers.isNotEmpty() || nitroKeyHeld,
    )

  /** Exposed for deterministic tests driving simulation ticks directly. */
  internal fun publishTelemetryFromSimForTest() {
    if (_uiState.value.phase is GamePhase.Running && !simulation.state.isGameOver) {
      audioManager.onSimulationTick(simulation.state, simulation.config)
    }
    publishTelemetryIfNeeded(simulation.state, force = true)
  }

  private fun publishTelemetryIfNeeded(simState: DrivingSimState, force: Boolean) {
    // Handle Game Over exactly once per run before telemetry throttle check; NEVER re-credit coins!
    if (simState.isGameOver && !gameOverHandledForRun && _uiState.value.phase is GamePhase.Running) {
      gameOverHandledForRun = true
      rendererPort.setSimulationRunning(false)
      clearAllHeldInputs("Game Over")
      val cpOutcome =
        checkpointCurrentRace(
          simState = simState,
          raceEnded = true,
          flushToDisk = true,
        )
      val (settledBest, settledNewBest) =
        economyService.settleRunCompletion(
          runOperationId = "run_complete_${runSequenceCounter}",
          finalScore = simState.score,
          runShieldBlocks = simState.shieldBlocksCount,
        )
      val updatedBest = maxOf(cpOutcome?.bestScore ?: 0, settledBest)
      val isNewBest = (cpOutcome?.isNewBestScore == true) || settledNewBest
      val walletSnap = economyService.currentSnapshot()
      val warning =
        if (walletSnap.saveStatus == SaveHealthStatus.WRITE_FAILED_UNCONFIRMED) {
          walletSnap.lastSaveError ?: "Save failed — pending coins not confirmed"
        } else {
          walletSnap.lastSaveError
        }
      val gameOverPhase =
        GamePhase.GameOver(
          finalScore = simState.score,
          bestScore = updatedBest,
          isNewBest = isNewBest,
          distanceMeters = simState.distanceMeters,
          validUniqueOvertakes = simState.validUniqueOvertakes,
          validUniqueNearMisses = simState.validUniqueNearMisses,
          stageLabel = simState.difficultyStage.label,
          shieldBlocksCount = simState.shieldBlocksCount,
          runCoinsEarned = simState.runCoinsEarned,
          runCoinPickupsCollected = simState.runCoinPickupsCollected,
          walletBalance = walletSnap.displayBalance,
          saveStatus = walletSnap.saveStatus,
          saveWarning = warning,
        )
      audioManager.onPhaseOrVehicleChanged(gameOverPhase, walletSnap.selectedVehicleId, raceId = activeRaceId)
      _uiState.update { state ->
        state.copy(
          phase = gameOverPhase,
          walletSnapshot = walletSnap,
          audioMixSnapshot = audioManager.currentSnapshot(),
          bestScore = updatedBest,
          walletBalance = walletSnap.displayBalance,
          confirmedWalletBalance = walletSnap.confirmedBalance,
          pendingWalletBalance = walletSnap.pendingCredit,
          saveStatus = walletSnap.saveStatus,
          saveWarning = warning,
          transitionCount = state.transitionCount + 1,
        )
      }
    } else if (_uiState.value.phase is GamePhase.Running && !simState.isGameOver && activeRaceId.isNotEmpty()) {
      val prevHud = _hudTelemetry.value
      val currentDateKey = economyService.peekCurrentLocalDateKey()
      val dateChanged =
        lastObservedRaceDateKey.isNotEmpty() && currentDateKey != lastObservedRaceDateKey
      val shouldFlushDisk =
        dateChanged ||
          (simState.totalSimSeconds - lastDiskCheckpointSimSeconds >= RACE_CHECKPOINT_INTERVAL_SECONDS)
      val discreteProgressChanged =
        simState.validUniqueOvertakes != prevHud.validUniqueOvertakes ||
          simState.validUniqueNearMisses != prevHud.validUniqueNearMisses ||
          simState.qualifiedManualNitroUses != prevHud.qualifiedManualNitroUses
      val shouldSyncMemory =
        shouldFlushDisk ||
          discreteProgressChanged ||
          (simState.totalSimSeconds - lastMemoryCheckpointSimSeconds >= MEMORY_CHECKPOINT_INTERVAL_SECONDS)
      if (shouldSyncMemory) {
        checkpointCurrentRace(
          simState = simState,
          raceEnded = false,
          flushToDisk = shouldFlushDisk,
        )
      }
    }

    val walletSnap = economyService.currentSnapshot()
    val mixSnap = audioManager.currentSnapshot()
    if (force && _uiState.value.walletSnapshot != walletSnap) {
      syncWalletToUiState(walletSnap)
    }
    val prev = _hudTelemetry.value
    val input = currentInputSnapshot()
    val graceActive = simState.collisionGraceRemainingSeconds > 0.0
    val activeEnvName =
      ProgressionCatalog.findEnvironment(activeSceneEnvironmentId)?.displayName
        ?: ProgressionCatalog.ENVIRONMENTS.first().displayName
    val engineProfile = AudioCatalog.profileForVehicle(simulation.config.id)
    val discreteChanged =
      prev.isBraking != simState.isBraking ||
        prev.isNitroActive != simState.isNitroActive ||
        prev.isAutoNitroActive != simState.isAutoNitroActive ||
        prev.currentLaneIndex != simState.currentLaneIndex ||
        prev.isChangingLane != simState.isChangingLane ||
        prev.brakeInputHeld != input.brakeHeld ||
        prev.nitroInputHeld != input.nitroHeld ||
        prev.hearts != simState.hearts ||
        prev.shieldCharges != simState.shieldCharges ||
        prev.validUniqueOvertakes != simState.validUniqueOvertakes ||
        prev.validUniqueNearMisses != simState.validUniqueNearMisses ||
        prev.qualifiedManualNitroUses != simState.qualifiedManualNitroUses ||
        prev.stageNumber != simState.difficultyStage.stageNumber ||
        prev.lastTrafficEvent != simState.lastTrafficEvent ||
        prev.collisionGraceActive != graceActive ||
        prev.runCoinsEarned != simState.runCoinsEarned ||
        prev.runCoinPickupsCollected != simState.runCoinPickupsCollected ||
        prev.walletBalance != walletSnap.displayBalance ||
        prev.pendingWalletBalance != walletSnap.pendingCredit ||
        prev.saveStatus != walletSnap.saveStatus ||
        prev.progressionNoticeMessage != latestProgressionNotice ||
        prev.activeEnvironmentName != activeEnvName ||
        prev.launchBannerText != mixSnap.launchBannerText ||
        prev.audioMuted != walletSnap.audioSettings.muted ||
        prev.reducedMotionActive != walletSnap.audioSettings.reducedMotion ||
        (prev.autoNitroRemainingSeconds > 0.0) != (simState.autoNitroRemainingSeconds > 0.0) ||
        (prev.magnetRemainingSeconds > 0.0) != (simState.magnetRemainingSeconds > 0.0) ||
        (prev.shieldRemainingSeconds > 0.0) != (simState.shieldRemainingSeconds > 0.0) ||
        (prev.doubleCoinsRemainingSeconds > 0.0) != (simState.doubleCoinsRemainingSeconds > 0.0) ||
        prev.score != simState.score

    val elapsedSincePublish = simState.totalSimSeconds - lastHudPublishSimSeconds
    if (!force && !discreteChanged && elapsedSincePublish in 0.0..<0.05) {
      return
    }
    lastHudPublishSimSeconds = simState.totalSimSeconds
    val currentBest = maxOf(_uiState.value.bestScore, walletSnap.bestScore)
    _hudTelemetry.value =
      HudTelemetryState(
        speedKmh = simState.speedKmh,
        speedMetersPerSecond = simState.speedMetersPerSecond,
        activeVehicleName = simulation.config.displayName,
        activeVehicleTopSpeedKmh = simulation.config.topSpeedKmh,
        activeEnvironmentName = activeEnvName,
        distanceMeters = simState.distanceMeters,
        nitroMeter = simState.nitroMeter,
        currentLaneIndex = simState.currentLaneIndex,
        isChangingLane = simState.isChangingLane,
        isBraking = simState.isBraking,
        isNitroActive = simState.isNitroActive,
        isAutoNitroActive = simState.isAutoNitroActive,
        brakeInputHeld = input.brakeHeld,
        nitroInputHeld = input.nitroHeld,
        fixedStepsExecuted = simState.fixedStepsExecuted,
        totalSimSeconds = simState.totalSimSeconds,
        activeHoldPointerCount = brakePointers.size + nitroPointers.size,
        lastInputSummary = lastInputNote,
        hearts = simState.hearts,
        shieldCharges = simState.shieldCharges,
        score = simState.score,
        bestScore = currentBest,
        validUniqueOvertakes = simState.validUniqueOvertakes,
        validUniqueNearMisses = simState.validUniqueNearMisses,
        stageNumber = simState.difficultyStage.stageNumber,
        stageLabel = simState.difficultyStage.label,
        lastTrafficEvent = simState.lastTrafficEvent,
        lastTrafficEventMessage = simState.lastTrafficEventMessage,
        collisionGraceActive = graceActive,
        walletBalance = walletSnap.displayBalance,
        confirmedWalletBalance = walletSnap.confirmedBalance,
        pendingWalletBalance = walletSnap.pendingCredit,
        runCoinsEarned = simState.runCoinsEarned,
        runCoinPickupsCollected = simState.runCoinPickupsCollected,
        qualifiedManualNitroUses = simState.qualifiedManualNitroUses,
        autoNitroRemainingSeconds = simState.autoNitroRemainingSeconds,
        magnetRemainingSeconds = simState.magnetRemainingSeconds,
        shieldRemainingSeconds = simState.shieldRemainingSeconds,
        doubleCoinsRemainingSeconds = simState.doubleCoinsRemainingSeconds,
        lastPickupEventMessage = simState.lastPickupEventMessage,
        progressionNoticeMessage = latestProgressionNotice,
        saveStatus = walletSnap.saveStatus,
        saveErrorMessage = walletSnap.lastSaveError,
        virtualGear = mixSnap.engineFrame?.virtualGear ?: 1,
        virtualRpm = mixSnap.engineFrame?.virtualRpm ?: engineProfile.idleRpm.toInt(),
        engineProfileName = engineProfile.profileName,
        launchBannerText = mixSnap.launchBannerText,
        audioMuted = walletSnap.audioSettings.muted,
        reducedMotionActive = walletSnap.audioSettings.reducedMotion,
      )
  }

  fun attachSurfaceView(surfaceView: SurfaceView) {
    rendererPort.attachSurfaceView(surfaceView)
  }

  fun detachSurfaceView(surfaceView: SurfaceView) {
    rendererPort.detachSurfaceView(surfaceView)
  }

  override fun onCleared() {
    audioManager.release()
    economyService.flushPending()
    rendererPort.destroy()
    super.onCleared()
  }

  companion object {
    private const val KEY_RECREATED_SAFE_HOME = "drip_race_safe_home_on_process_recreation"
    private const val PERIODIC_FLUSH_INTERVAL_SECONDS = 2.5
    private const val PERIODIC_FLUSH_COIN_THRESHOLD = 5
    private const val MEMORY_CHECKPOINT_INTERVAL_SECONDS = 1.0
    private const val RACE_CHECKPOINT_INTERVAL_SECONDS = 5.0
    private const val DEFAULT_SHOWROOM_YAW_DEG = 36f
    private const val DEFAULT_SHOWROOM_PITCH_DEG = 16f
    private const val DEFAULT_SHOWROOM_DIST_METERS = 6.4f
    const val SEDAN_SPEC_ID = "generic-sedan-mmcworks"
  }
}
