package com.team35.driveband

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

enum class Turn { OFF, LEFT, RIGHT }

/**
 * 작곡기가 한 칸(16분음표)마다 읽는 차 상태. 녹음·재생이 똑같이 나오도록 미리 잘게 나눈 값만 담는다.
 * speed = 5km/h 단위, steer = 멜로디 높낮이 단계(-7..7, 핸들 30°마다 한 칸)
 */
data class Inputs(
    val speed: Int = 0, val steer: Int = 0, val turn: Turn = Turn.OFF,
    val wiper: Boolean = false, val brake: Boolean = false, val riders: Int = 1,
) {
    companion object {
        fun of(kph: Double, steeringDeg: Double, turn: Turn, wiper: Boolean, brake: Boolean, riders: Int) = Inputs(
            speed = (kph.coerceIn(0.0, 150.0) / 5).roundToInt() * 5,
            steer = (steeringDeg / 30).roundToInt().coerceIn(-7, 7),
            turn = turn, wiper = wiper, brake = brake, riders = riders.coerceIn(1, 4),
        )
    }
}

enum class Instrument { PIANO, PAD, BASS, PLUCK, BELL, TICK, KICK, SNARE, HAT }

/** 음 하나. pitch = MIDI 번호, length = 16분음표 칸 수 */
data class Note(val inst: Instrument, val pitch: Int, val velocity: Float, val length: Int)

/**
 * 차 상태 → 음악. 정해 둔 화음 진행과 5음 음계 안에서만 고르기 때문에 아무렇게나 운전해도 듣기 좋다.
 *
 * | 차 | 음악 |
 * | 속도 | 템포 72~128 BPM, 빠를수록 멜로디가 촘촘 |
 * | 핸들 | 멜로디 높낮이 (왼쪽 ↓ 오른쪽 ↑) |
 * | 방향지시등 | 박마다 똑딱 + 왼쪽은 내려가는, 오른쪽은 올라가는 꾸밈음 |
 * | 와이퍼 | 드럼 비트 + 단조 진행 (빗길) |
 * | 브레이크 | 멜로디 쉼표 (화음만 남음) |
 * | 탑승 인원 | 악기 수: 1 피아노+패드 → 2 +베이스 → 3 +아르페지오 → 4 +벨 |
 *
 * 같은 seed 와 같은 Inputs 순서면 항상 같은 곡이 나온다 (녹음 = 입력 순서만 저장).
 */
class Composer(seed: Long) {
    private val rnd = Random(seed)
    var step = 0L; private set
    private var degree = 0          // 지금 멜로디 위치 (음계 칸)

    fun next(i: Inputs): List<Note> {
        val s = (step % 16).toInt()
        val chord = progression(i.wiper)[((step / 16) % 4).toInt()]
        val out = mutableListOf<Note>()

        // 화음 받침: 마디 첫 칸에 패드
        if (s == 0) chord.forEach { out += Note(Instrument.PAD, it + 12, 0.35f, 16) }

        // 멜로디 (피아노): 핸들 쪽으로 한 칸씩 걸어가되 가끔 한 칸 비틀어 노래처럼
        val target = i.steer
        val density = 0.25 + 0.6 * (i.speed / 120.0).coerceAtMost(1.0)   // 8분음표 칸에서 칠 확률
        val strong = s % 4 == 0
        val play = !i.brake && (strong || (s % 2 == 0 && rnd.nextDouble() < density))
        val wobble = rnd.nextInt(-1, 2)
        if (play) {
            degree += (target - degree).coerceIn(-2, 2)
            if (!strong) degree += wobble
            var pitch = scalePitch(degree)
            if (strong) pitch = nearestChordTone(pitch, chord)
            out += Note(Instrument.PIANO, pitch, if (strong) 0.8f else 0.6f, if (strong) 4 else 2)
        }

        // 방향지시등: 박마다 똑딱 + 반 박 뒤 꾸밈음
        if (i.turn != Turn.OFF) {
            if (strong) out += Note(Instrument.TICK, 0, 0.5f, 1)
            if (s % 4 == 2 && !i.brake) {
                val d = if (i.turn == Turn.RIGHT) 2 else -2
                out += Note(Instrument.BELL, scalePitch(degree + d) + 12, 0.3f, 2)
            }
        }

        // 와이퍼: 드럼
        if (i.wiper) {
            if (s == 0 || s == 8 || (i.speed >= 60 && s == 10)) out += Note(Instrument.KICK, 0, 0.9f, 4)
            if (s == 4 || s == 12) out += Note(Instrument.SNARE, 0, 0.7f, 4)
            if (s % 2 == 0) out += Note(Instrument.HAT, 0, if (s % 4 == 2) 0.5f else 0.3f, 1)
        }

        // 동승자 = 합주
        if (i.riders >= 2 && (s == 0 || s == 8)) out += Note(Instrument.BASS, chord[0] - 12, 0.7f, 6)
        if (i.riders >= 2 && s == 6) out += Note(Instrument.BASS, chord[2] - 12, 0.5f, 2)
        if (i.riders >= 3 && s % 4 == 2) out += Note(Instrument.PLUCK, chord[(s / 4) % 3] + 12, 0.45f, 2)
        if (i.riders >= 4 && s == 0) out += Note(Instrument.BELL, chord[1] + 24, 0.35f, 8)

        step++
        return out
    }

