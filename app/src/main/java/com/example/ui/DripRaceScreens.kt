package com.example.ui

import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MonetizationOn
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Stars
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.filled.TrackChanges
import androidx.compose.material3.IconButton
import com.example.audio.AudioSettingsState
import com.example.economy.SaveHealthStatus
import com.example.economy.WalletSnapshot
import com.example.garage.UpgradeTrack
import com.example.progression.ProgressionCatalog
import com.example.scenery.GraphicsQualityPreset
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.R
import com.example.sim.TrafficEventKind
import com.example.sim.TrafficSimulation
import com.example.state.AssetDiagnosticStatus
import com.example.state.DripRaceUiState
import com.example.state.GamePhase
import com.example.state.HudTelemetryState
import com.example.state.ProgressionTab
import com.example.state.VehicleAssetOrigin
import com.example.ui.theme.DripAlertRed
import com.example.ui.theme.DripElectricBlue
import com.example.ui.theme.DripEmeraldOk
import com.example.ui.theme.DripGoldAccent
import com.example.ui.theme.DripGraphiteBg
import com.example.ui.theme.DripMetallicSilver
import com.example.ui.theme.DripSurfaceDark
import com.example.ui.theme.DripSurfaceElevated
import com.example.ui.theme.DripTextMuted
import com.example.ui.theme.DripTextPrimary
import kotlin.math.abs

@Composable
fun DripRaceRootScreen(
  uiState: DripRaceUiState,
  telemetry: HudTelemetryState,
  onPlay: () -> Unit,
  onPause: () -> Unit,
  onResume: () -> Unit,
  onRestart: () -> Unit,
  onHome: () -> Unit,
  onRetry: () -> Unit,
  onPickGlbPreview: () -> Unit,
  onBackFromRunning: () -> Unit,
  onSteerLeft: () -> Unit,
  onSteerRight: () -> Unit,
  onBrakePointerDown: (Long) -> Unit,
  onBrakePointerUpOrCancel: (Long) -> Unit,
  onNitroPointerDown: (Long) -> Unit,
  onNitroPointerUpOrCancel: (Long) -> Unit,
  onBrakeKeyChanged: (Boolean) -> Unit,
  onNitroKeyChanged: (Boolean) -> Unit,
  onAttachSurface: (SurfaceView) -> Unit,
  onDetachSurface: (SurfaceView) -> Unit,
  render3DSurface: Boolean = true,
  onPlayAgain: () -> Unit = {},
  onImportSedanGlb: () -> Unit = {},
  onOpenGarage: () -> Unit = {},
  onExitGarageToHome: () -> Unit = {},
  onGarageRace: () -> Unit = {},
  onGarageSelectVehicle: (String) -> Unit = {},
  onGarageSelectPaint: (String) -> Unit = {},
  onGarageRequestUnlock: (String) -> Unit = {},
  onGarageConfirmUnlock: (String) -> Unit = {},
  onGarageCancelUnlock: () -> Unit = {},
  onGarageEquip: () -> Unit = {},
  onGarageUpgrade: (UpgradeTrack) -> Unit = {},
  onImportNissanHeroGlb: () -> Unit = {},
  onShowroomOrbitDelta: (Float, Float) -> Unit = { _, _ -> },
  onShowroomZoomDelta: (Float) -> Unit = {},
  onShowroomResetView: () -> Unit = {},
  onOpenProgressionTab: (ProgressionTab) -> Unit = {},
  onSelectProgressionTab: (ProgressionTab) -> Unit = {},
  onCloseProgressionOverlay: () -> Unit = {},
  onClaimDailyMission: (String) -> Unit = {},
  onClaimAchievement: (String) -> Unit = {},
  onClaimDailyLogin: () -> Unit = {},
  onSelectEnvironment: (String) -> Unit = {},
  onSelectGraphicsQuality: (GraphicsQualityPreset) -> Unit = {},
  onUpdateAudioSettings: (AudioSettingsState) -> Unit = {},
  onToggleQuickMute: () -> Unit = {},
  onImportValleySceneryGlb: () -> Unit = {},
  onSyncCalendarClock: () -> Unit = {},
) {
  val phase = uiState.phase

  LaunchedEffect(phase) {
    if (phase is GamePhase.Home || phase is GamePhase.Paused) {
      onSyncCalendarClock()
    }
  }

  BackHandler(
    enabled =
      uiState.progressionOverlay.isOpen ||
        phase is GamePhase.Running ||
        phase is GamePhase.Garage ||
        phase is GamePhase.GameOver
  ) {
    when {
      uiState.progressionOverlay.isOpen -> onCloseProgressionOverlay()
      phase is GamePhase.Running -> onBackFromRunning()
      phase is GamePhase.Garage -> onExitGarageToHome()
      else -> onHome()
    }
  }

  Box(modifier = Modifier.fillMaxSize().background(DripGraphiteBg)) {
    // Keep a single 3D SurfaceView mounted across Home <-> Garage <-> Running <-> Paused <-> GameOver
    val show3dStage =
      render3DSurface &&
        (phase is GamePhase.Home ||
          phase is GamePhase.Garage ||
          phase is GamePhase.Running ||
          phase is GamePhase.Paused ||
          phase is GamePhase.GameOver)
    if (show3dStage) {
      FilamentSceneSurface(
        onAttachSurface = onAttachSurface,
        onDetachSurface = onDetachSurface,
        modifier = Modifier.fillMaxSize(),
      )
    }

    when (phase) {
      is GamePhase.Loading -> {
        LoadingScreen(message = phase.message)
      }
      is GamePhase.Error -> {
        ErrorScreen(
          error = phase,
          onRetry = onRetry,
          onPickGlbPreview = onPickGlbPreview,
        )
      }
      is GamePhase.Home -> {
        HomeOverlay(
          diagnostics = uiState.diagnostics,
          walletSnapshot = uiState.walletSnapshot,
          bestScore = uiState.bestScore,
          walletBalance = uiState.walletBalance,
          pendingWalletBalance = uiState.pendingWalletBalance,
          saveStatus = uiState.saveStatus,
          saveWarning = uiState.saveWarning,
          viewportSummary = uiState.viewportSummary,
          transitionCount = uiState.transitionCount,
          onPlay = onPlay,
          onOpenGarage = onOpenGarage,
          onOpenProgressionTab = onOpenProgressionTab,
          onImportSedanGlb = onImportSedanGlb,
        )
      }
      is GamePhase.Garage -> {
        GarageOverlay(
          garage = uiState.garage,
          confirmedWalletBalance = uiState.confirmedWalletBalance,
          pendingWalletBalance = uiState.pendingWalletBalance,
          saveStatus = uiState.saveStatus,
          onBackToHome = onExitGarageToHome,
          onRace = onGarageRace,
          onSelectVehicle = onGarageSelectVehicle,
          onSelectPaint = onGarageSelectPaint,
          onRequestUnlock = onGarageRequestUnlock,
          onConfirmUnlock = onGarageConfirmUnlock,
          onCancelUnlock = onGarageCancelUnlock,
          onEquip = onGarageEquip,
          onUpgradeTrack = onGarageUpgrade,
          onImportNissanGlb = onImportNissanHeroGlb,
          onShowroomOrbitDelta = onShowroomOrbitDelta,
          onShowroomZoomDelta = onShowroomZoomDelta,
          onShowroomResetView = onShowroomResetView,
        )
      }
      is GamePhase.Running -> {
        RunningOverlay(
          telemetry = telemetry,
          onPause = onPause,
          onToggleQuickMute = onToggleQuickMute,
          onSteerLeft = onSteerLeft,
          onSteerRight = onSteerRight,
          onBrakePointerDown = onBrakePointerDown,
          onBrakePointerUpOrCancel = onBrakePointerUpOrCancel,
          onNitroPointerDown = onNitroPointerDown,
          onNitroPointerUpOrCancel = onNitroPointerUpOrCancel,
          onBrakeKeyChanged = onBrakeKeyChanged,
          onNitroKeyChanged = onNitroKeyChanged,
        )
      }
      is GamePhase.Paused -> {
        PausedOverlay(
          diagnostics = uiState.diagnostics,
          telemetry = telemetry,
          onResume = onResume,
          onRestart = onRestart,
          onHome = onHome,
          onOpenProgression = { onOpenProgressionTab(ProgressionTab.MISSIONS) },
          onOpenSettings = { onOpenProgressionTab(ProgressionTab.SETTINGS) },
        )
      }
      is GamePhase.GameOver -> {
        GameOverOverlay(
          gameOver = phase,
          actionableClaims = uiState.walletSnapshot.totalActionableClaimsCount,
          onPlayAgain = onPlayAgain,
          onHome = onHome,
          onOpenProgression = { onOpenProgressionTab(ProgressionTab.MISSIONS) },
        )
      }
    }

    if (uiState.progressionOverlay.isOpen) {
      ProgressionHubOverlay(
        overlayState = uiState.progressionOverlay,
        walletSnapshot = uiState.walletSnapshot,
        activeSceneEnvironmentId = uiState.activeSceneEnvironmentId,
        raceInProgress = phase is GamePhase.Paused || phase is GamePhase.Running,
        onSelectTab = onSelectProgressionTab,
        onClose = onCloseProgressionOverlay,
        onClaimMission = onClaimDailyMission,
        onClaimAchievement = onClaimAchievement,
        onClaimDailyLogin = onClaimDailyLogin,
        onSelectEnvironment = onSelectEnvironment,
        scenerySummary = uiState.diagnostics.sceneryInventorySummary,
        audioSummary = uiState.diagnostics.audioSummary,
        hasHardwareVibrator = uiState.diagnostics.hasHardwareVibrator,
        audioMixSnapshot = uiState.audioMixSnapshot,
        onSelectGraphicsQuality = onSelectGraphicsQuality,
        onUpdateAudioSettings = onUpdateAudioSettings,
        onImportValleySceneryGlb = onImportValleySceneryGlb,
      )
    }
  }
}

