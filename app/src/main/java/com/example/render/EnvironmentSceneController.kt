package com.example.render

import android.graphics.Bitmap
import android.opengl.Matrix
import com.example.assets.ActiveEnvironmentSceneryBundle
import com.example.assets.LoadedGroundPbrMaps
import com.example.progression.EnvironmentLevelSpec
import com.example.scenery.GraphicsQualityPreset
import com.example.scenery.PineInstanceLodTracker
import com.example.scenery.PineLodSelector
import com.example.scenery.RoadsidePropRule
import com.example.scenery.SceneryCatalog
import com.example.scenery.SceneryPlacementZone
import com.example.sim.DrivingSimState
import com.google.android.filament.Box
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.IndirectLight
import com.google.android.filament.LightManager
import com.google.android.filament.Scene
import com.google.android.filament.Texture
import com.google.android.filament.TextureSampler
import com.google.android.filament.TransformManager
import com.google.android.filament.View
import com.google.android.filament.android.TextureHelper
import com.google.android.filament.gltfio.AssetLoader
import com.google.android.filament.gltfio.FilamentAsset
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Manages the single active Phase 7 3D environment inside Filament:
 * - Lazy single-environment lifecycle: disposes all active sky dome, ground skirt, roadside/skyline/terrain
 *   props, dynamic lamps, particles, and environment textures before loading the next environment.
 * - Genuine Radiance `.hdr` integration: builds 3-band SH `IndirectLight` + `SAMPLER_2D` tone-mapped
 *   3D sky dome (`daylight-1k.hdr`, `sunset-1k.hdr`, `night-1k.hdr`).
 * - Pine 3-LOD hysteresis & mutual exclusion (`Pine_medium_1_LOD0/1/2` — strictly 1 visible at a time).
 * - Night Highway legibility: dual forward player headlights + scrolling overhead streetlamp point lights
 *   + high-contrast road lane markings (preserved even on `LOW` quality).
 * - Grounded vehicle contact shadows + restrained pooled speed/dust/nitro particle streaks.
 */
class EnvironmentSceneController {
  private data class PooledSceneryInstance(
    val rule: RoadsidePropRule,
    val asset: FilamentAsset,
    val baseMatrix: FloatArray,
    val sideSign: Float, // +1f = screen-left (+X_renderer), -1f = screen-right (-X_renderer)
    val lateralAbsXMeters: Float,
    val zPhaseMeters: Float,
    val pineLodTracker: PineInstanceLodTracker?,
    val pineLodEntities: Array<IntArray>?, // [0]=LOD0 renderables, [1]=LOD1, [2]=LOD2
    var visibleInCulledRange: Boolean = true,
    var lastAppliedPineLod: Int = -1,
  )

  private var skyDomeAsset: FilamentAsset? = null
  private var groundSkirtAsset: FilamentAsset? = null
  private val sceneryInstances = mutableListOf<PooledSceneryInstance>()
  private val environmentTextures = mutableListOf<Texture>()

  // Contact shadow pool (1 hero + 8 traffic cars)
  private var heroShadowAsset: FilamentAsset? = null
  private var heroShadowVisible = false
  private val trafficShadowAssets = mutableListOf<FilamentAsset>()
  private val trafficShadowVisible = BooleanArray(MAX_TRAFFIC_CONTACT_SHADOWS)

  // Restrained speed / dust / nitro particle pool
  private val particleAssets = mutableListOf<FilamentAsset>()
  private val particleVisible = BooleanArray(PARTICLE_STREAK_POOL_COUNT)

  // Dynamic Night / Golden-hour lights (2 player forward headlights + up to 6 overhead streetlamps)
  private var leftHeadlightEntity: Int = 0
  private var rightHeadlightEntity: Int = 0
  private val streetLampEntities = mutableListOf<Int>()

  private var activeQualityPreset: GraphicsQualityPreset = GraphicsQualityPreset.AUTO
  private var activeNightIlluminated: Boolean = false
  private var activeHeadlightCandela: Float = 0f

  var activeEnvironmentId: String? = null
    private set

  var activeEnvironmentCount: Int = 0
    private set

  var environmentApplyCount: Int = 0
    private set

  var environmentDisposeCount: Int = 0
    private set

  private val tmpMatA = FloatArray(16)
  private val tmpMatB = FloatArray(16)

