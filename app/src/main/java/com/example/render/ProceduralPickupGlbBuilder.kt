package com.example.render

import com.example.sim.CoinTier
import com.example.sim.PowerUpType
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import kotlin.math.cos
import kotlin.math.sin

/**
 * Generates honest procedural 3D glTF 2.0 binary (.glb) pickup visuals for Phase 4:
 * - Three distinct coin medallions: Regular 10 (gold), Bonus 25 (cyan), Rare 50 (amethyst-gold)
 * - Four distinct power-ups: Auto Nitro, Magnet (U-shape), Shield (hex crest), 2x Coins (twin discs)
 *
 * Uses right-handed Y-up meters centered on X=0, Y=0, Z=0.
 */
object ProceduralPickupGlbBuilder {
  private class MeshBuilder {
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
      addQuad(x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0, 0f, 1f, 0f)
      addQuad(x0, y0, z1, x0, y0, z0, x1, y0, z0, x1, y0, z1, 0f, -1f, 0f)
      addQuad(x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, 0f, 0f, 1f)
      addQuad(x1, y0, z0, x0, y0, z0, x0, y1, z0, x1, y1, z0, 0f, 0f, -1f)
      addQuad(x1, y0, z1, x1, y0, z0, x1, y1, z0, x1, y1, z1, 1f, 0f, 0f)
      addQuad(x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0, -1f, 0f, 0f)
    }

    fun addDiscFacingZ(
      centerX: Float,
      centerY: Float,
      radius: Float,
      halfThickness: Float,
      sides: Int = 12,
    ) {
      val frontCenterIdx = addVertex(centerX, centerY, halfThickness, 0f, 0f, 1f)
      val backCenterIdx = addVertex(centerX, centerY, -halfThickness, 0f, 0f, -1f)

      val step = (2.0 * Math.PI) / sides
      for (i in 0 until sides) {
        val a0 = i * step
        val a1 = (i + 1) * step
        val x0 = centerX + (radius * cos(a0)).toFloat()
        val y0 = centerY + (radius * sin(a0)).toFloat()
        val x1 = centerX + (radius * cos(a1)).toFloat()
        val y1 = centerY + (radius * sin(a1)).toFloat()

        // Front cap (+Z)
        val f0 = addVertex(x0, y0, halfThickness, 0f, 0f, 1f)
        val f1 = addVertex(x1, y1, halfThickness, 0f, 0f, 1f)
        idx.add(frontCenterIdx.toShort())
        idx.add(f0.toShort())
        idx.add(f1.toShort())

        // Back cap (-Z)
        val b0 = addVertex(x0, y0, -halfThickness, 0f, 0f, -1f)
        val b1 = addVertex(x1, y1, -halfThickness, 0f, 0f, -1f)
        idx.add(backCenterIdx.toShort())
        idx.add(b1.toShort())
        idx.add(b0.toShort())

        // Outer rim quad
        val nx = cos((a0 + a1) * 0.5).toFloat()
        val ny = sin((a0 + a1) * 0.5).toFloat()
        addQuad(x0, y0, -halfThickness, x1, y1, -halfThickness, x1, y1, halfThickness, x0, y0, halfThickness, nx, ny, 0f)
      }
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

    private fun addVertex(x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float): Int {
      val index = pos.size / 3
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
      return index
    }

    fun finish(name: String, materialIndex: Int): PartData =
      PartData(
        name = name,
        materialIndex = materialIndex,
        positions = pos.toFloatArray(),
        normals = nrm.toFloatArray(),
        indices = idx.toShortArray(),
        minPos = floatArrayOf(minX, minY, minZ),
        maxPos = floatArrayOf(maxX, maxY, maxZ),
      )
  }

  private data class PartData(
    val name: String,
    val materialIndex: Int,
    val positions: FloatArray,
    val normals: FloatArray,
    val indices: ShortArray,
    val minPos: FloatArray,
    val maxPos: FloatArray,
  )

