package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Landscape
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MonetizationOn
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.audio.AudioCatalog
import com.example.audio.AudioMixSnapshot
import com.example.audio.AudioSettingsState
import com.example.economy.WalletSnapshot
import com.example.progression.AchievementItemStatus
import com.example.progression.DailyLoginStatus
import com.example.progression.DailyMissionItemStatus
import com.example.progression.EnvironmentItemStatus
import com.example.progression.MissionStatus
import com.example.progression.ProgressionCatalog
import com.example.scenery.GraphicsQualityPreset
import com.example.scenery.RadianceHdrDecoder
import com.example.state.ProgressionOverlayState
import com.example.state.ProgressionTab
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
import java.util.Locale

@Composable
fun ProgressionHubOverlay(
  overlayState: ProgressionOverlayState,
  walletSnapshot: WalletSnapshot,
  activeSceneEnvironmentId: String,
  raceInProgress: Boolean,
  onSelectTab: (ProgressionTab) -> Unit,
  onClose: () -> Unit,
  onClaimMission: (String) -> Unit,
  onClaimAchievement: (String) -> Unit,
  onClaimDailyLogin: () -> Unit,
  onSelectEnvironment: (String) -> Unit,
  scenerySummary: String = "",
  audioSummary: String = "",
  hasHardwareVibrator: Boolean = false,
  audioMixSnapshot: AudioMixSnapshot = AudioMixSnapshot(),
  onSelectGraphicsQuality: (GraphicsQualityPreset) -> Unit = {},
  onUpdateAudioSettings: (AudioSettingsState) -> Unit = {},
  onImportValleySceneryGlb: () -> Unit = {},
) {
  BackHandler(onBack = onClose)

  Box(
    modifier =
      Modifier.fillMaxSize()
        .background(DripGraphiteBg.copy(alpha = 0.88f))
        .windowInsetsPadding(WindowInsets.safeDrawing)
        .padding(16.dp)
        .testTag("progression_hub_overlay"),
    contentAlignment = Alignment.Center,
  ) {
    Card(
      modifier = Modifier.widthIn(max = 680.dp).fillMaxSize(),
      colors = CardDefaults.cardColors(containerColor = DripSurfaceDark),
      shape = RoundedCornerShape(18.dp),
    ) {
      Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        // Header row: Title + Confirmed Wallet + Close button
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = stringResource(R.string.title_progression_hub),
              style = MaterialTheme.typography.titleLarge,
              fontWeight = FontWeight.Black,
              color = DripTextPrimary,
            )
            Row(
              horizontalArrangement = Arrangement.spacedBy(10.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
              ) {
                Icon(
                  imageVector = Icons.Filled.MonetizationOn,
                  contentDescription = null,
                  tint = DripGoldAccent,
                  modifier = Modifier.size(16.dp),
                )
                Text(
                  text = "WALLET: ${walletSnapshot.displayBalance}",
                  style = MaterialTheme.typography.labelLarge,
                  fontWeight = FontWeight.Bold,
                  color = DripEmeraldOk,
                  modifier = Modifier.testTag("progression_wallet_text"),
                )
              }
              Text(
                text = "DATE: ${walletSnapshot.currentDateKey} (${walletSnapshot.profileTimeZoneId})",
                style = MaterialTheme.typography.labelSmall,
                color = DripTextMuted,
                modifier = Modifier.testTag("progression_date_tz_text"),
              )
            }
          }

          IconButton(
            onClick = onClose,
            modifier = Modifier.minimumInteractiveComponentSize().testTag("progression_close_button"),
          ) {
            Icon(
              imageVector = Icons.Filled.Close,
              contentDescription = stringResource(R.string.cd_close_progression),
              tint = DripTextPrimary,
            )
          }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 5-Tab selector bar
        Row(
          modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          ProgressionTabChip(
            label = "Missions (${walletSnapshot.claimableMissionsCount})",
            icon = Icons.Filled.TaskAlt,
            selected = overlayState.activeTab == ProgressionTab.MISSIONS,
            tag = "progression_tab_missions",
            onClick = { onSelectTab(ProgressionTab.MISSIONS) },
          )
          ProgressionTabChip(
            label = "Achievements (${walletSnapshot.claimableAchievementsCount})",
            icon = Icons.Filled.EmojiEvents,
            selected = overlayState.activeTab == ProgressionTab.ACHIEVEMENTS,
            tag = "progression_tab_achievements",
            onClick = { onSelectTab(ProgressionTab.ACHIEVEMENTS) },
          )
          ProgressionTabChip(
            label = "Levels (${walletSnapshot.unlockedEnvironmentIds.size}/5)",
            icon = Icons.Filled.Landscape,
            selected = overlayState.activeTab == ProgressionTab.LEVELS,
            tag = "progression_tab_levels",
            onClick = { onSelectTab(ProgressionTab.LEVELS) },
          )
          ProgressionTabChip(
            label = if (walletSnapshot.dailyLogin?.canClaimToday == true) "Daily (!)" else "Daily",
            icon = Icons.Filled.CalendarMonth,
            selected = overlayState.activeTab == ProgressionTab.DAILY_REWARDS,
            tag = "progression_tab_daily_rewards",
            onClick = { onSelectTab(ProgressionTab.DAILY_REWARDS) },
          )
          ProgressionTabChip(
            label = "Profile",
            icon = Icons.Filled.Person,
            selected = overlayState.activeTab == ProgressionTab.PROFILE,
            tag = "progression_tab_profile",
            onClick = { onSelectTab(ProgressionTab.PROFILE) },
          )
          ProgressionTabChip(
            label = stringResource(R.string.action_settings),
            icon = Icons.Filled.Settings,
            selected = overlayState.activeTab == ProgressionTab.SETTINGS,
            tag = "progression_tab_settings",
            onClick = { onSelectTab(ProgressionTab.SETTINGS) },
          )
        }

        overlayState.statusMessage?.let { msg ->
          Spacer(modifier = Modifier.height(8.dp))
          Surface(
            color = DripSurfaceElevated,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth(),
          ) {
            Text(
              text = msg,
              style = MaterialTheme.typography.labelMedium,
              fontWeight = FontWeight.Bold,
              color = DripGoldAccent,
              modifier = Modifier.padding(10.dp).testTag("progression_status_message"),
            )
          }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Scrollable tab content
        Column(
          modifier =
            Modifier.weight(1f)
              .fillMaxWidth()
              .verticalScroll(rememberScrollState())
              .testTag("progression_scroll_body"),
          verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          when (overlayState.activeTab) {
            ProgressionTab.MISSIONS -> {
              DailyMissionsTabContent(
                missions = walletSnapshot.dailyMissions,
                previousDayKey = walletSnapshot.previousDayDateKey,
                expiredMissions = walletSnapshot.previousDayExpiredMissions,
                onClaimMission = onClaimMission,
              )
            }
            ProgressionTab.ACHIEVEMENTS -> {
              AchievementsTabContent(
                achievements = walletSnapshot.achievements,
                onClaimAchievement = onClaimAchievement,
              )
            }
            ProgressionTab.LEVELS -> {
              EnvironmentLevelsTabContent(
                environments = walletSnapshot.environmentsWithActiveScene(activeSceneEnvironmentId),
                lifetimeDistanceMeters = walletSnapshot.lifetimeDistanceMeters,
                selectedQualityPresetId = walletSnapshot.graphicsQualityPresetId,
                scenerySummary = scenerySummary,
                raceInProgress = raceInProgress,
                onSelectEnvironment = onSelectEnvironment,
                onSelectGraphicsQuality = onSelectGraphicsQuality,
                onImportValleySceneryGlb = onImportValleySceneryGlb,
              )
            }
            ProgressionTab.DAILY_REWARDS -> {
              DailyRewardsTabContent(
                dailyLogin = walletSnapshot.dailyLogin,
                onClaimDailyLogin = onClaimDailyLogin,
              )
            }
            ProgressionTab.PROFILE -> {
              ProfileStatsTabContent(walletSnapshot = walletSnapshot)
            }
            ProgressionTab.SETTINGS -> {
              SettingsTabContent(
                walletSnapshot = walletSnapshot,
                audioMixSnapshot = audioMixSnapshot,
                audioSummary = audioSummary,
                hasHardwareVibrator = hasHardwareVibrator,
                raceInProgress = raceInProgress,
                onUpdateAudioSettings = onUpdateAudioSettings,
                onSelectGraphicsQuality = onSelectGraphicsQuality,
              )
            }
          }
        }
      }
    }
  }
}

