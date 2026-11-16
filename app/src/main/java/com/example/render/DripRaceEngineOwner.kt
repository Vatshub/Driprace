package com.example.render

import android.graphics.Bitmap
import android.opengl.Matrix
import android.view.Choreographer
import android.view.Surface
import android.view.SurfaceView
import com.example.assets.ActiveEnvironmentSceneryBundle
import com.example.assets.BundledAsphaltMaps
import com.example.assets.HeroModelOrigin
import com.example.assets.LoadedHeroModelBytes
import com.example.assets.LoadedTrafficModelBytes
import com.example.assets.VerifiedHazardSceneryProps
import com.example.garage.BodyPaintColor
import com.example.garage.HeroVehicleCatalog
import com.example.garage.HeroVehicleSpec
import com.example.garage.HeroWheelMode
import com.example.garage.PaintCatalog
import com.example.progression.EnvironmentLevelSpec
import com.example.sim.CoinTier
import com.example.sim.DrivingInputSnapshot
import com.example.sim.DrivingSimState
import com.example.sim.DrivingSimulation
import com.example.sim.PowerUpType
import com.example.traffic.StaticHazardKind
import kotlin.math.sin
import com.google.android.filament.Camera
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.Filament
import com.google.android.filament.IndirectLight
import com.google.android.filament.LightManager
import com.google.android.filament.MaterialInstance
import com.google.android.filament.Renderer
import com.google.android.filament.Scene
import com.google.android.filament.Skybox
import com.google.android.filament.SwapChain
import com.google.android.filament.Texture
import com.google.android.filament.TextureSampler
import com.google.android.filament.View
import com.google.android.filament.Viewport
import com.google.android.filament.android.TextureHelper
import com.google.android.filament.android.UiHelper
import com.google.android.filament.gltfio.AssetLoader
import com.google.android.filament.gltfio.FilamentAsset
import com.google.android.filament.gltfio.Gltfio
import com.google.android.filament.gltfio.ResourceLoader
import com.google.android.filament.gltfio.UbershaderProvider
import com.google.android.filament.utils.Utils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.max

interface EngineRendererPort {
  fun loadSceneAssets(
    starterGlbBytes: ByteArray,
    asphaltMaps: BundledAsphaltMaps,
    onViewportChanged: (String) -> Unit,
  ): Result<VehicleNormalizationPlan>

  fun loadHeroVehicle(
    loadedHero: LoadedHeroModelBytes,
    paintColor: BodyPaintColor,
  ): Result<VehicleNormalizationPlan> =
    Result.success(
      StarterVehicleNormalizer.computeNormalizationPlan(
        VehicleBounds(-0.94f, 0f, -2.18f, 0.94f, 1.38f, 2.18f)
      )
    )

  fun applyHeroBodyPaint(paintColor: BodyPaintColor) {}

  fun setShowroomCamera(
    enabled: Boolean,
    yawDegrees: Float = 36f,
    pitchDegrees: Float = 16f,
    distanceMeters: Float = 6.4f,
  ) {}

  fun setVerifiedHazardProps(props: VerifiedHazardSceneryProps) {}

  fun loadTrafficPool(trafficModels: List<LoadedTrafficModelBytes>) {}

  fun applyEnvironmentConfig(environment: EnvironmentLevelSpec) {}

  fun applyEnvironmentScenery(
    environment: EnvironmentLevelSpec,
    bundle: ActiveEnvironmentSceneryBundle?,
  ) {
    applyEnvironmentConfig(environment)
  }

  fun bindSimulation(
    simulation: DrivingSimulation,
    onSimulationStepped: (DrivingSimState) -> Unit,
  )

  fun setSimulationRunning(running: Boolean)

  fun setDrivingInput(input: DrivingInputSnapshot)

  fun syncVisualState(state: DrivingSimState)

  fun attachSurfaceView(surfaceView: SurfaceView)

  fun detachSurfaceView(surfaceView: SurfaceView)

  fun setRenderingActive(active: Boolean)

  fun setReducedMotion(enabled: Boolean) {}

  fun destroy()
}

/**
 * Single authoritative Filament 3D rendering owner for DRIP RACE.
 *
 * Owns one Engine, one Renderer, one Scene, one View, one Camera, one UbershaderProvider,
 * one AssetLoader, one ResourceLoader, and at most one loaded active hero vehicle FilamentAsset.
 * Survives UI state transitions (Home <-> Garage <-> Running <-> Paused <-> GameOver) and
 * configuration changes without recreating Engine or leaking GLB entities/textures.
 */
class DripRaceEngineOwner : EngineRendererPort {
  private var initialized = false
  private var destroyed = false

  private var engine: Engine? = null
  private var renderer: Renderer? = null
  private var scene: Scene? = null
  private var view: View? = null
  private var camera: Camera? = null
  private var swapChain: SwapChain? = null
  private var uiHelper: UiHelper? = null

  private var materialProvider: UbershaderProvider? = null
  private var assetLoader: AssetLoader? = null
  private var resourceLoader: ResourceLoader? = null

  private var sunEntity: Int = 0
  private var fillLightEntity: Int = 0
  private var indirectLight: IndirectLight? = null
  private var skybox: Skybox? = null

  private var roadAsset: FilamentAsset? = null
  private var vehicleAsset: FilamentAsset? = null
  private var activeHeroSpec: HeroVehicleSpec = HeroVehicleCatalog.STARTER_SPEC
  private var baseVehicleMatrix: FloatArray? = null
  private var steeringAppliedForCurrentAsset = false
  private val uploadedTextures = mutableListOf<Texture>()
  private var scratchDirectBuffer: ByteBuffer = ByteBuffer.allocateDirect(0)

  // Dynamic Phase 2 & Phase 5 hero vehicle wheel, paint, and brake-light handles
  private val wheelSpinEntities = mutableListOf<Int>()
  private val wheelSpinTransformInstances = mutableListOf<Int>()
  private val wheelSpinBaseLocals = mutableListOf<FloatArray>()
  private val wheelRollSigns = mutableListOf<Float>()
  private var activeWheelAxis = floatArrayOf(1f, 0f, 0f)
  private var frontLeftWheelInstance: Int = 0
  private var frontRightWheelInstance: Int = 0
  private var frontLeftBaseLocal: FloatArray? = null
  private var frontRightBaseLocal: FloatArray? = null
  private val paintMaterialInstances = mutableListOf<MaterialInstance>()
  private val brakeLightMaterials = mutableListOf<MaterialInstance>()
  private var lastBrakeLightsLit: Boolean? = null
  private var measuredWheelRadiusMeters: Double = DrivingSimulation.DEFAULT_STARTER_WHEEL_RADIUS_METERS

  // Phase 5 Showroom Camera State
  private var showroomCameraEnabled = false
  private var showroomYawDeg = 36f
  private var showroomPitchDeg = 16f
  private var showroomDistanceMeters = 6.4f

  // Phase 3 pooled traffic & static hazard rigs (pre-allocated once, reused cleanly across runs)
  private data class PooledObstacleRig(
    val archetypeId: String,
    val asset: FilamentAsset,
    val baseMatrix: FloatArray,
    var isVisible: Boolean = false,
  )

  private val trafficRigsByArchetype = mutableMapOf<String, MutableList<PooledObstacleRig>>()
  private val activeRigCursor = mutableMapOf<String, Int>()
  private val visibleRigs = mutableSetOf<PooledObstacleRig>()

  // Phase 4 bounded pickup rigs (3 coin tiers + 4 power-ups, recycled cleanly)
  private val pickupRigsByArchetype = mutableMapOf<String, MutableList<PooledObstacleRig>>()
  private val activePickupCursor = mutableMapOf<String, Int>()
  private val visiblePickupRigs = mutableSetOf<PooledObstacleRig>()

  // Phase 7 single active environment controller + verified roadside hazard GLB bytes
  private val environmentSceneController = EnvironmentSceneController()
  private var verifiedHazardProps: VerifiedHazardSceneryProps =
    VerifiedHazardSceneryProps(null, null, null)

  // Bound simulation authority
  private var simulation: DrivingSimulation? = null
  private var simulationSteppedCallback: ((DrivingSimState) -> Unit)? = null
  private var simulationRunning = false
  private var currentDrivingInput = DrivingInputSnapshot()
  private var lastFrameTimeNanos: Long = 0L

  private var attachedSurfaceView: SurfaceView? = null
  private var viewportCallback: ((String) -> Unit)? = null
  private var renderingActive = false
  private var reducedMotionEnabled = false
  private var frameScheduled = false
  private var lastWidth = 0
  private var lastHeight = 0

  private val tmpMatrixA = FloatArray(16)
  private val tmpMatrixB = FloatArray(16)
  private val tmpRoadMatrix = FloatArray(16)
  private val tmpWheelSpinMatrix = FloatArray(16)
  private val tmpWheelOutMatrix = FloatArray(16)
  private val tmpTrafficMatrixA = FloatArray(16)
  private val tmpTrafficMatrixB = FloatArray(16)

