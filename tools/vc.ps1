# Virtual Console(127.0.0.1:8088) 조작 함수 모음 — 시연 스크립트에서 점(.)으로 불러 쓴다:  . "$PSScriptRoot\vc.ps1"
# 주의: 브라우저 세션 신호가 3초 안에 없으면 값을 안 받는다(emulator_inactive) → Send 마다 Alive
#       Invoke-WebRequest 는 첫 몇 번이 수백 ms 걸려 HttpClient 를 재사용한다

$script:VcBase = 'http://127.0.0.1:8088'
$script:VcHttp = [Net.Http.HttpClient]::new()

function Vc-Post([string]$path, [string]$query) {
    $null = $script:VcHttp.PostAsync("$script:VcBase/browser/session?client=demo", $null).Result
    $r = $script:VcHttp.PostAsync("$script:VcBase$path`?$query", $null).Result
    if (-not $r.IsSuccessStatusCode) { Write-Warning "$path?$query → $([int]$r.StatusCode) $($r.Content.ReadAsStringAsync().Result)" }
}
function Vc-State { ($script:VcHttp.GetStringAsync("$script:VcBase/vehicle/state").Result) | ConvertFrom-Json }

# 좌석: driver | passenger | rear_left | rear_right
function Vc-Seat($area, [bool]$on) { Vc-Post '/vehicle/seat' "kind=occupied&area=$area&value=$($on.ToString().ToLower())" }
function Vc-Recline($area, [int]$pct) { Vc-Post '/vehicle/seat' "kind=position&control=recline&area=$area&value=$pct" }
function Vc-Gear($g) { Vc-Post '/vehicle/gear' "gear=$g" }                     # Park | Reverse | Neutral | Drive
function Vc-Brake([int]$pct) { Vc-Post '/vehicle/controls' "brake=$pct" }
function Vc-Steer([int]$deg) { Vc-Post '/vehicle/controls' "steering=$deg" }
function Vc-Speed([int]$kph) { Vc-Post '/vehicle/controls' "speed=$kph" }
function Vc-Turn($mode) { Vc-Post '/vehicle/turn-signal' "mode=$mode" }      # Off | BlinkLeft | BlinkRight
function Vc-Cruise($state) { Vc-Post '/vehicle/driving-mode' "ad_mode_state=$state" } # ready = 대기 | full_autonomous = 작동(ACTIVATED). longitudinal·lateral 은 앱에 안 들어옴
function Vc-Wiper($mode) { Vc-Post '/vehicle/body' "front_wiper=$mode" }      # Off | Low | High | Auto
# 문: front_left | front_right | rear_left | rear_right → door_fl ...
function Vc-Door($area, [bool]$open) {
    $f = @{ front_left = 'door_fl'; front_right = 'door_fr'; rear_left = 'door_rl'; rear_right = 'door_rr' }[$area]
    Vc-Post '/vehicle/body' "$f=$($open.ToString().ToLower())"
}
function Vc-Trunk([bool]$open) { Vc-Post '/vehicle/body' "trunk=$($open.ToString().ToLower())" }
function Vc-Lock([bool]$locked) { Vc-Post '/vehicle/body' "doors_locked=$($locked.ToString().ToLower())" }
function Vc-Window($area, [int]$pct) {
    $f = @{ front_left = 'window_fl'; front_right = 'window_fr'; rear_left = 'window_rl'; rear_right = 'window_rr' }[$area]
    Vc-Post '/vehicle/body' "$f=$pct"
}
# 주의: Virtual Console 버그 — recirc=true 나 좌석(/vehicle/seat) 명령을 보내면 창문 4개가 100(완전 열림)이 된다 → 그 뒤 Vc-CloseWindows
function Vc-CloseWindows { foreach ($w in 'front_left', 'front_right', 'rear_left', 'rear_right') { Vc-Window $w 0 } }
function Vc-Recirc([bool]$on) { Vc-Post '/vehicle/hvac' "recirc=$($on.ToString().ToLower())" }
function Vc-Fan([int]$level) { Vc-Post '/vehicle/hvac' "fan_speed=$level" }
function Vc-Battery([double]$kwh) { Vc-Post '/vehicle/ev-battery' ("field=battery_level&value={0:0.0}" -f $kwh) }
# 타이어 area: Axle1Left | Axle1Right | Axle2Left | Axle2Right
function Vc-Tire($area, [int]$kpa) { Vc-Post '/vehicle/tire-pressure' "area=$area&pressure_kpa=$kpa" }
# 주행거리는 줄일 수 없다 → 지금 값 + km
function Vc-OdoAdd([double]$km) {
    $now = (Vc-State).odometer.traveled_distance_km
    Vc-Post '/vehicle/odometer' "traveled_distance_km=$($now + $km)"
}

