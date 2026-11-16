package com.example.render

import android.opengl.Matrix
import com.example.garage.HeroVehicleSpec
import com.example.garage.HeroWheelMode
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import org.json.JSONArray
import org.json.JSONObject

data class PreparedHeroGlb(
  val glbBytes: ByteArray,
  val paintMaterialNames: List<String>,
  val wheelNodeNames: List<String>,
  val carrierNodeNames: List<String>,
  val nissanWheelGroupNodeNames: List<List<String>>,
  val wheelAxisChar: Char,
)

/**
 * Pure Kotlin runtime glTF 2.0 adapter porting references/adapters/:
 * - prepare-paint.mjs & paint-bindings.json (strict body-paint material isolation)
 * - prepare-playable-wheels.mjs (Starter, Veloce, Crimson -14 deg front carrier removal, Nissan, BMW)
 * - prepare-bmw-wheels.mjs (indexed triangle split across X=0 around rotor Y/Z center)
 * - prepare-nissan-wheels.mjs (node group identifiers for dynamic hub pivot attachment)
 *
 * Operates strictly on an in-memory copy AFTER SHA-256 verification; original GLB bytes on disk
 * or in APK assets are never modified.
 */
object HeroGlbRuntimeAdapter {
  private const val GLB_MAGIC = 0x46546C67
  private const val JSON_CHUNK_TYPE = 0x4E4F534A
  private const val BIN_CHUNK_TYPE = 0x004E4942
  private const val PAINT_TAG_PREFIX = "__DRIP_PAINT_"
  private const val WHEEL_TAG_PREFIX = "__DRIP_WHEEL_"
  private const val CARRIER_TAG_PREFIX = "__DRIP_CARRIER_"
  private const val NISSAN_GROUP_PREFIX = "__DRIP_NISSAN_W"

  fun prepareGlbForFilament(
    spec: HeroVehicleSpec,
    rawGlbBytes: ByteArray,
    initialPaintLinearRgb: FloatArray? = null,
  ): PreparedHeroGlb {
    val (root, binBytes) = parseGlb(rawGlbBytes)
    val materials = root.optJSONArray("materials") ?: JSONArray()
    val nodes = root.optJSONArray("nodes") ?: JSONArray()

    // 1. Tag isolated paint materials from paint-bindings.json and tune PBR metallic/roughness
    //    and Ubershader extension compatibility without recoloring non-paint parts.
    val paintIndexSet = spec.paintMaterialIndices.toSet()
    val paintNames = mutableListOf<String>()
    for (matIdx in spec.paintMaterialIndices) {
      if (matIdx in 0 until materials.length()) {
        val matObj = materials.getJSONObject(matIdx)
        val tagName = "${PAINT_TAG_PREFIX}${matIdx}__"
        matObj.put("name", tagName)
        if (initialPaintLinearRgb != null && initialPaintLinearRgb.size >= 3) {
          val pbr =
            matObj.optJSONObject("pbrMetallicRoughness")
              ?: JSONObject().also { matObj.put("pbrMetallicRoughness", it) }
          pbr.put(
            "baseColorFactor",
            JSONArray()
              .put(initialPaintLinearRgb[0].toDouble())
              .put(initialPaintLinearRgb[1].toDouble())
              .put(initialPaintLinearRgb[2].toDouble())
              .put(1.0),
          )
        }
        paintNames += tagName
      }
    }
    sanitizeExtensionsUsedArray(root)
    tuneMaterialsForDirectIbl(materials, paintIndexSet)

    // 2. Ensure every triangle primitive has explicit indices and valid VEC4 TANGENT attributes
    //    so Filament's SurfaceOrientation & Ubershader tangent-space shading never zero out normals
    //    on unindexed or tangent-less meshes (such as Crimson's monocoque & widebody fenders).
    var updatedBinBytes = ensureIndexedPrimitivesAndTangents(root, binBytes)

    val wheelNames = mutableListOf<String>()
    val carrierNames = mutableListOf<String>()
    val nissanGroups = mutableListOf<List<String>>()
    var wheelAxis = 'x'

    when (spec.wheelMode) {
      HeroWheelMode.STARTER_GROUP -> {
        wheelAxis = 'x'
        wheelNames += listOf("WheelFrontL", "WheelFrontR", "WheelRearL", "WheelRearR")
      }
      HeroWheelMode.INDEXED_NODES_X -> {
        wheelAxis = 'x'
        spec.wheelNodeGroups.forEachIndexed { idx, group ->
          val nodeIdx = group.firstOrNull() ?: return@forEachIndexed
          if (nodeIdx in 0 until nodes.length()) {
            val tag = "${WHEEL_TAG_PREFIX}${idx}__"
            nodes.getJSONObject(nodeIdx).put("name", tag)
            wheelNames += tag
          }
        }
      }
      HeroWheelMode.CRIMSON_CARRIER_YAW_AND_NODES_X -> {
        wheelAxis = 'x'
        spec.crimsonCarrierIndices.forEachIndexed { idx, carrierIdx ->
          if (carrierIdx in 0 until nodes.length()) {
            val tag = "${CARRIER_TAG_PREFIX}${idx}__"
            nodes.getJSONObject(carrierIdx).put("name", tag)
            carrierNames += tag
          }
        }
        spec.wheelNodeGroups.forEachIndexed { idx, group ->
          val nodeIdx = group.firstOrNull() ?: return@forEachIndexed
          if (nodeIdx in 0 until nodes.length()) {
            val tag = "${WHEEL_TAG_PREFIX}${idx}__"
            nodes.getJSONObject(nodeIdx).put("name", tag)
            wheelNames += tag
          }
        }
      }
      HeroWheelMode.NISSAN_PIVOT_GROUPS_Z -> {
        wheelAxis = 'z'
        spec.wheelNodeGroups.forEachIndexed { gIdx, indices ->
          val groupNames = mutableListOf<String>()
          indices.forEachIndexed { kIdx, nodeIdx ->
            if (nodeIdx in 0 until nodes.length()) {
              val tag = "${NISSAN_GROUP_PREFIX}${gIdx}_${kIdx}__"
              nodes.getJSONObject(nodeIdx).put("name", tag)
              groupNames += tag
            }
          }
          if (groupNames.isNotEmpty()) {
            nissanGroups += groupNames
          }
        }
      }
      HeroWheelMode.BMW_SPLIT_HALVES_X -> {
        wheelAxis = 'x'
        val splitResult = splitBmwAxleMeshesAlongX(root, updatedBinBytes, spec.wheelNodeGroups)
        updatedBinBytes = splitResult.first
        wheelNames += splitResult.second
      }
    }

    val rebuilt = packGlb(root, updatedBinBytes)
    return PreparedHeroGlb(
      glbBytes = rebuilt,
      paintMaterialNames = paintNames,
      wheelNodeNames = wheelNames,
      carrierNodeNames = carrierNames,
      nissanWheelGroupNodeNames = nissanGroups,
      wheelAxisChar = wheelAxis,
    )
  }