@Composable
private fun FilamentSceneSurface(
  onAttachSurface: (SurfaceView) -> Unit,
  onDetachSurface: (SurfaceView) -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val surfaceView = remember { SurfaceView(context) }

  DisposableEffect(surfaceView) {
    onAttachSurface(surfaceView)
    onDispose { onDetachSurface(surfaceView) }
  }

  AndroidView(factory = { surfaceView }, modifier = modifier)
}

@Composable
private fun BrandingHeader(
  modifier: Modifier = Modifier,
  centered: Boolean = true,
  compact: Boolean = false,
) {
  Column(
    modifier = modifier,
    horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start,
  ) {
    Text(
      text = stringResource(R.string.brand_title),
      style = if (compact) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.displaySmall,
      color = DripTextPrimary,
      textAlign = if (centered) TextAlign.Center else TextAlign.Start,
    )
    Spacer(modifier = Modifier.height(if (compact) 1.dp else 4.dp))
    Text(
      text = stringResource(R.string.brand_subtitle),
      style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodyMedium,
      color = DripMetallicSilver,
      textAlign = if (centered) TextAlign.Center else TextAlign.Start,
    )
  }
}

@Composable
private fun LoadingScreen(message: String) {
  Box(
    modifier =
      Modifier.fillMaxSize()
        .background(
          Brush.verticalGradient(listOf(DripGraphiteBg, DripSurfaceDark, DripGraphiteBg))
        )
        .windowInsetsPadding(WindowInsets.safeDrawing)
        .padding(24.dp),
    contentAlignment = Alignment.Center,
  ) {
    Column(
      modifier = Modifier.widthIn(max = 520.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
      BrandingHeader(centered = true)
      CircularProgressIndicator(
        color = DripElectricBlue,
        trackColor = DripSurfaceElevated,
        modifier = Modifier.size(48.dp).testTag("loading_indicator"),
      )
      Text(
        text = message,
        style = MaterialTheme.typography.bodyMedium,
        color = DripTextMuted,
        textAlign = TextAlign.Center,
      )
    }
  }
}

@Composable
private fun ErrorScreen(
  error: GamePhase.Error,
  onRetry: () -> Unit,
  onPickGlbPreview: () -> Unit,
) {
  Box(
    modifier =
      Modifier.fillMaxSize()
        .background(
          Brush.verticalGradient(listOf(DripGraphiteBg, Color(0xFF141016), DripGraphiteBg))
        )
        .windowInsetsPadding(WindowInsets.safeDrawing)
        .padding(20.dp),
    contentAlignment = Alignment.Center,
  ) {
    Column(
      modifier =
        Modifier.widthIn(max = 600.dp)
          .fillMaxWidth()
          .verticalScroll(rememberScrollState()),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      BrandingHeader(centered = true)

      Card(
        colors = CardDefaults.cardColors(containerColor = DripSurfaceDark),
        shape = RoundedCornerShape(16.dp),
        modifier =
          Modifier.fillMaxWidth().border(1.dp, DripAlertRed.copy(alpha = 0.55f), RoundedCornerShape(16.dp)),
      ) {
        Column(
          modifier = Modifier.padding(20.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            Icon(
              imageVector = Icons.Filled.ErrorOutline,
              contentDescription = stringResource(R.string.cd_error_icon),
              tint = DripAlertRed,
            )
            Text(
              text = error.title,
              style = MaterialTheme.typography.headlineSmall,
              color = DripTextPrimary,
            )
          }

          Text(
            text = error.detail,
            style = MaterialTheme.typography.bodyMedium,
            color = DripMetallicSilver,
          )

          Surface(
            color = DripSurfaceElevated,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth(),
          ) {
            Column(
              modifier = Modifier.padding(12.dp),
              verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
              Text(
                text = "Expected bundled path: ${error.expectedAssetPath}",
                style = MaterialTheme.typography.labelMedium,
                color = DripElectricBlue,
              )
              Text(
                text = "Expected SHA-256:\n${error.expectedSha256}",
                style = MaterialTheme.typography.labelMedium,
                color = DripTextPrimary,
              )
              if (error.actualSha256 != null) {
                Text(
                  text = "Actual SHA-256:\n${error.actualSha256}",
                  style = MaterialTheme.typography.labelMedium,
                  color = DripAlertRed,
                )
              }
              if (error.missingItems.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                  text = "Unmet workspace files:",
                  style = MaterialTheme.typography.labelMedium,
                  color = DripGoldAccent,
                )
                error.missingItems.forEach { item ->
                  Text(
                    text = "• $item",
                    style = MaterialTheme.typography.labelMedium,
                    color = DripTextMuted,
                  )
                }
              }
            }
          }

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            Button(
              onClick = onRetry,
              modifier = Modifier.weight(1f).minimumInteractiveComponentSize().testTag("retry_button"),
              colors = ButtonDefaults.buttonColors(containerColor = DripElectricBlue),
            ) {
              Icon(Icons.Filled.Refresh, contentDescription = null)
              Spacer(modifier = Modifier.width(8.dp))
              Text(stringResource(R.string.action_retry))
            }
          }

          OutlinedButton(
            onClick = onPickGlbPreview,
            modifier =
              Modifier.fillMaxWidth()
                .minimumInteractiveComponentSize()
                .testTag("pick_glb_preview_button"),
          ) {
            Icon(Icons.Filled.FolderOpen, contentDescription = null, tint = DripGoldAccent)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
              text = stringResource(R.string.action_dev_pick_glb),
              color = DripGoldAccent,
            )
          }
          Text(
            text = stringResource(R.string.note_dev_pick_not_offline),
            style = MaterialTheme.typography.labelMedium,
            color = DripTextMuted,
          )
        }
      }
    }
  }
}

