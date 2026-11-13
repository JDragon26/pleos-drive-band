package com.team35.driveband.band

/** 연주에 쓰는 차 조작부 */
enum class Pad(val label: String, val sound: String) {
    BRAKE("브레이크", "킥"),
    LEFT("왼쪽 깜빡이", "스네어"),
    RIGHT("오른쪽 깜빡이", "하이햇"),
    STEER("핸들 꺾기", "심벌"),
}

data class Note(val pad: Pad, val timeMs: Long)

/**
 * 노래 = 반주(코드 진행) + 채보(언제 어떤 조작부를 누를지).
 * 반주에는 드럼이 없다 — 드럼은 플레이어가 차로 친다.
 */
class Song(
    val title: String,
    val bpm: Int,
    val bars: List<Chord>,        // 마디별 코드
    val countInBars: Int,
    chartBuilder: Song.() -> List<Note>,
) {
    val beatMs = 60_000.0 / bpm
    val chart: List<Note> = chartBuilder().sortedBy { it.timeMs }
    val lengthMs = ((countInBars + bars.size) * 4 * beatMs + 2_000).toLong()

    /** bar 는 1부터(카운트인 제외), beat 는 1.0 ~ 4.5 */
    fun at(bar: Int, beat: Double) = ((countInBars * 4 + (bar - 1) * 4 + (beat - 1)) * beatMs).toLong()

    /** 반주 PCM: 카운트인 하이햇 + 코드 스트럼 + 베이스 */
    fun renderBacking(): FloatArray {
        val rate = Synth.RATE
        val out = FloatArray((lengthMs / 1000.0 * rate).toInt())
        fun sample(ms: Double) = (ms / 1000 * rate).toInt()
        val tick = Synth.hihat()
        repeat(countInBars * 4) { Synth.mixInto(out, tick, sample(it * beatMs), 0.9f) }

        val strums = Chord.entries.associateWith { Synth.strum(it, seconds = 2.2) }
        val muted = Chord.entries.associateWith { Synth.strum(it, seconds = 0.3, muted = true) }
        val basses = Chord.entries.associateWith { Synth.bass(it.bassFreq(), 1.1) }
        bars.forEachIndexed { i, chord ->
            val bar = i + 1
            Synth.mixInto(out, strums.getValue(chord), sample(at(bar, 1.0).toDouble()), 0.55f)
            Synth.mixInto(out, muted.getValue(chord), sample(at(bar, 2.0).toDouble()), 0.35f)
            Synth.mixInto(out, strums.getValue(chord), sample(at(bar, 3.0).toDouble()), 0.4f)
            Synth.mixInto(out, muted.getValue(chord), sample(at(bar, 4.0).toDouble()), 0.35f)
            Synth.mixInto(out, basses.getValue(chord), sample(at(bar, 1.0).toDouble()), 0.8f)
            Synth.mixInto(out, basses.getValue(chord), sample(at(bar, 3.0).toDouble()), 0.6f)
        }
        // 마지막 코드 한 번 더 길게
        Synth.mixInto(out, strums.getValue(Chord.C), sample(at(bars.size + 1, 1.0).toDouble()), 0.6f)
        return out
    }
}

object Songs {
    /** 직접 만든 연습곡 "첫 시동" — 100 BPM, 16마디, 점점 어려워진다 */
    val firstIgnition = Song(
        title = "첫 시동",
        bpm = 100,
        bars = List(4) { listOf(Chord.C, Chord.G, Chord.Am, Chord.F) }.flatten(),
        countInBars = 1,
    ) {
        buildList {
            fun n(pad: Pad, bar: Int, beat: Double) = add(Note(pad, at(bar, beat)))
            for (bar in 1..4) {             // 1단계: 킥 + 스네어 하나씩
                n(Pad.BRAKE, bar, 1.0); n(Pad.LEFT, bar, 3.0)
            }
            for (bar in 5..8) {             // 2단계: 기본 8비트
                n(Pad.BRAKE, bar, 1.0); n(Pad.LEFT, bar, 2.0); n(Pad.BRAKE, bar, 3.0); n(Pad.LEFT, bar, 4.0)
            }
            for (bar in 9..12) {            // 3단계: 하이햇(오른쪽) 추가
                n(Pad.BRAKE, bar, 1.0); n(Pad.LEFT, bar, 2.0); n(Pad.BRAKE, bar, 3.0); n(Pad.RIGHT, bar, 4.0)
            }
            n(Pad.STEER, 13, 1.0)           // 4단계: 핸들 꺾어 심벌, 엇박 킥
            for (bar in 13..15) {
                n(Pad.BRAKE, bar, 1.0); n(Pad.LEFT, bar, 2.0); n(Pad.BRAKE, bar, 2.5); n(Pad.BRAKE, bar, 3.0); n(Pad.RIGHT, bar, 4.0)
            }
            n(Pad.BRAKE, 16, 1.0); n(Pad.STEER, 17, 1.0) // 마무리
        }
    }
}
