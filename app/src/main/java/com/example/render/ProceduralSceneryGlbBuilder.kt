package com.example.render

import com.example.scenery.GroundSkirtTheme
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.max
import kotlin.math.sin
import org.json.JSONArray
import org.json.JSONObject

/**
 * Generates lightweight glTF 2.0 binary (`.glb`) helper meshes for Phase 7 environment integration:
 * 1. Equirectangular Unlit Sky Dome (`KHR_materials_unlit` + `SAMPLER_2D` `baseColorTexture` slot)
 *    for rendering the genuine decoded `daylight-1k.hdr`, `sunset-1k.hdr`, and `night-1k.hdr` skies.
 * 2. Flanking Roadside Ground Skirt (`|X| in [8.72, 260.0]`, `Z in [-60, 270]`, `30 m` seamless
 *    repeat cycle) supporting `scenery/grass` and `scenery/sand` PBR texture maps or urban/night aprons.
 * 3. Grounded Contact Shadow Quad beneath vehicles (`ALPHA_MODE = BLEND`) so hero & traffic cars
 *    stay visually anchored to the asphalt even on `LOW` quality when directional shadow maps are off.
 * 4. Restrained Pooled Particle Streak Quad (`KHR_materials_unlit` + `BLEND`) for subtle speed/dust/nitro cues.
 */
object ProceduralSceneryGlbBuilder {
  private const val GLB_MAGIC = 0x46546C67
  private const val JSON_CHUNK_TYPE = 0x4E4F534A
  private const val BIN_CHUNK_TYPE = 0x004E4942

  private val PLACEHOLDER_1X1_PNG: ByteArray =
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

  /**
   * Builds an inward-facing equirectangular sphere (`radius = 345 m`) with `KHR_materials_unlit`
   * material `"SkyDomeSurface"` exposing `baseColorMap` for `TextureHelper.setBitmap`.
   */
  fun buildUnlitSkyDomeGlb(radiusMeters: Float = 345f): ByteArray {
    val latBands = 20
    val lonBands = 40
    val pos = ArrayList<Float>()
    val nrm = ArrayList<Float>()
    val uvs = ArrayList<Float>()
    val indices = ArrayList<Short>()

    var minX = Float.POSITIVE_INFINITY
    var minY = Float.POSITIVE_INFINITY
    var minZ = Float.POSITIVE_INFINITY
    var maxX = Float.NEGATIVE_INFINITY
    var maxY = Float.NEGATIVE_INFINITY
    var maxZ = Float.NEGATIVE_INFINITY

    for (lat in 0..latBands) {
      val v = lat.toFloat() / latBands.toFloat()
      val theta = v * PI.toFloat()
      val sinT = sin(theta)
      val cosT = cos(theta)
      for (lon in 0..lonBands) {
        val u = lon.toFloat() / lonBands.toFloat()
        val phi = (u - 0.5f) * 2.0f * PI.toFloat()
        val x = radiusMeters * sinT * sin(phi)
        val y = radiusMeters * cosT
        val z = radiusMeters * sinT * cos(phi)
        pos.add(x); pos.add(y); pos.add(z)
        nrm.add(-sinT * sin(phi)); nrm.add(-cosT); nrm.add(-sinT * cos(phi))
        uvs.add(u); uvs.add(v)
        minX = min(minX, x); minY = min(minY, y); minZ = min(minZ, z)
        maxX = max(maxX, x); maxY = max(maxY, y); maxZ = max(maxZ, z)
      }
    }

    val stride = lonBands + 1
    for (lat in 0 until latBands) {
      for (lon in 0 until lonBands) {
        val i0 = (lat * stride + lon).toShort()
        val i1 = ((lat + 1) * stride + lon).toShort()
        val i2 = ((lat + 1) * stride + lon + 1).toShort()
        val i3 = (lat * stride + lon + 1).toShort()
        // Inward-facing winding so camera inside sphere sees the front faces (and doubleSided=true as backup)
        indices.add(i0); indices.add(i2); indices.add(i1)
        indices.add(i0); indices.add(i3); indices.add(i2)
      }
    }

    val material =
      JSONObject().apply {
        put("name", "SkyDomeSurface")
        put("doubleSided", true)
        put(
          "pbrMetallicRoughness",
          JSONObject().apply {
            put("baseColorFactor", JSONArray().put(1.0).put(1.0).put(1.0).put(1.0))
            put("baseColorTexture", JSONObject().put("index", 0))
            put("metallicFactor", 0.0)
            put("roughnessFactor", 1.0)
          },
        )
        put("extensions", JSONObject().put("KHR_materials_unlit", JSONObject()))
      }

    return buildSingleMeshGlb(
      meshName = "HdrSkyDome",
      positions = pos.toFloatArray(),
      normals = nrm.toFloatArray(),
      tangents = null,
      uvs = uvs.toFloatArray(),
      indices = indices.toShortArray(),
      minBounds = floatArrayOf(minX, minY, minZ),
      maxBounds = floatArrayOf(maxX, maxY, maxZ),
      materialObj = material,
      includePlaceholderTexture = true,
      extensionsUsed = listOf("KHR_materials_unlit"),
      clampTextureV = true,
    )
  }

