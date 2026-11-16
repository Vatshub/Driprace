package com.example.state

import com.example.assets.HeroModelAssetStatus
import com.example.audio.AudioCatalog
import com.example.audio.AudioMixSnapshot
import com.example.economy.SaveHealthStatus
import com.example.economy.WalletSnapshot
import com.example.garage.HeroVehicleCatalog
import com.example.garage.VehicleUpgradeLevels
import com.example.progression.ProgressionCatalog
import com.example.sim.TrafficEventKind
import com.example.sim.TrafficSimulation
import com.example.traffic.TrafficModelAssetState

sealed interface GamePhase {
  data class Loading(val message: String) : GamePhase

  data class Error(
    val title: String,
    val detail: String,
    val expectedAssetPath: String,
    val expectedSha256: String,
    val actualSha256: String? = null,
    val missingItems: List<String> = emptyList(),
  ) : GamePhase

  data object Home : GamePhase

  data object Garage : GamePhase

  data object Running : GamePhase

  data object Paused : GamePhase

  data class GameOver(
    val finalScore: Int,
    val bestScore: Int,
    val isNewBest: Boolean,
    val distanceMeters: Double,
    val validUniqueOvertakes: Int,
    val validUniqueNearMisses: Int,
    val stageLabel: String,
    val shieldBlocksCount: Int,
    val runCoinsEarned: Int = 0,
    val runCoinPickupsCollected: Int = 0,
    val walletBalance: Long = 0L,
    val saveStatus: SaveHealthStatus = SaveHealthStatus.DURABLE_CONFIRMED,
    val saveWarning: String? = null,
  ) : GamePhase
}

enum class ProgressionTab {
  MISSIONS,
  ACHIEVEMENTS,
  LEVELS,
  DAILY_REWARDS,
  PROFILE,
  SETTINGS,
}

data class ProgressionOverlayState(
  val isOpen: Boolean = false,
  val activeTab: ProgressionTab = ProgressionTab.MISSIONS,
  val statusMessage: String? = null,
)

data class GarageUiState(
  val focusedVehicleId: String = HeroVehicleCatalog.STARTER_ID,
  val previewPaintColorId: String = HeroVehicleCatalog.STARTER_SPEC.defaultPaintId,
  val ownedVehicleIds: Set<String> = setOf(HeroVehicleCatalog.STARTER_ID),
  val equippedVehicleId: String = HeroVehicleCatalog.STARTER_ID,
  val vehiclePaintIds: Map<String, String> =
    mapOf(HeroVehicleCatalog.STARTER_ID to HeroVehicleCatalog.STARTER_SPEC.defaultPaintId),
  val vehicleUpgrades: Map<String, VehicleUpgradeLevels> = emptyMap(),
  val heroAssetStatuses: List<HeroModelAssetStatus> = emptyList(),
  val showroomYawDegrees: Float = 36f,
  val showroomPitchDegrees: Float = 16f,
  val showroomDistanceMeters: Float = 6.4f,
  val pendingConfirmVehicleId: String? = null,
  val statusMessage: String? = null,
) {
  fun upgradesForFocused(): VehicleUpgradeLevels =
    vehicleUpgrades[focusedVehicleId] ?: VehicleUpgradeLevels()

  fun isFocusedOwned(): Boolean = focusedVehicleId in ownedVehicleIds

  fun isFocusedEquipped(): Boolean = focusedVehicleId == equippedVehicleId

  fun statusForFocused(): HeroModelAssetStatus? =
    heroAssetStatuses.firstOrNull { it.vehicleId == focusedVehicleId }
}

enum class VehicleAssetOrigin {
  MISSING,
  BUNDLED_OFFLINE,
  DEV_DOCUMENT_PICKER_PREVIEW,
}

data class AssetDiagnosticStatus(
  val vehicleOrigin: VehicleAssetOrigin = VehicleAssetOrigin.MISSING,
  val vehicleSourceLabel: String = "Not loaded",
  val sha256Verified: Boolean = false,
  val sha256Hex: String? = null,
  val steeringCorrectionStatus: String =
    "Pending prepare-vehicle.mjs inspection (not yet supplied)",
  val normalizedPlacementSummary: String =
    "Ground Y=0, X/Z centered, +Z forward, Z length 4.357386 m",
  val asphaltMapsStatus: String =
    "Fallback procedural road surface (supplied asphalt maps not yet imported)",
  val hdrLightingStatus: String =
    "Daylight HDR fallback active (directional sun + hemisphere IBL; HDRI not yet imported)",
  val isOfflinePackagedReady: Boolean = false,
  val trafficInventorySummary: String = "Traffic pool pending initialization",
  val sedanOnDevicePending: Boolean = true,
  val trafficStates: List<TrafficModelAssetState> = emptyList(),
  val lastTrafficImportNotice: String? = null,
  val heroInventorySummary: String = "5-Car Garage pending verification",
  val heroAssetStatuses: List<HeroModelAssetStatus> = emptyList(),
  val sceneryInventorySummary: String = "Scenery package pending audit",
  val lastSceneryImportNotice: String? = null,
  val audioSummary: String = "Audio package pending audit",
  val hasHardwareVibrator: Boolean = false,
)