@Composable
private fun HomeOverlay(
  diagnostics: AssetDiagnosticStatus,
  walletSnapshot: WalletSnapshot,
  bestScore: Int,
  walletBalance: Long,
  pendingWalletBalance: Long,
  saveStatus: SaveHealthStatus,
  saveWarning: String?,
  viewportSummary: String,
  transitionCount: Int,
  onPlay: () -> Unit,
  onOpenGarage: () -> Unit,
  onOpenProgressionTab: (ProgressionTab) -> Unit,
  onImportSedanGlb: () -> Unit,
) {
  val selectedEnvName =
    ProgressionCatalog.findEnvironment(walletSnapshot.selectedEnvironmentId)?.displayName
      ?: ProgressionCatalog.ENVIRONMENTS.first().displayName

  BoxWithConstraints(
    modifier =
      Modifier.fillMaxSize()
        .background(
          Brush.verticalGradient(
            listOf(
              DripGraphiteBg.copy(alpha = 0.78f),
              Color.Transparent,
              DripGraphiteBg.copy(alpha = 0.88f),
            )
          )
        )
        .windowInsetsPadding(WindowInsets.safeDrawing)
  ) {
    val isLandscape = maxWidth > maxHeight
    val isCompactHeight = !isLandscape && maxHeight < 580.dp
    if (isLandscape) {
      Row(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
      ) {
        Column(
          modifier = Modifier.weight(1f).fillMaxHeight(),
          verticalArrangement = Arrangement.SpaceBetween,
        ) {
          Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            BrandingHeader(centered = false)
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
              Text(
                text = "BEST SCORE: $bestScore",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = DripGoldAccent,
                modifier = Modifier.testTag("home_best_score_text"),
              )
              Text(
                text = if (pendingWalletBalance > 0L) "WALLET: $walletBalance (UNSAVED +$pendingWalletBalance)" else "WALLET: $walletBalance",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = DripEmeraldOk,
                modifier = Modifier.testTag("home_wallet_text"),
              )
            }
            Text(
              text = "NEXT RACE LEVEL: $selectedEnvName",
              style = MaterialTheme.typography.labelLarge,
              fontWeight = FontWeight.Bold,
              color = DripElectricBlue,
              modifier = Modifier.testTag("home_environment_text"),
            )
            if (saveWarning != null || saveStatus == SaveHealthStatus.WRITE_FAILED_UNCONFIRMED) {
              Text(
                text = saveWarning ?: "Save warning active",
                style = MaterialTheme.typography.labelMedium,
                color = DripAlertRed,
                modifier = Modifier.testTag("home_save_warning_text"),
              )
            }
          }
          Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            HomeProgressionBar(
              walletSnapshot = walletSnapshot,
              onOpenProgressionTab = onOpenProgressionTab,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
              Button(
                onClick = onPlay,
                modifier =
                  Modifier.widthIn(min = 160.dp)
                    .minimumInteractiveComponentSize()
                    .testTag("play_button"),
                colors = ButtonDefaults.buttonColors(containerColor = DripElectricBlue),
              ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.action_play), fontWeight = FontWeight.Bold)
              }
              FilledTonalButton(
                onClick = onOpenGarage,
                modifier =
                  Modifier.widthIn(min = 140.dp)
                    .minimumInteractiveComponentSize()
                    .testTag("garage_button"),
              ) {
                Icon(Icons.Filled.DirectionsCar, contentDescription = null, tint = DripGoldAccent)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.action_garage), fontWeight = FontWeight.Bold)
              }
            }
          }
        }
        Spacer(modifier = Modifier.width(16.dp))
        DiagnosticStatusCard(
          diagnostics = diagnostics,
          viewportSummary = viewportSummary,
          transitionCount = transitionCount,
          onImportSedanGlb = onImportSedanGlb,
          modifier = Modifier.widthIn(max = 380.dp),
        )
      }
    } else {
      val outerPadH = if (isCompactHeight) 14.dp else 20.dp
      val outerPadV = if (isCompactHeight) 6.dp else 20.dp
      val bottomSpacing = if (isCompactHeight) 4.dp else 10.dp
      val actionButtonHeight = if (isCompactHeight) 48.dp else 56.dp
      Column(
        modifier =
          Modifier.fillMaxSize()
            .padding(horizontal = outerPadH, vertical = outerPadV)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
      ) {
        Column(
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.spacedBy(if (isCompactHeight) 2.dp else 4.dp),
          modifier = Modifier.padding(top = if (isCompactHeight) 0.dp else 8.dp),
        ) {
          BrandingHeader(centered = true, compact = isCompactHeight)
          Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
              text = "BEST SCORE: $bestScore",
              style = if (isCompactHeight) MaterialTheme.typography.labelLarge else MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.Bold,
              color = DripGoldAccent,
              modifier = Modifier.testTag("home_best_score_text"),
            )
            Text(
              text = if (pendingWalletBalance > 0L) "WALLET: $walletBalance (UNSAVED +$pendingWalletBalance)" else "WALLET: $walletBalance",
              style = if (isCompactHeight) MaterialTheme.typography.labelLarge else MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.Bold,
              color = DripEmeraldOk,
              modifier = Modifier.testTag("home_wallet_text"),
            )
          }
          Text(
            text = "NEXT RACE LEVEL: $selectedEnvName",
            style = if (isCompactHeight) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = DripElectricBlue,
            modifier = Modifier.testTag("home_environment_text"),
          )
          if (saveWarning != null || saveStatus == SaveHealthStatus.WRITE_FAILED_UNCONFIRMED) {
            Text(
              text = saveWarning ?: "Save warning active",
              style = MaterialTheme.typography.labelMedium,
              color = DripAlertRed,
              modifier = Modifier.testTag("home_save_warning_text"),
            )
          }
        }

        Column(
          modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth(),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.spacedBy(bottomSpacing),
        ) {
          DiagnosticStatusCard(
            diagnostics = diagnostics,
            viewportSummary = viewportSummary,
            transitionCount = transitionCount,
            onImportSedanGlb = onImportSedanGlb,
            compact = isCompactHeight,
            modifier = Modifier.fillMaxWidth(),
          )
          HomeProgressionBar(
            walletSnapshot = walletSnapshot,
            onOpenProgressionTab = onOpenProgressionTab,
          )
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            FilledTonalButton(
              onClick = onOpenGarage,
              modifier =
                Modifier.weight(1f)
                  .height(actionButtonHeight)
                  .minimumInteractiveComponentSize()
                  .testTag("garage_button"),
            ) {
              Icon(Icons.Filled.DirectionsCar, contentDescription = null, tint = DripGoldAccent)
              Spacer(modifier = Modifier.width(6.dp))
              Text(stringResource(R.string.action_garage), fontWeight = FontWeight.Bold)
            }
            Button(
              onClick = onPlay,
              modifier =
                Modifier.weight(1.35f)
                  .height(actionButtonHeight)
                  .minimumInteractiveComponentSize()
                  .testTag("play_button"),
              colors = ButtonDefaults.buttonColors(containerColor = DripElectricBlue),
            ) {
              Icon(Icons.Filled.PlayArrow, contentDescription = null)
              Spacer(modifier = Modifier.width(8.dp))
              Text(stringResource(R.string.action_play), fontWeight = FontWeight.Bold)
            }
          }
        }
      }
    }
  }
}