  /**
   * Normalizes in-memory traffic GLB PBR metallic factors and Ubershader extensions so vehicles
   * whose glTF JSON omitted `metallicFactor` (glTF default = 1.0) retain diffuse reflectance
   * under SH irradiance IBL. Original asset files on disk are never modified.
   */
  fun normalizeGlbMaterialsForDirectIbl(rawGlbBytes: ByteArray): ByteArray =
    runCatching {
      val (root, binBytes) = parseGlb(rawGlbBytes)
      val materials = root.optJSONArray("materials") ?: return rawGlbBytes
      sanitizeExtensionsUsedArray(root)
      tuneMaterialsForDirectIbl(
        materials = materials,
        paintIndices = emptySet(),
        maxNonPaintMetallic = MAX_TRAFFIC_METALLIC_FOR_SH_IBL,
      )
      packGlb(root, binBytes)
    }.getOrElse { rawGlbBytes }

  internal const val MAX_PAINT_METALLIC_FOR_SH_IBL = 0.26
  internal const val MAX_TRIM_METALLIC_FOR_SH_IBL = 0.35
  internal const val MAX_TRAFFIC_METALLIC_FOR_SH_IBL = 0.22

  private val UNSUPPORTED_UBERSHADER_EXTENSIONS =
    setOf(
      "KHR_materials_anisotropy",
      "KHR_materials_iridescence",
      "KHR_materials_sheen",
    )

  private fun sanitizeExtensionsUsedArray(root: JSONObject) {
    val used = root.optJSONArray("extensionsUsed") ?: return
    val filtered = JSONArray()
    for (i in 0 until used.length()) {
      val ext = used.optString(i, "")
      if (ext.isNotEmpty() && ext !in UNSUPPORTED_UBERSHADER_EXTENSIONS) {
        filtered.put(ext)
      }
    }
    root.put("extensionsUsed", filtered)
  }

  private fun tuneMaterialsForDirectIbl(
    materials: JSONArray,
    paintIndices: Set<Int>,
    maxNonPaintMetallic: Double = MAX_TRIM_METALLIC_FOR_SH_IBL,
  ) {
    for (matIdx in 0 until materials.length()) {
      val matObj = materials.optJSONObject(matIdx) ?: continue
      val exts = matObj.optJSONObject("extensions")
      if (exts != null && exts.has("KHR_materials_unlit")) continue

      // Strip extensions unsupported by Filament's precompiled UbershaderArchive or conflicting
      // with KHR_materials_clearcoat (which otherwise fall back to solid black default material).
      if (exts != null) {
        for (unsupported in UNSUPPORTED_UBERSHADER_EXTENSIONS) {
          exts.remove(unsupported)
        }
        if (exts.has("KHR_materials_clearcoat")) {
          exts.remove("KHR_materials_ior")
          exts.remove("KHR_materials_volume")
          if (matObj.optString("alphaMode", "OPAQUE") == "BLEND") {
            exts.remove("KHR_materials_transmission")
          }
          // Match repaired Veloce clearcoat paint structure when dual high-frequency normal maps exist
          val ccObj = exts.optJSONObject("KHR_materials_clearcoat")
          if (matIdx in paintIndices && ccObj != null && ccObj.has("clearcoatNormalTexture") && matObj.has("normalTexture")) {
            matObj.remove("normalTexture")
          }
        }
        if (exts.length() == 0) {
          matObj.remove("extensions")
        }
      }

      val pbr =
        matObj.optJSONObject("pbrMetallicRoughness")
          ?: JSONObject().also { matObj.put("pbrMetallicRoughness", it) }

      if (matIdx in paintIndices) {
        // Isolated body paint: glTF defaults omitted metallicFactor to 1.0 which zeroes (1 - metallic)
        // diffuse color without a cubemap. Clamp to 0.26 so all 7 body paints render brightly with
        // rich diffuse pigment + clearcoat/GGX specular highlights.
        val curMet = pbr.optDouble("metallicFactor", 1.0)
        pbr.put("metallicFactor", min(curMet, MAX_PAINT_METALLIC_FOR_SH_IBL))
        val curRough = pbr.optDouble("roughnessFactor", 0.26)
        pbr.put("roughnessFactor", curRough.coerceIn(0.20, 0.32))
      } else {
        // Non-paint materials: never touch baseColorFactor, baseColorTexture, emissiveFactor, or glass
        // transparency. Clamp high/omitted metallicFactor so rims, calipers, rotors, and traffic bodies
        // retain strong diffuse SH reflectance.
        val curMet = pbr.optDouble("metallicFactor", 1.0)
        if (curMet > maxNonPaintMetallic) {
          pbr.put("metallicFactor", maxNonPaintMetallic)
        }
        val isTransmissive =
          (exts != null && exts.has("KHR_materials_transmission")) ||
            matObj.optString("alphaMode", "OPAQUE") == "BLEND"
        if (!isTransmissive && !pbr.has("metallicRoughnessTexture") && pbr.has("roughnessFactor")) {
          val curRough = pbr.optDouble("roughnessFactor", 0.5)
          if (curRough < 0.12) {
            pbr.put("roughnessFactor", 0.16)
          }
        }
      }
    }
  }

