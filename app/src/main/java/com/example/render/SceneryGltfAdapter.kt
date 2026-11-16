package com.example.render

import com.example.assets.LoadedSceneryModelPayload
import com.example.scenery.SceneryCatalog
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import org.json.JSONArray
import org.json.JSONObject

/**
 * Runtime glTF / GLB adapter for approved Phase 7 scenery assets.
 *
 * - Converts multi-file `.gltf` + `.bin` + `textures/<name>.jpg` (`rock_moss_set_01_1k.gltf`,
 *   `rock_face_02_2k.gltf`) into an in-memory self-contained `.glb` after `SceneryAssetRepository`
 *   verifies all original relative asset paths and SHA-256 digests on disk.
 * - Generates missing orthonormal `VEC4 TANGENT` attributes for normal-mapped rock/cliff meshes
 *   that omitted `TANGENT` (`noTangentWithNormalTex`), preventing black tangent-space shading.
 * - Sanitizes extensions unsupported by Filament's precompiled `UbershaderProvider` while keeping
 *   `KHR_materials_emissive_strength`, `KHR_materials_clearcoat`, and `KHR_materials_specular`.
 * - Enforces scale-dependent UV proportionality on `desert-mars-sand-1k-lod-candidate.glb` if
 *   ever placed at a scale other than its authored `10.0x` uniform scale.
 * - Never modifies any asset file on disk.
 */
object SceneryGltfAdapter {
  private const val GLB_MAGIC = 0x46546C67
  private const val JSON_CHUNK_TYPE = 0x4E4F534A
  private const val BIN_CHUNK_TYPE = 0x004E4942

  private val UNSUPPORTED_SCENERY_EXTENSIONS =
    setOf(
      "KHR_materials_anisotropy",
      "KHR_materials_iridescence",
      "KHR_materials_sheen",
    )

  fun preparePayloadForFilament(
    payload: LoadedSceneryModelPayload,
    isNightEnvironment: Boolean = false,
    desertPlacementScale: Float = SceneryCatalog.DESERT_MARS_AUTHORED_UNIFORM_SCALE,
  ): ByteArray {
    return when (payload) {
      is LoadedSceneryModelPayload.SingleGlb -> {
        prepareSingleGlb(
          assetId = payload.spec.id,
          rawGlbBytes = payload.rawGlbBytes,
          isNightEnvironment = isNightEnvironment,
          desertPlacementScale = desertPlacementScale,
        )
      }
      is LoadedSceneryModelPayload.ExternalGltf -> {
        packExternalGltfToGlb(
          gltfJsonBytes = payload.gltfJsonBytes,
          dependenciesByUri = payload.dependencyBytesByRelativeUri,
          isNightEnvironment = isNightEnvironment,
        )
      }
    }
  }

  /**
   * Computes the UV multiplier required for `desert-mars-sand-1k-lod-candidate.glb` so its
   * sand texture maintains the authored 15.0 m world period at any uniform placement scale.
   * At the authored `10.0x` scale, returns `1.0f` (zero UV modification).
   */
  fun computeDesertMarsUvScaleMultiplier(placementUniformScale: Float): Float {
    require(placementUniformScale.isFinite() && placementUniformScale > 0.01f) {
      "Invalid desert placement scale $placementUniformScale."
    }
    return placementUniformScale / SceneryCatalog.DESERT_MARS_AUTHORED_UNIFORM_SCALE
  }

  fun prepareSingleGlb(
    assetId: String,
    rawGlbBytes: ByteArray,
    isNightEnvironment: Boolean = false,
    desertPlacementScale: Float = SceneryCatalog.DESERT_MARS_AUTHORED_UNIFORM_SCALE,
  ): ByteArray =
    runCatching {
      val (root, binBytes) = parseGlb(rawGlbBytes)
      sanitizeExtensionsAndMaterials(
        root = root,
        assetId = assetId,
        isNightEnvironment = isNightEnvironment,
      )
      var updatedBin = ensureTangentsForNormalMappedMeshes(root, binBytes)

      if (assetId == SceneryCatalog.DESERT_MARS_SAND_ID) {
        val uvMultiplier = computeDesertMarsUvScaleMultiplier(desertPlacementScale)
        if (abs(uvMultiplier - 1.0f) > 1e-4f) {
          updatedBin = scaleAllTexcoord0(root, updatedBin, uvMultiplier)
        }
      }

      packGlb(root, updatedBin)
    }.getOrElse { rawGlbBytes }

