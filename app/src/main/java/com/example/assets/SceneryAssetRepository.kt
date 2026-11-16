package com.example.assets

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.example.scenery.DecodedHdrEnvironment
import com.example.scenery.EnvironmentSceneryRecipe
import com.example.scenery.GraphicsQualityPreset
import com.example.scenery.RadianceHdrDecoder
import com.example.scenery.SceneryAssetKind
import com.example.scenery.SceneryAssetSpec
import com.example.scenery.SceneryCatalog
import java.io.File
import java.io.IOException

enum class SceneryAssetOrigin {
  BUNDLED_VERIFIED,
  ON_DEVICE_LOCAL_IMPORT_VERIFIED,
  PENDING_LOCAL_ANDROID_STUDIO_OR_PICKER,
  INVALID_OR_MISSING_DEPENDENCY,
}

data class SceneryAssetAuditEntry(
  val spec: SceneryAssetSpec,
  val origin: SceneryAssetOrigin,
  val sha256Verified: Boolean,
  val dependenciesVerified: Boolean,
  val statusDetail: String,
)

data class SceneryPackageAuditReport(
  val entries: List<SceneryAssetAuditEntry>,
  val verifiedCount: Int,
  val pendingLargeCount: Int,
  val missingOrInvalidCount: Int,
  val excludedAssetsConfirmedAbsent: Boolean,
) {
  val summaryLine: String
    get() =
      buildString {
        append("Scenery/HDR: $verifiedCount/${entries.size} verified")
        if (pendingLargeCount > 0) {
          append(" • $pendingLargeCount >30MB candidate pending local Android Studio copy")
        }
        if (missingOrInvalidCount > 0) {
          append(" • $missingOrInvalidCount missing/invalid")
        }
      }
}

data class LoadedGroundPbrMaps(
  val assetId: String,
  val colorBitmap: Bitmap?,
  val normalBitmap: Bitmap?,
  val roughnessBitmap: Bitmap?,
) {
  val hasMaps: Boolean
    get() = colorBitmap != null || normalBitmap != null || roughnessBitmap != null
}

sealed interface LoadedSceneryModelPayload {
  val spec: SceneryAssetSpec

  data class SingleGlb(
    override val spec: SceneryAssetSpec,
    val rawGlbBytes: ByteArray,
  ) : LoadedSceneryModelPayload

  data class ExternalGltf(
    override val spec: SceneryAssetSpec,
    val gltfJsonBytes: ByteArray,
    val dependencyBytesByRelativeUri: Map<String, ByteArray>,
  ) : LoadedSceneryModelPayload
}

data class ActiveEnvironmentSceneryBundle(
  val environmentId: String,
  val recipe: EnvironmentSceneryRecipe,
  val qualityPreset: GraphicsQualityPreset,
  val decodedHdr: DecodedHdrEnvironment?,
  val hdrAssetPath: String?,
  val groundMaps: LoadedGroundPbrMaps?,
  val modelsByAssetId: Map<String, LoadedSceneryModelPayload>,
  val missingRequestedAssetIds: List<String>,
) {
  val loadedAssetIds: Set<String>
    get() =
      buildSet {
        if (decodedHdr != null) add(recipe.hdrAssetId)
        groundMaps?.let { add(it.assetId) }
        addAll(modelsByAssetId.keys)
      }
}

data class VerifiedHazardSceneryProps(
  val concreteBarrierGlb: ByteArray?,
  val roadworkBarricadeGlb: ByteArray?,
  val trafficConeGlb: ByteArray?,
)

open class SceneryAssetRepository(private val context: Context) {
  private val importDir: File
    get() = File(context.filesDir, IMPORT_SCENERY_DIR)

  var lazyEnvironmentLoadCount: Int = 0
    private set

  var lastLoadedEnvironmentId: String? = null
    private set

  var lastLoadedAssetIds: Set<String> = emptySet()
    private set

