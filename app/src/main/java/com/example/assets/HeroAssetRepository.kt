package com.example.assets

import android.content.Context
import android.net.Uri
import com.example.garage.HeroVehicleCatalog
import com.example.garage.HeroVehicleSpec
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

enum class HeroModelOrigin {
  BUNDLED_OFFLINE_GLB,
  ON_DEVICE_RUNTIME_IMPORT_GLB,
  IMPORT_REQUIRED_PROCEDURAL_FALLBACK,
}

data class HeroModelAssetStatus(
  val vehicleId: String,
  val displayName: String,
  val fileName: String,
  val origin: HeroModelOrigin,
  val sha256Verified: Boolean,
  val onDeviceImportOnly: Boolean,
  val statusSummary: String,
)

data class LoadedHeroModelBytes(
  val spec: HeroVehicleSpec,
  val glbBytes: ByteArray?,
  val origin: HeroModelOrigin,
  val sha256Verified: Boolean,
  val statusSummary: String,
)

sealed interface HeroImportResult {
  data class Imported(
    val spec: HeroVehicleSpec,
    val sha256Hex: String,
    val message: String,
  ) : HeroImportResult

  data class Rejected(
    val title: String,
    val detail: String,
    val expectedSha256: String,
    val actualSha256: String? = null,
    val previousValidPreserved: Boolean = false,
  ) : HeroImportResult
}

/**
 * Resolves all five Garage hero vehicle models from bundled assets or app-private local imports,
 * enforcing exact binary glTF 2.0 header and SHA-256 verification against placements.json.
 * Supports safe atomic on-device import for NoAI-restricted Hyper Sports (Nissan), preserving
 * any earlier valid file if a subsequent import fails validation and never modifying ownership.
 */
open class HeroAssetRepository(private val context: Context) {
  private val importDir: File
    get() = File(context.filesDir, IMPORT_DIR_NAME)

  open fun loadHeroModel(vehicleId: String): LoadedHeroModelBytes {
    val spec = HeroVehicleCatalog.findById(vehicleId) ?: HeroVehicleCatalog.STARTER_SPEC

    // 1. Check bundled offline APK assets first (unless marked on-device import only, or also check if valid)
    if (!spec.onDeviceImportOnly) {
      val bundled = tryReadBundledBytes(spec)
      if (bundled != null &&
        StarterAssetRepository.hasValidGlbHeader(bundled) &&
        StarterAssetRepository.sha256Hex(bundled).equals(spec.sourceSha256, ignoreCase = true)
      ) {
        return LoadedHeroModelBytes(
          spec = spec,
          glbBytes = bundled,
          origin = HeroModelOrigin.BUNDLED_OFFLINE_GLB,
          sha256Verified = true,
          statusSummary = "Bundled offline GLB (SHA-256 verified)",
        )
      }
    }

    // 2. Check validated app-private persistent local copy (e.g., Nissan runtime import)
    val imported = tryReadImportedBytes(spec)
    if (imported != null &&
      StarterAssetRepository.hasValidGlbHeader(imported) &&
      StarterAssetRepository.sha256Hex(imported).equals(spec.sourceSha256, ignoreCase = true)
    ) {
      return LoadedHeroModelBytes(
        spec = spec,
        glbBytes = imported,
        origin = HeroModelOrigin.ON_DEVICE_RUNTIME_IMPORT_GLB,
        sha256Verified = true,
        statusSummary = "On-device imported GLB (SHA-256 verified)",
      )
    }

    // 3. Genuine model missing -> explicit import-required status (procedural fallback clearly flagged)
    val summary =
      if (spec.onDeviceImportOnly) {
        "Import required: select ${spec.fileName} inside Garage on your device (honest procedural fallback active until imported)"
      } else {
        "Import required: ${spec.fileName} not bundled (honest procedural fallback active)"
      }
    return LoadedHeroModelBytes(
      spec = spec,
      glbBytes = null,
      origin = HeroModelOrigin.IMPORT_REQUIRED_PROCEDURAL_FALLBACK,
      sha256Verified = false,
      statusSummary = summary,
    )
  }

  open fun loadAllHeroStatuses(): List<HeroModelAssetStatus> {
    if (this.javaClass != HeroAssetRepository::class.java) {
      return HeroVehicleCatalog.ALL_CARS.map { spec ->
        val loaded = loadHeroModel(spec.id)
        HeroModelAssetStatus(
          vehicleId = spec.id,
          displayName = spec.displayName,
          fileName = spec.fileName,
          origin = loaded.origin,
          sha256Verified = loaded.sha256Verified,
          onDeviceImportOnly = spec.onDeviceImportOnly,
          statusSummary = loaded.statusSummary,
        )
      }
    }
    return HeroVehicleCatalog.ALL_CARS.map { spec -> inspectHeroStatusStreaming(spec) }
  }

