package com.team35.driveband.band

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * 소리를 코드로 직접 합성한다 (음원 파일·저작권 없음). 모노 16bit, 44.1kHz.
 * 모든 함수는 -1..1 Float 배열을 돌려주고, 마지막에 toPcm16 으로 바꾼다.
 */
object Synth {
    const val RATE = 44_100

    private fun buf(seconds: Double) = FloatArray((seconds * RATE).toInt())

    fun kick(): FloatArray {
        val out = buf(0.45)
        var phase = 0.0
        for (i in out.indices) {
            val t = i.toDouble() / RATE
            val f = 45 + 110 * exp(-t * 30)          // 155Hz → 45Hz 로 떨어지는 음
            phase += 2 * PI * f / RATE
            out[i] = (sin(phase) * exp(-t * 7)).toFloat()
        }
        return out
    }

    fun snare(): FloatArray {
        val rnd = Random(1)
        val out = buf(0.3)
        for (i in out.indices) {
            val t = i.toDouble() / RATE
            val noise = rnd.nextDouble(-1.0, 1.0) * exp(-t * 18)
            val tone = sin(2 * PI * 185 * t) * exp(-t * 30)
            out[i] = (0.7 * noise + 0.5 * tone).toFloat()
        }
        return out
    }

    fun hihat(): FloatArray {
        val rnd = Random(2)
        val out = buf(0.09)
        var prev = 0.0
        for (i in out.indices) {
            val t = i.toDouble() / RATE
            val n = rnd.nextDouble(-1.0, 1.0)
            out[i] = ((n - prev) * 0.5 * exp(-t * 55)).toFloat() // 차분 = 고역만 남김
            prev = n
        }
        return out
    }

    fun crash(): FloatArray {
        val rnd = Random(3)
        val out = buf(1.4)
        var prev = 0.0
        for (i in out.indices) {
            val t = i.toDouble() / RATE
            val n = rnd.nextDouble(-1.0, 1.0)
            out[i] = ((n - prev) * 0.45 * exp(-t * 2.6)).toFloat()
            prev = n
        }
        return out
    }

    /** 메트로놈 클릭 (첫 박은 높게) */
    fun click(accent: Boolean): FloatArray {
        val out = buf(0.05)
        val f = if (accent) 1760.0 else 1320.0
        for (i in out.indices) {
            val t = i.toDouble() / RATE
            out[i] = (sin(2 * PI * f * t) * exp(-t * 80)).toFloat()
        }
        return out
    }

    /** 박자 맞추기용 클릭 트랙: 준비 4박 + 측정 clicks 박 */
    fun clickTrack(beatMs: Double, clicks: Int): FloatArray {
        val total = 4 + clicks
        val out = buf((total * beatMs + 800) / 1000)
        repeat(total) { k ->
            mixInto(out, click(accent = k % 4 == 0), (k * beatMs / 1000 * RATE).toInt(), if (k < 4) 0.5f else 1f)
        }
        return out
    }

    /** 카르플루스-스트롱 현 뜯는 소리 */
    fun pluck(freq: Double, seconds: Double, seed: Int = 0, brightness: Double = 0.996): FloatArray {
        val out = buf(seconds)
        val n = (RATE / freq).toInt().coerceAtLeast(2)
        val rnd = Random(seed)
        val ring = DoubleArray(n) { rnd.nextDouble(-1.0, 1.0) }
        var idx = 0
        for (i in out.indices) {
            val next = (idx + 1) % n
            val v = ring[idx]
            ring[idx] = brightness * 0.5 * (ring[idx] + ring[next])
            out[i] = v.toFloat()
            idx = next
        }
        return out
    }

    /** 기타 스트럼: 코드 구성음을 12ms 간격으로 차례로 뜯는다 */
    fun strum(chord: Chord, seconds: Double = 1.6, muted: Boolean = false): FloatArray {
        val out = buf(seconds)
        chord.guitarFreqs().forEachIndexed { k, f ->
            val s = pluck(f, seconds, seed = k + chord.ordinal * 10, brightness = if (muted) 0.95 else 0.996)
            mixInto(out, s, (k * 0.012 * RATE).toInt(), 0.32f)
        }
        if (muted) for (i in out.indices) out[i] *= exp(-i.toDouble() / RATE * 25).toFloat()
        return out
    }

    fun bass(freq: Double, seconds: Double): FloatArray {
        val out = buf(seconds)
        for (i in out.indices) {
            val t = i.toDouble() / RATE
            val env = (1 - exp(-t * 200)) * exp(-t * 2.2)
            out[i] = ((sin(2 * PI * freq * t) + 0.3 * sin(4 * PI * freq * t)) * env * 0.6).toFloat()
        }
        return out
    }

    fun mixInto(dst: FloatArray, src: FloatArray, at: Int, gain: Float = 1f) {
        for (i in src.indices) {
            val j = at + i
            if (j in dst.indices) dst[j] += src[i] * gain
        }
    }

    fun toPcm16(x: FloatArray, gain: Float = 0.8f): ShortArray {
        val peak = (x.maxOfOrNull { kotlin.math.abs(it) } ?: 1f).coerceAtLeast(1f)
        return ShortArray(x.size) { (x[it] / peak * gain * Short.MAX_VALUE).toInt().toShort() }
    }

    fun midiToFreq(m: Int) = 440.0 * 2.0.pow((m - 69) / 12.0)
}

/** 노래에 쓰는 코드 (C 장조 I–V–vi–IV) */
enum class Chord(val label: String, val root: Int, val third: Int, val fifth: Int) {
    C("C", 48, 52, 55),
    G("G", 43, 47, 50),
    Am("Am", 45, 48, 52),
    F("F", 41, 45, 48);

    /** 기타처럼 넓게 편 6음 */
    fun guitarFreqs() = listOf(root, fifth, root + 12, third + 12, fifth + 12, root + 24).map(Synth::midiToFreq)
    fun bassFreq() = Synth.midiToFreq(root - 12)
}
