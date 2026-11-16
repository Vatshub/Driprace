package com.example.assets

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.IOException
import java.security.MessageDigest

object StarterAssetContract {
  const val EXPECTED_STARTER_SHA256 =
    "c4246bb38128e6a129af8e176bb8d9474c961fdc713d854b34805cd41e6c7008"
  val BUNDLED_MODEL_CANDIDATE_PATHS =
    listOf("models/starter-sports.glb", "starter-sports.glb")
  val ASPHALT_BASE_COLOR_CANDIDATES =
    listOf(
      "textures/asphalt-color.jpg",
      "textures/asphalt-color.png",
      "textures/asphalt_basecolor.png",
      "textures/asphalt_basecolor.jpg",
      "textures/asphalt_basecolor.webp",
      "textures/asphalt_albedo.png",
      "textures/asphalt_albedo.jpg",
    )
  val ASPHALT_NORMAL_CANDIDATES =
    listOf(
      "textures/asphalt-normal.jpg",
      "textures/asphalt-normal.png",
      "textures/asphalt_normal.png",
      "textures/asphalt_normal.jpg",
      "textures/asphalt_normal.webp",
    )
  val ASPHALT_ROUGHNESS_CANDIDATES =
    listOf(
      "textures/asphalt-roughness.jpg",
      "textures/asphalt-roughness.png",
      "textures/asphalt_roughness.png",
      "textures/asphalt_roughness.jpg",
      "textures/asphalt_roughness.webp",
    )
  val HDRI_CANDIDATES =
    listOf("hdri/daylight-1k.hdr", "hdri/daylight.hdr", "hdri/daylight.ktx", "hdri/daylight.ktx2")
  val ADAPTER_REFERENCE_CANDIDATES =
    listOf("adapters/prepare-vehicle.mjs", "prepare-vehicle.mjs")
}

data class BundledAsphaltMaps(
  val baseColorBitmap: Bitmap?,
  val baseColorPath: String?,
  val normalBitmap: Bitmap?,
  val normalPath: String?,
  val roughnessBitmap: Bitmap?,
  val roughnessPath: String?,
) {
  val hasAnySuppliedMap: Boolean
    get() = baseColorBitmap != null || normalBitmap != null || roughnessBitmap != null
}

sealed interface StarterLoadResult {
  data class Ready(
    val glbBytes: ByteArray,
    val sha256Hex: String,
    val sourceLabel: String,
    val isBundledOfflineAsset: Boolean,
    val asphaltMaps: BundledAsphaltMaps,
    val hdrAssetPath: String?,
    val prepareVehicleMjsPath: String?,
  ) : StarterLoadResult

  data class MissingOrInvalid(
    val title: String,
    val detail: String,
    val expectedAssetPath: String,
    val expectedSha256: String,
    val actualSha256: String? = null,
    val missingItems: List<String>,
  ) : StarterLoadResult
}

