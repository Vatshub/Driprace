package com.example.audio

import android.content.Context
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.log10

data class WavPcmHeaderInfo(
  val audioFormat: Int,
  val channels: Int,
  val sampleRateHz: Int,
  val bitsPerSample: Int,
  val dataOffsetBytes: Int,
  val dataSizeBytes: Int,
  val pcmFrames: Int,
  val fullScaleSampleCount: Int = 0,
  val measuredPeakDbfs: Double = Double.NEGATIVE_INFINITY,
) {
  val isValid48kStereoPcm16: Boolean
    get() =
      audioFormat == 1 &&
        channels == 2 &&
        sampleRateHz == 48_000 &&
        bitsPerSample == 16 &&
        dataSizeBytes > 0 &&
        pcmFrames > 0
}

data class Mp3HeaderInfo(
  val validFrameFound: Boolean,
  val mpegVersionLabel: String,
  val layer: Int,
  val sampleRateHz: Int,
  val channels: Int,
  val bitrateKbps: Int,
  val inspectedFrameCount: Int,
) {
  val isValid48kStereoMp3: Boolean
    get() = validFrameFound && layer == 3 && sampleRateHz == 48_000 && channels == 2
}

data class AudioAssetAuditEntry(
  val spec: PreparedAudioAssetSpec,
  val present: Boolean,
  val actualBytes: Long,
  val actualSha256: String?,
  val bytesMatch: Boolean,
  val sha256Verified: Boolean,
  val decodeHeaderVerified: Boolean,
  val actualPcmFrames: Int?,
  val fullScaleSampleCount: Int,
  val detail: String,
)

data class AudioPackageAuditReport(
  val entries: List<AudioAssetAuditEntry>,
  val verifiedCount: Int,
  val loopVerifiedCount: Int,
  val oneShotVerifiedCount: Int,
  val missingOrInvalidIds: List<String>,
) {
  val allVerified: Boolean
    get() = verifiedCount == AudioCatalog.ALL_AUDIO_ASSETS.size && missingOrInvalidIds.isEmpty()

  val summaryLine: String
    get() =
      if (allVerified) {
        "Audio: $verifiedCount/${entries.size} verified ($loopVerifiedCount WAV loops + $oneShotVerifiedCount MP3 SFX)"
      } else {
        "Audio: $verifiedCount/${entries.size} verified • missing/invalid: ${missingOrInvalidIds.joinToString()}"
      }
}

/**
 * Pure Kotlin RIFF/WAVE and MPEG-1 Layer III inspectors + streaming SHA-256 verifier for the 23
 * prepared audio assets in `app/src/main/assets/audio/`.
 */
