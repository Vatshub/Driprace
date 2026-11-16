package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MonetizationOn
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Upgrade
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.assets.HeroModelOrigin
import com.example.economy.SaveHealthStatus
import com.example.garage.BodyPaintColor
import com.example.garage.HeroVehicleCatalog
import com.example.garage.HeroVehicleSpec
import com.example.garage.PaintCatalog
import com.example.garage.UpgradeTrack
import com.example.garage.VehicleUpgradeLevels
import com.example.state.GarageUiState
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

@Composable
internal fun GarageOverlay(
  garage: GarageUiState,
  confirmedWalletBalance: Long,
  pendingWalletBalance: Long,
  saveStatus: SaveHealthStatus,
  onBackToHome: () -> Unit,
  onRace: () -> Unit,
  onSelectVehicle: (String) -> Unit,
  onSelectPaint: (String) -> Unit,
  onRequestUnlock: (String) -> Unit,
  onConfirmUnlock: (String) -> Unit,
  onCancelUnlock: () -> Unit,
  onEquip: () -> Unit,
  onUpgradeTrack: (UpgradeTrack) -> Unit,
  onImportNissanGlb: () -> Unit,
  onShowroomOrbitDelta: (Float, Float) -> Unit,
  onShowroomZoomDelta: (Float) -> Unit,
  onShowroomResetView: () -> Unit,
) {
  val focusedSpec = HeroVehicleCatalog.findById(garage.focusedVehicleId) ?: HeroVehicleCatalog.STARTER_SPEC
  val isOwned = garage.isFocusedOwned()
  val isEquipped = garage.isFocusedEquipped()
  val isAffordable = confirmedWalletBalance >= focusedSpec.unlockPrice
  val upgrades = garage.upgradesForFocused()
  val effectivePhysics = focusedSpec.computeEffectivePhysics(upgrades)
  val focusedStatus = garage.statusForFocused()
  val activePaint = PaintCatalog.findById(garage.previewPaintColorId) ?: PaintCatalog.SEVEN_FREE_COLORS.first()
  var menuCollapsed by remember { mutableStateOf(false) }

  BoxWithConstraints(
    modifier =
      Modifier.fillMaxSize()
        .background(
          Brush.verticalGradient(
            listOf(
              DripGraphiteBg.copy(alpha = 0.18f),
              Color.Transparent,
              Color.Transparent,
            )
          )
        )
        .windowInsetsPadding(WindowInsets.safeDrawing)
        .padding(12.dp)
  ) {
    val isLandscape = maxWidth > maxHeight

    if (isLandscape) {
      Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        ShowroomStageColumn(
          modifier = Modifier.weight(1.1f).fillMaxHeight(),
          focusedSpec = focusedSpec,
          activePaint = activePaint,
          confirmedWalletBalance = confirmedWalletBalance,
          pendingWalletBalance = pendingWalletBalance,
          saveStatus = saveStatus,
          menuCollapsed = menuCollapsed,
          onToggleMenuCollapsed = { menuCollapsed = !menuCollapsed },
          onBackToHome = onBackToHome,
          onRace = onRace,
          onShowroomOrbitDelta = onShowroomOrbitDelta,
          onShowroomZoomDelta = onShowroomZoomDelta,
          onShowroomResetView = onShowroomResetView,
        )

        if (!menuCollapsed) {
          GarageControlDrawer(
            modifier = Modifier.weight(0.9f).fillMaxHeight(),
            garage = garage,
            focusedSpec = focusedSpec,
            focusedStatusOrigin = focusedStatus?.origin,
            focusedStatusSummary = focusedStatus?.statusSummary,
            isOwned = isOwned,
            isEquipped = isEquipped,
            isAffordable = isAffordable,
            confirmedWalletBalance = confirmedWalletBalance,
            upgrades = upgrades,
            effectiveTopSpeedKmh = effectivePhysics.topSpeedKmh,
            effectiveAccel = effectivePhysics.accelerationMps2,
            effectiveBrake = effectivePhysics.brakeDecelerationMps2,
            effectiveLaneDuration = effectivePhysics.laneChangeDurationSeconds,
            effectiveDrain = effectivePhysics.nitroDrainPerSecond,
            effectiveRegen = effectivePhysics.nitroRegenPerSecond,
            onSelectVehicle = onSelectVehicle,
            onSelectPaint = onSelectPaint,
            onRequestUnlock = onRequestUnlock,
            onConfirmUnlock = onConfirmUnlock,
            onCancelUnlock = onCancelUnlock,
            onEquip = onEquip,
            onUpgradeTrack = onUpgradeTrack,
            onImportNissanGlb = onImportNissanGlb,
          )
        }
      }
    } else {
      Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        ShowroomStageColumn(
          modifier = Modifier.fillMaxWidth().weight(if (menuCollapsed) 1f else 1.15f),
          focusedSpec = focusedSpec,
          activePaint = activePaint,
          confirmedWalletBalance = confirmedWalletBalance,
          pendingWalletBalance = pendingWalletBalance,
          saveStatus = saveStatus,
          menuCollapsed = menuCollapsed,
          onToggleMenuCollapsed = { menuCollapsed = !menuCollapsed },
          onBackToHome = onBackToHome,
          onRace = onRace,
          onShowroomOrbitDelta = onShowroomOrbitDelta,
          onShowroomZoomDelta = onShowroomZoomDelta,
          onShowroomResetView = onShowroomResetView,
        )

        if (!menuCollapsed) {
          GarageControlDrawer(
            modifier = Modifier.weight(0.85f).fillMaxWidth(),
            garage = garage,
            focusedSpec = focusedSpec,
            focusedStatusOrigin = focusedStatus?.origin,
            focusedStatusSummary = focusedStatus?.statusSummary,
            isOwned = isOwned,
            isEquipped = isEquipped,
            isAffordable = isAffordable,
            confirmedWalletBalance = confirmedWalletBalance,
            upgrades = upgrades,
            effectiveTopSpeedKmh = effectivePhysics.topSpeedKmh,
            effectiveAccel = effectivePhysics.accelerationMps2,
            effectiveBrake = effectivePhysics.brakeDecelerationMps2,
            effectiveLaneDuration = effectivePhysics.laneChangeDurationSeconds,
            effectiveDrain = effectivePhysics.nitroDrainPerSecond,
            effectiveRegen = effectivePhysics.nitroRegenPerSecond,
            onSelectVehicle = onSelectVehicle,
            onSelectPaint = onSelectPaint,
            onRequestUnlock = onRequestUnlock,
            onConfirmUnlock = onConfirmUnlock,
            onCancelUnlock = onCancelUnlock,
            onEquip = onEquip,
            onUpgradeTrack = onUpgradeTrack,
            onImportNissanGlb = onImportNissanGlb,
          )
        }
      }
    }
  }
}