@Composable
private fun ProgressionTabChip(
  label: String,
  icon: ImageVector,
  selected: Boolean,
  tag: String,
  onClick: () -> Unit,
) {
  if (selected) {
    Button(
      onClick = onClick,
      colors = ButtonDefaults.buttonColors(containerColor = DripElectricBlue),
      modifier = Modifier.minimumInteractiveComponentSize().testTag(tag),
    ) {
      Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
      Spacer(modifier = Modifier.width(6.dp))
      Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
    }
  } else {
    OutlinedButton(
      onClick = onClick,
      modifier = Modifier.minimumInteractiveComponentSize().testTag(tag),
    ) {
      Icon(icon, contentDescription = null, tint = DripMetallicSilver, modifier = Modifier.size(16.dp))
      Spacer(modifier = Modifier.width(6.dp))
      Text(label, style = MaterialTheme.typography.labelLarge, color = DripMetallicSilver)
    }
  }
}

@Composable
private fun DailyMissionsTabContent(
  missions: List<DailyMissionItemStatus>,
  previousDayKey: String,
  expiredMissions: List<DailyMissionItemStatus>,
  onClaimMission: (String) -> Unit,
) {
  Text(
    text =
      "Complete daily objectives before local midnight. Daily pool: ${ProgressionCatalog.TOTAL_DAILY_MISSION_REWARDS} coins. Unclaimed daily rewards expire at reset; mission rewards never count toward Coin Collector.",
    style = MaterialTheme.typography.bodySmall,
    color = DripMetallicSilver,
  )

  if (expiredMissions.isNotEmpty()) {
    Surface(
      color = DripAlertRed.copy(alpha = 0.16f),
      shape = RoundedCornerShape(12.dp),
      modifier =
        Modifier.fillMaxWidth()
          .border(1.dp, DripAlertRed.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
          .testTag("missions_expired_banner"),
    ) {
      Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
          text =
            "EXPIRED FROM $previousDayKey (${expiredMissions.size} unclaimed): ${expiredMissions.joinToString { it.spec.title }}",
          style = MaterialTheme.typography.labelMedium,
          fontWeight = FontWeight.Bold,
          color = DripAlertRed,
        )
        Text(
          text = "Previous-day unclaimed mission rewards expired on daily reset and cannot be claimed.",
          style = MaterialTheme.typography.labelSmall,
          color = DripTextMuted,
        )
      }
    }
  }

  missions.forEach { item ->
    val spec = item.spec
    val statusColor =
      when (item.status) {
        MissionStatus.CLAIMED -> DripEmeraldOk
        MissionStatus.COMPLETED_UNCLAIMED -> DripGoldAccent
        MissionStatus.EXPIRED -> DripAlertRed
        MissionStatus.IN_PROGRESS -> DripElectricBlue
      }
    Surface(
      color = DripSurfaceElevated,
      shape = RoundedCornerShape(12.dp),
      modifier =
        Modifier.fillMaxWidth()
          .border(1.dp, statusColor.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
          .testTag("mission_card_${spec.id}"),
    ) {
      Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = spec.title,
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.Bold,
              color = DripTextPrimary,
            )
            Text(
              text = spec.description,
              style = MaterialTheme.typography.bodySmall,
              color = DripMetallicSilver,
            )
          }
          Spacer(modifier = Modifier.width(8.dp))
          Text(
            text = "+${spec.rewardCoins} COINS",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Black,
            color = DripGoldAccent,
          )
        }

        LinearProgressIndicator(
          progress = { item.progressFraction },
          color = statusColor,
          trackColor = DripSurfaceDark,
          modifier = Modifier.fillMaxWidth().height(7.dp),
        )

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = "${item.currentValue} / ${spec.targetValue} ${spec.unitLabel} • ${item.status.name}",
            style = MaterialTheme.typography.labelMedium,
            color = statusColor,
            modifier = Modifier.testTag("mission_status_${spec.id}"),
          )

          Button(
            onClick = { onClaimMission(spec.id) },
            enabled = item.status == MissionStatus.COMPLETED_UNCLAIMED,
            colors = ButtonDefaults.buttonColors(containerColor = DripGoldAccent),
            modifier = Modifier.minimumInteractiveComponentSize().testTag("mission_claim_${spec.id}"),
          ) {
            Text(
              text =
                if (item.status == MissionStatus.CLAIMED) "Claimed"
                else "Claim +${spec.rewardCoins}",
              color = DripGraphiteBg,
              fontWeight = FontWeight.Bold,
            )
          }
        }
      }
    }
  }
}

