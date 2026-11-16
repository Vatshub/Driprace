package com.example.traffic

import kotlin.math.PI

enum class TrafficCategory(val displayName: String) {
  SEDAN("Sedan"),
  HATCHBACK("Hatchback"),
  SUV("SUV"),
  SPORTS_CAR("Sports Car"),
  TRUCK("Truck"),
  BUS("Bus"),
}

enum class StaticHazardKind(
  val id: String,
  val displayName: String,
  val widthMeters: Float,
  val heightMeters: Float,
  val lengthMeters: Float,
) {
  ROAD_BARRIER(
    id = "hazard_road_barrier",
    displayName = "Road Barrier",
    widthMeters = 2.45f,
    heightMeters = 1.05f,
    lengthMeters = 0.85f,
  ),
  WORK_BARRICADE(
    id = "hazard_work_barricade",
    displayName = "Roadwork Barricade",
    widthMeters = 2.35f,
    heightMeters = 1.12f,
    lengthMeters = 1.10f,
  ),
  CONCRETE_BLOCK(
    id = "hazard_concrete_block",
    displayName = "Concrete Block",
    widthMeters = 2.10f,
    heightMeters = 0.92f,
    lengthMeters = 1.55f,
  ),
  BROKEN_DOWN_CAR(
    id = "hazard_broken_down_car",
    displayName = "Broken-Down Car",
    widthMeters = 1.86f,
    heightMeters = 1.42f,
    lengthMeters = 4.30f,
  ),
}

enum class TrafficWheelMode {
  GROUP_NODES,
  SKINNED_BONES,
}

data class ApprovedTrafficSpec(
  val id: String,
  val fileName: String,
  val displayName: String,
  val category: TrafficCategory,
  val sourceSha256: String,
  val yawRadians: Float,
  val targetLengthMeters: Float,
  val collisionWidthMeters: Float,
  val collisionHeightMeters: Float,
  val minCruiseSpeedMps: Double,
  val maxCruiseSpeedMps: Double,
  val wheelMode: TrafficWheelMode = TrafficWheelMode.GROUP_NODES,
  val wheelAxis: Char = 'x',
  val wheelNodeGroups: List<List<Int>> = emptyList(),
  val needsFrontWheelStraightening: Boolean = false,
  val onDeviceImportOnly: Boolean = false,
  val isExplicitlyMoving: Boolean = true,
)

enum class TrafficModelOrigin {
  BUNDLED_OFFLINE_GLB,
  ON_DEVICE_RUNTIME_IMPORT_GLB,
  PROCEDURAL_FALLBACK_PENDING_IMPORT,
}

data class TrafficModelAssetState(
  val id: String,
  val fileName: String,
  val displayName: String,
  val category: TrafficCategory,
  val origin: TrafficModelOrigin,
  val sha256Verified: Boolean,
  val onDeviceImportOnly: Boolean,
  val statusSummary: String,
)

object TrafficCatalog {
  const val PLAYER_WIDTH_METERS = 1.88f
  const val PLAYER_HEIGHT_METERS = 1.38f
  const val PLAYER_LENGTH_METERS = 4.357386f