  fun applyEnvironment(
    eng: Engine,
    scn: Scene,
    vw: View,
    loader: AssetLoader,
    roadAsset: FilamentAsset?,
    sunEntity: Int,
    fillLightEntity: Int,
    environmentSpec: EnvironmentLevelSpec,
    bundle: ActiveEnvironmentSceneryBundle?,
    createGlbAssetFn: (ByteArray) -> FilamentAsset?,
  ): IndirectLight? {
    disposeActiveEnvironment(eng, scn, loader)

    val recipe = bundle?.recipe ?: SceneryCatalog.recipeFor(environmentSpec.id)
    val quality = bundle?.qualityPreset ?: GraphicsQualityPreset.AUTO
    activeQualityPreset = quality
    activeNightIlluminated = recipe.isNightIlluminated
    activeHeadlightCandela = recipe.playerHeadlightIntensityCandela

    // 1. Configure directional sun shadow toggle & HDR chromaticity / intensity
    val lm = eng.lightManager
    if (sunEntity != 0) {
      val sunInst = lm.getInstance(sunEntity)
      if (sunInst != 0) {
        val hdrSunRgb = bundle?.decodedHdr?.dominantSunChromaticityRgb
        val baseRgb = environmentSpec.keySunColorRgb
        val r = if (hdrSunRgb != null) (baseRgb[0] * 0.65f + hdrSunRgb[0] * 0.35f) else baseRgb[0]
        val g = if (hdrSunRgb != null) (baseRgb[1] * 0.65f + hdrSunRgb[1] * 0.35f) else baseRgb[1]
        val b = if (hdrSunRgb != null) (baseRgb[2] * 0.65f + hdrSunRgb[2] * 0.35f) else baseRgb[2]
        lm.setColor(sunInst, r, g, b)
        lm.setIntensity(sunInst, environmentSpec.keySunIntensityLux)
        lm.setShadowCaster(sunInst, quality.enableDirectionalShadows)
      }
    }
    if (fillLightEntity != 0) {
      val fillInst = lm.getInstance(fillLightEntity)
      if (fillInst != 0) {
        val fc = environmentSpec.fillColorRgb
        lm.setColor(fillInst, fc[0], fc[1], fc[2])
        lm.setIntensity(fillInst, environmentSpec.fillIntensityLux)
      }
    }

    // 2. Atmospheric fog per environment recipe
    vw.fogOptions =
      View.FogOptions().apply {
        enabled = true
        color = recipe.fogColorLinearRgb.copyOf()
        distance = recipe.fogStartMeters
        maximumOpacity = recipe.fogMaxOpacity
        density = 0.0042f
      }

    // 3. Genuine decoded Radiance .hdr -> 3-band SH IndirectLight + SAMPLER_2D Sky Dome
    var newIndirectLight: IndirectLight? = null
    val decodedHdr = bundle?.decodedHdr
    if (decodedHdr != null) {
      newIndirectLight =
        IndirectLight.Builder()
          .irradiance(3, decodedHdr.shIrradiance3Band)
          .intensity(environmentSpec.iblIntensityLux)
          .build(eng)

      val skyBytes = ProceduralSceneryGlbBuilder.buildUnlitSkyDomeGlb(radiusMeters = 360f)
      val dome = createGlbAssetFn(skyBytes)
      if (dome != null) {
        val skyBitmap =
          Bitmap.createBitmap(
            decodedHdr.skySrgbArgbPixels,
            decodedHdr.skyBitmapWidth,
            decodedHdr.skyBitmapHeight,
            Bitmap.Config.ARGB_8888,
          )
        val skyTex = uploadEnvironmentTexture(eng, skyBitmap, isSrgb = true)
        if (!skyBitmap.isRecycled) {
          skyBitmap.recycle()
        }

        val skySampler =
          TextureSampler(
            TextureSampler.MinFilter.LINEAR,
            TextureSampler.MagFilter.LINEAR,
            TextureSampler.WrapMode.REPEAT,
          ).apply {
            wrapModeT = TextureSampler.WrapMode.CLAMP_TO_EDGE
          }
        if (skyTex != null) {
          dome.instance.materialInstances.forEach { mi ->
            if (mi.name == "SkyDomeSurface" && mi.material.hasParameter("baseColorMap")) {
              mi.setParameter("baseColorMap", skyTex, skySampler)
            }
          }
        }
        disableShadowsOnAsset(eng, dome)
        scn.addEntities(dome.entities)
        skyDomeAsset = dome
      }
    }

    // 4. Flanking PBR Ground Skirt (scenery/grass or scenery/sand or urban/night apron)
    val groundMaps = bundle?.groundMaps
    val hasGroundMaps = groundMaps?.hasMaps == true
    val skirtGlb =
      ProceduralSceneryGlbBuilder.buildGroundSkirtGlb(
        theme = recipe.groundSkirtTheme,
        includeTextureSlots = hasGroundMaps,
      )
    val skirt = createGlbAssetFn(skirtGlb)
    if (skirt != null) {
      if (groundMaps != null && groundMaps.hasMaps) {
        applyGroundPbrTextures(eng, skirt, groundMaps)
      }
      setCastShadowsOnly(eng, skirt, cast = false, receive = quality.enableDirectionalShadows)
      scn.addEntities(skirt.entities)
      groundSkirtAsset = skirt
    }

    // 5. Tune road lane markings & curb legibility for Night / Sunset environments
    roadAsset?.let { road ->
      tuneRoadMaterialsForEnvironment(road, recipe.isNightIlluminated, recipe.roadMarkingEmissiveBoost)
    }

    // 6. Instantiate bounded roadside / skyline / terrain prop pools for THIS active environment ONLY
    if (bundle != null) {
      instantiateEnvironmentPropPools(
        eng = eng,
        scn = scn,
        bundle = bundle,
        quality = quality,
        createGlbAssetFn = createGlbAssetFn,
      )
    }

    // 7. Grounded contact shadows beneath hero + traffic cars (keeps vehicles anchored even on LOW)
    initContactShadowPool(eng, scn, createGlbAssetFn)

    // 8. Restrained speed / dust / nitro particle streaks (disabled on LOW quality)
    if (quality.enableParticles) {
      initParticlePool(eng, scn, recipe.particleTintLinearRgb, createGlbAssetFn)
    }

    // 9. Night / Sunset forward player headlights & scrolling overhead streetlamp point lights
    if (recipe.playerHeadlightIntensityCandela > 0f || recipe.isNightIlluminated) {
      initDynamicHighwayLights(
        eng = eng,
        scn = scn,
        headlightCandela = recipe.playerHeadlightIntensityCandela,
        streetLampCount = if (recipe.isNightIlluminated) quality.activeStreetLightPointCount else 0,
      )
    }

    activeEnvironmentId = environmentSpec.id
    activeEnvironmentCount = 1
    environmentApplyCount += 1
    return newIndirectLight
  }

