package com.team35.driveband.band

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JudgeTest {

    private val chart = listOf(
        Note(Pad.BRAKE, 1000),
        Note(Pad.LEFT, 1600),
        Note(Pad.BRAKE, 2200),
    )

    @Test
    fun `가까우면 완벽, 조금 멀면 좋아요, 멀면 무시`() {
        val j = Judge(chart)
        assertEquals(Grade.PERFECT, j.hit(Pad.BRAKE, 1050)!!.second)
        assertEquals(Grade.GOOD, j.hit(Pad.LEFT, 1750)!!.second)
        assertNull(j.hit(Pad.BRAKE, 2500)) // 300ms 벗어남 → 무시 (벌점 없음)
        assertEquals(0, j.count(Grade.MISS))
    }

    @Test
    fun `다른 조작부로 치면 판정 안 됨`() {
        val j = Judge(chart)
        assertNull(j.hit(Pad.RIGHT, 1000))
        assertNull(j.hit(Pad.LEFT, 1000))
    }

    @Test
    fun `지난 노트는 놓침, 콤보 끊김`() {
        val j = Judge(chart)
        j.hit(Pad.BRAKE, 1000)
        assertEquals(1, j.combo)
        assertEquals(listOf(1), j.sweep(1800)) // LEFT 1600 + 180 지남
        assertEquals(0, j.combo)
        assertEquals(1, j.maxCombo)
    }

    @Test
    fun `지연 보정 - 신호가 120ms 늦게 와도 완벽`() {
        val j = Judge(chart, offsetMs = 120)
        assertEquals(Grade.PERFECT, j.hit(Pad.BRAKE, 1120)!!.second)
        // 보정이 없으면 같은 입력이 좋아요
        assertEquals(Grade.GOOD, Judge(chart).hit(Pad.BRAKE, 1120)!!.second)
    }

    @Test
    fun `한 노트는 한 번만`() {
        val j = Judge(chart)
        j.hit(Pad.BRAKE, 1000)
        assertNull(j.hit(Pad.BRAKE, 1010))
    }

    @Test
    fun `별 - 전부 완벽이면 3개`() {
        val j = Judge(chart)
        chart.forEach { j.hit(it.pad, it.timeMs) }
        assertTrue(j.done)
        assertEquals(3, j.stars())
        assertEquals(3, j.maxCombo)
    }

    @Test
    fun `박자 맞추기 - 클릭보다 늦은 시간의 중앙값`() {
        val c = Calibration(beatMs = 750.0, firstClickMs = 3000, clicks = 8)
        val lates = listOf(110L, 130L, 120L, 500L, 125L) // 한 번은 엉뚱하게 늦음 → 중앙값이라 영향 적음
        lates.forEachIndexed { k, d -> c.hit(3000 + k * 750L + d) }
        assertEquals(5, c.count) // 500ms 는 반 박(375ms) 넘어서 다음 클릭 기준 -250 으로 들어감
        assertEquals(120L, c.result())
        c.hit(100) // 준비 박자 구간은 무시
        assertEquals(5, c.count)
    }

    @Test
    fun `박자 맞추기 - 4번 미만이면 결과 없음`() {
        val c = Calibration(750.0, 0, 8)
        repeat(3) { c.hit(it * 750L + 100) }
        assertNull(c.result())
    }

    @Test
    fun `연습곡 채보 - 시간순, 노래 안에, 같은 깜빡이 연타는 0점6초 이상 간격`() {
        val s = Songs.firstIgnition
        val times = s.chart.map { it.timeMs }
        assertEquals(times.sorted(), times)
        assertTrue(times.first() >= s.at(1, 1.0))
        assertTrue(times.last() < s.lengthMs)
        // 방향지시등은 레버라서 같은 쪽을 연달아 치려면 껐다 켜야 함 → 여유 필요
        for (pad in listOf(Pad.LEFT, Pad.RIGHT)) {
            val ts = s.chart.filter { it.pad == pad }.map { it.timeMs }
            ts.zipWithNext().forEach { (a, b) -> assertTrue("$pad ${b - a}ms", b - a >= 600) }
        }
        assertEquals(2400L, s.at(1, 1.0)) // 100BPM 카운트인 1마디 = 2.4초
    }
}
