package com.example.assets

import android.content.Context
import android.net.Uri
import com.example.traffic.ApprovedTrafficSpec
import com.example.traffic.TrafficCatalog
import com.example.traffic.TrafficModelAssetState
import com.example.traffic.TrafficModelOrigin
import java.io.File
import java.io.IOException

data class LoadedTrafficModelBytes(
  val spec: ApprovedTrafficSpec,
  val glbBytes: ByteArray?,
  val origin: TrafficModelOrigin,
  val sha256Verified: Boolean,
  val statusSummary: String,
)

data class TrafficInventoryReport(
  val states: List<TrafficModelAssetState>,
  val bundledCount: Int,
  val onDeviceImportedCount: Int,
  val fallbackCount: Int,
  val missingPermittedFilenames: List<String>,
  val sedanOnDevicePending: Boolean,
) {
  val summaryLine: String
    get() =
      buildString {
        append("Traffic GLBs: ${bundledCount + onDeviceImportedCount}/${states.size} genuine")
        if (onDeviceImportedCount > 0) append(" ($onDeviceImportedCount on-device)")
        if (fallbackCount > 0) append(" • $fallbackCount dimensioned fallback")
        if (sedanOnDevicePending) append(" • Sedan pending local pick")
      }
}

sealed interface TrafficImportResult {
  data class Imported(
    val spec: ApprovedTrafficSpec,
    val sha256Hex: String,
    val message: String,
  ) : TrafficImportResult

  data class Rejected(
    val title: String,
    val detail: String,
    val expectedSha256: String,
    val actualSha256: String? = null,
  ) : TrafficImportResult
}

open class TrafficAssetRepository(private val context: Context) {
  private val importDir: File
    get() = File(context.filesDir, IMPORT_DIR_NAME)

  open fun loadAllTrafficAssets(): Pair<List<LoadedTrafficModelBytes>, TrafficInventoryReport> {
    val loaded = mutableListOf<LoadedTrafficModelBytes>()
    val states = mutableListOf<TrafficModelAssetState>()
    val missingPermitted = mutableListOf<String>()
    var bundledCount = 0
    var importedCount = 0
    var fallbackCount = 0
    var sedanPending = false

    for (spec in TrafficCatalog.APPROVED_TRAFFIC_VEHICLES) {
      val bundledBytes = tryReadBundledBytes(spec)
      if (bundledBytes != null &&
        StarterAssetRepository.hasValidGlbHeader(bundledBytes) &&
        StarterAssetRepository.sha256Hex(bundledBytes).equals(spec.sourceSha256, ignoreCase = true)
      ) {
        bundledCount++
        val summary = "Bundled offline GLB (SHA-256 verified)"
        loaded += LoadedTrafficModelBytes(spec, bundledBytes, TrafficModelOrigin.BUNDLED_OFFLINE_GLB, true, summary)
        states +=
          TrafficModelAssetState(
            id = spec.id,
            fileName = spec.fileName,
            displayName = spec.displayName,
            category = spec.category,
            origin = TrafficModelOrigin.BUNDLED_OFFLINE_GLB,
            sha256Verified = true,
            onDeviceImportOnly = spec.onDeviceImportOnly,
            statusSummary = summary,
          )
        continue
      }

      val importedBytes = tryReadImportedBytes(spec)
      if (importedBytes != null &&
        StarterAssetRepository.hasValidGlbHeader(importedBytes) &&
        StarterAssetRepository.sha256Hex(importedBytes).equals(spec.sourceSha256, ignoreCase = true)
      ) {
        importedCount++
        val summary = "On-device imported GLB (SHA-256 verified)"
        loaded +=
          LoadedTrafficModelBytes(
            spec,
            importedBytes,
            TrafficModelOrigin.ON_DEVICE_RUNTIME_IMPORT_GLB,
            true,
            summary,
          )
        states +=
          TrafficModelAssetState(
            id = spec.id,
            fileName = spec.fileName,
            displayName = spec.displayName,
            category = spec.category,
            origin = TrafficModelOrigin.ON_DEVICE_RUNTIME_IMPORT_GLB,
            sha256Verified = true,
            onDeviceImportOnly = spec.onDeviceImportOnly,
            statusSummary = summary,
          )
        continue
      }

      fallbackCount++
      val summary =
        if (spec.onDeviceImportOnly) {
          sedanPending = true
          "Pending on-device import (select ${spec.fileName} inside app); using dimensioned fallback"
        } else {
          missingPermitted += spec.fileName
          "Missing ${spec.fileName}; using dimensioned fallback"
        }
      loaded +=
        LoadedTrafficModelBytes(
          spec = spec,
          glbBytes = null,
          origin = TrafficModelOrigin.PROCEDURAL_FALLBACK_PENDING_IMPORT,
          sha256Verified = false,
          statusSummary = summary,
        )
      states +=
        TrafficModelAssetState(
          id = spec.id,
          fileName = spec.fileName,
          displayName = spec.displayName,
          category = spec.category,
          origin = TrafficModelOrigin.PROCEDURAL_FALLBACK_PENDING_IMPORT,
          sha256Verified = false,
          onDeviceImportOnly = spec.onDeviceImportOnly,
          statusSummary = summary,
        )
    }

    val report =
      TrafficInventoryReport(
        states = states,
        bundledCount = bundledCount,
        onDeviceImportedCount = importedCount,
        fallbackCount = fallbackCount,
        missingPermittedFilenames = missingPermitted,
        sedanOnDevicePending = sedanPending,
      )
    return loaded to report
  }