  fun disposeActiveEnvironment(
    eng: Engine,
    scn: Scene,
    loader: AssetLoader,
  ) {
    val hadActive =
      activeEnvironmentId != null ||
        skyDomeAsset != null ||
        groundSkirtAsset != null ||
        sceneryInstances.isNotEmpty()

    sceneryInstances.forEach { inst ->
      scn.removeEntities(inst.asset.entities)
      loader.destroyAsset(inst.asset)
    }
    sceneryInstances.clear()

    skyDomeAsset?.let { dome ->
      scn.removeEntities(dome.entities)
      loader.destroyAsset(dome)
      skyDomeAsset = null
    }
    groundSkirtAsset?.let { skirt ->
      scn.removeEntities(skirt.entities)
      loader.destroyAsset(skirt)
      groundSkirtAsset = null
    }
    heroShadowAsset?.let { sh ->
      scn.removeEntities(sh.entities)
      loader.destroyAsset(sh)
      heroShadowAsset = null
    }
    heroShadowVisible = false
    trafficShadowAssets.forEach { sh ->
      scn.removeEntities(sh.entities)
      loader.destroyAsset(sh)
    }
    trafficShadowAssets.clear()
    trafficShadowVisible.fill(false)

    particleAssets.forEach { p ->
      scn.removeEntities(p.entities)
      loader.destroyAsset(p)
    }
    particleAssets.clear()
    particleVisible.fill(false)

    if (leftHeadlightEntity != 0) {
      scn.removeEntity(leftHeadlightEntity)
      eng.destroyEntity(leftHeadlightEntity)
      EntityManager.get().destroy(leftHeadlightEntity)
      leftHeadlightEntity = 0
    }
    if (rightHeadlightEntity != 0) {
      scn.removeEntity(rightHeadlightEntity)
      eng.destroyEntity(rightHeadlightEntity)
      EntityManager.get().destroy(rightHeadlightEntity)
      rightHeadlightEntity = 0
    }
    streetLampEntities.forEach { lamp ->
      scn.removeEntity(lamp)
      eng.destroyEntity(lamp)
      EntityManager.get().destroy(lamp)
    }
    streetLampEntities.clear()

    environmentTextures.forEach { tex -> eng.destroyTexture(tex) }
    environmentTextures.clear()

    activeEnvironmentId = null
    activeEnvironmentCount = 0
    if (hadActive) {
      environmentDisposeCount += 1
    }
  }

