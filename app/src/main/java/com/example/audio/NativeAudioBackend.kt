package com.example.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.PlaybackParams
import android.media.SoundPool
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.sin

enum class AudioFocusStatus {
  GRANTED,
  TRANSIENT_DUCK,
  TRANSIENT_LOSS,
  PERMANENT_LOSS,
  UNAVAILABLE,
}

enum class HapticCueKind {
  LIGHT_TICK,
  PICKUP_PULSE,
  NITRO_SURGE,
  SHIELD_DEFLECT,
  HEAVY_COLLISION,
}

interface HapticsBackend {
  val hasHardwareVibrator: Boolean

  fun performHaptic(cue: HapticCueKind)
}

interface NativeAudioBackend {
  fun initialize(
    loopSpecs: List<PreparedAudioAssetSpec>,
    oneShotSpecs: List<PreparedAudioAssetSpec>,
    onFocusChanged: (AudioFocusStatus) -> Unit,
  )

  fun requestPlaybackFocus(): AudioFocusStatus

  fun abandonPlaybackFocus()

  /**
   * Updates one of the 6 short PCM16 static loop voices (`engine-idle`, `engine-steady`,
   * `engine-high`, `nitro-sustain`, `road-noise`, `wind`). `gain <= 0.001f` pauses the voice.
   */
  fun setShortLoopState(
    loopId: String,
    active: Boolean,
    leftGain: Float,
    rightGain: Float,
    pitchRate: Float,
  )

  /**
   * Updates the single procedural harmonic character layer voice paired with the 3 original engine WAVs.
   */
  fun setEngineSynthCharacterState(
    active: Boolean,
    freqHz: Float,
    subOctaveBlend: Float,
    gain: Float,
  )

  /**
   * Updates the single streaming music player (`music-menu` or `music-race`, or `null` when stopped).
   * Guarantees at most ONE music stream ever plays at a time without loading 40 MB into RAM.
   */
  fun setMusicLoopState(
    musicLoopId: String?,
    playing: Boolean,
    gain: Float,
  )

  /**
   * Plays a bounded one-shot MP3 (`SoundPool`) with independent left/right stereo gain and pitch.
   * Returns a non-zero stream token if played, or `0` if suppressed/not yet loaded.
   */
  fun playOneShot(
    clipId: String,
    leftGain: Float,
    rightGain: Float,
    priority: Int,
    pitchRate: Float,
  ): Int

  fun stopAllActiveSounds()

  fun release()
}

object NoOpHapticsBackend : HapticsBackend {
  override val hasHardwareVibrator: Boolean = false

  override fun performHaptic(cue: HapticCueKind) {}
}

class NoOpNativeAudioBackend : NativeAudioBackend {
  override fun initialize(
    loopSpecs: List<PreparedAudioAssetSpec>,
    oneShotSpecs: List<PreparedAudioAssetSpec>,
    onFocusChanged: (AudioFocusStatus) -> Unit,
  ) {}

  override fun requestPlaybackFocus(): AudioFocusStatus = AudioFocusStatus.GRANTED

  override fun abandonPlaybackFocus() {}

  override fun setShortLoopState(
    loopId: String,
    active: Boolean,
    leftGain: Float,
    rightGain: Float,
    pitchRate: Float,
  ) {}

  override fun setEngineSynthCharacterState(
    active: Boolean,
    freqHz: Float,
    subOctaveBlend: Float,
    gain: Float,
  ) {}

  override fun setMusicLoopState(
    musicLoopId: String?,
    playing: Boolean,
    gain: Float,
  ) {}

  override fun playOneShot(
    clipId: String,
    leftGain: Float,
    rightGain: Float,
    priority: Int,
    pitchRate: Float,
  ): Int = 1

  override fun stopAllActiveSounds() {}

  override fun release() {}
}