@Composable
private fun HomeProgressionBar(
  walletSnapshot: WalletSnapshot,
  onOpenProgressionTab: (ProgressionTab) -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    HomeNavChip(
      label =
        if (walletSnapshot.claimableMissionsCount > 0) {
          "Missions(${walletSnapshot.claimableMissionsCount})"
        } else {
          stringResource(R.string.action_missions)
        },
      highlight = walletSnapshot.claimableMissionsCount > 0,
      tag = "missions_button",
      modifier = Modifier.weight(1f),
      onClick = { onOpenProgressionTab(ProgressionTab.MISSIONS) },
    )
    HomeNavChip(
      label =
        if (walletSnapshot.claimableAchievementsCount > 0) {
          "Trophies(${walletSnapshot.claimableAchievementsCount})"
        } else {
          stringResource(R.string.action_achievements)
        },
      highlight = walletSnapshot.claimableAchievementsCount > 0,
      tag = "achievements_button",
      modifier = Modifier.weight(1f),
      onClick = { onOpenProgressionTab(ProgressionTab.ACHIEVEMENTS) },
    )
    HomeNavChip(
      label = stringResource(R.string.action_levels),
      highlight = false,
      tag = "levels_button",
      modifier = Modifier.weight(1f),
      onClick = { onOpenProgressionTab(ProgressionTab.LEVELS) },
    )
    HomeNavChip(
      label =
        if (walletSnapshot.dailyLogin?.canClaimToday == true) "Daily(!)"
        else stringResource(R.string.action_daily_rewards),
      highlight = walletSnapshot.dailyLogin?.canClaimToday == true,
      tag = "daily_rewards_button",
      modifier = Modifier.weight(1f),
      onClick = { onOpenProgressionTab(ProgressionTab.DAILY_REWARDS) },
    )
    HomeNavChip(
      label = stringResource(R.string.action_profile),
      highlight = false,
      tag = "profile_button",
      modifier = Modifier.weight(1f),
      onClick = { onOpenProgressionTab(ProgressionTab.PROFILE) },
    )
    HomeNavChip(
      label = stringResource(R.string.action_settings),
      highlight = walletSnapshot.audioSettings.muted,
      tag = "settings_button",
      modifier = Modifier.weight(1f),
      onClick = { onOpenProgressionTab(ProgressionTab.SETTINGS) },
    )
  }
}

@Composable
private fun HomeNavChip(
  label: String,
  highlight: Boolean,
  tag: String,
  modifier: Modifier = Modifier,
  onClick: () -> Unit,
) {
  OutlinedButton(
    onClick = onClick,
    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 2.dp, vertical = 6.dp),
    modifier = modifier.minimumInteractiveComponentSize().testTag(tag),
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.labelSmall,
      fontWeight = FontWeight.Bold,
      color = if (highlight) DripGoldAccent else DripTextPrimary,
      maxLines = 1,
    )
  }
}

@Composable
private fun DiagnosticStatusCard(
  diagnostics: AssetDiagnosticStatus,
  viewportSummary: String,
  transitionCount: Int,
  onImportSedanGlb: () -> Unit,
  compact: Boolean = false,
  modifier: Modifier = Modifier,
) {
  Card(
    modifier = modifier,
    colors = CardDefaults.cardColors(containerColor = DripSurfaceDark.copy(alpha = 0.88f)),
    shape = RoundedCornerShape(14.dp),
  ) {
    Column(
      modifier = Modifier.padding(if (compact) 8.dp else 12.dp),
      verticalArrangement = Arrangement.spacedBy(if (compact) 2.dp else 3.dp),
    ) {
      val originColor =
        if (diagnostics.vehicleOrigin == VehicleAssetOrigin.BUNDLED_OFFLINE) {
          DripEmeraldOk
        } else {
          DripGoldAccent
        }
      Text(
        text = "Phase 8 Part 8 V2 HUD & Audio • ${diagnostics.vehicleSourceLabel}",
        style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
        color = originColor,
        maxLines = 1,
      )
      Text(
        text = diagnostics.heroInventorySummary,
        style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
        color = DripTextPrimary,
        maxLines = 1,
        modifier = Modifier.testTag("home_hero_inventory_text"),
      )
      Text(
        text = diagnostics.trafficInventorySummary,
        style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
        color = DripMetallicSilver,
        maxLines = 1,
        modifier = Modifier.testTag("traffic_inventory_text"),
      )
      diagnostics.lastTrafficImportNotice?.let { notice ->
        Text(
          text = notice,
          style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
          color = DripGoldAccent,
          maxLines = 1,
          modifier = Modifier.testTag("traffic_import_notice_text"),
        )
      }
      if (diagnostics.sedanOnDevicePending ||
        diagnostics.trafficStates.any { !it.sha256Verified }
      ) {
        OutlinedButton(
          onClick = onImportSedanGlb,
          contentPadding =
            if (compact) androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp)
            else ButtonDefaults.ContentPadding,
          modifier =
            Modifier.fillMaxWidth()
              .padding(top = if (compact) 1.dp else 2.dp)
              .minimumInteractiveComponentSize()
              .testTag("import_sedan_glb_button"),
        ) {
          Icon(Icons.Filled.FolderOpen, contentDescription = null, tint = DripGoldAccent)
          Spacer(modifier = Modifier.width(6.dp))
          Text(
            text = stringResource(R.string.action_import_sedan_glb),
            color = DripGoldAccent,
            maxLines = 1,
          )
        }
      }
      Text(
        text = "${diagnostics.sceneryInventorySummary} • ${diagnostics.audioSummary} • ${diagnostics.asphaltMapsStatus}",
        style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
        color = DripTextMuted,
        maxLines = 1,
        modifier = Modifier.testTag("home_scenery_inventory_text"),
      )
      if (!compact) {
        Text(
          text = "${diagnostics.audioSummary} • Viewport: $viewportSummary • T:$transitionCount",
          style = MaterialTheme.typography.labelMedium,
          color = DripElectricBlue,
          maxLines = 1,
          modifier = Modifier.testTag("home_audio_inventory_text"),
        )
      }
    }
  }
}

