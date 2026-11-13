package com.team35.driveband.band

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Accent = Color(0xFFFF6B9A)
private val Dim = Color(0xFF9AA0A6)
private val PadColor = mapOf(
    Pad.BRAKE to Color(0xFFFF5252),
    Pad.LEFT to Color(0xFF40C4FF),
    Pad.RIGHT to Color(0xFF69F0AE),
    Pad.STEER to Color(0xFFFFD740),
)

/** 카 밴드 탭 화면 (정차 중에만 불린다) */
@Composable
fun BandScreen(vm: BandViewModel) {
    when (vm.tab) {
        Tab.RHYTHM -> RhythmScreen(vm)
        Tab.FREE -> FreeScreen(vm)
        Tab.CALIBRATE -> CalibrateScreen(vm)
    }
}

/** 매 프레임 갱신되는 현재 시각 — 눌림 반짝임이 사라지게 */
@Composable
private fun rememberUptime(): Long {
    var now by remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
    LaunchedEffect(Unit) { while (true) withFrameNanos { now = SystemClock.uptimeMillis() } }
    return now
}

// ───────────── 노래 따라 연주 ─────────────

@Composable
private fun RhythmScreen(vm: BandViewModel) {
    LaunchedEffect(vm.phase) {
        while (vm.phase == Phase.PLAYING) withFrameNanos { vm.tick() }
    }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Lanes(vm, Modifier.weight(1.3f).fillMaxSize())
        Column(Modifier.weight(1f)) { RhythmSide(vm) }
    }
}

@Composable
private fun RhythmSide(vm: BandViewModel) {
    val j = vm.judge
    Text("♪ ${vm.song.title}", fontSize = 26.sp, fontWeight = FontWeight.Bold)
    Text("${vm.song.bpm} BPM · 직접 만든 곡", color = Dim, fontSize = 16.sp)
    Spacer(Modifier.height(16.dp))
    when (vm.phase) {
        Phase.LOADING -> Text("곡 준비 중…", fontSize = 22.sp)
        Phase.READY -> {
            Text("브레이크를 밟으면 시작!", fontSize = 28.sp, color = Accent, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            for (p in Pad.entries) Text("● ${p.label} = ${p.sound}", color = PadColor.getValue(p), fontSize = 20.sp)
        }
        Phase.PLAYING -> {
            Text("점수 ${j.score}", fontSize = 34.sp, fontWeight = FontWeight.Bold)
            Text("콤보 ${j.combo}", fontSize = 26.sp, color = Accent)
            val g = vm.lastGrade
            if (g != null && SystemClock.uptimeMillis() - vm.lastGradeAt < 600) {
                Text(g.label, fontSize = 40.sp, fontWeight = FontWeight.Bold,
                    color = if (g == Grade.MISS) Dim else PadColor.getValue(Pad.STEER))
            }
        }
        Phase.RESULT -> {
            Text("★".repeat(j.stars()) + "☆".repeat(3 - j.stars()), fontSize = 52.sp, color = PadColor.getValue(Pad.STEER))
            Text("점수 ${j.score} · 최대 콤보 ${j.maxCombo}", fontSize = 24.sp)
            Text("완벽 ${j.count(Grade.PERFECT)} · 좋아요 ${j.count(Grade.GOOD)} · 놓침 ${j.count(Grade.MISS)}", fontSize = 20.sp, color = Dim)
            Spacer(Modifier.height(8.dp))
            Text("브레이크를 밟으면 다시", fontSize = 22.sp, color = Accent)
        }
    }
    Spacer(Modifier.height(20.dp))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("판정 보정", fontSize = 16.sp, color = Dim)
        OutlinedButton(onClick = { vm.changeOffset(-10) }) { Text("−") }
        Text("${vm.offsetMs}ms", fontSize = 18.sp)
        OutlinedButton(onClick = { vm.changeOffset(10) }) { Text("+") }
    }
    if (vm.phase == Phase.PLAYING) {
        OutlinedButton(onClick = { vm.stop() }) { Text("그만하기") }
    }
}

