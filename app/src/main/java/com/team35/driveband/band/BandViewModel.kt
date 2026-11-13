package com.team35.driveband.band

import ai.pleos.playground.vehicle.Vehicle
import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import kotlin.concurrent.thread

enum class Tab(val label: String) { RHYTHM("노래 따라 연주"), FREE("자유 연주"), CALIBRATE("박자 맞추기") }
enum class FreeMode(val label: String) { DRUM("드럼"), GUITAR("기타") }
enum class Phase { LOADING, READY, PLAYING, RESULT }

class BandViewModel(app: Application) : AndroidViewModel(app) {
    private val main = Handler(Looper.getMainLooper())
    private val prefs = app.getSharedPreferences("carband", Context.MODE_PRIVATE)
    private val audio = Audio(app)
    val song = Songs.firstIgnition
    private var backing: ShortArray? = null

    var tab by mutableStateOf(Tab.RHYTHM)
    var freeMode by mutableStateOf(FreeMode.DRUM)
    var parked by mutableStateOf(true)
        private set
    var steering by mutableStateOf(0f)
        private set
    var phase by mutableStateOf(Phase.LOADING)
        private set
    var judge by mutableStateOf(Judge(song.chart))
        private set
    /** 화면 갱신용 노래 시각 */
    var songMs by mutableLongStateOf(-1L)
        private set
    var lastGrade by mutableStateOf<Grade?>(null)
        private set
    var lastGradeAt by mutableLongStateOf(0L)
        private set
    /** 조작부별 마지막으로 친 시각(uptime ms) — 레인 반짝임 */
    val flash = mutableStateMapOf<Pad, Long>()
    var offsetMs by mutableLongStateOf(prefs.getLong("offsetMs", 0L))
        private set

    // 박자 맞추기
    var calibration by mutableStateOf<Calibration?>(null)
        private set
    var calibrationHits by mutableStateOf(0)
        private set
    var calibrationResult by mutableStateOf<Long?>(null)
        private set
    private val clickTrack by lazy { Synth.toPcm16(Synth.clickTrack(CAL_BEAT_MS, CAL_CLICKS), 0.9f) }

    /** 기타 모드: 핸들 각도로 코드 고르기 */
    val chord get() = when {
        steering < -45 -> Chord.F
        steering > 180 -> Chord.Am
        steering > 45 -> Chord.G
        else -> Chord.C
    }

    private val inputs = CarInputs(
        onHit = { pad -> onHit(pad, clockMs()) },
        onSteer = { a -> main.post { steering = a } },
        onParked = { p -> main.post { parked = p; if (!p) stop() } },
    )

    /** 차 입력은 연주 탭을 열었을 때만 받는다 (주행 중·드라이브 송 탭에선 꺼서 신호 부하를 줄임) */
    private var active = false

    fun setActive(on: Boolean, vehicle: Vehicle) {
        if (on == active) return
        active = on
        if (on) inputs.start(vehicle) else { stop(); inputs.stop() }
    }

    init {
        thread(name = "render-backing") {
            val pcm = Synth.toPcm16(song.renderBacking(), 0.7f)
            main.post { backing = pcm; audio.prepareBacking(pcm); phase = Phase.READY }
        }
    }

    /** 바인더 스레드에서 호출. songAtHit = 받은 순간의 노래 시각 (판정은 이 값으로) */
    private fun onHit(pad: Pad, songAtHit: Long) {
        if (parked) audio.play(soundFor(pad))
        main.post {
            if (!parked) return@post
            flash[pad] = SystemClock.uptimeMillis()
            when (tab) {
                Tab.RHYTHM -> when (phase) {
                    Phase.READY, Phase.RESULT -> if (pad == Pad.BRAKE) start()
                    Phase.PLAYING -> {
                        val r = judge.hit(pad, songAtHit)
                        r?.let { (_, g) -> showGrade(g) }
                        Log.d(TAG, "PLAY $pad t=$songAtHit -> ${r?.let { (i, g) -> "$g ${songAtHit - song.chart[i].timeMs}ms" } ?: "노트 없음"}")
                    }
                    Phase.LOADING -> {}
                }
                Tab.CALIBRATE -> calibration?.let {
                    if (pad == Pad.BRAKE) { it.hit(songAtHit); calibrationHits = it.count }
                }
                Tab.FREE -> {}
            }
        }
    }

