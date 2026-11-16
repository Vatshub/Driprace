package com.example.render

import com.example.traffic.ApprovedTrafficSpec
import com.example.traffic.StaticHazardKind
import com.example.traffic.TrafficCategory
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * Generates valid, shaded glTF 2.0 binary (.glb) hulls with exact [width, height, length] bounding
 * boxes matching the collision geometry 1:1 for:
 * 1. Static hazards (RoadBarrier, WorkBarricade, ConcreteBlock, BrokenDownCar).
 * 2. Dimensioned procedural visual fallbacks when an approved traffic GLB is not yet bundled or imported.
 *
 * Uses right-handed Y-up coordinates, grounded at Y = 0, centered on X = 0 and Z = 0, facing +Z.
 */
object ProceduralTrafficGlbBuilder {
  private data class BoxSpec(
    val minX: Float,
    val maxX: Float,
    val minY: Float,
    val maxY: Float,
    val minZ: Float,
    val maxZ: Float,
    val materialIndex: Int,
  )

  private data class MeshData(
    val name: String,
    val materialIndex: Int,
    val positions: FloatArray,
    val normals: FloatArray,
    val indices: ShortArray,
    val minPos: FloatArray,
    val maxPos: FloatArray,
  )

  private class BoxMeshBuilder {
    private val pos = ArrayList<Float>()
    private val nrm = ArrayList<Float>()
    private val idx = ArrayList<Short>()
    private var minX = Float.POSITIVE_INFINITY
    private var minY = Float.POSITIVE_INFINITY
    private var minZ = Float.POSITIVE_INFINITY
    private var maxX = Float.NEGATIVE_INFINITY
    private var maxY = Float.NEGATIVE_INFINITY
    private var maxZ = Float.NEGATIVE_INFINITY

    fun addBox(x0: Float, x1: Float, y0: Float, y1: Float, z0: Float, z1: Float) {
      // Top (+Y)
      addQuad(x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0, 0f, 1f, 0f)
      // Bottom (-Y)
      addQuad(x0, y0, z1, x0, y0, z0, x1, y0, z0, x1, y0, z1, 0f, -1f, 0f)
      // Front (+Z)
      addQuad(x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, 0f, 0f, 1f)
      // Back (-Z)
      addQuad(x1, y0, z0, x0, y0, z0, x0, y1, z0, x1, y1, z0, 0f, 0f, -1f)
      // Left (+X)
      addQuad(x1, y0, z1, x1, y0, z0, x1, y1, z0, x1, y1, z1, 1f, 0f, 0f)
      // Right (-X)
      addQuad(x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0, -1f, 0f, 0f)
    }

    private fun addQuad(
      x0: Float, y0: Float, z0: Float,
      x1: Float, y1: Float, z1: Float,
      x2: Float, y2: Float, z2: Float,
      x3: Float, y3: Float, z3: Float,
      nx: Float, ny: Float, nz: Float,
    ) {
      val base = pos.size / 3
      addVertex(x0, y0, z0, nx, ny, nz)
      addVertex(x1, y1, z1, nx, ny, nz)
      addVertex(x2, y2, z2, nx, ny, nz)
      addVertex(x3, y3, z3, nx, ny, nz)
      idx.add(base.toShort())
      idx.add((base + 1).toShort())
      idx.add((base + 2).toShort())
      idx.add(base.toShort())
      idx.add((base + 2).toShort())
      idx.add((base + 3).toShort())
    }

    private fun addVertex(x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float) {
      pos.add(x)
      pos.add(y)
      pos.add(z)
      nrm.add(nx)
      nrm.add(ny)
      nrm.add(nz)
      if (x < minX) minX = x
      if (y < minY) minY = y
      if (z < minZ) minZ = z
      if (x > maxX) maxX = x
      if (y > maxY) maxY = y
      if (z > maxZ) maxZ = z
    }

    fun build(name: String, materialIndex: Int): MeshData =
      MeshData(
        name = name,
        materialIndex = materialIndex,
        positions = pos.toFloatArray(),
        normals = nrm.toFloatArray(),
        indices = idx.toShortArray(),
        minPos = floatArrayOf(minX, minY, minZ),
        maxPos = floatArrayOf(maxX, maxY, maxZ),
      )
  }