open class StarterAssetRepository(private val context: Context) {
  open fun loadBundledStarter(): StarterLoadResult {
    val assetManager = context.assets
    val missingItems = mutableListOf<String>()

    val modelPath = findFirstExistingAsset(StarterAssetContract.BUNDLED_MODEL_CANDIDATE_PATHS)
    val asphaltMaps = loadOptionalAsphaltMaps()
    val hdrPath = findFirstExistingAsset(StarterAssetContract.HDRI_CANDIDATES)
    val adapterPath = findFirstExistingAsset(StarterAssetContract.ADAPTER_REFERENCE_CANDIDATES)

    if (adapterPath == null) {
      missingItems += "app/src/main/assets/adapters/prepare-vehicle.mjs (vehicle steering/adapter reference)"
    }
    if (!asphaltMaps.hasAnySuppliedMap) {
      missingItems += "app/src/main/assets/textures/asphalt_{basecolor,normal,roughness}.{png,jpg}"
    }
    if (hdrPath == null) {
      missingItems += "app/src/main/assets/hdri/daylight.{hdr,ktx} (optional in slice; fallback active)"
    }

    if (modelPath == null) {
      missingItems.add(0, "app/src/main/assets/models/starter-sports.glb")
      return StarterLoadResult.MissingOrInvalid(
        title = "Bundled starter-sports.glb not supplied",
        detail =
          "DRIP RACE requires the approved starter-sports.glb packaged inside Android assets for offline rendering. No procedural or box car fallback will be substituted.",
        expectedAssetPath = "app/src/main/assets/models/starter-sports.glb",
        expectedSha256 = StarterAssetContract.EXPECTED_STARTER_SHA256,
        actualSha256 = null,
        missingItems = missingItems,
      )
    }

    val bytes =
      try {
        assetManager.open(modelPath).use { it.readBytes() }
      } catch (io: IOException) {
        return StarterLoadResult.MissingOrInvalid(
          title = "Failed reading starter-sports.glb",
          detail = io.message ?: "Unable to read bundled asset bytes.",
          expectedAssetPath = "app/src/main/assets/$modelPath",
          expectedSha256 = StarterAssetContract.EXPECTED_STARTER_SHA256,
          missingItems = missingItems,
        )
      }

    if (!hasValidGlbHeader(bytes)) {
      return StarterLoadResult.MissingOrInvalid(
        title = "Invalid GLB binary header",
        detail = "File at assets/$modelPath does not begin with a valid glTF 2.0 binary header.",
        expectedAssetPath = "app/src/main/assets/$modelPath",
        expectedSha256 = StarterAssetContract.EXPECTED_STARTER_SHA256,
        actualSha256 = sha256Hex(bytes),
        missingItems = missingItems,
      )
    }

    val actualHash = sha256Hex(bytes)
    if (!actualHash.equals(StarterAssetContract.EXPECTED_STARTER_SHA256, ignoreCase = true)) {
      return StarterLoadResult.MissingOrInvalid(
        title = "starter-sports.glb SHA-256 mismatch",
        detail =
          "The supplied starter-sports.glb bytes do not match the approved SHA-256 digest. Source bytes must be preserved unmodified.",
        expectedAssetPath = "app/src/main/assets/$modelPath",
        expectedSha256 = StarterAssetContract.EXPECTED_STARTER_SHA256,
        actualSha256 = actualHash,
        missingItems = missingItems,
      )
    }

    return StarterLoadResult.Ready(
      glbBytes = bytes,
      sha256Hex = actualHash,
      sourceLabel = "assets/$modelPath",
      isBundledOfflineAsset = true,
      asphaltMaps = asphaltMaps,
      hdrAssetPath = hdrPath,
      prepareVehicleMjsPath = adapterPath,
    )
  }

  fun loadFromDocumentUri(uri: Uri): StarterLoadResult {
    val bytes =
      try {
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
          ?: return StarterLoadResult.MissingOrInvalid(
            title = "Unable to open selected file",
            detail = "Document provider returned a null stream for $uri.",
            expectedAssetPath = "app/src/main/assets/models/starter-sports.glb",
            expectedSha256 = StarterAssetContract.EXPECTED_STARTER_SHA256,
            missingItems = listOf("Readable .glb document"),
          )
      } catch (e: Exception) {
        return StarterLoadResult.MissingOrInvalid(
          title = "Document read error",
          detail = e.message ?: "Failed reading selected document.",
          expectedAssetPath = "app/src/main/assets/models/starter-sports.glb",
          expectedSha256 = StarterAssetContract.EXPECTED_STARTER_SHA256,
          missingItems = listOf("Readable .glb document"),
        )
      }

    if (!hasValidGlbHeader(bytes)) {
      return StarterLoadResult.MissingOrInvalid(
        title = "Selected file is not a valid GLB",
        detail = "Expected binary glTF 2.0 magic header ('glTF').",
        expectedAssetPath = "app/src/main/assets/models/starter-sports.glb",
        expectedSha256 = StarterAssetContract.EXPECTED_STARTER_SHA256,
        actualSha256 = sha256Hex(bytes),
        missingItems = listOf("Valid starter-sports.glb"),
      )
    }

    val actualHash = sha256Hex(bytes)
    if (!actualHash.equals(StarterAssetContract.EXPECTED_STARTER_SHA256, ignoreCase = true)) {
      return StarterLoadResult.MissingOrInvalid(
        title = "Picked GLB SHA-256 mismatch",
        detail =
          "Selected file does not match the approved starter-sports.glb SHA-256 digest.",
        expectedAssetPath = "app/src/main/assets/models/starter-sports.glb",
        expectedSha256 = StarterAssetContract.EXPECTED_STARTER_SHA256,
        actualSha256 = actualHash,
        missingItems = listOf("Approved starter-sports.glb"),
      )
    }

    val asphaltMaps = loadOptionalAsphaltMaps()
    val hdrPath = findFirstExistingAsset(StarterAssetContract.HDRI_CANDIDATES)
    val adapterPath = findFirstExistingAsset(StarterAssetContract.ADAPTER_REFERENCE_CANDIDATES)
    return StarterLoadResult.Ready(
      glbBytes = bytes,
      sha256Hex = actualHash,
      sourceLabel = "System Picker Preview (NOT bundled offline)",
      isBundledOfflineAsset = false,
      asphaltMaps = asphaltMaps,
      hdrAssetPath = hdrPath,
      prepareVehicleMjsPath = adapterPath,
    )
  }

