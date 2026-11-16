package com.example

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.state.DripRaceViewModel
import com.example.state.GamePhase
import com.example.ui.DripRaceRootScreen
import com.example.ui.theme.DripRaceTheme

class MainActivity : ComponentActivity() {
  private val viewModel: DripRaceViewModel by viewModels()

  private val glbDocumentPicker =
    registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
      if (uri != null) {
        viewModel.loadPreviewDocumentUri(uri)
      }
    }

  private val sedanGlbDocumentPicker =
    registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
      if (uris.isNotEmpty()) {
        viewModel.importMultipleTrafficGlbs(uris)
      }
    }

  private val nissanHeroGlbDocumentPicker =
    registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
      if (uri != null) {
        viewModel.onImportNissanHeroGlb(uri)
      }
    }

  private val valleySceneryGlbDocumentPicker =
    registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
      if (uri != null) {
        viewModel.onImportSceneryCandidateGlb(uri)
      }
    }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      window.attributes =
        window.attributes.apply {
          layoutInDisplayCutoutMode =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
              WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else {
              WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    setContent {
      val uiState by viewModel.uiState.collectAsStateWithLifecycle()
      val telemetry by viewModel.hudTelemetry.collectAsStateWithLifecycle()

      LaunchedEffect(uiState.phase) {
        updateImmersiveFullscreen(immersive = uiState.phase is GamePhase.Running)
      }

      DripRaceTheme {
        DripRaceRootScreen(
          uiState = uiState,
          telemetry = telemetry,
          onPlay = viewModel::onPlayClicked,
          onPlayAgain = viewModel::onPlayAgainClicked,
          onPause = viewModel::onPauseClicked,
          onResume = viewModel::onResumeClicked,
          onRestart = viewModel::onRestartClicked,
          onHome = viewModel::onHomeClicked,
          onRetry = viewModel::onRetryClicked,
          onPickGlbPreview = {
            glbDocumentPicker.launch(arrayOf("model/gltf-binary", "application/octet-stream", "*/*"))
          },
          onImportSedanGlb = {
            sedanGlbDocumentPicker.launch(arrayOf("model/gltf-binary", "application/octet-stream", "*/*"))
          },
          onOpenGarage = viewModel::onOpenGarageClicked,
          onExitGarageToHome = viewModel::onExitGarageToHomeClicked,
          onGarageRace = viewModel::onGarageRaceClicked,
          onGarageSelectVehicle = viewModel::onGarageSelectFocusVehicle,
          onGarageSelectPaint = viewModel::onGarageSelectPaintColor,
          onGarageRequestUnlock = viewModel::onGarageRequestUnlockVehicle,
          onGarageConfirmUnlock = viewModel::onGarageConfirmUnlockVehicle,
          onGarageCancelUnlock = viewModel::onGarageCancelUnlockVehicle,
          onGarageEquip = viewModel::onGarageEquipFocusedVehicle,
          onGarageUpgrade = viewModel::onGarageUpgradeTrack,
          onImportNissanHeroGlb = {
            nissanHeroGlbDocumentPicker.launch(
              arrayOf("model/gltf-binary", "application/octet-stream", "*/*")
            )
          },
          onShowroomOrbitDelta = viewModel::onShowroomOrbitDelta,
          onShowroomZoomDelta = viewModel::onShowroomZoomDelta,
          onShowroomResetView = viewModel::onShowroomResetView,
          onOpenProgressionTab = viewModel::onOpenProgressionTab,
          onSelectProgressionTab = viewModel::onSelectProgressionTab,
          onCloseProgressionOverlay = viewModel::onCloseProgressionOverlay,
          onClaimDailyMission = viewModel::onClaimDailyMission,
          onClaimAchievement = viewModel::onClaimAchievement,
          onClaimDailyLogin = viewModel::onClaimDailyLoginReward,
          onSelectEnvironment = viewModel::onSelectEnvironment,
          onSelectGraphicsQuality = viewModel::onSelectGraphicsQualityPreset,
          onUpdateAudioSettings = viewModel::onUpdateAudioSettings,
          onToggleQuickMute = viewModel::onToggleQuickMute,
          onImportValleySceneryGlb = {
            valleySceneryGlbDocumentPicker.launch(
              arrayOf("model/gltf-binary", "application/octet-stream", "*/*")
            )
          },
          onSyncCalendarClock = viewModel::onSyncCalendarClock,
          onBackFromRunning = { viewModel.onBackPressed() },
          onSteerLeft = viewModel::onSteerLeft,
          onSteerRight = viewModel::onSteerRight,
          onBrakePointerDown = viewModel::onBrakePointerDown,
          onBrakePointerUpOrCancel = viewModel::onBrakePointerUpOrCancel,
          onNitroPointerDown = viewModel::onNitroPointerDown,
          onNitroPointerUpOrCancel = viewModel::onNitroPointerUpOrCancel,
          onBrakeKeyChanged = viewModel::onBrakeKeyChanged,
          onNitroKeyChanged = viewModel::onNitroKeyChanged,
          onAttachSurface = viewModel::attachSurfaceView,
          onDetachSurface = viewModel::detachSurfaceView,
        )
      }
    }
  }

  private var isActivityResumed = false

  override fun onResume() {
    super.onResume()
    isActivityResumed = true
    viewModel.onActivityStartedOrResumed(active = true)
    updateImmersiveFullscreen(immersive = viewModel.uiState.value.phase is GamePhase.Running)
  }

  override fun onPause() {
    isActivityResumed = false
    viewModel.onActivityStartedOrResumed(active = false)
    super.onPause()
  }

  override fun onWindowFocusChanged(hasFocus: Boolean) {
    super.onWindowFocusChanged(hasFocus)
    viewModel.onWindowFocusChanged(hasFocus)
    if (hasFocus && isActivityResumed) {
      updateImmersiveFullscreen(immersive = viewModel.uiState.value.phase is GamePhase.Running)
    }
  }

  private fun updateImmersiveFullscreen(immersive: Boolean) {
    val controller = WindowCompat.getInsetsController(window, window.decorView)
    controller.systemBarsBehavior =
      WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    controller.isAppearanceLightStatusBars = false
    controller.isAppearanceLightNavigationBars = false
    if (immersive) {
      controller.hide(WindowInsetsCompat.Type.systemBars())
    } else {
      controller.show(WindowInsetsCompat.Type.systemBars())
    }
  }
}