@Composable
private fun ShowroomStageColumn(
  modifier: Modifier,
  focusedSpec: HeroVehicleSpec,
  activePaint: BodyPaintColor,
  confirmedWalletBalance: Long,
  pendingWalletBalance: Long,
  saveStatus: SaveHealthStatus,
  menuCollapsed: Boolean,
  onToggleMenuCollapsed: () -> Unit,
  onBackToHome: () -> Unit,
  onRace: () -> Unit,
  onShowroomOrbitDelta: (Float, Float) -> Unit,
  onShowroomZoomDelta: (Float) -> Unit,
  onShowroomResetView: () -> Unit,
) {
  Column(
    modifier = modifier,
    verticalArrangement = Arrangement.SpaceBetween,
  ) {
    // Top bar: Back, Wallet, Race
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      OutlinedButton(
        onClick = onBackToHome,
        modifier = Modifier.minimumInteractiveComponentSize().testTag("garage_home_button"),
      ) {
        Icon(Icons.Filled.Home, contentDescription = null)
        Spacer(modifier = Modifier.width(6.dp))
        Text(stringResource(R.string.action_home))
      }

      Surface(
        color = DripSurfaceDark.copy(alpha = 0.88f),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.border(1.dp, DripGoldAccent.copy(alpha = 0.45f), RoundedCornerShape(10.dp)),
      ) {
        Row(
          modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
          Icon(
            imageVector = Icons.Filled.MonetizationOn,
            contentDescription = null,
            tint = DripGoldAccent,
            modifier = Modifier.size(16.dp),
          )
          Text(
            text =
              if (pendingWalletBalance > 0L) {
                "$confirmedWalletBalance (+${pendingWalletBalance} unsaved)"
              } else if (saveStatus == SaveHealthStatus.WRITE_FAILED_UNCONFIRMED) {
                "$confirmedWalletBalance (UNCONFIRMED)"
              } else {
                "$confirmedWalletBalance"
              },
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = DripGoldAccent,
            modifier = Modifier.testTag("garage_wallet_text"),
          )
        }
      }

      Button(
        onClick = onRace,
        modifier = Modifier.minimumInteractiveComponentSize().testTag("garage_race_button"),
        colors = ButtonDefaults.buttonColors(containerColor = DripElectricBlue),
      ) {
        Icon(Icons.Filled.PlayArrow, contentDescription = null)
        Spacer(modifier = Modifier.width(4.dp))
        Text(stringResource(R.string.action_race), fontWeight = FontWeight.Bold)
      }
    }

    // Transparent Interactive 3D Orbit & Pinch Showroom Viewport
    Box(
      modifier =
        Modifier.weight(1f)
          .fillMaxWidth()
          .padding(vertical = 6.dp)
          .clip(RoundedCornerShape(16.dp))
          .border(1.dp, DripElectricBlue.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
          .testTag("garage_showroom_viewport")
          .pointerInput(Unit) {
            detectTransformGestures { _, pan, zoom, _ ->
              if (pan.x != 0f || pan.y != 0f) {
                onShowroomOrbitDelta(-pan.x * 0.35f, pan.y * 0.20f)
              }
              if (zoom != 1f) {
                onShowroomZoomDelta((1f - zoom) * 4.0f)
              }
            }
          },
    ) {
      Surface(
        color = DripSurfaceDark.copy(alpha = 0.72f),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.align(Alignment.TopStart).padding(10.dp),
      ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
          Text(
            text = focusedSpec.displayName,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = DripTextPrimary,
            modifier = Modifier.testTag("garage_focused_car_title"),
          )
          Text(
            text = "${activePaint.displayName} • Drag to orbit, pinch/buttons to zoom",
            style = MaterialTheme.typography.labelSmall,
            color = DripMetallicSilver,
          )
        }
      }

      Row(
        modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        FilledTonalButton(
          onClick = onToggleMenuCollapsed,
          modifier =
            Modifier.minimumInteractiveComponentSize().testTag("garage_toggle_menu_button"),
        ) {
          Icon(
            imageVector = if (menuCollapsed) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
            contentDescription = null,
            tint = DripElectricBlue,
            modifier = Modifier.size(16.dp),
          )
          Spacer(modifier = Modifier.width(6.dp))
          Text(
            text = if (menuCollapsed) "Show Menu" else "Full Car View",
            style = MaterialTheme.typography.labelMedium,
          )
        }
      }

      Row(
        modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Surface(color = DripSurfaceDark.copy(alpha = 0.85f), shape = RoundedCornerShape(10.dp)) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
              onClick = { onShowroomZoomDelta(-0.6f) },
              modifier = Modifier.minimumInteractiveComponentSize().testTag("garage_zoom_in_button"),
            ) {
              Icon(
                imageVector = Icons.Filled.ZoomIn,
                contentDescription = stringResource(R.string.cd_zoom_in),
                tint = DripTextPrimary,
              )
            }
            IconButton(
              onClick = { onShowroomZoomDelta(+0.6f) },
              modifier = Modifier.minimumInteractiveComponentSize().testTag("garage_zoom_out_button"),
            ) {
              Icon(
                imageVector = Icons.Filled.ZoomOut,
                contentDescription = stringResource(R.string.cd_zoom_out),
                tint = DripTextPrimary,
              )
            }
            IconButton(
              onClick = onShowroomResetView,
              modifier = Modifier.minimumInteractiveComponentSize().testTag("garage_reset_view_button"),
            ) {
              Icon(
                imageVector = Icons.Filled.CenterFocusStrong,
                contentDescription = stringResource(R.string.cd_reset_view),
                tint = DripElectricBlue,
              )
            }
          }
        }
      }
    }
  }
}