  fun buildTrafficFallbackGlb(spec: ApprovedTrafficSpec): ByteArray {
    val hw = spec.collisionWidthMeters * 0.5f
    val h = spec.collisionHeightMeters
    val hl = spec.targetLengthMeters * 0.5f

    val paintRgb =
      when (spec.category) {
        TrafficCategory.SEDAN -> floatArrayOf(0.56f, 0.62f, 0.70f, 1.0f)
        TrafficCategory.HATCHBACK -> floatArrayOf(0.84f, 0.24f, 0.20f, 1.0f)
        TrafficCategory.SUV -> floatArrayOf(0.18f, 0.42f, 0.36f, 1.0f)
        TrafficCategory.SPORTS_CAR -> floatArrayOf(0.92f, 0.64f, 0.12f, 1.0f)
        TrafficCategory.TRUCK -> floatArrayOf(0.76f, 0.78f, 0.80f, 1.0f)
        TrafficCategory.BUS -> floatArrayOf(0.14f, 0.44f, 0.78f, 1.0f)
      }

    val boxes = mutableListOf<BoxSpec>()
    // 4 Wheel silhouettes touching ground Y=0 and full width boundary
    val wheelR = 0.34f
    val wheelW = 0.26f
    val frontZ = hl * 0.62f
    val rearZ = -hl * 0.64f
    for (wz in listOf(frontZ, rearZ)) {
      boxes += BoxSpec(hw - wheelW, hw, 0f, wheelR * 2f, wz - wheelR, wz + wheelR, 2)
      boxes += BoxSpec(-hw, -hw + wheelW, 0f, wheelR * 2f, wz - wheelR, wz + wheelR, 2)
    }

    when (spec.category) {
      TrafficCategory.BUS -> {
        boxes += BoxSpec(-hw, hw, 0.28f, h, -hl, hl, 0)
        boxes += BoxSpec(-hw * 0.94f, hw * 0.94f, h * 0.46f, h * 0.84f, -hl * 0.96f, hl, 1)
      }
      TrafficCategory.TRUCK -> {
        // Cab forward + cargo box rear
        boxes += BoxSpec(-hw, hw, 0.28f, h * 0.82f, hl * 0.22f, hl, 0)
        boxes += BoxSpec(-hw * 0.98f, hw * 0.98f, 0.36f, h, -hl, hl * 0.20f, 0)
        boxes += BoxSpec(-hw * 0.90f, hw * 0.90f, h * 0.50f, h * 0.76f, hl * 0.55f, hl, 1)
      }
      TrafficCategory.SUV -> {
        boxes += BoxSpec(-hw, hw, 0.24f, h * 0.56f, -hl, hl, 0)
        boxes += BoxSpec(-hw * 0.88f, hw * 0.88f, h * 0.54f, h, -hl * 0.82f, hl * 0.44f, 1)
      }
      TrafficCategory.HATCHBACK -> {
        boxes += BoxSpec(-hw, hw, 0.20f, h * 0.54f, -hl, hl, 0)
        boxes += BoxSpec(-hw * 0.86f, hw * 0.86f, h * 0.52f, h, -hl * 0.86f, hl * 0.34f, 1)
      }
      TrafficCategory.SPORTS_CAR -> {
        boxes += BoxSpec(-hw, hw, 0.16f, h * 0.52f, -hl, hl, 0)
        boxes += BoxSpec(-hw * 0.82f, hw * 0.82f, h * 0.50f, h, -hl * 0.55f, hl * 0.28f, 1)
      }
      TrafficCategory.SEDAN -> {
        boxes += BoxSpec(-hw, hw, 0.20f, h * 0.54f, -hl, hl, 0)
        boxes += BoxSpec(-hw * 0.86f, hw * 0.86f, h * 0.52f, h, -hl * 0.58f, hl * 0.36f, 1)
      }
    }

    // Rear brake light bar strictly flush within -hl (material 3 = red emissive)
    boxes += BoxSpec(-hw * 0.78f, hw * 0.78f, h * 0.36f, h * 0.46f, -hl, -hl + 0.04f, 3)

    val materialsJson =
      """
      {"name": "TrafficBody", "pbrMetallicRoughness": {"baseColorFactor": [${paintRgb[0]}, ${paintRgb[1]}, ${paintRgb[2]}, 1.0], "metallicFactor": 0.20, "roughnessFactor": 0.30}},
      {"name": "TrafficCabinGlass", "pbrMetallicRoughness": {"baseColorFactor": [0.10, 0.14, 0.20, 1.0], "metallicFactor": 0.12, "roughnessFactor": 0.18}},
      {"name": "TrafficRubber", "pbrMetallicRoughness": {"baseColorFactor": [0.06, 0.06, 0.07, 1.0], "metallicFactor": 0.0, "roughnessFactor": 0.88}},
      {"name": "TrafficTaillight", "pbrMetallicRoughness": {"baseColorFactor": [0.95, 0.10, 0.08, 1.0], "metallicFactor": 0.0, "roughnessFactor": 0.30}, "emissiveFactor": [0.9, 0.05, 0.03]}
      """.trimIndent().replace("\n", " ")

    return compileBoxesToGlb(spec.id, boxes, materialsJson)
  }

