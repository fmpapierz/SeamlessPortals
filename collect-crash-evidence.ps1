# Collects the evidence around the most recent IntelliJ termination into
# crash-evidence.txt (next to this script). Run via collect-crash-evidence.bat.
# NOTE: ASCII only in this file - PowerShell 5.1 reads BOM-less files as ANSI.
$out = Join-Path $PSScriptRoot "crash-evidence.txt"
$lines = @()
$lines += "=== collected $(Get-Date) ==="

# 1. The most recent idea64.exe termination (Security 4689), if audited.
$kill = $null
try {
    $kill = Get-WinEvent -FilterHashtable @{LogName='Security'; Id=4689; StartTime=(Get-Date).AddHours(-12)} -ErrorAction Stop |
        Where-Object { $_.Message -match 'idea64\.exe' } | Select-Object -First 1
} catch {}
if ($kill) {
    $lines += "=== idea64.exe termination event ==="
    $lines += "time: $($kill.TimeCreated)"
    $lines += $kill.Message
    $t = $kill.TimeCreated
} else {
    $lines += "(no idea64.exe termination event found in last 12h - using last 3h window)"
    $t = (Get-Date).AddMinutes(-90)
}

# 2. Every process CREATED (4688, with command line) within -3min..+1min of the kill.
$lines += ""
$lines += "=== processes started around that moment (4688) ==="
try {
    Get-WinEvent -FilterHashtable @{LogName='Security'; Id=4688; StartTime=$t.AddMinutes(-3); EndTime=$t.AddMinutes(1)} -ErrorAction Stop |
        ForEach-Object {
            $m = $_.Message
            $proc = if ($m -match 'New Process Name:\s*(.+)') { $Matches[1].Trim() } else { '?' }
            $cmd  = if ($m -match 'Process Command Line:\s*(.+)') { $Matches[1].Trim() } else { '' }
            $par  = if ($m -match 'Creator Process Name:\s*(.+)') { $Matches[1].Trim() } else { '' }
            $lines += "$($_.TimeCreated)  $proc"
            if ($par) { $lines += "    parent: $par" }
            if ($cmd) { $lines += "    cmd: $cmd" }
        }
} catch { $lines += "(4688 query failed: $($_.Exception.Message))" }

# 3. Application + System events in the window.
$lines += ""
$lines += "=== Application/System events in the window ==="
foreach ($log in 'Application','System') {
    try {
        Get-WinEvent -FilterHashtable @{LogName=$log; StartTime=$t.AddMinutes(-3); EndTime=$t.AddMinutes(2)} -ErrorAction Stop |
            ForEach-Object { $lines += "$($_.TimeCreated) [$log/$($_.Id)] $($_.ProviderName): $((($_.Message -split "`n")[0]))" }
    } catch {}
}

# 4. The IDE log cutoff for correlation.
$ideaLog = "$env:LOCALAPPDATA\JetBrains\IntelliJIdea2026.1\log\idea.log"
if (Test-Path $ideaLog) {
    $lines += ""
    $lines += "=== last 5 idea.log lines ==="
    $lines += (Get-Content $ideaLog -Tail 5 | ForEach-Object { $_.Substring(0, [Math]::Min(160, $_.Length)) })
}

$lines | Set-Content -Path $out -Encoding utf8
Write-Host "Wrote $out - tell Claude to read it."
