package com.team35.driveband

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.tanh

/**
 * 실시간 연주기. 오디오 스레드가 16분음표 칸이 될 때마다 next() 로 차 상태를 받아 Composer 에 넣고,
 * 나온 음을 합성해 섞어서 AudioTrack 으로 흘려보낸다. 메아리(딜레이)를 살짝 얹고, 브레이크 중엔 더 울린다.
 *
 * next() 가 null 을 주면 (다시 듣기가 끝나면) 남은 소리를 흘려보내고 멈춘다.
 */
class Player(
    private val attrs: AudioAttributes,
    private val seed: Long,
    private val next: () -> Inputs?,
    private val onEnd: () -> Unit,
) {
    @Volatile var bpm = 0; private set
    @Volatile var chordName = ""; private set
    @Volatile var layers = 0; private set
    @Volatile private var running = false
    private var thread: Thread? = null

    private class Voice(val data: FloatArray, var pos: Int = 0)

    fun start() {
        running = true
        thread = Thread({ loop() }, "drive-song").apply { priority = Thread.MAX_PRIORITY; start() }
    }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null
    }

    private fun loop() {
        val rate = Voices.RATE
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(min * 4, rate / 5 * 2))      // 약 0.2초 여유 (합성이 잠깐 늦어도 안 끊기게)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track.play()

        val composer = Composer(seed)
        val voices = mutableListOf<Voice>()
        val block = 512
        val mix = FloatArray(block)
        val pcm = ShortArray(block)
        val echo = FloatArray((rate * 0.33).toInt())
        var echoPos = 0
        var wet = 0.22f
        var untilStep = 0             // 다음 칸까지 남은 샘플
        var ended = false
        var tail = rate * 2           // 끝난 뒤 울림 흘려보낼 샘플

        try {
            while (running) {
                mix.fill(0f)
                var i = 0
                while (i < block) {
                    if (untilStep == 0 && !ended) {
                        val inp = next()
                        if (inp == null) ended = true else {
                            val stepSec = Composer.stepSeconds(inp)
                            chordName = chordLabel(Composer.progression(inp.wiper)[((composer.step / 16) % 4).toInt()])
                            composer.next(inp).forEach { voices += Voice(Voices.render(it, stepSec)) }
                            untilStep = (stepSec * rate).toInt()
                            bpm = Composer.bpm(inp)
                            layers = 1 + (if (inp.wiper) 1 else 0) + (inp.riders - 1)
                            wet = if (inp.brake) 0.4f else 0.22f
                        }
                    }
                    val n = if (ended) block - i else minOf(block - i, untilStep)
                    for (v in voices) {
                        val m = minOf(n, v.data.size - v.pos)
                        for (k in 0 until m) mix[i + k] += v.data[v.pos + k]
                        v.pos += m
                    }
                    voices.removeAll { it.pos >= it.data.size }
                    if (!ended) untilStep -= n
                    i += n
                }
                for (k in 0 until block) {
                    val dry = mix[k]
                    val e = echo[echoPos]
                    echo[echoPos] = dry + e * 0.35f
                    echoPos = (echoPos + 1) % echo.size
                    pcm[k] = (tanh((dry + e * wet) * 1.2) * 0.9 * Short.MAX_VALUE).toInt().toShort()
                }
                track.write(pcm, 0, block)
                if (ended) { tail -= block; if (tail <= 0) break }
            }
        } finally {
            runCatching { track.stop(); track.release() }
            if (ended) onEnd()
        }
    }

    companion object {
        fun chordLabel(c: List<Int>) = when (c[0] % 12) { 0 -> "C"; 7 -> "G"; 9 -> "Am"; 5 -> "F"; else -> "?" }
    }
}