@Composable
private fun RunningOverlay(
  telemetry: HudTelemetryState,
  onPause: () -> Unit,
  onToggleQuickMute: () -> Unit,
  onSteerLeft: () -> Unit,
  onSteerRight: () -> Unit,
  onBrakePointerDown: (Long) -> Unit,
  onBrakePointerUpOrCancel: (Long) -> Unit,
  onNitroPointerDown: (Long) -> Unit,
  onNitroPointerUpOrCancel: (Long) -> Unit,
  onBrakeKeyChanged: (Boolean) -> Unit,
  onNitroKeyChanged: (Boolean) -> Unit,
) {
  val focusRequester = remember { FocusRequester() }
  val swipeThresholdPx = with(LocalDensity.current) { 36.dp.toPx() }

  LaunchedEffect(Unit) {
    runCatching { focusRequester.requestFocus() }
  }

  BoxWithConstraints(
    modifier =
      Modifier.fillMaxSize()
        .focusRequester(focusRequester)
        .focusable()
        .onPreviewKeyEvent { event ->
          val repeat = event.nativeKeyEvent.repeatCount
          when (event.key) {
            Key.DirectionLeft, Key.A -> {
              if (event.type == KeyEventType.KeyDown && repeat == 0) {
                onSteerLeft()
              }
              true
            }
            Key.DirectionRight, Key.D -> {
              if (event.type == KeyEventType.KeyDown && repeat == 0) {
                onSteerRight()
              }
              true
            }
            Key.DirectionDown, Key.S -> {
              onBrakeKeyChanged(event.type == KeyEventType.KeyDown)
              true
            }
            Key.Spacebar -> {
              onNitroKeyChanged(event.type == KeyEventType.KeyDown)
              true
            }
            Key.M -> {
              if (event.type == KeyEventType.KeyDown && repeat == 0) {
                onToggleQuickMute()
              }
              true
            }
            Key.Escape, Key.P -> {
              if (event.type == KeyEventType.KeyDown && repeat == 0) {
                onPause()
              }
              true
            }
            else -> false
          }
        }
  ) {
    val isCompactWidth = maxWidth < 380.dp
    val isCompactHeight = maxHeight < 540.dp

    // 1. Central swipe surface behind top HUD and bottom touch buttons so button taps never swipe
    Box(
      modifier =
        Modifier.fillMaxSize()
          .testTag("running_swipe_surface")
          .pointerInput(swipeThresholdPx) {
            val startPositions = mutableMapOf<Long, Pair<Float, Float>>()
            val triggeredSwipes = mutableSetOf<Long>()
            awaitPointerEventScope {
              while (true) {
                val event = awaitPointerEvent()
                when (event.type) {
                  PointerEventType.Press -> {
                    event.changes.forEach { change ->
                      if (change.pressed && !change.previousPressed) {
                        startPositions[change.id.value] = change.position.x to change.position.y
                      }
                    }
                  }
                  PointerEventType.Move -> {
                    event.changes.forEach { change ->
                      val id = change.id.value
                      if (change.pressed && id !in triggeredSwipes) {
                        val origin = startPositions[id]
                        if (origin != null) {
                          val dx = change.position.x - origin.first
                          val dy = change.position.y - origin.second
                          if (abs(dx) >= swipeThresholdPx && abs(dx) > abs(dy)) {
                            triggeredSwipes += id
                            if (dx > 0f) onSteerRight() else onSteerLeft()
                          }
                        }
                      }
                    }
                  }
                  PointerEventType.Release, PointerEventType.Exit -> {
                    event.changes.forEach { change ->
                      if (!change.pressed) {
                        startPositions.remove(change.id.value)
                        triggeredSwipes.remove(change.id.value)
                      }
                    }
                  }
                  else -> {
                    event.changes.forEach { change ->
                      if (!change.pressed) {
                        startPositions.remove(change.id.value)
                        triggeredSwipes.remove(change.id.value)
                      }
                    }
                  }
                }
              }
            }
          }
    )

    // 2. Part 8 V2 Premium HUD & Driving Controls inside safeDrawing (cutout & gesture-bar safe)
    //    Keeps the entire mid-screen 3-lane highway horizon clear so approaching traffic is never obstructed.
    Column(
      modifier =
        Modifier.fillMaxSize()
          .windowInsetsPadding(WindowInsets.safeDrawing)
          .padding(horizontal = if (isCompactWidth) 10.dp else 16.dp, vertical = if (isCompactHeight) 8.dp else 12.dp),
      verticalArrangement = Arrangement.SpaceBetween,
    ) {
      // TOP PERIMETER: Split left/right glass pods + non-blocking top-center transient strip
      Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.Top,
        ) {
          // Top-Left Race Telemetry Pod (Hearts, Score, Distance, Wallet/Run Coins, Stage/Car/Env)
          Surface(
            color = DripSurfaceDark.copy(alpha = 0.80f),
            shape = RoundedCornerShape(12.dp),
            modifier =
              Modifier.weight(1f, fill = false)
                .widthIn(max = 320.dp)
                .border(1.dp, DripMetallicSilver.copy(alpha = 0.18f), RoundedCornerShape(12.dp)),
          ) {
            Column(
              modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
              verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Row(
                  modifier = Modifier.testTag("hud_hearts_row"),
                  horizontalArrangement = Arrangement.spacedBy(3.dp),
                  verticalAlignment = Alignment.CenterVertically,
                ) {
                  for (i in 1..TrafficSimulation.MAX_HEARTS) {
                    val full = i <= telemetry.hearts
                    Icon(
                      imageVector =
                        if (full) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                      contentDescription =
                        stringResource(if (full) R.string.cd_heart_full else R.string.cd_heart_empty),
                      tint = if (full) DripAlertRed else DripTextMuted,
                      modifier = Modifier.size(16.dp),
                    )
                  }
                  if (telemetry.shieldCharges > 0) {
                    Icon(
                      imageVector = Icons.Filled.Shield,
                      contentDescription = "Shield active",
                      tint = DripElectricBlue,
                      modifier = Modifier.size(16.dp).testTag("hud_shield_badge"),
                    )
                  }
                }

                Row(
                  horizontalArrangement = Arrangement.spacedBy(8.dp),
                  verticalAlignment = Alignment.CenterVertically,
                ) {
                  Text(
                    text = "SCORE ${telemetry.score}",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Black,
                    color = DripGoldAccent,
                    modifier = Modifier.testTag("hud_score_text"),
                  )
                  Text(
                    text = "${telemetry.distanceMeters.toInt()} m",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = DripTextPrimary,
                    modifier = Modifier.testTag("hud_distance_text"),
                  )
                }
              }

              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Row(
                  horizontalArrangement = Arrangement.spacedBy(3.dp),
                  verticalAlignment = Alignment.CenterVertically,
                ) {
                  Icon(
                    imageVector = Icons.Filled.MonetizationOn,
                    contentDescription = null,
                    tint = DripGoldAccent,
                    modifier = Modifier.size(14.dp),
                  )
                  Text(
                    text =
                      if (telemetry.saveStatus == SaveHealthStatus.WRITE_FAILED_UNCONFIRMED) {
                        "WALLET ${telemetry.walletBalance} (UNCONFIRMED)"
                      } else {
                        "WALLET ${telemetry.walletBalance}"
                      },
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color =
                      if (telemetry.saveStatus == SaveHealthStatus.WRITE_FAILED_UNCONFIRMED) {
                        DripAlertRed
                      } else {
                        DripGoldAccent
                      },
                    modifier = Modifier.testTag("hud_wallet_text"),
                  )
                }

                Text(
                  text = "RUN +${telemetry.runCoinsEarned} (${telemetry.runCoinPickupsCollected})",
                  style = MaterialTheme.typography.labelSmall,
                  fontWeight = FontWeight.Bold,
                  color = DripEmeraldOk,
                  modifier = Modifier.testTag("hud_run_coins_text"),
                )
              }

              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Row(
                  modifier = Modifier.weight(1f, fill = false),
                  verticalAlignment = Alignment.CenterVertically,
                ) {
                  Text(
                    text = telemetry.activeVehicleName,
                    style = MaterialTheme.typography.labelSmall,
                    color = DripMetallicSilver,
                    maxLines = 1,
                    modifier = Modifier.testTag("hud_active_car_text"),
                  )
                  Text(
                    text = " • ${telemetry.activeEnvironmentName}",
                    style = MaterialTheme.typography.labelSmall,
                    color = DripElectricBlue,
                    maxLines = 1,
                    modifier = Modifier.testTag("hud_active_environment_text"),
                  )
                }
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                  text = telemetry.stageLabel,
                  style = MaterialTheme.typography.labelSmall,
                  color = DripElectricBlue,
                  maxLines = 1,
                  modifier = Modifier.testTag("hud_stage_text"),
                )
              }
            }
          }

          Spacer(modifier = Modifier.width(6.dp))

          // Top-Right Action Cluster: Quick Mute + Pause
          Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Surface(
              color = DripSurfaceDark.copy(alpha = 0.82f),
              shape = RoundedCornerShape(12.dp),
              modifier = Modifier.border(1.dp, DripMetallicSilver.copy(alpha = 0.2f), RoundedCornerShape(12.dp)),
            ) {
              IconButton(
                onClick = onToggleQuickMute,
                modifier = Modifier.minimumInteractiveComponentSize().testTag("hud_mute_button"),
              ) {
                Icon(
                  imageVector =
                    if (telemetry.audioMuted) Icons.AutoMirrored.Filled.VolumeOff
                    else Icons.AutoMirrored.Filled.VolumeUp,
                  contentDescription =
                    stringResource(
                      if (telemetry.audioMuted) R.string.cd_unmute_audio else R.string.cd_mute_audio
                    ),
                  tint = if (telemetry.audioMuted) DripAlertRed else DripTextPrimary,
                )
              }
            }

            FilledTonalButton(
              onClick = onPause,
              contentPadding =
                if (isCompactWidth) androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                else ButtonDefaults.ContentPadding,
              modifier = Modifier.minimumInteractiveComponentSize().testTag("pause_button"),
            ) {
              Icon(
                imageVector = Icons.Filled.Pause,
                contentDescription = stringResource(R.string.cd_pause),
              )
              if (!isCompactWidth) {
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.action_pause))
              }
            }
          }
        }

        // Non-blocking Top-Center Transient Pills (3-2-1-GO!, Active Power-Ups, Events)
        if (telemetry.launchBannerText.isNotEmpty()) {
          val isGo = telemetry.launchBannerText == "GO!"
          Surface(
            color = DripSurfaceDark.copy(alpha = 0.88f),
            shape = RoundedCornerShape(12.dp),
            modifier =
              Modifier.border(
                  1.5.dp,
                  if (isGo) DripEmeraldOk else DripGoldAccent,
                  RoundedCornerShape(12.dp),
                )
                .testTag("hud_launch_banner"),
          ) {
            Text(
              text = telemetry.launchBannerText,
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.Black,
              color = if (isGo) DripEmeraldOk else DripGoldAccent,
              modifier = Modifier.padding(horizontal = 16.dp, vertical = 3.dp),
            )
          }
        }

        val hasAnyPowerUp =
          telemetry.autoNitroRemainingSeconds > 0.0 ||
            telemetry.magnetRemainingSeconds > 0.0 ||
            telemetry.shieldRemainingSeconds > 0.0 ||
            telemetry.doubleCoinsRemainingSeconds > 0.0
        if (hasAnyPowerUp) {
          Row(
            modifier = Modifier.testTag("hud_powerups_row"),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            if (telemetry.autoNitroRemainingSeconds > 0.0) {
              PowerUpTimerChip(
                icon = Icons.Filled.Bolt,
                label = "AUTO %.1fs".format(telemetry.autoNitroRemainingSeconds),
                tint = DripGoldAccent,
                tag = "hud_powerup_auto_nitro",
              )
            }
            if (telemetry.magnetRemainingSeconds > 0.0) {
              PowerUpTimerChip(
                icon = Icons.Filled.TrackChanges,
                label = "MAG %.1fs".format(telemetry.magnetRemainingSeconds),
                tint = DripAlertRed,
                tag = "hud_powerup_magnet",
              )
            }
            if (telemetry.shieldRemainingSeconds > 0.0) {
              PowerUpTimerChip(
                icon = Icons.Filled.Shield,
                label = "SHD %.1fs".format(telemetry.shieldRemainingSeconds),
                tint = DripElectricBlue,
                tag = "hud_powerup_shield",
              )
            }
            if (telemetry.doubleCoinsRemainingSeconds > 0.0) {
              PowerUpTimerChip(
                icon = Icons.Filled.Stars,
                label = "2X %.1fs".format(telemetry.doubleCoinsRemainingSeconds),
                tint = DripEmeraldOk,
                tag = "hud_powerup_double_coins",
              )
            }
          }
        }

        val hasAnyTransientFeed =
          telemetry.isBraking ||
            telemetry.isNitroActive ||
            telemetry.collisionGraceActive ||
            telemetry.lastPickupEventMessage.isNotEmpty() ||
            (telemetry.lastTrafficEvent != TrafficEventKind.NONE && telemetry.lastTrafficEventMessage.isNotEmpty()) ||
            telemetry.progressionNoticeMessage.isNotEmpty()
        if (hasAnyTransientFeed) {
          Surface(
            color = DripSurfaceDark.copy(alpha = if (telemetry.reducedMotionActive) 0.90f else 0.78f),
            shape = RoundedCornerShape(10.dp),
          ) {
            Column(
              modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
              horizontalAlignment = Alignment.CenterHorizontally,
              verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
              if (telemetry.isBraking || telemetry.isNitroActive || telemetry.collisionGraceActive) {
                val badgeText =
                  when {
                    telemetry.collisionGraceActive -> "GRACE WINDOW"
                    telemetry.isBraking -> "BRAKE ACTIVE"
                    telemetry.isAutoNitroActive -> "AUTO NITRO"
                    else -> "NITRO BOOST"
                  }
                val badgeColor =
                  when {
                    telemetry.collisionGraceActive -> DripElectricBlue
                    telemetry.isBraking -> DripAlertRed
                    else -> DripGoldAccent
                  }
                Text(
                  text = badgeText,
                  style = MaterialTheme.typography.labelSmall,
                  fontWeight = FontWeight.Bold,
                  color = badgeColor,
                  modifier = Modifier.testTag("hud_status_badge"),
                )
              }

              if (telemetry.lastPickupEventMessage.isNotEmpty()) {
                Text(
                  text = telemetry.lastPickupEventMessage,
                  style = MaterialTheme.typography.labelSmall,
                  fontWeight = FontWeight.Bold,
                  color = DripGoldAccent,
                  maxLines = 1,
                  modifier = Modifier.testTag("hud_pickup_event_text"),
                )
              }

              if (telemetry.lastTrafficEvent != TrafficEventKind.NONE &&
                telemetry.lastTrafficEventMessage.isNotEmpty()
              ) {
                Text(
                  text = telemetry.lastTrafficEventMessage,
                  style = MaterialTheme.typography.labelSmall,
                  fontWeight = FontWeight.Bold,
                  color =
                    when (telemetry.lastTrafficEvent) {
                      TrafficEventKind.IMPACT, TrafficEventKind.GAME_OVER -> DripAlertRed
                      TrafficEventKind.SHIELD_BLOCKED -> DripElectricBlue
                      else -> DripEmeraldOk
                    },
                  maxLines = 1,
                  modifier = Modifier.testTag("hud_event_badge"),
                )
              }

              if (telemetry.progressionNoticeMessage.isNotEmpty()) {
                Text(
                  text = telemetry.progressionNoticeMessage,
                  style = MaterialTheme.typography.labelSmall,
                  fontWeight = FontWeight.Bold,
                  color = DripEmeraldOk,
                  maxLines = 1,
                  modifier = Modifier.testTag("hud_progression_notice_text"),
                )
              }
            }
          }
        }
      }

      // BOTTOM PERIMETER: Compact Speed/Nitro/Lane Cockpit Strip + Multi-Touch Controls
      Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(if (isCompactHeight) 5.dp else 8.dp),
      ) {
        Surface(
          color = DripSurfaceDark.copy(alpha = 0.84f),
          shape = RoundedCornerShape(12.dp),
          modifier =
            Modifier.widthIn(max = 460.dp)
              .fillMaxWidth()
              .border(1.dp, DripMetallicSilver.copy(alpha = 0.18f), RoundedCornerShape(12.dp)),
        ) {
          Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
          ) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Text(
                  text = "${telemetry.speedKmh.toInt()} km/h",
                  style = MaterialTheme.typography.titleMedium,
                  fontWeight = FontWeight.Black,
                  color = if (telemetry.isNitroActive) DripGoldAccent else DripTextPrimary,
                  modifier = Modifier.testTag("hud_speed_text"),
                )
                Text(
                  text = "G${telemetry.virtualGear} • ${telemetry.virtualRpm} RPM",
                  style = MaterialTheme.typography.labelSmall,
                  color = DripMetallicSilver,
                  modifier = Modifier.testTag("hud_gear_rpm_text"),
                )
              }

              Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
              ) {
                val laneLabel =
                  when (telemetry.currentLaneIndex) {
                    0 -> "L"
                    2 -> "R"
                    else -> "C"
                  }
                Text(
                  text = laneLabel,
                  style = MaterialTheme.typography.labelLarge,
                  fontWeight = FontWeight.Black,
                  color = DripElectricBlue,
                  modifier = Modifier.testTag("hud_lane_text"),
                )
                Text(
                  text = "N2O ${telemetry.nitroMeter.toInt()}",
                  style = MaterialTheme.typography.labelMedium,
                  fontWeight = FontWeight.Bold,
                  color = if (telemetry.isNitroActive) DripGoldAccent else DripMetallicSilver,
                  modifier = Modifier.testTag("hud_nitro_text"),
                )
              }
            }

            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              LinearProgressIndicator(
                progress = { (telemetry.speedKmh / 340.0).toFloat().coerceIn(0f, 1f) },
                color = if (telemetry.isNitroActive) DripGoldAccent else DripEmeraldOk,
                trackColor = DripSurfaceElevated,
                modifier = Modifier.weight(1f).height(6.dp).testTag("hud_speedometer_bar"),
              )
              Row(
                modifier = Modifier.weight(0.75f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
              ) {
                Icon(
                  imageVector = Icons.Filled.Bolt,
                  contentDescription = null,
                  tint = if (telemetry.isNitroActive) DripGoldAccent else DripElectricBlue,
                  modifier = Modifier.size(13.dp),
                )
                LinearProgressIndicator(
                  progress = { (telemetry.nitroMeter / 100.0).toFloat().coerceIn(0f, 1f) },
                  color = if (telemetry.isNitroActive) DripGoldAccent else DripElectricBlue,
                  trackColor = DripSurfaceElevated,
                  modifier = Modifier.weight(1f).height(6.dp),
                )
              }
            }
          }
        }

        // Ergonomic Left/Right Steer + Simultaneous Holdable Brake/Nitro Pedals
        val btnHeight = if (isCompactHeight) 50.dp else 58.dp
        val steerMinWidth = if (isCompactWidth) 62.dp else 74.dp
        val pedalMinWidth = if (isCompactWidth) 68.dp else 84.dp
        val clusterGap = if (isCompactWidth) 6.dp else 10.dp

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.Bottom,
        ) {
          Row(horizontalArrangement = Arrangement.spacedBy(clusterGap)) {
            FilledTonalButton(
              onClick = onSteerLeft,
              contentPadding =
                if (isCompactWidth) androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 4.dp)
                else ButtonDefaults.ContentPadding,
              modifier =
                Modifier.height(btnHeight)
                  .widthIn(min = steerMinWidth)
                  .minimumInteractiveComponentSize()
                  .testTag("steer_left_button"),
            ) {
              Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = stringResource(R.string.cd_steer_left),
              )
              Text(
                text = stringResource(R.string.action_steer_left),
                style = if (isCompactWidth) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
              )
            }

            FilledTonalButton(
              onClick = onSteerRight,
              contentPadding =
                if (isCompactWidth) androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 4.dp)
                else ButtonDefaults.ContentPadding,
              modifier =
                Modifier.height(btnHeight)
                  .widthIn(min = steerMinWidth)
                  .minimumInteractiveComponentSize()
                  .testTag("steer_right_button"),
            ) {
              Text(
                text = stringResource(R.string.action_steer_right),
                style = if (isCompactWidth) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
              )
              Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = stringResource(R.string.cd_steer_right),
              )
            }
          }

          Row(horizontalArrangement = Arrangement.spacedBy(clusterGap)) {
            HoldControlPedal(
              label = stringResource(R.string.action_brake),
              active = telemetry.brakeInputHeld || telemetry.isBraking,
              activeColor = DripAlertRed,
              testTag = "brake_button",
              heightDp = btnHeight,
              minWidthDp = pedalMinWidth,
              compact = isCompactWidth,
              onPointerDown = onBrakePointerDown,
              onPointerUpOrCancel = onBrakePointerUpOrCancel,
            )
            HoldControlPedal(
              label = stringResource(R.string.action_nitro),
              active = telemetry.isNitroActive || telemetry.nitroInputHeld,
              activeColor = DripGoldAccent,
              testTag = "nitro_button",
              heightDp = btnHeight,
              minWidthDp = pedalMinWidth,
              compact = isCompactWidth,
              onPointerDown = onNitroPointerDown,
              onPointerUpOrCancel = onNitroPointerUpOrCancel,
            )
          }
        }
      }
    }
  }
}

