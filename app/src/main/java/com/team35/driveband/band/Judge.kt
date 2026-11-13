package com.team35.driveband.band

import kotlin.math.abs

/**
 * 박자 맞추기: 메트로놈 클릭에 맞춰 브레이크를 밟으면, 클릭보다 얼마나 늦게 들어오는지(차 신호 지연 + 사람 반응)를 잰다.
 * 결과(중앙값)를 판정 보정값으로 쓴다.
 */
class Calibration(val beatMs: Double, val firstClickMs: Long, val clicks: Int) {
    private val diffs = mutableListOf<Long>()
    val count get() = diffs.size
    val endMs get() = (firstClickMs + clicks * beatMs).toLong() + 400

    fun hit(songMs: Long) {
        val k = Math.round((songMs - firstClickMs) / beatMs).toInt()
        if (k !in 0 until clicks) return
        val d = songMs - (firstClickMs + k * beatMs).toLong()
        if (abs(d) < beatMs / 2) diffs += d
    }

    /** 4번 이상 쳤으면 중앙값(ms) */
    fun result(): Long? = if (diffs.size < 4) null else diffs.sorted()[diffs.size / 2]
}

enum class Grade(val label: String, val points: Int) {
    PERFECT("완벽", 100),
    GOOD("좋아요", 50),
    MISS("놓침", 0),
}

/**
 * 리듬 판정. 시간은 모두 노래 기준 ms.
 * offsetMs = 입력이 늦게 도착하는 만큼(차량 신호 지연) 빼 주는 보정값.
 * 노트 없는 곳을 친 건 벌점 없이 무시한다 (차 조작은 실수하기 쉬우니 너그럽게).
 */
class Judge(
    private val chart: List<Note>,
    var offsetMs: Long = 0,
    private val perfectMs: Long = 90,
    private val goodMs: Long = 180,
) {
    private val result = arrayOfNulls<Grade>(chart.size)
    var score = 0
        private set
    var combo = 0
        private set
    var maxCombo = 0
        private set

    fun gradeOf(index: Int) = result[index]
    fun count(g: Grade) = result.count { it == g }
    val done get() = result.all { it != null }

    /** 조작부를 눌렀다. 판정된 (노트 번호, 등급) 또는 null */
    fun hit(pad: Pad, songMs: Long): Pair<Int, Grade>? {
        val t = songMs - offsetMs
        val i = chart.indices
            .filter { result[it] == null && chart[it].pad == pad && abs(chart[it].timeMs - t) <= goodMs }
            .minByOrNull { abs(chart[it].timeMs - t) } ?: return null
        val g = if (abs(chart[i].timeMs - t) <= perfectMs) Grade.PERFECT else Grade.GOOD
        record(i, g)
        return i to g
    }

    /** 시간이 지나 못 친 노트를 놓침 처리. 새로 놓친 노트 번호들 */
    fun sweep(songMs: Long): List<Int> {
        val t = songMs - offsetMs
        val missed = chart.indices.filter { result[it] == null && chart[it].timeMs < t - goodMs }
        missed.forEach { record(it, Grade.MISS) }
        return missed
    }

    private fun record(i: Int, g: Grade) {
        result[i] = g
        if (g == Grade.MISS) {
            combo = 0
        } else {
            combo++
            maxCombo = maxOf(maxCombo, combo)
            score += g.points + minOf(combo, 50) // 콤보 보너스
        }
    }

    /** 별 0~3 : 놓침 없이 대부분 완벽이면 3 */
    fun stars(): Int {
        val total = chart.size.coerceAtLeast(1)
        val ratio = (count(Grade.PERFECT) + 0.5 * count(Grade.GOOD)) / total
        return when {
            ratio >= 0.9 -> 3
            ratio >= 0.7 -> 2
            ratio >= 0.4 -> 1
            else -> 0
        }
    }
}