  open fun auditSceneryPackage(): SceneryPackageAuditReport {
    val entries = mutableListOf<SceneryAssetAuditEntry>()
    var verifiedCount = 0
    var pendingLargeCount = 0
    var missingOrInvalidCount = 0

    val excludedConfirmedAbsent = verifyExcludedAssetsAbsent()

    for (spec in SceneryCatalog.ALL_SCENERY_ASSETS) {
      require(!SceneryCatalog.isExcludedAssetPath(spec.primaryAssetPath)) {
        "Excluded asset '${spec.primaryAssetPath}' must never appear in SceneryCatalog."
      }
      val entry = auditSingleSpec(spec)
      entries += entry
      when (entry.origin) {
        SceneryAssetOrigin.BUNDLED_VERIFIED,
        SceneryAssetOrigin.ON_DEVICE_LOCAL_IMPORT_VERIFIED -> verifiedCount++
        SceneryAssetOrigin.PENDING_LOCAL_ANDROID_STUDIO_OR_PICKER -> pendingLargeCount++
        SceneryAssetOrigin.INVALID_OR_MISSING_DEPENDENCY -> missingOrInvalidCount++
      }
    }

    return SceneryPackageAuditReport(
      entries = entries,
      verifiedCount = verifiedCount,
      pendingLargeCount = pendingLargeCount,
      missingOrInvalidCount = missingOrInvalidCount,
      excludedAssetsConfirmedAbsent = excludedConfirmedAbsent,
    )
  }

  /**
   * Lazily loads ONLY the single active environment's required HDR, ground texture set, and
   * roadside/skyline/terrain 3D models. Unselected environments' HDRs and terrains are NEVER
   * decoded or read into memory simultaneously.
   */
  open fun loadActiveEnvironmentBundle(
    environmentId: String,
    qualityPreset: GraphicsQualityPreset = GraphicsQualityPreset.AUTO,
  ): ActiveEnvironmentSceneryBundle {
    val recipe = SceneryCatalog.recipeFor(environmentId)
    val missingIds = mutableListOf<String>()

    // 1. Lazy decode ONLY this environment's single Radiance .hdr file
    val hdrSpec = SceneryCatalog.findAsset(recipe.hdrAssetId)
    val hdrBytes = hdrSpec?.let { readVerifiedPrimaryBytes(it)?.first }
    val decodedHdr =
      if (hdrSpec != null && hdrBytes != null) {
        val (exposure, floor) =
          when (recipe.hdrAssetId) {
            SceneryCatalog.HDR_NIGHT_ID -> 0.22f to 0.24f
            SceneryCatalog.HDR_SUNSET_ID -> 1.05f to 0.14f
            else -> 1.00f to 0.16f
          }
        runCatching {
          RadianceHdrDecoder.decodeHdrBytes(
            bytes = hdrBytes,
            assetLabel = hdrSpec.primaryAssetPath,
            decodeStride = qualityPreset.hdrDecodeStride,
            exposureScale = exposure,
            minimumAmbientFloorLinear = floor,
          )
        }.getOrNull()
      } else {
        null
      }
    if (decodedHdr == null) {
      missingIds += recipe.hdrAssetId
    }

    // 2. Lazy decode ONLY this environment's PBR ground texture set (if any)
    val groundMaps =
      recipe.groundTextureAssetId?.let { groundId ->
        val groundSpec = SceneryCatalog.findAsset(groundId)
        if (groundSpec != null) {
          loadGroundTextureSet(groundSpec)
        } else {
          null
        }
      }

    // 3. Lazy read ONLY this environment's prop/terrain models (respecting LOW far-backdrop toggle)
    val models = LinkedHashMap<String, LoadedSceneryModelPayload>()
    for (rule in recipe.propRules) {
      if (rule.isFarBackdrop && !qualityPreset.enableFarBackdropModels) {
        continue
      }
      if (models.containsKey(rule.assetId)) continue
      val spec = SceneryCatalog.findAsset(rule.assetId) ?: continue
      val loadedPayload = loadModelPayload(spec)
      if (loadedPayload != null) {
        models[spec.id] = loadedPayload
      } else {
        missingIds += spec.id
      }
    }

    val bundle =
      ActiveEnvironmentSceneryBundle(
        environmentId = recipe.environmentId,
        recipe = recipe,
        qualityPreset = qualityPreset,
        decodedHdr = decodedHdr,
        hdrAssetPath = hdrSpec?.primaryAssetPath,
        groundMaps = groundMaps,
        modelsByAssetId = models,
        missingRequestedAssetIds = missingIds,
      )

    lazyEnvironmentLoadCount += 1
    lastLoadedEnvironmentId = recipe.environmentId
    lastLoadedAssetIds = bundle.loadedAssetIds
    return bundle
  }