@Composable
private fun GarageControlDrawer(
  modifier: Modifier,
  garage: GarageUiState,
  focusedSpec: HeroVehicleSpec,
  focusedStatusOrigin: HeroModelOrigin?,
  focusedStatusSummary: String?,
  isOwned: Boolean,
  isEquipped: Boolean,
  isAffordable: Boolean,
  confirmedWalletBalance: Long,
  upgrades: VehicleUpgradeLevels,
  effectiveTopSpeedKmh: Double,
  effectiveAccel: Double,
  effectiveBrake: Double,
  effectiveLaneDuration: Double,
  effectiveDrain: Double,
  effectiveRegen: Double,
  onSelectVehicle: (String) -> Unit,
  onSelectPaint: (String) -> Unit,
  onRequestUnlock: (String) -> Unit,
  onConfirmUnlock: (String) -> Unit,
  onCancelUnlock: () -> Unit,
  onEquip: () -> Unit,
  onUpgradeTrack: (UpgradeTrack) -> Unit,
  onImportNissanGlb: () -> Unit,
) {
  Card(
    modifier = modifier,
    colors = CardDefaults.cardColors(containerColor = DripSurfaceDark.copy(alpha = 0.88f)),
    shape = RoundedCornerShape(14.dp),
  ) {
    Column(
      modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(10.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      // 1. 5-Car Horizontal Selector
      Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        HeroVehicleCatalog.ALL_CARS.forEach { car ->
          val carOwned = car.id in garage.ownedVehicleIds
          val carEquipped = car.id == garage.equippedVehicleId
          val carFocused = car.id == garage.focusedVehicleId
          val carStatus = garage.heroAssetStatuses.firstOrNull { it.vehicleId == car.id }
          val importNeeded =
            car.onDeviceImportOnly &&
              carStatus?.origin == HeroModelOrigin.IMPORT_REQUIRED_PROCEDURAL_FALLBACK

          val borderColor =
            when {
              carFocused -> DripElectricBlue
              carEquipped -> DripEmeraldOk.copy(alpha = 0.7f)
              else -> DripSurfaceElevated
            }
          Surface(
            color = if (carFocused) DripSurfaceElevated else DripGraphiteBg.copy(alpha = 0.7f),
            shape = RoundedCornerShape(12.dp),
            modifier =
              Modifier.widthIn(min = 132.dp)
                .minimumInteractiveComponentSize()
                .border(1.5.dp, borderColor, RoundedCornerShape(12.dp))
                .clip(RoundedCornerShape(12.dp))
                .clickable { onSelectVehicle(car.id) }
                .testTag("garage_car_${car.id}"),
          ) {
            Column(
              modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
              verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
              Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Icon(
                  imageVector =
                    when {
                      carEquipped -> Icons.Filled.CheckCircle
                      carOwned -> Icons.Filled.DirectionsCar
                      else -> Icons.Filled.Lock
                    },
                  contentDescription = null,
                  tint =
                    when {
                      carEquipped -> DripEmeraldOk
                      carOwned -> DripElectricBlue
                      else -> DripGoldAccent
                    },
                  modifier = Modifier.size(14.dp),
                )
                Text(
                  text = car.displayName,
                  style = MaterialTheme.typography.labelMedium,
                  fontWeight = FontWeight.Bold,
                  color = DripTextPrimary,
                )
              }
              Text(
                text =
                  when {
                    carEquipped -> "EQUIPPED"
                    carOwned -> "OWNED"
                    else -> "${car.unlockPrice} COINS"
                  },
                style = MaterialTheme.typography.labelSmall,
                color =
                  when {
                    carEquipped -> DripEmeraldOk
                    carOwned -> DripElectricBlue
                    confirmedWalletBalance >= car.unlockPrice -> DripGoldAccent
                    else -> DripTextMuted
                  },
              )
              if (importNeeded) {
                Text(
                  text = "IMPORT REQUIRED",
                  style = MaterialTheme.typography.labelSmall,
                  color = DripAlertRed,
                )
              }
            }
          }
        }
      }

      // 2. Active Car Asset Status, Specs & Purchase/Equip/Nissan SAF Importer
      Surface(
        color = DripSurfaceElevated,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
      ) {
        Column(
          modifier = Modifier.padding(12.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Text(
            text = focusedSpec.subtitleTag,
            style = MaterialTheme.typography.labelMedium,
            color = DripMetallicSilver,
          )
          Text(
            text =
              "Base/Upgraded: ${"%.0f".format(effectiveTopSpeedKmh)} km/h (Nitro ${"%.1f".format(effectiveTopSpeedKmh * 1.25)}) • Accel ${"%.2f".format(effectiveAccel)} m/s² • Brake ${"%.1f".format(effectiveBrake)} m/s² • Steer ${"%.3f".format(effectiveLaneDuration)}s • 3 Hearts",
            style = MaterialTheme.typography.labelSmall,
            color = DripElectricBlue,
            modifier = Modifier.testTag("garage_specs_summary_text"),
          )

          focusedStatusSummary?.let { summary ->
            val originColor =
              when (focusedStatusOrigin) {
                HeroModelOrigin.BUNDLED_OFFLINE_GLB,
                HeroModelOrigin.ON_DEVICE_RUNTIME_IMPORT_GLB -> DripEmeraldOk
                else -> DripGoldAccent
              }
            Text(
              text = summary,
              style = MaterialTheme.typography.labelSmall,
              color = originColor,
              modifier = Modifier.testTag("garage_model_origin_text"),
            )
          }

          if (focusedSpec.onDeviceImportOnly) {
            OutlinedButton(
              onClick = onImportNissanGlb,
              modifier =
                Modifier.fillMaxWidth()
                  .minimumInteractiveComponentSize()
                  .testTag("garage_import_nissan_button"),
            ) {
              Icon(Icons.Filled.FolderOpen, contentDescription = null, tint = DripGoldAccent)
              Spacer(modifier = Modifier.width(8.dp))
              Text(
                text = stringResource(R.string.action_import_nissan_glb),
                color = DripGoldAccent,
              )
            }
          }

          garage.statusMessage?.let { msg ->
            Text(
              text = msg,
              style = MaterialTheme.typography.labelMedium,
              fontWeight = FontWeight.Bold,
              color = DripGoldAccent,
              modifier = Modifier.testTag("garage_status_message_text"),
            )
          }

          // Action Row: Buy (with explicit confirmation), Equip, or Equipped
          if (!isOwned) {
            if (garage.pendingConfirmVehicleId == focusedSpec.id) {
              Surface(
                color = DripGraphiteBg.copy(alpha = 0.85f),
                shape = RoundedCornerShape(10.dp),
                modifier =
                  Modifier.fillMaxWidth()
                    .border(1.dp, DripGoldAccent, RoundedCornerShape(10.dp)),
              ) {
                Column(
                  modifier = Modifier.padding(10.dp),
                  verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                  Text(
                    text = "Confirm unlocking ${focusedSpec.displayName} for ${focusedSpec.unlockPrice} coins?",
                    style = MaterialTheme.typography.labelMedium,
                    color = DripTextPrimary,
                  )
                  Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                  ) {
                    Button(
                      onClick = { onConfirmUnlock(focusedSpec.id) },
                      enabled = isAffordable,
                      modifier =
                        Modifier.weight(1f)
                          .minimumInteractiveComponentSize()
                          .testTag("garage_confirm_unlock_button"),
                      colors = ButtonDefaults.buttonColors(containerColor = DripEmeraldOk),
                    ) {
                      Text(stringResource(R.string.action_confirm_buy), color = DripGraphiteBg, fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(
                      onClick = onCancelUnlock,
                      modifier =
                        Modifier.weight(1f)
                          .minimumInteractiveComponentSize()
                          .testTag("garage_cancel_unlock_button"),
                    ) {
                      Text(stringResource(R.string.action_cancel))
                    }
                  }
                }
              }
            } else {
              Button(
                onClick = { onRequestUnlock(focusedSpec.id) },
                enabled = isAffordable,
                modifier =
                  Modifier.fillMaxWidth()
                    .minimumInteractiveComponentSize()
                    .testTag("garage_unlock_button"),
                colors = ButtonDefaults.buttonColors(containerColor = DripGoldAccent),
              ) {
                Icon(Icons.Filled.ShoppingCart, contentDescription = null, tint = DripGraphiteBg)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                  text =
                    if (isAffordable) {
                      "Unlock for ${focusedSpec.unlockPrice} Coins"
                    } else {
                      "Need ${focusedSpec.unlockPrice} Coins"
                    },
                  color = DripGraphiteBg,
                  fontWeight = FontWeight.Bold,
                )
              }
            }
          } else {
            Button(
              onClick = onEquip,
              enabled = !isEquipped,
              modifier =
                Modifier.fillMaxWidth()
                  .minimumInteractiveComponentSize()
                  .testTag("garage_equip_button"),
              colors =
                ButtonDefaults.buttonColors(
                  containerColor = if (isEquipped) DripEmeraldOk else DripElectricBlue
                ),
            ) {
              Icon(
                imageVector = if (isEquipped) Icons.Filled.CheckCircle else Icons.Filled.DirectionsCar,
                contentDescription = null,
              )
              Spacer(modifier = Modifier.width(8.dp))
              Text(
                text =
                  if (isEquipped) {
                    stringResource(R.string.action_equipped)
                  } else {
                    stringResource(R.string.action_equip)
                  },
                fontWeight = FontWeight.Bold,
              )
            }
          }
        }
      }

      // 3. 7 Free Body Paint Colors
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          Icon(Icons.Filled.Palette, contentDescription = null, tint = DripElectricBlue, modifier = Modifier.size(18.dp))
          Text(
            text =
              if (isOwned) {
                "Body Paint (Free • Saved per car)"
              } else {
                "Body Paint (Free preview • Unlock car to save)"
              },
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = DripTextPrimary,
          )
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
          PaintCatalog.SEVEN_FREE_COLORS.chunked(4).forEach { rowColors ->
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
              rowColors.forEach { paint ->
                val selected = paint.id == garage.previewPaintColorId
                Box(
                  modifier =
                    Modifier.size(48.dp)
                      .clip(CircleShape)
                      .background(Color(paint.srgbHex))
                      .border(
                        width = if (selected) 3.dp else 1.5.dp,
                        color = if (selected) DripElectricBlue else DripMetallicSilver.copy(alpha = 0.5f),
                        shape = CircleShape,
                      )
                      .clickable { onSelectPaint(paint.id) }
                      .semantics { contentDescription = paint.displayName }
                      .testTag("garage_paint_${paint.id}"),
                  contentAlignment = Alignment.Center,
                ) {
                  if (selected) {
                    Icon(
                      imageVector = Icons.Filled.CheckCircle,
                      contentDescription = null,
                      tint =
                        if (paint.id == "pearl_white" || paint.id == "racing_yellow") {
                          DripGraphiteBg
                        } else {
                          Color.White
                        },
                      modifier = Modifier.size(18.dp),
                    )
                  }
                }
              }
            }
          }
        }
      }

      // 4. 4 Independent Upgrade Tracks (Sequential 0..3, owned car between races)
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          Icon(Icons.Filled.Upgrade, contentDescription = null, tint = DripGoldAccent, modifier = Modifier.size(18.dp))
          Text(
            text = "Performance Upgrades (Lv 0–3 • Per-Car)",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = DripTextPrimary,
          )
        }

        UpgradeTrack.entries.forEach { track ->
          val level = upgrades.levelFor(track)
          val nextCost = focusedSpec.nextUpgradeCost(level)
          val canUpgrade = isOwned && nextCost != null && confirmedWalletBalance >= nextCost
          val liveReadout =
            when (track) {
              UpgradeTrack.ENGINE ->
                "${"%.0f".format(effectiveTopSpeedKmh)} km/h • ${"%.2f".format(effectiveAccel)} m/s²"
              UpgradeTrack.HANDLING ->
                "Lane change ${"%.3f".format(effectiveLaneDuration)} s"
              UpgradeTrack.BRAKES ->
                "Brake decel ${"%.1f".format(effectiveBrake)} m/s²"
              UpgradeTrack.NITRO ->
                "Drain ${"%.1f".format(effectiveDrain)}/s • Regen ${"%.1f".format(effectiveRegen)}/s"
            }

          Surface(
            color = DripSurfaceElevated,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth(),
          ) {
            Row(
              modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                  text = "${track.displayName} • Lv $level/${VehicleUpgradeLevels.MAX_LEVEL}",
                  style = MaterialTheme.typography.labelLarge,
                  fontWeight = FontWeight.Bold,
                  color = DripTextPrimary,
                  modifier = Modifier.testTag("garage_upgrade_level_${track.id}"),
                )
                Text(
                  text = liveReadout,
                  style = MaterialTheme.typography.labelSmall,
                  color = DripElectricBlue,
                )
              }

              Spacer(modifier = Modifier.width(8.dp))

              FilledTonalButton(
                onClick = { onUpgradeTrack(track) },
                enabled = canUpgrade,
                modifier =
                  Modifier.minimumInteractiveComponentSize()
                    .testTag("garage_upgrade_${track.id}"),
              ) {
                if (track == UpgradeTrack.NITRO) {
                  Icon(Icons.Filled.Bolt, contentDescription = null, modifier = Modifier.size(16.dp))
                  Spacer(modifier = Modifier.width(4.dp))
                }
                Text(
                  text =
                    when {
                      !isOwned -> "LOCKED"
                      nextCost == null -> "MAX"
                      else -> "$nextCost"
                    },
                  fontWeight = FontWeight.Bold,
                )
              }
            }
          }
        }
      }
    }
  }
}
