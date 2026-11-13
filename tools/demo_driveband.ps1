# 드라이브 밴드 전체 시연 — 1부 주행 중 드라이브 송(자동 작곡·저장), 2부 정차 중 카 밴드(노래 따라 연주·자유 연주)
# 앱을 켜 두고(드라이브 송 탭) 실행. 시연 전 차량 신호를 받는 다른 앱은 종료할 것 (에뮬레이터가 느려져 판정이 밀림)
#   pwsh -File tools\demo_driveband.ps1 [-Auto]
param([switch]$Auto)
. "$PSScriptRoot\vc.ps1"

Write-Host '── 1부: 드라이브 송 (주행 중) ──' -ForegroundColor Cyan
& "$PSScriptRoot\demo_drivesong.ps1" -Auto:$Auto
Adb-TapText '■'   # 다시 듣기 멈춤

Write-Host '── 2부: 카 밴드 (정차 중) ──' -ForegroundColor Cyan
Vc-Step '노래 따라 연주 → 브레이크·깜빡이·핸들로 "첫 시동" 자동 연주' -Auto:$Auto
Adb-TapText '노래 따라 연주'; Start-Sleep 3
& "$PSScriptRoot\demo_carband.ps1"
Start-Sleep 4

Vc-Step '자유 연주 (드럼) → 브레이크=킥, 깜빡이=스네어·하이햇, 핸들=심벌' -Auto:$Auto
Adb-TapText '자유 연주'; Start-Sleep 2
foreach ($i in 1..2) {
    Vc-Brake 60; Start-Sleep -Milliseconds 150; Vc-Brake 0; Start-Sleep -Milliseconds 350
    Vc-Turn 'BlinkLeft'; Start-Sleep -Milliseconds 200; Vc-Turn 'Off'; Start-Sleep -Milliseconds 300
    Vc-Brake 60; Start-Sleep -Milliseconds 150; Vc-Brake 0; Start-Sleep -Milliseconds 350
    Vc-Turn 'BlinkRight'; Start-Sleep -Milliseconds 200; Vc-Turn 'Off'; Start-Sleep -Milliseconds 300
}
Vc-Steer 150; Start-Sleep -Milliseconds 300; Vc-Steer 0

Vc-Step '출발하면 카 밴드는 꺼지고 다시 드라이브 송이 작곡' -Auto:$Auto
Vc-Gear 'Drive'; Vc-Speed 30; Start-Sleep 5
Vc-Speed 0; Vc-Gear 'Park'
Write-Host '끝' -ForegroundColor Green
