# 드라이브 송 시연 — 출발하면 자동 작곡: 속도=템포, 핸들=멜로디, 깜빡이=박자, 와이퍼=빗길 드럼, 브레이크=쉼표, 동승자=합주
# 도착(P)하면 곡으로 저장되고, 목록에서 다시 들을 수 있다.
#   pwsh -File tools\demo_drivesong.ps1 [-Auto]
param([switch]$Auto)
. "$PSScriptRoot\vc.ps1"

function Sweep([int]$to, [int]$step) {   # 핸들을 천천히 돌린다 → 멜로디가 따라 움직임
    $now = [int](Vc-State).controls.steering_deg
    $s = if ($to -ge $now) { $step } else { -$step }
    for ($a = $now; ($s -gt 0 -and $a -lt $to) -or ($s -lt 0 -and $a -gt $to); $a += $s) { Vc-Steer $a; Start-Sleep -Milliseconds 400 }
    Vc-Steer $to
}

Vc-Step '준비 — 주차(P), 운전자만 탑승' -Auto:$Auto
Vc-Gear 'Park'; Vc-Speed 0; Vc-Steer 0; Vc-Wiper 'Off'; Vc-Turn 'Off'
Vc-Seat 'driver' $true; Vc-Seat 'passenger' $false; Vc-Seat 'rear_left' $false; Vc-Seat 'rear_right' $false
Start-Sleep 1; Vc-CloseWindows

Write-Host '▶ 이제 출발(D) · 시내 30km/h' -ForegroundColor Yellow
Vc-Gear 'Drive'; Vc-Speed 30
Say-WhenUi '[0-9]+ BPM' '달리자마자 차가 작곡 — 느린 템포'
Start-Sleep 4

Vc-Step '핸들 오른쪽 → 멜로디가 올라간다' -Auto:$Auto
Sweep 150 30; Start-Sleep 3; Sweep -150 30; Start-Sleep 3; Sweep 0 50

Write-Host '▶ 이제 속도를 80km/h 로 올린다' -ForegroundColor Yellow
Vc-Speed 80
Say-WhenUi '1[0-9][0-9] BPM' '속도가 오르니 템포도 빨라진다'
Start-Sleep 5

Vc-Step '깜빡이 → 똑딱 박자와 꾸밈음' -Auto:$Auto
Vc-Turn 'BlinkRight'; Start-Sleep 5; Vc-Turn 'Off'

Vc-Step '와이퍼(비) → 드럼과 빗길 화음' -Auto:$Auto
Vc-Wiper 'Low'; Start-Sleep 8

Vc-Step '브레이크 → 멜로디가 쉰다' -Auto:$Auto
Vc-Brake 70; Vc-Speed 20; Start-Sleep 3; Vc-Brake 0; Start-Sleep 2

Write-Host '▶ 이제 도착(P) 한다' -ForegroundColor Yellow
Vc-Wiper 'Off'; Vc-Speed 0; Vc-Gear 'Park'
Say-WhenUi '곡을 저장했어요' '이번 주행이 한 곡으로 저장됐다'
Start-Sleep 4

Vc-Step '4명 탑승 → 사람 수만큼 악기가 는다' -Auto:$Auto
Vc-Seat 'passenger' $true; Vc-Seat 'rear_left' $true; Vc-Seat 'rear_right' $true; Start-Sleep 1; Vc-CloseWindows
Adb-TapText '▶ 미리 듣기'; Start-Sleep 2
Write-Host '▶ 지금 나오는 소리 — 4인 합주' -ForegroundColor Yellow
Start-Sleep 6; Adb-TapText '■ 멈추기'

Vc-Step '저장된 곡을 목록에서 다시 듣기' -Auto:$Auto
Adb-TapText '▶'; Start-Sleep 2
Write-Host '▶ 지금 나오는 소리 — 저장된 곡 재생' -ForegroundColor Yellow
Start-Sleep 6
Write-Host '끝' -ForegroundColor Green
