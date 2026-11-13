package com.team35.pleoskit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 차 화면 공통 색 (어두운 배경, 큰 글자, 높은 대비) */
object Palette {
    val Dim = Color(0xFF9AA0A6)
    val Card = Color(0xFF22252B)
    val Good = Color(0xFF69F0AE)
    val Warn = Color(0xFFFFD740)
    val Bad = Color(0xFFFF5252)
}

@Composable
fun AppTheme(accent: Color, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(primary = accent)) {
        Surface(Modifier.fillMaxSize(), content = content)
    }
}

/** 화면 제목 + 한 줄 설명 */
@Composable
fun Title(title: String, sub: String) {
    Text(title, fontSize = 30.sp, fontWeight = FontWeight.Bold)
    Text(sub, fontSize = 16.sp, color = Palette.Dim)
}

/** 둥근 카드 */
@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().background(Palette.Card, RoundedCornerShape(14.dp)).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

/** 주행 중 화면: 큰 글자 한두 줄만 (운전자 주의분산 가이드) */
@Composable
fun DrivingView(label: String, big: String, color: Color, sub: String? = null) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("주행 중 · 간단 화면", fontSize = 20.sp, color = Palette.Dim)
        Text(label, fontSize = 28.sp)
        Text(big, fontSize = 72.sp, fontWeight = FontWeight.Bold, color = color)
        if (sub != null) Text(sub, fontSize = 26.sp, color = Palette.Dim)
    }
}
