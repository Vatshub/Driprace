package com.example.scenery

import com.example.progression.ProgressionCatalog

/**
 * Measured graphics quality tiers controlling draw distances, LOD switch scale, roadside prop
 * pool budgets, shadow map casting, dynamic lamp count, and restrained particles.
 *
 * Simulation tick rate (fixed 60 Hz), lane geometry, vehicle speeds, and collision hitboxes are
 * strictly invariant across all quality presets.
 */
enum class GraphicsQualityPreset(
  val id: String,
  val displayName: String,
  val maxSceneryDrawDistanceMeters: Float,
  val pineLodDistanceScale: Float,
  val propPoolMultiplier: Float,
  val enableDirectionalShadows: Boolean,
  val enableFarBackdropModels: Boolean,
  val enableParticles: Boolean,
  val activeStreetLightPointCount: Int,
  val hdrDecodeStride: Int,
) {
  AUTO(
    id = "auto",
    displayName = "Auto (Balanced)",
    maxSceneryDrawDistanceMeters = 240f,
    pineLodDistanceScale = 1.0f,
    propPoolMultiplier = 1.0f,
    enableDirectionalShadows = true,
    enableFarBackdropModels = true,
    enableParticles = true,
    activeStreetLightPointCount = 4,
    hdrDecodeStride = 2,
  ),
  HIGH(
    id = "high",
    displayName = "High",
    maxSceneryDrawDistanceMeters = 310f,
    pineLodDistanceScale = 1.15f,
    propPoolMultiplier = 1.25f,
    enableDirectionalShadows = true,
    enableFarBackdropModels = true,
    enableParticles = true,
    activeStreetLightPointCount = 6,
    hdrDecodeStride = 2,
  ),
  MEDIUM(
    id = "medium",
    displayName = "Medium",
    maxSceneryDrawDistanceMeters = 220f,
    pineLodDistanceScale = 0.95f,
    propPoolMultiplier = 0.85f,
    enableDirectionalShadows = true,
    enableFarBackdropModels = true,
    enableParticles = true,
    activeStreetLightPointCount = 3,
    hdrDecodeStride = 2,
  ),
  LOW(
    id = "low",
    displayName = "Low (Max Stability)",
    maxSceneryDrawDistanceMeters = 145f,
    pineLodDistanceScale = 0.68f,
    propPoolMultiplier = 0.55f,
    enableDirectionalShadows = false,
    enableFarBackdropModels = false,
    enableParticles = false,
    activeStreetLightPointCount = 2,
    hdrDecodeStride = 4,
  );

  companion object {
    fun fromId(id: String?): GraphicsQualityPreset =
      entries.firstOrNull { it.id.equals(id?.trim(), ignoreCase = true) } ?: AUTO
  }
}

enum class SceneryAssetKind {
  GLB_SINGLE,
  GLTF_EXTERNAL,
  PBR_GROUND_TEXTURE_SET,
  RADIANCE_HDR,
}

data class SceneryAssetSpec(
  val id: String,
  val displayName: String,
  val kind: SceneryAssetKind,
  val primaryAssetPath: String,
  val expectedSha256: String?,
  val externalDependenciesWithSha256: Map<String, String> = emptyMap(),
  val licenseSpdx: String,
  val authorAttribution: String,
  val isCandidate: Boolean = false,
  val candidateRuntimeNote: String? = null,
  val exceedsCloudUploadLimit: Boolean = false,
  val scaleDependentUvNote: String? = null,
)

enum class GroundSkirtTheme {
  LEAFY_GRASS_PBR,
  AERIAL_SAND_PBR,
  URBAN_CONCRETE_APRON,
  NIGHT_REFLECTIVE_APRON,
}

enum class SceneryPlacementZone {
  SHOULDER_NEAR,
  ROADSIDE_MID,
  SKYLINE_FLANK,
  DESERT_SAND_TERRAIN_10X,
  FAR_VISTA_BACKDROP,
}

data class RoadsidePropRule(
  val assetId: String,
  val zone: SceneryPlacementZone,
  val basePoolPairs: Int,
  val cycleLengthMeters: Float,
  val zSpacingMeters: Float,
  val minAbsLateralXMeters: Float,
  val maxAbsLateralXMeters: Float,
  val yOffsetMeters: Float = 0f,
  val uniformScale: Float = 1f,
  val targetHeightMeters: Float? = null,
  val yawLeftDegrees: Float = 0f,
  val yawRightDegrees: Float = 180f,
  val usesPineThreeLodHysteresis: Boolean = false,
  val isFarBackdrop: Boolean = false,
)

data class EnvironmentSceneryRecipe(
  val environmentId: String,
  val hdrAssetId: String,
  val groundSkirtTheme: GroundSkirtTheme,
  val groundTextureAssetId: String?,
  val fogColorLinearRgb: FloatArray,
  val fogStartMeters: Float,
  val fogMaxOpacity: Float,
  val isNightIlluminated: Boolean,
  val playerHeadlightIntensityCandela: Float,
  val roadMarkingEmissiveBoost: Float,
  val particleTintLinearRgb: FloatArray,
  val propRules: List<RoadsidePropRule>,
) {
  val requiredAssetIds: Set<String>
    get() =
      buildSet {
        add(hdrAssetId)
        groundTextureAssetId?.let(::add)
        propRules.forEach { add(it.assetId) }
      }
}