@Composable
private fun AchievementsTabContent(
  achievements: List<AchievementItemStatus>,
  onClaimAchievement: (String) -> Unit,
) {
  Text(
    text = "Permanent lifetime milestones. Unlocked achievements never expire and can be claimed once.",
    style = MaterialTheme.typography.bodySmall,
    color = DripMetallicSilver,
  )

  achievements.forEach { item ->
    val spec = item.spec
    val accent =
      when {
        item.claimed -> DripEmeraldOk
        item.unlocked -> DripGoldAccent
        else -> DripElectricBlue
      }
    Surface(
      color = DripSurfaceElevated,
      shape = RoundedCornerShape(12.dp),
      modifier =
        Modifier.fillMaxWidth()
          .border(1.dp, accent.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
          .testTag("achievement_card_${spec.id}"),
    ) {
      Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = spec.title,
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.Bold,
              color = DripTextPrimary,
            )
            Text(
              text = spec.description,
              style = MaterialTheme.typography.bodySmall,
              color = DripMetallicSilver,
            )
          }
          Spacer(modifier = Modifier.width(8.dp))
          Text(
            text = "+${spec.rewardCoins} COINS",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Black,
            color = DripGoldAccent,
          )
        }

        LinearProgressIndicator(
          progress = { item.progressFraction },
          color = accent,
          trackColor = DripSurfaceDark,
          modifier = Modifier.fillMaxWidth().height(7.dp),
        )

        val statusLabel =
          when {
            item.claimed -> "CLAIMED"
            item.unlocked -> "UNLOCKED (UNCLAIMED)"
            else -> "LOCKED"
          }
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = "${item.currentValue} / ${spec.targetValue} ${spec.unitLabel} • $statusLabel",
            style = MaterialTheme.typography.labelMedium,
            color = accent,
            modifier = Modifier.testTag("achievement_status_${spec.id}"),
          )

          Button(
            onClick = { onClaimAchievement(spec.id) },
            enabled = item.canClaim,
            colors = ButtonDefaults.buttonColors(containerColor = DripGoldAccent),
            modifier =
              Modifier.minimumInteractiveComponentSize().testTag("achievement_claim_${spec.id}"),
          ) {
            Text(
              text = if (item.claimed) "Claimed" else "Claim +${spec.rewardCoins}",
              color = DripGraphiteBg,
              fontWeight = FontWeight.Bold,
            )
          }
        }
      }
    }
  }
}

