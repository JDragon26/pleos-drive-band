package com.team35.driveband

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * 악기 소리를 코드로 합성한다 (음원 파일·저작권 없음). 모노, -1..1 Float.
 * 음 하나를 통째로 만들어 두고 믹서가 더한다. 타악기는 늘 같은 소리라 한 번만 만든다.
 */
object Voices {
    const val RATE = 32_000

    fun freq(midi: Int) = 440.0 * 2.0.pow((midi - 69) / 12.0)

    private val drums = mutableMapOf<Instrument, FloatArray>()

    fun render(n: Note, stepSec: Double): FloatArray {
        val f = freq(n.pitch)
        val len = n.length * stepSec
        val out = when (n.inst) {
            Instrument.PIANO -> piano(f, len)
            Instrument.PAD -> pad(f, len)
            Instrument.BASS -> bass(f, len)
            Instrument.PLUCK -> pluck(f, 0.9)
            Instrument.BELL -> bell(f)
            else -> drums.getOrPut(n.inst) { drum(n.inst) }
        }
        return if (n.velocity == 1f) out else FloatArray(out.size) { out[it] * n.velocity }
    }

    private fun buf(seconds: Double) = FloatArray((seconds * RATE).toInt().coerceAtLeast(1))

    /** 짧은 어택 + 배음 3개가 각자 다른 속도로 줄어드는 전자 피아노 */
    private fun piano(f: Double, len: Double): FloatArray {
        val out = buf(len + 0.6)
        val rel = len
        for (i in out.indices) {
            val t = i.toDouble() / RATE
            val env = min(1.0, t * 200) * exp(-t * 2.5) * (if (t > rel) exp(-(t - rel) * 12) else 1.0)
            val v = sin(2 * PI * f * t) + 0.35 * sin(4 * PI * f * t) * exp(-t * 4) + 0.12 * sin(6 * PI * f * t) * exp(-t * 7)
            out[i] = (v * env * 0.35).toFloat()
        }
        return out
    }

    /** 부드러운 톱니파 한 주기 (배음 6개). 패드는 마디마다 3음씩 길게 만들어서 표로 빨리 읽는다 */
    private val padTable = DoubleArray(2048) { k ->
        val x = 2 * PI * k / 2048
        (1..6).sumOf { h -> sin(h * x) / (h * h) }
    }

    /** 살짝 어긋난 톱니파 둘 + 느린 어택 = 부드러운 패드 */
    private fun pad(f: Double, len: Double): FloatArray {
        val out = buf(len + 0.8)
        val size = padTable.size
        var p1 = 0.0; var p2 = 0.0
        val d1 = f * size / RATE; val d2 = f * 1.004 * size / RATE
        for (i in out.indices) {
            val t = i.toDouble() / RATE
            val env = min(1.0, t / 0.35) * (if (t > len) exp(-(t - len) * 5) else 1.0)
            val v = padTable[p1.toInt()] + padTable[p2.toInt()]
            p1 = (p1 + d1) % size; p2 = (p2 + d2) % size
            out[i] = (v * env * 0.07).toFloat()
        }
        return out
    }

    private fun bass(f: Double, len: Double): FloatArray {
        val out = buf(len + 0.2)
        for (i in out.indices) {
            val t = i.toDouble() / RATE
            val env = min(1.0, t * 150) * exp(-t * 1.8) * (if (t > len) exp(-(t - len) * 20) else 1.0)
            out[i] = ((sin(2 * PI * f * t) + 0.25 * sin(4 * PI * f * t)) * env * 0.45).toFloat()
        }
        return out
    }

    /** 카르플루스-스트롱 (현 뜯는 소리) */
    private fun pluck(f: Double, seconds: Double): FloatArray {
        val out = buf(seconds)
        val n = (RATE / f).toInt().coerceAtLeast(2)
        val rnd = Random(f.toInt())
        val ring = DoubleArray(n) { rnd.nextDouble(-1.0, 1.0) }
        var idx = 0
        for (i in out.indices) {
            val next = (idx + 1) % n
            val v = ring[idx]
            ring[idx] = 0.995 * 0.5 * (ring[idx] + ring[next])
            out[i] = (v * 0.3).toFloat()
            idx = next
        }
        return out
    }

    /** FM 벨 (반짝이는 소리) */
    private fun bell(f: Double): FloatArray {
        val out = buf(1.6)
        for (i in out.indices) {
            val t = i.toDouble() / RATE
            val mod = 2.2 * exp(-t * 3) * sin(2 * PI * f * 3.5 * t)
            out[i] = (sin(2 * PI * f * t + mod) * min(1.0, t * 300) * exp(-t * 2.8) * 0.25).toFloat()
        }
        return out
    }

    private fun drum(inst: Instrument): FloatArray = when (inst) {
        Instrument.KICK -> buf(0.4).also { out ->
            var ph = 0.0
            for (i in out.indices) {
                val t = i.toDouble() / RATE
                ph += 2 * PI * (45 + 110 * exp(-t * 30)) / RATE
                out[i] = (sin(ph) * exp(-t * 8) * 0.8).toFloat()
            }
        }
        Instrument.SNARE -> buf(0.25).also { out ->
            val r = Random(1)
            for (i in out.indices) {
                val t = i.toDouble() / RATE
                out[i] = ((0.6 * r.nextDouble(-1.0, 1.0) * exp(-t * 20) + 0.4 * sin(2 * PI * 185 * t) * exp(-t * 30)) * 0.6).toFloat()
            }
        }
        Instrument.HAT -> buf(0.08).also { out ->
            val r = Random(2); var prev = 0.0
            for (i in out.indices) {
                val t = i.toDouble() / RATE
                val n = r.nextDouble(-1.0, 1.0)
                out[i] = ((n - prev) * 0.35 * exp(-t * 60)).toFloat(); prev = n
            }
        }
        // 방향지시등 "똑딱" — 나무 블록 같은 짧은 소리
        else -> buf(0.06).also { out ->
            for (i in out.indices) {
                val t = i.toDouble() / RATE
                out[i] = ((sin(2 * PI * 1250 * t) + 0.5 * sin(2 * PI * 2900 * t)) * exp(-t * 90) * 0.4).toFloat()
            }
        }
    }
}