  open fun hasValidImportedFile(vehicleId: String): Boolean {
    val spec = HeroVehicleCatalog.findById(vehicleId) ?: return false
    val file = File(importDir, spec.fileName)
    if (!file.exists() || !file.isFile) return false
    val inspection =
      runCatching {
        file.inputStream().use { StarterAssetRepository.inspectStreamHeaderAndSha256(it) }
      }.getOrNull() ?: return false
    return inspection.hasValidGlbHeader &&
      inspection.sha256Hex.equals(spec.sourceSha256, ignoreCase = true)
  }

  /**
   * Imports the NoAI-restricted Nissan GLB (or any specific hero GLB) via Android SAF URI.
   * Validates glTF 2.0 header and exact SHA-256 in-memory, writes atomically to app-private storage,
   * and preserves any previously validated local file if the new import fails.
   * Does NOT purchase, unlock, or equip the car.
   */
  open fun importHeroGlbFromUri(
    vehicleId: String = HeroVehicleCatalog.HYPER_SPORTS_ID,
    uri: Uri,
  ): HeroImportResult {
    val spec =
      HeroVehicleCatalog.findById(vehicleId)
        ?: return HeroImportResult.Rejected(
          title = "Unknown hero vehicle",
          detail = "No approved vehicle matches '$vehicleId'.",
          expectedSha256 = "",
        )
    val hadPreviousValid = hasValidImportedFile(spec.id)

    val bytes =
      try {
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
          ?: return HeroImportResult.Rejected(
            title = "Unable to open selected GLB",
            detail = "Document provider returned a null stream for ${spec.fileName}.",
            expectedSha256 = spec.sourceSha256,
            previousValidPreserved = hadPreviousValid,
          )
      } catch (e: Exception) {
        return HeroImportResult.Rejected(
          title = "Failed reading selected GLB",
          detail = e.message ?: "Unable to read document.",
          expectedSha256 = spec.sourceSha256,
          previousValidPreserved = hadPreviousValid,
        )
      }

    if (!StarterAssetRepository.hasValidGlbHeader(bytes)) {
      return HeroImportResult.Rejected(
        title = "Invalid GLB binary header",
        detail = "Selected file does not begin with a glTF 2.0 binary header ('glTF').",
        expectedSha256 = spec.sourceSha256,
        actualSha256 = StarterAssetRepository.sha256Hex(bytes),
        previousValidPreserved = hadPreviousValid,
      )
    }

    val actualHash = StarterAssetRepository.sha256Hex(bytes)
    if (!actualHash.equals(spec.sourceSha256, ignoreCase = true)) {
      return HeroImportResult.Rejected(
        title = "${spec.displayName} SHA-256 mismatch",
        detail =
          "Selected file does not match the approved ${spec.fileName} SHA-256 in placements.json." +
            if (hadPreviousValid) " Earlier verified file preserved." else "",
        expectedSha256 = spec.sourceSha256,
        actualSha256 = actualHash,
        previousValidPreserved = hadPreviousValid,
      )
    }

    return persistVerifiedHeroBytes(spec, bytes, actualHash, hadPreviousValid)
  }

  private fun persistVerifiedHeroBytes(
    spec: HeroVehicleSpec,
    bytes: ByteArray,
    actualHash: String,
    hadPreviousValid: Boolean,
  ): HeroImportResult {
    return runCatching {
      val dir = importDir
      if (!dir.exists() && !dir.mkdirs()) {
        error("Could not create app-private directory for ${spec.fileName}.")
      }
      val targetFile = File(dir, spec.fileName)
      val tempFile = File(dir, "${spec.fileName}.tmp")
      FileOutputStream(tempFile, false).use { fos ->
        fos.write(bytes)
        fos.fd.sync()
      }
      if (targetFile.exists() && !tempFile.renameTo(targetFile)) {
        // Fallback copy to target after backup if rename fails on unusual filesystem
        val backupFile = File(dir, "${spec.fileName}.bak")
        targetFile.copyTo(backupFile, overwrite = true)
        try {
          FileOutputStream(targetFile, false).use { fos ->
            fos.write(bytes)
            fos.fd.sync()
          }
          backupFile.delete()
        } catch (writeErr: Exception) {
          if (backupFile.exists()) {
            backupFile.copyTo(targetFile, overwrite = true)
            backupFile.delete()
          }
          throw writeErr
        } finally {
          tempFile.delete()
        }
      } else if (!targetFile.exists() && !tempFile.renameTo(targetFile)) {
        FileOutputStream(targetFile, false).use { fos ->
          fos.write(bytes)
          fos.fd.sync()
        }
        tempFile.delete()
      }
    }.fold(
      onSuccess = {
        HeroImportResult.Imported(
          spec = spec,
          sha256Hex = actualHash,
          message = "Imported ${spec.fileName} locally (SHA-256 verified). Unlock/purchase separately when ready.",
        )
      },
      onFailure = { err ->
        HeroImportResult.Rejected(
          title = "Local storage failure",
          detail = err.message ?: "Failed saving ${spec.fileName}.",
          expectedSha256 = spec.sourceSha256,
          actualSha256 = actualHash,
          previousValidPreserved = hadPreviousValid,
        )
      },
    )
  }