/** 4개 레인에 노트가 위에서 내려온다. 아래 선에 닿을 때 누르면 된다. */
@Composable
private fun Lanes(vm: BandViewModel, modifier: Modifier) {
    val now = vm.songMs
    val j = vm.judge
    val lookAhead = 1800.0
    val nowUp = rememberUptime()
    Column(modifier) {
        Canvas(Modifier.weight(1f).fillMaxWidth()) {
            val laneW = size.width / Pad.entries.size
            val hitY = size.height * 0.88f
            Pad.entries.forEachIndexed { i, p ->
                val lit = nowUp - (vm.flash[p] ?: 0L) < 150
                drawRect(
                    if (lit) PadColor.getValue(p).copy(alpha = 0.25f) else Color(0xFF1C1F25),
                    topLeft = Offset(i * laneW + 4, 0f), size = Size(laneW - 8, size.height),
                )
            }
            drawRect(Color.White.copy(alpha = 0.7f), topLeft = Offset(0f, hitY - 2), size = Size(size.width, 4f))
            if (vm.phase == Phase.PLAYING && now >= 0) {
                vm.song.chart.forEachIndexed { idx, n ->
                    if (j.gradeOf(idx) != null) return@forEachIndexed
                    val dt = n.timeMs - now // 화면은 소리 기준, 지연 보정은 판정에만
                    if (dt < -200 || dt > lookAhead) return@forEachIndexed
                    val y = hitY - (dt / lookAhead * hitY).toFloat()
                    val i = n.pad.ordinal
                    drawRoundRect(
                        PadColor.getValue(n.pad),
                        topLeft = Offset(i * laneW + 14, y - 14), size = Size(laneW - 28, 28f),
                        cornerRadius = CornerRadius(14f, 14f),
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth()) {
            for (p in Pad.entries) {
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(p.label, fontSize = 15.sp, color = PadColor.getValue(p))
                    Text(p.sound, fontSize = 13.sp, color = Dim)
                }
            }
        }
    }
}

// ───────────── 자유 연주 ─────────────

@Composable
private fun FreeScreen(vm: BandViewModel) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        for (m in FreeMode.entries) {
            if (m == vm.freeMode) Button(onClick = {}) { Text(m.label, fontSize = 20.sp) }
            else OutlinedButton(onClick = { vm.freeMode = m }) { Text(m.label, fontSize = 20.sp) }
        }
    }
    Spacer(Modifier.height(20.dp))
    val nowUp = rememberUptime()
    if (vm.freeMode == FreeMode.GUITAR) {
        Text("핸들로 코드 고르기: 왼쪽 F · 가운데 C · 오른쪽 G · 크게 오른쪽 Am", color = Dim, fontSize = 17.sp)
        Text("지금 코드  ${vm.chord.label}", fontSize = 64.sp, fontWeight = FontWeight.Bold, color = Accent)
        Text("핸들 ${"%.0f".format(vm.steering)}°", color = Dim, fontSize = 16.sp)
        Spacer(Modifier.height(12.dp))
    }
    val sounds = if (vm.freeMode == FreeMode.DRUM) mapOf(
        Pad.BRAKE to "킥", Pad.LEFT to "스네어", Pad.RIGHT to "하이햇", Pad.STEER to "심벌",
    ) else mapOf(
        Pad.BRAKE to "스트럼", Pad.LEFT to "뮤트 커팅", Pad.RIGHT to "스트럼", Pad.STEER to "심벌",
    )
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        for (p in Pad.entries) {
            val lit = nowUp - (vm.flash[p] ?: 0L) < 180
            Column(
                Modifier.weight(1f).height(170.dp)
                    .background(if (lit) PadColor.getValue(p) else Color(0xFF22252B), RoundedCornerShape(16.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(sounds.getValue(p), fontSize = 30.sp, fontWeight = FontWeight.Bold, color = if (lit) Color.Black else Color.White)
                Text(p.label, fontSize = 16.sp, color = if (lit) Color.Black else Dim)
            }
        }
    }
}

// ───────────── 박자 맞추기 ─────────────

@Composable
private fun CalibrateScreen(vm: BandViewModel) {
    LaunchedEffect(vm.calibration) {
        while (vm.calibration != null) withFrameNanos { vm.tick() }
    }
    Text("메트로놈 소리에 맞춰 브레이크를 밟아 주세요", fontSize = 24.sp, fontWeight = FontWeight.Bold)
    Text("차 신호가 앱에 도착하는 시간 + 내 반응을 재서 판정을 맞춰요 (준비 4박 뒤 8번)", fontSize = 16.sp, color = Dim)
    Spacer(Modifier.height(20.dp))
    val c = vm.calibration
    if (c != null) {
        Text("밟은 횟수 ${vm.calibrationHits} / ${BandViewModel.CAL_CLICKS}", fontSize = 40.sp, color = Accent)
    } else {
        Button(onClick = { vm.startCalibration() }) { Text("시작", fontSize = 22.sp) }
        Spacer(Modifier.height(16.dp))
        when (val r = vm.calibrationResult) {
            null -> if (vm.calibrationHits > 0) Text("4번 이상 맞춰 밟아야 해요. 다시 해 볼까요?", fontSize = 20.sp)
            else -> {
                Text("평균 ${r}ms 늦게 들어와요", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Accent)
                Text("판정 보정값을 ${vm.offsetMs}ms 로 맞췄어요", fontSize = 20.sp, color = Dim)
            }
        }
    }
}