object AudioBinaryInspectors {
  fun parseWavHeaderAndScanHeadroom(
    stream: InputStream,
    scanSamplesForHeadroom: Boolean = false,
  ): WavPcmHeaderInfo? {
    val headerPrefix = readExact(stream, 12) ?: return null
    val riff = String(headerPrefix, 0, 4, Charsets.US_ASCII)
    val wave = String(headerPrefix, 8, 4, Charsets.US_ASCII)
    if (riff != "RIFF" || wave != "WAVE") return null

    var offset = 12
    var audioFormat = 0
    var channels = 0
    var sampleRate = 0
    var bitsPerSample = 0
    var dataOffset = -1
    var dataSize = -1

    while (true) {
      val chunkHeader = readExact(stream, 8) ?: break
      offset += 8
      val chunkId = String(chunkHeader, 0, 4, Charsets.US_ASCII)
      val chunkSize =
        ByteBuffer.wrap(chunkHeader, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
      if (chunkSize < 0) return null

      when (chunkId) {
        "fmt " -> {
          val fmtBytes = readExact(stream, chunkSize) ?: return null
          offset += chunkSize
          if (chunkSize >= 16) {
            val bb = ByteBuffer.wrap(fmtBytes).order(ByteOrder.LITTLE_ENDIAN)
            audioFormat = bb.short.toInt() and 0xFFFF
            channels = bb.short.toInt() and 0xFFFF
            sampleRate = bb.int
            bb.int // byteRate
            bb.short // blockAlign
            bitsPerSample = bb.short.toInt() and 0xFFFF
          }
          if (chunkSize % 2 == 1) {
            stream.read()
            offset += 1
          }
        }
        "data" -> {
          dataOffset = offset
          dataSize = chunkSize
          break
        }
        else -> {
          val toSkip = chunkSize.toLong() + (chunkSize % 2).toLong()
          skipFully(stream, toSkip)
          offset += toSkip.toInt()
        }
      }
    }

    if (dataOffset < 0 || dataSize <= 0 || channels <= 0 || bitsPerSample <= 0) return null
    val bytesPerFrame = channels * (bitsPerSample / 8)
    if (bytesPerFrame <= 0) return null
    val pcmFrames = dataSize / bytesPerFrame

    var fullScaleSamples = 0
    var maxAbsSample = 0
    if (scanSamplesForHeadroom && bitsPerSample == 16) {
      val buf = ByteArray(32_768)
      var remaining = dataSize
      while (remaining > 0) {
        val want = minOf(buf.size, remaining)
        val n = stream.read(buf, 0, want)
        if (n <= 0) break
        remaining -= n
        var i = 0
        while (i + 1 < n) {
          val lo = buf[i].toInt() and 0xFF
          val hi = buf[i + 1].toInt()
          val sample = (hi shl 8) or lo
          val mag = if (sample == Short.MIN_VALUE.toInt()) 32768 else abs(sample)
          if (mag >= 32767) fullScaleSamples++
          if (mag > maxAbsSample) maxAbsSample = mag
          i += 2
        }
      }
    }
    val peakDbfs =
      if (maxAbsSample > 0) 20.0 * log10(maxAbsSample.toDouble() / 32768.0)
      else Double.NEGATIVE_INFINITY

    return WavPcmHeaderInfo(
      audioFormat = audioFormat,
      channels = channels,
      sampleRateHz = sampleRate,
      bitsPerSample = bitsPerSample,
      dataOffsetBytes = dataOffset,
      dataSizeBytes = dataSize,
      pcmFrames = pcmFrames,
      fullScaleSampleCount = fullScaleSamples,
      measuredPeakDbfs = peakDbfs,
    )
  }

  fun inspectMp3Bytes(bytes: ByteArray): Mp3HeaderInfo {
    var pos = 0
    // Skip optional ID3v2 tag if present
    if (bytes.size >= 10 &&
      bytes[0] == 'I'.code.toByte() &&
      bytes[1] == 'D'.code.toByte() &&
      bytes[2] == '3'.code.toByte()
    ) {
      val size =
        ((bytes[6].toInt() and 0x7F) shl 21) or
          ((bytes[7].toInt() and 0x7F) shl 14) or
          ((bytes[8].toInt() and 0x7F) shl 7) or
          (bytes[9].toInt() and 0x7F)
      pos = 10 + size
    }

    var firstSampleRate = 0
    var firstChannels = 0
    var firstBitrate = 0
    var versionLabel = ""
    var validFrames = 0

    while (pos + 4 <= bytes.size) {
      val b0 = bytes[pos].toInt() and 0xFF
      val b1 = bytes[pos + 1].toInt() and 0xFF
      if (b0 == 0xFF && (b1 and 0xE0) == 0xE0) {
        val versionBits = (b1 shr 3) and 0x03
        val layerBits = (b1 shr 1) and 0x03
        val b2 = bytes[pos + 2].toInt() and 0xFF
        val b3 = bytes[pos + 3].toInt() and 0xFF
        val bitrateIdx = (b2 shr 4) and 0x0F
        val srIdx = (b2 shr 2) and 0x03
        val padding = (b2 shr 1) and 0x01
        val channelMode = (b3 shr 6) and 0x03

        if (versionBits == 3 && layerBits == 1 && bitrateIdx in 1..14 && srIdx in 0..2) {
          val bitratesMpeg1L3 =
            intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0)
          val sampleRatesMpeg1 = intArrayOf(44_100, 48_000, 32_000)
          val bitrate = bitratesMpeg1L3[bitrateIdx]
          val sr = sampleRatesMpeg1[srIdx]
          val frameLen = (144 * bitrate * 1000) / sr + padding
          if (frameLen > 24) {
            if (validFrames == 0) {
              firstSampleRate = sr
              firstChannels = if (channelMode == 3) 1 else 2
              firstBitrate = bitrate
              versionLabel = "MPEG-1"
            }
            validFrames++
            pos += frameLen
            continue
          }
        }
      }
      pos++
    }

    return Mp3HeaderInfo(
      validFrameFound = validFrames > 0,
      mpegVersionLabel = versionLabel,
      layer = if (validFrames > 0) 3 else 0,
      sampleRateHz = firstSampleRate,
      channels = firstChannels,
      bitrateKbps = firstBitrate,
      inspectedFrameCount = validFrames,
    )
  }