  /**
   * Synchronizes scrolling ground skirt, sky dome centering, roadside prop pools, Pine 3-LOD
   * hysteresis masks, grounded contact shadows, restrained particles, and Night Highway lights.
   * Called inside `TransformManager` transaction in `DripRaceEngineOwner.applyVisualState`.
   */
  fun syncVisualStateInTransaction(
    eng: Engine,
    tm: TransformManager,
    state: DrivingSimState,
    showroomMode: Boolean,
    heroVisible: Boolean,
  ) {
    val rendererCarX = CoordinateMapper.gameToRendererX(state.carX)
    val camEyeZ = if (showroomMode) 6.4f else -8.2f

    // 1. Center sky dome laterally on car/camera so horizon stays infinitely distant
    skyDomeAsset?.let { dome ->
      val domeTi = tm.getInstance(dome.root)
      if (domeTi != 0) {
        Matrix.setIdentityM(tmpMatA, 0)
        Matrix.translateM(tmpMatA, 0, rendererCarX * 0.5f, -6.0f, 0f)
        tm.setTransform(domeTi, tmpMatA)
      }
    }

    // 2. Seamless 30 m Z-scrolling ground skirt synchronized 1:1 with roadAsset
    groundSkirtAsset?.let { skirt ->
      val skirtTi = tm.getInstance(skirt.root)
      if (skirtTi != 0) {
        val scrollOffset =
          -(state.distanceMeters % ProceduralRoadGlbBuilder.SEAMLESS_REPEAT_CYCLE_METERS).toFloat()
        Matrix.setIdentityM(tmpMatA, 0)
        Matrix.translateM(tmpMatA, 0, 0f, 0f, scrollOffset)
        tm.setTransform(skirtTi, tmpMatA)
      }
    }

    // 3. Bounded scrolling roadside, skyline & far-vista scenery instances + Pine 3-LOD mutual exclusion
    val rm = eng.renderableManager
    val maxDrawDist =
      if (showroomMode) max(activeQualityPreset.maxSceneryDrawDistanceMeters, 180f)
      else activeQualityPreset.maxSceneryDrawDistanceMeters
    val rearWrapMeters = 36f

    for (inst in sceneryInstances) {
      val cycle = inst.rule.cycleLengthMeters
      val scrolled = ((inst.zPhaseMeters - (state.distanceMeters % cycle.toDouble()).toFloat()) % cycle + cycle) % cycle
      val relZ = scrolled - rearWrapMeters
      val renderX = inst.sideSign * inst.lateralAbsXMeters
      val camDist = hypot(renderX - rendererCarX * 0.65f, relZ - camEyeZ)

      val inDrawRange =
        inst.rule.isFarBackdrop ||
          inst.rule.zone == SceneryPlacementZone.DESERT_SAND_TERRAIN_10X ||
          camDist <= maxDrawDist

      if (!inDrawRange) {
        if (inst.visibleInCulledRange) {
          setAllRenderablesLayerMask(rm, inst.asset, 0x00)
          inst.visibleInCulledRange = false
          inst.lastAppliedPineLod = -1
        }
        continue
      }

      if (inst.pineLodTracker != null && inst.pineLodEntities != null) {
        val activeLod = inst.pineLodTracker.updateForCameraDistance(camDist)
        if (!inst.visibleInCulledRange || inst.lastAppliedPineLod != activeLod) {
          val mask = PineLodSelector.visibilityMaskForLod(activeLod)
          for (lodIdx in 0..2) {
            val layer = if (mask[lodIdx]) 0x01 else 0x00
            for (ent in inst.pineLodEntities[lodIdx]) {
              val ri = rm.getInstance(ent)
              if (ri != 0) {
                rm.setLayerMask(ri, 0xFF, layer)
              }
            }
          }
          inst.visibleInCulledRange = true
          inst.lastAppliedPineLod = activeLod
        }
      } else if (!inst.visibleInCulledRange) {
        setAllRenderablesLayerMask(rm, inst.asset, 0x01)
        inst.visibleInCulledRange = true
      }

      val rootTi = tm.getInstance(inst.asset.root)
      if (rootTi != 0) {
        Matrix.setIdentityM(tmpMatA, 0)
        Matrix.translateM(tmpMatA, 0, renderX, 0f, relZ)
        Matrix.multiplyMM(tmpMatB, 0, tmpMatA, 0, inst.baseMatrix, 0)
        tm.setTransform(rootTi, tmpMatB)
      }
    }

    // 4. Grounded contact shadows under hero & visible traffic vehicles
    heroShadowAsset?.let { sh ->
      if (heroShadowVisible != heroVisible) {
        setAllRenderablesLayerMask(rm, sh, if (heroVisible) 0x01 else 0x00)
        heroShadowVisible = heroVisible
      }
      if (heroVisible) {
        val ti = tm.getInstance(sh.root)
        if (ti != 0) {
          Matrix.setIdentityM(tmpMatA, 0)
          Matrix.translateM(tmpMatA, 0, rendererCarX, 0f, 0f)
          tm.setTransform(ti, tmpMatA)
        }
      }
    }

    val obstacles = state.activeObstacles
    for (i in trafficShadowAssets.indices) {
      val sh = trafficShadowAssets[i]
      val obs = obstacles.getOrNull(i)
      val showShadow = !showroomMode && obs != null && obs.relativeZ in -18.0..135.0
      if (trafficShadowVisible[i] != showShadow) {
        setAllRenderablesLayerMask(rm, sh, if (showShadow) 0x01 else 0x00)
        trafficShadowVisible[i] = showShadow
      }
      if (obs != null && showShadow) {
        val ti = tm.getInstance(sh.root)
        if (ti != 0) {
          Matrix.setIdentityM(tmpMatA, 0)
          Matrix.translateM(tmpMatA, 0, CoordinateMapper.gameToRendererX(obs.x), 0f, obs.relativeZ.toFloat())
          tm.setTransform(ti, tmpMatA)
        }
      }
    }

    // 5. Restrained speed / dust / nitro particle streaks (strictly visual, zero gameplay effect)
    if (particleAssets.isNotEmpty()) {
      val speedMps = state.speedMetersPerSecond
      val showStreaks = !showroomMode && (speedMps >= 22.0 || state.isNitroActive)
      val streakLengthScale = if (state.isNitroActive) 1.75f else 1.0f
      for (i in particleAssets.indices) {
        val pAsset = particleAssets[i]
        val active = showStreaks && (state.isNitroActive || i % 2 == 0)
        if (particleVisible[i] != active) {
          setAllRenderablesLayerMask(rm, pAsset, if (active) 0x01 else 0x00)
          particleVisible[i] = active
        }
        if (active) {
          val ti = tm.getInstance(pAsset.root)
          if (ti != 0) {
            val side = if (i % 2 == 0) 1f else -1f
            val lateralX =
              if (state.isNitroActive && i < 4) {
                rendererCarX + side * (0.48f + (i % 2) * 0.18f)
              } else {
                side * (5.9f + (i % 5) * 0.95f)
              }
            val heightY =
              if (state.isNitroActive && i < 4) 0.42f else (0.55f + (i % 4) * 0.48f)
            val cycleZ = 44f
            val phaseZ = (i * 5.3f) % cycleZ
            val zPos =
              if (state.isNitroActive && i < 4) {
                -2.6f - ((state.distanceMeters.toFloat() * 1.8f + i * 1.1f) % 3.6f)
              } else {
                val raw = ((phaseZ - (state.distanceMeters.toFloat() * 1.25f) % cycleZ) + cycleZ) % cycleZ
                raw - 8f
              }
            Matrix.setIdentityM(tmpMatA, 0)
            Matrix.translateM(tmpMatA, 0, lateralX, heightY, zPos)
            Matrix.scaleM(tmpMatA, 0, 1f, 1f, streakLengthScale)
            tm.setTransform(ti, tmpMatA)
          }
        }
      }
    }

    // 6. Update player headlights & overhead streetlamp point lights for Night / Sunset legibility
    updateDynamicLights(eng, rendererCarX, state.distanceMeters, showroomMode)
  }