@Composable
private fun HoldControlPedal(
  label: String,
  active: Boolean,
  activeColor: Color,
  testTag: String,
  heightDp: androidx.compose.ui.unit.Dp = 58.dp,
  minWidthDp: androidx.compose.ui.unit.Dp = 84.dp,
  compact: Boolean = false,
  onPointerDown: (Long) -> Unit,
  onPointerUpOrCancel: (Long) -> Unit,
) {
  val heldIds = remember { mutableSetOf<Long>() }

  DisposableEffect(Unit) {
    onDispose {
      heldIds.toList().forEach { id -> onPointerUpOrCancel(id) }
      heldIds.clear()
    }
  }

  Surface(
    color =
      if (active) activeColor.copy(alpha = 0.92f) else DripSurfaceDark.copy(alpha = 0.86f),
    shape = RoundedCornerShape(14.dp),
    modifier =
      Modifier.height(heightDp)
        .widthIn(min = minWidthDp)
        .minimumInteractiveComponentSize()
        .border(1.5.dp, activeColor.copy(alpha = if (active) 1f else 0.55f), RoundedCornerShape(14.dp))
        .testTag(testTag)
        .semantics {
          role = Role.Button
          contentDescription = label
        }
        .pointerInput(Unit) {
          awaitPointerEventScope {
            while (true) {
              val event = awaitPointerEvent()
              when (event.type) {
                PointerEventType.Press -> {
                  event.changes.forEach { change ->
                    if (change.pressed && heldIds.add(change.id.value)) {
                      onPointerDown(change.id.value)
                    }
                    change.consume()
                  }
                }
                PointerEventType.Move -> {
                  event.changes.forEach { change ->
                    if (!change.pressed && heldIds.remove(change.id.value)) {
                      onPointerUpOrCancel(change.id.value)
                    }
                  }
                }
                else -> {
                  event.changes.forEach { change ->
                    if (!change.pressed && heldIds.remove(change.id.value)) {
                      onPointerUpOrCancel(change.id.value)
                    }
                  }
                }
              }
            }
          }
        },
  ) {
    Box(
      contentAlignment = Alignment.Center,
      modifier = Modifier.padding(horizontal = if (compact) 10.dp else 16.dp),
    ) {
      Text(
        text = label,
        style = if (compact) MaterialTheme.typography.labelLarge else MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Black,
        color = if (active) DripGraphiteBg else DripTextPrimary,
      )
    }
  }
}