  fun buildCoinGlb(tier: CoinTier): ByteArray {
    val r = tier.radiusMeters
    val ht = tier.thicknessMeters * 0.5f
    val outer = MeshBuilder().apply { addDiscFacingZ(0f, 0f, r, ht, sides = 14) }
    val inner = MeshBuilder().apply { addDiscFacingZ(0f, 0f, r * 0.68f, ht * 1.18f, sides = 10) }

    val (outerMat, innerMat) =
      when (tier) {
        CoinTier.REGULAR ->
          listOf(
            """{"name":"GoldRim","pbrMetallicRoughness":{"baseColorFactor":[0.98,0.78,0.16,1.0],"metallicFactor":0.85,"roughnessFactor":0.24},"emissiveFactor":[0.28,0.18,0.02],"doubleSided":true}""",
            """{"name":"GoldCore","pbrMetallicRoughness":{"baseColorFactor":[1.0,0.88,0.34,1.0],"metallicFactor":0.9,"roughnessFactor":0.18},"emissiveFactor":[0.35,0.24,0.03],"doubleSided":true}""",
          )
        CoinTier.BONUS ->
          listOf(
            """{"name":"CyanRim","pbrMetallicRoughness":{"baseColorFactor":[0.16,0.82,0.98,1.0],"metallicFactor":0.82,"roughnessFactor":0.20},"emissiveFactor":[0.06,0.32,0.42],"doubleSided":true}""",
            """{"name":"SilverCore","pbrMetallicRoughness":{"baseColorFactor":[0.86,0.96,1.0,1.0],"metallicFactor":0.9,"roughnessFactor":0.16},"emissiveFactor":[0.10,0.28,0.38],"doubleSided":true}""",
          )
        CoinTier.RARE ->
          listOf(
            """{"name":"RareRim","pbrMetallicRoughness":{"baseColorFactor":[0.88,0.24,0.96,1.0],"metallicFactor":0.86,"roughnessFactor":0.18},"emissiveFactor":[0.38,0.06,0.44],"doubleSided":true}""",
            """{"name":"RareGoldCore","pbrMetallicRoughness":{"baseColorFactor":[1.0,0.84,0.24,1.0],"metallicFactor":0.92,"roughnessFactor":0.14},"emissiveFactor":[0.45,0.32,0.04],"doubleSided":true}""",
          )
      }

    return assembleGlb(
      parts = listOf(outer.finish("OuterCoin", 0), inner.finish("InnerCoin", 1)),
      materialsJson = listOf(outerMat, innerMat),
    )
  }

