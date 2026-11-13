# 드라이브 밴드 시연 영상용 — 안내 + 1부 드라이브 송 + 2부 카 밴드 + 결과 읽기 + 마무리
# ▶ 로 시작하는 줄이 자막이 된다. 소리가 핵심이므로 "지금 나오는 소리" 자막은 실제 재생이 시작된 뒤에 찍는다.
param([switch]$Auto)
$ErrorActionPreference = 'Stop'
$adb = "$env:ANDROID_HOME\platform-tools\adb.exe"
$tools = $PSScriptRoot
. "$tools\vc.ps1"

function Ui-Text {
  & $adb shell uiautomator dump /sdcard/ui.xml 2>$null | Out-Null
  $enc = [Console]::OutputEncoding
  [Console]::OutputEncoding = [Text.Encoding]::UTF8
  try { (& $adb shell cat /sdcard/ui.xml) -join '' } finally { [Console]::OutputEncoding = $enc }
}

Start-Sleep -Seconds 3   # 맨 앞 제목 자막을 읽을 시간
Write-Host '▶ 에뮬레이터 자동 시연 — 차량 신호를 빠르게 주입합니다'
Start-Sleep -Seconds 4

Write-Host '── 1부: 드라이브 송 (주행 중 자동 작곡) ──'
& "$tools\demo_drivesong.ps1" -Auto:$Auto
Adb-TapText '■'   # 다시 듣기 멈춤

Write-Host '── 2부: 카 밴드 (정차 중 연주) ──'
Write-Host '▶ 서 있으면 차가 악기 — 노래 따라 연주'
Adb-TapText '노래 따라 연주'; Start-Sleep 2
& "$tools\demo_carband.ps1"
Start-Sleep 3

# 결과를 화면에서 읽어 그대로 자막으로 (문서의 다른 수치를 쓰지 않는다)
$ui = Ui-Text
$score = [regex]::Match($ui, '점수\s*([0-9,]+)').Groups[1].Value
$combo = [regex]::Match($ui, '최대\s*콤보\s*([0-9]+)').Groups[1].Value
$perfect = [regex]::Match($ui, '완벽\s*([0-9]+)').Groups[1].Value
$good = [regex]::Match($ui, '좋아요\s*([0-9]+)').Groups[1].Value
$miss = [regex]::Match($ui, '놓침\s*([0-9]+)').Groups[1].Value
$line = '자동 시연 결과'
if ($score) { $line += " — 점수 $score" }
if ($perfect) { $line += " · 완벽 $perfect / 좋아요 $good / 놓침 $miss" }
Write-Host "▶ $line"
Start-Sleep -Seconds 8

Write-Host '▶ 자유 연주 — 차를 드럼처럼'
Adb-TapText '자유 연주'; Start-Sleep 2
Vc-Brake 60; Start-Sleep -Milliseconds 150; Vc-Brake 0; Start-Sleep -Milliseconds 350
Write-Host '▶ 브레이크=킥 · 깜빡이=스네어 · 핸들=심벌'
foreach ($i in 1..2) {
    Vc-Brake 60; Start-Sleep -Milliseconds 150; Vc-Brake 0; Start-Sleep -Milliseconds 350
    Vc-Turn 'BlinkLeft'; Start-Sleep -Milliseconds 200; Vc-Turn 'Off'; Start-Sleep -Milliseconds 300
    Vc-Brake 60; Start-Sleep -Milliseconds 150; Vc-Brake 0; Start-Sleep -Milliseconds 350
    Vc-Turn 'BlinkRight'; Start-Sleep -Milliseconds 200; Vc-Turn 'Off'; Start-Sleep -Milliseconds 300
}
Vc-Steer 150; Start-Sleep -Milliseconds 300; Vc-Steer 0
Start-Sleep -Seconds 1

Write-Host '▶ 이제 다시 출발(P→D) 한다'
Vc-Gear 'Drive'; Vc-Speed 40
Say-WhenUi '작곡 중' '연주는 끝나고 다시 작곡을 시작'
Start-Sleep -Seconds 4