  private fun instantiateEnvironmentPropPools(
    eng: Engine,
    scn: Scene,
    bundle: ActiveEnvironmentSceneryBundle,
    quality: GraphicsQualityPreset,
    createGlbAssetFn: (ByteArray) -> FilamentAsset?,
  ) {
    val preparedGlbCache = HashMap<String, ByteArray>()

    for (rule in bundle.recipe.propRules) {
      if (rule.isFarBackdrop && !quality.enableFarBackdropModels) continue
      val payload = bundle.modelsByAssetId[rule.assetId] ?: continue

      val preparedBytes =
        preparedGlbCache.getOrPut(rule.assetId) {
          SceneryGltfAdapter.preparePayloadForFilament(
            payload = payload,
            isNightEnvironment = bundle.recipe.isNightIlluminated,
            desertPlacementScale =
              if (rule.assetId == SceneryCatalog.DESERT_MARS_SAND_ID) {
                rule.uniformScale
              } else {
                SceneryCatalog.DESERT_MARS_AUTHORED_UNIFORM_SCALE
              },
          )
        }

      val pairCount = max(1, (rule.basePoolPairs * quality.propPoolMultiplier).roundToInt())
      for (pairIdx in 0 until pairCount) {
        for (sideIdx in 0..1) {
          val sideSign = if (sideIdx == 0) 1f else -1f
          val yawDeg = if (sideIdx == 0) rule.yawLeftDegrees else rule.yawRightDegrees
          val asset = createGlbAssetFn(preparedBytes) ?: continue

          val postYawBounds = computeAssetPostYawBounds(eng, asset, yawDeg)
          val uniformScale =
            if (rule.targetHeightMeters != null) {
              val rawH = abs(postYawBounds.maxY - postYawBounds.minY).coerceAtLeast(0.05f)
              rule.targetHeightMeters / rawH
            } else {
              rule.uniformScale
            }

          // Base transform grounds minY at rule.yOffsetMeters & centers X/Z around placement origin
          val baseMat = FloatArray(16)
          Matrix.setIdentityM(baseMat, 0)
          Matrix.translateM(
            baseMat,
            0,
            -postYawBounds.centerX * uniformScale,
            -postYawBounds.minY * uniformScale + rule.yOffsetMeters,
            -postYawBounds.centerZ * uniformScale,
          )
          Matrix.scaleM(baseMat, 0, uniformScale, uniformScale, uniformScale)
          if (yawDeg != 0f) {
            Matrix.rotateM(baseMat, 0, yawDeg, 0f, 1f, 0f)
          }

          // Deterministic lateral stagger outside playable lanes & barriers
          val jitterFrac = (((pairIdx * 7 + sideIdx * 3) % 11) / 10.0f)
          val rawAbsX =
            rule.minAbsLateralXMeters +
              (rule.maxAbsLateralXMeters - rule.minAbsLateralXMeters) * jitterFrac
          val scaledHalfX = (postYawBounds.sizeX * uniformScale) * 0.5f
          val safeMinAbsX =
            when (rule.zone) {
              SceneryPlacementZone.SHOULDER_NEAR ->
                max(SceneryCatalog.MINIMUM_SAFE_ROADSIDE_ABS_X_METERS, SceneryCatalog.BARRIER_OUTER_HALF_WIDTH_METERS + 0.30f)
              SceneryPlacementZone.ROADSIDE_MID ->
                max(SceneryCatalog.MINIMUM_SAFE_ROADSIDE_ABS_X_METERS + 2.0f, 9.4f + scaledHalfX * 0.45f)
              SceneryPlacementZone.SKYLINE_FLANK,
              SceneryPlacementZone.DESERT_SAND_TERRAIN_10X,
              SceneryPlacementZone.FAR_VISTA_BACKDROP ->
                max(12.5f, SceneryCatalog.MINIMUM_SAFE_ROADSIDE_ABS_X_METERS + scaledHalfX + 1.5f)
            }
          val safeAbsX = max(rawAbsX, safeMinAbsX)

          val spacing = rule.cycleLengthMeters / pairCount.toFloat()
          val sideStaggerZ = if (sideIdx == 1) spacing * 0.45f else 0f
          val zPhase = (pairIdx * spacing + sideStaggerZ) % rule.cycleLengthMeters

          var pineTracker: PineInstanceLodTracker? = null
          var pineEntities: Array<IntArray>? = null
          if (rule.usesPineThreeLodHysteresis) {
            val resolved = resolvePineThreeLodRenderables(eng, asset)
            if (resolved != null) {
              pineTracker =
                PineInstanceLodTracker(
                  distanceScale = quality.pineLodDistanceScale,
                ).also { tracker ->
                  tracker.markPreparedOnce()
                  tracker.resetAtDistance(
                    distanceMeters = zPhase,
                    distanceScale = quality.pineLodDistanceScale,
                  )
                }
              pineEntities = resolved
            }
          }

          val castShadow =
            quality.enableDirectionalShadows &&
              !rule.isFarBackdrop &&
              rule.zone != SceneryPlacementZone.DESERT_SAND_TERRAIN_10X
          setCastShadowsOnly(
            eng = eng,
            asset = asset,
            cast = castShadow,
            receive = quality.enableDirectionalShadows,
          )

          scn.addEntities(asset.entities)

          // Immediately apply single-LOD mask on Pine instances so all 3 LODs never overlap even on frame 0
          if (pineTracker != null && pineEntities != null) {
            val rm = eng.renderableManager
            val mask = pineTracker.currentVisibilityMask()
            for (lodIdx in 0..2) {
              val layer = if (mask[lodIdx]) 0x01 else 0x00
              for (ent in pineEntities[lodIdx]) {
                val ri = rm.getInstance(ent)
                if (ri != 0) rm.setLayerMask(ri, 0xFF, layer)
              }
            }
          }

          sceneryInstances +=
            PooledSceneryInstance(
              rule = rule,
              asset = asset,
              baseMatrix = baseMat,
              sideSign = sideSign,
              lateralAbsXMeters = safeAbsX,
              zPhaseMeters = zPhase,
              pineLodTracker = pineTracker,
              pineLodEntities = pineEntities,
              visibleInCulledRange = true,
              lastAppliedPineLod = pineTracker?.activeLodIndex ?: -1,
            )
        }
      }
    }
  }