object SceneryCatalog {
  const val MINIMUM_SAFE_ROADSIDE_ABS_X_METERS = 9.05f
  const val PLAYABLE_ROAD_HALF_WIDTH_METERS = 5.40f
  const val BARRIER_OUTER_HALF_WIDTH_METERS = 8.75f

  /**
   * Mars-derived desert terrain (`desert-mars-sand-1k-lod-candidate.glb`) has planar sand UVs
   * prepared specifically for a 10.0x uniform placement scale (~250m x ~139.6m, 15.0m sand UV period).
   */
  const val DESERT_MARS_AUTHORED_UNIFORM_SCALE = 10.0f
  const val DESERT_MARS_UV_PERIOD_AT_10X_METERS = 15.0f

  /** Explicitly excluded props & rejected fragments that must NEVER be loaded or referenced. */
  val EXCLUDED_ASSET_PATTERNS: List<String> =
    listOf(
      "Construction Barier-02",
      "Construction_Barier-02",
      "construction-barier-02",
      "valley-fragment",
      "valley-incomplete",
    )

  const val HDR_DAYLIGHT_ID = "hdr-daylight-1k"
  const val HDR_SUNSET_ID = "hdr-sunset-1k"
  const val HDR_NIGHT_ID = "hdr-night-1k"

  const val PINE_LOD_SET_ID = "pine-medium-1-lod-set"
  const val CITY_SKYLINE_ID = "city-skyline-cluster"
  const val SKYSCRAPER_ID = "skyscraper"
  const val DESERT_MARS_SAND_ID = "desert-mars-sand"
  const val MOUNTAIN_PEAK_ID = "mountain-peak"
  const val VALLEY_COMPLETE_ID = "valley-complete-atlas"
  const val ROCKS_MOSS_GLTF_ID = "rock-moss-set-01"
  const val ROCK_FACE_GLTF_ID = "rock-face-02"
  const val STREETLIGHT_ID = "street-light"
  const val GUARDRAIL_ID = "guardrail-and-terminal"
  const val CONCRETE_BARRIER_ID = "concrete-barrier"
  const val ROADWORK_BARRICADE_ID = "roadwork-barricade"
  const val TRAFFIC_CONE_ID = "traffic-cone"
  const val ROAD_SIGNS_ID = "road-signs"
  const val GRASS_GROUND_ID = "leafy-grass-pbr"
  const val SAND_GROUND_ID = "aerial-sand-pbr"