    private fun soundFor(pad: Pad): Sound =
        if (tab == Tab.FREE && freeMode == FreeMode.GUITAR) when (pad) {
            Pad.BRAKE, Pad.RIGHT -> strumOf(chord)
            Pad.LEFT -> muteOf(chord)
            Pad.STEER -> Sound.CRASH
        } else when (pad) {
            Pad.BRAKE -> Sound.KICK
            Pad.LEFT -> Sound.SNARE
            Pad.RIGHT -> Sound.HIHAT
            Pad.STEER -> Sound.CRASH
        }

    private fun showGrade(g: Grade) {
        lastGrade = g
        lastGradeAt = SystemClock.uptimeMillis()
    }

    fun start() {
        if (backing == null) return
        judge = Judge(song.chart, offsetMs)
        lastGrade = null
        phase = Phase.PLAYING
        startedAt = audio.playBacking() ?: SystemClock.elapsedRealtime()
    }

    /** 다음 판을 바로 시작할 수 있게 반주를 다시 준비 */
    private fun rearm() {
        backing?.let(audio::prepareBacking)
    }

    fun stop() {
        if (phase == Phase.PLAYING) phase = Phase.READY
        calibration = null
        rearm()
    }

    /** 화면 프레임마다: 노래 시각 갱신, 놓친 노트 처리, 끝나면 결과 */
    fun tick() {
        val t = clockMs()
        songMs = t
        if (phase == Phase.PLAYING) {
            // 입력 시각은 받은 순간 찍지만 판정은 메인 스레드에서 한다 → 메인이 바빠 늦게 처리돼도
            // 먼저 놓침 처리되지 않게 여유를 둔다
            if (judge.sweep(t - SWEEP_GRACE_MS).isNotEmpty()) showGrade(Grade.MISS)
            // 마지막 2초는 여운 → 0.5초 전에 결과로
            if (t >= song.lengthMs - 500) {
                rearm()
                phase = Phase.RESULT
            }
        }
        calibration?.let { c ->
            if (t >= c.endMs) {
                rearm()
                calibration = null
                calibrationResult = c.result()
                c.result()?.let(::setOffset)
            }
        }
    }

    fun startCalibration() {
        calibrationResult = null
        calibrationHits = 0
        calibration = Calibration(CAL_BEAT_MS, firstClickMs = (4 * CAL_BEAT_MS).toLong(), clicks = CAL_CLICKS)
        audio.prepareBacking(clickTrack)
        startedAt = audio.playBacking() ?: SystemClock.elapsedRealtime()
    }

    /**
     * 노래 시각(ms). 오디오 재생 위치(playbackHeadPosition) 대신 시스템 시계를 쓴다 —
     * 에뮬레이터에서 타격음이 겹치면 재생 위치가 실제 시간보다 5%쯤 뒤처졌다 (2026-10-29 측정).
     */
    private fun clockMs(): Long = if (startedAt == 0L) -1 else SystemClock.elapsedRealtime() - startedAt - OUTPUT_LATENCY_MS

    @Volatile private var startedAt = 0L

    fun changeOffset(delta: Long) = setOffset(offsetMs + delta)

    private fun setOffset(v: Long) {
        offsetMs = v.coerceIn(-300, 500)
        judge.offsetMs = offsetMs
        prefs.edit().putLong("offsetMs", offsetMs).apply()
    }

    override fun onCleared() {
        if (active) { active = false; inputs.stop() }
        audio.release()
    }

    companion object {
        private const val TAG = "CarBand"
        const val CAL_BEAT_MS = 750.0 // 80 BPM
        const val OUTPUT_LATENCY_MS = 30 // 재생 시작 ~ 소리 나기까지 (에뮬레이터 측정값)
        const val SWEEP_GRACE_MS = 250
        const val CAL_CLICKS = 8
    }
}