  private fun resolvePineThreeLodRenderables(
    eng: Engine,
    asset: FilamentAsset,
  ): Array<IntArray>? {
    val tm = eng.transformManager
    val rm = eng.renderableManager
    val groups = Array(3) { ArrayList<Int>() }

    // 1. Collect subtree renderables under Pine_medium_1_LOD0, Pine_medium_1_LOD1, Pine_medium_1_LOD2
    for (lodIdx in 0..2) {
      val groupName = PineLodSelector.LOD_GROUP_NODE_NAMES[lodIdx]
      val rootEnt = asset.getFirstEntityByName(groupName)
      if (rootEnt != 0) {
        fun collectSubtree(ent: Int) {
          if (rm.getInstance(ent) != 0) {
            groups[lodIdx].add(ent)
          }
          val ti = tm.getInstance(ent)
          if (ti != 0) {
            for (child in tm.getChildren(ti, null)) {
              collectSubtree(child)
            }
          }
        }
        collectSubtree(rootEnt)
      }
    }

    // 2. Fallback name prefix match if gltfio flattened leaf mesh entities
    if (groups.any { it.isEmpty() }) {
      for (ent in asset.renderableEntities) {
        val nm = asset.getName(ent) ?: continue
        when {
          nm.contains("LOD0", ignoreCase = true) && ent !in groups[0] -> groups[0].add(ent)
          nm.contains("LOD1", ignoreCase = true) && ent !in groups[1] -> groups[1].add(ent)
          nm.contains("LOD2", ignoreCase = true) && ent !in groups[2] -> groups[2].add(ent)
        }
      }
    }

    if (groups.any { it.isEmpty() }) return null
    return Array(3) { idx -> groups[idx].toIntArray() }
  }

  private fun initContactShadowPool(
    eng: Engine,
    scn: Scene,
    createGlbAssetFn: (ByteArray) -> FilamentAsset?,
  ) {
    val shadowGlb = ProceduralSceneryGlbBuilder.buildContactShadowGlb()
    createGlbAssetFn(shadowGlb)?.let { sh ->
      disableShadowsOnAsset(eng, sh)
      scn.addEntities(sh.entities)
      heroShadowAsset = sh
      heroShadowVisible = true
    }
    val rm = eng.renderableManager
    repeat(MAX_TRAFFIC_CONTACT_SHADOWS) { idx ->
      val sh = createGlbAssetFn(shadowGlb) ?: return@repeat
      disableShadowsOnAsset(eng, sh)
      setAllRenderablesLayerMask(rm, sh, 0x00)
      scn.addEntities(sh.entities)
      trafficShadowAssets += sh
      trafficShadowVisible[idx] = false
    }
  }