  fun buildPowerUpGlb(type: PowerUpType): ByteArray =
    when (type) {
      PowerUpType.AUTOMATIC_NITRO -> {
        val core = MeshBuilder().apply {
          addBox(-0.22f, 0.22f, -0.44f, 0.44f, -0.22f, 0.22f)
        }
        val fins = MeshBuilder().apply {
          addBox(-0.46f, 0.46f, -0.18f, 0.18f, -0.12f, 0.12f)
        }
        assembleGlb(
          parts = listOf(core.finish("NitroCore", 0), fins.finish("NitroFins", 1)),
          materialsJson =
            listOf(
              """{"name":"NitroFlame","pbrMetallicRoughness":{"baseColorFactor":[1.0,0.52,0.08,1.0],"metallicFactor":0.35,"roughnessFactor":0.25},"emissiveFactor":[0.55,0.22,0.02],"doubleSided":true}""",
              """{"name":"NitroGold","pbrMetallicRoughness":{"baseColorFactor":[1.0,0.88,0.22,1.0],"metallicFactor":0.75,"roughnessFactor":0.22},"emissiveFactor":[0.40,0.30,0.04],"doubleSided":true}""",
            ),
        )
      }
      PowerUpType.MAGNET -> {
        val redArms = MeshBuilder().apply {
          addBox(-0.44f, -0.18f, -0.12f, 0.46f, -0.14f, 0.14f)
          addBox(0.18f, 0.44f, -0.12f, 0.46f, -0.14f, 0.14f)
          addBox(-0.44f, 0.44f, -0.42f, -0.12f, -0.14f, 0.14f)
        }
        val silverTips = MeshBuilder().apply {
          addBox(-0.45f, -0.17f, 0.34f, 0.50f, -0.15f, 0.15f)
          addBox(0.17f, 0.45f, 0.34f, 0.50f, -0.15f, 0.15f)
        }
        assembleGlb(
          parts = listOf(redArms.finish("MagnetYoke", 0), silverTips.finish("MagnetTips", 1)),
          materialsJson =
            listOf(
              """{"name":"MagnetRed","pbrMetallicRoughness":{"baseColorFactor":[0.92,0.16,0.18,1.0],"metallicFactor":0.45,"roughnessFactor":0.28},"emissiveFactor":[0.35,0.04,0.05],"doubleSided":true}""",
              """{"name":"MagnetSilver","pbrMetallicRoughness":{"baseColorFactor":[0.88,0.92,0.96,1.0],"metallicFactor":0.92,"roughnessFactor":0.16},"emissiveFactor":[0.15,0.18,0.22],"doubleSided":true}""",
            ),
        )
      }
      PowerUpType.SHIELD -> {
        val crest = MeshBuilder().apply { addDiscFacingZ(0f, 0f, 0.54f, 0.11f, sides = 6) }
        val trim = MeshBuilder().apply { addBox(-0.18f, 0.18f, -0.34f, 0.34f, -0.15f, 0.15f) }
        assembleGlb(
          parts = listOf(crest.finish("ShieldHex", 0), trim.finish("ShieldCore", 1)),
          materialsJson =
            listOf(
              """{"name":"ShieldBlue","pbrMetallicRoughness":{"baseColorFactor":[0.14,0.62,1.0,1.0],"metallicFactor":0.55,"roughnessFactor":0.22},"emissiveFactor":[0.08,0.32,0.58],"doubleSided":true}""",
              """{"name":"ShieldCore","pbrMetallicRoughness":{"baseColorFactor":[0.85,0.95,1.0,1.0],"metallicFactor":0.85,"roughnessFactor":0.18},"emissiveFactor":[0.22,0.40,0.55],"doubleSided":true}""",
            ),
        )
      }
      PowerUpType.DOUBLE_COINS -> {
        val discs = MeshBuilder().apply {
          addDiscFacingZ(-0.22f, 0.08f, 0.34f, 0.09f, sides = 12)
          addDiscFacingZ(0.22f, -0.08f, 0.34f, 0.09f, sides = 12)
        }
        val bar = MeshBuilder().apply { addBox(-0.42f, 0.42f, -0.10f, 0.10f, -0.13f, 0.13f) }
        assembleGlb(
          parts = listOf(discs.finish("DoubleDiscs", 0), bar.finish("DoubleBar", 1)),
          materialsJson =
            listOf(
              """{"name":"DoubleEmerald","pbrMetallicRoughness":{"baseColorFactor":[0.16,0.88,0.46,1.0],"metallicFactor":0.65,"roughnessFactor":0.22},"emissiveFactor":[0.05,0.38,0.16],"doubleSided":true}""",
              """{"name":"DoubleGold","pbrMetallicRoughness":{"baseColorFactor":[1.0,0.85,0.22,1.0],"metallicFactor":0.88,"roughnessFactor":0.18},"emissiveFactor":[0.35,0.28,0.03],"doubleSided":true}""",
            ),
        )
      }
    }