  private val choreographer = Choreographer.getInstance()
  private val frameCallback =
    object : Choreographer.FrameCallback {
      override fun doFrame(frameTimeNanos: Long) {
        frameScheduled = false
        if (destroyed || !renderingActive) return

        val sim = simulation
        if (simulationRunning && sim != null && !showroomCameraEnabled) {
          if (lastFrameTimeNanos > 0L) {
            val deltaSeconds = (frameTimeNanos - lastFrameTimeNanos) / 1_000_000_000.0
            val steps = sim.advanceRenderFrame(deltaSeconds, currentDrivingInput)
            applyVisualState(sim.state)
            if (steps > 0) {
              simulationSteppedCallback?.invoke(sim.state)
            }
          }
          lastFrameTimeNanos = frameTimeNanos
        } else {
          lastFrameTimeNanos = 0L
        }

        val currentSwapChain = swapChain
        val currentRenderer = renderer
        val currentView = view
        if (currentSwapChain != null && currentRenderer != null && currentView != null) {
          if (currentRenderer.beginFrame(currentSwapChain, frameTimeNanos)) {
            currentRenderer.render(currentView)
            currentRenderer.endFrame()
          }
        }
        scheduleNextFrame()
      }
    }

  private fun ensureInitialized() {
    if (initialized) return
    initNativeLibrariesOnce()

    val eng = Engine.create()
    val rend = eng.createRenderer()
    val scn = eng.createScene()
    val vw = eng.createView()
    val camEntity = EntityManager.get().create()
    val cam = eng.createCamera(camEntity)

    vw.scene = scn
    vw.camera = cam
    vw.isPostProcessingEnabled = true
    vw.antiAliasing = View.AntiAliasing.FXAA

    // Key directional sunlight (re-aimed toward camera front-quarter in Garage showroom)
    val sun = EntityManager.get().create()
    LightManager.Builder(LightManager.Type.DIRECTIONAL)
      .color(1.0f, 0.98f, 0.94f)
      .intensity(StudioIblBuilder.KEY_SUN_INTENSITY_LUX)
      .direction(-0.35f, -0.78f, 0.50f)
      .castShadows(true)
      .build(eng, sun)
    scn.addEntity(sun)

    // Non-shadow-casting studio/sky fill directional light for opposite-flank & traffic rear highlights
    val fillSun = EntityManager.get().create()
    LightManager.Builder(LightManager.Type.DIRECTIONAL)
      .color(0.90f, 0.95f, 1.0f)
      .intensity(StudioIblBuilder.FILL_LIGHT_INTENSITY_LUX)
      .direction(0.48f, -0.62f, 0.25f)
      .castShadows(false)
      .build(eng, fillSun)
    scn.addEntity(fillSun)

    // Crash-free 3-band spherical harmonic studio + daylight IndirectLight
    val ibl = StudioIblBuilder.buildDaylightStudioIbl(eng)
    scn.indirectLight = ibl

    val sky = Skybox.Builder().color(0.14f, 0.24f, 0.38f, 1.0f).build(eng)
    scn.skybox = sky

    val ubershader = UbershaderProvider(eng)
    val loader = AssetLoader(eng, ubershader, EntityManager.get())
    val resLoader = ResourceLoader(eng, true)

    val helper =
      UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK).apply {
        isOpaque = true
        renderCallback =
          object : UiHelper.RendererCallback {
            override fun onNativeWindowChanged(surface: Surface) {
              swapChain?.let { eng.destroySwapChain(it) }
              swapChain = eng.createSwapChain(surface)
              scheduleNextFrame()
            }

            override fun onDetachedFromSurface() {
              swapChain?.let {
                eng.destroySwapChain(it)
                swapChain = null
              }
              eng.flushAndWait()
            }

            override fun onResized(width: Int, height: Int) {
              updateViewportAndCamera(width, height)
            }
          }
      }