  private fun initParticlePool(
    eng: Engine,
    scn: Scene,
    tintRgb: FloatArray,
    createGlbAssetFn: (ByteArray) -> FilamentAsset?,
  ) {
    val streakGlb = ProceduralSceneryGlbBuilder.buildParticleStreakGlb(tintRgb)
    val rm = eng.renderableManager
    repeat(PARTICLE_STREAK_POOL_COUNT) { idx ->
      val asset = createGlbAssetFn(streakGlb) ?: return@repeat
      disableShadowsOnAsset(eng, asset)
      setAllRenderablesLayerMask(rm, asset, 0x00)
      scn.addEntities(asset.entities)
      particleAssets += asset
      particleVisible[idx] = false
    }
  }

  private fun initDynamicHighwayLights(
    eng: Engine,
    scn: Scene,
    headlightCandela: Float,
    streetLampCount: Int,
  ) {
    if (headlightCandela > 0f) {
      val outerConeRad = 0.68f
      val innerConeRad = 0.28f
      leftHeadlightEntity =
        EntityManager.get().create().also { ent ->
          LightManager.Builder(LightManager.Type.FOCUSED_SPOT)
            .color(0.96f, 0.98f, 1.0f)
            .intensity(headlightCandela)
            .position(0.62f, 0.72f, 2.05f)
            .direction(0.0f, -0.09f, 0.995f)
            .spotLightCone(innerConeRad, outerConeRad)
            .falloff(72.0f)
            .castShadows(false)
            .build(eng, ent)
          scn.addEntity(ent)
        }
      rightHeadlightEntity =
        EntityManager.get().create().also { ent ->
          LightManager.Builder(LightManager.Type.FOCUSED_SPOT)
            .color(0.96f, 0.98f, 1.0f)
            .intensity(headlightCandela)
            .position(-0.62f, 0.72f, 2.05f)
            .direction(0.0f, -0.09f, 0.995f)
            .spotLightCone(innerConeRad, outerConeRad)
            .falloff(72.0f)
            .castShadows(false)
            .build(eng, ent)
          scn.addEntity(ent)
        }
    }

    repeat(streetLampCount.coerceIn(0, 6)) {
      val lampEnt = EntityManager.get().create()
      LightManager.Builder(LightManager.Type.POINT)
        .color(1.0f, 0.84f, 0.62f)
        .intensity(62_000f)
        .position(0f, 8.6f, 22f)
        .falloff(38.0f)
        .castShadows(false)
        .build(eng, lampEnt)
      scn.addEntity(lampEnt)
      streetLampEntities += lampEnt
    }
  }

  private fun updateDynamicLights(
    eng: Engine,
    rendererCarX: Float,
    distanceMeters: Double,
    showroomMode: Boolean,
  ) {
    val lm = eng.lightManager
    val enableHeadlights = !showroomMode && activeHeadlightCandela > 0f
    if (leftHeadlightEntity != 0) {
      val li = lm.getInstance(leftHeadlightEntity)
      if (li != 0) {
        lm.setIntensity(li, if (enableHeadlights) activeHeadlightCandela else 0f)
        lm.setPosition(li, rendererCarX + 0.62f, 0.72f, 2.05f)
      }
    }
    if (rightHeadlightEntity != 0) {
      val ri = lm.getInstance(rightHeadlightEntity)
      if (ri != 0) {
        lm.setIntensity(ri, if (enableHeadlights) activeHeadlightCandela else 0f)
        lm.setPosition(ri, rendererCarX - 0.62f, 0.72f, 2.05f)
      }
    }

    if (streetLampEntities.isNotEmpty()) {
      val spacingZ = 22f
      val cycleZ = spacingZ * streetLampEntities.size
      for (i in streetLampEntities.indices) {
        val inst = lm.getInstance(streetLampEntities[i])
        if (inst != 0) {
          val rawZ = (((i * spacingZ) - (distanceMeters % cycleZ.toDouble()).toFloat()) % cycleZ + cycleZ) % cycleZ
          val lampZ = rawZ - 12f
          val lampX = if (i % 2 == 0) 6.8f else -6.8f
          lm.setPosition(inst, lampX, 8.6f, lampZ)
        }
      }
    }
  }

  private fun applyGroundPbrTextures(
    eng: Engine,
    skirt: FilamentAsset,
    maps: LoadedGroundPbrMaps,
  ) {
    val sampler =
      TextureSampler(
        TextureSampler.MinFilter.LINEAR,
        TextureSampler.MagFilter.LINEAR,
        TextureSampler.WrapMode.REPEAT,
      ).apply { anisotropy = 4.0f }

    val colorTex = maps.colorBitmap?.let { uploadEnvironmentTexture(eng, it, isSrgb = true) }
    val normalTex = maps.normalBitmap?.let { uploadEnvironmentTexture(eng, it, isSrgb = false) }
    val roughTex = maps.roughnessBitmap?.let { uploadEnvironmentTexture(eng, it, isSrgb = false) }

    skirt.instance.materialInstances.forEach { mi ->
      if (mi.name == "GroundSkirtSurface") {
        val mat = mi.material
        if (colorTex != null && mat.hasParameter("baseColorMap")) {
          mi.setParameter("baseColorMap", colorTex, sampler)
        }
        if (normalTex != null && mat.hasParameter("normalMap")) {
          mi.setParameter("normalMap", normalTex, sampler)
        }
        if (roughTex != null && mat.hasParameter("metallicRoughnessMap")) {
          mi.setParameter("metallicRoughnessMap", roughTex, sampler)
        }
      }
    }
  }