    companion object {
        /** C 장조 I–V–vi–IV, 비 오면 vi–IV–I–V (같은 화음, 단조 느낌) */
        private val SUNNY = listOf(listOf(48, 52, 55), listOf(43, 47, 50), listOf(45, 48, 52), listOf(41, 45, 48))
        private val RAINY = listOf(SUNNY[2], SUNNY[3], SUNNY[0], SUNNY[1])
        fun progression(rain: Boolean) = if (rain) RAINY else SUNNY

        /** C 장조 5음 음계 (C D E G A) — 어떤 화음 위에서도 크게 부딪히지 않는다 */
        val PENTA = intArrayOf(0, 2, 4, 7, 9)
        private const val CENTER = 72  // C5

        fun scalePitch(degree: Int): Int {
            val d = degree.coerceIn(-10, 12)
            val oct = Math.floorDiv(d, 5); val k = Math.floorMod(d, 5)
            return CENTER + 12 * oct + PENTA[k]
        }

        /** 가장 가까운 화음 구성음 — 단, 5음 음계 안의 음만 (F 화음의 F, G 화음의 B 는 빼서 멜로디가 음계를 안 벗어나게) */
        fun nearestChordTone(pitch: Int, chord: List<Int>): Int =
            (pitch - 12..pitch + 12).filter { p ->
                Math.floorMod(p, 12) in PENTA && chord.any { Math.floorMod(p - it, 12) == 0 }
            }.minBy { abs(it - pitch) }

        /** 속도 → 템포 (0km/h 72 BPM, 120km/h 이상 128 BPM) */
        fun bpm(i: Inputs) = 72 + (56 * (i.speed.coerceAtMost(120) / 120.0)).roundToInt()

        fun stepSeconds(i: Inputs) = 60.0 / bpm(i) / 4
    }
}

/**
 * 곡 = seed + 칸마다 읽은 Inputs (같은 값이 이어지면 묶어서 [횟수, 값]).
 * 다시 들을 땐 같은 seed 의 Composer 에 그대로 넣으면 같은 곡이 나온다.
 */
class Song(val seed: Long, val startedAtMs: Long) {
    val runs = mutableListOf<Pair<Int, Inputs>>()
    var steps = 0L; private set
    var seconds = 0.0; private set

    fun add(i: Inputs) {
        val last = runs.lastOrNull()
        if (last != null && last.second == i) runs[runs.size - 1] = last.first + 1 to i else runs += 1 to i
        steps++
        seconds += Composer.stepSeconds(i)
    }

    fun inputs(): Sequence<Inputs> = runs.asSequence().flatMap { (n, i) -> generateSequence { i }.take(n) }

    val rainRatio get() = if (steps == 0L) 0.0 else runs.filter { it.second.wiper }.sumOf { it.first } / steps.toDouble()
    val maxRiders get() = runs.maxOfOrNull { it.second.riders } ?: 1

    /** "10/29 퇴근길 · 비 · 3인 합주 · 23분" */
    fun title(hour: Int, month: Int, day: Int): String {
        val part = when (hour) {
            in 5..9 -> "출근길"; in 10..15 -> "낮 드라이브"; in 16..19 -> "퇴근길"; else -> "밤 드라이브"
        }
        val tags = buildList {
            add("$month/$day $part")
            if (rainRatio >= 0.2) add("비")
            if (maxRiders >= 2) add("${maxRiders}인 합주")
            add("${(seconds / 60).roundToInt().coerceAtLeast(1)}분")
        }
        return tags.joinToString(" · ")
    }

    fun toJson(): JSONObject = JSONObject().put("seed", seed).put("start", startedAtMs).put("runs", JSONArray().apply {
        runs.forEach { (n, i) ->
            put(JSONArray().put(n).put(i.speed).put(i.steer).put(i.turn.ordinal).put(i.wiper).put(i.brake).put(i.riders))
        }
    })

    companion object {
        fun fromJson(o: JSONObject) = Song(o.getLong("seed"), o.getLong("start")).apply {
            val a = o.getJSONArray("runs")
            for (k in 0 until a.length()) {
                val r = a.getJSONArray(k)
                val i = Inputs(r.getInt(1), r.getInt(2), Turn.entries[r.getInt(3)], r.getBoolean(4), r.getBoolean(5), r.getInt(6))
                repeat(r.getInt(0)) { add(i) }
            }
        }
    }
}