@Composable
private fun EnvironmentLevelsTabContent(
  environments: List<EnvironmentItemStatus>,
  lifetimeDistanceMeters: Double,
  selectedQualityPresetId: String,
  scenerySummary: String,
  raceInProgress: Boolean,
  onSelectEnvironment: (String) -> Unit,
  onSelectGraphicsQuality: (GraphicsQualityPreset) -> Unit,
  onImportValleySceneryGlb: () -> Unit,
) {
  Surface(
    color = DripSurfaceElevated,
    shape = RoundedCornerShape(12.dp),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Text(
        text = "Lifetime Distance: ${lifetimeDistanceMeters.toInt()} m • Starter & all Garage cars supported",
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = DripEmeraldOk,
        modifier = Modifier.testTag("levels_lifetime_distance_text"),
      )
      Text(
        text = ProgressionCatalog.PHASE_7_ENVIRONMENT_HONEST_NOTE,
        style = MaterialTheme.typography.labelSmall,
        color = DripMetallicSilver,
      )
      if (scenerySummary.isNotBlank()) {
        Text(
          text = scenerySummary,
          style = MaterialTheme.typography.labelSmall,
          color = DripElectricBlue,
          modifier = Modifier.testTag("levels_scenery_summary_text"),
        )
      }
      Text(
        text = RadianceHdrDecoder.INTEGRATION_ROUTE_LABEL,
        style = MaterialTheme.typography.labelSmall,
        color = DripTextMuted,
        modifier = Modifier.testTag("levels_hdr_route_text"),
      )
      if (scenerySummary.contains("pending", ignoreCase = true)) {
        OutlinedButton(
          onClick = onImportValleySceneryGlb,
          modifier =
            Modifier.fillMaxWidth()
              .minimumInteractiveComponentSize()
              .testTag("import_valley_scenery_button"),
        ) {
          Text(
            text = stringResource(R.string.action_import_valley_glb),
            color = DripGoldAccent,
            fontWeight = FontWeight.Bold,
          )
        }
      }
      if (raceInProgress) {
        Text(
          text = "Race paused: selecting a level or quality preset applies on your NEXT race (no mid-run world swap).",
          style = MaterialTheme.typography.labelSmall,
          color = DripGoldAccent,
          modifier = Modifier.testTag("levels_next_race_notice"),
        )
      }
    }
  }

  Surface(
    color = DripSurfaceElevated,
    shape = RoundedCornerShape(12.dp),
    modifier = Modifier.fillMaxWidth().testTag("graphics_quality_card"),
  ) {
    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Text(
        text = "GRAPHICS QUALITY & SCENERY BUDGET",
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = DripGoldAccent,
      )
      Text(
        text = "Low quality reduces draw distance, pool density, particles & far backdrop while preserving identical 60 Hz collision fairness.",
        style = MaterialTheme.typography.labelSmall,
        color = DripMetallicSilver,
      )
      Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        GraphicsQualityPreset.entries.chunked(2).forEach { rowPresets ->
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            rowPresets.forEach { preset ->
              val isSelected = selectedQualityPresetId == preset.id
              if (isSelected) {
                Button(
                  onClick = { onSelectGraphicsQuality(preset) },
                  colors = ButtonDefaults.buttonColors(containerColor = DripElectricBlue),
                  modifier =
                    Modifier.weight(1f)
                      .minimumInteractiveComponentSize()
                      .testTag("quality_preset_${preset.id}"),
                ) {
                  Text(
                    text = preset.displayName,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                  )
                }
              } else {
                OutlinedButton(
                  onClick = { onSelectGraphicsQuality(preset) },
                  modifier =
                    Modifier.weight(1f)
                      .minimumInteractiveComponentSize()
                      .testTag("quality_preset_${preset.id}"),
                ) {
                  Text(
                    text = preset.displayName,
                    style = MaterialTheme.typography.labelMedium,
                    color = DripMetallicSilver,
                    maxLines = 1,
                  )
                }
              }
            }
          }
        }
      }
    }
  }

  environments.forEach { env ->
    val spec = env.spec
    val borderColor =
      when {
        env.selectedForNextRace -> DripEmeraldOk
        env.unlocked -> DripElectricBlue
        else -> DripTextMuted
      }
    Surface(
      color = DripSurfaceElevated,
      shape = RoundedCornerShape(12.dp),
      modifier =
        Modifier.fillMaxWidth()
          .border(1.dp, borderColor.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
          .testTag("environment_card_${spec.id}"),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
          Text(
            text = "Level ${spec.levelNumber} • ${spec.displayName}",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = DripTextPrimary,
          )
          Text(
            text = spec.themeSummary,
            style = MaterialTheme.typography.bodySmall,
            color = DripMetallicSilver,
          )
          val badge =
            when {
              env.selectedForNextRace && env.activeInCurrentScene -> "UNLOCKED • ACTIVE"
              env.selectedForNextRace -> "UNLOCKED • SELECTED FOR NEXT RACE"
              env.unlocked -> "UNLOCKED (FREE)"
              else -> "LOCKED • Requires ${spec.requiredLifetimeMeters.toInt()} m lifetime"
            }
          Text(
            text = badge,
            style = MaterialTheme.typography.labelMedium,
            color = borderColor,
            modifier = Modifier.testTag("environment_status_${spec.id}"),
          )
        }

        Spacer(modifier = Modifier.width(10.dp))

        if (env.unlocked) {
          FilledTonalButton(
            onClick = { onSelectEnvironment(spec.id) },
            enabled = !env.selectedForNextRace,
            modifier =
              Modifier.minimumInteractiveComponentSize().testTag("environment_select_${spec.id}"),
          ) {
            Icon(
              imageVector = Icons.Filled.CheckCircle,
              contentDescription = null,
              tint = if (env.selectedForNextRace) DripEmeraldOk else DripElectricBlue,
              modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
              text =
                if (env.selectedForNextRace) stringResource(R.string.action_selected_level)
                else stringResource(R.string.action_select_level),
              fontWeight = FontWeight.Bold,
            )
          }
        } else {
          Icon(
            imageVector = Icons.Filled.Lock,
            contentDescription = "Locked environment",
            tint = DripTextMuted,
            modifier = Modifier.padding(8.dp),
          )
        }
      }
    }
  }
}