  private fun readExact(stream: InputStream, count: Int): ByteArray? {
    val out = ByteArray(count)
    var readTotal = 0
    while (readTotal < count) {
      val r = stream.read(out, readTotal, count - readTotal)
      if (r < 0) return null
      readTotal += r
    }
    return out
  }

  private fun skipFully(stream: InputStream, bytesToSkip: Long) {
    var remaining = bytesToSkip
    val scratch = ByteArray(4096)
    while (remaining > 0L) {
      val skipped = stream.skip(remaining)
      if (skipped > 0L) {
        remaining -= skipped
      } else {
        val r = stream.read(scratch, 0, minOf(scratch.size.toLong(), remaining).toInt())
        if (r < 0) break
        remaining -= r
      }
    }
  }
}

open class AudioAssetRepository(private val context: Context) {

  /**
   * Verifies all 23 prepared audio files (`audio/<fileName>`) against `references/audio/manifest.json`
   * filenames, exact byte sizes, SHA-256 digests, and WAV/MP3 48 kHz stereo headers without
   * materializing the 17 MB / 22 MB music WAVs on the JVM heap at once.
   */
  open fun auditAudioPackage(scanWavHeadroom: Boolean = false): AudioPackageAuditReport {
    if (!scanWavHeadroom) {
      cachedBaselineVerifiedReport?.let { return it }
    }
    val availableInAudioDir =
      runCatching { context.assets.list("audio")?.toSet() ?: emptySet() }.getOrDefault(emptySet())

    val entries = mutableListOf<AudioAssetAuditEntry>()
    val missingOrInvalid = mutableListOf<String>()
    var verified = 0
    var loopsOk = 0
    var oneShotsOk = 0

    for (spec in AudioCatalog.ALL_AUDIO_ASSETS) {
      if (spec.fileName !in availableInAudioDir) {
        missingOrInvalid += spec.id
        entries +=
          AudioAssetAuditEntry(
            spec = spec,
            present = false,
            actualBytes = 0L,
            actualSha256 = null,
            bytesMatch = false,
            sha256Verified = false,
            decodeHeaderVerified = false,
            actualPcmFrames = null,
            fullScaleSampleCount = 0,
            detail = "Missing asset ${spec.assetPath}",
          )
        continue
      }

      val hashAndSize = computeStreamingSha256AndSize(spec.assetPath)
      if (hashAndSize == null) {
        missingOrInvalid += spec.id
        entries +=
          AudioAssetAuditEntry(
            spec = spec,
            present = false,
            actualBytes = 0L,
            actualSha256 = null,
            bytesMatch = false,
            sha256Verified = false,
            decodeHeaderVerified = false,
            actualPcmFrames = null,
            fullScaleSampleCount = 0,
            detail = "Unreadable asset ${spec.assetPath}",
          )
        continue
      }

      val (actualBytes, actualSha) = hashAndSize
      val sizeOk = actualBytes == spec.expectedBytes
      val shaOk = actualSha.equals(spec.expectedSha256, ignoreCase = true)

      var decodeOk = false
      var actualFrames: Int? = null
      var fullScaleCount = 0
      var detail = ""

      when (spec.format) {
        AudioFormatKind.WAV_LOOP_PCM16 -> {
          val wavInfo =
            runCatching {
                context.assets.open(spec.assetPath).use { input ->
                  AudioBinaryInspectors.parseWavHeaderAndScanHeadroom(
                    stream = input,
                    scanSamplesForHeadroom = scanWavHeadroom,
                  )
                }
              }
              .getOrNull()
          if (wavInfo != null &&
            wavInfo.isValid48kStereoPcm16 &&
            wavInfo.pcmFrames == spec.expectedPcmFrames
          ) {
            decodeOk = true
            actualFrames = wavInfo.pcmFrames
            fullScaleCount = wavInfo.fullScaleSampleCount
            detail =
              "WAV 48kHz stereo PCM16 verified (${wavInfo.pcmFrames} frames)"
          } else {
            detail = "WAV header/frame mismatch: $wavInfo"
          }
        }
        AudioFormatKind.MP3_ONESHOT -> {
          val mp3Bytes =
            runCatching { context.assets.open(spec.assetPath).use { it.readBytes() } }.getOrNull()
          val mp3Info = mp3Bytes?.let { AudioBinaryInspectors.inspectMp3Bytes(it) }
          if (mp3Info != null && mp3Info.isValid48kStereoMp3 && mp3Info.inspectedFrameCount >= 20) {
            decodeOk = true
            actualFrames = spec.expectedPcmFrames
            detail =
              "MP3 48kHz stereo verified (${mp3Info.inspectedFrameCount} MPEG frames, ${mp3Info.bitrateKbps} kbps)"
          } else {
            detail = "MP3 header inspection failed: $mp3Info"
          }
        }
      }

      val entryOk = sizeOk && shaOk && decodeOk && (!scanWavHeadroom || fullScaleCount == 0)
      if (entryOk) {
        verified++
        if (spec.isLoop) loopsOk++ else oneShotsOk++
      } else {
        missingOrInvalid += spec.id
      }

      entries +=
        AudioAssetAuditEntry(
          spec = spec,
          present = true,
          actualBytes = actualBytes,
          actualSha256 = actualSha,
          bytesMatch = sizeOk,
          sha256Verified = shaOk,
          decodeHeaderVerified = decodeOk,
          actualPcmFrames = actualFrames,
          fullScaleSampleCount = fullScaleCount,
          detail = detail,
        )
    }

    val report =
      AudioPackageAuditReport(
        entries = entries,
        verifiedCount = verified,
        loopVerifiedCount = loopsOk,
        oneShotVerifiedCount = oneShotsOk,
        missingOrInvalidIds = missingOrInvalid,
      )
    if (!scanWavHeadroom && report.allVerified) {
      cachedBaselineVerifiedReport = report
    }
    return report
  }