  /**
   * Repairs unindexed triangle primitives (adding sequential `ELEMENT_ARRAY_BUFFER` indices) and
   * missing `TANGENT` attributes (generating unit orthonormal `VEC4` float tangents orthogonal to
   * `NORMAL`) in-memory so Filament's `SurfaceOrientation` never leaves `VertexAttribute.TANGENTS`
   * zeroed out on CAD/procedural meshes such as `crimson-v8-shaker-muscle-car.glb`.
   */
  private fun ensureIndexedPrimitivesAndTangents(
    root: JSONObject,
    originalBin: ByteArray,
  ): ByteArray {
    val meshes = root.optJSONArray("meshes") ?: return originalBin
    val accessors = root.optJSONArray("accessors") ?: return originalBin
    val bufferViews = root.optJSONArray("bufferViews") ?: return originalBin
    val buffers = root.optJSONArray("buffers") ?: return originalBin
    if (buffers.length() == 0) return originalBin

    var needsRepair = false
    outer@ for (mIdx in 0 until meshes.length()) {
      val prims = meshes.optJSONObject(mIdx)?.optJSONArray("primitives") ?: continue
      for (pIdx in 0 until prims.length()) {
        val prim = prims.optJSONObject(pIdx) ?: continue
        if (prim.optInt("mode", 4) != 4) continue
        val attrs = prim.optJSONObject("attributes") ?: continue
        if (!prim.has("indices") || (!attrs.has("TANGENT") && attrs.has("NORMAL"))) {
          needsRepair = true
          break@outer
        }
      }
    }
    if (!needsRepair) return originalBin

    val appendedBin = ByteArrayOutputStream(originalBin.size + 262_144)
    appendedBin.write(originalBin)
    while (appendedBin.size() % 4 != 0) appendedBin.write(0)

    val srcBuf = ByteBuffer.wrap(originalBin).order(ByteOrder.LITTLE_ENDIAN)
    val tangentAccByNormalAcc = HashMap<Int, Int>()

    for (mIdx in 0 until meshes.length()) {
      val prims = meshes.optJSONObject(mIdx)?.optJSONArray("primitives") ?: continue
      for (pIdx in 0 until prims.length()) {
        val prim = prims.optJSONObject(pIdx) ?: continue
        if (prim.optInt("mode", 4) != 4) continue
        val attrs = prim.optJSONObject("attributes") ?: continue
        val posAccIdx = attrs.optInt("POSITION", -1)
        if (posAccIdx !in 0 until accessors.length()) continue
        val vertexCount = accessors.getJSONObject(posAccIdx).optInt("count", 0)
        if (vertexCount <= 0) continue

        // A. Synthesize sequential triangle index buffer for unindexed primitives
        if (!prim.has("indices") && vertexCount >= 3) {
          while (appendedBin.size() % 4 != 0) appendedBin.write(0)
          val byteOffset = appendedBin.size()
          val useShort = vertexCount <= 65_535
          val idxBytes =
            if (useShort) {
              ByteBuffer.allocate(vertexCount * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
                for (v in 0 until vertexCount) putShort(v.toShort())
              }.array()
            } else {
              ByteBuffer.allocate(vertexCount * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
                for (v in 0 until vertexCount) putInt(v)
              }.array()
            }
          appendedBin.write(idxBytes)
          val bvIdx = bufferViews.length()
          bufferViews.put(
            JSONObject().apply {
              put("buffer", 0)
              put("byteOffset", byteOffset)
              put("byteLength", idxBytes.size)
              put("target", 34963)
            }
          )
          val accIdx = accessors.length()
          accessors.put(
            JSONObject().apply {
              put("bufferView", bvIdx)
              put("componentType", if (useShort) 5123 else 5125)
              put("count", vertexCount)
              put("type", "SCALAR")
              put("min", JSONArray().put(0))
              put("max", JSONArray().put(vertexCount - 1))
            }
          )
          prim.put("indices", accIdx)
        }

        // B. Synthesize unit orthonormal VEC4 float TANGENT attribute from VEC3 float NORMAL
        if (!attrs.has("TANGENT") && attrs.has("NORMAL")) {
          val normAccIdx = attrs.optInt("NORMAL", -1)
          if (normAccIdx !in 0 until accessors.length()) continue
          val cachedTanAcc = tangentAccByNormalAcc[normAccIdx]
          if (cachedTanAcc != null) {
            attrs.put("TANGENT", cachedTanAcc)
            continue
          }
          val normAcc = accessors.getJSONObject(normAccIdx)
          if (normAcc.has("sparse") ||
            normAcc.optInt("componentType") != 5126 ||
            normAcc.optString("type") != "VEC3" ||
            !normAcc.has("bufferView") ||
            normAcc.optInt("count", 0) != vertexCount
          ) {
            continue
          }
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
            val nLen = kotlin.math.sqrt(nx * nx + ny * ny + nz * nz)
            val ux = if (nLen > 1e-6f) nx / nLen else 0f
            val uy = if (nLen > 1e-6f) ny / nLen else 1f
            val uz = if (nLen > 1e-6f) nz / nLen else 0f

            var tx: Float
            var ty: Float
            var tz: Float
            if (abs(uz) < 0.9f) {
              tx = uy
              ty = -ux
              tz = 0f
            } else {
              tx = 0f
              ty = uz
              tz = -uy
            }
            val tLen = kotlin.math.sqrt(tx * tx + ty * ty + tz * tz).coerceAtLeast(1e-6f)
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

          while (appendedBin.size() % 4 != 0) appendedBin.write(0)
          val tanByteOffset = appendedBin.size()
          val tanBytes = tanBuf.array()
          appendedBin.write(tanBytes)

          val tanBvIdx = bufferViews.length()
          bufferViews.put(
            JSONObject().apply {
              put("buffer", 0)
              put("byteOffset", tanByteOffset)
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
          tangentAccByNormalAcc[normAccIdx] = tanAccIdx
          attrs.put("TANGENT", tanAccIdx)
        }
      }
    }

    while (appendedBin.size() % 4 != 0) appendedBin.write(0)
    val finalBin = appendedBin.toByteArray()
    buffers.getJSONObject(0).put("byteLength", finalBin.size)
    return finalBin
  }

  private fun parseGlb(bytes: ByteArray): Pair<JSONObject, ByteArray> {
    require(bytes.size >= 20) { "GLB too small (${bytes.size} bytes)." }
    val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    val magic = bb.int
    val version = bb.int
    val totalLength = bb.int
    require(magic == GLB_MAGIC && version == 2) { "Unsupported glTF binary header." }
    require(totalLength <= bytes.size) { "Truncated GLB length." }

    var jsonObj: JSONObject? = null
    var binChunk = ByteArray(0)
    while (bb.position() + 8 <= totalLength) {
      val chunkLen = bb.int
      val chunkType = bb.int
      require(chunkLen >= 0 && bb.position() + chunkLen <= totalLength) { "Invalid GLB chunk bounds." }
      val data = ByteArray(chunkLen)
      bb.get(data)
      if (chunkType == JSON_CHUNK_TYPE) {
        jsonObj = JSONObject(String(data, StandardCharsets.UTF_8))
      } else if (chunkType == BIN_CHUNK_TYPE) {
        binChunk = data
      }
    }
    return (jsonObj ?: error("GLB missing JSON chunk.")) to binChunk
  }

  private fun packGlb(root: JSONObject, binBytes: ByteArray): ByteArray {
    val jsonBytes = root.toString().toByteArray(StandardCharsets.UTF_8)
    val jsonPad = (4 - (jsonBytes.size % 4)) % 4
    val jsonChunkLen = jsonBytes.size + jsonPad
    val binPad = (4 - (binBytes.size % 4)) % 4
    val binChunkLen = binBytes.size + binPad

    val hasBin = binChunkLen > 0
    val totalLen = 12 + 8 + jsonChunkLen + (if (hasBin) 8 + binChunkLen else 0)
    val out = ByteBuffer.allocate(totalLen).order(ByteOrder.LITTLE_ENDIAN)
    out.putInt(GLB_MAGIC)
    out.putInt(2)
    out.putInt(totalLen)
    out.putInt(jsonChunkLen)
    out.putInt(JSON_CHUNK_TYPE)
    out.put(jsonBytes)
    repeat(jsonPad) { out.put(0x20.toByte()) }
    if (hasBin) {
      out.putInt(binChunkLen)
      out.putInt(BIN_CHUNK_TYPE)
      out.put(binBytes)
      repeat(binPad) { out.put(0.toByte()) }
    }
    return out.array()
  }

  /**
   * Exact Kotlin port of references/adapters/prepare-bmw-wheels.mjs:
   * Splits each axle's indexed triangles by X > 0 in scene coordinates into side 0 (right/minX)
   * and side 1 (left/maxX), computes each half's rotor (Y, Z) center + tire X center as pivot,
   * reparents shifted meshes under 4 independent wheel pivot nodes attached to scene root, and
   * removes unsplit mesh references from Object_145..164 so wheels never double-render.
   */
  private fun splitBmwAxleMeshesAlongX(
    root: JSONObject,
    originalBin: ByteArray,
    axleGroups: List<List<Int>>,
  ): Pair<ByteArray, List<String>> {
    val nodes = root.getJSONArray("nodes")
    val meshes = root.getJSONArray("meshes")
    val accessors = root.getJSONArray("accessors")
    val bufferViews = root.getJSONArray("bufferViews")
    val buffers = root.getJSONArray("buffers")

    val scenes = root.getJSONArray("scenes")
    val sceneIndex = root.optInt("scene", 0)
    val sceneObj = scenes.getJSONObject(sceneIndex)
    val sceneRootNodes = sceneObj.getJSONArray("nodes")

    val worldMatrices = computeAllNodeWorldMatrices(nodes, sceneRootNodes)
    val nodeByName = HashMap<String, Int>(nodes.length())
    for (i in 0 until nodes.length()) {
      val nm = nodes.getJSONObject(i).optString("name", "")
      if (nm.isNotEmpty()) nodeByName[nm] = i
    }

    val appendedBin = ByteArrayOutputStream()
    appendedBin.write(originalBin)
    while (appendedBin.size() % 4 != 0) appendedBin.write(0)

    val createdWheelNames = mutableListOf<String>()

    axleGroups.forEachIndexed { axleIdx, objectNumbers ->
      // sides[0] (X <= 0) and sides[1] (X > 0)
      val halves = arrayOf(mutableListOf<BmwSplitPart>(), mutableListOf<BmwSplitPart>())

      objectNumbers.forEach { objNum ->
        val nodeIdx = nodeByName["Object_$objNum"]
          ?: error("Missing BMW wheel node Object_$objNum")
        val nodeObj = nodes.getJSONObject(nodeIdx)
        val meshIdx = nodeObj.optInt("mesh", -1)
        require(meshIdx >= 0) { "Object_$objNum missing mesh." }
        val meshObj = meshes.getJSONObject(meshIdx)
        val primObj = meshObj.getJSONArray("primitives").getJSONObject(0)
        val posAccessorIdx = primObj.getJSONObject("attributes").getInt("POSITION")
        val idxAccessorIdx = primObj.getInt("indices")

        val worldMat = worldMatrices[nodeIdx] ?: FloatArray(16).apply { Matrix.setIdentityM(this, 0) }
        val worldPositions = readVec3AccessorTransformed(originalBin, bufferViews, accessors.getJSONObject(posAccessorIdx), worldMat)
        val triIndices = readScalarIndexAccessor(originalBin, bufferViews, accessors.getJSONObject(idxAccessorIdx))

        val sideLists = arrayOf(ArrayList<Int>(triIndices.size / 2), ArrayList<Int>(triIndices.size / 2))
        val sideMin = arrayOf(
          floatArrayOf(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
          floatArrayOf(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
        )
        val sideMax = arrayOf(
          floatArrayOf(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY),
          floatArrayOf(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY),
        )

        var k = 0
        while (k + 2 < triIndices.size) {
          val i0 = triIndices[k]
          val i1 = triIndices[k + 1]
          val i2 = triIndices[k + 2]
          val x0 = worldPositions[i0 * 3]
          val x1 = worldPositions[i1 * 3]
          val x2 = worldPositions[i2 * 3]
          val side = if (x0 > 0f && x1 > 0f && x2 > 0f) 1 else 0
          val target = sideLists[side]
          target.add(i0)
          target.add(i1)
          target.add(i2)
          for (vi in intArrayOf(i0, i1, i2)) {
            val wx = worldPositions[vi * 3]
            val wy = worldPositions[vi * 3 + 1]
            val wz = worldPositions[vi * 3 + 2]
            sideMin[side][0] = min(sideMin[side][0], wx)
            sideMin[side][1] = min(sideMin[side][1], wy)
            sideMin[side][2] = min(sideMin[side][2], wz)
            sideMax[side][0] = max(sideMax[side][0], wx)
            sideMax[side][1] = max(sideMax[side][1], wy)
            sideMax[side][2] = max(sideMax[side][2], wz)
          }
          k += 3
        }

        for (side in 0..1) {
          val indicesList = sideLists[side]
          if (indicesList.isEmpty()) continue
          // Append new UNSIGNED_INT index buffer view + accessor referencing the ORIGINAL attributes & material
          while (appendedBin.size() % 4 != 0) appendedBin.write(0)
          val byteOffset = appendedBin.size()
          val idxBuf = ByteBuffer.allocate(indicesList.size * 4).order(ByteOrder.LITTLE_ENDIAN)
          for (v in indicesList) idxBuf.putInt(v)
          val idxBytes = idxBuf.array()
          appendedBin.write(idxBytes)

          val bvIdx = bufferViews.length()
          bufferViews.put(
            JSONObject().apply {
              put("buffer", 0)
              put("byteOffset", byteOffset)
              put("byteLength", idxBytes.size)
              put("target", 34963)
            }
          )
          val accIdx = accessors.length()
          accessors.put(
            JSONObject().apply {
              put("bufferView", bvIdx)
              put("componentType", 5125) // UNSIGNED_INT
              put("count", indicesList.size)
              put("type", "SCALAR")
            }
          )
          val newPrim = JSONObject(primObj.toString()).apply {
            put("indices", accIdx)
          }
          val newMeshIdx = meshes.length()
          meshes.put(
            JSONObject().apply {
              put("name", "Object_${objNum}_side$side")
              put("primitives", JSONArray().put(newPrim))
            }
          )
          halves[side] +=
            BmwSplitPart(
              meshIndex = newMeshIdx,
              worldMatrix = worldMat,
              centerX = (sideMin[side][0] + sideMax[side][0]) * 0.5f,
              centerY = (sideMin[side][1] + sideMax[side][1]) * 0.5f,
              centerZ = (sideMin[side][2] + sideMax[side][2]) * 0.5f,
              isTire = objNum == objectNumbers[4],
              isRotor = objNum == objectNumbers[1],
            )
        }

        // Detach original full-axle mesh from Object_$objNum (preserves node hierarchy without rendering duplicate)
        nodeObj.remove("mesh")
      }

      for (side in 0..1) {
        val parts = halves[side]
        if (parts.isEmpty()) continue
        val tirePart = parts.firstOrNull { it.isTire } ?: parts.first()
        val rotorPart = parts.firstOrNull { it.isRotor } ?: tirePart
        val pivotX = tirePart.centerX
        val pivotY = rotorPart.centerY
        val pivotZ = rotorPart.centerZ

        val childNodeIndices = JSONArray()
        for (part in parts) {
          // Compute local matrix relative to wheel pivot: T(-pivot) * part.worldMatrix
          val childMat = FloatArray(16)
          val negPivot = FloatArray(16)
          Matrix.setIdentityM(negPivot, 0)
          Matrix.translateM(negPivot, 0, -pivotX, -pivotY, -pivotZ)
          Matrix.multiplyMM(childMat, 0, negPivot, 0, part.worldMatrix, 0)

          val childNodeIdx = nodes.length()
          nodes.put(
            JSONObject().apply {
              put("mesh", part.meshIndex)
              put("matrix", floatArrayToJsonArray(childMat))
            }
          )
          childNodeIndices.put(childNodeIdx)
        }

        val wheelTag = "${WHEEL_TAG_PREFIX}${axleIdx * 2 + side}__"
        val pivotNodeIdx = nodes.length()
        nodes.put(
          JSONObject().apply {
            put("name", wheelTag)
            put("translation", JSONArray().put(pivotX.toDouble()).put(pivotY.toDouble()).put(pivotZ.toDouble()))
            put("children", childNodeIndices)
          }
        )
        sceneRootNodes.put(pivotNodeIdx)
        createdWheelNames += wheelTag
      }
    }

    while (appendedBin.size() % 4 != 0) appendedBin.write(0)
    val finalBin = appendedBin.toByteArray()
    buffers.getJSONObject(0).put("byteLength", finalBin.size)
    return finalBin to createdWheelNames
  }

  private data class BmwSplitPart(
    val meshIndex: Int,
    val worldMatrix: FloatArray,
    val centerX: Float,
    val centerY: Float,
    val centerZ: Float,
    val isTire: Boolean,
    val isRotor: Boolean,
  )

  private fun computeAllNodeWorldMatrices(nodes: JSONArray, rootNodes: JSONArray): Array<FloatArray?> {
    val result = arrayOfNulls<FloatArray>(nodes.length())
    val identity = FloatArray(16).apply { Matrix.setIdentityM(this, 0) }

    fun walk(nodeIdx: Int, parentWorld: FloatArray) {
      val n = nodes.getJSONObject(nodeIdx)
      val local = readLocalNodeMatrix(n)
      val world = FloatArray(16)
      Matrix.multiplyMM(world, 0, parentWorld, 0, local, 0)
      result[nodeIdx] = world
      val children = n.optJSONArray("children")
      if (children != null) {
        for (c in 0 until children.length()) {
          walk(children.getInt(c), world)
        }
      }
    }

    for (i in 0 until rootNodes.length()) {
      walk(rootNodes.getInt(i), identity)
    }
    return result
  }

  private fun readLocalNodeMatrix(node: JSONObject): FloatArray {
    val out = FloatArray(16)
    val matArr = node.optJSONArray("matrix")
    if (matArr != null && matArr.length() == 16) {
      for (i in 0 until 16) out[i] = matArr.getDouble(i).toFloat()
      return out
    }
    Matrix.setIdentityM(out, 0)
    val tr = node.optJSONArray("translation")
    if (tr != null && tr.length() == 3) {
      Matrix.translateM(out, 0, tr.getDouble(0).toFloat(), tr.getDouble(1).toFloat(), tr.getDouble(2).toFloat())
    }
    val rot = node.optJSONArray("rotation")
    if (rot != null && rot.length() == 4) {
      val qx = rot.getDouble(0).toFloat()
      val qy = rot.getDouble(1).toFloat()
      val qz = rot.getDouble(2).toFloat()
      val qw = rot.getDouble(3).toFloat()
      val rotMat = quaternionToColumnMajorMatrix(qx, qy, qz, qw)
      val tmp = FloatArray(16)
      Matrix.multiplyMM(tmp, 0, out, 0, rotMat, 0)
      System.arraycopy(tmp, 0, out, 0, 16)
    }
    val scl = node.optJSONArray("scale")
    if (scl != null && scl.length() == 3) {
      Matrix.scaleM(out, 0, scl.getDouble(0).toFloat(), scl.getDouble(1).toFloat(), scl.getDouble(2).toFloat())
    }
    return out
  }

  private fun quaternionToColumnMajorMatrix(x: Float, y: Float, z: Float, w: Float): FloatArray {
    val xx = x * x
    val yy = y * y
    val zz = z * z
    val xy = x * y
    val xz = x * z
    val yz = y * z
    val wx = w * x
    val wy = w * y
    val wz = w * z
    return floatArrayOf(
      1f - 2f * (yy + zz), 2f * (xy + wz), 2f * (xz - wy), 0f,
      2f * (xy - wz), 1f - 2f * (xx + zz), 2f * (yz + wx), 0f,
      2f * (xz + wy), 2f * (yz - wx), 1f - 2f * (xx + yy), 0f,
      0f, 0f, 0f, 1f,
    )
  }

  private fun floatArrayToJsonArray(arr: FloatArray): JSONArray =
    JSONArray().apply { arr.forEach { put(it.toDouble()) } }

  private fun readVec3AccessorTransformed(
    bin: ByteArray,
    bufferViews: JSONArray,
    accessor: JSONObject,
    worldMat: FloatArray,
  ): FloatArray {
    val bvIdx = accessor.getInt("bufferView")
    val bv = bufferViews.getJSONObject(bvIdx)
    val baseOffset = bv.optInt("byteOffset", 0) + accessor.optInt("byteOffset", 0)
    val stride = bv.optInt("byteStride", 12).coerceAtLeast(12)
    val count = accessor.getInt("count")
    val bb = ByteBuffer.wrap(bin).order(ByteOrder.LITTLE_ENDIAN)
    val out = FloatArray(count * 3)
    val inPt = FloatArray(4)
    val outPt = FloatArray(4)
    for (i in 0 until count) {
      val off = baseOffset + i * stride
      inPt[0] = bb.getFloat(off)
      inPt[1] = bb.getFloat(off + 4)
      inPt[2] = bb.getFloat(off + 8)
      inPt[3] = 1f
      Matrix.multiplyMV(outPt, 0, worldMat, 0, inPt, 0)
      out[i * 3] = outPt[0]
      out[i * 3 + 1] = outPt[1]
      out[i * 3 + 2] = outPt[2]
    }
    return out
  }

  private fun readScalarIndexAccessor(
    bin: ByteArray,
    bufferViews: JSONArray,
    accessor: JSONObject,
  ): IntArray {
    val bvIdx = accessor.getInt("bufferView")
    val bv = bufferViews.getJSONObject(bvIdx)
    val baseOffset = bv.optInt("byteOffset", 0) + accessor.optInt("byteOffset", 0)
    val compType = accessor.getInt("componentType")
    val count = accessor.getInt("count")
    val bb = ByteBuffer.wrap(bin).order(ByteOrder.LITTLE_ENDIAN)
    val elemSize =
      when (compType) {
        5121 -> 1
        5123 -> 2
        else -> 4
      }
    val stride = bv.optInt("byteStride", elemSize).coerceAtLeast(elemSize)
    val out = IntArray(count)
    for (i in 0 until count) {
      val off = baseOffset + i * stride
      out[i] =
        when (compType) {
          5121 -> bb.get(off).toInt() and 0xFF
          5123 -> bb.getShort(off).toInt() and 0xFFFF
          else -> bb.getInt(off)
        }
    }
    return out
  }
}

/**
 * Honest procedural hero fallback GLB used ONLY when a genuine GLB (such as NoAI-restricted Nissan
 * before user selection on device) has not yet been imported locally. Never labeled as the genuine model.
 */
object ProceduralHeroGlbBuilder {
  private data class BoxSpec(
    val minX: Float,
    val maxX: Float,
    val minY: Float,
    val maxY: Float,
    val minZ: Float,
    val maxZ: Float,
    val materialIndex: Int,
  )

  fun buildFallbackHeroGlb(spec: HeroVehicleSpec, paintLinearRgb: FloatArray): PreparedHeroGlb {
    val hw = spec.collisionWidthMeters * 0.5f
    val h = spec.collisionHeightMeters
    val hl = spec.targetLengthMeters * 0.5f

    val boxes =
      listOf(
        // Sculpted lower body & aero splitter (material 0 = paint)
        BoxSpec(-hw * 0.96f, hw * 0.96f, 0.16f, h * 0.52f, -hl, hl, 0),
        BoxSpec(-hw * 0.86f, hw * 0.86f, h * 0.50f, h * 0.62f, -hl * 0.92f, hl * 0.72f, 0),
        // Cabin smoked glass (material 1 = glass)
        BoxSpec(-hw * 0.76f, hw * 0.76f, h * 0.56f, h, -hl * 0.45f, hl * 0.25f, 1),
        // Rear aero wing (material 0 = paint)
        BoxSpec(-hw * 0.90f, hw * 0.90f, h * 0.74f, h * 0.82f, -hl * 0.96f, -hl * 0.76f, 0),
        // Headlights (material 2 = light)
        BoxSpec(-hw * 0.82f, -hw * 0.48f, h * 0.34f, h * 0.44f, hl - 0.04f, hl, 2),
        BoxSpec(hw * 0.48f, hw * 0.82f, h * 0.34f, h * 0.44f, hl - 0.04f, hl, 2),
        // Brake lights (material 3 = Brakelight)
        BoxSpec(-hw * 0.82f, -hw * 0.35f, h * 0.36f, h * 0.46f, -hl, -hl + 0.04f, 3),
        BoxSpec(hw * 0.35f, hw * 0.82f, h * 0.36f, h * 0.46f, -hl, -hl + 0.04f, 3),
      )

    val wheelR = 0.34f
    val wheelW = 0.28f
    val wheelCenters =
      listOf(
        Triple(-hw + wheelW * 0.5f, wheelR, hl * 0.62f),
        Triple(hw - wheelW * 0.5f, wheelR, hl * 0.62f),
        Triple(-hw + wheelW * 0.5f, wheelR, -hl * 0.62f),
        Triple(hw - wheelW * 0.5f, wheelR, -hl * 0.62f),
      )

    val binStream = ByteArrayOutputStream()
    val bufferViews = JSONArray()
    val accessors = JSONArray()
    val meshes = JSONArray()
    val nodes = JSONArray()

    fun appendView(bytes: ByteArray, target: Int): Int {
      while (binStream.size() % 4 != 0) binStream.write(0)
      val off = binStream.size()
      binStream.write(bytes)
      val idx = bufferViews.length()
      bufferViews.put(
        JSONObject().apply {
          put("buffer", 0)
          put("byteOffset", off)
          put("byteLength", bytes.size)
          put("target", target)
        }
      )
      return idx
    }

    fun addMeshForBoxes(meshName: String, groupBoxes: List<BoxSpec>, materialIndex: Int): Int {
      val pos = ArrayList<Float>()
      val nrm = ArrayList<Float>()
      val indices = ArrayList<Short>()
      var minX = Float.POSITIVE_INFINITY
      var minY = Float.POSITIVE_INFINITY
      var minZ = Float.POSITIVE_INFINITY
      var maxX = Float.NEGATIVE_INFINITY
      var maxY = Float.NEGATIVE_INFINITY
      var maxZ = Float.NEGATIVE_INFINITY

      fun v(x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float) {
        pos.add(x); pos.add(y); pos.add(z)
        nrm.add(nx); nrm.add(ny); nrm.add(nz)
        minX = min(minX, x); minY = min(minY, y); minZ = min(minZ, z)
        maxX = max(maxX, x); maxY = max(maxY, y); maxZ = max(maxZ, z)
      }

      fun q(
        x0: Float, y0: Float, z0: Float,
        x1: Float, y1: Float, z1: Float,
        x2: Float, y2: Float, z2: Float,
        x3: Float, y3: Float, z3: Float,
        nx: Float, ny: Float, nz: Float,
      ) {
        val base = pos.size / 3
        v(x0, y0, z0, nx, ny, nz)
        v(x1, y1, z1, nx, ny, nz)
        v(x2, y2, z2, nx, ny, nz)
        v(x3, y3, z3, nx, ny, nz)
        indices.add(base.toShort()); indices.add((base + 1).toShort()); indices.add((base + 2).toShort())
        indices.add(base.toShort()); indices.add((base + 2).toShort()); indices.add((base + 3).toShort())
      }

      for (b in groupBoxes) {
        q(b.minX, b.maxY, b.minZ, b.minX, b.maxY, b.maxZ, b.maxX, b.maxY, b.maxZ, b.maxX, b.maxY, b.minZ, 0f, 1f, 0f)
        q(b.minX, b.minY, b.maxZ, b.minX, b.minY, b.minZ, b.maxX, b.minY, b.minZ, b.maxX, b.minY, b.maxZ, 0f, -1f, 0f)
        q(b.minX, b.minY, b.maxZ, b.maxX, b.minY, b.maxZ, b.maxX, b.maxY, b.maxZ, b.minX, b.maxY, b.maxZ, 0f, 0f, 1f)
        q(b.maxX, b.minY, b.minZ, b.minX, b.minY, b.minZ, b.minX, b.maxY, b.minZ, b.maxX, b.maxY, b.minZ, 0f, 0f, -1f)
        q(b.maxX, b.minY, b.maxZ, b.maxX, b.minY, b.minZ, b.maxX, b.maxY, b.minZ, b.maxX, b.maxY, b.maxZ, 1f, 0f, 0f)
        q(b.minX, b.minY, b.minZ, b.minX, b.minY, b.maxZ, b.minX, b.maxY, b.maxZ, b.minX, b.maxY, b.minZ, -1f, 0f, 0f)
      }

      val pBuf = ByteBuffer.allocate(pos.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply { pos.forEach { putFloat(it) } }.array()
      val nBuf = ByteBuffer.allocate(nrm.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply { nrm.forEach { putFloat(it) } }.array()
      val iBuf = ByteBuffer.allocate(indices.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply { indices.forEach { putShort(it) } }.array()

      val pv = appendView(pBuf, 34962)
      val nv = appendView(nBuf, 34962)
      val iv = appendView(iBuf, 34963)
      val pa = accessors.length().also {
        accessors.put(JSONObject().apply {
          put("bufferView", pv); put("componentType", 5126); put("count", pos.size / 3); put("type", "VEC3")
          put("min", JSONArray().put(minX.toDouble()).put(minY.toDouble()).put(minZ.toDouble()))
          put("max", JSONArray().put(maxX.toDouble()).put(maxY.toDouble()).put(maxZ.toDouble()))
        })
      }
      val na = accessors.length().also {
        accessors.put(JSONObject().apply {
          put("bufferView", nv); put("componentType", 5126); put("count", nrm.size / 3); put("type", "VEC3")
        })
      }
      val ia = accessors.length().also {
        accessors.put(JSONObject().apply {
          put("bufferView", iv); put("componentType", 5123); put("count", indices.size); put("type", "SCALAR")
        })
      }

      val meshIdx = meshes.length()
      meshes.put(JSONObject().apply {
        put("name", meshName)
        put("primitives", JSONArray().put(JSONObject().apply {
          put("attributes", JSONObject().apply { put("POSITION", pa); put("NORMAL", na) })
          put("indices", ia)
          put("material", materialIndex)
        }))
      })
      return meshIdx
    }

    val childIds = JSONArray()
    nodes.put(JSONObject().put("name", "FallbackHeroRoot").put("children", childIds))

    boxes.groupBy { it.materialIndex }.toSortedMap().forEach { (matIdx, grp) ->
      val mIdx = addMeshForBoxes("FallbackPart_$matIdx", grp, matIdx)
      val nIdx = nodes.length()
      nodes.put(JSONObject().put("name", "FallbackPart_$matIdx").put("mesh", mIdx))
      childIds.put(nIdx)
    }

    val wheelNames = mutableListOf<String>()
    val wheelMeshIdx = addMeshForBoxes(
      "FallbackWheel",
      listOf(BoxSpec(-wheelW * 0.5f, wheelW * 0.5f, -wheelR, wheelR, -wheelR, wheelR, 4)),
      4,
    )
    wheelCenters.forEachIndexed { idx, (wx, wy, wz) ->
      val tag = "__DRIP_WHEEL_${idx}__"
      val nIdx = nodes.length()
      nodes.put(
        JSONObject().apply {
          put("name", tag)
          put("mesh", wheelMeshIdx)
          put("translation", JSONArray().put(wx.toDouble()).put(wy.toDouble()).put(wz.toDouble()))
        }
      )
      childIds.put(nIdx)
      wheelNames += tag
    }

    while (binStream.size() % 4 != 0) binStream.write(0)
    val binBytes = binStream.toByteArray()

    val mats = JSONArray().apply {
      put(JSONObject().apply {
        put("name", "__DRIP_PAINT_FALLBACK__")
        put("pbrMetallicRoughness", JSONObject().apply {
          put("baseColorFactor", JSONArray().put(paintLinearRgb[0].toDouble()).put(paintLinearRgb[1].toDouble()).put(paintLinearRgb[2].toDouble()).put(1.0))
          put("metallicFactor", 0.55)
          put("roughnessFactor", 0.28)
        })
      })
      put(JSONObject().apply {
        put("name", "FallbackGlass")
        put("pbrMetallicRoughness", JSONObject().apply {
          put("baseColorFactor", JSONArray().put(0.08).put(0.11).put(0.16).put(1.0))
          put("metallicFactor", 0.1)
          put("roughnessFactor", 0.14)
        })
      })
      put(JSONObject().apply {
        put("name", "FallbackHeadlight")
        put("pbrMetallicRoughness", JSONObject().apply {
          put("baseColorFactor", JSONArray().put(0.95).put(0.97).put(1.0).put(1.0))
        })
        put("emissiveFactor", JSONArray().put(0.95).put(0.95).put(1.0))
      })
      put(JSONObject().apply {
        put("name", "Brakelight")
        put("pbrMetallicRoughness", JSONObject().apply {
          put("baseColorFactor", JSONArray().put(0.95).put(0.05).put(0.02).put(1.0))
        })
        put("emissiveFactor", JSONArray().put(0.25).put(0.01).put(0.01))
      })
      put(JSONObject().apply {
        put("name", "FallbackRubber")
        put("pbrMetallicRoughness", JSONObject().apply {
          put("baseColorFactor", JSONArray().put(0.04).put(0.04).put(0.045).put(1.0))
          put("metallicFactor", 0.0)
          put("roughnessFactor", 0.88)
        })
      })
    }

    val root = JSONObject().apply {
      put("asset", JSONObject().put("version", "2.0").put("generator", "DRIP_RACE_HERO_FALLBACK"))
      put("scene", 0)
      put("scenes", JSONArray().put(JSONObject().put("nodes", JSONArray().put(0))))
      put("nodes", nodes)
      put("meshes", meshes)
      put("materials", mats)
      put("accessors", accessors)
      put("bufferViews", bufferViews)
      put("buffers", JSONArray().put(JSONObject().put("byteLength", binBytes.size)))
    }

    val jsonBytes = root.toString().toByteArray(StandardCharsets.UTF_8)
    val jsonPad = (4 - (jsonBytes.size % 4)) % 4
    val jsonChunkLen = jsonBytes.size + jsonPad
    val totalLen = 12 + 8 + jsonChunkLen + 8 + binBytes.size
    val out = ByteBuffer.allocate(totalLen).order(ByteOrder.LITTLE_ENDIAN)
    out.putInt(0x46546C67)
    out.putInt(2)
    out.putInt(totalLen)
    out.putInt(jsonChunkLen)
    out.putInt(0x4E4F534A)
    out.put(jsonBytes)
    repeat(jsonPad) { out.put(0x20.toByte()) }
    out.putInt(binBytes.size)
    out.putInt(0x004E4942)
    out.put(binBytes)

    return PreparedHeroGlb(
      glbBytes = out.array(),
      paintMaterialNames = listOf("__DRIP_PAINT_FALLBACK__"),
      wheelNodeNames = wheelNames,
      carrierNodeNames = emptyList(),
      nissanWheelGroupNodeNames = emptyList(),
      wheelAxisChar = 'x',
    )
  }
}