  /**
   * Builds the flanking roadside ground skirt (`X in [-260, -8.72]` and `[+8.72, +260]`,
   * `Z in [-60, +270]`, `Y = -0.04m`, `15 m` UV tiling -> exact `2` tiles per `30 m` road scroll cycle!).
   */
  fun buildGroundSkirtGlb(
    theme: GroundSkirtTheme,
    includeTextureSlots: Boolean,
  ): ByteArray {
    val z0 = -60f
    val z1 = 270f
    val y = -0.04f
    val innerX = 8.72f
    val outerX = 258.72f
    // 250m width / 15m tile = 16.6667f; 330m length / 15m tile = 22.0f (exact integer over 30m cycle = 2.0 tiles!)
    val uTiles = (outerX - innerX) / 15.0f
    val vTiles = (z1 - z0) / 15.0f

    val pos = floatArrayOf(
      // Left flank [-outerX, -innerX]
      -outerX, y, z0,
      -outerX, y, z1,
      -innerX, y, z1,
      -innerX, y, z0,
      // Right flank [+innerX, +outerX]
      innerX, y, z0,
      innerX, y, z1,
      outerX, y, z1,
      outerX, y, z0,
    )
    val nrm = floatArrayOf(
      0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f, 0f,
      0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f, 0f,
    )
    val tan =
      if (includeTextureSlots) {
        floatArrayOf(
          1f, 0f, 0f, -1f, 1f, 0f, 0f, -1f, 1f, 0f, 0f, -1f, 1f, 0f, 0f, -1f,
          1f, 0f, 0f, -1f, 1f, 0f, 0f, -1f, 1f, 0f, 0f, -1f, 1f, 0f, 0f, -1f,
        )
      } else {
        null
      }
    val uvs = floatArrayOf(
      0f, 0f, 0f, vTiles, uTiles, vTiles, uTiles, 0f,
      0f, 0f, 0f, vTiles, uTiles, vTiles, uTiles, 0f,
    )
    val indices = shortArrayOf(
      0, 1, 2, 0, 2, 3,
      4, 5, 6, 4, 6, 7,
    )

    val (baseR, baseG, baseB, rough) =
      when (theme) {
        GroundSkirtTheme.LEAFY_GRASS_PBR ->
          if (includeTextureSlots) floatArrayOf(0.92f, 0.96f, 0.90f, 1.0f)
          else floatArrayOf(0.12f, 0.24f, 0.09f, 0.92f)
        GroundSkirtTheme.AERIAL_SAND_PBR ->
          if (includeTextureSlots) floatArrayOf(1.0f, 0.95f, 0.88f, 1.0f)
          else floatArrayOf(0.58f, 0.38f, 0.22f, 0.90f)
        GroundSkirtTheme.URBAN_CONCRETE_APRON ->
          floatArrayOf(0.14f, 0.15f, 0.18f, 0.84f)
        GroundSkirtTheme.NIGHT_REFLECTIVE_APRON ->
          floatArrayOf(0.05f, 0.06f, 0.09f, 0.58f)
      }

    val matObj =
      JSONObject().apply {
        put("name", "GroundSkirtSurface")
        put(
          "pbrMetallicRoughness",
          JSONObject().apply {
            put("baseColorFactor", JSONArray().put(baseR.toDouble()).put(baseG.toDouble()).put(baseB.toDouble()).put(1.0))
            put("metallicFactor", 0.0)
            put("roughnessFactor", rough.toDouble())
            if (includeTextureSlots) {
              put("baseColorTexture", JSONObject().put("index", 0))
              put("metallicRoughnessTexture", JSONObject().put("index", 0))
            }
          },
        )
        if (includeTextureSlots) {
          put("normalTexture", JSONObject().put("index", 0))
        }
      }

    return buildSingleMeshGlb(
      meshName = "GroundSkirt",
      positions = pos,
      normals = nrm,
      tangents = tan,
      uvs = uvs,
      indices = indices,
      minBounds = floatArrayOf(-outerX, y, z0),
      maxBounds = floatArrayOf(outerX, y, z1),
      materialObj = matObj,
      includePlaceholderTexture = includeTextureSlots,
      extensionsUsed = emptyList(),
      clampTextureV = false,
    )
  }

