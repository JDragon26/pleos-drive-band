package com.team35.driveband

import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import android.app.Application
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.team35.driveband.band.BandScreen
import com.team35.driveband.band.BandViewModel
import com.team35.driveband.band.Tab
import com.team35.pleoskit.AppTheme
import com.team35.pleoskit.CarHub
import com.team35.pleoskit.CarState
import com.team35.pleoskit.DrivingView
import com.team35.pleoskit.Palette
import com.team35.pleoskit.Panel
import com.team35.pleoskit.Signal
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.io.File
import java.util.Calendar

private val Accent = Color(0xFFB388FF)

/** 저장된 곡 (목록용) */
data class SavedSong(val file: File, val title: String, val song: Song)

enum class Mode { IDLE, RECORDING, PREVIEW, REPLAY }

class SongViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("song", Context.MODE_PRIVATE)
    private val dir = File(app.filesDir, "songs").apply { mkdirs() }
    private val main = Handler(Looper.getMainLooper())
    private val audio = app.getSystemService(AudioManager::class.java)
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(attrs)
        .setOnAudioFocusChangeListener { if (it == AudioManager.AUDIOFOCUS_LOSS) main.post { stop() } }
        .build()

    var car by mutableStateOf(CarState())
        private set
    var mode by mutableStateOf(Mode.IDLE)
        private set
    var playing by mutableStateOf<File?>(null)
        private set
    var songs by mutableStateOf(load())
        private set
    var autoPlay by mutableStateOf(prefs.getBoolean("auto", true))
        private set
    var justSaved by mutableStateOf<String?>(null)
        private set
    /** 연주 상태 (화면 갱신용) */
    var tick by mutableIntStateOf(0)
        private set

    private var player: Player? = null
    private var recording: Song? = null
    @Volatile private var live = Inputs()

    private val hub = CarHub(app, setOf(Signal.DRIVE, Signal.SPEED, Signal.SEATS, Signal.WINDOWS)) { old, new ->
        car = new
        live = Inputs.of(
            new.speedKph ?: 0.0, new.steering.toDouble(),
            when (new.turn) { TurnSignalValue.LEFT -> Turn.LEFT; TurnSignalValue.RIGHT -> Turn.RIGHT; else -> Turn.OFF },
            new.wiper, new.brake, new.riders,
        )
        if (old.parked && !new.parked && autoPlay) record()
        if (!old.parked && new.parked && mode == Mode.RECORDING) stop()
    }

    init { hub.start() }

    val bpm get() = player?.bpm ?: 0
    val chord get() = player?.chordName ?: ""
    val layers get() = player?.layers ?: 0
    val now get() = live
    /** 카 밴드가 같은 차량 연결을 쓰도록 */
    val vehicle get() = hub.vehicle

    /** 주행 시작: 지금 차 상태로 작곡하며 녹음 */
    fun record() {
        stop()
        val song = Song(System.nanoTime(), System.currentTimeMillis())
        recording = song
        play(Mode.RECORDING, song.seed) { live.also { song.add(it) } }
    }

    /** 정차 중 미리 듣기: 녹음 없이 지금 차 상태로 연주 */
    fun preview() {
        stop()
        play(Mode.PREVIEW, System.nanoTime()) { live }
    }

    fun replay(s: SavedSong) {
        stop()
        val it = s.song.inputs().iterator()
        playing = s.file
        play(Mode.REPLAY, s.song.seed) { if (it.hasNext()) it.next() else null }
    }

    private fun play(m: Mode, seed: Long, next: () -> Inputs?) {
        audio.requestAudioFocus(focus)
        mode = m
        player = Player(attrs, seed, next) { main.post { if (mode == Mode.REPLAY) stop() } }.also { it.start() }
    }

    fun stop() {
        player?.stop()
        player = null
        audio.abandonAudioFocusRequest(focus)
        recording?.let { save(it) }
        recording = null
        playing = null
        mode = Mode.IDLE
    }

    fun toggleAuto() {
        autoPlay = !autoPlay
        prefs.edit().putBoolean("auto", autoPlay).apply()
    }

    fun refresh() { tick++ }

    private fun titleOf(s: Song): String {
        val c = Calendar.getInstance().apply { timeInMillis = s.startedAtMs }
        return s.title(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }

    private fun save(s: Song) {
        if (s.seconds < MIN_SECONDS) return
        File(dir, "${s.startedAtMs}.json").writeText(s.toJson().toString())
        justSaved = titleOf(s)
        songs = load()
    }

    private fun load(): List<SavedSong> = (dir.listFiles { f -> f.extension == "json" } ?: emptyArray())
        .sortedByDescending { it.name }
        .mapNotNull { f -> runCatching { Song.fromJson(JSONObject(f.readText())) }.getOrNull()?.let { SavedSong(f, titleOf(it), it) } }
        .also { list -> list.drop(MAX_SONGS).forEach { it.file.delete() } }
        .take(MAX_SONGS)

    override fun onCleared() {
        stop()
        hub.stop()
    }

    companion object {
        const val MIN_SECONDS = 20.0
        const val MAX_SONGS = 30
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme(Accent) {
                val vm: SongViewModel = viewModel()
                val band: BandViewModel = viewModel()
                var tab by rememberSaveable { mutableStateOf<Tab?>(null) } // null = 드라이브 송 탭
                // 연주 상태(템포·화음)는 오디오 스레드 값이라 0.5초마다 다시 읽는다
                LaunchedEffect(Unit) { while (true) { delay(500); vm.refresh() } }
                // 카 밴드는 정차 중 + 밴드 탭일 때만 차 입력을 받는다. 들어가면 드라이브 송 미리 듣기·다시 듣기는 멈춤
                val bandOn = vm.car.parked && tab != null
                LaunchedEffect(bandOn) {
                    if (bandOn && vm.mode != Mode.RECORDING) vm.stop()
                    band.setActive(bandOn, vm.vehicle)
                }
                if (!vm.car.parked) Driving(vm) else Column {
                    Tabs(tab) { t ->
                        band.stop()
                        t?.let { band.tab = it }
                        tab = t
                    }
                    if (tab == null) Parked(vm) else Column(Modifier.padding(horizontal = 24.dp)) { BandScreen(band) }
                }
            }
        }
    }
}

