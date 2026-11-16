package com.example

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.example.assets.BestScoreRepository
import com.example.assets.BundledAsphaltMaps
import com.example.assets.StarterAssetContract
import com.example.assets.StarterAssetRepository
import com.example.assets.TrafficAssetRepository
import com.example.assets.TrafficImportResult
import com.example.render.CoordinateMapper
import com.example.render.ProceduralRoadGlbBuilder
import com.example.render.ProceduralTrafficGlbBuilder
import com.example.render.StarterVehicleNormalizer
import com.example.render.VehicleBounds
import com.example.sim.CollisionAndReachabilityMath
import com.example.sim.DifficultyDirector
import com.example.sim.DrivingInputSnapshot
import com.example.sim.DrivingSimulation
import com.example.sim.EncounterCandidate
import com.example.sim.TrafficSimulation
import com.example.sim.VehiclePhysicsConfig
import com.example.state.DripRaceViewModel
import com.example.state.GamePhase
import com.example.traffic.StaticHazardKind
import com.example.traffic.TrafficCatalog
import com.example.traffic.TrafficCategory
import com.example.traffic.TrafficModelOrigin
import com.example.ui.DripRaceRootScreen
import com.example.ui.theme.DripRaceTheme
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DripRacePhase3Test {

  @get:Rule val composeTestRule = createComposeRule()

  private fun createIsolatedTestViewModel():
    Triple<DripRaceViewModel, DripRacePhase1Test.FakeRendererPort, BestScoreRepository> {
    val app = ApplicationProvider.getApplicationContext<Application>()
    app.getSharedPreferences("drip_race_phase3_scores", Context.MODE_PRIVATE).edit().clear().commit()
    val vm = DripRaceViewModel(app, SavedStateHandle())
    val fakeRenderer = DripRacePhase1Test.FakeRendererPort()
    val fakeRepo =
      object : StarterAssetRepository(app) {
        override fun loadBundledStarter() =
          com.example.assets.StarterLoadResult.Ready(
            glbBytes = ProceduralRoadGlbBuilder.buildRoadGlb(),
            sha256Hex = StarterAssetContract.EXPECTED_STARTER_SHA256,
            sourceLabel = "test-bundled",
            isBundledOfflineAsset = true,
            asphaltMaps = BundledAsphaltMaps(null, null, null, null, null, null),
            hdrAssetPath = null,
            prepareVehicleMjsPath = null,
          )
      }
    val bestScoreRepo = BestScoreRepository(app)
    vm.overrideDependenciesForTest(
      repository = fakeRepo,
      renderer = fakeRenderer,
      trafficRepository = TrafficAssetRepository(app),
      scoreRepository = bestScoreRepo,
    )
    vm.onActivityStartedOrResumed(active = true)
    return Triple(vm, fakeRenderer, bestScoreRepo)
  }

  private fun staticHazardCandidate(kind: StaticHazardKind, lane: Int, relZ: Double) =
    EncounterCandidate(
      laneIndex = lane,
      relativeZ = relZ,
      widthMeters = kind.widthMeters,
      heightMeters = kind.heightMeters,
      lengthMeters = kind.lengthMeters,
      speedMetersPerSecond = 0.0,
      isStaticHazard = true,
      hazardKind = kind,
    )

  private fun parseGlbMinMaxBounds(glbBytes: ByteArray): Pair<FloatArray, FloatArray> {
    val bb = ByteBuffer.wrap(glbBytes).order(ByteOrder.LITTLE_ENDIAN)
    bb.position(12)
    val jsonLen = bb.int
    bb.int // JSON chunk type
    val jsonBytes = ByteArray(jsonLen)
    bb.get(jsonBytes)
    val root = org.json.JSONObject(String(jsonBytes, Charsets.UTF_8))
    val accessors = root.getJSONArray("accessors")
    val gMin = floatArrayOf(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
    val gMax = floatArrayOf(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY)
    for (i in 0 until accessors.length()) {
      val acc = accessors.getJSONObject(i)
      val minArr = acc.optJSONArray("min") ?: continue
      val maxArr = acc.optJSONArray("max") ?: continue
      for (k in 0..2) {
        gMin[k] = minOf(gMin[k], minArr.getDouble(k).toFloat())
        gMax[k] = maxOf(gMax[k], maxArr.getDouble(k).toFloat())
      }
    }
    return gMin to gMax
  }

  @Test
  fun `catalog preserves all 6 categories, all 9 vehicles including moving Olpen, and 1_1 static hazard bounds`() {
    assertEquals(9, TrafficCatalog.APPROVED_TRAFFIC_VEHICLES.size)
    assertEquals(TrafficCategory.entries.toSet(), TrafficCatalog.APPROVED_TRAFFIC_VEHICLES.map { it.category }.toSet())

    val olpen = TrafficCatalog.findById("post-apocalypse__derby_car_-_olpen_aster")!!
    assertTrue("Olpen must explicitly be moving traffic", olpen.isExplicitlyMoving)
    assertTrue(olpen.minCruiseSpeedMps > 15.0)
    assertEquals("9151e4e2cd52c1dc350fdfd4e49c2b8fae68b17074808adf37dd8f1eadbe22a8", olpen.sourceSha256)

    // Verify all 4 static hazards build valid GLBs whose visual bounding boxes match collision 1:1
    for (kind in StaticHazardKind.entries) {
      val glb = ProceduralTrafficGlbBuilder.buildStaticHazardGlb(kind)
      assertTrue(StarterAssetRepository.hasValidGlbHeader(glb))
      val (gMin, gMax) = parseGlbMinMaxBounds(glb)
      assertEquals(kind.widthMeters, gMax[0] - gMin[0], 1e-4f)
      assertEquals(kind.heightMeters, gMax[1] - gMin[1], 1e-4f)
      assertEquals(kind.lengthMeters, gMax[2] - gMin[2], 1e-4f)
      assertEquals(0f, gMin[1], 1e-4f)
      assertEquals(0f, (gMin[0] + gMax[0]) * 0.5f, 1e-4f)
      assertEquals(0f, (gMin[2] + gMax[2]) * 0.5f, 1e-4f)
    }

    // Verify yaw-then-scale helper correctly aligns +-90 degree trucks/buses to targetLengthMeters
    val preYawBusBounds = VehicleBounds(minX = -5.75f, minY = 0f, minZ = -1.24f, maxX = 5.75f, maxY = 3.10f, maxZ = 1.24f)
    val acc = StarterVehicleNormalizer.BoundsAccumulator()
    val rotMat = FloatArray(16).also {
      android.opengl.Matrix.setIdentityM(it, 0)
      android.opengl.Matrix.rotateM(it, 0, 90f, 0f, 1f, 0f)
    }
    acc.includeTransformedBox(0f, 1.55f, 0f, 5.75f, 1.55f, 1.24f, rotMat)
    val postYaw = acc.toVehicleBoundsOrNull()!!
    val plan = StarterVehicleNormalizer.computeYawAlignedNormalizationPlan(postYaw, 11.5f, 90f)
    val pMin = FloatArray(4)
    val pMax = FloatArray(4)
    android.opengl.Matrix.multiplyMV(pMin, 0, plan.matrixColumnMajor, 0, floatArrayOf(preYawBusBounds.minX, preYawBusBounds.minY, preYawBusBounds.minZ, 1f), 0)
    android.opengl.Matrix.multiplyMV(pMax, 0, plan.matrixColumnMajor, 0, floatArrayOf(preYawBusBounds.maxX, preYawBusBounds.maxY, preYawBusBounds.maxZ, 1f), 0)
    assertEquals(11.5f, abs(pMax[2] - pMin[2]), 1e-3f)
  }

  @Test
  fun `relative movement uses player minus traffic speed and Olpen drives forward cleanly`() {
    val sim = DrivingSimulation(enableTraffic = false)
    // Bring player quickly up to 50 m/s
    repeat(600) { sim.stepFixedTick(DrivingInputSnapshot()) }
    val olpen = TrafficCatalog.findById("post-apocalypse__derby_car_-_olpen_aster")!!

    val olpenId =
      sim.traffic.trySpawnCandidate(
        candidate =
          EncounterCandidate(
            laneIndex = 0,
            relativeZ = 120.0,
            widthMeters = olpen.collisionWidthMeters,
            heightMeters = olpen.collisionHeightMeters,
            lengthMeters = olpen.targetLengthMeters,
            speedMetersPerSecond = 25.0,
            isStaticHazard = false,
            trafficSpec = olpen,
          ),
        playerTargetLane = sim.state.targetLaneIndex,
        playerSpeedMps = sim.state.speedMetersPerSecond,
        vehicleConfig = sim.config,
        enforceReachability = false,
      )!!

    val barrierId =
      sim.traffic.trySpawnCandidate(
        candidate = staticHazardCandidate(StaticHazardKind.ROAD_BARRIER, lane = 2, relZ = 120.0),
        playerTargetLane = sim.state.targetLaneIndex,
        playerSpeedMps = sim.state.speedMetersPerSecond,
        vehicleConfig = sim.config,
        enforceReachability = false,
      )!!

    // Step exactly 1.0 second (60 ticks) at 50 m/s:
    // Olpen at 25 m/s -> deltaZ = -(50 - 25)*1 = -25m => 95.0 m
    // Static barrier at 0 m/s -> deltaZ = -(50 - 0)*1 = -50m => 70.0 m
    repeat(60) { sim.stepFixedTick(DrivingInputSnapshot()) }

    val active = sim.state.activeObstacles.associateBy { it.logicalId }
    assertEquals(95.0, active[olpenId]!!.relativeZ, 1e-4)
    assertTrue("Olpen wheels must roll while moving forward", active[olpenId]!!.wheelRollRadians > 0.0)
    assertEquals(70.0, active[barrierId]!!.relativeZ, 1e-4)
    assertEquals(0.0, active[barrierId]!!.wheelRollRadians, 1e-9)
  }

  @Test
  fun `bounded pool recycles slots with fresh monotonic logicalIds and cleared per-instance flags`() {
    val sim = DrivingSimulation(enableTraffic = false)
    repeat(600) { sim.stepFixedTick(DrivingInputSnapshot()) } // 50 m/s

    val seenLogicalIds = mutableListOf<Long>()
    // Fill all MAX_POOL_SIZE slots at lane 2 (safe to pass from lane 1) and let them recycle behind camera
    for (wave in 1..20) {
      val id =
        sim.traffic.trySpawnCandidate(
          candidate = staticHazardCandidate(StaticHazardKind.CONCRETE_BLOCK, lane = 2, relZ = 20.0),
          playerTargetLane = sim.state.targetLaneIndex,
          playerSpeedMps = sim.state.speedMetersPerSecond,
          vehicleConfig = sim.config,
          enforceReachability = false,
        )!!
      seenLogicalIds += id
      assertTrue(sim.traffic.activeCount() <= TrafficSimulation.MAX_POOL_SIZE)
      // Advance 1.1s at 50 m/s -> moves -55m -> -35m < RECYCLE_BEHIND_Z (-26m), cleanly passing & recycling
      repeat(66) { sim.stepFixedTick(DrivingInputSnapshot()) }
      assertEquals(0, sim.traffic.activeCount())
    }

    // All 20 logical IDs must be strictly unique & monotonically increasing across slot reuse
    assertEquals(20, seenLogicalIds.distinct().size)
    for (i in 1 until seenLogicalIds.size) {
      assertTrue(seenLogicalIds[i] > seenLogicalIds[i - 1])
    }
    // Near-miss gap check in lane 2 vs player center lane 1:
    // lane gap = 3.6 - (0.94 + 1.05) = 1.61m > 1.15m => not near-miss, static hazard => 0 overtakes, hearts still 3
    assertEquals(TrafficSimulation.MAX_HEARTS, sim.state.hearts)
    assertEquals(0, sim.state.validUniqueOvertakes)
  }

  @Test
  fun `fair encounter planner rejects 3-lane blockages, same-lane overlaps, and Nitro pinch traps`() {
    val sim = DrivingSimulation(enableTraffic = false)
    repeat(600) { sim.stepFixedTick(DrivingInputSnapshot()) } // 50 m/s in lane 1

    // Place lane 0 and lane 2 barriers at 130 m
    assertNotNull(
      sim.traffic.trySpawnCandidate(
        staticHazardCandidate(StaticHazardKind.ROAD_BARRIER, lane = 0, relZ = 130.0),
        playerTargetLane = 1,
        playerSpeedMps = 50.0,
        vehicleConfig = sim.config,
        enforceReachability = true,
      )
    )
    assertNotNull(
      sim.traffic.trySpawnCandidate(
        staticHazardCandidate(StaticHazardKind.ROAD_BARRIER, lane = 2, relZ = 130.0),
        playerTargetLane = 1,
        playerSpeedMps = 50.0,
        vehicleConfig = sim.config,
        enforceReachability = true,
      )
    )

    // Attempt to plug remaining middle lane 1 at 132 m -> 3-lane wall MUST be rejected!
    val threeLaneWallRejected =
      sim.traffic.trySpawnCandidate(
        staticHazardCandidate(StaticHazardKind.ROAD_BARRIER, lane = 1, relZ = 132.0),
        playerTargetLane = 1,
        playerSpeedMps = 50.0,
        vehicleConfig = sim.config,
        enforceReachability = true,
      )
    assertNull("3-lane wall must be rejected by reachability planner", threeLaneWallRejected)

    // Attempt same-lane tight overlap behind lane 0 barrier -> MUST be rejected!
    val sameLaneOverlapRejected =
      sim.traffic.trySpawnCandidate(
        staticHazardCandidate(StaticHazardKind.CONCRETE_BLOCK, lane = 0, relZ = 138.0),
        playerTargetLane = 1,
        playerSpeedMps = 50.0,
        vehicleConfig = sim.config,
        enforceReachability = true,
      )
    assertNull("Same-lane close overlap must be rejected", sameLaneOverlapRejected)

    // Seeded long auto-spawn run across all stages verifies pool stays bounded and 0 unavoidable 3-lane blocks happen
    val autoSim = DrivingSimulation(enableTraffic = true, trafficSeed = 98765L)
    repeat(1500) { // 25 seconds of highway driving
      autoSim.stepFixedTick(DrivingInputSnapshot())
      val obstacles = autoSim.state.activeObstacles
      assertTrue(obstacles.size <= TrafficSimulation.MAX_POOL_SIZE)
      // Verify no 3 lanes simultaneously overlap within 12m Z window
      for (obs in obstacles) {
        val blockedLanes = obstacles.filter { abs(it.relativeZ - obs.relativeZ) < 6.0 }.map { it.laneIndex }.toSet()
        assertTrue("No simultaneous 3-lane blockade allowed", blockedLanes.size < 3)
      }
    }
  }

  @Test
  fun `swept collision detection catches high-speed tunnelling and diagonal lane-change hits`() {
    // 1. High-speed anti-tunnelling: 0.85m thin road barrier traversed completely inside a single step at 62.5 m/s (225 km/h) + slow delta
    val hitTunnelPrevented =
      CollisionAndReachabilityMath.intersectsSwept2d(
        prevPlayerX = 0f,
        nextPlayerX = 0f,
        playerHalfW = TrafficCatalog.PLAYER_WIDTH_METERS * 0.5f,
        playerHalfZ = TrafficCatalog.PLAYER_LENGTH_METERS * 0.5,
        obsX = 0f,
        obsHalfW = StaticHazardKind.ROAD_BARRIER.widthMeters * 0.5f,
        prevObsRelZ = 4.0,
        nextObsRelZ = -4.0, // jumped 8 meters across player center 0 in 1 step!
        obsHalfZ = StaticHazardKind.ROAD_BARRIER.lengthMeters * 0.5,
      )
    assertTrue("Swept AABB must catch barrier passed through in one step", hitTunnelPrevented)

    // 2. Diagonal lane-change sweep: player starts safely left (X=-3.6), sweeps to center (X=0.0)
    //    Wait: even more subtly, sweeping from Lane 0 (-3.6) to Lane 2 (+3.6) across Lane 1 obstacle (X=0.0)
    val midLaneSweepHit =
      CollisionAndReachabilityMath.intersectsSwept2d(
        prevPlayerX = -3.6f,
        nextPlayerX = 3.6f,
        playerHalfW = 0.94f,
        playerHalfZ = 2.18,
        obsX = 0f,
        obsHalfW = 1.0f,
        prevObsRelZ = 1.5,
        nextObsRelZ = -1.5,
        obsHalfZ = 1.0,
      )
    assertTrue("Swept AABB must detect lateral pass through Lane 1 obstacle mid-sweep", midLaneSweepHit)

    // 3. Actual DrivingSimulation lane change into an obstacle triggers impact reliably
    val sim = DrivingSimulation(enableTraffic = false)
    repeat(300) { sim.stepFixedTick(DrivingInputSnapshot()) } // ~27.5 m/s
    sim.traffic.trySpawnCandidate(
      staticHazardCandidate(StaticHazardKind.WORK_BARRICADE, lane = 0, relZ = 10.0),
      playerTargetLane = 1,
      playerSpeedMps = sim.state.speedMetersPerSecond,
      vehicleConfig = sim.config,
      enforceReachability = false,
    )
    sim.requestLaneChange(-1) // Move from lane 1 into lane 0 while approaching barricade
    repeat(30) { sim.stepFixedTick(DrivingInputSnapshot()) }
    assertEquals(2, sim.state.hearts)
  }

  @Test
  fun `three separate hits reach exactly-once Game Over while 1s grace protects continuous overlap`() {
    val sim = DrivingSimulation(enableTraffic = false)
    repeat(300) { sim.stepFixedTick(DrivingInputSnapshot()) }

    // Continuous overlap test: long bus overlapping player for ~0.4s -> ONLY 1 heart removed + ~1.0s grace
    val bus = TrafficCatalog.findById("generic-town-bus")!!
    sim.traffic.trySpawnCandidate(
      EncounterCandidate(
        laneIndex = 1,
        relativeZ = 6.0,
        widthMeters = bus.collisionWidthMeters,
        heightMeters = bus.collisionHeightMeters,
        lengthMeters = bus.targetLengthMeters,
        speedMetersPerSecond = 14.0,
        isStaticHazard = false,
        trafficSpec = bus,
      ),
      playerTargetLane = 1,
      playerSpeedMps = sim.state.speedMetersPerSecond,
      vehicleConfig = sim.config,
      enforceReachability = false,
    )
    // Also queue another obstacle 1.5m behind inside the 1.0s grace window (must deal 0 damage!)
    sim.traffic.trySpawnCandidate(
      staticHazardCandidate(StaticHazardKind.ROAD_BARRIER, lane = 1, relZ = 14.0),
      playerTargetLane = 1,
      playerSpeedMps = sim.state.speedMetersPerSecond,
      vehicleConfig = sim.config,
      enforceReachability = false,
    )

    repeat(50) { sim.stepFixedTick(DrivingInputSnapshot()) } // < 0.9s elapsed
    assertEquals("Continuous contact + grace window must only remove 1 heart", 2, sim.state.hearts)
    assertTrue(sim.state.collisionGraceRemainingSeconds > 0.0)
    assertFalse(sim.state.isGameOver)

    // Let grace expire cleanly (> 1.0s)
    repeat(45) { sim.stepFixedTick(DrivingInputSnapshot()) }
    assertEquals(0.0, sim.state.collisionGraceRemainingSeconds, 1e-6)

    // Second damaging hit -> 1 heart
    sim.traffic.trySpawnCandidate(
      staticHazardCandidate(StaticHazardKind.CONCRETE_BLOCK, lane = 1, relZ = 4.0),
      playerTargetLane = 1,
      playerSpeedMps = sim.state.speedMetersPerSecond,
      vehicleConfig = sim.config,
      enforceReachability = false,
    )
    repeat(30) { sim.stepFixedTick(DrivingInputSnapshot()) }
    assertEquals(1, sim.state.hearts)
    assertFalse(sim.state.isGameOver)

    // Wait > 1.0s grace then third damaging hit -> 0 hearts & Game Over
    repeat(65) { sim.stepFixedTick(DrivingInputSnapshot()) }
    sim.traffic.trySpawnCandidate(
      staticHazardCandidate(StaticHazardKind.CONCRETE_BLOCK, lane = 1, relZ = 4.0),
      playerTargetLane = 1,
      playerSpeedMps = sim.state.speedMetersPerSecond,
      vehicleConfig = sim.config,
      enforceReachability = false,
    )
    repeat(30) { sim.stepFixedTick(DrivingInputSnapshot()) }
    assertEquals(0, sim.state.hearts)
    assertTrue(sim.state.isGameOver)

    // Further fixed ticks after Game Over must be frozen cleanly
    val stepsAtGameOver = sim.state.fixedStepsExecuted
    val scoreAtGameOver = sim.state.score
    repeat(60) { sim.stepFixedTick(DrivingInputSnapshot(nitroHeld = true)) }
    assertEquals(stepsAtGameOver, sim.state.fixedStepsExecuted)
    assertEquals(scoreAtGameOver, sim.state.score)
  }

  @Test
  fun `tested Phase 4 shield hook blocks one impact cleanly without heart loss`() {
    val sim = DrivingSimulation(enableTraffic = false)
    repeat(300) { sim.stepFixedTick(DrivingInputSnapshot()) }

    sim.grantShieldCharge(1)
    assertEquals(1, sim.state.shieldCharges)

    sim.traffic.trySpawnCandidate(
      staticHazardCandidate(StaticHazardKind.BROKEN_DOWN_CAR, lane = 1, relZ = 4.5),
      playerTargetLane = 1,
      playerSpeedMps = sim.state.speedMetersPerSecond,
      vehicleConfig = sim.config,
      enforceReachability = false,
    )
    repeat(20) { sim.stepFixedTick(DrivingInputSnapshot()) }

    assertEquals("Shield must absorb impact with zero heart loss", 3, sim.state.hearts)
    assertEquals(0, sim.state.shieldCharges)
    assertEquals(1, sim.state.shieldBlocksCount)
    assertTrue("Shield absorption grants ~1s grace window", sim.state.collisionGraceRemainingSeconds > 0.5)

    // After grace expires, next unprotected impact removes 1 heart normally
    repeat(70) { sim.stepFixedTick(DrivingInputSnapshot()) }
    sim.traffic.trySpawnCandidate(
      staticHazardCandidate(StaticHazardKind.ROAD_BARRIER, lane = 1, relZ = 4.5),
      playerTargetLane = 1,
      playerSpeedMps = sim.state.speedMetersPerSecond,
      vehicleConfig = sim.config,
      enforceReachability = false,
    )
    repeat(20) { sim.stepFixedTick(DrivingInputSnapshot()) }
    assertEquals(2, sim.state.hearts)
  }

  @Test
  fun `unique overtakes, close near-misses, exact contract scoring formula, and best score persistence`() {
    val sim = DrivingSimulation(enableTraffic = false)
    repeat(600) { sim.stepFixedTick(DrivingInputSnapshot()) } // 50 m/s

    val sedan = TrafficCatalog.findById("generic-sedan-mmcworks")!!
    // Lane 0 sedan while we briefly steer right toward center/left edge OR close dodge from lane 0 to lane 1
    sim.requestLaneChange(-1)
    repeat(30) { sim.stepFixedTick(DrivingInputSnapshot()) } // Now in lane 0

    // Spawn moving sedan ahead in lane 0 at 14.5 m -> close dodge out to lane 1 triggers valid Near Miss + Overtake!
    sim.traffic.trySpawnCandidate(
      EncounterCandidate(
        laneIndex = 0,
        relativeZ = 14.5,
        widthMeters = sedan.collisionWidthMeters,
        heightMeters = sedan.collisionHeightMeters,
        lengthMeters = sedan.targetLengthMeters,
        speedMetersPerSecond = 25.0,
        isStaticHazard = false,
        trafficSpec = sedan,
      ),
      playerTargetLane = 0,
      playerSpeedMps = sim.state.speedMetersPerSecond,
      vehicleConfig = sim.config,
      enforceReachability = false,
    )
    sim.requestLaneChange(+1) // Dodge from lane 0 -> lane 1 while overtaking sedan!

    // Advance 2.5s so sedan clears behind -Z and exits pool without repeated bonus counting
    repeat(150) { sim.stepFixedTick(DrivingInputSnapshot()) }

    assertEquals(3, sim.state.hearts)
    assertEquals(1, sim.state.validUniqueOvertakes)
    assertEquals(1, sim.state.validUniqueNearMisses)
    val expectedScore =
      floor(sim.state.distanceMeters).toInt() + 30 * 1 + 50 * 1
    assertEquals(expectedScore, sim.state.score)

    // BestScoreRepository persists strictly when exceeded
    val app = ApplicationProvider.getApplicationContext<Application>()
    app.getSharedPreferences("drip_race_phase3_scores", Context.MODE_PRIVATE).edit().clear().commit()
    val repo = BestScoreRepository(app)
    assertEquals(0, repo.loadBestScore())

    val (best1, new1) = repo.recordRunScore(expectedScore)
    assertEquals(expectedScore, best1)
    assertTrue(new1)

    val (best2, new2) = repo.recordRunScore(expectedScore - 50)
    assertEquals(expectedScore, best2)
    assertFalse(new2)

    val (best3, new3) = repo.recordRunScore(expectedScore + 250)
    assertEquals(expectedScore + 250, best3)
    assertTrue(new3)
  }

  @Test
  fun `five distance difficulty stages stay bounded and scale smoothly`() {
    val d0 = DifficultyDirector.stageForDistance(0.0)
    val d1 = DifficultyDirector.stageForDistance(499.9)
    val d2 = DifficultyDirector.stageForDistance(500.0)
    val d3 = DifficultyDirector.stageForDistance(1500.0)
    val d4 = DifficultyDirector.stageForDistance(3000.0)
    val d5 = DifficultyDirector.stageForDistance(6500.0)

    assertEquals(1, d0.stageNumber)
    assertEquals(1, d1.stageNumber)
    assertEquals(2, d2.stageNumber)
    assertEquals(3, d3.stageNumber)
    assertEquals(4, d4.stageNumber)
    assertEquals(5, d5.stageNumber)

    val stages = DifficultyDirector.STAGES
    assertEquals(5, stages.size)
    for (i in 1 until stages.size) {
      assertTrue(stages[i].spawnIntervalSeconds <= stages[i - 1].spawnIntervalSeconds)
      assertTrue(stages[i].maxActiveEntities >= stages[i - 1].maxActiveEntities)
      assertTrue(stages[i].maxActiveEntities <= TrafficSimulation.MAX_POOL_SIZE)
      assertTrue(stages[i].minReactionTimeSeconds >= 0.90)
    }
  }

  @Test
  fun `pause freezes traffic grace difficulty and score while Restart clears state and 30vs60 FPS remain identical`() {
    val (vm, fakeRenderer, _) = createIsolatedTestViewModel()
    vm.onPlayClicked()

    // Drive for 4s
    repeat(240) { fakeRenderer.simulateRenderFrame(1.0 / 60.0) }
    vm.onPauseClicked()
    assertTrue(vm.uiState.value.phase is GamePhase.Paused)
    val scorePaused = vm.simulation.state.score
    val heartsPaused = vm.simulation.state.hearts
    val obstaclesPaused = vm.simulation.state.activeObstacles.map { it.logicalId to it.relativeZ }

    repeat(120) { fakeRenderer.simulateRenderFrame(1.0 / 60.0) }
    assertEquals(scorePaused, vm.simulation.state.score)
    assertEquals(heartsPaused, vm.simulation.state.hearts)
    assertEquals(obstaclesPaused, vm.simulation.state.activeObstacles.map { it.logicalId to it.relativeZ })

    // Restart clears all traffic, damage, and score without duplicating renderer loads
    vm.onRestartClicked()
    assertTrue(vm.uiState.value.phase is GamePhase.Running)
    assertEquals(3, vm.simulation.state.hearts)
    assertEquals(0, vm.simulation.state.score)
    assertEquals(0, vm.simulation.state.validUniqueOvertakes)
    assertEquals(0, vm.simulation.state.validUniqueNearMisses)
    assertEquals(0, vm.simulation.traffic.activeCount())
    assertEquals(1, fakeRenderer.loadCount)

    // 30 FPS vs 60 FPS parity with seeded auto-spawn traffic enabled
    val sim60 = DrivingSimulation(enableTraffic = true, trafficSeed = 4242L)
    val sim30 = DrivingSimulation(enableTraffic = true, trafficSeed = 4242L)
    repeat(600) { f ->
      if (f == 180) sim60.requestLaneChange(-1)
      sim60.advanceRenderFrame(1.0 / 60.0, DrivingInputSnapshot(nitroHeld = f in 200 until 260))
    }
    repeat(300) { f ->
      if (f == 90) sim30.requestLaneChange(-1)
      sim30.advanceRenderFrame(1.0 / 30.0, DrivingInputSnapshot(nitroHeld = f in 100 until 130))
    }
    assertEquals(sim60.state.fixedStepsExecuted, sim30.state.fixedStepsExecuted)
    assertEquals(sim60.state.distanceMeters, sim30.state.distanceMeters, 1e-9)
    assertEquals(sim60.state.score, sim30.state.score)
    assertEquals(sim60.state.hearts, sim30.state.hearts)
    assertEquals(sim60.state.validUniqueOvertakes, sim30.state.validUniqueOvertakes)
    assertEquals(sim60.state.validUniqueNearMisses, sim30.state.validUniqueNearMisses)
  }

  @Test
  fun `on-device sedan GLB import validates header and SHA-256 while reporting missing files honestly`() {
    val app = ApplicationProvider.getApplicationContext<Application>()
    File(app.filesDir, TrafficAssetRepository.IMPORT_DIR_NAME).deleteRecursively()

    val repo = TrafficAssetRepository(app)
    val (_, reportBefore) = repo.loadAllTrafficAssets()
    assertEquals(5, reportBefore.bundledCount)
    assertEquals(0, reportBefore.onDeviceImportedCount)
    assertEquals(4, reportBefore.fallbackCount)
    assertTrue(reportBefore.sedanOnDevicePending)
    // generic-sedan-mmcworks.glb must NOT appear in AI-requested missing permitted list (on-device import only)
    assertFalse(reportBefore.missingPermittedFilenames.contains("generic-sedan-mmcworks.glb"))
    assertEquals(
      listOf(
        "old_car.glb",
        "post-apocalypse__derby_car_-_olpen_aster.glb",
        "post-apocalypse__derby_car_toytyta_doubtlux.glb",
      ),
      reportBefore.missingPermittedFilenames,
    )

    // Invalid GLB or wrong hash is rejected cleanly without replacing fallback
    val badFile = File(app.cacheDir, "wrong-sedan.glb")
    badFile.writeBytes(ProceduralRoadGlbBuilder.buildRoadGlb())
    val rejected = repo.importTrafficGlbFromUri("generic-sedan-mmcworks", Uri.fromFile(badFile))
    assertTrue(rejected is TrafficImportResult.Rejected)
    val rejectedAuto = repo.importAnyTrafficGlbFromUri(Uri.fromFile(badFile))
    assertTrue(rejectedAuto is TrafficImportResult.Rejected)

    // Verify valid hash acceptance check
    val sedanSpec = TrafficCatalog.findById("generic-sedan-mmcworks")!!
    val roadBytes = ProceduralRoadGlbBuilder.buildRoadGlb()
    val roadHash = StarterAssetRepository.sha256Hex(roadBytes)
    assertFalse(roadHash.equals(sedanSpec.sourceSha256, ignoreCase = true))
    val sedanState = reportBefore.states.first { it.id == "generic-sedan-mmcworks" }
    assertEquals(TrafficModelOrigin.PROCEDURAL_FALLBACK_PENDING_IMPORT, sedanState.origin)
    assertTrue(sedanState.onDeviceImportOnly)

    val (vm, _, _) = createIsolatedTestViewModel()
    vm.importMultipleTrafficGlbs(emptyList())
    val badFile2 = File(app.cacheDir, "wrong-derby.glb")
    badFile2.writeBytes(ProceduralRoadGlbBuilder.buildRoadGlb())
    vm.importMultipleTrafficGlbs(listOf(Uri.fromFile(badFile), Uri.fromFile(badFile2)))
    val notice = vm.uiState.value.diagnostics.lastTrafficImportNotice ?: ""
    assertTrue(notice.contains("Rejected 2"))
  }

  @Test
  fun `game over UI flow displays final score, persists best score, and supports Play Again and Home`() {
    val (vm, fakeRenderer, _) = createIsolatedTestViewModel()

    composeTestRule.setContent {
      val uiState by vm.uiState.collectAsState()
      val telemetry by vm.hudTelemetry.collectAsState()
      DripRaceTheme {
        DripRaceRootScreen(
          uiState = uiState,
          telemetry = telemetry,
          onPlay = vm::onPlayClicked,
          onPlayAgain = vm::onPlayAgainClicked,
          onPause = vm::onPauseClicked,
          onResume = vm::onResumeClicked,
          onRestart = vm::onRestartClicked,
          onHome = vm::onHomeClicked,
          onRetry = vm::onRetryClicked,
          onPickGlbPreview = {},
          onImportSedanGlb = {},
          onBackFromRunning = { vm.onBackPressed() },
          onSteerLeft = vm::onSteerLeft,
          onSteerRight = vm::onSteerRight,
          onBrakePointerDown = vm::onBrakePointerDown,
          onBrakePointerUpOrCancel = vm::onBrakePointerUpOrCancel,
          onNitroPointerDown = vm::onNitroPointerDown,
          onNitroPointerUpOrCancel = vm::onNitroPointerUpOrCancel,
          onBrakeKeyChanged = vm::onBrakeKeyChanged,
          onNitroKeyChanged = vm::onNitroKeyChanged,
          onAttachSurface = {},
          onDetachSurface = {},
          render3DSurface = false,
        )
      }
    }

    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("home_best_score_text").assertIsDisplayed()
    composeTestRule.onNodeWithTag("import_sedan_glb_button").assertIsDisplayed()
    composeTestRule.onNodeWithTag("play_button").performClick()
    composeTestRule.waitForIdle()

    // HUD displays Hearts, Score, and Stage
    composeTestRule.onNodeWithTag("hud_hearts_row").assertIsDisplayed()
    composeTestRule.onNodeWithTag("hud_score_text").assertIsDisplayed()
    composeTestRule.onNodeWithTag("hud_stage_text").assertIsDisplayed()

    // Drive forward 5s then deal 3 separate damaging impacts -> Game Over
    repeat(300) { fakeRenderer.simulateRenderFrame(1.0 / 60.0) }
    repeat(3) { hitIndex ->
      if (hitIndex > 0) {
        repeat(70) { fakeRenderer.simulateRenderFrame(1.0 / 60.0) }
      }
      vm.simulation.traffic.trySpawnCandidate(
        staticHazardCandidate(StaticHazardKind.ROAD_BARRIER, lane = vm.simulation.state.targetLaneIndex, relZ = 4.0),
        playerTargetLane = vm.simulation.state.targetLaneIndex,
        playerSpeedMps = vm.simulation.state.speedMetersPerSecond,
        vehicleConfig = vm.simulation.config,
        enforceReachability = false,
      )
      repeat(20) { fakeRenderer.simulateRenderFrame(1.0 / 60.0) }
    }
    composeTestRule.waitForIdle()

    val phase = vm.uiState.value.phase
    assertTrue(phase is GamePhase.GameOver)
    val expectedFinalScore = (phase as GamePhase.GameOver).finalScore
    assertTrue(expectedFinalScore > 0)
    assertTrue(phase.isNewBest)

    composeTestRule.onNodeWithTag("game_over_title").assertIsDisplayed()
    composeTestRule.onNodeWithTag("game_over_score_text").assertTextContains("FINAL SCORE: $expectedFinalScore")
    composeTestRule.onNodeWithTag("game_over_best_score_text").assertTextContains("BEST SCORE: $expectedFinalScore")

    // Play Again resets cleanly and retains 1 renderer load
    composeTestRule.onNodeWithTag("play_again_button").performScrollTo().performClick()
    composeTestRule.waitForIdle()
    assertTrue(vm.uiState.value.phase is GamePhase.Running)
    assertEquals(3, vm.hudTelemetry.value.hearts)
    assertEquals(0, vm.hudTelemetry.value.score)
    assertEquals(1, fakeRenderer.loadCount)

    // Pause -> Home shows persisted best score on Home screen
    vm.onPauseClicked()
    vm.onHomeClicked()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("home_best_score_text").assertTextContains("BEST SCORE: $expectedFinalScore")
  }

  @Test
  fun `verified moving traffic filter spawns 100 percent genuine uploaded GLB models across race resets while preserving honest diagnostics`() {
    val app = ApplicationProvider.getApplicationContext<Application>()
    File(app.filesDir, TrafficAssetRepository.IMPORT_DIR_NAME).deleteRecursively()
    val repo = TrafficAssetRepository(app)
    val (loadedTraffic, report) = repo.loadAllTrafficAssets()

    // Honest diagnostics remain untouched: 5 bundled genuine GLBs, 4 pending fallback
    assertEquals(5, report.bundledCount)
    assertEquals(4, report.fallbackCount)

    val verifiedIds =
      loadedTraffic
        .filter { it.sha256Verified && it.glbBytes != null }
        .map { it.spec.id }
        .toSet()
    assertEquals(
      setOf(
        "mmc-generic-small-suv",
        "european-delivery-van",
        "conquer-89-pickup",
        "lct-3000-95",
        "generic-town-bus",
      ),
      verifiedIds,
    )

    val sim = DrivingSimulation(enableTraffic = true, trafficSeed = 20261116L)
    sim.setVerifiedMovingTrafficIds(verifiedIds)

    val seenMovingIds = mutableSetOf<String>()
    repeat(1200) {
      if (sim.state.shieldCharges == 0) sim.grantShieldCharge(1)
      sim.stepFixedTick(DrivingInputSnapshot())
      sim.state.activeObstacles
        .filter { !it.isStaticHazard }
        .forEach { obs ->
          assertTrue(
            "Moving traffic must only spawn genuine uploaded GLB models, got ${obs.archetypeId}",
            obs.archetypeId in verifiedIds,
          )
          seenMovingIds += obs.archetypeId
        }
    }
    assertEquals("All 5 uploaded 3D traffic vehicles should appear in highway rotation", verifiedIds, seenMovingIds)

    // Verify verifiedMovingTrafficIds persists across race restarts (Play Again / Restart)
    sim.resetForNewRace(seedOverride = 77777L)
    repeat(600) {
      if (sim.state.shieldCharges == 0) sim.grantShieldCharge(1)
      sim.stepFixedTick(DrivingInputSnapshot())
      sim.state.activeObstacles
        .filter { !it.isStaticHazard }
        .forEach { obs ->
          assertTrue(
            "Post-reset moving traffic must still only spawn genuine uploaded GLBs, got ${obs.archetypeId}",
            obs.archetypeId in verifiedIds,
          )
        }
    }
  }
}
