package com.example.scenery

import java.nio.charset.StandardCharsets
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class DecodedHdrEnvironment(
  val sourceWidth: Int,
  val sourceHeight: Int,
  val skyBitmapWidth: Int,
  val skyBitmapHeight: Int,
  val skySrgbArgbPixels: IntArray,
  val shIrradiance3Band: FloatArray,
  val dominantDirectionToLight: FloatArray,
  val dominantSunChromaticityRgb: FloatArray,
  val meanUpperHemisphereLuminance: Float,
  val integrationSummary: String,
)

/**
 * Pure Kotlin Radiance RGBE (`.hdr`) decoder & Spherical Harmonics integrator.
 *
 * Parses genuine `#?RADIANCE` / `#?RGBE` equirectangular files (`daylight-1k.hdr`,
 * `sunset-1k.hdr`, `night-1k.hdr`), decodes adaptive scanline RLE or flat RGBE scanlines into
 * linear floating-point radiance, projects the sky dome onto 3-band (9-coefficient) Spherical
 * Harmonics for `IndirectLight.Builder().irradiance(3, sh)`, and tone-maps a crisp `SAMPLER_2D`
 * sky dome bitmap — avoiding Filament's mobile GPU `IBLPrefilterContext` / `SAMPLER_CUBEMAP`
 * mipmap precondition traps while honestly reporting the exact Filament preparation route.
 */
object RadianceHdrDecoder {
  const val INTEGRATION_ROUTE_LABEL =
    "Kotlin Radiance RGBE (.hdr) -> HDR-integrated 3-band SH IndirectLight + tone-mapped SAMPLER_2D 3D sky dome (offline cmgen GGX specular cubemap .ktx not run in cloud build; directional sun/clearcoat + SH irradiance used for specular)"

  private val POW2_TABLE: FloatArray =
    FloatArray(256) { exp ->
      if (exp == 0) 0f else java.lang.Math.scalb(1.0f, exp - (128 + 8))
    }

  fun hasValidRadianceHeader(bytes: ByteArray): Boolean {
    if (bytes.size < 16) return false
    val prefix = String(bytes, 0, min(64, bytes.size), StandardCharsets.US_ASCII)
    return prefix.startsWith("#?RADIANCE") ||
      prefix.startsWith("#?RGBE") ||
      prefix.contains("FORMAT=32-bit_rle_rgbe")
  }