@Composable
private fun DailyRewardsTabContent(
  dailyLogin: DailyLoginStatus?,
  onClaimDailyLogin: () -> Unit,
) {
  if (dailyLogin == null) return

  Surface(
    color = DripSurfaceElevated,
    shape = RoundedCornerShape(12.dp),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Text(
        text = "7-Day Manual Login Calendar (Cycle Total: ${ProgressionCatalog.DAILY_REWARD_CYCLE_TOTAL} coins)",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = DripTextPrimary,
      )
      Text(
        text = "Calendar Date: ${dailyLogin.todayDateKey} • Timezone: ${dailyLogin.profileTimeZoneId}",
        style = MaterialTheme.typography.labelMedium,
        color = DripElectricBlue,
        modifier = Modifier.testTag("daily_date_text"),
      )
      Text(
        text = ProgressionCatalog.OFFLINE_CLOCK_LIMIT_NOTE,
        style = MaterialTheme.typography.labelSmall,
        color = DripTextMuted,
      )
      dailyLogin.clockNotice?.let { notice ->
        Text(
          text = notice,
          style = MaterialTheme.typography.labelMedium,
          fontWeight = FontWeight.Bold,
          color = if (dailyLogin.clockRollbackDetected) DripAlertRed else DripGoldAccent,
          modifier = Modifier.testTag("daily_clock_notice_text"),
        )
      }
    }
  }

  dailyLogin.slots.forEach { slot ->
    val borderTint =
      when {
        slot.isClaimableToday -> DripGoldAccent
        slot.isClaimedInCycle -> DripEmeraldOk
        slot.isNextTarget -> DripElectricBlue
        else -> DripTextMuted
      }
    Surface(
      color = DripSurfaceElevated,
      shape = RoundedCornerShape(12.dp),
      modifier =
        Modifier.fillMaxWidth()
          .border(1.dp, borderTint.copy(alpha = 0.65f), RoundedCornerShape(12.dp))
          .testTag("daily_slot_day_${slot.dayNumber}"),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = "DAY ${slot.dayNumber}",
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.Black,
          color = DripTextPrimary,
        )
        val stateLabel =
          when {
            slot.isClaimableToday -> "ELIGIBLE TODAY"
            slot.isClaimedInCycle -> "CLAIMED"
            slot.isNextTarget -> "NEXT CONSECUTIVE DAY"
            else -> "UPCOMING"
          }
        Text(
          text = "+${slot.rewardCoins} coins • $stateLabel",
          style = MaterialTheme.typography.labelLarge,
          fontWeight = FontWeight.Bold,
          color = borderTint,
        )
      }
    }
  }

  Button(
    onClick = onClaimDailyLogin,
    enabled = dailyLogin.canClaimToday,
    colors = ButtonDefaults.buttonColors(containerColor = DripGoldAccent),
    modifier =
      Modifier.fillMaxWidth()
        .height(52.dp)
        .minimumInteractiveComponentSize()
        .testTag("daily_claim_button"),
  ) {
    val label =
      when {
        dailyLogin.clockRollbackDetected -> "Blocked: Clock Rollback Detected"
        dailyLogin.alreadyClaimedToday -> "Claimed for ${dailyLogin.todayDateKey}"
        else -> "Claim Day ${dailyLogin.eligibleDayNumber} (+${dailyLogin.eligibleRewardCoins} Coins)"
      }
    Text(
      text = label,
      style = MaterialTheme.typography.titleMedium,
      fontWeight = FontWeight.Black,
      color = if (dailyLogin.canClaimToday) DripGraphiteBg else DripTextPrimary,
    )
  }
}