  private fun assembleGlb(parts: List<PartData>, materialsJson: List<String>): ByteArray {
    val bin = ByteArrayOutputStream()
    val bufferViews = mutableListOf<String>()
    val accessors = mutableListOf<String>()
    val meshesJson = mutableListOf<String>()
    val nodesJson = mutableListOf<String>()
    val nodeIndices = mutableListOf<Int>()

    for ((partIndex, part) in parts.withIndex()) {
      pad4(bin)
      val posOffset = bin.size()
      val posBytes = ByteBuffer.allocate(part.positions.size * 4).order(ByteOrder.LITTLE_ENDIAN)
      part.positions.forEach { posBytes.putFloat(it) }
      bin.write(posBytes.array())

      val nrmOffset = bin.size()
      val nrmBytes = ByteBuffer.allocate(part.normals.size * 4).order(ByteOrder.LITTLE_ENDIAN)
      part.normals.forEach { nrmBytes.putFloat(it) }
      bin.write(nrmBytes.array())

      val idxOffset = bin.size()
      val idxBytes = ByteBuffer.allocate(part.indices.size * 2).order(ByteOrder.LITTLE_ENDIAN)
      part.indices.forEach { idxBytes.putShort(it) }
      bin.write(idxBytes.array())

      val vCount = part.positions.size / 3
      val iCount = part.indices.size
      val baseView = partIndex * 3
      val baseAcc = partIndex * 3

      bufferViews += """{"buffer":0,"byteOffset":$posOffset,"byteLength":${vCount * 12},"target":34962}"""
      bufferViews += """{"buffer":0,"byteOffset":$nrmOffset,"byteLength":${vCount * 12},"target":34962}"""
      bufferViews += """{"buffer":0,"byteOffset":$idxOffset,"byteLength":${iCount * 2},"target":34963}"""

      accessors += """{"bufferView":$baseView,"componentType":5126,"count":$vCount,"type":"VEC3","min":[${part.minPos[0]},${part.minPos[1]},${part.minPos[2]}],"max":[${part.maxPos[0]},${part.maxPos[1]},${part.maxPos[2]}]}"""
      accessors += """{"bufferView":${baseView + 1},"componentType":5126,"count":$vCount,"type":"VEC3"}"""
      accessors += """{"bufferView":${baseView + 2},"componentType":5123,"count":$iCount,"type":"SCALAR"}"""

      meshesJson += """{"name":"${part.name}","primitives":[{"attributes":{"POSITION":$baseAcc,"NORMAL":${baseAcc + 1}},"indices":${baseAcc + 2},"material":${part.materialIndex}}]}"""
      nodesJson += """{"name":"${part.name}","mesh":$partIndex}"""
      nodeIndices += partIndex
    }

    pad4(bin)
    val binArray = bin.toByteArray()
    val gltfJson =
      """{"asset":{"version":"2.0","generator":"DripRaceProceduralPickup"},"scene":0,"scenes":[{"nodes":[${nodeIndices.joinToString(",")}]}],"nodes":[${nodesJson.joinToString(",")}],"meshes":[${meshesJson.joinToString(",")}],"materials":[${materialsJson.joinToString(",")}],"accessors":[${accessors.joinToString(",")}],"bufferViews":[${bufferViews.joinToString(",")}],"buffers":[{"byteLength":${binArray.size}}]}"""
    val jsonBytesRaw = gltfJson.toByteArray(StandardCharsets.UTF_8)
    val jsonPad = (4 - (jsonBytesRaw.size % 4)) % 4
    val jsonLength = jsonBytesRaw.size + jsonPad
    val totalLength = 12 + 8 + jsonLength + 8 + binArray.size

    val out = ByteBuffer.allocate(totalLength).order(ByteOrder.LITTLE_ENDIAN)
    out.putInt(0x46546C67)
    out.putInt(2)
    out.putInt(totalLength)
    out.putInt(jsonLength)
    out.putInt(0x4E4F534A)
    out.put(jsonBytesRaw)
    repeat(jsonPad) { out.put(0x20.toByte()) }
    out.putInt(binArray.size)
    out.putInt(0x004E4942)
    out.put(binArray)
    return out.array()
  }

  private fun pad4(stream: ByteArrayOutputStream) {
    while (stream.size() % 4 != 0) stream.write(0)
  }
}