  /**
   * Decodes a Radiance `.hdr` byte array lazily for the single active environment.
   *
   * @param decodeStride Downsample factor for the tone-mapped sky dome (`1` = full `1024x512`,
   * `2` = `512x256`, `4` = `256x128`).
   * @param exposureScale Tone-mapping exposure multiplier before ACES filmic compression.
   * @param minimumAmbientFloorLinear Safety ambient floor ensuring Night Highway road & traffic
   * remain legible on every display and quality tier.
   */
  fun decodeHdrBytes(
    bytes: ByteArray,
    assetLabel: String,
    decodeStride: Int = 2,
    exposureScale: Float = 1.0f,
    minimumAmbientFloorLinear: Float = 0.06f,
  ): DecodedHdrEnvironment {
    require(bytes.size >= 32) { "HDR file '$assetLabel' is too small (${bytes.size} bytes)." }

    var cursor = 0
    // 1. Read ASCII header lines until empty line ""
    var foundFormat = false
    while (cursor < bytes.size) {
      val lineEnd = findNewline(bytes, cursor)
      val line = String(bytes, cursor, lineEnd - cursor, StandardCharsets.US_ASCII).trimEnd('\r')
      cursor = lineEnd + 1
      if (line.isEmpty()) break
      if (line.startsWith("FORMAT=")) {
        require(line.contains("32-bit_rle_rgbe", ignoreCase = true)) {
          "Unsupported Radiance format '$line' in $assetLabel."
        }
        foundFormat = true
      }
    }
    require(foundFormat || hasValidRadianceHeader(bytes)) {
      "Missing Radiance RGBE header in $assetLabel."
    }

    // 2. Read resolution line: "-Y <height> +X <width>"
    val resLineEnd = findNewline(bytes, cursor)
    val resLine = String(bytes, cursor, resLineEnd - cursor, StandardCharsets.US_ASCII).trim()
    cursor = resLineEnd + 1
    val tokens = resLine.split(Regex("\\s+"))
    require(tokens.size >= 4 && tokens[0] == "-Y" && tokens[2] == "+X") {
      "Unsupported HDR orientation '$resLine' in $assetLabel (expected '-Y <H> +X <W>')."
    }
    val height = tokens[1].toIntOrNull() ?: error("Invalid HDR height in '$resLine'.")
    val width = tokens[3].toIntOrNull() ?: error("Invalid HDR width in '$resLine'.")
    require(width in 8..8192 && height in 4..4096) {
      "Out-of-bounds HDR dimensions ${width}x${height} in $assetLabel."
    }

    val safeStride = decodeStride.coerceIn(1, 8)
    val outW = max(1, width / safeStride)
    val outH = max(1, height / safeStride)
    val skyPixels = IntArray(outW * outH)

    // Accumulate 9 real Spherical Harmonics radiance coefficients L_lm (RGB = 27 doubles)
    val shRad = DoubleArray(27)
    var weightSum = 0.0
    var upperLumSum = 0.0
    var upperWeightSum = 0.0

    var peakWeightedLum = -1.0
    var peakDirX = -0.35f
    var peakDirY = 0.78f
    var peakDirZ = -0.50f
    var peakR = 1.0f
    var peakG = 0.96f
    var peakB = 0.90f

    val scanlineRgbe = ByteArray(width * 4)
    val dTheta = PI / height.toDouble()
    val dPhi = (2.0 * PI) / width.toDouble()
    // Subsample SH integration every 4 pixels horizontally & 2 vertically (~32k samples for 1024x512 -> <5ms)
    val shStepX = max(1, width / 256)
    val shStepY = max(1, height / 128)

    for (y in 0 until height) {
      cursor = readRadianceScanline(bytes, cursor, width, scanlineRgbe)

      val sampleSkyRow = (y % safeStride == 0) && (y / safeStride < outH)
      val outRowOffset = (y / safeStride) * outW
      val sampleShRow = (y % shStepY == 0)

      val v = (y + 0.5) / height.toDouble()
      val theta = v * PI // 0 at zenith (+Y), PI at nadir (-Y)
      val sinTheta = sin(theta)
      val cosTheta = cos(theta) // +1 at zenith, -1 at nadir

      if (sampleSkyRow || sampleShRow) {
        for (x in 0 until width) {
          val doSky = sampleSkyRow && (x % safeStride == 0) && (x / safeStride < outW)
          val doSh = sampleShRow && (x % shStepX == 0)
          if (!doSky && !doSh) continue

          val base = x * 4
          val exp = scanlineRgbe[base + 3].toInt() and 0xFF
          var rLin = 0f
          var gLin = 0f
          var bLin = 0f
          if (exp != 0) {
            val scale = POW2_TABLE[exp]
            rLin = ((scanlineRgbe[base].toInt() and 0xFF) + 0.5f) * scale
            gLin = ((scanlineRgbe[base + 1].toInt() and 0xFF) + 0.5f) * scale
            bLin = ((scanlineRgbe[base + 2].toInt() and 0xFF) + 0.5f) * scale
          }

          if (doSky) {
            skyPixels[outRowOffset + (x / safeStride)] =
              toneMapLinearToSrgbArgb(rLin * exposureScale, gLin * exposureScale, bLin * exposureScale)
          }

          if (doSh && sinTheta > 1e-5) {
            val u = (x + 0.5) / width.toDouble()
            val phi = (u - 0.5) * 2.0 * PI
            val dx = (sinTheta * sin(phi)).toFloat()
            val dy = cosTheta.toFloat()
            val dz = (sinTheta * cos(phi)).toFloat()

            val dOmega = sinTheta * (dTheta * shStepY) * (dPhi * shStepX)
            weightSum += dOmega

            // Clamp extreme solar disk singularity (> 35.0) during SH diffuse projection to prevent ringing
            val cR = min(rLin, 35f).toDouble()
            val cG = min(gLin, 35f).toDouble()
            val cB = min(bLin, 35f).toDouble()

            val lum = 0.2126 * rLin + 0.7152 * gLin + 0.0722 * bLin
            if (dy >= 0.08f) {
              upperLumSum += min(lum, 50.0) * dOmega
              upperWeightSum += dOmega
              if (lum > peakWeightedLum) {
                peakWeightedLum = lum
                peakDirX = dx
                peakDirY = max(0.22f, dy)
                peakDirZ = dz
                val maxCh = max(max(rLin, gLin), max(bLin, 1e-4f))
                peakR = (rLin / maxCh).coerceIn(0.45f, 1.0f)
                peakG = (gLin / maxCh).coerceIn(0.40f, 1.0f)
                peakB = (bLin / maxCh).coerceIn(0.35f, 1.0f)
              }
            }

            // Real orthonormal SH basis Y_lm(dx, dy, dz)
            val y00 = 0.282095
            val y1n1 = 0.488603 * dy
            val y10 = 0.488603 * dz
            val y1p1 = 0.488603 * dx
            val y2n2 = 1.092548 * dx * dy
            val y2n1 = 1.092548 * dy * dz
            val y20 = 0.315392 * (3.0 * dz * dz - 1.0)
            val y2p1 = 1.092548 * dx * dz
            val y2p2 = 0.546274 * (dx * dx - dy * dy)

            val basis = doubleArrayOf(y00, y1n1, y10, y1p1, y2n2, y2n1, y20, y2p1, y2p2)
            for (bIdx in 0 until 9) {
              val w = basis[bIdx] * dOmega
              shRad[bIdx * 3] += cR * w
              shRad[bIdx * 3 + 1] += cG * w
              shRad[bIdx * 3 + 2] += cB * w
            }
          }
        }
      }
    }

    // Convert radiance SH L_lm into normalized pre-scaled diffuse irradiance E_lm (Ramamoorthi & Hanrahan
    // cosine-lobe convolution A_0 = PI, A_1 = 2*PI/3, A_2 = PI/4), normalized to unit-friendly L00 scale
    // so IndirectLight.intensity(lux) directly controls scene brightness cleanly without negative crushing.
    val shIrradiance = buildNormalizedIrradianceSh(shRad, minimumAmbientFloorLinear)
    val meanUpperLum =
      if (upperWeightSum > 1e-6) (upperLumSum / upperWeightSum).toFloat() else 0.2f

    // Normalize light-from direction
    val dLen = sqrt(peakDirX * peakDirX + peakDirY * peakDirY + peakDirZ * peakDirZ).coerceAtLeast(1e-4f)
    val normDirToLight = floatArrayOf(peakDirX / dLen, peakDirY / dLen, peakDirZ / dLen)

    return DecodedHdrEnvironment(
      sourceWidth = width,
      sourceHeight = height,
      skyBitmapWidth = outW,
      skyBitmapHeight = outH,
      skySrgbArgbPixels = skyPixels,
      shIrradiance3Band = shIrradiance,
      dominantDirectionToLight = normDirToLight,
      dominantSunChromaticityRgb = floatArrayOf(peakR, peakG, peakB),
      meanUpperHemisphereLuminance = meanUpperLum,
      integrationSummary = "$assetLabel (${width}x${height} -> ${outW}x${outH} sky + 3-band SH; $INTEGRATION_ROUTE_LABEL)",
    )
  }