data class HudTelemetryState(
  val speedKmh: Double = 0.0,
  val speedMetersPerSecond: Double = 0.0,
  val activeVehicleName: String = HeroVehicleCatalog.STARTER_SPEC.displayName,
  val activeVehicleTopSpeedKmh: Double = HeroVehicleCatalog.STARTER_SPEC.baseTopSpeedKmh,
  val activeEnvironmentName: String = ProgressionCatalog.ENVIRONMENTS.first().displayName,
  val distanceMeters: Double = 0.0,
  val nitroMeter: Double = 100.0,
  val currentLaneIndex: Int = 1,
  val isChangingLane: Boolean = false,
  val isBraking: Boolean = false,
  val isNitroActive: Boolean = false,
  val isAutoNitroActive: Boolean = false,
  val brakeInputHeld: Boolean = false,
  val nitroInputHeld: Boolean = false,
  val fixedStepsExecuted: Long = 0L,
  val totalSimSeconds: Double = 0.0,
  val activeHoldPointerCount: Int = 0,
  val lastInputSummary: String = "Auto-cruise active • Tap/Swipe L/R • Hold Brake/Nitro",
  // Phase 3 authoritative telemetry
  val hearts: Int = TrafficSimulation.MAX_HEARTS,
  val shieldCharges: Int = 0,
  val score: Int = 0,
  val bestScore: Int = 0,
  val validUniqueOvertakes: Int = 0,
  val validUniqueNearMisses: Int = 0,
  val stageNumber: Int = 1,
  val stageLabel: String = "Stage 1 • Highway Warmup",
  val lastTrafficEvent: TrafficEventKind = TrafficEventKind.NONE,
  val lastTrafficEventMessage: String = "",
  val collisionGraceActive: Boolean = false,
  // Phase 4 authoritative wallet, run coin & power-up telemetry
  val walletBalance: Long = 0L,
  val confirmedWalletBalance: Long = 0L,
  val pendingWalletBalance: Long = 0L,
  val runCoinsEarned: Int = 0,
  val runCoinPickupsCollected: Int = 0,
  val qualifiedManualNitroUses: Int = 0,
  val autoNitroRemainingSeconds: Double = 0.0,
  val magnetRemainingSeconds: Double = 0.0,
  val shieldRemainingSeconds: Double = 0.0,
  val doubleCoinsRemainingSeconds: Double = 0.0,
  val lastPickupEventMessage: String = "",
  val progressionNoticeMessage: String = "",
  val saveStatus: SaveHealthStatus = SaveHealthStatus.DURABLE_CONFIRMED,
  val saveErrorMessage: String? = null,
  // Phase 8 Part 8 V2 HUD & Native Audio telemetry (strictly 1-way display, never alters physics)
  val virtualGear: Int = 1,
  val virtualRpm: Int = 850,
  val engineProfileName: String = AudioCatalog.profileForVehicle(HeroVehicleCatalog.STARTER_ID).profileName,
  val launchBannerText: String = "",
  val audioMuted: Boolean = false,
  val reducedMotionActive: Boolean = false,
)

data class DripRaceUiState(
  val phase: GamePhase = GamePhase.Loading("Initializing DRIP RACE 3D engine..."),
  val diagnostics: AssetDiagnosticStatus = AssetDiagnosticStatus(),
  val garage: GarageUiState = GarageUiState(),
  val progressionOverlay: ProgressionOverlayState = ProgressionOverlayState(),
  val walletSnapshot: WalletSnapshot = WalletSnapshot(),
  val audioMixSnapshot: AudioMixSnapshot = AudioMixSnapshot(),
  val activeSceneEnvironmentId: String = ProgressionCatalog.BEGINNER_HIGHWAY_ID,
  val bestScore: Int = 0,
  val walletBalance: Long = 0L,
  val confirmedWalletBalance: Long = 0L,
  val pendingWalletBalance: Long = 0L,
  val saveStatus: SaveHealthStatus = SaveHealthStatus.DURABLE_CONFIRMED,
  val saveWarning: String? = null,
  val transitionCount: Int = 0,
  val viewportSummary: String = "Pending surface",
)