  private fun tuneRoadMaterialsForEnvironment(
    road: FilamentAsset,
    isNight: Boolean,
    markingBoost: Float,
  ) {
    road.instance.materialInstances.forEach { mi ->
      val mat = mi.material
      when (mi.name) {
        "LaneMarkingPaint" -> {
          if (mat.hasParameter("baseColorFactor")) {
            if (isNight) {
              mi.setParameter("baseColorFactor", 1.0f, 0.98f, 0.90f, 1.0f)
            } else {
              mi.setParameter("baseColorFactor", 0.92f + markingBoost * 0.08f, 0.94f, 0.98f, 1.0f)
            }
          }
          if (mat.hasParameter("roughnessFactor")) {
            mi.setParameter("roughnessFactor", if (isNight) 0.28f else 0.50f)
          }
        }
        "CurbBarrier" -> {
          if (mat.hasParameter("baseColorFactor")) {
            if (isNight) {
              mi.setParameter("baseColorFactor", 0.46f, 0.52f, 0.62f, 1.0f)
            } else {
              mi.setParameter("baseColorFactor", 0.30f, 0.34f, 0.40f, 1.0f)
            }
          }
        }
      }
    }
  }

  private fun uploadEnvironmentTexture(
    eng: Engine,
    bitmap: Bitmap,
    isSrgb: Boolean,
  ): Texture? {
    if (bitmap.isRecycled) return null
    val format = if (isSrgb) Texture.InternalFormat.SRGB8_A8 else Texture.InternalFormat.RGBA8
    val texture =
      Texture.Builder()
        .width(bitmap.width)
        .height(bitmap.height)
        .levels(1)
        .sampler(Texture.Sampler.SAMPLER_2D)
        .format(format)
        .build(eng)
    TextureHelper.setBitmap(eng, texture, 0, bitmap)
    if (!bitmap.isRecycled) {
      bitmap.recycle()
    }
    environmentTextures += texture
    return texture
  }

  private fun computeAssetPostYawBounds(
    eng: Engine,
    asset: FilamentAsset,
    yawDegrees: Float,
  ): VehicleBounds {
    val tm = eng.transformManager
    val rm = eng.renderableManager
    val acc = StarterVehicleNormalizer.BoundsAccumulator()
    val tmpBox = Box()
    val worldMat = FloatArray(16)
    val yawMat =
      FloatArray(16).apply {
        Matrix.setIdentityM(this, 0)
        if (yawDegrees != 0f) {
          Matrix.rotateM(this, 0, yawDegrees, 0f, 1f, 0f)
        }
      }
    val rotatedWorld = FloatArray(16)
    for (ent in asset.renderableEntities) {
      val ri = rm.getInstance(ent)
      val ti = tm.getInstance(ent)
      if (ri != 0 && ti != 0) {
        rm.getAxisAlignedBoundingBox(ri, tmpBox)
        tm.getWorldTransform(ti, worldMat)
        Matrix.multiplyMM(rotatedWorld, 0, yawMat, 0, worldMat, 0)
        val c = tmpBox.center
        val h = tmpBox.halfExtent
        acc.includeTransformedBox(c[0], c[1], c[2], h[0], h[1], h[2], rotatedWorld)
      }
    }
    return acc.toVehicleBoundsOrNull() ?: VehicleBounds(-1f, 0f, -1f, 1f, 2f, 1f)
  }

  private fun disableShadowsOnAsset(eng: Engine, asset: FilamentAsset) {
    setCastShadowsOnly(eng, asset, cast = false, receive = false)
  }

  private fun setCastShadowsOnly(
    eng: Engine,
    asset: FilamentAsset,
    cast: Boolean,
    receive: Boolean,
  ) {
    val rm = eng.renderableManager
    for (ent in asset.renderableEntities) {
      val ri = rm.getInstance(ent)
      if (ri != 0) {
        rm.setCastShadows(ri, cast)
        rm.setReceiveShadows(ri, receive)
      }
    }
  }

  private fun setAllRenderablesLayerMask(
    rm: com.google.android.filament.RenderableManager,
    asset: FilamentAsset,
    mask: Int,
  ) {
    for (ent in asset.renderableEntities) {
      val ri = rm.getInstance(ent)
      if (ri != 0) {
        rm.setLayerMask(ri, 0xFF, mask)
      }
    }
  }

  companion object {
    private const val MAX_TRAFFIC_CONTACT_SHADOWS = 8
    private const val PARTICLE_STREAK_POOL_COUNT = 12
  }
}