@Composable
private fun ProfileStatsTabContent(walletSnapshot: WalletSnapshot) {
  val unlockedAch = walletSnapshot.achievements.count { it.unlocked }
  val claimedAch = walletSnapshot.achievements.count { it.claimed }

  Surface(
    color = DripSurfaceElevated,
    shape = RoundedCornerShape(12.dp),
    modifier = Modifier.fillMaxWidth().testTag("profile_stats_card"),
  ) {
    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(
        text = "LIFETIME DRIVER STATISTICS",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Black,
        color = DripGoldAccent,
      )
      StatLine("Lifetime Distance", "${"%.1f".format(Locale.US, walletSnapshot.lifetimeDistanceMeters)} m", "profile_lifetime_distance_text")
      StatLine("Active Racing Time", "${"%.1f".format(Locale.US, walletSnapshot.lifetimeActiveRaceSeconds)} s", "profile_lifetime_time_text")
      StatLine("Completed Races", "${walletSnapshot.lifetimeRacesCompleted}", "profile_lifetime_races_text")
      StatLine("Best Score", "${walletSnapshot.bestScore}", "profile_best_score_text")
      StatLine("Longest Single Race", "${"%.1f".format(Locale.US, walletSnapshot.longestSingleRaceDistanceMeters)} m", "profile_longest_race_text")
      StatLine("Longest Survival Time", "${"%.1f".format(Locale.US, walletSnapshot.longestSingleRaceSurvivalSeconds)} s", "profile_longest_survival_text")
      StatLine("Unique Overtakes", "${walletSnapshot.lifetimeUniqueOvertakes}", "profile_overtakes_text")
      StatLine("Valid Near Misses", "${walletSnapshot.lifetimeUniqueNearMisses}", "profile_near_misses_text")
      StatLine("Qualified Manual Nitro Uses", "${walletSnapshot.lifetimeQualifiedManualNitroUses}", "profile_manual_nitro_text")
      StatLine("Physical Coin Pickups", "${walletSnapshot.totalPhysicalCoinPickups}", "profile_physical_pickups_text")
      StatLine("Collectible Pickup Currency", "${walletSnapshot.totalCollectibleCurrencyEarned} coins", "profile_collectible_earned_text")
      StatLine("Claimed Mission/Trophy/Login Rewards", "${walletSnapshot.lifetimeRewardCoinsClaimed} coins", "profile_reward_claimed_text")
      StatLine("Power-Ups Collected", "${walletSnapshot.totalPowerUpsCollected}", "profile_powerups_text")
      StatLine("Shield Impacts Blocked", "${walletSnapshot.totalShieldBlocks}", "profile_shield_blocks_text")
      StatLine("Vehicles Owned", "${walletSnapshot.ownedVehicleIds.size} / 5", "profile_vehicles_owned_text")
      StatLine("Upgrades Purchased", "${walletSnapshot.lifetimeUpgradesPurchased}", "profile_upgrades_purchased_text")
      StatLine("Daily Missions Completed / Claimed", "${walletSnapshot.lifetimeMissionsCompleted} / ${walletSnapshot.lifetimeMissionsClaimed}", "profile_missions_summary_text")
      StatLine("Achievements Unlocked / Claimed", "$unlockedAch / $claimedAch (of 8)", "profile_achievements_summary_text")
      StatLine("Environments Unlocked", "${walletSnapshot.unlockedEnvironmentIds.size} / 5", "profile_levels_unlocked_text")
      StatLine("Daily Login Rewards Claimed", "${walletSnapshot.lifetimeDailyRewardsClaimed}", "profile_daily_claims_text")
      StatLine("Profile IANA Timezone", walletSnapshot.profileTimeZoneId, "profile_timezone_text")
    }
  }
}