  /**
   * Reads the raw 16-bit PCM payload (without RIFF header) for short gameplay WAV loops
   * (`engine-*`, `nitro-sustain`, `road-noise`, `wind`) so `AudioTrack` (`MODE_STATIC`) can loop
   * exact sample frame `[0, pcmFrames)` seamlessly without `MediaPlayer` gap or `SoundPool` 1 MB truncation.
   */
  open fun loadShortWavLoopPcmPayload(spec: PreparedAudioAssetSpec): Pair<WavPcmHeaderInfo, ByteArray>? {
    require(spec.format == AudioFormatKind.WAV_LOOP_PCM16) {
      "${spec.id} is not a WAV PCM16 loop."
    }
    return runCatching {
        context.assets.open(spec.assetPath).use { stream ->
          val info =
            AudioBinaryInspectors.parseWavHeaderAndScanHeadroom(stream, scanSamplesForHeadroom = false)
              ?: return@use null
          if (!info.isValid48kStereoPcm16) return@use null
          val pcmBytes = ByteArray(info.dataSizeBytes)
          var total = 0
          while (total < pcmBytes.size) {
            val r = stream.read(pcmBytes, total, pcmBytes.size - total)
            if (r <= 0) break
            total += r
          }
          if (total != pcmBytes.size) return@use null
          info to pcmBytes
        }
      }
      .getOrNull()
  }

  private fun computeStreamingSha256AndSize(assetPath: String): Pair<Long, String>? {
    return runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        var totalBytes = 0L
        val buffer = ByteArray(65_536)
        context.assets.open(assetPath).use { stream ->
          while (true) {
            val read = stream.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
            totalBytes += read
          }
        }
        val hex = digest.digest().joinToString("") { "%02x".format(it) }
        totalBytes to hex
      }
      .getOrNull()
  }

  companion object {
    @Volatile
    private var cachedBaselineVerifiedReport: AudioPackageAuditReport? = null
  }
}
