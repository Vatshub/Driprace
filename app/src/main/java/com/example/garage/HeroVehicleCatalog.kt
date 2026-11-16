package com.example.garage

import com.example.sim.VehiclePhysicsConfig
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.pow

enum class HeroWheelMode {
  STARTER_GROUP,
  INDEXED_NODES_X,
  CRIMSON_CARRIER_YAW_AND_NODES_X,
  NISSAN_PIVOT_GROUPS_Z,
  BMW_SPLIT_HALVES_X,
}

enum class UpgradeTrack(
  val id: String,
  val displayName: String,
  val summaryFormula: String,
) {
  ENGINE("engine", "Engine", "+6 km/h top speed & +0.35 m/s² accel / lvl"),
  HANDLING("handling", "Handling", "max(0.30, -0.025 s) lane change / lvl"),
  BRAKES("brakes", "Brakes", "+0.8 m/s² braking decel / lvl"),
  NITRO("nitro", "Nitro", "max(15, -1.5/s) drain & +0.5/s regen / lvl"),
}

data class VehicleUpgradeLevels(
  val engine: Int = 0,
  val handling: Int = 0,
  val brakes: Int = 0,
  val nitro: Int = 0,
) {
  init {
    require(engine in 0..MAX_LEVEL && handling in 0..MAX_LEVEL && brakes in 0..MAX_LEVEL && nitro in 0..MAX_LEVEL) {
      "Upgrade levels must be within 0..$MAX_LEVEL."
    }
  }

  fun levelFor(track: UpgradeTrack): Int =
    when (track) {
      UpgradeTrack.ENGINE -> engine
      UpgradeTrack.HANDLING -> handling
      UpgradeTrack.BRAKES -> brakes
      UpgradeTrack.NITRO -> nitro
    }

  fun withIncremented(track: UpgradeTrack): VehicleUpgradeLevels {
    val cur = levelFor(track)
    require(cur < MAX_LEVEL) { "Track ${track.displayName} is already at max level $MAX_LEVEL." }
    val next = cur + 1
    return when (track) {
      UpgradeTrack.ENGINE -> copy(engine = next)
      UpgradeTrack.HANDLING -> copy(handling = next)
      UpgradeTrack.BRAKES -> copy(brakes = next)
      UpgradeTrack.NITRO -> copy(nitro = next)
    }
  }

  companion object {
    const val MAX_LEVEL = 3
  }
}

data class BodyPaintColor(
  val id: String,
  val displayName: String,
  val srgbHex: Long,
) {
  val linearRgb: FloatArray
    get() {
      val r = (((srgbHex shr 16) and 0xFF) / 255.0).srgbChannelToLinear()
      val g = (((srgbHex shr 8) and 0xFF) / 255.0).srgbChannelToLinear()
      val b = ((srgbHex and 0xFF) / 255.0).srgbChannelToLinear()
      return floatArrayOf(r, g, b)
    }

  private fun Double.srgbChannelToLinear(): Float =
    if (this <= 0.04045) {
      (this / 12.92).toFloat()
    } else {
      (((this + 0.055) / 1.055).pow(2.4)).toFloat()
    }
}

object PaintCatalog {
  val SEVEN_FREE_COLORS: List<BodyPaintColor> =
    listOf(
      BodyPaintColor("metallic_red", "Metallic Red", 0xFFC4161CL),
      BodyPaintColor("electric_blue", "Electric Blue", 0xFF1677FFL),
      BodyPaintColor("pearl_white", "Pearl White", 0xFFF2F4F8L),
      BodyPaintColor("midnight_black", "Midnight Black", 0xFF14161CL),
      BodyPaintColor("titanium_silver", "Titanium Silver", 0xFFA5AEB8L),
      BodyPaintColor("racing_yellow", "Racing Yellow", 0xFFF5C400L),
      BodyPaintColor("deep_emerald_green", "Deep Emerald Green", 0xFF0F6E48L),
    )

  fun findById(id: String?): BodyPaintColor? = SEVEN_FREE_COLORS.firstOrNull { it.id == id }
}