class AndroidHapticsBackend(context: Context) : HapticsBackend {
  private val vibrator: Vibrator? =
    runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
          val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
          vm?.defaultVibrator
        } else {
          @Suppress("DEPRECATION")
          context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
      }
      .getOrNull()

  override val hasHardwareVibrator: Boolean =
    runCatching { vibrator?.hasVibrator() == true }.getOrDefault(false)

  override fun performHaptic(cue: HapticCueKind) {
    val vib = vibrator ?: return
    if (!hasHardwareVibrator) return
    runCatching {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val effect =
          when (cue) {
            HapticCueKind.LIGHT_TICK -> VibrationEffect.createOneShot(14L, 70)
            HapticCueKind.PICKUP_PULSE -> VibrationEffect.createOneShot(22L, 110)
            HapticCueKind.NITRO_SURGE -> VibrationEffect.createOneShot(38L, 165)
            HapticCueKind.SHIELD_DEFLECT -> VibrationEffect.createOneShot(45L, 190)
            HapticCueKind.HEAVY_COLLISION -> VibrationEffect.createOneShot(75L, 240)
          }
        vib.vibrate(effect)
      } else {
        @Suppress("DEPRECATION")
        val ms =
          when (cue) {
            HapticCueKind.LIGHT_TICK -> 12L
            HapticCueKind.PICKUP_PULSE -> 20L
            HapticCueKind.NITRO_SURGE -> 35L
            HapticCueKind.SHIELD_DEFLECT -> 45L
            HapticCueKind.HEAVY_COLLISION -> 70L
          }
        @Suppress("DEPRECATION")
        vib.vibrate(ms)
      }
    }
  }
}

/**
 * Production Android native audio backend:
 * - 6 reusable `AudioTrack` (`MODE_STATIC`) voices for short 48 kHz stereo PCM16 loops
 *   (`setLoopPoints(0, pcmFrames, -1)`) — avoids `SoundPool`'s 1 MB truncation and `MediaPlayer` loop gaps.
 * - 1 lightweight `AudioTrack` (`MODE_STREAM`) harmonic character synthesizer voice for the 5 car profiles.
 * - 1 single-owner streaming PCM `AudioTrack` (`MODE_STREAM`) for `music-menu.wav` (17 MB) and
 *   `music-race.wav` (22 MB), wrapping sample-accurately from `pcmFrames` -> `0` using <128 KB RAM.
 * - 1 bounded `SoundPool` (`maxStreams = 8`) for the 15 short MP3 one-shots.
 */