/** 맨 위 탭: 드라이브 송(주행 중 자동 작곡한 곡) + 카 밴드 3개(정차 중 직접 연주) */
@Composable
private fun Tabs(current: Tab?, onPick: (Tab?) -> Unit) {
    Row(
        Modifier.padding(start = 24.dp, end = 24.dp, top = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("드라이브 밴드", fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 12.dp))
        for (t in listOf<Tab?>(null) + Tab.entries) {
            val label = t?.label ?: "드라이브 송"
            if (t == current) Button(onClick = {}) { Text(label, fontSize = 18.sp) }
            else OutlinedButton(onClick = { onPick(t) }) { Text(label, fontSize = 18.sp) }
        }
    }
}

private fun describe(i: Inputs) = buildList {
    add(if (i.wiper) "빗길 비트" else "맑은 날")
    if (i.riders >= 2) add("${i.riders}인 합주")
    if (i.brake) add("쉼표")
    if (i.turn != Turn.OFF) add("깜빡이 박자")
}.joinToString(" · ")

/** 주행 중: 큰 글자 두 줄 (조작 없음) */
@Composable
private fun Driving(vm: SongViewModel) {
    vm.tick // 템포는 오디오 스레드 값 → 갱신 신호를 여기서 읽어야 다시 그린다 (Compose 건너뛰기)
    if (vm.mode == Mode.RECORDING) DrivingView("드라이브 송 · 작곡 중", "♪ ${vm.bpm} BPM", Accent, describe(vm.now))
    else DrivingView("드라이브 송", "대기 중", Palette.Dim, "다음 출발부터 자동으로 작곡해요")
}

@Composable
private fun Parked(vm: SongViewModel) {
    vm.tick
    Row(Modifier.padding(start = 24.dp, end = 24.dp, top = 14.dp, bottom = 24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("주행하면 차가 작곡해요 — 오늘 이 길에서만 나오는 곡", fontSize = 18.sp, color = Palette.Dim)
            vm.justSaved?.let { Panel { Text("방금 곡을 저장했어요: $it", fontSize = 20.sp, color = Accent, fontWeight = FontWeight.Bold) } }
            Panel {
                val on = vm.mode == Mode.PREVIEW || vm.mode == Mode.RECORDING
                Text(if (on) "♪ 연주 중  ${vm.bpm} BPM · ${vm.chord}" else "지금 차로 연주해 보기", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                if (on) Text(describe(vm.now), fontSize = 18.sp, color = Palette.Dim)
                Text("핸들을 돌리면 멜로디가 올라가고 내려가요. 깜빡이·와이퍼·브레이크도 음악을 바꿔요.", fontSize = 16.sp, color = Palette.Dim)
                Button(
                    onClick = { if (on) vm.stop() else vm.preview() },
                    colors = ButtonDefaults.buttonColors(containerColor = if (on) Palette.Bad else Accent),
                ) { Text(if (on) "■ 멈추기" else "▶ 미리 듣기", fontSize = 20.sp) }
            }
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("출발하면 자동 작곡·녹음", fontSize = 20.sp)
                        Text("P에서 벗어나면 시작, P로 돌아오면 곡으로 저장", fontSize = 15.sp, color = Palette.Dim)
                    }
                    Switch(checked = vm.autoPlay, onCheckedChange = { vm.toggleAuto() })
                }
            }
            Panel {
                Text("차가 연주하는 법", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                listOf(
                    "속도 → 템포 (72~128 BPM)", "핸들 → 멜로디 높낮이", "깜빡이 → 똑딱 박자와 꾸밈음",
                    "와이퍼 → 드럼 비트·빗길 화음", "브레이크 → 쉼표·잔향", "탑승 인원 → 악기 수",
                ).forEach { Text("· $it", fontSize = 16.sp, color = Palette.Dim) }
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Panel {
                Text("내 드라이브 곡 ${vm.songs.size}개", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                if (vm.songs.isEmpty()) Text("아직 곡이 없어요. 출발하면 첫 곡이 만들어져요.", fontSize = 18.sp, color = Palette.Dim)
                vm.songs.forEach { s ->
                    val on = vm.playing == s.file
                    Row(
                        Modifier.fillMaxWidth().clickable { if (on) vm.stop() else vm.replay(s) }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(if (on) "■" else "▶", fontSize = 26.sp, color = Accent, modifier = Modifier.padding(end = 14.dp))
                        Text(s.title, fontSize = 19.sp, color = if (on) Accent else Color.Unspecified)
                    }
                }
            }
        }
    }
}