data class HeroVehicleSpec(
  val id: String,
  val displayName: String,
  val subtitleTag: String,
  val unlockPrice: Long,
  val fileName: String,
  val sourceSha256: String,
  val yawRadians: Float,
  val targetLengthMeters: Float,
  val collisionWidthMeters: Float,
  val collisionHeightMeters: Float,
  val baseTopSpeedKmh: Double,
  val baseAccelerationMps2: Double,
  val baseBrakeDecelerationMps2: Double,
  val baseLaneChangeDurationSeconds: Double,
  val baseNitroDrainPerSecond: Double,
  val paintMaterialIndices: List<Int>,
  val defaultPaintId: String,
  val wheelMode: HeroWheelMode,
  val wheelNodeGroups: List<List<Int>> = emptyList(),
  val crimsonCarrierIndices: List<Int> = emptyList(),
  val upgradeLevelPrices: List<Long>,
  val onDeviceImportOnly: Boolean = false,
  val sourceRestrictionNote: String? = null,
) {
  fun nextUpgradeCost(currentLevel: Int): Long? {
    if (currentLevel !in 0 until VehicleUpgradeLevels.MAX_LEVEL) return null
    return upgradeLevelPrices[currentLevel]
  }

  fun computeEffectivePhysics(upgrades: VehicleUpgradeLevels = VehicleUpgradeLevels()): VehiclePhysicsConfig {
    val effectiveTopSpeedKmh = baseTopSpeedKmh + 6.0 * upgrades.engine
    val effectiveAccel = baseAccelerationMps2 + 0.35 * upgrades.engine
    val effectiveLaneChange = max(0.30, baseLaneChangeDurationSeconds - 0.025 * upgrades.handling)
    val effectiveBrake = baseBrakeDecelerationMps2 + 0.8 * upgrades.brakes
    val effectiveDrain = max(15.0, baseNitroDrainPerSecond - 1.5 * upgrades.nitro)
    val effectiveRegen = 12.0 + 0.5 * upgrades.nitro
    return VehiclePhysicsConfig(
      id = id,
      displayName = displayName,
      topSpeedKmh = effectiveTopSpeedKmh,
      accelerationMps2 = effectiveAccel,
      brakeDecelerationMps2 = effectiveBrake,
      laneChangeDurationSeconds = effectiveLaneChange,
      nitroDrainPerSecond = effectiveDrain,
      nitroRegenPerSecond = effectiveRegen,
      nitroSpeedMultiplier = NITRO_SPEED_MULTIPLIER,
      nitroAccelMultiplier = NITRO_ACCEL_MULTIPLIER,
      collisionWidthMeters = collisionWidthMeters,
      collisionHeightMeters = collisionHeightMeters,
      collisionLengthMeters = targetLengthMeters,
    )
  }

  companion object {
    const val NITRO_SPEED_MULTIPLIER = 1.25
    const val NITRO_ACCEL_MULTIPLIER = 1.65
  }
}

object HeroVehicleCatalog {
  const val STARTER_ID = "starter_sports"
  const val SUPER_RACING_ID = "super_racing"
  const val MUSCLE_ID = "muscle"
  const val HYPER_SPORTS_ID = "hyper_sports"
  const val LEGENDARY_ID = "legendary_supercar"