  fun packExternalGltfToGlb(
    gltfJsonBytes: ByteArray,
    dependenciesByUri: Map<String, ByteArray>,
    isNightEnvironment: Boolean = false,
  ): ByteArray {
    val root = JSONObject(String(gltfJsonBytes, StandardCharsets.UTF_8))
    val buffers = root.optJSONArray("buffers") ?: error("External glTF missing buffers array.")
    require(buffers.length() >= 1) { "External glTF has empty buffers array." }

    val primaryBufObj = buffers.getJSONObject(0)
    val binUri = primaryBufObj.optString("uri", "")
    require(binUri.isNotBlank()) { "External glTF buffer[0] missing external .bin URI." }
    val rawBinBytes =
      dependenciesByUri[binUri]
        ?: error("Missing external glTF binary buffer '$binUri'.")

    val combinedBin = ByteArrayOutputStream(rawBinBytes.size + 524_288)
    combinedBin.write(rawBinBytes)
    while (combinedBin.size() % 4 != 0) combinedBin.write(0)

    // Remove URI from buffer[0] so gltfio reads from the embedded GLB BIN chunk
    primaryBufObj.remove("uri")

    // Embed external image URIs (keeping original files untouched in assets/scenery/<dir>/textures/...)
    val bufferViews =
      root.optJSONArray("bufferViews") ?: JSONArray().also { root.put("bufferViews", it) }
    val images = root.optJSONArray("images")
    if (images != null) {
      for (i in 0 until images.length()) {
        val imgObj = images.getJSONObject(i)
        val uri = imgObj.optString("uri", "")
        if (uri.isNotEmpty()) {
          val imgBytes =
            dependenciesByUri[uri]
              ?: error("Missing external glTF texture dependency '$uri'.")
          while (combinedBin.size() % 4 != 0) combinedBin.write(0)
          val offset = combinedBin.size()
          combinedBin.write(imgBytes)
          val bvIndex = bufferViews.length()
          bufferViews.put(
            JSONObject().apply {
              put("buffer", 0)
              put("byteOffset", offset)
              put("byteLength", imgBytes.size)
            }
          )
          imgObj.remove("uri")
          imgObj.put("bufferView", bvIndex)
          if (!imgObj.has("mimeType")) {
            val lower = uri.lowercase()
            imgObj.put(
              "mimeType",
              if (lower.endsWith(".png")) "image/png" else "image/jpeg",
            )
          }
        }
      }
    }

    while (combinedBin.size() % 4 != 0) combinedBin.write(0)
    val baseBin = combinedBin.toByteArray()
    primaryBufObj.put("byteLength", baseBin.size)

    sanitizeExtensionsAndMaterials(
      root = root,
      assetId = "external_gltf",
      isNightEnvironment = isNightEnvironment,
    )
    val finalBin = ensureTangentsForNormalMappedMeshes(root, baseBin)
    return packGlb(root, finalBin)
  }