@Composable
private fun SettingsTabContent(
  walletSnapshot: WalletSnapshot,
  audioMixSnapshot: AudioMixSnapshot,
  audioSummary: String,
  hasHardwareVibrator: Boolean,
  raceInProgress: Boolean,
  onUpdateAudioSettings: (AudioSettingsState) -> Unit,
  onSelectGraphicsQuality: (GraphicsQualityPreset) -> Unit,
) {
  val settings = walletSnapshot.audioSettings
  val activeCarProfile = AudioCatalog.profileForVehicle(walletSnapshot.selectedVehicleId)

  // 1. Audio Buses & Volume Card
  Surface(
    color = DripSurfaceElevated,
    shape = RoundedCornerShape(12.dp),
    modifier = Modifier.fillMaxWidth().testTag("settings_audio_card"),
  ) {
    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = "NATIVE AUDIO MIX & VOLUME",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Black,
            color = DripGoldAccent,
          )
          Text(
            text =
              if (settings.muted) "All 5 buses muted (saved persistently)"
              else "5 active buses: MASTER • ENGINE • GAMEPLAY_SFX • AMBIENCE • MUSIC",
            style = MaterialTheme.typography.labelSmall,
            color = if (settings.muted) DripAlertRed else DripMetallicSilver,
            modifier = Modifier.testTag("settings_mute_status_text"),
          )
        }
        FilledTonalButton(
          onClick = { onUpdateAudioSettings(settings.copy(muted = !settings.muted)) },
          modifier = Modifier.minimumInteractiveComponentSize().testTag("settings_mute_toggle"),
        ) {
          Icon(
            imageVector =
              if (settings.muted) Icons.AutoMirrored.Filled.VolumeOff
              else Icons.AutoMirrored.Filled.VolumeUp,
            contentDescription = null,
            tint = if (settings.muted) DripAlertRed else DripEmeraldOk,
            modifier = Modifier.size(18.dp),
          )
          Spacer(modifier = Modifier.width(6.dp))
          Text(
            text = if (settings.muted) "Unmute" else "Mute All",
            fontWeight = FontWeight.Bold,
          )
        }
      }

      VolumeSliderRow(
        title = "Master Bus Volume",
        value = settings.masterVolume,
        enabled = !settings.muted,
        valueTag = "settings_master_volume_text",
        sliderTag = "settings_master_volume_slider",
        minusTag = "settings_master_minus_button",
        plusTag = "settings_master_plus_button",
        onValueChange = { next ->
          onUpdateAudioSettings(settings.copy(masterVolume = next))
        },
      )

      ToggleSettingRow(
        title = "Music Bus (Menu & Race Loops)",
        subtitle = "Streaming 48 kHz WAV loop without memory bloat or duplicate tracks",
        checked = settings.musicEnabled,
        toggleTag = "settings_music_toggle",
        onCheckedChange = { enabled ->
          onUpdateAudioSettings(settings.copy(musicEnabled = enabled))
        },
      )

      VolumeSliderRow(
        title = "Music Volume",
        value = settings.musicVolume,
        enabled = !settings.muted && settings.musicEnabled,
        valueTag = "settings_music_volume_text",
        sliderTag = "settings_music_volume_slider",
        minusTag = "settings_music_minus_button",
        plusTag = "settings_music_plus_button",
        onValueChange = { next ->
          onUpdateAudioSettings(settings.copy(musicVolume = next))
        },
      )

      ToggleSettingRow(
        title = "Engine, Gameplay SFX & Ambience",
        subtitle = "Controls ENGINE, GAMEPLAY_SFX, and AMBIENCE buses together",
        checked = settings.sfxEnabled,
        toggleTag = "settings_sfx_toggle",
        onCheckedChange = { enabled ->
          onUpdateAudioSettings(settings.copy(sfxEnabled = enabled))
        },
      )

      VolumeSliderRow(
        title = "SFX / Engine Volume",
        value = settings.sfxVolume,
        enabled = !settings.muted && settings.sfxEnabled,
        valueTag = "settings_sfx_volume_text",
        sliderTag = "settings_sfx_volume_slider",
        minusTag = "settings_sfx_minus_button",
        plusTag = "settings_sfx_plus_button",
        onValueChange = { next ->
          onUpdateAudioSettings(settings.copy(sfxVolume = next))
        },
      )
    }
  }

  // 2. Accessibility, Reduced Motion & Capability-Checked Haptics Card
  Surface(
    color = DripSurfaceElevated,
    shape = RoundedCornerShape(12.dp),
    modifier = Modifier.fillMaxWidth().testTag("settings_accessibility_card"),
  ) {
    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Text(
        text = "ACCESSIBILITY, MOTION & HAPTICS",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Black,
        color = DripElectricBlue,
      )

      ToggleSettingRow(
        title = "Reduced Motion",
        subtitle =
          "Dampens impact camera shake, high-speed FOV surges & chassis roll while keeping identical 60 Hz collision fairness",
        checked = settings.reducedMotion,
        toggleTag = "settings_reduced_motion_toggle",
        onCheckedChange = { enabled ->
          onUpdateAudioSettings(settings.copy(reducedMotion = enabled))
        },
      )

      ToggleSettingRow(
        title = "Optional Haptics",
        subtitle =
          if (hasHardwareVibrator) {
            "Hardware vibrator detected: tactile pulses active for collisions, Nitro & pickups"
          } else {
            "No hardware vibrator reported on this device/emulator — preference saved safely (no vibration emitted)"
          },
        checked = settings.hapticsEnabled,
        toggleTag = "settings_haptics_toggle",
        subtitleTag = "settings_haptics_capability_text",
        onCheckedChange = { enabled ->
          onUpdateAudioSettings(settings.copy(hapticsEnabled = enabled))
        },
      )
    }
  }

  // 3. Graphics Quality Preset Card (consistent with Levels tab)
  Surface(
    color = DripSurfaceElevated,
    shape = RoundedCornerShape(12.dp),
    modifier = Modifier.fillMaxWidth().testTag("settings_graphics_card"),
  ) {
    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Text(
        text = "GRAPHICS QUALITY PRESET",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Black,
        color = DripGoldAccent,
      )
      if (raceInProgress) {
        Text(
          text = "Race paused: quality preset changes apply cleanly on your next race.",
          style = MaterialTheme.typography.labelSmall,
          color = DripGoldAccent,
        )
      }
      Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        GraphicsQualityPreset.entries.chunked(2).forEach { rowPresets ->
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            rowPresets.forEach { preset ->
              val isSelected = walletSnapshot.graphicsQualityPresetId == preset.id
              if (isSelected) {
                Button(
                  onClick = { onSelectGraphicsQuality(preset) },
                  colors = ButtonDefaults.buttonColors(containerColor = DripElectricBlue),
                  modifier =
                    Modifier.weight(1f)
                      .minimumInteractiveComponentSize()
                      .testTag("settings_quality_${preset.id}"),
                ) {
                  Text(preset.displayName, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, maxLines = 1)
                }
              } else {
                OutlinedButton(
                  onClick = { onSelectGraphicsQuality(preset) },
                  modifier =
                    Modifier.weight(1f)
                      .minimumInteractiveComponentSize()
                      .testTag("settings_quality_${preset.id}"),
                ) {
                  Text(preset.displayName, style = MaterialTheme.typography.labelMedium, color = DripMetallicSilver, maxLines = 1)
                }
              }
            }
          }
        }
      }
    }
  }

  // 4. Honest Audio Provenance & 5-Car Synthesized Layer Disclosure Card
  Surface(
    color = DripSurfaceElevated,
    shape = RoundedCornerShape(12.dp),
    modifier = Modifier.fillMaxWidth().testTag("settings_audio_provenance_card"),
  ) {
    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Text(
        text = "PREPARED AUDIO & ENGINE PROFILE PROVENANCE",
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = DripEmeraldOk,
      )
      Text(
        text = audioSummary.ifBlank { audioMixSnapshot.auditSummary },
        style = MaterialTheme.typography.labelMedium,
        color = DripTextPrimary,
        modifier = Modifier.testTag("settings_audio_status_text"),
      )
      Text(
        text =
          "Equipped Engine Profile: ${activeCarProfile.profileName} • ${activeCarProfile.synthesizedLayerLabel}",
        style = MaterialTheme.typography.labelSmall,
        color = DripElectricBlue,
        modifier = Modifier.testTag("settings_engine_synth_label_text"),
      )
      Text(
        text =
          "Headroom Margin: ${"%.1f".format(Locale.US, audioMixSnapshot.headroomMarginDb)} dB below 0 dBFS • Active Voices: ${audioMixSnapshot.activeOneShotVoices}/${AudioCatalog.MAX_CONCURRENT_ONESHOT_VOICES} • Offline export gainDb/trim/overlap not reapplied.",
        style = MaterialTheme.typography.labelSmall,
        color = DripMetallicSilver,
        modifier = Modifier.testTag("settings_headroom_text"),
      )
      Text(
        text = AudioCatalog.PROVENANCE_SUMMARY,
        style = MaterialTheme.typography.labelSmall,
        color = DripTextMuted,
      )
    }
  }
}

