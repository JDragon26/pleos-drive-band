package com.team35.driveband

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerTest {
    private fun play(c: Composer, i: Inputs, steps: Int) = (1..steps).flatMap { c.next(i) }
    private fun melody(notes: List<Note>) = notes.filter { it.inst == Instrument.PIANO }

    @Test
    fun `속도가 빠를수록 템포가 빠르다`() {
        assertEquals(72, Composer.bpm(Inputs(speed = 0)))
        assertEquals(100, Composer.bpm(Inputs(speed = 60)))
        assertEquals(128, Composer.bpm(Inputs(speed = 150)))
        assertEquals(60, Inputs.of(61.0, 0.0, Turn.OFF, false, false, 1).speed)
    }

    @Test
    fun `멜로디는 음계 안에서만, 박 첫 칸은 화음 구성음`() {
        val c = Composer(7)
        repeat(64) { k ->
            val chord = Composer.progression(false)[(k / 16) % 4]
            val i = Inputs(speed = 80, steer = (k % 15) - 7)
            melody(c.next(i)).forEach { n ->
                assertTrue("음계 밖 ${n.pitch}", Math.floorMod(n.pitch, 12) in Composer.PENTA.toList())
                if (k % 4 == 0) assertTrue(chord.any { Math.floorMod(n.pitch - it, 12) == 0 })
            }
        }
    }

    @Test
    fun `핸들을 오른쪽으로 돌리면 멜로디가 올라간다`() {
        val left = melody(play(Composer(1), Inputs(speed = 60, steer = -6), 64)).map { it.pitch }.average()
        val right = melody(play(Composer(1), Inputs(speed = 60, steer = 6), 64)).map { it.pitch }.average()
        assertTrue("왼쪽 $left 오른쪽 $right", right > left + 8)
    }

    @Test
    fun `브레이크는 쉼표, 와이퍼는 드럼, 동승자는 악기를 더한다`() {
        assertTrue(melody(play(Composer(2), Inputs(brake = true), 32)).isEmpty())
        val solo = play(Composer(2), Inputs(), 32).map { it.inst }.toSet()
        assertFalse(Instrument.KICK in solo)
        assertTrue(Instrument.KICK in play(Composer(2), Inputs(wiper = true), 32).map { it.inst })
        val family = play(Composer(2), Inputs(riders = 4), 32).map { it.inst }.toSet()
        assertTrue(family.containsAll(listOf(Instrument.BASS, Instrument.PLUCK, Instrument.BELL)))
        assertFalse(Instrument.BASS in solo)
        assertTrue(Instrument.TICK in play(Composer(2), Inputs(turn = Turn.LEFT), 8).map { it.inst })
    }

    @Test
    fun `녹음한 곡을 다시 들으면 똑같은 음이 나온다`() {
        val song = Song(42, 0)
        val live = Composer(42)
        val heard = mutableListOf<Note>()
        val drive = listOf(Inputs(speed = 30), Inputs(speed = 60, steer = 3), Inputs(speed = 60, turn = Turn.RIGHT), Inputs(brake = true, wiper = true))
        drive.forEach { i -> repeat(40) { song.add(i); heard += live.next(i) } }
        assertEquals(4, song.runs.size)       // 같은 값은 묶여서 저장
        val back = Song.fromJson(JSONObject(song.toJson().toString()))
        val replay = Composer(back.seed)
        val again = back.inputs().flatMap { replay.next(it) }.toList()
        assertEquals(heard, again)
    }

    @Test
    fun `곡 제목 - 시간대, 비, 합주, 길이`() {
        val s = Song(1, 0)
        repeat(8 * 60 * 10) { s.add(Inputs(speed = 60, wiper = it % 2 == 0, riders = 3)) }   // 100 BPM → 0.15초 × 4800 = 12분
        assertEquals("10/29 퇴근길 · 비 · 3인 합주 · 12분", s.title(18, 10, 29))
        assertEquals("1/2 출근길 · 1분", Song(1, 0).apply { add(Inputs()) }.title(7, 1, 2))
    }
}