  private fun sanitizeExtensionsAndMaterials(
    root: JSONObject,
    assetId: String,
    isNightEnvironment: Boolean,
  ) {
    val used = root.optJSONArray("extensionsUsed")
    if (used != null) {
      val cleanUsed = JSONArray()
      for (i in 0 until used.length()) {
        val ext = used.optString(i, "")
        if (ext.isNotEmpty() && ext !in UNSUPPORTED_SCENERY_EXTENSIONS) {
          cleanUsed.put(ext)
        }
      }
      root.put("extensionsUsed", cleanUsed)
    }

    val materials = root.optJSONArray("materials") ?: return
    for (mIdx in 0 until materials.length()) {
      val matObj = materials.optJSONObject(mIdx) ?: continue
      val exts = matObj.optJSONObject("extensions")
      if (exts != null && exts.has("KHR_materials_unlit")) continue

      if (exts != null) {
        for (unsupported in UNSUPPORTED_SCENERY_EXTENSIONS) {
          exts.remove(unsupported)
        }
        if (exts.has("KHR_materials_clearcoat")) {
          exts.remove("KHR_materials_ior")
          exts.remove("KHR_materials_volume")
        }
        if (exts.length() == 0) {
          matObj.remove("extensions")
        }
      }

      val pbr =
        matObj.optJSONObject("pbrMetallicRoughness")
          ?: JSONObject().also { matObj.put("pbrMetallicRoughness", it) }

      // Clamp omitted/high metallicFactor (=1.0 default) so steel poles, signs, guardrails & towers
      // retain strong diffuse reflectance under SH-only IndirectLight!
      val curMet = pbr.optDouble("metallicFactor", 0.0)
      val maxSafeMet =
        if (assetId == SceneryCatalog.SKYSCRAPER_ID || assetId == SceneryCatalog.CITY_SKYLINE_ID) {
          0.32
        } else {
          0.25
        }
      if (curMet > maxSafeMet) {
        pbr.put("metallicFactor", maxSafeMet)
      }

      // Boost streetlight luminaire bulb / glass emissive at night for clear highway legibility
      if (isNightEnvironment && assetId == SceneryCatalog.STREETLIGHT_ID) {
        val matName = matObj.optString("name", "").lowercase()
        val hasEmissive = matObj.has("emissiveFactor") || matObj.has("emissiveTexture")
        if (hasEmissive ||
          matName.contains("light") ||
          matName.contains("lamp") ||
          matName.contains("bulb") ||
          matName.contains("glass") ||
          matName.contains("emit")
        ) {
          matObj.put("emissiveFactor", JSONArray().put(1.0).put(0.86).put(0.62))
        }
      }
    }
  }

