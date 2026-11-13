package com.team35.driveband.band

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.SoundPool
import android.os.SystemClock
import java.io.File
import java.io.RandomAccessFile

/** 연주 소리 이름 */
enum class Sound { KICK, SNARE, HIHAT, CRASH, STRUM_C, STRUM_G, STRUM_AM, STRUM_F, MUTE_C, MUTE_G, MUTE_AM, MUTE_F }

fun strumOf(c: Chord) = Sound.valueOf("STRUM_${c.name.uppercase()}")
fun muteOf(c: Chord) = Sound.valueOf("MUTE_${c.name.uppercase()}")

/**
 * 타격음: 합성한 소리를 WAV 로 캐시에 써 두고 SoundPool 로 즉시 재생.
 * 반주: 미리 합성한 PCM 을 AudioTrack 스트림으로 재생 (노래 시각은 BandViewModel 이 시스템 시계로 잰다).
 */
class Audio(context: Context) {
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()
    private val pool = SoundPool.Builder().setMaxStreams(12).setAudioAttributes(attrs).build()
    private val ids = mutableMapOf<Sound, Int>()

    init {
        val dir = File(context.cacheDir, "sounds").apply { mkdirs() }
        for (s in Sound.entries) {
            val f = File(dir, "${s.name}.wav")
            if (!f.exists()) writeWav(f, Synth.toPcm16(render(s)))
            ids[s] = pool.load(f.path, 1)
        }
    }

    private fun render(s: Sound): FloatArray = when (s) {
        Sound.KICK -> Synth.kick()
        Sound.SNARE -> Synth.snare()
        Sound.HIHAT -> Synth.hihat()
        Sound.CRASH -> Synth.crash()
        else -> {
            val chord = Chord.entries.first { s.name.endsWith("_" + it.name.uppercase()) }
            if (s.name.startsWith("MUTE")) Synth.strum(chord, 0.3, muted = true) else Synth.strum(chord, 1.8)
        }
    }

    fun play(s: Sound, volume: Float = 1f) {
        ids[s]?.let { pool.play(it, volume, volume, 1, 0, 1f) }
    }

    // ── 반주 ──
    @Volatile private var track: AudioTrack? = null
    private var feeder: Thread? = null

    private var prepared: AudioTrack? = null
    private var preparedPcm: ShortArray? = null
    private var prefilled = 0

    /**
     * 반주를 미리 준비: 트랙 생성 + 버퍼 채우기까지 해 둔다.
     * 트랙 생성 시간이 매번 달라(수십~백수십 ms) 시작할 때 하면 노래 시작 시각이 흔들린다.
     */
    fun prepareBacking(pcm: ShortArray) {
        stopBacking()
        val t = newTrack()
        val min = t.bufferSizeInFrames
        prefilled = t.write(pcm, 0, minOf(min, pcm.size)).coerceAtLeast(0)
        prepared = t
        preparedPcm = pcm
    }

    /** 준비한 반주 재생. 재생을 시작한 순간의 elapsedRealtime(ms), 준비 안 됐으면 null */
    fun playBacking(): Long? {
        val t = prepared ?: return null
        val pcm = preparedPcm ?: return null
        prepared = null
        preparedPcm = null
        track = t
        t.play()
        val at = SystemClock.elapsedRealtime()
        feeder = Thread {
            var pos = prefilled
            while (pos < pcm.size && track === t) {
                val n = minOf(4096, pcm.size - pos)
                val w = t.write(pcm, pos, n)
                if (w <= 0) break
                pos += w
            }
        }.apply { start() }
        return at
    }

    private fun newTrack(): AudioTrack {
        val min = AudioTrack.getMinBufferSize(Synth.RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        return AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(
                AudioFormat.Builder().setSampleRate(Synth.RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()
            )
            .setBufferSizeInBytes(min * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
    }

    fun stopBacking() {
        val t = track
        track = null
        runCatching { t?.stop(); t?.release() }
        feeder?.join(200)
        feeder = null
        runCatching { prepared?.release() }
        prepared = null
        preparedPcm = null
    }

    fun release() {
        stopBacking()
        pool.release()
    }

    private fun writeWav(f: File, pcm: ShortArray) {
        RandomAccessFile(f, "rw").use { out ->
            val dataLen = pcm.size * 2
            fun i32(v: Int) = out.write(byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte()))
            fun i16(v: Int) = out.write(byteArrayOf(v.toByte(), (v shr 8).toByte()))
            out.writeBytes("RIFF"); i32(36 + dataLen); out.writeBytes("WAVE")
            out.writeBytes("fmt "); i32(16); i16(1); i16(1); i32(Synth.RATE); i32(Synth.RATE * 2); i16(2); i16(16)
            out.writeBytes("data"); i32(dataLen)
            val bytes = ByteArray(dataLen)
            pcm.forEachIndexed { i, s -> bytes[2 * i] = s.toByte(); bytes[2 * i + 1] = (s.toInt() shr 8).toByte() }
            out.write(bytes)
        }
    }
}