  private fun tryReadBundledBytes(spec: HeroVehicleSpec): ByteArray? {
    val candidates = listOf("models/${spec.fileName}", spec.fileName)
    for (path in candidates) {
      val folder = path.substringBeforeLast('/', "")
      val fileName = path.substringAfterLast('/')
      val entries =
        try {
          context.assets.list(folder) ?: emptyArray()
        } catch (_: IOException) {
          emptyArray()
        }
      if (entries.contains(fileName)) {
        return try {
          context.assets.open(path).use { it.readBytes() }
        } catch (_: IOException) {
          null
        }
      }
    }
    return null
  }

  private fun tryReadImportedBytes(spec: HeroVehicleSpec): ByteArray? {
    val file = File(importDir, spec.fileName)
    return if (file.exists() && file.isFile) {
      try {
        file.readBytes()
      } catch (_: IOException) {
        null
      }
    } else {
      null
    }
  }

  private fun inspectHeroStatusStreaming(spec: HeroVehicleSpec): HeroModelAssetStatus {
    if (!spec.onDeviceImportOnly) {
      cachedVerifiedBundledStatus[spec.id]?.let { return it }
      val bundledInspection = tryInspectBundledStream(spec)
      if (bundledInspection != null &&
        bundledInspection.hasValidGlbHeader &&
        bundledInspection.sha256Hex.equals(spec.sourceSha256, ignoreCase = true)
      ) {
        val verified =
          HeroModelAssetStatus(
            vehicleId = spec.id,
            displayName = spec.displayName,
            fileName = spec.fileName,
            origin = HeroModelOrigin.BUNDLED_OFFLINE_GLB,
            sha256Verified = true,
            onDeviceImportOnly = spec.onDeviceImportOnly,
            statusSummary = "Bundled offline GLB (SHA-256 verified)",
          )
        cachedVerifiedBundledStatus[spec.id] = verified
        return verified
      }
    }

    if (hasValidImportedFile(spec.id)) {
      return HeroModelAssetStatus(
        vehicleId = spec.id,
        displayName = spec.displayName,
        fileName = spec.fileName,
        origin = HeroModelOrigin.ON_DEVICE_RUNTIME_IMPORT_GLB,
        sha256Verified = true,
        onDeviceImportOnly = spec.onDeviceImportOnly,
        statusSummary = "On-device imported GLB (SHA-256 verified)",
      )
    }

    val summary =
      if (spec.onDeviceImportOnly) {
        "Import required: select ${spec.fileName} inside Garage on your device (honest procedural fallback active until imported)"
      } else {
        "Import required: ${spec.fileName} not bundled (honest procedural fallback active)"
      }
    return HeroModelAssetStatus(
      vehicleId = spec.id,
      displayName = spec.displayName,
      fileName = spec.fileName,
      origin = HeroModelOrigin.IMPORT_REQUIRED_PROCEDURAL_FALLBACK,
      sha256Verified = false,
      onDeviceImportOnly = spec.onDeviceImportOnly,
      statusSummary = summary,
    )
  }

  private fun tryInspectBundledStream(
    spec: HeroVehicleSpec,
  ): StarterAssetRepository.StreamDigestInspection? {
    val candidates = listOf("models/${spec.fileName}", spec.fileName)
    for (path in candidates) {
      val folder = path.substringBeforeLast('/', "")
      val fileName = path.substringAfterLast('/')
      val entries =
        try {
          context.assets.list(folder) ?: emptyArray()
        } catch (_: IOException) {
          emptyArray()
        }
      if (entries.contains(fileName)) {
        return try {
          context.assets.open(path).use { StarterAssetRepository.inspectStreamHeaderAndSha256(it) }
        } catch (_: IOException) {
          null
        }
      }
    }
    return null
  }

  companion object {
    const val IMPORT_DIR_NAME = "imported_vehicles"
    private val cachedVerifiedBundledStatus =
      java.util.concurrent.ConcurrentHashMap<String, HeroModelAssetStatus>()
  }
}