  /**
   * Generates unit orthonormal `VEC4` float `TANGENT` attributes whenever a triangle primitive has
   * `NORMAL` (and especially `normalTexture`) without `TANGENT`. Fixes `rock_moss_set_01_1k.gltf`
   * and `rock_face_02_2k.gltf` (`noTangentWithNormalTex`).
   */
  private fun ensureTangentsForNormalMappedMeshes(
    root: JSONObject,
    originalBin: ByteArray,
  ): ByteArray {
    val meshes = root.optJSONArray("meshes") ?: return originalBin
    val accessors = root.optJSONArray("accessors") ?: return originalBin
    val bufferViews = root.optJSONArray("bufferViews") ?: return originalBin
    val buffers = root.optJSONArray("buffers") ?: return originalBin
    if (buffers.length() == 0) return originalBin

    var needsTangent = false
    outer@ for (mIdx in 0 until meshes.length()) {
      val prims = meshes.optJSONObject(mIdx)?.optJSONArray("primitives") ?: continue
      for (pIdx in 0 until prims.length()) {
        val prim = prims.optJSONObject(pIdx) ?: continue
        if (prim.optInt("mode", 4) != 4) continue
        val attrs = prim.optJSONObject("attributes") ?: continue
        if (!attrs.has("TANGENT") && attrs.has("NORMAL")) {
          needsTangent = true
          break@outer
        }
      }
    }
    if (!needsTangent) return originalBin

    val appended = ByteArrayOutputStream(originalBin.size + 262_144)
    appended.write(originalBin)
    while (appended.size() % 4 != 0) appended.write(0)

    val srcBuf = ByteBuffer.wrap(originalBin).order(ByteOrder.LITTLE_ENDIAN)
    val tangentByNormalAcc = HashMap<Int, Int>()

    for (mIdx in 0 until meshes.length()) {
      val prims = meshes.optJSONObject(mIdx)?.optJSONArray("primitives") ?: continue
      for (pIdx in 0 until prims.length()) {
        val prim = prims.optJSONObject(pIdx) ?: continue
        if (prim.optInt("mode", 4) != 4) continue
        val attrs = prim.optJSONObject("attributes") ?: continue
        if (attrs.has("TANGENT") || !attrs.has("NORMAL")) continue

        val normAccIdx = attrs.optInt("NORMAL", -1)
        if (normAccIdx !in 0 until accessors.length()) continue
        val cached = tangentByNormalAcc[normAccIdx]
        if (cached != null) {
          attrs.put("TANGENT", cached)
          continue
        }

        val normAcc = accessors.getJSONObject(normAccIdx)
        if (normAcc.has("sparse") ||
          normAcc.optInt("componentType") != 5126 ||
          normAcc.optString("type") != "VEC3" ||
          !normAcc.has("bufferView")
        ) {
          continue
        }
        val vertexCount = normAcc.optInt("count", 0)
        if (vertexCount <= 0) continue

        val normBvIdx = normAcc.getInt("bufferView")
        if (normBvIdx !in 0 until bufferViews.length()) continue
        val normBv = bufferViews.getJSONObject(normBvIdx)
        val baseOffset = normBv.optInt("byteOffset", 0) + normAcc.optInt("byteOffset", 0)
        val stride = normBv.optInt("byteStride", 12).coerceAtLeast(12)
        val lastByte = baseOffset.toLong() + (vertexCount - 1).toLong() * stride.toLong() + 12L
        if (baseOffset < 0 || lastByte > originalBin.size.toLong()) continue

        val tanBuf = ByteBuffer.allocate(vertexCount * 16).order(ByteOrder.LITTLE_ENDIAN)
        var minTx = Float.POSITIVE_INFINITY
        var minTy = Float.POSITIVE_INFINITY
        var minTz = Float.POSITIVE_INFINITY
        var maxTx = Float.NEGATIVE_INFINITY
        var maxTy = Float.NEGATIVE_INFINITY
        var maxTz = Float.NEGATIVE_INFINITY

        for (i in 0 until vertexCount) {
          val off = baseOffset + i * stride
          val nx = srcBuf.getFloat(off)
          val ny = srcBuf.getFloat(off + 4)
          val nz = srcBuf.getFloat(off + 8)
          val nLen = sqrt(nx * nx + ny * ny + nz * nz)
          val ux = if (nLen > 1e-6f) nx / nLen else 0f
          val uy = if (nLen > 1e-6f) ny / nLen else 1f
          val uz = if (nLen > 1e-6f) nz / nLen else 0f

          // Orthonormal tangent aligned primarily along +X when normal is vertical (+Y)
          var tx: Float
          var ty: Float
          var tz: Float
          if (abs(ux) < 0.85f) {
            // cross((1,0,0), n) -> perpendicular to n; cross with n again gives tangent near +X
            tx = 1f - ux * ux
            ty = -ux * uy
            tz = -ux * uz
          } else {
            tx = -uz * ux
            ty = -uz * uy
            tz = 1f - uz * uz
          }
          val tLen = sqrt(tx * tx + ty * ty + tz * tz).coerceAtLeast(1e-6f)
          tx /= tLen
          ty /= tLen
          tz /= tLen

          tanBuf.putFloat(tx)
          tanBuf.putFloat(ty)
          tanBuf.putFloat(tz)
          tanBuf.putFloat(1.0f)

          minTx = min(minTx, tx); minTy = min(minTy, ty); minTz = min(minTz, tz)
          maxTx = max(maxTx, tx); maxTy = max(maxTy, ty); maxTz = max(maxTz, tz)
        }

        while (appended.size() % 4 != 0) appended.write(0)
        val tanOffset = appended.size()
        val tanBytes = tanBuf.array()
        appended.write(tanBytes)

        val tanBvIdx = bufferViews.length()
        bufferViews.put(
          JSONObject().apply {
            put("buffer", 0)
            put("byteOffset", tanOffset)
            put("byteLength", tanBytes.size)
            put("target", 34962)
          }
        )
        val tanAccIdx = accessors.length()
        accessors.put(
          JSONObject().apply {
            put("bufferView", tanBvIdx)
            put("componentType", 5126)
            put("count", vertexCount)
            put("type", "VEC4")
            put("min", JSONArray().put(minTx.toDouble()).put(minTy.toDouble()).put(minTz.toDouble()).put(1.0))
            put("max", JSONArray().put(maxTx.toDouble()).put(maxTy.toDouble()).put(maxTz.toDouble()).put(1.0))
          }
        )
        tangentByNormalAcc[normAccIdx] = tanAccIdx
        attrs.put("TANGENT", tanAccIdx)
      }
    }

    while (appended.size() % 4 != 0) appended.write(0)
    val outBin = appended.toByteArray()
    buffers.getJSONObject(0).put("byteLength", outBin.size)
    return outBin
  }