  /**
   * Loads verified genuine road barrier & roadwork barricade GLBs for Phase 3/7 static highway
   * hazards (`Construction Barier-02` remains strictly excluded).
   */
  open fun loadVerifiedHazardPropBytes(): VerifiedHazardSceneryProps {
    val concreteSpec = SceneryCatalog.findAsset(SceneryCatalog.CONCRETE_BARRIER_ID)
    val barricadeSpec = SceneryCatalog.findAsset(SceneryCatalog.ROADWORK_BARRICADE_ID)
    val coneSpec = SceneryCatalog.findAsset(SceneryCatalog.TRAFFIC_CONE_ID)
    return VerifiedHazardSceneryProps(
      concreteBarrierGlb = concreteSpec?.let { readVerifiedPrimaryBytes(it)?.first },
      roadworkBarricadeGlb = barricadeSpec?.let { readVerifiedPrimaryBytes(it)?.first },
      trafficConeGlb = coneSpec?.let { readVerifiedPrimaryBytes(it)?.first },
    )
  }

  /**
   * Optional on-device SAF importer for >30 MB scenery candidates (e.g.
   * `valley-complete-atlas-far-lod-candidate.glb`), matching exact SHA-256 in CHECKSUMS.json.
   */
  open fun importSceneryGlbFromUri(uri: Uri): Result<SceneryAssetSpec> = runCatching {
    val bytes =
      context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        ?: error("Unable to open selected scenery GLB stream.")
    require(StarterAssetRepository.hasValidGlbHeader(bytes)) {
      "Selected file is not a valid glTF 2.0 binary (.glb)."
    }
    val hash = StarterAssetRepository.sha256Hex(bytes)
    val matched =
      SceneryCatalog.ALL_SCENERY_ASSETS.firstOrNull {
        it.kind == SceneryAssetKind.GLB_SINGLE &&
          it.expectedSha256 != null &&
          it.expectedSha256.equals(hash, ignoreCase = true)
      } ?: error("Selected GLB SHA-256 ($hash) does not match any approved scenery model.")

    val dir = importDir
    if (!dir.exists() && !dir.mkdirs()) {
      error("Could not create local scenery import directory.")
    }
    val fileName = matched.primaryAssetPath.substringAfterLast('/')
    val outFile = File(dir, fileName)
    outFile.writeBytes(bytes)
    matched
  }

  private fun loadModelPayload(spec: SceneryAssetSpec): LoadedSceneryModelPayload? {
    return when (spec.kind) {
      SceneryAssetKind.GLB_SINGLE -> {
        val pair = readVerifiedPrimaryBytes(spec) ?: return null
        if (!StarterAssetRepository.hasValidGlbHeader(pair.first)) return null
        LoadedSceneryModelPayload.SingleGlb(spec = spec, rawGlbBytes = pair.first)
      }
      SceneryAssetKind.GLTF_EXTERNAL -> {
        val primary = readVerifiedPrimaryBytes(spec) ?: return null
        val parentFolder = spec.primaryAssetPath.substringBeforeLast('/', "")
        val deps = LinkedHashMap<String, ByteArray>()
        for ((relUri, expectedHash) in spec.externalDependenciesWithSha256) {
          val depPath = if (parentFolder.isEmpty()) relUri else "$parentFolder/$relUri"
          val depBytes = readBundledAssetBytes(depPath) ?: return null
          val actualHash = StarterAssetRepository.sha256Hex(depBytes)
          if (!actualHash.equals(expectedHash, ignoreCase = true)) {
            return null
          }
          deps[relUri] = depBytes
        }
        LoadedSceneryModelPayload.ExternalGltf(
          spec = spec,
          gltfJsonBytes = primary.first,
          dependencyBytesByRelativeUri = deps,
        )
      }
      else -> null
    }
  }

  private fun loadGroundTextureSet(spec: SceneryAssetSpec): LoadedGroundPbrMaps? {
    val parentFolder = spec.primaryAssetPath.substringBeforeLast('/', "")
    val colorBytes = readVerifiedPrimaryBytes(spec)?.first ?: return null
    val normalBytes =
      readBundledAssetBytes("$parentFolder/normal-gl.jpg")?.takeIf { bytes ->
        val expected = spec.externalDependenciesWithSha256["normal-gl.jpg"]
        expected == null || StarterAssetRepository.sha256Hex(bytes).equals(expected, ignoreCase = true)
      }
    val roughBytes =
      readBundledAssetBytes("$parentFolder/roughness.jpg")?.takeIf { bytes ->
        val expected = spec.externalDependenciesWithSha256["roughness.jpg"]
        expected == null || StarterAssetRepository.sha256Hex(bytes).equals(expected, ignoreCase = true)
      }

    return LoadedGroundPbrMaps(
      assetId = spec.id,
      colorBitmap = decodeBitmap(colorBytes, maxDim = 1024),
      normalBitmap = normalBytes?.let { decodeBitmap(it, maxDim = 1024) },
      roughnessBitmap = roughBytes?.let { decodeBitmap(it, maxDim = 1024) },
    )
  }

