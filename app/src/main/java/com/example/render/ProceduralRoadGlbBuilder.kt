package com.example.render

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * Generates a valid glTF 2.0 binary (.glb) buffer for the 3-lane highway road geometry.
 *
 * Geometry layout in Game World coordinates (Y = 0 road plane, +Z forward):
 * - 3 playable lanes of 3.6 m width each, centered at X = -3.6, 0.0, +3.6 (total asphalt width 10.8 m).
 * - Asphalt deck: X in [-5.4, +5.4], Z in [-30.0, +240.0], Y = 0.0.
 * - Shoulders: X in [-8.4, -5.4] and [+5.4, +8.4], Y = -0.015.
 * - Side curbs/barriers: X in [-8.75, -8.4] and [+8.4, +8.75], Y in [0.0, 0.48].
 * - Dashed lane dividers at X = -1.8 and X = +1.8; solid outer edge lines at X = -5.22 and X = +5.22.
 */
object ProceduralRoadGlbBuilder {
  private const val Z_START = -60f
  private const val Z_END = 270f
  const val SEAMLESS_REPEAT_CYCLE_METERS = 30.0

  private data class MeshData(
    val name: String,
    val materialIndex: Int,
    val positions: FloatArray,
    val normals: FloatArray,
    val tangents: FloatArray?,
    val uvs: FloatArray,
    val indices: ShortArray,
    val minPos: FloatArray,
    val maxPos: FloatArray,
  )

  private class GeometryAccumulator(private val includeTangents: Boolean = false) {
    private val pos = ArrayList<Float>()
    private val nrm = ArrayList<Float>()
    private val tan = ArrayList<Float>()
    private val uv = ArrayList<Float>()
    private val idx = ArrayList<Short>()
    private var minX = Float.POSITIVE_INFINITY
    private var minY = Float.POSITIVE_INFINITY
    private var minZ = Float.POSITIVE_INFINITY
    private var maxX = Float.NEGATIVE_INFINITY
    private var maxY = Float.NEGATIVE_INFINITY
    private var maxZ = Float.NEGATIVE_INFINITY

    fun addHorizontalQuad(
      x0: Float,
      x1: Float,
      y: Float,
      z0: Float,
      z1: Float,
      uScale: Float = 1f,
      vScale: Float = 1f,
    ) {
      val base = (pos.size / 3)
      // Counter-clockwise when viewed from +Y looking down (Y+ normal; T=+X, B=+Z -> w=-1)
      addVertex(x0, y, z0, 0f, 1f, 0f, 0f, 0f)
      addVertex(x0, y, z1, 0f, 1f, 0f, 0f, vScale)
      addVertex(x1, y, z1, 0f, 1f, 0f, uScale, vScale)
      addVertex(x1, y, z0, 0f, 1f, 0f, uScale, 0f)
      idx.add(base.toShort())
      idx.add((base + 1).toShort())
      idx.add((base + 2).toShort())
      idx.add(base.toShort())
      idx.add((base + 2).toShort())
      idx.add((base + 3).toShort())
    }

    fun addBox(x0: Float, x1: Float, y0: Float, y1: Float, z0: Float, z1: Float) {
      // Top (+Y)
      addHorizontalQuad(x0, x1, y1, z0, z1)
      // Inner facing +X (left barrier inner face)
      addVerticalQuadX(x1, y0, y1, z0, z1, 1f)
      // Inner facing -X (right barrier inner face)
      addVerticalQuadX(x0, y0, y1, z1, z0, -1f)
    }

    private fun addVerticalQuadX(
      x: Float,
      y0: Float,
      y1: Float,
      zStart: Float,
      zEnd: Float,
      nx: Float,
    ) {
      val base = pos.size / 3
      addVertex(x, y0, zStart, nx, 0f, 0f, 0f, 0f)
      addVertex(x, y1, zStart, nx, 0f, 0f, 0f, 1f)
      addVertex(x, y1, zEnd, nx, 0f, 0f, 1f, 1f)
      addVertex(x, y0, zEnd, nx, 0f, 0f, 1f, 0f)
      idx.add(base.toShort())
      idx.add((base + 1).toShort())
      idx.add((base + 2).toShort())
      idx.add(base.toShort())
      idx.add((base + 2).toShort())
      idx.add((base + 3).toShort())
    }

    private fun addVertex(
      x: Float,
      y: Float,
      z: Float,
      nx: Float,
      ny: Float,
      nz: Float,
      u: Float,
      v: Float,
    ) {
      pos.add(x)
      pos.add(y)
      pos.add(z)
      nrm.add(nx)
      nrm.add(ny)
      nrm.add(nz)
      if (includeTangents) {
        tan.add(1f)
        tan.add(0f)
        tan.add(0f)
        tan.add(-1f)
      }
      uv.add(u)
      uv.add(v)
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
        tangents = if (includeTangents) tan.toFloatArray() else null,
        uvs = uv.toFloatArray(),
        indices = idx.toShortArray(),
        minPos = floatArrayOf(minX, minY, minZ),
        maxPos = floatArrayOf(maxX, maxY, maxZ),
      )
  }