  /**
   * Builds a grounded contact shadow quad (`1.96 m x 4.45 m` at `Y = 0.012 m`) with soft inner core
   * and feathered outer skirt so cars sit anchored to the road surface.
   */
  fun buildContactShadowGlb(): ByteArray {
    val y = 0.012f
    val hw = 0.96f
    val hl = 2.22f
    val pos = floatArrayOf(
      -hw, y, -hl,
      -hw, y, hl,
      hw, y, hl,
      hw, y, -hl,
    )
    val nrm = floatArrayOf(0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f, 0f)
    val uvs = floatArrayOf(0f, 0f, 0f, 1f, 1f, 1f, 1f, 0f)
    val indices = shortArrayOf(0, 1, 2, 0, 2, 3)

    val matObj =
      JSONObject().apply {
        put("name", "ContactShadowMat")
        put("alphaMode", "BLEND")
        put(
          "pbrMetallicRoughness",
          JSONObject().apply {
            put("baseColorFactor", JSONArray().put(0.01).put(0.012).put(0.018).put(0.56))
            put("metallicFactor", 0.0)
            put("roughnessFactor", 1.0)
          },
        )
        put("extensions", JSONObject().put("KHR_materials_unlit", JSONObject()))
      }

    return buildSingleMeshGlb(
      meshName = "ContactShadowQuad",
      positions = pos,
      normals = nrm,
      tangents = null,
      uvs = uvs,
      indices = indices,
      minBounds = floatArrayOf(-hw, y, -hl),
      maxBounds = floatArrayOf(hw, y, hl),
      materialObj = matObj,
      includePlaceholderTexture = false,
      extensionsUsed = listOf("KHR_materials_unlit"),
      clampTextureV = false,
    )
  }

  /**
   * Builds a restrained speed/dust/nitro particle streak ribbon (`0.06 m x 1.35 m`).
   */
  fun buildParticleStreakGlb(tintRgb: FloatArray): ByteArray {
    val hw = 0.035f
    val hh = 0.035f
    val hl = 0.68f
    val pos = floatArrayOf(
      -hw, 0f, -hl, -hw, 0f, hl, hw, 0f, hl, hw, 0f, -hl,
      0f, -hh, -hl, 0f, -hh, hl, 0f, hh, hl, 0f, hh, -hl,
    )
    val nrm = floatArrayOf(
      0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f, 0f,
      1f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f,
    )
    val uvs = FloatArray(16)
    val indices = shortArrayOf(
      0, 1, 2, 0, 2, 3,
      4, 5, 6, 4, 6, 7,
    )
    val r = tintRgb.getOrElse(0) { 0.85f }.toDouble()
    val g = tintRgb.getOrElse(1) { 0.92f }.toDouble()
    val b = tintRgb.getOrElse(2) { 1.0f }.toDouble()

    val matObj =
      JSONObject().apply {
        put("name", "ParticleStreakMat")
        put("doubleSided", true)
        put("alphaMode", "BLEND")
        put(
          "pbrMetallicRoughness",
          JSONObject().apply {
            put("baseColorFactor", JSONArray().put(r).put(g).put(b).put(0.42))
            put("metallicFactor", 0.0)
            put("roughnessFactor", 1.0)
          },
        )
        put("extensions", JSONObject().put("KHR_materials_unlit", JSONObject()))
      }

    return buildSingleMeshGlb(
      meshName = "ParticleStreak",
      positions = pos,
      normals = nrm,
      tangents = null,
      uvs = uvs,
      indices = indices,
      minBounds = floatArrayOf(-hw, -hh, -hl),
      maxBounds = floatArrayOf(hw, hh, hl),
      materialObj = matObj,
      includePlaceholderTexture = false,
      extensionsUsed = listOf("KHR_materials_unlit"),
      clampTextureV = false,
    )
  }