  val APPROVED_TRAFFIC_VEHICLES: List<ApprovedTrafficSpec> =
    listOf(
      ApprovedTrafficSpec(
        id = "generic-sedan-mmcworks",
        fileName = "generic-sedan-mmcworks.glb",
        displayName = "Generic Sedan (MMCWorks)",
        category = TrafficCategory.SEDAN,
        sourceSha256 = "e51286cbd9d1e582aeaa35450bf426b21d47107206daaa254d5b567825446665",
        yawRadians = 0f,
        targetLengthMeters = 4.978f,
        collisionWidthMeters = 1.88f,
        collisionHeightMeters = 1.48f,
        minCruiseSpeedMps = 22.0,
        maxCruiseSpeedMps = 31.0,
        wheelMode = TrafficWheelMode.GROUP_NODES,
        wheelAxis = 'x',
        wheelNodeGroups =
          listOf(
            listOf(240, 242, 14),
            listOf(246, 248, 16),
            listOf(252, 254, 18),
            listOf(258, 260, 20),
          ),
        needsFrontWheelStraightening = true,
        onDeviceImportOnly = true, // NoAI-restricted: user selects locally inside installed app
      ),
      ApprovedTrafficSpec(
        id = "mmc-generic-small-suv",
        fileName = "mmc-generic-small-suv.glb",
        displayName = "Compact SUV",
        category = TrafficCategory.SUV,
        sourceSha256 = "83956983258e64bc643fe1bded2eb32d175eb9e72052334980a2a8aad77b0773",
        yawRadians = 0f,
        targetLengthMeters = 4.194f,
        collisionWidthMeters = 1.84f,
        collisionHeightMeters = 1.66f,
        minCruiseSpeedMps = 21.0,
        maxCruiseSpeedMps = 29.0,
        wheelMode = TrafficWheelMode.GROUP_NODES,
        wheelAxis = 'x',
        wheelNodeGroups =
          listOf(listOf(59, 61, 57), listOf(67, 69, 65), listOf(75, 77, 73), listOf(83, 85, 81)),
      ),
      ApprovedTrafficSpec(
        id = "european-delivery-van",
        fileName = "european-delivery-van.glb",
        displayName = "Delivery Van",
        category = TrafficCategory.TRUCK,
        sourceSha256 = "624fe078f7d8741b2e80a9f9a754cbe9b63ac3e560a6845be653bf292345acff",
        yawRadians = 0f,
        targetLengthMeters = 5.372f,
        collisionWidthMeters = 2.04f,
        collisionHeightMeters = 2.42f,
        minCruiseSpeedMps = 18.0,
        maxCruiseSpeedMps = 25.0,
        wheelMode = TrafficWheelMode.GROUP_NODES,
        wheelAxis = 'x',
        wheelNodeGroups = listOf(listOf(131), listOf(133), listOf(135), listOf(137)),
      ),
      ApprovedTrafficSpec(
        id = "conquer-89-pickup",
        fileName = "conquer-89-pickup.glb",
        displayName = "Conquer 89 Pickup",
        category = TrafficCategory.TRUCK,
        sourceSha256 = "bf2c8ca11daa135f7499bff194f4f7400202d1f14ccfc13e74686f5273448932",
        yawRadians = -(PI / 2.0).toFloat(),
        targetLengthMeters = 5.219f,
        collisionWidthMeters = 2.00f,
        collisionHeightMeters = 1.84f,
        minCruiseSpeedMps = 20.0,
        maxCruiseSpeedMps = 27.0,
        wheelMode = TrafficWheelMode.GROUP_NODES,
        wheelAxis = 'z',
        wheelNodeGroups = listOf(listOf(63), listOf(66), listOf(69), listOf(72)),
      ),
      ApprovedTrafficSpec(
        id = "lct-3000-95",
        fileName = "lct-3000-95.glb",
        displayName = "Box Truck",
        category = TrafficCategory.TRUCK,
        sourceSha256 = "6cc4dd05a1f0fe056580e942e5bb0cdbdd57f052253684a72b45daf5af21a7d8",
        yawRadians = -(PI / 2.0).toFloat(),
        targetLengthMeters = 6.024f,
        collisionWidthMeters = 2.16f,
        collisionHeightMeters = 2.65f,
        minCruiseSpeedMps = 16.0,
        maxCruiseSpeedMps = 23.0,
        wheelMode = TrafficWheelMode.GROUP_NODES,
        wheelAxis = 'z',
        wheelNodeGroups = listOf(listOf(63), listOf(66), listOf(69), listOf(72)),
      ),
      ApprovedTrafficSpec(
        id = "generic-town-bus",
        fileName = "generic-town-bus.glb",
        displayName = "Town Bus",
        category = TrafficCategory.BUS,
        sourceSha256 = "d0b9a7acd1a2c269fd3184b8caf09ffde149fb9fed9f0c5c233f01aeb40ab8fd",
        yawRadians = (PI / 2.0).toFloat(),
        targetLengthMeters = 11.500f,
        collisionWidthMeters = 2.48f,
        collisionHeightMeters = 3.10f,
        minCruiseSpeedMps = 14.0,
        maxCruiseSpeedMps = 21.0,
        wheelMode = TrafficWheelMode.GROUP_NODES,
        wheelAxis = 'z',
        wheelNodeGroups = listOf(listOf(10), listOf(11), listOf(12, 13, 14, 15)),
      ),
      ApprovedTrafficSpec(
        id = "old_car",
        fileName = "old_car.glb",
        displayName = "Classic Hatchback",
        category = TrafficCategory.HATCHBACK,
        sourceSha256 = "04eb9209abfdd21f695303baf04633e5e5fb3422c643439e07394931fec6d253",
        yawRadians = (PI / 2.0).toFloat(),
        targetLengthMeters = 4.500f,
        collisionWidthMeters = 1.80f,
        collisionHeightMeters = 1.46f,
        minCruiseSpeedMps = 19.0,
        maxCruiseSpeedMps = 27.0,
        wheelMode = TrafficWheelMode.SKINNED_BONES,
        wheelAxis = 'y',
        wheelNodeGroups = listOf(listOf(41, 43, 42, 44)),
      ),
      ApprovedTrafficSpec(
        id = "post-apocalypse__derby_car_-_olpen_aster",
        fileName = "post-apocalypse__derby_car_-_olpen_aster.glb",
        displayName = "Olpen Aster",
        category = TrafficCategory.HATCHBACK,
        sourceSha256 = "9151e4e2cd52c1dc350fdfd4e49c2b8fae68b17074808adf37dd8f1eadbe22a8",
        yawRadians = PI.toFloat(),
        targetLengthMeters = 4.500f,
        collisionWidthMeters = 1.84f,
        collisionHeightMeters = 1.48f,
        minCruiseSpeedMps = 22.0,
        maxCruiseSpeedMps = 30.0,
        wheelMode = TrafficWheelMode.GROUP_NODES,
        wheelAxis = 'x',
        wheelNodeGroups = listOf(listOf(40), listOf(46), listOf(52), listOf(58)),
        isExplicitlyMoving = true, // Contract: Olpen is explicitly moving traffic
      ),
      ApprovedTrafficSpec(
        id = "post-apocalypse__derby_car_toytyta_doubtlux",
        fileName = "post-apocalypse__derby_car_toytyta_doubtlux.glb",
        displayName = "Doubtlux Coupe",
        category = TrafficCategory.SPORTS_CAR,
        sourceSha256 = "aee15ac7c871d92b1d44eef00ee088c6cc76d47c6a9fa4246cd0a33612dfbe39",
        yawRadians = (PI / 2.0).toFloat(),
        targetLengthMeters = 5.158f,
        collisionWidthMeters = 1.94f,
        collisionHeightMeters = 1.42f,
        minCruiseSpeedMps = 26.0,
        maxCruiseSpeedMps = 36.0,
        wheelMode = TrafficWheelMode.GROUP_NODES,
        wheelAxis = 'z',
        wheelNodeGroups = listOf(listOf(172), listOf(180), listOf(188), listOf(196)),
      ),
    )

  fun findById(id: String): ApprovedTrafficSpec? = APPROVED_TRAFFIC_VEHICLES.firstOrNull { it.id == id }

  val ALL_CATEGORIES: List<TrafficCategory> = TrafficCategory.entries
}