@Composable
private fun PausedOverlay(
  diagnostics: AssetDiagnosticStatus,
  telemetry: HudTelemetryState,
  onResume: () -> Unit,
  onRestart: () -> Unit,
  onHome: () -> Unit,
  onOpenProgression: () -> Unit = {},
  onOpenSettings: () -> Unit = {},
) {
  Box(
    modifier =
      Modifier.fillMaxSize()
        .background(DripGraphiteBg.copy(alpha = 0.78f))
        .windowInsetsPadding(WindowInsets.safeDrawing)
        .padding(24.dp),
    contentAlignment = Alignment.Center,
  ) {
    Card(
      modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth(),
      colors = CardDefaults.cardColors(containerColor = DripSurfaceDark),
      shape = RoundedCornerShape(18.dp),
    ) {
      Column(
        modifier = Modifier.verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Text(
          text = stringResource(R.string.title_paused),
          style = MaterialTheme.typography.headlineSmall,
          color = DripTextPrimary,
        )
        Text(
          text =
            "Frozen • Score ${telemetry.score} • Run +${telemetry.runCoinsEarned} coins • Wallet ${telemetry.walletBalance} • Hearts ${telemetry.hearts}/${TrafficSimulation.MAX_HEARTS}",
          style = MaterialTheme.typography.labelMedium,
          color = DripMetallicSilver,
          textAlign = TextAlign.Center,
          modifier = Modifier.testTag("paused_snapshot_text"),
        )
        if (!diagnostics.isOfflinePackagedReady) {
          Text(
            text = "Dev preview mode (not offline bundled)",
            style = MaterialTheme.typography.labelMedium,
            color = DripGoldAccent,
          )
        }

        Button(
          onClick = onResume,
          modifier =
            Modifier.fillMaxWidth()
              .height(52.dp)
              .minimumInteractiveComponentSize()
              .testTag("resume_button"),
          colors = ButtonDefaults.buttonColors(containerColor = DripElectricBlue),
        ) {
          Icon(Icons.Filled.PlayArrow, contentDescription = null)
          Spacer(modifier = Modifier.width(8.dp))
          Text(stringResource(R.string.action_resume), fontWeight = FontWeight.Bold)
        }

        FilledTonalButton(
          onClick = onOpenSettings,
          modifier =
            Modifier.fillMaxWidth()
              .height(50.dp)
              .minimumInteractiveComponentSize()
              .testTag("paused_settings_button"),
        ) {
          Icon(Icons.Filled.Settings, contentDescription = null, tint = DripElectricBlue)
          Spacer(modifier = Modifier.width(8.dp))
          Text(stringResource(R.string.action_audio_settings))
        }

        FilledTonalButton(
          onClick = onOpenProgression,
          modifier =
            Modifier.fillMaxWidth()
              .height(50.dp)
              .minimumInteractiveComponentSize()
              .testTag("paused_progression_button"),
        ) {
          Icon(Icons.Filled.TaskAlt, contentDescription = null, tint = DripGoldAccent)
          Spacer(modifier = Modifier.width(8.dp))
          Text(stringResource(R.string.action_progression_hub))
        }

        FilledTonalButton(
          onClick = onRestart,
          modifier =
            Modifier.fillMaxWidth()
              .height(50.dp)
              .minimumInteractiveComponentSize()
              .testTag("restart_button"),
        ) {
          Icon(Icons.Filled.Replay, contentDescription = null)
          Spacer(modifier = Modifier.width(8.dp))
          Text(stringResource(R.string.action_restart))
        }

        OutlinedButton(
          onClick = onHome,
          modifier =
            Modifier.fillMaxWidth()
              .height(50.dp)
              .minimumInteractiveComponentSize()
              .testTag("home_button"),
        ) {
          Icon(Icons.Filled.Home, contentDescription = null)
          Spacer(modifier = Modifier.width(8.dp))
          Text(stringResource(R.string.action_home))
        }
      }
    }
  }
}

