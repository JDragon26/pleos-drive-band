# 차 조작 → 앱 도착 반응 속도 측정 (카 밴드 앱이 떠 있어야 함)
# Virtual Console 로 브레이크/깜빡이를 누르는 순간부터 앱의 "HIT" 로그가 PC 에 돌아올 때까지 시간을 잰다.
# 결과 = HTTP → 차량 시스템 → Pleos SDK → 앱 → logcat 전달 (logcat 몫만큼 실제보다 약간 크게 나옴 = 상한)
#   pwsh -File tools\measure_latency.ps1 [-Count 15]
param([int]$Count = 15)

$vc = 'http://127.0.0.1:8088'
$adb = "$env:ANDROID_HOME\platform-tools\adb.exe"

function Alive { Invoke-WebRequest -Method Post -UseBasicParsing "$vc/browser/session?client=latency" | Out-Null }
function Send($path, $query) { Invoke-WebRequest -Method Post -UseBasicParsing "$vc$path`?$query" | Out-Null }

# logcat 을 계속 읽는다. 줄이 들어온 시각은 이벤트의 TimeGenerated (읽기 스레드에서 찍힘)
& $adb logcat -c
$proc = [Diagnostics.Process]::new()
$proc.StartInfo = [Diagnostics.ProcessStartInfo]::new($adb, 'logcat -v raw -s CarBand:I')
$proc.StartInfo.RedirectStandardOutput = $true
$proc.StartInfo.UseShellExecute = $false
Register-ObjectEvent $proc OutputDataReceived -SourceIdentifier hit | Out-Null
$proc.Start() | Out-Null
$proc.BeginOutputReadLine()

function Probe($pad, [scriptblock]$press, [scriptblock]$release) {
    $out = @()
    foreach ($i in 1..$Count) {
        Alive
        Get-Event -SourceIdentifier hit -ErrorAction SilentlyContinue | Remove-Event
        $t0 = [DateTime]::Now
        & $press
        $got = $null
        while ((([DateTime]::Now) - $t0).TotalMilliseconds -lt 2000 -and -not $got) {
            $e = Wait-Event -SourceIdentifier hit -Timeout 1
            if ($e) {
                if ($e.SourceEventArgs.Data -match "HIT $pad") { $got = ($e.TimeGenerated - $t0).TotalMilliseconds }
                Remove-Event -EventIdentifier $e.EventIdentifier
            }
        }
        & $release
        if ($got) { $out += $got }
        Start-Sleep -Milliseconds 400
    }
    $s = @($out | Sort-Object)
    if ($s.Count -eq 0) { "{0,-6} 응답 없음" -f $pad; return }
    "{0,-6} {1}회  중앙값 {2:0}ms  최소 {3:0}ms  최대 {4:0}ms" -f $pad, $s.Count, $s[[int]($s.Count / 2)], $s[0], $s[-1]
}

Alive; Send '/vehicle/gear' 'gear=Park'
Start-Sleep -Milliseconds 500
Probe 'BRAKE' { Send '/vehicle/controls' 'brake=60' } { Alive; Send '/vehicle/controls' 'brake=0' }
Probe 'LEFT' { Send '/vehicle/turn-signal' 'mode=BlinkLeft' } { Alive; Send '/vehicle/turn-signal' 'mode=Off' }

Unregister-Event -SourceIdentifier hit
$proc.Kill()