    engine = eng
    renderer = rend
    scene = scn
    view = vw
    camera = cam
    sunEntity = sun
    fillLightEntity = fillSun
    indirectLight = ibl
    skybox = sky
    materialProvider = ubershader
    assetLoader = loader
    resourceLoader = resLoader
    uiHelper = helper
    initialized = true
    attachedSurfaceView?.let { surfaceView ->
      helper.attachTo(surfaceView)
    }
  }

  override fun loadSceneAssets(
    starterGlbBytes: ByteArray,
    asphaltMaps: BundledAsphaltMaps,
    onViewportChanged: (String) -> Unit,
  ): Result<VehicleNormalizationPlan> {
    viewportCallback = onViewportChanged
    return runCatching {
      ensureInitialized()
      val eng = engine!!
      val scn = scene!!
      val loader = assetLoader!!
      val resLoader = resourceLoader!!

      // Clear any prior vehicle or road asset so repeated retries never duplicate entities
      clearLoadedAssets(eng, scn, loader)

      // 1. Build & load 3-lane highway road GLB via pinned DirectByteBuffer
      val roadBytes =
        ProceduralRoadGlbBuilder.buildRoadGlb(
          includeAsphaltTextureSlots = asphaltMaps.hasAnySuppliedMap
        )
      val road =
        createAndLoadGlbAsset(eng, loader, resLoader, roadBytes)
          ?: error("Filament failed creating 3-lane road asset.")
      scn.addEntities(road.entities)
      roadAsset = road

      if (asphaltMaps.hasAnySuppliedMap) {
        applyAsphaltTextures(eng, road, asphaltMaps)
      }

      // 2. Install genuine starter hero vehicle using unified Phase 5 hero installer
      val starterSpec = HeroVehicleCatalog.STARTER_SPEC
      val defaultPaint =
        PaintCatalog.findById(starterSpec.defaultPaintId) ?: PaintCatalog.SEVEN_FREE_COLORS.first()
      val plan =
        installHeroVehicleInternal(
          eng = eng,
          scn = scn,
          loader = loader,
          resLoader = resLoader,
          loadedHero =
            LoadedHeroModelBytes(
              spec = starterSpec,
              glbBytes = starterGlbBytes,
              origin = HeroModelOrigin.BUNDLED_OFFLINE_GLB,
              sha256Verified = true,
              statusSummary = "Bundled starter-sports.glb",
            ),
          paintColor = defaultPaint,
        )

      // Pre-allocate bounded Phase 4 procedural 3D pickup pool (coins + power-ups)
      initPickupPool(eng, scn, loader, resLoader)
      releaseScratchDirectBuffer()

      simulation?.let { sim ->
        sim.setWheelRadiusMeters(measuredWheelRadiusMeters)
        applyVisualState(sim.state)
      } ?: run {
        if (lastWidth > 0 && lastHeight > 0) {
          updateViewportAndCamera(lastWidth, lastHeight)
        }
      }
      plan
    }
  }

  override fun loadHeroVehicle(
    loadedHero: LoadedHeroModelBytes,
    paintColor: BodyPaintColor,
  ): Result<VehicleNormalizationPlan> {
    return runCatching {
      ensureInitialized()
      val eng = engine!!
      val scn = scene!!
      val loader = assetLoader!!
      val resLoader = resourceLoader!!

      val plan =
        installHeroVehicleInternal(
          eng = eng,
          scn = scn,
          loader = loader,
          resLoader = resLoader,
          loadedHero = loadedHero,
          paintColor = paintColor,
        )
      releaseScratchDirectBuffer()

      simulation?.let { sim ->
        sim.setWheelRadiusMeters(measuredWheelRadiusMeters)
        applyVisualState(sim.state)
      } ?: run {
        if (lastWidth > 0 && lastHeight > 0) {
          updateViewportAndCamera(lastWidth, lastHeight)
        }
      }
      plan
    }
  }

  override fun applyHeroBodyPaint(paintColor: BodyPaintColor) {
    val rgb = paintColor.linearRgb
    for (mi in paintMaterialInstances) {
      val mat = mi.material
      if (mat.hasParameter("baseColorFactor")) {
        mi.setParameter("baseColorFactor", rgb[0], rgb[1], rgb[2], 1.0f)
      } else if (mat.hasParameter("diffuseFactor")) {
        mi.setParameter("diffuseFactor", rgb[0], rgb[1], rgb[2], 1.0f)
      }
    }
  }

  override fun setShowroomCamera(
    enabled: Boolean,
    yawDegrees: Float,
    pitchDegrees: Float,
    distanceMeters: Float,
  ) {
    showroomCameraEnabled = enabled
    showroomYawDeg = yawDegrees
    showroomPitchDeg = pitchDegrees
    showroomDistanceMeters = distanceMeters
    val eng = engine
    if (eng != null) {
      val lm = eng.lightManager
      if (sunEntity != 0) {
        val sunInst = lm.getInstance(sunEntity)
        if (sunInst != 0) {
          if (enabled) {
            val dir = StudioIblBuilder.computeShowroomKeyDirection(yawDegrees)
            lm.setDirection(sunInst, dir[0], dir[1], dir[2])
          } else {
            lm.setDirection(sunInst, -0.35f, -0.78f, 0.50f)
          }
        }
      }
      if (fillLightEntity != 0) {
        val fillInst = lm.getInstance(fillLightEntity)
        if (fillInst != 0) {
          if (enabled) {
            val fillDir = StudioIblBuilder.computeShowroomFillDirection(yawDegrees)
            lm.setDirection(fillInst, fillDir[0], fillDir[1], fillDir[2])
          } else {
            lm.setDirection(fillInst, 0.48f, -0.62f, 0.25f)
          }
        }
      }
    }
    if (lastWidth > 0 && lastHeight > 0) {
      val state = simulation?.state ?: DrivingSimState()
      updateCameraFromState(lastWidth, lastHeight, state)
    }
  }

  override fun setVerifiedHazardProps(props: VerifiedHazardSceneryProps) {
    verifiedHazardProps = props
  }

  override fun applyEnvironmentConfig(environment: EnvironmentLevelSpec) {
    applyEnvironmentScenery(environment = environment, bundle = null)
  }

  override fun applyEnvironmentScenery(
    environment: EnvironmentLevelSpec,
    bundle: ActiveEnvironmentSceneryBundle?,
  ) {
    val eng = engine ?: return
    val scn = scene ?: return
    val vw = view ?: return
    val loader = assetLoader ?: return
    val resLoader = resourceLoader ?: return

    val skyRgb = environment.skyboxRgb
    val newSky = Skybox.Builder().color(skyRgb[0], skyRgb[1], skyRgb[2], 1.0f).build(eng)
    val prevSky = skybox
    scn.skybox = newSky
    skybox = newSky
    if (prevSky != null) {
      eng.destroySkybox(prevSky)
    }

    val newHdrIbl =
      environmentSceneController.applyEnvironment(
        eng = eng,
        scn = scn,
        vw = vw,
        loader = loader,
        roadAsset = roadAsset,
        sunEntity = sunEntity,
        fillLightEntity = fillLightEntity,
        environmentSpec = environment,
        bundle = bundle,
        createGlbAssetFn = { bytes -> createAndLoadGlbAsset(eng, loader, resLoader, bytes) },
      )
    releaseScratchDirectBuffer()

    if (newHdrIbl != null) {
      val prevIbl = indirectLight
      scn.indirectLight = newHdrIbl
      indirectLight = newHdrIbl
      if (prevIbl != null) {
        eng.destroyIndirectLight(prevIbl)
      }
    } else {
      indirectLight?.intensity = environment.iblIntensityLux
    }

    simulation?.let { applyVisualState(it.state) }
  }

  private fun installHeroVehicleInternal(
    eng: Engine,
    scn: Scene,
    loader: AssetLoader,
    resLoader: ResourceLoader,
    loadedHero: LoadedHeroModelBytes,
    paintColor: BodyPaintColor,
  ): VehicleNormalizationPlan {
    clearActiveHeroVehicle(eng, scn, loader)

    val spec = loadedHero.spec
    activeHeroSpec = spec
    val isGenuineGlb = loadedHero.glbBytes != null
    val prepared =
      if (isGenuineGlb) {
        HeroGlbRuntimeAdapter.prepareGlbForFilament(
          spec = spec,
          rawGlbBytes = loadedHero.glbBytes!!,
          initialPaintLinearRgb = paintColor.linearRgb,
        )
      } else {
        ProceduralHeroGlbBuilder.buildFallbackHeroGlb(spec, paintColor.linearRgb)
      }

    val car =
      createAndLoadGlbAsset(eng, loader, resLoader, prepared.glbBytes)
        ?: error("Filament gltfio AssetLoader returned null for ${spec.fileName}.")

    val tm = eng.transformManager
    val useStarterRig = isGenuineGlb && spec.wheelMode == HeroWheelMode.STARTER_GROUP

    // 1. Pre-placement steering/carrier corrections strictly per preparePlayableWheels & prepareVehicle
    if (useStarterRig) {
      if (steeringAppliedForCurrentAsset) {
        error("Starter steering correction must be applied at most once per loaded vehicle.")
      }
      tm.openLocalTransformTransaction()
      for (wheelName in listOf("WheelFrontL", "WheelFrontR")) {
        val wheelEntity = car.getFirstEntityByName(wheelName)
        if (wheelEntity == 0) {
          tm.commitLocalTransformTransaction()
          error("Missing starter wheel node: $wheelName")
        }
        val wheelInstance = tm.getInstance(wheelEntity)
        if (wheelInstance == 0) {
          tm.commitLocalTransformTransaction()
          error("Missing TransformManager instance for $wheelName")
        }
        val currentLocal = tm.getTransform(wheelInstance, FloatArray(16))
        val correctedLocal =
          StarterVehicleNormalizer.premultiplyLocalZRotation(
            localMatrixColumnMajor = currentLocal,
            degrees = StarterVehicleNormalizer.STARTER_FRONT_STEERING_CORRECTION_DEG,
          )
        tm.setTransform(wheelInstance, correctedLocal)
      }
      tm.commitLocalTransformTransaction()
    } else if (isGenuineGlb && spec.wheelMode == HeroWheelMode.CRIMSON_CARRIER_YAW_AND_NODES_X) {
      tm.openLocalTransformTransaction()
      for (carrierName in prepared.carrierNodeNames) {
        val carrierEntity = car.getFirstEntityByName(carrierName)
        val carrierTi = if (carrierEntity != 0) tm.getInstance(carrierEntity) else 0
        if (carrierTi != 0) {
          val curLocal = tm.getTransform(carrierTi, FloatArray(16))
          val corrected = premultiplyLocalYRotation(curLocal, CRIMSON_FRONT_CARRIER_YAW_FIX_DEG)
          tm.setTransform(carrierTi, corrected)
        }
      }
      tm.commitLocalTransformTransaction()
    }

    // 2. Bind playable wheels BEFORE normalized placement when Nissan needs root-local pivot groups
    if (useStarterRig) {
      setupStarterWheelRig(eng, car, 1.0f)
    } else if (isGenuineGlb && spec.wheelMode == HeroWheelMode.NISSAN_PIVOT_GROUPS_Z) {
      setupNissanWheelPivotRigs(eng, car, prepared.nissanWheelGroupNodeNames)
    } else {
      setupIndexedWheelRigs(eng, car, prepared.wheelNodeNames)
    }

    // 3. Compute exact yaw-aligned world AABB and normalize scale/grounding/center
    val yawDegrees =
      if (isGenuineGlb) {
        (spec.yawRadians * 180.0 / PI).toFloat()
      } else {
        0f
      }
    val plan =
      if (yawDegrees == 0f) {
        val bounds = computePostCorrectionWorldBounds(eng, car)
        StarterVehicleNormalizer.computeNormalizationPlan(
          bounds = bounds,
          targetZLengthMeters = spec.targetLengthMeters,
          yawCorrectionDegrees = 0f,
          steeringCorrectionAlreadyApplied = steeringAppliedForCurrentAsset,
        )
      } else {
        val postYawBounds = computePostYawWorldBounds(eng, car, yawDegrees)
        StarterVehicleNormalizer.computeYawAlignedNormalizationPlan(
          postYawBounds = postYawBounds,
          targetZLengthMeters = spec.targetLengthMeters,
          yawDegrees = yawDegrees,
        )
      }
    steeringAppliedForCurrentAsset = true

    val rootInstance = tm.getInstance(car.root)
    if (rootInstance != 0) {
      tm.setTransform(rootInstance, plan.matrixColumnMajor)
    }
    baseVehicleMatrix = plan.matrixColumnMajor.copyOf()

    // 4. Measure realistic tire radius (with uniformScale) and compute forward rolling direction signs
    measuredWheelRadiusMeters = measureHeroWheelRadiusMeters(eng, car, plan.uniformScale)
    activeWheelAxis =
      if (isGenuineGlb && prepared.wheelAxisChar == 'z') {
        floatArrayOf(0f, 0f, 1f)
      } else {
        floatArrayOf(1f, 0f, 0f)
      }
    computeWheelForwardRollSigns(eng)

    // 5. Isolate mutable body paint materials and apply active paint color + cache brake lights
    cachePaintMaterials(car, prepared.paintMaterialNames)
    applyHeroBodyPaint(paintColor)
    cacheBrakeLightMaterials(car, prepared.paintMaterialNames.toSet())

    scn.addEntities(car.entities)
    vehicleAsset = car
    return plan
  }

  override fun loadTrafficPool(trafficModels: List<LoadedTrafficModelBytes>) {
    val eng = engine ?: return
    val scn = scene ?: return
    val loader = assetLoader ?: return
    val resLoader = resourceLoader ?: return

    clearTrafficPool(eng, scn, loader)
    val tm = eng.transformManager

    // 1. Pre-allocate 2 pooled rigs per approved traffic vehicle (genuine GLB if provided, else dimensioned procedural hull)
    for (loaded in trafficModels) {
      val spec = loaded.spec
      val glbBytes =
        loaded.glbBytes?.let { HeroGlbRuntimeAdapter.normalizeGlbMaterialsForDirectIbl(it) }
          ?: ProceduralTrafficGlbBuilder.buildTrafficFallbackGlb(spec)
      val yawDeg =
        if (loaded.glbBytes != null) {
          (spec.yawRadians * 180.0 / PI).toFloat()
        } else {
          0f
        }
      val rigList = trafficRigsByArchetype.getOrPut(spec.id) { mutableListOf() }
      repeat(TRAFFIC_INSTANCES_PER_ARCHETYPE) {
        val asset = createAndLoadGlbAsset(eng, loader, resLoader, glbBytes) ?: return@repeat
        val baseMatrix =
          if (loaded.glbBytes != null) {
            val postYawBounds = computePostYawWorldBounds(eng, asset, yawDeg)
            StarterVehicleNormalizer.computeYawAlignedNormalizationPlan(
              postYawBounds = postYawBounds,
              targetZLengthMeters = spec.targetLengthMeters,
              yawDegrees = yawDeg,
            ).matrixColumnMajor
          } else {
            FloatArray(16).apply { Matrix.setIdentityM(this, 0) }
          }
        scn.addEntities(asset.entities)
        val rig = PooledObstacleRig(archetypeId = spec.id, asset = asset, baseMatrix = baseMatrix)
        setRigVisible(eng, tm, rig, visible = false, renderX = 0f, relativeZ = -400f)
        rigList += rig
      }
    }

    // 2. Pre-allocate 3 pooled rigs per static hazard kind using verified genuine GLBs:
    //    - ROAD_BARRIER / CONCRETE_BLOCK -> concrete-barrier.glb
    //    - WORK_BARRICADE -> roadwork-barricade-textures-1k.glb (Construction Barier-02 strictly excluded)
    //    - BROKEN_DOWN_CAR -> genuine verified passenger GLB
    val genuineStalledCarModel =
      trafficModels.firstOrNull { it.spec.id == "generic-sedan-mmcworks" && it.glbBytes != null }
        ?: trafficModels.firstOrNull { it.spec.id == "mmc-generic-small-suv" && it.glbBytes != null }
        ?: trafficModels.firstOrNull { it.glbBytes != null }
    val preparedBarrierBytes =
      verifiedHazardProps.concreteBarrierGlb?.let {
        SceneryGltfAdapter.prepareSingleGlb(
          assetId = com.example.scenery.SceneryCatalog.CONCRETE_BARRIER_ID,
          rawGlbBytes = it,
        )
      }
    val preparedWorkBarricadeBytes =
      verifiedHazardProps.roadworkBarricadeGlb?.let {
        SceneryGltfAdapter.prepareSingleGlb(
          assetId = com.example.scenery.SceneryCatalog.ROADWORK_BARRICADE_ID,
          rawGlbBytes = it,
        )
      }

    for (hazardKind in StaticHazardKind.entries) {
      val useGenuineCarForHazard =
        hazardKind == StaticHazardKind.BROKEN_DOWN_CAR && genuineStalledCarModel?.glbBytes != null
      val useGenuineConcreteBarrier =
        (hazardKind == StaticHazardKind.ROAD_BARRIER || hazardKind == StaticHazardKind.CONCRETE_BLOCK) &&
          preparedBarrierBytes != null
      val useGenuineWorkBarricade =
        hazardKind == StaticHazardKind.WORK_BARRICADE && preparedWorkBarricadeBytes != null

      val hazardBytes =
        when {
          useGenuineCarForHazard ->
            HeroGlbRuntimeAdapter.normalizeGlbMaterialsForDirectIbl(genuineStalledCarModel!!.glbBytes!!)
          useGenuineConcreteBarrier -> preparedBarrierBytes!!
          useGenuineWorkBarricade -> preparedWorkBarricadeBytes!!
          else -> ProceduralTrafficGlbBuilder.buildStaticHazardGlb(hazardKind)
        }
      val hazardYawDeg =
        if (useGenuineCarForHazard) {
          (genuineStalledCarModel!!.spec.yawRadians * 180.0 / PI).toFloat()
        } else {
          0f
        }
      val rigList = trafficRigsByArchetype.getOrPut(hazardKind.id) { mutableListOf() }
      repeat(HAZARD_INSTANCES_PER_ARCHETYPE) {
        val asset = createAndLoadGlbAsset(eng, loader, resLoader, hazardBytes) ?: return@repeat
        val baseMat =
          when {
            useGenuineCarForHazard -> {
              val postYawBounds = computePostYawWorldBounds(eng, asset, hazardYawDeg)
              StarterVehicleNormalizer.computeYawAlignedNormalizationPlan(
                postYawBounds = postYawBounds,
                targetZLengthMeters = hazardKind.lengthMeters,
                yawDegrees = hazardYawDeg,
              ).matrixColumnMajor
            }
            useGenuineConcreteBarrier || useGenuineWorkBarricade -> {
              val postYawBounds = computePostYawWorldBounds(eng, asset, 0f)
              val scale =
                if (useGenuineConcreteBarrier) {
                  hazardKind.widthMeters / kotlin.math.abs(postYawBounds.sizeX).coerceAtLeast(0.1f)
                } else {
                  hazardKind.heightMeters / kotlin.math.abs(postYawBounds.sizeY).coerceAtLeast(0.1f)
                }
              FloatArray(16).apply {
                Matrix.setIdentityM(this, 0)
                Matrix.translateM(
                  this,
                  0,
                  -postYawBounds.centerX * scale,
                  -postYawBounds.minY * scale,
                  -postYawBounds.centerZ * scale,
                )
                Matrix.scaleM(this, 0, scale, scale, scale)
              }
            }
            else -> FloatArray(16).apply { Matrix.setIdentityM(this, 0) }
          }
        scn.addEntities(asset.entities)
        val rig = PooledObstacleRig(archetypeId = hazardKind.id, asset = asset, baseMatrix = baseMat)
        setRigVisible(eng, tm, rig, visible = false, renderX = 0f, relativeZ = -400f)
        rigList += rig
      }
    }
    releaseScratchDirectBuffer()

    simulation?.let { applyVisualState(it.state) }
  }

  private fun clearTrafficPool(eng: Engine, scn: Scene, loader: AssetLoader) {
    visibleRigs.clear()
    activeRigCursor.clear()
    trafficRigsByArchetype.values.flatten().forEach { rig ->
      scn.removeEntities(rig.asset.entities)
      loader.destroyAsset(rig.asset)
    }
    trafficRigsByArchetype.clear()
  }

  private fun initPickupPool(
    eng: Engine,
    scn: Scene,
    loader: AssetLoader,
    resLoader: ResourceLoader,
  ) {
    clearPickupPool(eng, scn, loader)
    val tm = eng.transformManager
    val identity = FloatArray(16).apply { Matrix.setIdentityM(this, 0) }

    for (tier in CoinTier.entries) {
      val glb = ProceduralPickupGlbBuilder.buildCoinGlb(tier)
      val list = pickupRigsByArchetype.getOrPut(tier.id) { mutableListOf() }
      repeat(COIN_INSTANCES_PER_TIER) {
        val asset = createAndLoadGlbAsset(eng, loader, resLoader, glb) ?: return@repeat
        scn.addEntities(asset.entities)
        val rig = PooledObstacleRig(archetypeId = tier.id, asset = asset, baseMatrix = identity)
        setPickupRigTransform(eng, tm, rig, visible = false, renderX = 0f, renderY = -10f, relativeZ = -400f, yawDeg = 0f)
        list += rig
      }
    }

    for (type in PowerUpType.entries) {
      val glb = ProceduralPickupGlbBuilder.buildPowerUpGlb(type)
      val list = pickupRigsByArchetype.getOrPut(type.id) { mutableListOf() }
      repeat(POWERUP_INSTANCES_PER_TYPE) {
        val asset = createAndLoadGlbAsset(eng, loader, resLoader, glb) ?: return@repeat
        scn.addEntities(asset.entities)
        val rig = PooledObstacleRig(archetypeId = type.id, asset = asset, baseMatrix = identity)
        setPickupRigTransform(eng, tm, rig, visible = false, renderX = 0f, renderY = -10f, relativeZ = -400f, yawDeg = 0f)
        list += rig
      }
    }
  }

  /**
   * Loads a `.glb` asset using a native `DirectByteBuffer` so `gltfio` `AssetLoader::createAsset`
   * (`cgltf_parse`) keeps valid native pointers into the BIN chunk while `ResourceLoader`
   * decodes embedded PNG/JPEG textures (`StbProvider`) and computes tangent frames (`computeTangents`).
   */
  private fun createAndLoadGlbAsset(
    eng: Engine,
    loader: AssetLoader,
    resLoader: ResourceLoader,
    glbBytes: ByteArray,
  ): FilamentAsset? {
    if (scratchDirectBuffer.capacity() < glbBytes.size) {
      scratchDirectBuffer = ByteBuffer.allocateDirect(glbBytes.size).order(ByteOrder.nativeOrder())
    }
    val directBuf = scratchDirectBuffer
    directBuf.clear()
    directBuf.put(glbBytes)
    directBuf.flip()
    val asset = loader.createAsset(directBuf) ?: return null
    resLoader.loadResources(asset)
    resLoader.evictResourceData()
    asset.releaseSourceData()
    eng.flushAndWait()
    java.lang.ref.Reference.reachabilityFence(directBuf)
    return asset
  }

  private fun releaseScratchDirectBuffer() {
    scratchDirectBuffer = ByteBuffer.allocateDirect(0)
  }

  private fun clearPickupPool(eng: Engine, scn: Scene, loader: AssetLoader) {
    visiblePickupRigs.clear()
    activePickupCursor.clear()
    pickupRigsByArchetype.values.flatten().forEach { rig ->
      scn.removeEntities(rig.asset.entities)
      loader.destroyAsset(rig.asset)
    }
    pickupRigsByArchetype.clear()
  }

  private fun setPickupRigTransform(
    eng: Engine,
    tm: com.google.android.filament.TransformManager,
    rig: PooledObstacleRig,
    visible: Boolean,
    renderX: Float,
    renderY: Float,
    relativeZ: Float,
    yawDeg: Float,
  ) {
    val rm = eng.renderableManager
    if (rig.isVisible != visible) {
      val mask = if (visible) 0x01 else 0x00
      for (entity in rig.asset.renderableEntities) {
        val ri = rm.getInstance(entity)
        if (ri != 0) {
          rm.setLayerMask(ri, 0xFF, mask)
        }
      }
      rig.isVisible = visible
    }
    val rootTi = tm.getInstance(rig.asset.root)
    if (rootTi != 0) {
      Matrix.setIdentityM(tmpTrafficMatrixA, 0)
      Matrix.translateM(tmpTrafficMatrixA, 0, renderX, renderY, relativeZ)
      if (yawDeg != 0f) {
        Matrix.rotateM(tmpTrafficMatrixA, 0, yawDeg, 0f, 1f, 0f)
      }
      tm.setTransform(rootTi, tmpTrafficMatrixA)
    }
  }

  private fun setRigVisible(
    eng: Engine,
    tm: com.google.android.filament.TransformManager,
    rig: PooledObstacleRig,
    visible: Boolean,
    renderX: Float,
    relativeZ: Float,
  ) {
    val rm = eng.renderableManager
    if (rig.isVisible != visible) {
      val mask = if (visible) 0x01 else 0x00
      for (entity in rig.asset.renderableEntities) {
        val ri = rm.getInstance(entity)
        if (ri != 0) {
          rm.setLayerMask(ri, 0xFF, mask)
        }
      }
      rig.isVisible = visible
    }
    val rootTi = tm.getInstance(rig.asset.root)
    if (rootTi != 0) {
      Matrix.setIdentityM(tmpTrafficMatrixA, 0)
      Matrix.translateM(tmpTrafficMatrixA, 0, renderX, 0f, relativeZ)
      Matrix.multiplyMM(tmpTrafficMatrixB, 0, tmpTrafficMatrixA, 0, rig.baseMatrix, 0)
      tm.setTransform(rootTi, tmpTrafficMatrixB)
    }
  }

  private fun premultiplyLocalYRotation(localMatrixColumnMajor: FloatArray, degrees: Float): FloatArray {
    val tx = localMatrixColumnMajor[12]
    val ty = localMatrixColumnMajor[13]
    val tz = localMatrixColumnMajor[14]
    val rotY = FloatArray(16)
    Matrix.setIdentityM(rotY, 0)
    Matrix.rotateM(rotY, 0, degrees, 0f, 1f, 0f)
    val linearPart = localMatrixColumnMajor.copyOf()
    linearPart[12] = 0f
    linearPart[13] = 0f
    linearPart[14] = 0f
    linearPart[15] = 1f
    val out = FloatArray(16)
    Matrix.multiplyMM(out, 0, rotY, 0, linearPart, 0)
    out[12] = tx
    out[13] = ty
    out[14] = tz
    out[15] = localMatrixColumnMajor[15]
    return out
  }

  private fun setupStarterWheelRig(eng: Engine, car: FilamentAsset, uniformScale: Float) {
    val tm = eng.transformManager
    val rm = eng.renderableManager
    val tmpBox = com.google.android.filament.Box()
    var maxUnscaledTireRadius = 0f
    val identity = FloatArray(16).apply { Matrix.setIdentityM(this, 0) }

    tm.openLocalTransformTransaction()
    for (wheelName in listOf("WheelFrontL", "WheelFrontR", "WheelRearL", "WheelRearR")) {
      val wheelEntity = car.getFirstEntityByName(wheelName)
      if (wheelEntity == 0) continue
      val wheelInstance = tm.getInstance(wheelEntity)
      if (wheelInstance == 0) continue

      if (wheelName == "WheelFrontL") {
        frontLeftWheelInstance = wheelInstance
        frontLeftBaseLocal = tm.getTransform(wheelInstance, FloatArray(16))
      } else if (wheelName == "WheelFrontR") {
        frontRightWheelInstance = wheelInstance
        frontRightBaseLocal = tm.getTransform(wheelInstance, FloatArray(16))
      }

      // Snapshot existing children BEFORE attaching spinEntity so spinInstance never reparents to itself
      val originalChildren = tm.getChildren(wheelInstance, null)

      // Create synthetic spin pivot child at wheel hub origin (prepare-playable-wheels.mjs pattern)
      val spinEntity = EntityManager.get().create()
      val spinInstance = tm.create(spinEntity)
      tm.setTransform(spinInstance, identity)
      tm.setParent(spinInstance, wheelInstance)
      wheelSpinEntities += spinEntity
      wheelSpinTransformInstances += spinInstance
      wheelSpinBaseLocals += identity.copyOf()

      for (childEntity in originalChildren) {
        if (childEntity == spinEntity || childEntity == wheelEntity) continue
        val childName = car.getName(childEntity) ?: ""
        if (!childName.contains("BrakePad", ignoreCase = true)) {
          val childInstance = tm.getInstance(childEntity)
          if (childInstance != 0 && childInstance != spinInstance) {
            tm.setParent(childInstance, spinInstance)
          }
          val ri = rm.getInstance(childEntity)
          if (ri != 0) {
            rm.getAxisAlignedBoundingBox(ri, tmpBox)
            val halfY = tmpBox.halfExtent[1]
            val halfZ = tmpBox.halfExtent[2]
            maxUnscaledTireRadius = max(maxUnscaledTireRadius, max(halfY, halfZ))
          }
        }
      }
    }
    tm.commitLocalTransformTransaction()

    if (maxUnscaledTireRadius > 0.1f) {
      measuredWheelRadiusMeters = (maxUnscaledTireRadius * uniformScale).toDouble()
    }
  }

  private fun setupIndexedWheelRigs(eng: Engine, car: FilamentAsset, wheelNames: List<String>) {
    val tm = eng.transformManager
    for (name in wheelNames) {
      val entity = car.getFirstEntityByName(name)
      if (entity == 0) continue
      val ti = tm.getInstance(entity)
      if (ti == 0) continue
      wheelSpinTransformInstances += ti
      wheelSpinBaseLocals += tm.getTransform(ti, FloatArray(16))
    }
  }

  private fun setupNissanWheelPivotRigs(
    eng: Engine,
    car: FilamentAsset,
    groups: List<List<String>>,
  ) {
    val tm = eng.transformManager
    val rm = eng.renderableManager
    val rootTi = tm.getInstance(car.root)
    if (rootTi == 0) return

    tm.openLocalTransformTransaction()
    for (groupNames in groups) {
      val leadName = groupNames.firstOrNull() ?: continue
      val leadEntity = car.getFirstEntityByName(leadName)
      if (leadEntity == 0) continue

      val acc = StarterVehicleNormalizer.BoundsAccumulator()
      val tmpBox = com.google.android.filament.Box()
      val worldMat = FloatArray(16)

      fun collectSubtreeBounds(entity: Int) {
        val ri = rm.getInstance(entity)
        val ti = tm.getInstance(entity)
        if (ri != 0 && ti != 0) {
          rm.getAxisAlignedBoundingBox(ri, tmpBox)
          tm.getWorldTransform(ti, worldMat)
          val c = tmpBox.center
          val h = tmpBox.halfExtent
          acc.includeTransformedBox(c[0], c[1], c[2], h[0], h[1], h[2], worldMat)
        }
        if (ti != 0) {
          for (child in tm.getChildren(ti, null)) {
            collectSubtreeBounds(child)
          }
        }
      }
      collectSubtreeBounds(leadEntity)

      val bounds = acc.toVehicleBoundsOrNull() ?: continue
      val pivotX = bounds.centerX
      val pivotY = (bounds.minY + bounds.maxY) * 0.5f
      val pivotZ = bounds.centerZ

      val rootWorld = tm.getWorldTransform(rootTi, FloatArray(16))
      val invRootWorld = FloatArray(16)
      Matrix.invertM(invRootWorld, 0, rootWorld, 0)
      val worldPivot = floatArrayOf(pivotX, pivotY, pivotZ, 1f)
      val localPivot = FloatArray(4)
      Matrix.multiplyMV(localPivot, 0, invRootWorld, 0, worldPivot, 0)

      val pivotEntity = EntityManager.get().create()
      val pivotTi = tm.create(pivotEntity)
      val pivotLocalMat = FloatArray(16)
      Matrix.setIdentityM(pivotLocalMat, 0)
      Matrix.translateM(pivotLocalMat, 0, localPivot[0], localPivot[1], localPivot[2])
      tm.setTransform(pivotTi, pivotLocalMat)
      tm.setParent(pivotTi, rootTi)

      val pivotWorld = FloatArray(16)
      Matrix.multiplyMM(pivotWorld, 0, rootWorld, 0, pivotLocalMat, 0)
      val invPivotWorld = FloatArray(16)
      Matrix.invertM(invPivotWorld, 0, pivotWorld, 0)

      for (nodeName in groupNames) {
        val memberEntity = car.getFirstEntityByName(nodeName)
        if (memberEntity == 0) continue
        val memberTi = tm.getInstance(memberEntity)
        if (memberTi == 0) continue
        val memberWorld = tm.getWorldTransform(memberTi, FloatArray(16))
        val newLocal = FloatArray(16)
        Matrix.multiplyMM(newLocal, 0, invPivotWorld, 0, memberWorld, 0)
        tm.setParent(memberTi, pivotTi)
        tm.setTransform(memberTi, newLocal)
      }

      wheelSpinEntities += pivotEntity
      wheelSpinTransformInstances += pivotTi
      wheelSpinBaseLocals += pivotLocalMat
    }
    tm.commitLocalTransformTransaction()
  }

  private fun measureHeroWheelRadiusMeters(
    eng: Engine,
    car: FilamentAsset,
    uniformScale: Float,
  ): Double {
    if (measuredWheelRadiusMeters > 0.15 && activeHeroSpec.wheelMode == HeroWheelMode.STARTER_GROUP) {
      return measuredWheelRadiusMeters
    }
    val tm = eng.transformManager
    val rm = eng.renderableManager
    val tmpBox = com.google.android.filament.Box()
    var maxRadius = 0f
    for (ti in wheelSpinTransformInstances) {
      val ent =
        car.renderableEntities.firstOrNull { renderEnt ->
          tm.getInstance(renderEnt) == ti
        }
      if (ent != null) {
        val ri = rm.getInstance(ent)
        if (ri != 0) {
          rm.getAxisAlignedBoundingBox(ri, tmpBox)
          val wy = tmpBox.halfExtent[1] * uniformScale
          val wz = tmpBox.halfExtent[2] * uniformScale
          maxRadius = max(maxRadius, max(wy, wz))
        }
      }
    }
    return if (maxRadius in 0.22f..0.52f) {
      maxRadius.toDouble()
    } else {
      DrivingSimulation.DEFAULT_STARTER_WHEEL_RADIUS_METERS
    }
  }

  private fun computeWheelForwardRollSigns(eng: Engine) {
    wheelRollSigns.clear()
    val tm = eng.transformManager
    val worldMat = FloatArray(16)
    val ax = activeWheelAxis[0]
    val ay = activeWheelAxis[1]
    val az = activeWheelAxis[2]
    for (ti in wheelSpinTransformInstances) {
      tm.getWorldTransform(ti, worldMat)
      // Transform local axis direction by upper 3x3 world matrix
      val worldX = worldMat[0] * ax + worldMat[4] * ay + worldMat[8] * az
      wheelRollSigns += if (worldX >= 0f) 1f else -1f
    }
  }

  private fun cachePaintMaterials(car: FilamentAsset, targetPaintNames: List<String>) {
    paintMaterialInstances.clear()
    car.instance.materialInstances.forEach { mi ->
      val nm = mi.name
      if (targetPaintNames.any { tag -> nm == tag || nm.startsWith(tag) }) {
        paintMaterialInstances += mi
      }
    }
  }

  private fun cacheBrakeLightMaterials(car: FilamentAsset, excludedPaintNames: Set<String>) {
    brakeLightMaterials.clear()
    lastBrakeLightsLit = null
    car.instance.materialInstances.forEach { mi ->
      val nm = mi.name
      if (nm !in excludedPaintNames &&
        (nm.equals("Brakelight", ignoreCase = true) ||
          nm.equals("TaillightBar", ignoreCase = true) ||
          nm.equals("RacetrackLEDTaillight", ignoreCase = true) ||
          nm.contains("brakelight", ignoreCase = true) ||
          nm.contains("taillight", ignoreCase = true))
      ) {
        brakeLightMaterials += mi
      }
    }
    updateBrakeLightMaterials(lit = false)
  }

  private fun updateBrakeLightMaterials(lit: Boolean) {
    if (lastBrakeLightsLit == lit) return
    lastBrakeLightsLit = lit
    brakeLightMaterials.forEach { mi ->
      val mat = mi.material
      if (mat.hasParameter("emissiveFactor")) {
        if (lit) {
          mi.setParameter("emissiveFactor", 1.0f, 0.05f, 0.02f)
        } else {
          mi.setParameter("emissiveFactor", 0.25f, 0.01f, 0.01f)
        }
      }
      if (mat.hasParameter("emissiveStrength")) {
        mi.setParameter("emissiveStrength", if (lit) 4.5f else 1.0f)
      }
    }
  }

  private fun computePostYawWorldBounds(eng: Engine, asset: FilamentAsset, yawDegrees: Float): VehicleBounds {
    val tm = eng.transformManager
    val rm = eng.renderableManager
    val acc = StarterVehicleNormalizer.BoundsAccumulator()
    val tmpBox = com.google.android.filament.Box()
    val worldMat = FloatArray(16)
    val yawMat = FloatArray(16).apply {
      Matrix.setIdentityM(this, 0)
      if (yawDegrees != 0f) {
        Matrix.rotateM(this, 0, yawDegrees, 0f, 1f, 0f)
      }
    }
    val rotatedWorld = FloatArray(16)

    for (entity in asset.renderableEntities) {
      val ri = rm.getInstance(entity)
      val ti = tm.getInstance(entity)
      if (ri != 0 && ti != 0) {
        rm.getAxisAlignedBoundingBox(ri, tmpBox)
        tm.getWorldTransform(ti, worldMat)
        Matrix.multiplyMM(rotatedWorld, 0, yawMat, 0, worldMat, 0)
        val c = tmpBox.center
        val h = tmpBox.halfExtent
        acc.includeTransformedBox(c[0], c[1], c[2], h[0], h[1], h[2], rotatedWorld)
      }
    }

    return acc.toVehicleBoundsOrNull()
      ?: VehicleBounds(-0.9f, 0f, -2.2f, 0.9f, 1.5f, 2.2f)
  }

  private fun computePostCorrectionWorldBounds(eng: Engine, car: FilamentAsset): VehicleBounds {
    val tm = eng.transformManager
    val rm = eng.renderableManager
    val acc = StarterVehicleNormalizer.BoundsAccumulator()
    val tmpBox = com.google.android.filament.Box()
    val worldMat = FloatArray(16)

    for (entity in car.renderableEntities) {
      val ri = rm.getInstance(entity)
      val ti = tm.getInstance(entity)
      if (ri != 0 && ti != 0) {
        rm.getAxisAlignedBoundingBox(ri, tmpBox)
        tm.getWorldTransform(ti, worldMat)
        val c = tmpBox.center
        val h = tmpBox.halfExtent
        acc.includeTransformedBox(c[0], c[1], c[2], h[0], h[1], h[2], worldMat)
      }
    }

    return acc.toVehicleBoundsOrNull()
      ?: run {
        val box = car.boundingBox
        val c = box.center
        val h = box.halfExtent
        VehicleBounds(
          minX = c[0] - h[0],
          minY = c[1] - h[1],
          minZ = c[2] - h[2],
          maxX = c[0] + h[0],
          maxY = c[1] + h[1],
          maxZ = c[2] + h[2],
        )
      }
  }

  private fun applyAsphaltTextures(
    eng: Engine,
    road: FilamentAsset,
    maps: BundledAsphaltMaps,
  ) {
    val sampler =
      TextureSampler(
        TextureSampler.MinFilter.LINEAR,
        TextureSampler.MagFilter.LINEAR,
        TextureSampler.WrapMode.REPEAT,
      ).apply { anisotropy = 4.0f }
    val baseTex = maps.baseColorBitmap?.let { uploadBitmapTexture(eng, it, isSrgb = true) }
    val normalTex = maps.normalBitmap?.let { uploadBitmapTexture(eng, it, isSrgb = false) }
    val roughTex = maps.roughnessBitmap?.let { uploadBitmapTexture(eng, it, isSrgb = false) }

    road.instance.materialInstances.forEach { mi ->
      if (mi.name == "AsphaltSurface") {
        val mat = mi.material
        if (baseTex != null && mat.hasParameter("baseColorMap")) {
          mi.setParameter("baseColorMap", baseTex, sampler)
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

  private fun uploadBitmapTexture(eng: Engine, bitmap: Bitmap, isSrgb: Boolean): Texture? {
    if (bitmap.isRecycled) return null
    val format =
      if (isSrgb) Texture.InternalFormat.SRGB8_A8 else Texture.InternalFormat.RGBA8
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
    uploadedTextures += texture
    return texture
  }

  private fun clearActiveHeroVehicle(eng: Engine, scn: Scene, loader: AssetLoader) {
    paintMaterialInstances.clear()
    brakeLightMaterials.clear()
    lastBrakeLightsLit = null
    frontLeftWheelInstance = 0
    frontRightWheelInstance = 0
    frontLeftBaseLocal = null
    frontRightBaseLocal = null
    baseVehicleMatrix = null
    measuredWheelRadiusMeters = DrivingSimulation.DEFAULT_STARTER_WHEEL_RADIUS_METERS

    vehicleAsset?.let { asset ->
      scn.removeEntities(asset.entities)
      loader.destroyAsset(asset)
      vehicleAsset = null
    }
    wheelSpinEntities.forEach { spinEntity ->
      eng.destroyEntity(spinEntity)
      EntityManager.get().destroy(spinEntity)
    }
    wheelSpinEntities.clear()
    wheelSpinTransformInstances.clear()
    wheelSpinBaseLocals.clear()
    wheelRollSigns.clear()
    steeringAppliedForCurrentAsset = false
  }

  private fun clearLoadedAssets(eng: Engine, scn: Scene, loader: AssetLoader) {
    environmentSceneController.disposeActiveEnvironment(eng, scn, loader)
    clearTrafficPool(eng, scn, loader)
    clearPickupPool(eng, scn, loader)
    clearActiveHeroVehicle(eng, scn, loader)

    roadAsset?.let { asset ->
      scn.removeEntities(asset.entities)
      loader.destroyAsset(asset)
      roadAsset = null
    }
    uploadedTextures.forEach { tex -> eng.destroyTexture(tex) }
    uploadedTextures.clear()
  }

  override fun bindSimulation(
    simulation: DrivingSimulation,
    onSimulationStepped: (DrivingSimState) -> Unit,
  ) {
    this.simulation = simulation
    this.simulationSteppedCallback = onSimulationStepped
    simulation.setWheelRadiusMeters(measuredWheelRadiusMeters)
    applyVisualState(simulation.state)
  }

  override fun setSimulationRunning(running: Boolean) {
    simulationRunning = running
    if (!running) {
      lastFrameTimeNanos = 0L
      simulation?.clearPendingFrameAccumulator()
    } else {
      lastFrameTimeNanos = 0L
      scheduleNextFrame()
    }
  }

  override fun setDrivingInput(input: DrivingInputSnapshot) {
    currentDrivingInput = input
  }

  override fun syncVisualState(state: DrivingSimState) {
    applyVisualState(state)
  }

  private fun applyVisualState(state: DrivingSimState) {
    val eng = engine ?: return
    val tm = eng.transformManager
    val baseMatrix = baseVehicleMatrix

    tm.openLocalTransformTransaction()

    // 1. Seamless scrolling road synchronized with distanceMeters
    roadAsset?.let { road ->
      val roadRoot = tm.getInstance(road.root)
      if (roadRoot != 0) {
        val scrollOffset =
          -(state.distanceMeters % ProceduralRoadGlbBuilder.SEAMLESS_REPEAT_CYCLE_METERS).toFloat()
        Matrix.setIdentityM(tmpRoadMatrix, 0)
        Matrix.translateM(tmpRoadMatrix, 0, 0f, 0f, scrollOffset)
        tm.setTransform(roadRoot, tmpRoadMatrix)
      }
    }

    // 2. Vehicle chassis placement: mapped renderer lateral X, subtle roll/pitch, grounded Y=0
    val car = vehicleAsset
    if (car != null && baseMatrix != null) {
      val rootInstance = tm.getInstance(car.root)
      if (rootInstance != 0) {
        val motionScale = if (reducedMotionEnabled) 0.35f else 1.0f
        val effPitch = state.bodyPitchDegrees * motionScale
        val effRoll = state.bodyRollDegrees * motionScale
        val pitchLiftY = kotlin.math.abs(effPitch) * 0.012f
        val rollLiftY = kotlin.math.abs(effRoll) * 0.010f
        val rendererCarX = CoordinateMapper.gameToRendererX(state.carX)
        Matrix.setIdentityM(tmpMatrixA, 0)
        Matrix.translateM(tmpMatrixA, 0, rendererCarX, pitchLiftY + rollLiftY, 0f)
        if (effRoll != 0f) {
          Matrix.rotateM(tmpMatrixA, 0, effRoll, 0f, 0f, 1f)
        }
        if (effPitch != 0f) {
          Matrix.rotateM(tmpMatrixA, 0, effPitch, 1f, 0f, 0f)
        }
        Matrix.multiplyMM(tmpMatrixB, 0, tmpMatrixA, 0, baseMatrix, 0)
        tm.setTransform(rootInstance, tmpMatrixB)
      }
    }

    // 3. Front wheel visual steering (premultiply local Z around base +30deg corrected local transform;
    //    positive Z rotation in BodyUnderside turns front wheels toward +X = screen-left)
    val steerDeg = -state.steerVisualRatio * MAX_FRONT_STEER_DEGREES
    val leftBase = frontLeftBaseLocal
    if (frontLeftWheelInstance != 0 && leftBase != null) {
      val steeredLeft = StarterVehicleNormalizer.premultiplyLocalZRotation(leftBase, steerDeg)
      tm.setTransform(frontLeftWheelInstance, steeredLeft)
    }
    val rightBase = frontRightBaseLocal
    if (frontRightWheelInstance != 0 && rightBase != null) {
      val steeredRight = StarterVehicleNormalizer.premultiplyLocalZRotation(rightBase, steerDeg)
      tm.setTransform(frontRightWheelInstance, steeredRight)
    }

    // 4. Distance-driven wheel rolling around model-specific local axle * forward sign
    val baseSpinDeg = ((state.wheelRollRadians * 180.0) / PI).toFloat()
    val ax = activeWheelAxis[0]
    val ay = activeWheelAxis[1]
    val az = activeWheelAxis[2]
    for (i in wheelSpinTransformInstances.indices) {
      val spinInstance = wheelSpinTransformInstances[i]
      val baseLocal = wheelSpinBaseLocals.getOrNull(i)
      val rollSign = wheelRollSigns.getOrElse(i) { 1f }
      Matrix.setIdentityM(tmpWheelSpinMatrix, 0)
      Matrix.rotateM(tmpWheelSpinMatrix, 0, baseSpinDeg * rollSign, ax, ay, az)
      if (baseLocal != null) {
        Matrix.multiplyMM(tmpWheelOutMatrix, 0, baseLocal, 0, tmpWheelSpinMatrix, 0)
        tm.setTransform(spinInstance, tmpWheelOutMatrix)
      } else {
        tm.setTransform(spinInstance, tmpWheelSpinMatrix)
      }
    }

    // 5. Phase 3 pooled traffic & static hazards synchronized 1:1 with authoritative relativeZ & lane X
    activeRigCursor.clear()
    val newlyVisible = mutableSetOf<PooledObstacleRig>()
    for (obs in state.activeObstacles) {
      val rigList = trafficRigsByArchetype[obs.archetypeId] ?: continue
      val idx = activeRigCursor[obs.archetypeId] ?: 0
      if (idx < rigList.size) {
        activeRigCursor[obs.archetypeId] = idx + 1
        val rig = rigList[idx]
        setRigVisible(
          eng = eng,
          tm = tm,
          rig = rig,
          visible = true,
          renderX = CoordinateMapper.gameToRendererX(obs.x),
          relativeZ = obs.relativeZ.toFloat(),
        )
        newlyVisible += rig
      }
    }
    for (prevRig in visibleRigs) {
      if (prevRig !in newlyVisible) {
        setRigVisible(eng, tm, prevRig, visible = false, renderX = 0f, relativeZ = -400f)
      }
    }
    visibleRigs.clear()
    visibleRigs.addAll(newlyVisible)

    // 5b. Phase 4 pooled procedural 3D coins & power-ups (spin & bob tied strictly to sim time)
    activePickupCursor.clear()
    val newlyVisiblePickups = mutableSetOf<PooledObstacleRig>()
    val simSpinDeg = ((state.totalSimSeconds * 165.0) % 360.0).toFloat()
    for (pickup in state.activePickups) {
      val rigList = pickupRigsByArchetype[pickup.archetypeId] ?: continue
      val idx = activePickupCursor[pickup.archetypeId] ?: 0
      if (idx < rigList.size) {
        activePickupCursor[pickup.archetypeId] = idx + 1
        val rig = rigList[idx]
        val bobY = (sin(state.totalSimSeconds * 4.5 + pickup.logicalId * 0.7) * 0.08).toFloat()
        setPickupRigTransform(
          eng = eng,
          tm = tm,
          rig = rig,
          visible = true,
          renderX = CoordinateMapper.gameToRendererX(pickup.x),
          renderY = pickup.y + bobY,
          relativeZ = pickup.relativeZ.toFloat(),
          yawDeg = (simSpinDeg + (pickup.logicalId * 37L) % 360L).toFloat(),
        )
        newlyVisiblePickups += rig
      }
    }
    for (prevRig in visiblePickupRigs) {
      if (prevRig !in newlyVisiblePickups) {
        setPickupRigTransform(eng, tm, prevRig, visible = false, renderX = 0f, renderY = -10f, relativeZ = -400f, yawDeg = 0f)
      }
    }
    visiblePickupRigs.clear()
    visiblePickupRigs.addAll(newlyVisiblePickups)

    // 5c. Phase 7 active environment roadside/skyline/terrain pools, Pine 3-LOD hysteresis,
    //     grounded contact shadows, restrained particles, and Night Highway headlights/lamps
    environmentSceneController.syncVisualStateInTransaction(
      eng = eng,
      tm = tm,
      state = state,
      showroomMode = showroomCameraEnabled,
      heroVisible = vehicleAsset != null,
    )

    tm.commitLocalTransformTransaction()

    // 6. Brake lights material response
    updateBrakeLightMaterials(lit = state.isBraking)

    // 7. Dynamic chase camera follow / FOV / impact shake response
    if (lastWidth > 0 && lastHeight > 0) {
      updateCameraFromState(lastWidth, lastHeight, state)
    }
  }

  override fun attachSurfaceView(surfaceView: SurfaceView) {
    if (destroyed) return
    ensureInitialized()
    if (attachedSurfaceView === surfaceView) return
    detachCurrentSurface()
    attachedSurfaceView = surfaceView
    uiHelper?.attachTo(surfaceView)
    scheduleNextFrame()
  }

  override fun detachSurfaceView(surfaceView: SurfaceView) {
    if (attachedSurfaceView === surfaceView) {
      detachCurrentSurface()
    }
  }

  private fun detachCurrentSurface() {
    if (attachedSurfaceView != null) {
      uiHelper?.detach()
      attachedSurfaceView = null
    }
  }

  override fun setRenderingActive(active: Boolean) {
    renderingActive = active
    if (active) {
      lastFrameTimeNanos = 0L
      scheduleNextFrame()
    } else {
      lastFrameTimeNanos = 0L
      simulation?.clearPendingFrameAccumulator()
      if (frameScheduled) {
        choreographer.removeFrameCallback(frameCallback)
        frameScheduled = false
      }
    }
  }

  private fun updateViewportAndCamera(width: Int, height: Int) {
    if (width <= 0 || height <= 0) return
    lastWidth = width
    lastHeight = height
    val vw = view ?: return
    vw.viewport = Viewport(0, 0, width, height)
    val state = simulation?.state ?: DrivingSimState()
    val pose = updateCameraFromState(width, height, state)
    val aspect = width.toDouble() / height.toDouble()
    val orientationLabel = if (aspect < 1.0) "Portrait" else "Landscape"
    viewportCallback?.invoke(
      "$orientationLabel ${width}x${height} (FOV ${pose.verticalFovDegrees.toInt()}°)"
    )
  }

  private fun updateCameraFromState(
    width: Int,
    height: Int,
    state: DrivingSimState,
  ): CoordinateMapper.ChaseCameraPose {
    val cam = camera
    val aspect = width.toDouble() / height.toDouble()
    val pose =
      if (showroomCameraEnabled) {
        CoordinateMapper.computeShowroomCameraPose(
          widthPx = width,
          heightPx = height,
          yawDegrees = showroomYawDeg,
          pitchDegrees = showroomPitchDeg,
          distanceMeters = showroomDistanceMeters,
        )
      } else {
        val topSpeedMps = simulation?.config?.topSpeedMps ?: 50.0
        val speedNorm = (state.speedMetersPerSecond / topSpeedMps).toFloat()
        CoordinateMapper.computeChaseCameraPose(
          widthPx = width,
          heightPx = height,
          cameraFollowX = state.cameraFollowX,
          carX = state.carX,
          speedNormalized = speedNorm,
          isNitroActive = state.isNitroActive,
          isBraking = state.isBraking,
          cameraShakeSeconds = state.cameraShakeSeconds,
          reducedMotion = reducedMotionEnabled,
        )
      }
    if (cam != null) {
      cam.setProjection(pose.verticalFovDegrees, aspect, 0.1, 680.0, Camera.Fov.VERTICAL)
      cam.lookAt(
        pose.eyeX,
        pose.eyeY,
        pose.eyeZ,
        pose.centerX,
        pose.centerY,
        pose.centerZ,
        0.0,
        1.0,
        0.0,
      )
    }
    return pose
  }

  private fun scheduleNextFrame() {
    if (!destroyed && renderingActive && swapChain != null && !frameScheduled) {
      frameScheduled = true
      choreographer.postFrameCallback(frameCallback)
    }
  }

  override fun setReducedMotion(enabled: Boolean) {
    reducedMotionEnabled = enabled
  }

  override fun destroy() {
    if (destroyed) return
    destroyed = true
    renderingActive = false
    if (frameScheduled) {
      choreographer.removeFrameCallback(frameCallback)
      frameScheduled = false
    }
    detachCurrentSurface()
    val eng = engine ?: return
    val scn = scene
    val loader = assetLoader
    if (scn != null && loader != null) {
      clearLoadedAssets(eng, scn, loader)
    }
    loader?.destroy()
    resourceLoader?.destroy()
    materialProvider?.destroyMaterials()
    materialProvider?.destroy()
    if (sunEntity != 0) {
      scn?.removeEntity(sunEntity)
      eng.destroyEntity(sunEntity)
      EntityManager.get().destroy(sunEntity)
      sunEntity = 0
    }
    if (fillLightEntity != 0) {
      scn?.removeEntity(fillLightEntity)
      eng.destroyEntity(fillLightEntity)
      EntityManager.get().destroy(fillLightEntity)
      fillLightEntity = 0
    }
    indirectLight?.let { eng.destroyIndirectLight(it) }
    skybox?.let { eng.destroySkybox(it) }
    camera?.let {
      val camEntity = it.entity
      eng.destroyCameraComponent(camEntity)
      EntityManager.get().destroy(camEntity)
    }
    view?.let { eng.destroyView(it) }
    scn?.let { eng.destroyScene(it) }
    renderer?.let { eng.destroyRenderer(it) }
    eng.destroy()

    releaseScratchDirectBuffer()
    engine = null
    renderer = null
    scene = null
    view = null
    camera = null
    indirectLight = null
    skybox = null
    materialProvider = null
    assetLoader = null
    resourceLoader = null
    uiHelper = null
  }

  companion object {
    private const val MAX_FRONT_STEER_DEGREES = 18.0f
    private const val CRIMSON_FRONT_CARRIER_YAW_FIX_DEG = 14.0f
    private const val TRAFFIC_INSTANCES_PER_ARCHETYPE = 2
    private const val HAZARD_INSTANCES_PER_ARCHETYPE = 3
    private const val COIN_INSTANCES_PER_TIER = 10
    private const val POWERUP_INSTANCES_PER_TYPE = 3
    private var libsLoaded = false

    fun initNativeLibrariesOnce() {
      if (libsLoaded) return
      Filament.init()
      Gltfio.init()
      Utils.init()
      libsLoaded = true
    }
  }
}