  val ALL_SCENERY_ASSETS: List<SceneryAssetSpec> =
    listOf(
      SceneryAssetSpec(
        id = HDR_DAYLIGHT_ID,
        displayName = "Daylight 1K HDR (Kloofendal 48d Partly Cloudy)",
        kind = SceneryAssetKind.RADIANCE_HDR,
        primaryAssetPath = "hdri/daylight-1k.hdr",
        expectedSha256 = "fd94c84997b8a3c353b62c2125a9b44e19509956986a126e472684432a02d798",
        licenseSpdx = "CC0-1.0",
        authorAttribution = "Greg Zaal / Poly Haven",
      ),
      SceneryAssetSpec(
        id = HDR_SUNSET_ID,
        displayName = "Sunset 1K HDR (The Sky Is On Fire)",
        kind = SceneryAssetKind.RADIANCE_HDR,
        primaryAssetPath = "hdri/sunset-1k.hdr",
        expectedSha256 = "27504c5dd20ffc8490537b152860b80ec9c2bf2832ab28ebd2b6e9f475627411",
        licenseSpdx = "CC0-1.0",
        authorAttribution = "Greg Zaal / Poly Haven",
      ),
      SceneryAssetSpec(
        id = HDR_NIGHT_ID,
        displayName = "Night 1K HDR (Qwantani Night Pure Sky)",
        kind = SceneryAssetKind.RADIANCE_HDR,
        primaryAssetPath = "hdri/night-1k.hdr",
        expectedSha256 = "480d67a67c8d7f8b69fe2334461b2004d7b420a61db1f68fa9043d91ee702b98",
        licenseSpdx = "CC0-1.0",
        authorAttribution = "Greg Zaal & Jarod Guest / Poly Haven",
      ),
      SceneryAssetSpec(
        id = PINE_LOD_SET_ID,
        displayName = "Pine Medium 1 (3-LOD Combined Set)",
        kind = SceneryAssetKind.GLB_SINGLE,
        primaryAssetPath = "scenery/pine-medium-1-lod-set.glb",
        expectedSha256 = "99d0cee29a4cb4cd5fc294c4d854ef02ecf9bcd93899fe0a1e4855da7a14978d",
        licenseSpdx = "CC-BY-4.0",
        authorAttribution = "lucasalic99 (Sketchfab)",
      ),
      SceneryAssetSpec(
        id = CITY_SKYLINE_ID,
        displayName = "City Skyline Cluster 1K",
        kind = SceneryAssetKind.GLB_SINGLE,
        primaryAssetPath = "scenery/city-skyline-cluster-textures-1k.glb",
        expectedSha256 = "4e623dca9a20bfb54d7fa392e21b1ddc9c73e912bc39d9132ba350726a4babba",
        licenseSpdx = "CC-BY-4.0",
        authorAttribution = "pedram ashoori (Sketchfab)",
      ),
      SceneryAssetSpec(
        id = SKYSCRAPER_ID,
        displayName = "Skyscraper Tower",
        kind = SceneryAssetKind.GLB_SINGLE,
        primaryAssetPath = "scenery/skyscraper/skyscraper.glb",
        expectedSha256 = "b4a3b40029908f8289223df5c435f510262feda4b65f068b484784bdd9682bbc",
        licenseSpdx = "CC-BY-4.0",
        authorAttribution = "Screaming Color (Sketchfab)",
      ),
      SceneryAssetSpec(
        id = DESERT_MARS_SAND_ID,
        displayName = "Mars-Derived Prepared Sand Surface Candidate (Not Earth Dune Scan)",
        kind = SceneryAssetKind.GLB_SINGLE,
        primaryAssetPath = "scenery/desert-mars-sand-1k-lod-candidate.glb",
        expectedSha256 = "abb8c7def4328f7385a98a3fca4dd83ed940ee4e616ab72ecbeb69318f609350",
        licenseSpdx = "CC0-1.0",
        authorAttribution = "NASA / JPL-Caltech / University of Arizona (HiRISE) + Poly Haven Aerial Sand",
        isCandidate = true,
        candidateRuntimeNote =
          "Prepared Mars Jezero Crater relief + Aerial Sand PBR; candidate requiring rendered comparison & phone pacing check (not an Earth dune scan).",
        scaleDependentUvNote =
          "Planar sand UVs authored for 10x uniform placement scale (15 m sand texture period).",
      ),
      SceneryAssetSpec(
        id = MOUNTAIN_PEAK_ID,
        displayName = "Mountain Peak Far-LOD Candidate",
        kind = SceneryAssetKind.GLB_SINGLE,
        primaryAssetPath = "scenery/mountain-peak-far-lod-candidate.glb",
        expectedSha256 = "95a2b4aa889b2f8cbd5ec6a4d11f4672c88cad4cf95f577f571024f2727d8ca0",
        licenseSpdx = "CC-BY-4.0",
        authorAttribution = "any3d_com (Sketchfab)",
        isCandidate = true,
        candidateRuntimeNote = "315,380-tri far-LOD candidate; requires rendered comparison & real-phone frame-time verification.",
      ),
      SceneryAssetSpec(
        id = VALLEY_COMPLETE_ID,
        displayName = "Complete Repaired Valley Atlas Far-LOD Candidate",
        kind = SceneryAssetKind.GLB_SINGLE,
        primaryAssetPath = "scenery/valley-complete-atlas-far-lod-candidate.glb",
        expectedSha256 = "1c772ca2de798f3e00f779c7caeddb3041bdbfc741977d9558833c58f4d55a38",
        licenseSpdx = "CC-BY-4.0",
        authorAttribution = "any3d_com (Sketchfab)",
        isCandidate = true,
        exceedsCloudUploadLimit = true,
        candidateRuntimeNote =
          "47.16 MB complete repaired valley scan (>30 MB AI Studio upload cap; auto-loads when copied into Android Studio assets/scenery/ with cut edges concealed behind cliff/pine banks).",
      ),
      SceneryAssetSpec(
        id = ROCKS_MOSS_GLTF_ID,
        displayName = "Mossy Rock Set 1K (External glTF + BIN + Textures)",
        kind = SceneryAssetKind.GLTF_EXTERNAL,
        primaryAssetPath = "scenery/rocks/rock_moss_set_01_1k.gltf",
        expectedSha256 = "c36675aca9c6b2be7785c2534cf413b03ec82a5a2a5407a2b1c0cdb0346c9727",
        externalDependenciesWithSha256 =
          mapOf(
            "rock_moss_set_01.bin" to "de8360e6175879aaf02c171911069cf5f1852547f9ca7f10e440085dbcc3315e",
            "textures/rock_moss_set_01_diff_1k.jpg" to "40cea65d8aa4ee73a93b04af19963834d061eee9779c3fc2c1cba76eef812ccc",
            "textures/rock_moss_set_01_nor_gl_1k.jpg" to "eb4aad3f207f1dc5b624d74ae240372171626a66912bd71adf7bd3e518181d6e",
            "textures/rock_moss_set_01_rough_1k.jpg" to "4d6ec46623abd8e2cdb855f59c7ce31873ad61b36b60e4de579e9e776a497c6b",
          ),
        licenseSpdx = "CC0-1.0",
        authorAttribution = "Poly Haven",
      ),
      SceneryAssetSpec(
        id = ROCK_FACE_GLTF_ID,
        displayName = "Cliff Rock Face 2K (External glTF + BIN + Textures)",
        kind = SceneryAssetKind.GLTF_EXTERNAL,
        primaryAssetPath = "scenery/rock-face/rock_face_02_2k.gltf",
        expectedSha256 = "532c5662536c59dde08e1ab3e0985addcf555d7c6bd9c4ee6957c6e5a7c200c6",
        externalDependenciesWithSha256 =
          mapOf(
            "rock_face_02.bin" to "cfa3dea8f0f91525b5dfe93d889acacf961d20f7174aae100eae2c6e101edf31",
            "textures/rock_face_02_diff_2k.jpg" to "928fe2b96672ed5104abbeb5eaa7611ac65218d6b4e392f523549426284e2379",
            "textures/rock_face_02_nor_gl_2k.jpg" to "bb1a4cd13b02346f90efc95e71f9e019a7bc4169bd5f5d2b38825694b3c81253",
            "textures/rock_face_02_arm_2k.jpg" to "06e106bf0d1c885cb245b5a85fb283d00e5871116c5bc8cc39bea150799dd4b8",
          ),
        licenseSpdx = "CC0-1.0",
        authorAttribution = "Poly Haven",
      ),
      SceneryAssetSpec(
        id = STREETLIGHT_ID,
        displayName = "Highway Streetlight",
        kind = SceneryAssetKind.GLB_SINGLE,
        primaryAssetPath = "scenery/streetlight/street-light.glb",
        expectedSha256 = "f415b52044e19f925ef9d6fcf950bfec9fe92ce1decba26b96daad0e72a1de10",
        licenseSpdx = "CC0-1.0",
        authorAttribution = "Francesco Coldesina (Sketchfab)",
      ),
      SceneryAssetSpec(
        id = GUARDRAIL_ID,
        displayName = "Guardrail & Terminal",
        kind = SceneryAssetKind.GLB_SINGLE,
        primaryAssetPath = "scenery/guardrail/guardrail-and-terminal.glb",
        expectedSha256 = "73f136c7b826f35f5ca0bcbb6176517a25ef1f56ed9a20aa028641993ebafb8a",
        licenseSpdx = "CC-BY-4.0",
        authorAttribution = "Desertsage (Sketchfab)",
      ),
      SceneryAssetSpec(
        id = CONCRETE_BARRIER_ID,
        displayName = "Concrete Jersey Barrier",
        kind = SceneryAssetKind.GLB_SINGLE,
        primaryAssetPath = "scenery/concrete-barrier/concrete-barrier.glb",
        expectedSha256 = "495e001730800699bb9af51416bc5c4470ecb86eeb76bd088255b273c752fc1e",
        licenseSpdx = "CC0-1.0",
        authorAttribution = "Blender3D (Sketchfab)",
      ),
      SceneryAssetSpec(
        id = ROADWORK_BARRICADE_ID,
        displayName = "Striped Roadwork Barricade 1K",
        kind = SceneryAssetKind.GLB_SINGLE,
        primaryAssetPath = "scenery/roadwork-barricade-textures-1k.glb",
        expectedSha256 = "d0993718dfe8a9090457fe03dcf5b864d3804e714b483ba662b2d0eb08d6dd27",
        licenseSpdx = "CC-BY-4.0",
        authorAttribution = "akil.khamidullin (Sketchfab, isolated Object_19; Construction Barier-02 excluded)",
      ),
      SceneryAssetSpec(
        id = TRAFFIC_CONE_ID,
        displayName = "Traffic Cone 1K",
        kind = SceneryAssetKind.GLB_SINGLE,
        primaryAssetPath = "scenery/traffic-cone-textures-1k.glb",
        expectedSha256 = "3471e63324df6de48382b52c6773135e5921d6ce97fc4ee2daedbff2a9ef0c2f",
        licenseSpdx = "CC-BY-4.0",
        authorAttribution = "akil.khamidullin (Sketchfab)",
      ),
      SceneryAssetSpec(
        id = ROAD_SIGNS_ID,
        displayName = "Highway Road Signs",
        kind = SceneryAssetKind.GLB_SINGLE,
        primaryAssetPath = "scenery/road-signs/road-signs.glb",
        expectedSha256 = "7b18be91dc801431a61bb7bb0ac25bc457d831379a180803c50319378ca6e033",
        licenseSpdx = "CC-BY-4.0",
        authorAttribution = "CreativeTrio (Sketchfab)",
      ),
      SceneryAssetSpec(
        id = GRASS_GROUND_ID,
        displayName = "Leafy Grass Ground PBR Set",
        kind = SceneryAssetKind.PBR_GROUND_TEXTURE_SET,
        primaryAssetPath = "scenery/grass/color.jpg",
        expectedSha256 = "8e1c6d21365d4b89bc5a35ab664da98a78dbc3ab9ba6100474881c8619a5b113",
        externalDependenciesWithSha256 =
          mapOf(
            "normal-gl.jpg" to "df0cf0ce96e653f033e5b934d5b12995464bda027aa53bd12e329be889aa9f45",
            "roughness.jpg" to "34e1733bf4064b6950a575ff57e78c8b7ff71b63749eb48a49221bd65de6fb4f",
            "ao.jpg" to "bcce4368f6a1affe11b4a643e14cfed7dfb46abf6bb9e6a28a3b87c2de2ddb11",
          ),
        licenseSpdx = "CC0-1.0",
        authorAttribution = "Poly Haven",
      ),
      SceneryAssetSpec(
        id = SAND_GROUND_ID,
        displayName = "Aerial Sand Ground PBR Set",
        kind = SceneryAssetKind.PBR_GROUND_TEXTURE_SET,
        primaryAssetPath = "scenery/sand/color.jpg",
        expectedSha256 = "98eddd01834e996cda3fa20dbc769ae38e87277ad0c62650604572fb2e646cd1",
        externalDependenciesWithSha256 =
          mapOf(
            "normal-gl.jpg" to "d4ce3a47379acd398c657adf89611df3843872a76dd3bd9e76f62ee1fb0363ef",
            "roughness.jpg" to "d6b80e523363c7960f3dc59d02e8ca902e4b9ea642353cc01c3b39eb1a5063c5",
            "ao.jpg" to "3a042eac5d03461c56c673f9afe7a4980386259d6439e3da87418ba687db176b",
          ),
        licenseSpdx = "CC0-1.0",
        authorAttribution = "Poly Haven",
      ),
    )