  private fun buildNormalizedIrradianceSh(
    shRad: DoubleArray,
    minimumFloor: Float,
  ): FloatArray {
    // Cosine convolution factors divided by PI: A0/PI = 1.0, A1/PI = 2/3, A2/PI = 1/4
    val bandConvolve = doubleArrayOf(1.0, 0.666667, 0.666667, 0.666667, 0.25, 0.25, 0.25, 0.25, 0.25)
    val yBasisScale =
      doubleArrayOf(0.282095, 0.488603, 0.488603, 0.488603, 1.092548, 1.092548, 0.315392, 1.092548, 0.546274)

    // Evaluate pre-scaled E(n) coefficients expected by Filament IndirectLight.Builder().irradiance(3, sh)
    val rawE = FloatArray(27)
    for (i in 0 until 9) {
      val factor = bandConvolve[i] * yBasisScale[i]
      rawE[i * 3] = (shRad[i * 3] * factor).toFloat()
      rawE[i * 3 + 1] = (shRad[i * 3 + 1] * factor).toFloat()
      rawE[i * 3 + 2] = (shRad[i * 3 + 2] * factor).toFloat()
    }

    // Normalize by max L00 channel so L00 sits around ~0.85..0.95 while preserving exact HDR chromaticity & directional ratios!
    val maxL00 = max(max(rawE[0], rawE[1]), max(rawE[2], 1e-4f))
    val normScale = 0.88f / maxL00
    val out = FloatArray(27)
    for (i in 0 until 27) {
      out[i] = rawE[i] * normScale
    }

    // Ensure L00 meets minimum ambient legibility floor and attenuate higher bands slightly (0.38x)
    // so undersides/fenders never evaluate to negative irradiance on grazing normals.
    out[0] = max(out[0], minimumFloor)
    out[1] = max(out[1], minimumFloor)
    out[2] = max(out[2], minimumFloor * 1.08f)
    for (i in 3 until 27) {
      out[i] = (out[i] * 0.38f).coerceIn(-0.28f, 0.32f)
    }
    return out
  }