  private fun loadOptionalAsphaltMaps(): BundledAsphaltMaps {
    val basePath = findFirstExistingAsset(StarterAssetContract.ASPHALT_BASE_COLOR_CANDIDATES)
    val normalPath = findFirstExistingAsset(StarterAssetContract.ASPHALT_NORMAL_CANDIDATES)
    val roughPath = findFirstExistingAsset(StarterAssetContract.ASPHALT_ROUGHNESS_CANDIDATES)
    return BundledAsphaltMaps(
      baseColorBitmap = basePath?.let(::decodeAssetBitmap),
      baseColorPath = basePath,
      normalBitmap = normalPath?.let(::decodeAssetBitmap),
      normalPath = normalPath,
      roughnessBitmap = roughPath?.let(::decodeAssetBitmap),
      roughnessPath = roughPath,
    )
  }

  private fun decodeAssetBitmap(path: String, maxDim: Int = 1024): Bitmap? {
    return try {
      val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
      context.assets.open(path).use { boundsStream ->
        BitmapFactory.decodeStream(boundsStream, null, boundsOptions)
      }
      var sampleSize = 1
      while (
        boundsOptions.outWidth / sampleSize > maxDim ||
          boundsOptions.outHeight / sampleSize > maxDim
      ) {
        sampleSize *= 2
      }
      context.assets.open(path).use { stream ->
        val options =
          BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPremultiplied = false
            inPreferredConfig = Bitmap.Config.ARGB_8888
          }
        BitmapFactory.decodeStream(stream, null, options)
      }
    } catch (_: Exception) {
      null
    }
  }

  private fun findFirstExistingAsset(candidates: List<String>): String? {
    for (candidate in candidates) {
      val folder = candidate.substringBeforeLast('/', "")
      val fileName = candidate.substringAfterLast('/')
      val entries =
        try {
          context.assets.list(folder) ?: emptyArray()
        } catch (_: IOException) {
          emptyArray()
        }
      if (entries.contains(fileName)) {
        return candidate
      }
    }
    return null
  }

  data class StreamDigestInspection(
    val totalBytes: Long,
    val sha256Hex: String,
    val hasValidGlbHeader: Boolean,
    val hasValidRadianceHdrHeader: Boolean,
  )

  companion object {
    fun sha256Hex(bytes: ByteArray): String {
      val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
      return digest.joinToString("") { "%02x".format(it) }
    }

    fun hasValidGlbHeader(bytes: ByteArray): Boolean {
      if (bytes.size < 12) return false
      // "glTF" in little-endian ASCII: 0x67, 0x6C, 0x54, 0x46
      return bytes[0] == 0x67.toByte() &&
        bytes[1] == 0x6C.toByte() &&
        bytes[2] == 0x54.toByte() &&
        bytes[3] == 0x46.toByte()
    }

    /**
     * Computes exact byte length, SHA-256 digest, and binary magic-header validity using a single
     * 64 KB buffer so startup audits never materialize 10–25 MB GLB/HDR ByteArrays on the heap.
     */
    fun inspectStreamHeaderAndSha256(stream: java.io.InputStream): StreamDigestInspection {
      val digest = MessageDigest.getInstance("SHA-256")
      val prefix = ByteArray(16)
      var prefixFilled = 0
      var totalBytes = 0L
      val buffer = ByteArray(65_536)
      while (true) {
        val read = stream.read(buffer)
        if (read <= 0) break
        if (prefixFilled < prefix.size) {
          val toCopy = minOf(read, prefix.size - prefixFilled)
          System.arraycopy(buffer, 0, prefix, prefixFilled, toCopy)
          prefixFilled += toCopy
        }
        digest.update(buffer, 0, read)
        totalBytes += read
      }
      val glbOk =
        totalBytes >= 12L &&
          prefix[0] == 0x67.toByte() &&
          prefix[1] == 0x6C.toByte() &&
          prefix[2] == 0x54.toByte() &&
          prefix[3] == 0x46.toByte()
      val asciiPrefix =
        if (prefixFilled > 0) String(prefix, 0, prefixFilled, Charsets.US_ASCII) else ""
      val hdrOk =
        asciiPrefix.startsWith("#?RADIANCE") || asciiPrefix.startsWith("#?RGBE")
      val hex = digest.digest().joinToString("") { "%02x".format(it) }
      return StreamDigestInspection(
        totalBytes = totalBytes,
        sha256Hex = hex,
        hasValidGlbHeader = glbOk,
        hasValidRadianceHdrHeader = hdrOk,
      )
    }
  }
}