@Composable
private fun GameOverOverlay(
  gameOver: GamePhase.GameOver,
  actionableClaims: Int = 0,
  onPlayAgain: () -> Unit,
  onHome: () -> Unit,
  onOpenProgression: () -> Unit = {},
) {
  Box(
    modifier =
      Modifier.fillMaxSize()
        .background(DripGraphiteBg.copy(alpha = 0.84f))
        .windowInsetsPadding(WindowInsets.safeDrawing)
        .padding(24.dp),
    contentAlignment = Alignment.Center,
  ) {
    Card(
      modifier = Modifier.widthIn(max = 440.dp).fillMaxWidth(),
      colors = CardDefaults.cardColors(containerColor = DripSurfaceDark),
      shape = RoundedCornerShape(18.dp),
    ) {
      Column(
        modifier = Modifier.verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Text(
          text = stringResource(R.string.title_game_over),
          style = MaterialTheme.typography.headlineSmall,
          fontWeight = FontWeight.Black,
          color = DripAlertRed,
          modifier = Modifier.testTag("game_over_title"),
        )
        if (gameOver.isNewBest) {
          Text(
            text = stringResource(R.string.label_new_best),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = DripGoldAccent,
            modifier = Modifier.testTag("game_over_new_best_badge"),
          )
        }

        Text(
          text = "FINAL SCORE: ${gameOver.finalScore}",
          style = MaterialTheme.typography.headlineMedium,
          fontWeight = FontWeight.Black,
          color = DripTextPrimary,
          modifier = Modifier.testTag("game_over_score_text"),
        )
        Text(
          text = "BEST SCORE: ${gameOver.bestScore}",
          style = MaterialTheme.typography.titleMedium,
          color = DripGoldAccent,
          modifier = Modifier.testTag("game_over_best_score_text"),
        )

        Surface(
          color = DripSurfaceElevated,
          shape = RoundedCornerShape(12.dp),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            Text(
              text = "Distance: ${gameOver.distanceMeters.toInt()} m",
              style = MaterialTheme.typography.bodyMedium,
              color = DripTextPrimary,
            )
            Text(
              text = "Unique Overtakes: ${gameOver.validUniqueOvertakes} (+${gameOver.validUniqueOvertakes * 30})",
              style = MaterialTheme.typography.bodyMedium,
              color = DripEmeraldOk,
            )
            Text(
              text = "Unique Near Misses: ${gameOver.validUniqueNearMisses} (+${gameOver.validUniqueNearMisses * 50})",
              style = MaterialTheme.typography.bodyMedium,
              color = DripElectricBlue,
            )
            Text(
              text = "Stage Reached: ${gameOver.stageLabel}",
              style = MaterialTheme.typography.labelMedium,
              color = DripMetallicSilver,
            )
            Text(
              text = "Run Coins Earned: +${gameOver.runCoinsEarned} (${gameOver.runCoinPickupsCollected} pickups)",
              style = MaterialTheme.typography.bodyMedium,
              fontWeight = FontWeight.Bold,
              color = DripGoldAccent,
              modifier = Modifier.testTag("game_over_run_coins_text"),
            )
            Text(
              text = "Wallet Balance: ${gameOver.walletBalance}",
              style = MaterialTheme.typography.bodyMedium,
              fontWeight = FontWeight.Bold,
              color = DripEmeraldOk,
              modifier = Modifier.testTag("game_over_wallet_text"),
            )
            val saveLabel =
              when (gameOver.saveStatus) {
                SaveHealthStatus.DURABLE_CONFIRMED -> "Save Status: Confirmed durable"
                SaveHealthStatus.PENDING_FLUSH -> "Save Status: Pending flush"
                SaveHealthStatus.WRITE_FAILED_UNCONFIRMED ->
                  "Save Status: WRITE FAILED (${gameOver.saveWarning ?: "unconfirmed"})"
                SaveHealthStatus.RECOVERED_FROM_BACKUP ->
                  "Save Status: Recovered from backup"
                SaveHealthStatus.CORRUPT_RESET_WARNING ->
                  "Save Status: Corrupt save warning"
              }
            Text(
              text = saveLabel,
              style = MaterialTheme.typography.labelMedium,
              color =
                if (gameOver.saveStatus == SaveHealthStatus.WRITE_FAILED_UNCONFIRMED ||
                  gameOver.saveStatus == SaveHealthStatus.CORRUPT_RESET_WARNING
                ) {
                  DripAlertRed
                } else {
                  DripMetallicSilver
                },
              modifier = Modifier.testTag("game_over_save_status_text"),
            )
          }
        }

        Button(
          onClick = onPlayAgain,
          modifier =
            Modifier.fillMaxWidth()
              .height(54.dp)
              .minimumInteractiveComponentSize()
              .testTag("play_again_button"),
          colors = ButtonDefaults.buttonColors(containerColor = DripElectricBlue),
        ) {
          Icon(Icons.Filled.Replay, contentDescription = null)
          Spacer(modifier = Modifier.width(8.dp))
          Text(stringResource(R.string.action_play_again), fontWeight = FontWeight.Bold)
        }

        FilledTonalButton(
          onClick = onOpenProgression,
          modifier =
            Modifier.fillMaxWidth()
              .height(50.dp)
              .minimumInteractiveComponentSize()
              .testTag("game_over_progression_button"),
        ) {
          Icon(Icons.Filled.TaskAlt, contentDescription = null, tint = DripGoldAccent)
          Spacer(modifier = Modifier.width(8.dp))
          Text(
            text =
              if (actionableClaims > 0) "Missions & Rewards ($actionableClaims Ready)"
              else stringResource(R.string.action_progression_hub),
            fontWeight = FontWeight.Bold,
          )
        }

        OutlinedButton(
          onClick = onHome,
          modifier =
            Modifier.fillMaxWidth()
              .height(50.dp)
              .minimumInteractiveComponentSize()
              .testTag("game_over_home_button"),
        ) {
          Icon(Icons.Filled.Home, contentDescription = null)
          Spacer(modifier = Modifier.width(8.dp))
          Text(stringResource(R.string.action_home))
        }
      }
    }
  }
}

@Composable
private fun PowerUpTimerChip(
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  label: String,
  tint: Color,
  tag: String,
) {
  Surface(
    color = DripSurfaceElevated,
    shape = RoundedCornerShape(8.dp),
    modifier = Modifier.border(1.dp, tint.copy(alpha = 0.7f), RoundedCornerShape(8.dp)).testTag(tag),
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
      horizontalArrangement = Arrangement.spacedBy(3.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(
        imageVector = icon,
        contentDescription = null,
        tint = tint,
        modifier = Modifier.size(12.dp),
      )
      Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = tint,
      )
    }
  }
}