  private fun buildSingleMeshGlb(
    meshName: String,
    positions: FloatArray,
    normals: FloatArray,
    tangents: FloatArray?,
    uvs: FloatArray,
    indices: ShortArray,
    minBounds: FloatArray,
    maxBounds: FloatArray,
    materialObj: JSONObject,
    includePlaceholderTexture: Boolean,
    extensionsUsed: List<String>,
    clampTextureV: Boolean,
  ): ByteArray {
    val bin = ByteArrayOutputStream()
    val bufferViews = JSONArray()
    val accessors = JSONArray()

    fun appendView(bytes: ByteArray, target: Int): Int {
      while (bin.size() % 4 != 0) bin.write(0)
      val offset = bin.size()
      bin.write(bytes)
      val idx = bufferViews.length()
      bufferViews.put(
        JSONObject().apply {
          put("buffer", 0)
          put("byteOffset", offset)
          put("byteLength", bytes.size)
          if (target != 0) put("target", target)
        }
      )
      return idx
    }

    fun floatsToBytes(arr: FloatArray): ByteArray =
      ByteBuffer.allocate(arr.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
        arr.forEach { putFloat(it) }
      }.array()

    fun shortsToBytes(arr: ShortArray): ByteArray =
      ByteBuffer.allocate(arr.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
        arr.forEach { putShort(it) }
      }.array()

    val vCount = positions.size / 3
    val posView = appendView(floatsToBytes(positions), 34962)
    val nrmView = appendView(floatsToBytes(normals), 34962)
    val uvView = appendView(floatsToBytes(uvs), 34962)
    val idxView = appendView(shortsToBytes(indices), 34963)
    val tanView = tangents?.let { appendView(floatsToBytes(it), 34962) }
    val pngView = if (includePlaceholderTexture) appendView(PLACEHOLDER_1X1_PNG, 0) else -1

    accessors.put(
      JSONObject().apply {
        put("bufferView", posView)
        put("componentType", 5126)
        put("count", vCount)
        put("type", "VEC3")
        put("min", JSONArray().put(minBounds[0].toDouble()).put(minBounds[1].toDouble()).put(minBounds[2].toDouble()))
        put("max", JSONArray().put(maxBounds[0].toDouble()).put(maxBounds[1].toDouble()).put(maxBounds[2].toDouble()))
      }
    )
    accessors.put(
      JSONObject().apply {
        put("bufferView", nrmView); put("componentType", 5126); put("count", vCount); put("type", "VEC3")
      }
    )
    accessors.put(
      JSONObject().apply {
        put("bufferView", uvView); put("componentType", 5126); put("count", vCount); put("type", "VEC2")
      }
    )
    accessors.put(
      JSONObject().apply {
        put("bufferView", idxView); put("componentType", 5123); put("count", indices.size); put("type", "SCALAR")
      }
    )
    val tanAccIdx =
      if (tanView != null) {
        val idx = accessors.length()
        accessors.put(
          JSONObject().apply {
            put("bufferView", tanView); put("componentType", 5126); put("count", vCount); put("type", "VEC4")
          }
        )
        idx
      } else {
        -1
      }

    while (bin.size() % 4 != 0) bin.write(0)
    val binBytes = bin.toByteArray()

    val attrsObj =
      JSONObject().apply {
        put("POSITION", 0)
        put("NORMAL", 1)
        put("TEXCOORD_0", 2)
        if (tanAccIdx >= 0) put("TANGENT", tanAccIdx)
      }

    val root =
      JSONObject().apply {
        put("asset", JSONObject().put("version", "2.0").put("generator", "DRIP_RACE_SCENERY_HELPER"))
        if (extensionsUsed.isNotEmpty()) {
          put("extensionsUsed", JSONArray(extensionsUsed))
        }
        put("scene", 0)
        put("scenes", JSONArray().put(JSONObject().put("nodes", JSONArray().put(0))))
        put("nodes", JSONArray().put(JSONObject().put("name", meshName).put("mesh", 0)))
        put(
          "meshes",
          JSONArray().put(
            JSONObject().apply {
              put("name", meshName)
              put(
                "primitives",
                JSONArray().put(
                  JSONObject().apply {
                    put("attributes", attrsObj)
                    put("indices", 3)
                    put("material", 0)
                  }
                ),
              )
            }
          ),
        )
        if (pngView >= 0) {
          put(
            "samplers",
            JSONArray().put(
              JSONObject().apply {
                put("magFilter", 9729)
                put("minFilter", 9729)
                put("wrapS", 10497) // REPEAT horizontally
                put("wrapT", if (clampTextureV) 33071 else 10497) // CLAMP_TO_EDGE vertically on sky dome
              }
            ),
          )
          put(
            "images",
            JSONArray().put(
              JSONObject().put("bufferView", pngView).put("mimeType", "image/png")
            ),
          )
          put("textures", JSONArray().put(JSONObject().put("sampler", 0).put("source", 0)))
        }
        put("materials", JSONArray().put(materialObj))
        put("accessors", accessors)
        put("bufferViews", bufferViews)
        put("buffers", JSONArray().put(JSONObject().put("byteLength", binBytes.size)))
      }

    val jsonBytes = root.toString().toByteArray(StandardCharsets.UTF_8)
    val jsonPad = (4 - (jsonBytes.size % 4)) % 4
    val jsonChunkLen = jsonBytes.size + jsonPad
    val totalLen = 12 + 8 + jsonChunkLen + 8 + max(0, binBytes.size)
    val out = ByteBuffer.allocate(totalLen).order(ByteOrder.LITTLE_ENDIAN)
    out.putInt(GLB_MAGIC)
    out.putInt(2)
    out.putInt(totalLen)
    out.putInt(jsonChunkLen)
    out.putInt(JSON_CHUNK_TYPE)
    out.put(jsonBytes)
    repeat(jsonPad) { out.put(0x20.toByte()) }
    out.putInt(binBytes.size)
    out.putInt(BIN_CHUNK_TYPE)
    out.put(binBytes)
    return out.array()
  }
}