class AndroidNativeAudioBackend(
  private val context: Context,
  private val assetRepository: AudioAssetRepository,
) : NativeAudioBackend {

  private class StaticLoopVoice(
    val track: AudioTrack,
    val pcmFrames: Int,
  ) {
    var isPlaying: Boolean = false
    var lastPitch: Float = 1.0f
  }

  private val systemAudioManager: AudioManager? =
    context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
  private val staticLoops = ConcurrentHashMap<String, StaticLoopVoice>()
  private val soundPoolIds = ConcurrentHashMap<String, Int>()
  private val soundPoolReady = ConcurrentHashMap<Int, Boolean>()
  private var soundPool: SoundPool? = null

  private var focusCallback: ((AudioFocusStatus) -> Unit)? = null
  private var focusRequestOreo: AudioFocusRequest? = null
  private val focusListener =
    AudioManager.OnAudioFocusChangeListener { change ->
      val mapped =
        when (change) {
          AudioManager.AUDIOFOCUS_GAIN -> AudioFocusStatus.GRANTED
          AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> AudioFocusStatus.TRANSIENT_DUCK
          AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> AudioFocusStatus.TRANSIENT_LOSS
          AudioManager.AUDIOFOCUS_LOSS -> AudioFocusStatus.PERMANENT_LOSS
          else -> AudioFocusStatus.TRANSIENT_LOSS
        }
      focusCallback?.invoke(mapped)
    }

  private var musicStreamer: SingleMusicStreamThread? = null
  private var synthThread: HarmonicCharacterStreamThread? = null
  private val released = AtomicBoolean(false)

  private val gameAudioAttributes: AudioAttributes by lazy {
    AudioAttributes.Builder()
      .setUsage(AudioAttributes.USAGE_GAME)
      .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
      .build()
  }

  private val musicAudioAttributes: AudioAttributes by lazy {
    AudioAttributes.Builder()
      .setUsage(AudioAttributes.USAGE_GAME)
      .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
      .build()
  }

  override fun initialize(
    loopSpecs: List<PreparedAudioAssetSpec>,
    oneShotSpecs: List<PreparedAudioAssetSpec>,
    onFocusChanged: (AudioFocusStatus) -> Unit,
  ) {
    released.set(false)
    focusCallback = onFocusChanged

    // 1. Bounded SoundPool (max 8 voices) for the 15 short MP3 one-shots
    runCatching {
      val pool =
        SoundPool.Builder()
          .setMaxStreams(AudioCatalog.MAX_CONCURRENT_ONESHOT_VOICES)
          .setAudioAttributes(gameAudioAttributes)
          .build()
      pool.setOnLoadCompleteListener { _, sampleId, status ->
        if (status == 0) {
          soundPoolReady[sampleId] = true
        }
      }
      for (spec in oneShotSpecs) {
        runCatching {
          context.assets.openFd(spec.assetPath).use { afd ->
            val id = pool.load(afd, spec.priority)
            if (id != 0) {
              soundPoolIds[spec.id] = id
            }
          }
        }
      }
      soundPool = pool
    }

    // 2. Reusable static PCM16 AudioTracks for the 6 short gameplay loops (excludes 17MB/22MB music)
    for (spec in loopSpecs) {
      if (spec.bus == AudioBus.MUSIC) continue
      runCatching {
        val loaded = assetRepository.loadShortWavLoopPcmPayload(spec) ?: return@runCatching
        val (wavInfo, pcmBytes) = loaded
        val format =
          AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(wavInfo.sampleRateHz)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .build()
        val track =
          AudioTrack.Builder()
            .setAudioAttributes(gameAudioAttributes)
            .setAudioFormat(format)
            .setBufferSizeInBytes(pcmBytes.size)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        val written = track.write(pcmBytes, 0, pcmBytes.size)
        if (written > 0) {
          track.setLoopPoints(0, wavInfo.pcmFrames, -1)
          staticLoops[spec.id] = StaticLoopVoice(track = track, pcmFrames = wavInfo.pcmFrames)
        } else {
          track.release()
        }
      }
    }

    // 3. Lightweight procedural harmonic character thread (dormant until Running)
    synthThread =
      HarmonicCharacterStreamThread(gameAudioAttributes).also { thread ->
        thread.start()
      }
  }

  override fun requestPlaybackFocus(): AudioFocusStatus {
    val am = systemAudioManager ?: return AudioFocusStatus.UNAVAILABLE
    return runCatching {
        val res =
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req =
              focusRequestOreo
                ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                  .setAudioAttributes(musicAudioAttributes)
                  .setAcceptsDelayedFocusGain(false)
                  .setWillPauseWhenDucked(false)
                  .setOnAudioFocusChangeListener(focusListener)
                  .build()
                  .also { focusRequestOreo = it }
            am.requestAudioFocus(req)
          } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(
              focusListener,
              AudioManager.STREAM_MUSIC,
              AudioManager.AUDIOFOCUS_GAIN,
            )
          }
        if (res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
          AudioFocusStatus.GRANTED
        } else {
          AudioFocusStatus.PERMANENT_LOSS
        }
      }
      .getOrDefault(AudioFocusStatus.UNAVAILABLE)
  }

  override fun abandonPlaybackFocus() {
    val am = systemAudioManager ?: return
    runCatching {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        focusRequestOreo?.let { am.abandonAudioFocusRequest(it) }
      } else {
        @Suppress("DEPRECATION")
        am.abandonAudioFocus(focusListener)
      }
    }
  }

  override fun setShortLoopState(
    loopId: String,
    active: Boolean,
    leftGain: Float,
    rightGain: Float,
    pitchRate: Float,
  ) {
    if (released.get()) return
    val voice = staticLoops[loopId] ?: return
    val l = leftGain.coerceIn(0f, 1f)
    val r = rightGain.coerceIn(0f, 1f)
    val audible = active && (l > 0.0015f || r > 0.0015f)
    runCatching {
      if (!audible) {
        if (voice.isPlaying) {
          @Suppress("DEPRECATION")
          voice.track.setStereoVolume(0f, 0f)
          voice.track.pause()
          voice.isPlaying = false
        }
        return
      }
      @Suppress("DEPRECATION")
      voice.track.setStereoVolume(l, r)
      val clampedPitch = pitchRate.coerceIn(0.55f, 1.95f)
      if (kotlin.math.abs(clampedPitch - voice.lastPitch) >= 0.012f) {
        voice.track.playbackParams = PlaybackParams().setPitch(clampedPitch)
        voice.lastPitch = clampedPitch
      }
      if (!voice.isPlaying) {
        voice.track.setLoopPoints(0, voice.pcmFrames, -1)
        voice.track.play()
        voice.isPlaying = true
      }
    }
  }

  override fun setEngineSynthCharacterState(
    active: Boolean,
    freqHz: Float,
    subOctaveBlend: Float,
    gain: Float,
  ) {
    if (released.get()) return
    synthThread?.updateParameters(
      active = active && gain > 0.001f,
      freqHz = freqHz,
      subOctaveBlend = subOctaveBlend,
      gain = gain.coerceIn(0f, 0.25f),
    )
  }

  @Synchronized
  override fun setMusicLoopState(
    musicLoopId: String?,
    playing: Boolean,
    gain: Float,
  ) {
    if (released.get()) return
    val current = musicStreamer
    if (musicLoopId == null) {
      current?.shutdownAndRelease()
      musicStreamer = null
      return
    }
    val spec = AudioCatalog.findAsset(musicLoopId) ?: return
    if (current != null && current.assetPath == spec.assetPath && current.isAlive) {
      current.setPlaybackState(playing = playing, gain = gain.coerceIn(0f, 1f))
      return
    }
    // Tear down previous music stream before starting the new track -> strict single music loop
    current?.shutdownAndRelease()
    musicStreamer =
      SingleMusicStreamThread(
          context = context,
          assetPath = spec.assetPath,
          attributes = musicAudioAttributes,
          initialPlaying = playing,
          initialGain = gain.coerceIn(0f, 1f),
        )
        .also { it.start() }
  }

  override fun playOneShot(
    clipId: String,
    leftGain: Float,
    rightGain: Float,
    priority: Int,
    pitchRate: Float,
  ): Int {
    if (released.get()) return 0
    val pool = soundPool ?: return 0
    val sampleId = soundPoolIds[clipId] ?: return 0
    if (soundPoolReady[sampleId] != true) return 0
    val l = leftGain.coerceIn(0f, 1f)
    val r = rightGain.coerceIn(0f, 1f)
    if (l <= 0.001f && r <= 0.001f) return 0
    return runCatching {
        pool.play(sampleId, l, r, priority, 0, pitchRate.coerceIn(0.55f, 1.95f))
      }
      .getOrDefault(0)
  }

  @Synchronized
  override fun stopAllActiveSounds() {
    for (voice in staticLoops.values) {
      runCatching {
        if (voice.isPlaying) {
          @Suppress("DEPRECATION")
          voice.track.setStereoVolume(0f, 0f)
          voice.track.pause()
          voice.track.flush()
          voice.isPlaying = false
        }
      }
    }
    synthThread?.updateParameters(active = false, freqHz = 90f, subOctaveBlend = 0f, gain = 0f)
    musicStreamer?.setPlaybackState(playing = false, gain = 0f)
    runCatching { soundPool?.autoPause() }
  }

  @Synchronized
  override fun release() {
    if (!released.compareAndSet(false, true)) return
    abandonPlaybackFocus()
    musicStreamer?.shutdownAndRelease()
    musicStreamer = null
    synthThread?.shutdownAndRelease()
    synthThread = null
    for (voice in staticLoops.values) {
      runCatching {
        voice.track.stop()
        voice.track.release()
      }
    }
    staticLoops.clear()
    runCatching { soundPool?.release() }
    soundPool = null
    soundPoolIds.clear()
    soundPoolReady.clear()
  }

  /**
   * Streams 48 kHz 16-bit stereo WAV music in 16 KB chunks and wraps seamlessly from `dataSizeBytes`
   * back to byte `0` of the PCM `data` chunk without allocating 17–22 MB on the heap.
   */
  private class SingleMusicStreamThread(
    private val context: Context,
    val assetPath: String,
    private val attributes: AudioAttributes,
    initialPlaying: Boolean,
    initialGain: Float,
  ) : Thread("DripRaceMusicStream") {
    @Volatile private var running = true
    @Volatile private var playing = initialPlaying
    @Volatile private var gain = initialGain

    init {
      isDaemon = true
    }

    fun setPlaybackState(playing: Boolean, gain: Float) {
      this.playing = playing
      this.gain = gain
    }

    fun shutdownAndRelease() {
      running = false
      interrupt()
    }

    override fun run() {
      var track: AudioTrack? = null
      try {
        val header =
          context.assets.open(assetPath).use {
            AudioBinaryInspectors.parseWavHeaderAndScanHeadroom(it, scanSamplesForHeadroom = false)
          } ?: return
        val minBuf =
          AudioTrack.getMinBufferSize(
            header.sampleRateHz,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
          )
        val bufferBytes = maxOf(minBuf, 16_384)
        val format =
          AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(header.sampleRateHz)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .build()
        track =
          AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferBytes * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        val chunk = ByteArray(16_384)
        var stream = openAtDataOffset(header.dataOffsetBytes) ?: return
        var remainingInLoop = header.dataSizeBytes
        var trackStarted = false
        var appliedGain = -1f

        while (running) {
          if (!playing || gain <= 0.001f) {
            if (trackStarted) {
              runCatching { track.pause() }
              trackStarted = false
            }
            sleepQuietly(35L)
            continue
          }

          val g = gain
          if (kotlin.math.abs(g - appliedGain) >= 0.008f) {
            track.setVolume(g)
            appliedGain = g
          }
          if (!trackStarted) {
            track.play()
            trackStarted = true
          }

          val toRead = minOf(chunk.size, remainingInLoop)
          val n = stream.read(chunk, 0, toRead)
          if (n <= 0 || remainingInLoop - maxOf(0, n) <= 0) {
            if (n > 0) {
              // Align to 4-byte stereo 16-bit frame boundary
              val aligned = n - (n % 4)
              if (aligned > 0) track.write(chunk, 0, aligned)
            }
            runCatching { stream.close() }
            stream = openAtDataOffset(header.dataOffsetBytes) ?: break
            remainingInLoop = header.dataSizeBytes
          } else {
            remainingInLoop -= n
            val aligned = n - (n % 4)
            if (aligned > 0) {
              track.write(chunk, 0, aligned)
            }
          }
        }
        runCatching { stream.close() }
      } catch (_: Throwable) {
        // Safely exit daemon thread on interruption/release
      } finally {
        runCatching {
          track?.stop()
          track?.release()
        }
      }
    }

    private fun openAtDataOffset(dataOffsetBytes: Int) =
      runCatching {
          val s = context.assets.open(assetPath)
          var rem = dataOffsetBytes.toLong()
          while (rem > 0L) {
            val skipped = s.skip(rem)
            if (skipped <= 0L) {
              if (s.read() < 0) break
              rem -= 1L
            } else {
              rem -= skipped
            }
          }
          s
        }
        .getOrNull()

    private fun sleepQuietly(ms: Long) {
      try {
        sleep(ms)
      } catch (_: InterruptedException) {
        // Woken up on state change or shutdown
      }
    }
  }

  /**
   * Generates the subtle, honestly labeled procedural harmonic + sub-octave character layer
   * differentiating the 5 hero car engine profiles on top of the 3 user-created engine WAV stems.
   */
  private class HarmonicCharacterStreamThread(
    private val attributes: AudioAttributes,
  ) : Thread("DripRaceEngineHarmonicSynth") {
    @Volatile private var running = true
    @Volatile private var active = false
    @Volatile private var targetFreqHz = 95f
    @Volatile private var subOctaveBlend = 0.2f
    @Volatile private var targetGain = 0f

    init {
      isDaemon = true
    }

    fun updateParameters(
      active: Boolean,
      freqHz: Float,
      subOctaveBlend: Float,
      gain: Float,
    ) {
      this.active = active
      this.targetFreqHz = freqHz.coerceIn(28f, 540f)
      this.subOctaveBlend = subOctaveBlend.coerceIn(0f, 0.85f)
      this.targetGain = gain.coerceIn(0f, 0.25f)
    }

    fun shutdownAndRelease() {
      running = false
      interrupt()
    }

    override fun run() {
      var track: AudioTrack? = null
      try {
        val sampleRate = 24_000
        val minBuf =
          AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
          )
        val format =
          AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        track =
          AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(minBuf, 4096))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        val framesPerWrite = 480 // 20 ms
        val pcm = ShortArray(framesPerWrite)
        var phaseMain = 0.0
        var phaseSub = 0.0
        var smoothedFreq = 95f
        var smoothedGain = 0f
        var started = false

        while (running) {
          if (!active && smoothedGain <= 0.0008f) {
            if (started) {
              runCatching { track.pause() }
              started = false
            }
            try {
              sleep(30L)
            } catch (_: InterruptedException) {}
            continue
          }
          if (!started) {
            track.play()
            started = true
          }

          val goalGain = if (active) targetGain else 0f
          val goalFreq = targetFreqHz
          val subMix = subOctaveBlend

          for (i in 0 until framesPerWrite) {
            smoothedFreq += (goalFreq - smoothedFreq) * 0.008f
            smoothedGain += (goalGain - smoothedGain) * 0.006f
            phaseMain = (phaseMain + 2.0 * PI * smoothedFreq / sampleRate) % (2.0 * PI)
            phaseSub = (phaseSub + PI * smoothedFreq / sampleRate) % (2.0 * PI)

            val mainWave = 0.72 * sin(phaseMain) + 0.28 * sin(phaseMain * 2.0)
            val subWave = sin(phaseSub)
            val blended = ((1f - subMix) * mainWave + subMix * subWave) * smoothedGain
            val clamped = (blended * 16_384.0).toInt().coerceIn(-24_000, 24_000)
            pcm[i] = clamped.toShort()
          }
          track.write(pcm, 0, framesPerWrite)
        }
      } catch (_: Throwable) {
        // Clean exit
      } finally {
        runCatching {
          track?.stop()
          track?.release()
        }
      }
    }
  }
}