function Vc-Step($msg, [switch]$Auto, [double]$Delay = 4) {
    Write-Host "`n▶ $msg" -ForegroundColor Yellow
    if ($Auto) { Start-Sleep -Seconds $Delay } else { Read-Host '   Enter' | Out-Null }
}

# 에뮬레이터 화면 캡처 (검수용)
function Adb-Shot($path) {
    & "$env:ANDROID_HOME\platform-tools\adb.exe" exec-out screencap -p > $path
}

# 화면에서 글자로 버튼을 찾아 누른다 (uiautomator 덤프, 좌표가 바뀌어도 동작)
function Adb-TapText([string]$text) {
    $adb = "$env:ANDROID_HOME\platform-tools\adb.exe"
    & $adb shell uiautomator dump /sdcard/ui.xml | Out-Null
    $enc = [Console]::OutputEncoding
    [Console]::OutputEncoding = [Text.Encoding]::UTF8     # adb 출력은 UTF-8 (기본 콘솔 CP949 로 읽으면 한글·기호가 깨져 못 찾음)
    try { $xml = (& $adb shell cat /sdcard/ui.xml) -join '' } finally { [Console]::OutputEncoding = $enc }
    $m = [regex]::Match($xml, 'text="' + [regex]::Escape($text) + '"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
    if (-not $m.Success) { Write-Warning "화면에서 '$text' 를 못 찾음"; return }
    $x = ([int]$m.Groups[1].Value + [int]$m.Groups[3].Value) / 2; $y = ([int]$m.Groups[2].Value + [int]$m.Groups[4].Value) / 2
    & $adb shell input tap $x $y
}


# ── 영상 자막용: 화면이 실제로 바뀐 뒤에 설명을 찍는다 ────────────────────────
function Vc-UiText {
    $adb = "$env:ANDROID_HOME\platform-tools\adb.exe"
    & $adb shell uiautomator dump /sdcard/ui.xml 2>$null | Out-Null
    $enc = [Console]::OutputEncoding
    [Console]::OutputEncoding = [Text.Encoding]::UTF8
    try { (& $adb shell cat /sdcard/ui.xml) -join '' } finally { [Console]::OutputEncoding = $enc }
}

# 결과를 단정하는 자막은 그 상태가 화면에 보일 때부터 나와야 한다.
#   Say-WhenUi '정산' '도착(P) → 정산 화면'          : 글자가 나타나면
#   Say-WhenUi -Gone '카드 준비 중' '카드 완성'       : 글자가 사라지면
function Say-WhenUi {
    param([Parameter(Mandatory)][string]$Pattern, [Parameter(Mandatory)][string]$Text,
          [switch]$Gone, [double]$TimeoutSec = 15)
    $t0 = Get-Date
    while (((Get-Date) - $t0).TotalSeconds -lt $TimeoutSec) {
        $hit = (Vc-UiText) -match $Pattern
        if ($Gone) { if (-not $hit) { break } } elseif ($hit) { break }
        Start-Sleep -Milliseconds 300
    }
    Write-Host "▶ $Text" -ForegroundColor Yellow
}