  fun buildRoadGlb(includeAsphaltTextureSlots: Boolean = false): ByteArray {
    // 1. Asphalt Deck (3 lanes x 3.6 m = 10.8 m -> [-5.4, +5.4], 330 m = 55 * 6 m)
    val asphalt =
      GeometryAccumulator(includeTangents = includeAsphaltTextureSlots).apply {
        addHorizontalQuad(-5.4f, 5.4f, 0.0f, Z_START, Z_END, uScale = 3f, vScale = 55f)
      }.build("AsphaltDeck", 0)

    // 2. Shoulders
    val shoulders =
      GeometryAccumulator().apply {
        addHorizontalQuad(-8.4f, -5.4f, -0.015f, Z_START, Z_END, uScale = 1f, vScale = 55f)
        addHorizontalQuad(5.4f, 8.4f, -0.015f, Z_START, Z_END, uScale = 1f, vScale = 55f)
      }.build("RoadShoulders", 1)

    // 3. Curbs / Barriers + 15m-spaced barrier posts (aligned with 30m seamless cycle)
    val curbs =
      GeometryAccumulator().apply {
        addBox(-8.75f, -8.4f, -0.02f, 0.48f, Z_START, Z_END)
        addBox(8.4f, 8.75f, -0.02f, 0.48f, Z_START, Z_END)
        var postZ = Z_START
        while (postZ < Z_END) {
          addBox(-8.70f, -8.45f, 0.48f, 0.92f, postZ, (postZ + 0.30f).coerceAtMost(Z_END))
          addBox(8.45f, 8.70f, 0.48f, 0.92f, postZ, (postZ + 0.30f).coerceAtMost(Z_END))
          postZ += 15.0f
        }
      }.build("RoadCurbs", 2)

    // 4. Lane Markings: dashed dividers at X = -1.8 and +1.8; solid outer lines at X = -5.22 and +5.22
    val markings =
      GeometryAccumulator().apply {
        addHorizontalQuad(-5.28f, -5.16f, 0.008f, Z_START, Z_END)
        addHorizontalQuad(5.16f, 5.28f, 0.008f, Z_START, Z_END)
        var z = Z_START
        while (z < Z_END) {
          val dashEnd = (z + 4.0f).coerceAtMost(Z_END)
          addHorizontalQuad(-1.86f, -1.74f, 0.008f, z, dashEnd)
          addHorizontalQuad(1.74f, 1.86f, 0.008f, z, dashEnd)
          z += 10.0f
        }
      }.build("LaneMarkings", 3)

    val meshes = listOf(asphalt, shoulders, curbs, markings)
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

    fun floatArrayToLeBytes(values: FloatArray): ByteArray {
      val bb = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
      values.forEach { bb.putFloat(it) }
      return bb.array()
    }

    fun shortArrayToLeBytes(values: ShortArray): ByteArray {
      val bb = ByteBuffer.allocate(values.size * 2).order(ByteOrder.LITTLE_ENDIAN)
      values.forEach { bb.putShort(it) }
      return bb.array()
    }

    // Node 0 is root; nodes 1..4 are meshes
    nodesJson.append("""{"name":"HighwayRoadRoot","children":[1,2,3,4]}""")

    meshes.forEachIndexed { meshIdx, mesh ->
      val posBytes = floatArrayToLeBytes(mesh.positions)
      val nrmBytes = floatArrayToLeBytes(mesh.normals)
      val uvBytes = floatArrayToLeBytes(mesh.uvs)
      val idxBytes = shortArrayToLeBytes(mesh.indices)
      val tanBytes = mesh.tangents?.let { floatArrayToLeBytes(it) }

      val posView = appendBufferView(posBytes, 34962)
      val nrmView = appendBufferView(nrmBytes, 34962)
      val uvView = appendBufferView(uvBytes, 34962)
      val idxView = appendBufferView(idxBytes, 34963)
      val tanView = tanBytes?.let { appendBufferView(it, 34962) }

      val vCount = mesh.positions.size / 3
      val iCount = mesh.indices.size

      val posAcc = accessorIndex++
      val nrmAcc = accessorIndex++
      val uvAcc = accessorIndex++
      val idxAcc = accessorIndex++
      val tanAcc = if (tanView != null) accessorIndex++ else null

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
        """{"bufferView":$uvView,"componentType":5126,"count":$vCount,"type":"VEC2"}"""
      )
      accessorsJson.append(',')
      accessorsJson.append(
        """{"bufferView":$idxView,"componentType":5123,"count":$iCount,"type":"SCALAR"}"""
      )
      if (tanView != null && tanAcc != null) {
        accessorsJson.append(',')
        accessorsJson.append(
          """{"bufferView":$tanView,"componentType":5126,"count":$vCount,"type":"VEC4"}"""
        )
      }

      val tangentAttr = if (tanAcc != null) ""","TANGENT":$tanAcc""" else ""
      if (meshIdx > 0) meshesJson.append(',')
      meshesJson.append(
        """{"name":"${mesh.name}","primitives":[{"attributes":{"POSITION":$posAcc,"NORMAL":$nrmAcc,"TEXCOORD_0":$uvAcc$tangentAttr},"indices":$idxAcc,"material":${mesh.materialIndex}}]}"""
      )

      nodesJson.append(",{\"name\":\"${mesh.name}\",\"mesh\":$meshIdx}")
    }