  fun buildStaticHazardGlb(kind: StaticHazardKind): ByteArray {
    val hw = kind.widthMeters * 0.5f
    val h = kind.heightMeters
    val hl = kind.lengthMeters * 0.5f

    val boxes =
      when (kind) {
        StaticHazardKind.ROAD_BARRIER ->
          listOf(
            // Base feet
            BoxSpec(-hw, -hw * 0.65f, 0f, 0.18f, -hl, hl, 1),
            BoxSpec(hw * 0.65f, hw, 0f, 0.18f, -hl, hl, 1),
            // High-visibility safety red/white crossbeam up to exact height & width
            BoxSpec(-hw, hw, 0.34f, h, -hl * 0.72f, hl * 0.72f, 0),
            BoxSpec(-hw * 0.55f, hw * 0.55f, 0.48f, h * 0.86f, -hl * 0.76f, hl * 0.76f, 2),
          )
        StaticHazardKind.WORK_BARRICADE ->
          listOf(
            BoxSpec(-hw, -hw * 0.72f, 0f, h, -hl, hl, 1),
            BoxSpec(hw * 0.72f, hw, 0f, h, -hl, hl, 1),
            BoxSpec(-hw, hw, h * 0.48f, h * 0.92f, -hl * 0.6f, hl * 0.6f, 0),
            BoxSpec(-hw * 0.6f, hw * 0.6f, h * 0.62f, h * 0.78f, -hl * 0.64f, hl * 0.64f, 2),
          )
        StaticHazardKind.CONCRETE_BLOCK ->
          listOf(
            BoxSpec(-hw, hw, 0f, h * 0.42f, -hl, hl, 1),
            BoxSpec(-hw * 0.76f, hw * 0.76f, h * 0.42f, h, -hl * 0.85f, hl * 0.85f, 1),
            BoxSpec(-hw * 0.78f, hw * 0.78f, h * 0.75f, h * 0.90f, -hl * 0.87f, hl * 0.87f, 0),
          )
        StaticHazardKind.BROKEN_DOWN_CAR ->
          listOf(
            BoxSpec(-hw, hw, 0.18f, h * 0.54f, -hl, hl, 1),
            BoxSpec(-hw * 0.85f, hw * 0.85f, h * 0.52f, h, -hl * 0.58f, hl * 0.34f, 2),
            BoxSpec(-hw, -hw + 0.24f, 0f, 0.64f, hl * 0.42f, hl * 0.76f, 2),
            BoxSpec(hw - 0.24f, hw, 0f, 0.64f, hl * 0.42f, hl * 0.76f, 2),
            BoxSpec(-hw, -hw + 0.24f, 0f, 0.64f, -hl * 0.76f, -hl * 0.42f, 2),
            BoxSpec(hw - 0.24f, hw, 0f, 0.64f, -hl * 0.76f, -hl * 0.42f, 2),
            // Amber hazard flashers strictly flush with -hl
            BoxSpec(-hw * 0.85f, -hw * 0.55f, h * 0.38f, h * 0.48f, -hl, -hl + 0.05f, 0),
            BoxSpec(hw * 0.55f, hw * 0.85f, h * 0.38f, h * 0.48f, -hl, -hl + 0.05f, 0),
          )
      }

    val materialsJson =
      """
      {"name": "HazardAlertStripe", "pbrMetallicRoughness": {"baseColorFactor": [0.96, 0.38, 0.06, 1.0], "metallicFactor": 0.0, "roughnessFactor": 0.45}, "emissiveFactor": [0.35, 0.12, 0.0]},
      {"name": "HazardConcrete", "pbrMetallicRoughness": {"baseColorFactor": [0.46, 0.48, 0.52, 1.0], "metallicFactor": 0.0, "roughnessFactor": 0.90}},
      {"name": "HazardTrim", "pbrMetallicRoughness": {"baseColorFactor": [0.92, 0.92, 0.94, 1.0], "metallicFactor": 0.1, "roughnessFactor": 0.55}}
      """.trimIndent().replace("\n", " ")

    return compileBoxesToGlb(kind.id, boxes, materialsJson)
  }