  private val ASSETS_BY_ID: Map<String, SceneryAssetSpec> =
    ALL_SCENERY_ASSETS.associateBy { it.id }

  fun findAsset(id: String): SceneryAssetSpec? = ASSETS_BY_ID[id]

  val ENVIRONMENT_RECIPES: Map<String, EnvironmentSceneryRecipe> =
    mapOf(
      // 1. Beginner Highway — Countryside Daylight, Green Grass Skirt, 3-LOD Pines, Moss Rocks, Guardrails
      ProgressionCatalog.BEGINNER_HIGHWAY_ID to
        EnvironmentSceneryRecipe(
          environmentId = ProgressionCatalog.BEGINNER_HIGHWAY_ID,
          hdrAssetId = HDR_DAYLIGHT_ID,
          groundSkirtTheme = GroundSkirtTheme.LEAFY_GRASS_PBR,
          groundTextureAssetId = GRASS_GROUND_ID,
          fogColorLinearRgb = floatArrayOf(0.56f, 0.72f, 0.90f),
          fogStartMeters = 130f,
          fogMaxOpacity = 0.34f,
          isNightIlluminated = false,
          playerHeadlightIntensityCandela = 0f,
          roadMarkingEmissiveBoost = 0f,
          particleTintLinearRgb = floatArrayOf(0.88f, 0.94f, 1.0f),
          propRules =
            listOf(
              RoadsidePropRule(
                assetId = GUARDRAIL_ID,
                zone = SceneryPlacementZone.SHOULDER_NEAR,
                basePoolPairs = 8,
                cycleLengthMeters = 192f,
                zSpacingMeters = 24f,
                minAbsLateralXMeters = 9.15f,
                maxAbsLateralXMeters = 9.15f,
                targetHeightMeters = 1.05f,
                yawLeftDegrees = 90f,
                yawRightDegrees = -90f,
              ),
              RoadsidePropRule(
                assetId = ROAD_SIGNS_ID,
                zone = SceneryPlacementZone.SHOULDER_NEAR,
                basePoolPairs = 3,
                cycleLengthMeters = 210f,
                zSpacingMeters = 70f,
                minAbsLateralXMeters = 10.2f,
                maxAbsLateralXMeters = 10.5f,
                targetHeightMeters = 3.2f,
                yawLeftDegrees = 180f,
                yawRightDegrees = 180f,
              ),
              RoadsidePropRule(
                assetId = PINE_LOD_SET_ID,
                zone = SceneryPlacementZone.ROADSIDE_MID,
                basePoolPairs = 10,
                cycleLengthMeters = 220f,
                zSpacingMeters = 22f,
                minAbsLateralXMeters = 13.5f,
                maxAbsLateralXMeters = 26.0f,
                uniformScale = 1.0f,
                yawLeftDegrees = 15f,
                yawRightDegrees = 195f,
                usesPineThreeLodHysteresis = true,
              ),
              RoadsidePropRule(
                assetId = ROCKS_MOSS_GLTF_ID,
                zone = SceneryPlacementZone.ROADSIDE_MID,
                basePoolPairs = 5,
                cycleLengthMeters = 200f,
                zSpacingMeters = 40f,
                minAbsLateralXMeters = 12.2f,
                maxAbsLateralXMeters = 22.0f,
                targetHeightMeters = 1.85f,
                yawLeftDegrees = 35f,
                yawRightDegrees = 215f,
              ),
              RoadsidePropRule(
                assetId = MOUNTAIN_PEAK_ID,
                zone = SceneryPlacementZone.FAR_VISTA_BACKDROP,
                basePoolPairs = 1,
                cycleLengthMeters = 520f,
                zSpacingMeters = 520f,
                minAbsLateralXMeters = 165f,
                maxAbsLateralXMeters = 195f,
                yOffsetMeters = -6.0f,
                uniformScale = 0.65f,
                yawLeftDegrees = 25f,
                yawRightDegrees = -155f,
                isFarBackdrop = true,
              ),
            ),
        ),

      // 2. City Highway — Urban Skyline, Skyscrapers, Expressway Barriers, Streetlights & Signs
      ProgressionCatalog.CITY_HIGHWAY_ID to
        EnvironmentSceneryRecipe(
          environmentId = ProgressionCatalog.CITY_HIGHWAY_ID,
          hdrAssetId = HDR_DAYLIGHT_ID,
          groundSkirtTheme = GroundSkirtTheme.URBAN_CONCRETE_APRON,
          groundTextureAssetId = null,
          fogColorLinearRgb = floatArrayOf(0.58f, 0.66f, 0.78f),
          fogStartMeters = 115f,
          fogMaxOpacity = 0.42f,
          isNightIlluminated = false,
          playerHeadlightIntensityCandela = 0f,
          roadMarkingEmissiveBoost = 0.05f,
          particleTintLinearRgb = floatArrayOf(0.84f, 0.92f, 1.0f),
          propRules =
            listOf(
              RoadsidePropRule(
                assetId = CONCRETE_BARRIER_ID,
                zone = SceneryPlacementZone.SHOULDER_NEAR,
                basePoolPairs = 8,
                cycleLengthMeters = 160f,
                zSpacingMeters = 20f,
                minAbsLateralXMeters = 9.10f,
                maxAbsLateralXMeters = 9.10f,
                targetHeightMeters = 1.0f,
                yawLeftDegrees = 90f,
                yawRightDegrees = -90f,
              ),
              RoadsidePropRule(
                assetId = STREETLIGHT_ID,
                zone = SceneryPlacementZone.SHOULDER_NEAR,
                basePoolPairs = 7,
                cycleLengthMeters = 210f,
                zSpacingMeters = 30f,
                minAbsLateralXMeters = 9.6f,
                maxAbsLateralXMeters = 9.6f,
                targetHeightMeters = 9.8f,
                yawLeftDegrees = 90f,
                yawRightDegrees = -90f,
              ),
              RoadsidePropRule(
                assetId = ROAD_SIGNS_ID,
                zone = SceneryPlacementZone.SHOULDER_NEAR,
                basePoolPairs = 3,
                cycleLengthMeters = 195f,
                zSpacingMeters = 65f,
                minAbsLateralXMeters = 10.4f,
                maxAbsLateralXMeters = 10.8f,
                targetHeightMeters = 3.3f,
                yawLeftDegrees = 180f,
                yawRightDegrees = 180f,
              ),
              RoadsidePropRule(
                assetId = TRAFFIC_CONE_ID,
                zone = SceneryPlacementZone.SHOULDER_NEAR,
                basePoolPairs = 4,
                cycleLengthMeters = 160f,
                zSpacingMeters = 40f,
                minAbsLateralXMeters = 9.35f,
                maxAbsLateralXMeters = 9.55f,
                targetHeightMeters = 0.78f,
              ),
              RoadsidePropRule(
                assetId = CITY_SKYLINE_ID,
                zone = SceneryPlacementZone.SKYLINE_FLANK,
                basePoolPairs = 5,
                cycleLengthMeters = 275f,
                zSpacingMeters = 55f,
                minAbsLateralXMeters = 46.0f,
                maxAbsLateralXMeters = 58.0f,
                uniformScale = 16.5f,
                yawLeftDegrees = 0f,
                yawRightDegrees = 180f,
              ),
              RoadsidePropRule(
                assetId = SKYSCRAPER_ID,
                zone = SceneryPlacementZone.SKYLINE_FLANK,
                basePoolPairs = 3,
                cycleLengthMeters = 300f,
                zSpacingMeters = 100f,
                minAbsLateralXMeters = 68.0f,
                maxAbsLateralXMeters = 84.0f,
                uniformScale = 0.85f,
                yawLeftDegrees = 0f,
                yawRightDegrees = 180f,
              ),
            ),
        ),

      // 3. Desert Highway — Golden-Hour Sunset HDR, 10x Mars-Derived Sand Surface, Cliff Outcrops
      ProgressionCatalog.DESERT_HIGHWAY_ID to
        EnvironmentSceneryRecipe(
          environmentId = ProgressionCatalog.DESERT_HIGHWAY_ID,
          hdrAssetId = HDR_SUNSET_ID,
          groundSkirtTheme = GroundSkirtTheme.AERIAL_SAND_PBR,
          groundTextureAssetId = SAND_GROUND_ID,
          fogColorLinearRgb = floatArrayOf(0.86f, 0.52f, 0.28f),
          fogStartMeters = 110f,
          fogMaxOpacity = 0.46f,
          isNightIlluminated = false,
          playerHeadlightIntensityCandela = 4_500f,
          roadMarkingEmissiveBoost = 0.06f,
          particleTintLinearRgb = floatArrayOf(0.96f, 0.76f, 0.48f),
          propRules =
            listOf(
              RoadsidePropRule(
                assetId = DESERT_MARS_SAND_ID,
                zone = SceneryPlacementZone.DESERT_SAND_TERRAIN_10X,
                basePoolPairs = 2,
                cycleLengthMeters = 280f,
                zSpacingMeters = 140f,
                minAbsLateralXMeters = 138.0f,
                maxAbsLateralXMeters = 138.0f,
                yOffsetMeters = -3.4f,
                uniformScale = DESERT_MARS_AUTHORED_UNIFORM_SCALE, // Exact 10x scale preserves 15 m sand UV period
                yawLeftDegrees = 0f,
                yawRightDegrees = 180f,
              ),
              RoadsidePropRule(
                assetId = ROCK_FACE_GLTF_ID,
                zone = SceneryPlacementZone.ROADSIDE_MID,
                basePoolPairs = 4,
                cycleLengthMeters = 240f,
                zSpacingMeters = 60f,
                minAbsLateralXMeters = 24.0f,
                maxAbsLateralXMeters = 36.0f,
                targetHeightMeters = 11.5f,
                yawLeftDegrees = 75f,
                yawRightDegrees = -105f,
              ),
              RoadsidePropRule(
                assetId = GUARDRAIL_ID,
                zone = SceneryPlacementZone.SHOULDER_NEAR,
                basePoolPairs = 5,
                cycleLengthMeters = 200f,
                zSpacingMeters = 40f,
                minAbsLateralXMeters = 9.15f,
                maxAbsLateralXMeters = 9.15f,
                targetHeightMeters = 1.05f,
                yawLeftDegrees = 90f,
                yawRightDegrees = -90f,
              ),
              RoadsidePropRule(
                assetId = ROAD_SIGNS_ID,
                zone = SceneryPlacementZone.SHOULDER_NEAR,
                basePoolPairs = 3,
                cycleLengthMeters = 210f,
                zSpacingMeters = 70f,
                minAbsLateralXMeters = 10.3f,
                maxAbsLateralXMeters = 10.7f,
                targetHeightMeters = 3.2f,
                yawLeftDegrees = 180f,
                yawRightDegrees = 180f,
              ),
            ),
        ),

      // 4. Mountain Highway — Towering Peaks, Repaired Complete Valley Backdrop (when local >30MB GLB present), Cliff Walls & 3-LOD Pines
      ProgressionCatalog.MOUNTAIN_HIGHWAY_ID to
        EnvironmentSceneryRecipe(
          environmentId = ProgressionCatalog.MOUNTAIN_HIGHWAY_ID,
          hdrAssetId = HDR_DAYLIGHT_ID,
          groundSkirtTheme = GroundSkirtTheme.LEAFY_GRASS_PBR,
          groundTextureAssetId = GRASS_GROUND_ID,
          fogColorLinearRgb = floatArrayOf(0.50f, 0.68f, 0.92f),
          fogStartMeters = 105f,
          fogMaxOpacity = 0.48f,
          isNightIlluminated = false,
          playerHeadlightIntensityCandela = 0f,
          roadMarkingEmissiveBoost = 0.04f,
          particleTintLinearRgb = floatArrayOf(0.86f, 0.94f, 1.0f),
          propRules =
            listOf(
              RoadsidePropRule(
                assetId = GUARDRAIL_ID,
                zone = SceneryPlacementZone.SHOULDER_NEAR,
                basePoolPairs = 9,
                cycleLengthMeters = 180f,
                zSpacingMeters = 20f,
                minAbsLateralXMeters = 9.12f,
                maxAbsLateralXMeters = 9.12f,
                targetHeightMeters = 1.08f,
                yawLeftDegrees = 90f,
                yawRightDegrees = -90f,
              ),
              // Steep cliff faces & pine banks placed along mid-flank specifically conceal any scan cut-edges/skirts of background valley terrain
              RoadsidePropRule(
                assetId = ROCK_FACE_GLTF_ID,
                zone = SceneryPlacementZone.ROADSIDE_MID,
                basePoolPairs = 6,
                cycleLengthMeters = 210f,
                zSpacingMeters = 35f,
                minAbsLateralXMeters = 19.5f,
                maxAbsLateralXMeters = 26.5f,
                targetHeightMeters = 16.0f,
                yawLeftDegrees = 85f,
                yawRightDegrees = -95f,
              ),
              RoadsidePropRule(
                assetId = PINE_LOD_SET_ID,
                zone = SceneryPlacementZone.ROADSIDE_MID,
                basePoolPairs = 10,
                cycleLengthMeters = 200f,
                zSpacingMeters = 20f,
                minAbsLateralXMeters = 12.8f,
                maxAbsLateralXMeters = 21.0f,
                uniformScale = 1.08f,
                yawLeftDegrees = 30f,
                yawRightDegrees = 210f,
                usesPineThreeLodHysteresis = true,
              ),
              RoadsidePropRule(
                assetId = ROCKS_MOSS_GLTF_ID,
                zone = SceneryPlacementZone.ROADSIDE_MID,
                basePoolPairs = 5,
                cycleLengthMeters = 190f,
                zSpacingMeters = 38f,
                minAbsLateralXMeters = 11.4f,
                maxAbsLateralXMeters = 16.5f,
                targetHeightMeters = 2.1f,
                yawLeftDegrees = 50f,
                yawRightDegrees = 230f,
              ),
              RoadsidePropRule(
                assetId = MOUNTAIN_PEAK_ID,
                zone = SceneryPlacementZone.FAR_VISTA_BACKDROP,
                basePoolPairs = 2,
                cycleLengthMeters = 440f,
                zSpacingMeters = 220f,
                minAbsLateralXMeters = 140f,
                maxAbsLateralXMeters = 175f,
                yOffsetMeters = -5.0f,
                uniformScale = 0.82f,
                yawLeftDegrees = 20f,
                yawRightDegrees = -160f,
                isFarBackdrop = true,
              ),
              RoadsidePropRule(
                assetId = VALLEY_COMPLETE_ID,
                zone = SceneryPlacementZone.FAR_VISTA_BACKDROP,
                basePoolPairs = 1,
                cycleLengthMeters = 620f,
                zSpacingMeters = 620f,
                minAbsLateralXMeters = 210f,
                maxAbsLateralXMeters = 210f,
                yOffsetMeters = -22.0f, // Recessed below foreground cliff/pine curtain to conceal scan cut edges & holes
                uniformScale = 0.24f,
                yawLeftDegrees = 0f,
                yawRightDegrees = 180f,
                isFarBackdrop = true,
              ),
            ),
        ),

      // 5. Night Highway — Qwantani Night HDR, Illuminated Streetlights, Player Headlights, High-Contrast Legible Road & Skyline
      ProgressionCatalog.NIGHT_HIGHWAY_ID to
        EnvironmentSceneryRecipe(
          environmentId = ProgressionCatalog.NIGHT_HIGHWAY_ID,
          hdrAssetId = HDR_NIGHT_ID,
          groundSkirtTheme = GroundSkirtTheme.NIGHT_REFLECTIVE_APRON,
          groundTextureAssetId = null,
          fogColorLinearRgb = floatArrayOf(0.03f, 0.06f, 0.14f),
          fogStartMeters = 135f,
          fogMaxOpacity = 0.36f,
          isNightIlluminated = true,
          playerHeadlightIntensityCandela = 85_000f,
          roadMarkingEmissiveBoost = 0.62f,
          particleTintLinearRgb = floatArrayOf(0.45f, 0.78f, 1.0f),
          propRules =
            listOf(
              RoadsidePropRule(
                assetId = STREETLIGHT_ID,
                zone = SceneryPlacementZone.SHOULDER_NEAR,
                basePoolPairs = 9,
                cycleLengthMeters = 198f,
                zSpacingMeters = 22f,
                minAbsLateralXMeters = 9.45f,
                maxAbsLateralXMeters = 9.45f,
                targetHeightMeters = 9.6f,
                yawLeftDegrees = 90f,
                yawRightDegrees = -90f,
              ),
              RoadsidePropRule(
                assetId = CONCRETE_BARRIER_ID,
                zone = SceneryPlacementZone.SHOULDER_NEAR,
                basePoolPairs = 8,
                cycleLengthMeters = 160f,
                zSpacingMeters = 20f,
                minAbsLateralXMeters = 9.08f,
                maxAbsLateralXMeters = 9.08f,
                targetHeightMeters = 1.0f,
                yawLeftDegrees = 90f,
                yawRightDegrees = -90f,
              ),
              RoadsidePropRule(
                assetId = GUARDRAIL_ID,
                zone = SceneryPlacementZone.SHOULDER_NEAR,
                basePoolPairs = 5,
                cycleLengthMeters = 200f,
                zSpacingMeters = 40f,
                minAbsLateralXMeters = 9.85f,
                maxAbsLateralXMeters = 9.85f,
                targetHeightMeters = 1.05f,
                yawLeftDegrees = 90f,
                yawRightDegrees = -90f,
              ),
              RoadsidePropRule(
                assetId = ROAD_SIGNS_ID,
                zone = SceneryPlacementZone.SHOULDER_NEAR,
                basePoolPairs = 3,
                cycleLengthMeters = 195f,
                zSpacingMeters = 65f,
                minAbsLateralXMeters = 10.4f,
                maxAbsLateralXMeters = 10.7f,
                targetHeightMeters = 3.2f,
                yawLeftDegrees = 180f,
                yawRightDegrees = 180f,
              ),
              RoadsidePropRule(
                assetId = SKYSCRAPER_ID,
                zone = SceneryPlacementZone.SKYLINE_FLANK,
                basePoolPairs = 4,
                cycleLengthMeters = 280f,
                zSpacingMeters = 70f,
                minAbsLateralXMeters = 62.0f,
                maxAbsLateralXMeters = 78.0f,
                uniformScale = 0.82f,
                yawLeftDegrees = 0f,
                yawRightDegrees = 180f,
              ),
              RoadsidePropRule(
                assetId = CITY_SKYLINE_ID,
                zone = SceneryPlacementZone.SKYLINE_FLANK,
                basePoolPairs = 4,
                cycleLengthMeters = 260f,
                zSpacingMeters = 65f,
                minAbsLateralXMeters = 48.0f,
                maxAbsLateralXMeters = 58.0f,
                uniformScale = 15.5f,
                yawLeftDegrees = 0f,
                yawRightDegrees = 180f,
              ),
            ),
        ),
    )

  fun recipeFor(environmentId: String): EnvironmentSceneryRecipe =
    ENVIRONMENT_RECIPES[environmentId]
      ?: ENVIRONMENT_RECIPES.getValue(ProgressionCatalog.BEGINNER_HIGHWAY_ID)

  fun isExcludedAssetPath(pathOrName: String): Boolean =
    EXCLUDED_ASSET_PATTERNS.any { pattern ->
      pathOrName.contains(pattern, ignoreCase = true)
    }
}