  private fun decodeBitmap(bytes: ByteArray, maxDim: Int): Bitmap? =
    runCatching {
      val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
      BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOpts)
      var sample = 1
      while (boundsOpts.outWidth / sample > maxDim || boundsOpts.outHeight / sample > maxDim) {
        sample *= 2
      }
      val opts =
        BitmapFactory.Options().apply {
          inSampleSize = sample
          inPremultiplied = false
          inPreferredConfig = Bitmap.Config.ARGB_8888
        }
      BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }.getOrNull()

  private fun auditSingleSpec(spec: SceneryAssetSpec): SceneryAssetAuditEntry {
    cachedBundledVerifiedEntries[spec.id]?.let { return it }

    val primaryStreamResult = inspectVerifiedPrimaryStream(spec)
    if (primaryStreamResult == null) {
      if (spec.exceedsCloudUploadLimit) {
        return SceneryAssetAuditEntry(
          spec = spec,
          origin = SceneryAssetOrigin.PENDING_LOCAL_ANDROID_STUDIO_OR_PICKER,
          sha256Verified = false,
          dependenciesVerified = false,
          statusDetail =
            "Pending local copy (>30 MB AI Studio upload cap): place ${spec.primaryAssetPath} in Android Studio assets/scenery/",
        )
      }
      return SceneryAssetAuditEntry(
        spec = spec,
        origin = SceneryAssetOrigin.INVALID_OR_MISSING_DEPENDENCY,
        sha256Verified = false,
        dependenciesVerified = false,
        statusDetail = "Missing or SHA-256 mismatch at assets/${spec.primaryAssetPath}",
      )
    }

    val origin = primaryStreamResult
    if (spec.externalDependenciesWithSha256.isNotEmpty()) {
      val parentFolder = spec.primaryAssetPath.substringBeforeLast('/', "")
      for ((relUri, expectedHash) in spec.externalDependenciesWithSha256) {
        val depPath = if (parentFolder.isEmpty()) relUri else "$parentFolder/$relUri"
        val depInspection =
          inspectBundledAssetStream(depPath)
            ?: return SceneryAssetAuditEntry(
              spec = spec,
              origin = SceneryAssetOrigin.INVALID_OR_MISSING_DEPENDENCY,
              sha256Verified = true,
              dependenciesVerified = false,
              statusDetail = "Missing external dependency assets/$depPath",
            )
        if (!depInspection.sha256Hex.equals(expectedHash, ignoreCase = true)) {
          return SceneryAssetAuditEntry(
            spec = spec,
            origin = SceneryAssetOrigin.INVALID_OR_MISSING_DEPENDENCY,
            sha256Verified = true,
            dependenciesVerified = false,
            statusDetail = "Dependency SHA-256 mismatch at assets/$depPath",
          )
        }
      }
    }

    val candidateBadge = if (spec.isCandidate) " [CANDIDATE]" else ""
    val entry =
      SceneryAssetAuditEntry(
        spec = spec,
        origin = origin,
        sha256Verified = true,
        dependenciesVerified = true,
        statusDetail = "Verified (${spec.primaryAssetPath})$candidateBadge",
      )
    if (origin == SceneryAssetOrigin.BUNDLED_VERIFIED) {
      cachedBundledVerifiedEntries[spec.id] = entry
    }
    return entry
  }

  private fun inspectVerifiedPrimaryStream(spec: SceneryAssetSpec): SceneryAssetOrigin? {
    val bundled = inspectBundledAssetStream(spec.primaryAssetPath)
    if (bundled != null && matchesStreamSpecValidation(spec, bundled)) {
      return SceneryAssetOrigin.BUNDLED_VERIFIED
    }
    if (spec.kind == SceneryAssetKind.GLB_SINGLE) {
      val localFile = File(importDir, spec.primaryAssetPath.substringAfterLast('/'))
      if (localFile.exists() && localFile.isFile) {
        val imported =
          runCatching {
            localFile.inputStream().use { StarterAssetRepository.inspectStreamHeaderAndSha256(it) }
          }.getOrNull()
        if (imported != null && matchesStreamSpecValidation(spec, imported)) {
          return SceneryAssetOrigin.ON_DEVICE_LOCAL_IMPORT_VERIFIED
        }
      }
    }
    return null
  }

  private fun matchesStreamSpecValidation(
    spec: SceneryAssetSpec,
    inspection: StarterAssetRepository.StreamDigestInspection,
  ): Boolean {
    if (spec.kind == SceneryAssetKind.RADIANCE_HDR && !inspection.hasValidRadianceHdrHeader) {
      return false
    }
    if (spec.kind == SceneryAssetKind.GLB_SINGLE && !inspection.hasValidGlbHeader) {
      return false
    }
    val expected = spec.expectedSha256 ?: return inspection.totalBytes > 0L
    return inspection.sha256Hex.equals(expected, ignoreCase = true)
  }

  private fun readVerifiedPrimaryBytes(
    spec: SceneryAssetSpec,
  ): Pair<ByteArray, SceneryAssetOrigin>? {
    val bundled = readBundledAssetBytes(spec.primaryAssetPath)
    if (bundled != null && matchesSpecValidation(spec, bundled)) {
      return bundled to SceneryAssetOrigin.BUNDLED_VERIFIED
    }
    if (spec.kind == SceneryAssetKind.GLB_SINGLE) {
      val localFile = File(importDir, spec.primaryAssetPath.substringAfterLast('/'))
      if (localFile.exists() && localFile.isFile) {
        val imported = runCatching { localFile.readBytes() }.getOrNull()
        if (imported != null && matchesSpecValidation(spec, imported)) {
          return imported to SceneryAssetOrigin.ON_DEVICE_LOCAL_IMPORT_VERIFIED
        }
      }
    }
    return null
  }

  private fun matchesSpecValidation(spec: SceneryAssetSpec, bytes: ByteArray): Boolean {
    if (spec.kind == SceneryAssetKind.RADIANCE_HDR && !RadianceHdrDecoder.hasValidRadianceHeader(bytes)) {
      return false
    }
    if (spec.kind == SceneryAssetKind.GLB_SINGLE && !StarterAssetRepository.hasValidGlbHeader(bytes)) {
      return false
    }
    val expected = spec.expectedSha256 ?: return bytes.isNotEmpty()
    return StarterAssetRepository.sha256Hex(bytes).equals(expected, ignoreCase = true)
  }

  private fun verifyExcludedAssetsAbsent(): Boolean {
    val foldersToScan = listOf("scenery", "scenery/concrete-barrier", "scenery/guardrail", "models")
    for (folder in foldersToScan) {
      val entries =
        try {
          context.assets.list(folder) ?: emptyArray()
        } catch (_: IOException) {
          emptyArray()
        }
      if (entries.any { SceneryCatalog.isExcludedAssetPath(it) }) {
        return false
      }
    }
    return true
  }

  private fun readBundledAssetBytes(assetPath: String): ByteArray? {
    val folder = assetPath.substringBeforeLast('/', "")
    val fileName = assetPath.substringAfterLast('/')
    val entries =
      try {
        context.assets.list(folder) ?: emptyArray()
      } catch (_: IOException) {
        emptyArray()
      }
    if (!entries.contains(fileName)) return null
    return try {
      context.assets.open(assetPath).use { it.readBytes() }
    } catch (_: IOException) {
      null
    }
  }

  private fun inspectBundledAssetStream(
    assetPath: String,
  ): StarterAssetRepository.StreamDigestInspection? {
    val folder = assetPath.substringBeforeLast('/', "")
    val fileName = assetPath.substringAfterLast('/')
    val entries =
      try {
        context.assets.list(folder) ?: emptyArray()
      } catch (_: IOException) {
        emptyArray()
      }
    if (!entries.contains(fileName)) return null
    return try {
      context.assets.open(assetPath).use { StarterAssetRepository.inspectStreamHeaderAndSha256(it) }
    } catch (_: IOException) {
      null
    }
  }

  companion object {
    const val IMPORT_SCENERY_DIR = "imported_scenery"
    private val cachedBundledVerifiedEntries =
      java.util.concurrent.ConcurrentHashMap<String, SceneryAssetAuditEntry>()
  }
}