    while (binStream.size() % 4 != 0) binStream.write(0)
    val binBytes = binStream.toByteArray()

    // Embed 1x1 valid PNG placeholder when supplied asphalt maps exist so UbershaderProvider
    // allocates baseColorMap, normalMap, and metallicRoughnessMap samplers for AsphaltSurface.
    val texturesJson =
      if (includeAsphaltTextureSlots) {
        val placeholderPng =
          byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
            0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
            0x08, 0x02, 0x00, 0x00, 0x00, 0x90.toByte(), 0x77, 0x53, 0xDE.toByte(),
            0x00, 0x00, 0x00, 0x0C, 0x49, 0x44, 0x41, 0x54,
            0x78, 0x9C.toByte(), 0x63, 0x68, 0x68, 0xF8.toByte(), 0x0F, 0x00,
            0x03, 0x83.toByte(), 0x02, 0x00,
            0x00, 0xA5.toByte(), 0xFB.toByte(), 0x89.toByte(),
            0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44,
            0xAE.toByte(), 0x42, 0x60, 0x82.toByte(),
          )
        val imgView = appendBufferView(placeholderPng, 0)
        // Update binBytes with the appended PNG
        "" to imgView
      } else {
        "" to -1
      }

    val finalBinBytes =
      if (includeAsphaltTextureSlots) {
        while (binStream.size() % 4 != 0) binStream.write(0)
        binStream.toByteArray()
      } else {
        binBytes
      }

    val asphaltMaterialJson =
      if (includeAsphaltTextureSlots) {
        """{"name": "AsphaltSurface", "pbrMetallicRoughness": {"baseColorFactor": [1.0, 1.0, 1.0, 1.0], "baseColorTexture": {"index": 0}, "metallicFactor": 0.0, "roughnessFactor": 1.0, "metallicRoughnessTexture": {"index": 0}}, "normalTexture": {"index": 0}}"""
      } else {
        """{"name": "AsphaltSurface", "pbrMetallicRoughness": {"baseColorFactor": [0.10, 0.11, 0.13, 1.0], "metallicFactor": 0.0, "roughnessFactor": 0.86}}"""
      }

    val extraTextureSection =
      if (texturesJson.second >= 0) {
        """"samplers": [{"magFilter": 9729, "minFilter": 9987, "wrapS": 10497, "wrapT": 10497}], "images": [{"bufferView": ${texturesJson.second}, "mimeType": "image/png"}], "textures": [{"sampler": 0, "source": 0}],"""
      } else {
        ""
      }

    val jsonString =
      """
      {
        "asset": {"version": "2.0", "generator": "DRIP_RACE_ROAD_BUILDER"},
        "scene": 0,
        "scenes": [{"nodes": [0]}],
        "nodes": [$nodesJson],
        "meshes": [$meshesJson],
        $extraTextureSection
        "materials": [
          $asphaltMaterialJson,
          {"name": "ShoulderSurface", "pbrMetallicRoughness": {"baseColorFactor": [0.065, 0.075, 0.09, 1.0], "metallicFactor": 0.0, "roughnessFactor": 0.94}},
          {"name": "CurbBarrier", "pbrMetallicRoughness": {"baseColorFactor": [0.30, 0.34, 0.40, 1.0], "metallicFactor": 0.05, "roughnessFactor": 0.78}},
          {"name": "LaneMarkingPaint", "pbrMetallicRoughness": {"baseColorFactor": [0.92, 0.94, 0.98, 1.0], "metallicFactor": 0.0, "roughnessFactor": 0.50}}
        ],
        "accessors": [$accessorsJson],
        "bufferViews": [$bufferViewsJson],
        "buffers": [{"byteLength": ${finalBinBytes.size}}]
      }
      """
        .trimIndent()
        .replace("\n", " ")

    val jsonRaw = jsonString.toByteArray(StandardCharsets.UTF_8)
    val jsonPad = (4 - (jsonRaw.size % 4)) % 4
    val jsonChunkLen = jsonRaw.size + jsonPad
    val totalLen = 12 + 8 + jsonChunkLen + 8 + finalBinBytes.size

    val out = ByteBuffer.allocate(totalLen).order(ByteOrder.LITTLE_ENDIAN)
    // Header
    out.putInt(0x46546C67) // "glTF"
    out.putInt(2)
    out.putInt(totalLen)
    // JSON Chunk
    out.putInt(jsonChunkLen)
    out.putInt(0x4E4F534A) // "JSON"
    out.put(jsonRaw)
    repeat(jsonPad) { out.put(0x20.toByte()) }
    // BIN Chunk
    out.putInt(finalBinBytes.size)
    out.putInt(0x004E4942) // "BIN"
    out.put(finalBinBytes)
    return out.array()
  }
}