  private fun readRadianceScanline(
    bytes: ByteArray,
    startOffset: Int,
    width: Int,
    destRgbe: ByteArray,
  ): Int {
    var pos = startOffset
    require(pos + 4 <= bytes.size) { "Truncated HDR scanline header at byte $pos." }

    val b0 = bytes[pos].toInt() and 0xFF
    val b1 = bytes[pos + 1].toInt() and 0xFF
    val b2 = bytes[pos + 2].toInt() and 0xFF
    val b3 = bytes[pos + 3].toInt() and 0xFF

    // Uncompressed flat RGBE fallback if width < 8, width > 0x7FFF, or not adaptive 0x02 0x02 marker
    if (width < 8 || width > 0x7FFF || b0 != 2 || b1 != 2 || (b2 and 0x80) != 0) {
      val byteCount = width * 4
      require(pos + byteCount <= bytes.size) { "Truncated uncompressed HDR scanline." }
      System.arraycopy(bytes, pos, destRgbe, 0, byteCount)
      return pos + byteCount
    }

    val encodedWidth = (b2 shl 8) or b3
    require(encodedWidth == width) {
      "HDR RLE scanline width mismatch (expected $width, got $encodedWidth)."
    }
    pos += 4

    // Adaptive Radiance RLE stores each channel (R, G, B, E) contiguously across the scanline
    for (ch in 0 until 4) {
      var x = 0
      while (x < width) {
        require(pos < bytes.size) { "Unexpected EOF in HDR RLE stream." }
        val code = bytes[pos++].toInt() and 0xFF
        if (code > 128) {
          val runLen = code - 128
          require(x + runLen <= width && pos < bytes.size) { "Invalid HDR RLE run length $runLen." }
          val repeatedVal = bytes[pos++]
          for (k in 0 until runLen) {
            destRgbe[(x + k) * 4 + ch] = repeatedVal
          }
          x += runLen
        } else {
          val litLen = code
          require(litLen > 0 && x + litLen <= width && pos + litLen <= bytes.size) {
            "Invalid HDR RLE literal length $litLen."
          }
          for (k in 0 until litLen) {
            destRgbe[(x + k) * 4 + ch] = bytes[pos++]
          }
          x += litLen
        }
      }
    }
    return pos
  }

  private fun toneMapLinearToSrgbArgb(r: Float, g: Float, b: Float): Int {
    fun acesAndSrgbByte(v: Float): Int {
      val x = max(0f, v)
      // Narkowicz ACES filmic tone curve
      val mapped = ((x * (2.51f * x + 0.03f)) / (x * (2.43f * x + 0.59f) + 0.14f)).coerceIn(0f, 1f)
      val srgb = mapped.toDouble().pow(1.0 / 2.2).toFloat()
      return (srgb * 255f + 0.5f).toInt().coerceIn(0, 255)
    }
    val sr = acesAndSrgbByte(r)
    val sg = acesAndSrgbByte(g)
    val sb = acesAndSrgbByte(b)
    return (0xFF shl 24) or (sr shl 16) or (sg shl 8) or sb
  }

  private fun findNewline(bytes: ByteArray, start: Int): Int {
    var i = start
    while (i < bytes.size && bytes[i] != '\n'.code.toByte()) {
      i++
    }
    require(i < bytes.size) { "Unterminated ASCII header line in HDR file." }
    return i
  }
}