@Composable
private fun VolumeSliderRow(
  title: String,
  value: Float,
  enabled: Boolean,
  valueTag: String,
  sliderTag: String,
  minusTag: String,
  plusTag: String,
  onValueChange: (Float) -> Unit,
) {
  val pct = (value * 100f).toInt()
  Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = if (enabled) DripTextPrimary else DripTextMuted,
      )
      Text(
        text = "$pct%",
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Black,
        color = if (enabled) DripEmeraldOk else DripTextMuted,
        modifier = Modifier.testTag(valueTag),
      )
    }
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      OutlinedButton(
        onClick = { onValueChange((value - 0.10f).coerceIn(0f, 1f)) },
        enabled = enabled && value > 0.001f,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        modifier = Modifier.minimumInteractiveComponentSize().testTag(minusTag),
      ) {
        Text("-10%", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
      }
      Slider(
        value = value.coerceIn(0f, 1f),
        onValueChange = { onValueChange(it.coerceIn(0f, 1f)) },
        enabled = enabled,
        modifier = Modifier.weight(1f).testTag(sliderTag),
      )
      OutlinedButton(
        onClick = { onValueChange((value + 0.10f).coerceIn(0f, 1f)) },
        enabled = enabled && value < 0.999f,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        modifier = Modifier.minimumInteractiveComponentSize().testTag(plusTag),
      ) {
        Text("+10%", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
      }
    }
  }
}

@Composable
private fun ToggleSettingRow(
  title: String,
  subtitle: String,
  checked: Boolean,
  toggleTag: String,
  subtitleTag: String? = null,
  onCheckedChange: (Boolean) -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
      Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = DripTextPrimary,
      )
      Text(
        text = subtitle,
        style = MaterialTheme.typography.labelSmall,
        color = DripMetallicSilver,
        modifier = if (subtitleTag != null) Modifier.testTag(subtitleTag) else Modifier,
      )
    }
    Switch(
      checked = checked,
      onCheckedChange = onCheckedChange,
      modifier = Modifier.minimumInteractiveComponentSize().testTag(toggleTag),
    )
  }
}

@Composable
private fun StatLine(label: String, value: String, tag: String) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.bodyMedium,
      color = DripMetallicSilver,
    )
    Text(
      text = value,
      style = MaterialTheme.typography.bodyMedium,
      fontWeight = FontWeight.Bold,
      color = DripTextPrimary,
      modifier = Modifier.testTag(tag),
    )
  }
}