  private fun compileBoxesToGlb(rootName: String, boxes: List<BoxSpec>, materialsJson: String): ByteArray {
    val byMaterial = boxes.groupBy { it.materialIndex }.toSortedMap()
    val meshes =
      byMaterial.map { (matIndex, group) ->
        val acc = BoxMeshBuilder()
        group.forEach { b -> acc.addBox(b.minX, b.maxX, b.minY, b.maxY, b.minZ, b.maxZ) }
        acc.build("${rootName}_Mat$matIndex", matIndex)
      }

    val binStream = ByteArrayOutputStream()
    val bufferViewsJson = StringBuilder()
    val accessorsJson = StringBuilder()
    val meshesJson = StringBuilder()
    val nodesJson = StringBuilder()

    var bufferViewIndex = 0
    var accessorIndex = 0

    fun appendBufferView(bytes: ByteArray, target: Int): Int {
      while (binStream.size() % 4 != 0) binStream.write(0)
      val offset = binStream.size()
      binStream.write(bytes)
      if (bufferViewIndex > 0) bufferViewsJson.append(',')
      bufferViewsJson.append(
        """{"buffer":0,"byteOffset":$offset,"byteLength":${bytes.size},"target":$target}"""
      )
      return bufferViewIndex++
    }

    fun floatsToLe(values: FloatArray): ByteArray {
      val bb = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
      values.forEach { bb.putFloat(it) }
      return bb.array()
    }

    fun shortsToLe(values: ShortArray): ByteArray {
      val bb = ByteBuffer.allocate(values.size * 2).order(ByteOrder.LITTLE_ENDIAN)
      values.forEach { bb.putShort(it) }
      return bb.array()
    }

    val childIndices = (1..meshes.size).joinToString(",")
    nodesJson.append("""{"name":"$rootName","children":[$childIndices]}""")

    meshes.forEachIndexed { idx, mesh ->
      val posView = appendBufferView(floatsToLe(mesh.positions), 34962)
      val nrmView = appendBufferView(floatsToLe(mesh.normals), 34962)
      val idxView = appendBufferView(shortsToLe(mesh.indices), 34963)
      val vCount = mesh.positions.size / 3
      val iCount = mesh.indices.size

      val posAcc = accessorIndex++
      val nrmAcc = accessorIndex++
      val idxAcc = accessorIndex++

      if (posAcc > 0) accessorsJson.append(',')
      accessorsJson.append(
        """{"bufferView":$posView,"componentType":5126,"count":$vCount,"type":"VEC3","min":[${mesh.minPos[0]},${mesh.minPos[1]},${mesh.minPos[2]}],"max":[${mesh.maxPos[0]},${mesh.maxPos[1]},${mesh.maxPos[2]}]}"""
      )
      accessorsJson.append(',')
      accessorsJson.append(
        """{"bufferView":$nrmView,"componentType":5126,"count":$vCount,"type":"VEC3"}"""
      )
      accessorsJson.append(',')
      accessorsJson.append(
        """{"bufferView":$idxView,"componentType":5123,"count":$iCount,"type":"SCALAR"}"""
      )

      if (idx > 0) meshesJson.append(',')
      meshesJson.append(
        """{"name":"${mesh.name}","primitives":[{"attributes":{"POSITION":$posAcc,"NORMAL":$nrmAcc},"indices":$idxAcc,"material":${mesh.materialIndex}}]}"""
      )
      nodesJson.append(",{\"name\":\"${mesh.name}\",\"mesh\":$idx}")
    }

    while (binStream.size() % 4 != 0) binStream.write(0)
    val binBytes = binStream.toByteArray()

    val jsonString =
      """
      {
        "asset": {"version": "2.0", "generator": "DRIP_RACE_TRAFFIC_BUILDER"},
        "scene": 0,
        "scenes": [{"nodes": [0]}],
        "nodes": [$nodesJson],
        "meshes": [$meshesJson],
        "materials": [$materialsJson],
        "accessors": [$accessorsJson],
        "bufferViews": [$bufferViewsJson],
        "buffers": [{"byteLength": ${binBytes.size}}]
      }
      """
        .trimIndent()
        .replace("\n", " ")

    val jsonRaw = jsonString.toByteArray(StandardCharsets.UTF_8)
    val jsonPad = (4 - (jsonRaw.size % 4)) % 4
    val jsonChunkLen = jsonRaw.size + jsonPad
    val totalLen = 12 + 8 + jsonChunkLen + 8 + binBytes.size

    val out = ByteBuffer.allocate(totalLen).order(ByteOrder.LITTLE_ENDIAN)
    out.putInt(0x46546C67)
    out.putInt(2)
    out.putInt(totalLen)
    out.putInt(jsonChunkLen)
    out.putInt(0x4E4F534A)
    out.put(jsonRaw)
    repeat(jsonPad) { out.put(0x20.toByte()) }
    out.putInt(binBytes.size)
    out.putInt(0x004E4942)
    out.put(binBytes)
    return out.array()
  }
}