  val ALL_CARS: List<HeroVehicleSpec> =
    listOf(
      HeroVehicleSpec(
        id = STARTER_ID,
        displayName = "Starter Sports Car",
        subtitleTag = "Modern Coupe • 3 Hearts",
        unlockPrice = 0L,
        fileName = "starter-sports.glb",
        sourceSha256 = "c4246bb38128e6a129af8e176bb8d9474c961fdc713d854b34805cd41e6c7008",
        yawRadians = 0f,
        targetLengthMeters = 4.357386f,
        collisionWidthMeters = 1.88f,
        collisionHeightMeters = 1.38f,
        baseTopSpeedKmh = 180.0,
        baseAccelerationMps2 = 5.5,
        baseBrakeDecelerationMps2 = 12.0,
        baseLaneChangeDurationSeconds = 0.48,
        baseNitroDrainPerSecond = 28.0,
        paintMaterialIndices = listOf(10, 11),
        defaultPaintId = "metallic_red",
        wheelMode = HeroWheelMode.STARTER_GROUP,
        upgradeLevelPrices = listOf(100L, 200L, 350L),
      ),
      HeroVehicleSpec(
        id = SUPER_RACING_ID,
        displayName = "Super Racing Car",
        subtitleTag = "Repaired Veloce Track Racer • 3 Hearts",
        unlockPrice = 500L,
        fileName = "veloce-atelier-metallic-repaired.glb",
        sourceSha256 = "14ad19d473dc693dbea05d5cbcd9739d57c3f672ae3a5021ec6e6343ce7d367d",
        yawRadians = 0f,
        targetLengthMeters = 4.693f,
        collisionWidthMeters = 1.92f,
        collisionHeightMeters = 1.28f,
        baseTopSpeedKmh = 200.0,
        baseAccelerationMps2 = 6.2,
        baseBrakeDecelerationMps2 = 12.5,
        baseLaneChangeDurationSeconds = 0.44,
        baseNitroDrainPerSecond = 26.0,
        paintMaterialIndices = listOf(0),
        defaultPaintId = "metallic_red",
        wheelMode = HeroWheelMode.INDEXED_NODES_X,
        wheelNodeGroups = listOf(listOf(157), listOf(188), listOf(219), listOf(250)),
        upgradeLevelPrices = listOf(175L, 350L, 600L),
      ),
      HeroVehicleSpec(
        id = MUSCLE_ID,
        displayName = "Muscle Car",
        subtitleTag = "Original Crimson V8 Shaker • 3 Hearts",
        unlockPrice = 1000L,
        fileName = "crimson-v8-shaker-muscle-car.glb",
        sourceSha256 = "89688f9e5c90dbdbf9749f2405cff88644ad21511f28512eaa872730ffd8d38b",
        yawRadians = 0f,
        targetLengthMeters = 4.97f,
        collisionWidthMeters = 1.98f,
        collisionHeightMeters = 1.42f,
        baseTopSpeedKmh = 195.0,
        baseAccelerationMps2 = 7.2,
        baseBrakeDecelerationMps2 = 11.5,
        baseLaneChangeDurationSeconds = 0.49,
        baseNitroDrainPerSecond = 29.0,
        paintMaterialIndices = listOf(0),
        defaultPaintId = "metallic_red",
        wheelMode = HeroWheelMode.CRIMSON_CARRIER_YAW_AND_NODES_X,
        wheelNodeGroups = listOf(listOf(144), listOf(181), listOf(218), listOf(255)),
        crimsonCarrierIndices = listOf(140, 177),
        upgradeLevelPrices = listOf(250L, 500L, 850L),
      ),
      HeroVehicleSpec(
        id = HYPER_SPORTS_ID,
        displayName = "Hyper Sports Car",
        subtitleTag = "Nissan Custom Livery (On-Device Import) • 3 Hearts",
        unlockPrice = 2000L,
        fileName = "nissan_gtrs_-_max_nfs_most_wanted_bmw_version.glb",
        sourceSha256 = "1828ca21a3ab001664fc4ccbb2265082d260c1ca2663d7e42100b9113a5e0cd5",
        yawRadians = (PI / 2.0).toFloat(),
        targetLengthMeters = 4.70f,
        collisionWidthMeters = 1.90f,
        collisionHeightMeters = 1.34f,
        baseTopSpeedKmh = 220.0,
        baseAccelerationMps2 = 7.0,
        baseBrakeDecelerationMps2 = 13.0,
        baseLaneChangeDurationSeconds = 0.38,
        baseNitroDrainPerSecond = 24.0,
        paintMaterialIndices = listOf(10, 15),
        defaultPaintId = "pearl_white",
        wheelMode = HeroWheelMode.NISSAN_PIVOT_GROUPS_Z,
        wheelNodeGroups =
          listOf(
            listOf(93, 42, 44, 46),
            listOf(98, 36, 38, 40),
            listOf(95, 22, 52, 56),
            listOf(100, 8, 50, 54),
          ),
        upgradeLevelPrices = listOf(400L, 800L, 1400L),
        onDeviceImportOnly = true,
        sourceRestrictionNote = "NoAI-restricted source: select locally inside the installed app via document picker.",
      ),
      HeroVehicleSpec(
        id = LEGENDARY_ID,
        displayName = "Legendary Supercar",
        subtitleTag = "BMW M4 Widebody (CC BY-NC-SA) • 3 Hearts",
        unlockPrice = 5000L,
        fileName = "bmw_m4_modified_widebody.glb",
        sourceSha256 = "37e1125fedba9ee49c484040025483bfec5aca196d4201309d73e8135019f5ee",
        yawRadians = 0f,
        targetLengthMeters = 4.80f,
        collisionWidthMeters = 1.96f,
        collisionHeightMeters = 1.36f,
        baseTopSpeedKmh = 240.0,
        baseAccelerationMps2 = 8.0,
        baseBrakeDecelerationMps2 = 13.5,
        baseLaneChangeDurationSeconds = 0.35,
        baseNitroDrainPerSecond = 22.0,
        paintMaterialIndices = listOf(0),
        defaultPaintId = "electric_blue",
        wheelMode = HeroWheelMode.BMW_SPLIT_HALVES_X,
        wheelNodeGroups =
          listOf(
            listOf(145, 147, 149, 151, 153),
            listOf(156, 158, 160, 162, 164),
          ),
        upgradeLevelPrices = listOf(600L, 1200L, 2000L),
      ),
    )

  fun findById(id: String?): HeroVehicleSpec? = ALL_CARS.firstOrNull { it.id == id }

  val STARTER_SPEC: HeroVehicleSpec = ALL_CARS.first()
}
