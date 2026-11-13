# 카 밴드 "첫 시동" 자동 연주 — Virtual Console 로 채보대로 브레이크·깜빡이·핸들을 조작한다 (시연 영상·동작 확인용)
# 카 밴드 앱을 "노래 따라 연주" 탭에 두고 실행:  pwsh -File tools\demo_carband.ps1 [-LeadMs -45]
# LeadMs = 미리 누를 시간 (음수 = 늦게). 앱은 시작 브레이크를 받은 뒤 반주를 틀기 때문에 그 지연만큼 늦춘다 (측정 -45ms 에서 평균 오차 0)
param([int]$LeadMs = -45)

$vc = 'http://127.0.0.1:8088'
# Invoke-WebRequest 는 첫 몇 번이 수백 ms 걸려 박자가 밀린다 → HttpClient 재사용 + 미리 예열
$http = [Net.Http.HttpClient]::new()
function Send($path, $query) { $null = $http.PostAsync("$vc$path`?$query", $null).Result }
function Alive { Send '/browser/session' 'client=carband-demo' }
1..5 | ForEach-Object { Alive }

# 앱 Song.kt 와 같은 채보 (100 BPM, 카운트인 1마디)
$beat = 600.0
function At($bar, $b) { (4 + ($bar - 1) * 4 + ($b - 1)) * $beat }
$notes = [Collections.Generic.List[object]]::new()
function N($pad, $bar, $b) { $notes.Add(@($pad, (At $bar $b))) }
foreach ($bar in 1..4) { N BRAKE $bar 1; N LEFT $bar 3 }
foreach ($bar in 5..8) { N BRAKE $bar 1; N LEFT $bar 2; N BRAKE $bar 3; N LEFT $bar 4 }
foreach ($bar in 9..12) { N BRAKE $bar 1; N LEFT $bar 2; N BRAKE $bar 3; N RIGHT $bar 4 }
N STEER 13 1
foreach ($bar in 13..15) { N BRAKE $bar 1; N LEFT $bar 2; N BRAKE $bar 2.5; N BRAKE $bar 3; N RIGHT $bar 4 }
N BRAKE 16 1; N STEER 17 1

# 누르기·떼기 동작을 시간순으로
$actions = [Collections.Generic.List[object]]::new()
foreach ($n in $notes) {
    $t = $n[1] - $LeadMs
    switch ($n[0]) {
        'BRAKE' { $actions.Add(@($t, '/vehicle/controls', 'brake=60')); $actions.Add(@(($t + 120), '/vehicle/controls', 'brake=0')) }
        'LEFT'  { $actions.Add(@($t, '/vehicle/turn-signal', 'mode=BlinkLeft')); $actions.Add(@(($t + 200), '/vehicle/turn-signal', 'mode=Off')) }
        'RIGHT' { $actions.Add(@($t, '/vehicle/turn-signal', 'mode=BlinkRight')); $actions.Add(@(($t + 200), '/vehicle/turn-signal', 'mode=Off')) }
        'STEER' { $actions.Add(@($t, '/vehicle/controls', 'steering=150')); $actions.Add(@(($t + 250), '/vehicle/controls', 'steering=0')) }
    }
}
$actions = $actions | Sort-Object { $_[0] }

Alive; Send '/vehicle/gear' 'gear=Park'; Send '/vehicle/controls' 'brake=0&steering=0'; Send '/vehicle/turn-signal' 'mode=Off'
Start-Sleep -Milliseconds 800

Write-Host '▶ 차로 「첫 시동」을 연주한다 (자동 시연)' -ForegroundColor Yellow
$clock = [Diagnostics.Stopwatch]::StartNew()
Send '/vehicle/controls' 'brake=60'; Start-Sleep -Milliseconds 120; Send '/vehicle/controls' 'brake=0'
$start = 0 # 앱은 브레이크를 받은 순간 반주를 시작 → 대략 지금이 노래 0ms
$lastAlive = 0
# 연주 도중에 넣을 설명 (영상 자막용) — ms, 문구
$says = @(@(14000, '브레이크 = 킥 · 깜빡이 = 스네어'), @(29000, '핸들 꺾기 = 심벌 · 판정은 오른쪽에'))
$saidTo = 0
foreach ($a in $actions) {
    while ($clock.Elapsed.TotalMilliseconds - $start -lt $a[0]) {
        if ($clock.Elapsed.TotalMilliseconds - $lastAlive -gt 1000) { Alive; $lastAlive = $clock.Elapsed.TotalMilliseconds }
        if ($saidTo -lt $says.Count -and ($clock.Elapsed.TotalMilliseconds - $start) -ge $says[$saidTo][0]) {
            Write-Host ("▶ " + $says[$saidTo][1]) -ForegroundColor Yellow
            $saidTo++
        }
        Start-Sleep -Milliseconds 1
    }
    Send $a[1] $a[2]
}
Write-Host '끝' -ForegroundColor Green