  /**
   * Imports any approved traffic GLB selected on-device via SAF picker, automatically identifying
   * the matching model by its exact glTF header and SHA-256 digest in placements.json (works for
   * generic-sedan-mmcworks.glb as well as >30 MB traffic GLBs that cannot be uploaded via web UI).
   */
  open fun importAnyTrafficGlbFromUri(uri: Uri): TrafficImportResult {
    val bytes =
      try {
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
          ?: return TrafficImportResult.Rejected(
            title = "Unable to open selected GLB",
            detail = "Document provider returned a null stream.",
            expectedSha256 = "",
          )
      } catch (e: Exception) {
        return TrafficImportResult.Rejected(
          title = "Failed reading selected GLB",
          detail = e.message ?: "Unable to read document.",
          expectedSha256 = "",
        )
      }

    if (!StarterAssetRepository.hasValidGlbHeader(bytes)) {
      return TrafficImportResult.Rejected(
        title = "Invalid GLB binary header",
        detail = "Selected file does not begin with a glTF 2.0 binary header ('glTF').",
        expectedSha256 = "",
        actualSha256 = StarterAssetRepository.sha256Hex(bytes),
      )
    }

    val actualHash = StarterAssetRepository.sha256Hex(bytes)
    val matchedSpec =
      TrafficCatalog.APPROVED_TRAFFIC_VEHICLES.firstOrNull {
        it.sourceSha256.equals(actualHash, ignoreCase = true)
      }
        ?: return TrafficImportResult.Rejected(
          title = "Unrecognized traffic GLB SHA-256",
          detail = "Selected GLB hash does not match any approved traffic vehicle in placements.json.",
          expectedSha256 = "Approved catalog hash",
          actualSha256 = actualHash,
        )

    return persistVerifiedTrafficBytes(matchedSpec, bytes, actualHash)
  }

  /**
   * Imports a specific approved traffic GLB file directly on-device (e.g. NoAI-restricted
   * generic-sedan-mmcworks.glb), validates its glTF 2.0 binary header and exact SHA-256,
   * and persists the unmodified bytes locally in app-private storage.
   */
  open fun importTrafficGlbFromUri(specId: String, uri: Uri): TrafficImportResult {
    val spec =
      TrafficCatalog.findById(specId)
        ?: return TrafficImportResult.Rejected(
          title = "Unknown traffic model ID",
          detail = "No approved traffic vehicle matches '$specId'.",
          expectedSha256 = "",
        )

    val bytes =
      try {
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
          ?: return TrafficImportResult.Rejected(
            title = "Unable to open selected GLB",
            detail = "Document provider returned a null stream for ${spec.fileName}.",
            expectedSha256 = spec.sourceSha256,
          )
      } catch (e: Exception) {
        return TrafficImportResult.Rejected(
          title = "Failed reading selected GLB",
          detail = e.message ?: "Unable to read document.",
          expectedSha256 = spec.sourceSha256,
        )
      }

    if (!StarterAssetRepository.hasValidGlbHeader(bytes)) {
      return TrafficImportResult.Rejected(
        title = "Invalid GLB binary header",
        detail = "Selected file does not begin with a glTF 2.0 binary header ('glTF').",
        expectedSha256 = spec.sourceSha256,
        actualSha256 = StarterAssetRepository.sha256Hex(bytes),
      )
    }

    val actualHash = StarterAssetRepository.sha256Hex(bytes)
    if (!actualHash.equals(spec.sourceSha256, ignoreCase = true)) {
      return TrafficImportResult.Rejected(
        title = "${spec.fileName} SHA-256 mismatch",
        detail = "Selected file does not match the approved SHA-256 in placements.json.",
        expectedSha256 = spec.sourceSha256,
        actualSha256 = actualHash,
      )
    }

    return persistVerifiedTrafficBytes(spec, bytes, actualHash)
  }

  private fun persistVerifiedTrafficBytes(
    spec: ApprovedTrafficSpec,
    bytes: ByteArray,
    actualHash: String,
  ): TrafficImportResult {
    val dir = importDir
    if (!dir.exists() && !dir.mkdirs()) {
      return TrafficImportResult.Rejected(
        title = "Storage directory unavailable",
        detail = "Could not create app-private directory for ${spec.fileName}.",
        expectedSha256 = spec.sourceSha256,
        actualSha256 = actualHash,
      )
    }

    val targetFile = File(dir, spec.fileName)
    val tempFile = File(dir, "${spec.fileName}.tmp")
    tempFile.writeBytes(bytes)
    if (targetFile.exists()) {
      targetFile.delete()
    }
    if (!tempFile.renameTo(targetFile)) {
      targetFile.writeBytes(bytes)
      tempFile.delete()
    }

    return TrafficImportResult.Imported(
      spec = spec,
      sha256Hex = actualHash,
      message = "Imported ${spec.fileName} on-device (SHA-256 verified)",
    )
  }

  private fun tryReadBundledBytes(spec: ApprovedTrafficSpec): ByteArray? {
    val candidates =
      listOf(
        "models/traffic/${spec.fileName}",
        "models/${spec.fileName}",
        spec.fileName,
      )
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

  private fun tryReadImportedBytes(spec: ApprovedTrafficSpec): ByteArray? {
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

  companion object {
    const val IMPORT_DIR_NAME = "imported_traffic"
  }
}