  private fun scaleAllTexcoord0(
    root: JSONObject,
    originalBin: ByteArray,
    uvScale: Float,
  ): ByteArray {
    val meshes = root.optJSONArray("meshes") ?: return originalBin
    val accessors = root.optJSONArray("accessors") ?: return originalBin
    val bufferViews = root.optJSONArray("bufferViews") ?: return originalBin
    val copy = originalBin.copyOf()
    val bb = ByteBuffer.wrap(copy).order(ByteOrder.LITTLE_ENDIAN)
    val scaledAccessors = HashSet<Int>()

    for (mIdx in 0 until meshes.length()) {
      val prims = meshes.optJSONObject(mIdx)?.optJSONArray("primitives") ?: continue
      for (pIdx in 0 until prims.length()) {
        val attrs = prims.optJSONObject(pIdx)?.optJSONObject("attributes") ?: continue
        val uvAccIdx = attrs.optInt("TEXCOORD_0", -1)
        if (uvAccIdx !in 0 until accessors.length() || !scaledAccessors.add(uvAccIdx)) continue
        val uvAcc = accessors.getJSONObject(uvAccIdx)
        if (uvAcc.optInt("componentType") != 5126 || uvAcc.optString("type") != "VEC2") continue
        val bv = bufferViews.getJSONObject(uvAcc.getInt("bufferView"))
        val baseOff = bv.optInt("byteOffset", 0) + uvAcc.optInt("byteOffset", 0)
        val stride = bv.optInt("byteStride", 8).coerceAtLeast(8)
        val count = uvAcc.getInt("count")
        for (i in 0 until count) {
          val off = baseOff + i * stride
          bb.putFloat(off, bb.getFloat(off) * uvScale)
          bb.putFloat(off + 4, bb.getFloat(off + 4) * uvScale)
        }
      }
    }
    return copy
  }

  internal fun parseGlb(bytes: ByteArray): Pair<JSONObject, ByteArray> {
    require(bytes.size >= 20) { "GLB too small (${bytes.size} bytes)." }
    val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    val magic = bb.int
    val version = bb.int
    val totalLen = bb.int
    require(magic == GLB_MAGIC && version == 2 && totalLen <= bytes.size) {
      "Invalid GLB header."
    }
    var jsonObj: JSONObject? = null
    var binBytes = ByteArray(0)
    while (bb.position() + 8 <= totalLen) {
      val chunkLen = bb.int
      val chunkType = bb.int
      val data = ByteArray(chunkLen)
      bb.get(data)
      if (chunkType == JSON_CHUNK_TYPE) {
        jsonObj = JSONObject(String(data, StandardCharsets.UTF_8))
      } else if (chunkType == BIN_CHUNK_TYPE) {
        binBytes = data
      }
    }
    return (jsonObj ?: error("GLB missing JSON chunk.")) to binBytes
  }

  private fun packGlb(root: JSONObject, binBytes: ByteArray): ByteArray {
    val jsonBytes = root.toString().toByteArray(StandardCharsets.UTF_8)
    val jsonPad = (4 - (jsonBytes.size % 4)) % 4
    val jsonChunkLen = jsonBytes.size + jsonPad
    val binPad = (4 - (binBytes.size % 4)) % 4
    val binChunkLen = binBytes.size + binPad
    val totalLen = 12 + 8 + jsonChunkLen + 8 + binChunkLen

    val out = ByteBuffer.allocate(totalLen).order(ByteOrder.LITTLE_ENDIAN)
    out.putInt(GLB_MAGIC)
    out.putInt(2)
    out.putInt(totalLen)
    out.putInt(jsonChunkLen)
    out.putInt(JSON_CHUNK_TYPE)
    out.put(jsonBytes)
    repeat(jsonPad) { out.put(0x20.toByte()) }
    out.putInt(binChunkLen)
    out.putInt(BIN_CHUNK_TYPE)
    out.put(binBytes)
    repeat(binPad) { out.put(0.toByte()) }
    return out.array()
  }
}
