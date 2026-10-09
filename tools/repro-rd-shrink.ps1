<#
.SYNOPSIS
    Reproduces the "Overflowed the mesh time buffer" crash: a real Fabric client (release jar + Sodium + Voxy, Iris
    optional) opens a world in singleplayer, raises the render distance to 32, waits, drops it to 2, waits, and
    reports whether the game survived. Run it in the three variants to attribute the crash.
.EXAMPLE
    tools\repro-rd-shrink.ps1 -World "C:\...\build\play-client\saves\New World" -Variant tier2
    tools\repro-rd-shrink.ps1 -World ... -Variant tier1      # -Dseamlessportals.disableVoxyPortalLods=true
    tools\repro-rd-shrink.ps1 -World ... -Variant novoxy     # Voxy jar left out
    tools\repro-rd-shrink.ps1 -World ... -Variant tier2 -Shaders   # with the BSL pack on
#>
param(
    [Parameter(Mandatory = $true)][string]$World,
    [ValidateSet('tier2', 'tier1', 'novoxy')][string]$Variant = 'tier2',
    [switch]$Shaders,
    [int]$HoldSeconds = 90
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$launcher = Join-Path $root 'build\release-test\launcher'
$downloads = Join-Path $root 'build\release-test\downloads'
$agentJar = Join-Path $root 'tools\testclient\build\testclient-agent.jar'
$gameDir = Join-Path $root "build\repro-rd-shrink\$Variant$(if ($Shaders) { '-shaders' })"
$javaHome = 'C:\Program Files\Zulu\zulu-25'
$java = Join-Path $javaHome 'bin\java.exe'
function Say([string]$Text) { Write-Host "[repro] $Text" }

# ---- the same launcher-profile machinery as tools\play-client.ps1 ----
function Read-Properties([string]$Path) { $p = @{}; foreach ($l in Get-Content -LiteralPath $Path) { if ($l -match '^\s*([^#=\s][^=]*?)\s*=\s*(.*)$') { $p[$Matches[1]] = $Matches[2].Trim() } }; return $p }
function Read-Json([string]$Path) { return Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json }
function Get-OfflineUuid([string]$Name) { $m = [Security.Cryptography.MD5]::Create().ComputeHash([Text.Encoding]::UTF8.GetBytes("OfflinePlayer:$Name")); $m[6] = ($m[6] -band 0x0f) -bor 0x30; $m[8] = ($m[8] -band 0x3f) -bor 0x80; $h = ($m | ForEach-Object { $_.ToString('x2') }) -join ''; return ($h.Substring(0, 8), $h.Substring(8, 4), $h.Substring(12, 4), $h.Substring(16, 4), $h.Substring(20, 12)) -join '-' }
function Test-Rules($Rules) { if (-not $Rules) { return $true }; $a = $false; foreach ($r in $Rules) { $ok = $true; if ($r.features) { $ok = $false }; if ($r.os -and $r.os.name -and $r.os.name -ne 'windows') { $ok = $false }; if ($r.os -and $r.os.arch -and $r.os.arch -ne 'x86_64') { $ok = $false }; if ($ok) { $a = ($r.action -eq 'allow') } }; return $a }
function Expand-Arguments($List) { $r = @(); foreach ($e in $List) { if ($e -is [string]) { $r += $e } elseif (Test-Rules $e.rules) { $r += @($e.value) } }; return $r }
function Get-MavenPath([string]$Name) { $ext = 'jar'; if ($Name -match '^(.*)@(.+)$') { $Name = $Matches[1]; $ext = $Matches[2] }; $p = $Name.Split(':'); $f = "$($p[1])-$($p[2])"; if ($p.Count -gt 3) { $f += "-$($p[3])" }; return ($p[0].Replace('.', '/') + "/$($p[1])/$($p[2])/$f.$ext") }
function Get-LibraryKey([string]$Name) { $p = ($Name -replace '@.*$', '').Split(':'); $k = "$($p[0]):$($p[1])"; if ($p.Count -gt 3) { $k += ":$($p[3])" }; return $k }
function Resolve-Version([string]$Id) {
    $json = Read-Json (Join-Path $launcher "versions\$Id\$Id.json")
    $v = @{ Id = $Id; MainClass = $json.mainClass; Libraries = @($json.libraries | Where-Object { $_ }); Jvm = @($json.arguments.jvm | Where-Object { $_ }); Game = @($json.arguments.game | Where-Object { $_ }) }
    if ($json.inheritsFrom) { $parent = Resolve-Version $json.inheritsFrom; $own = @{}; foreach ($l in $v.Libraries) { $own[(Get-LibraryKey $l.name)] = $true }; $v.Libraries += @($parent.Libraries | Where-Object { -not $own.ContainsKey((Get-LibraryKey $_.name)) }); $v.Jvm = @($parent.Jvm) + $v.Jvm; $v.Game = @($parent.Game) + $v.Game; if (-not $v.MainClass) { $v.MainClass = $parent.MainClass } }
    return $v
}
function Get-Library($Library) { $a = $null; if ($Library.downloads -and $Library.downloads.artifact) { $a = $Library.downloads.artifact }; $rel = if ($a -and $a.path) { $a.path } else { Get-MavenPath $Library.name }; $f = Join-Path $launcher ('libraries\' + $rel.Replace('/', '\')); if (-not (Test-Path -LiteralPath $f)) { throw "missing library $f" }; return $f }
function Send-ClientCommands([string]$Dir, [string[]]$Commands) { $inbox = Join-Path $Dir 'testclient\inbox'; New-Item -ItemType Directory -Force $inbox | Out-Null; $n = 'cmd-' + (Get-Date -Format 'HHmmssfff') + '.txt'; [IO.File]::WriteAllLines((Join-Path $inbox "$n.tmp"), [string[]]$Commands, (New-Object Text.UTF8Encoding $false)); Move-Item -LiteralPath (Join-Path $inbox "$n.tmp") -Destination (Join-Path $inbox $n) }
function Wait-ForLog([string]$Path, [string]$Pattern, [int]$Seconds, $Process) { $d = (Get-Date).AddSeconds($Seconds); while ((Get-Date) -lt $d) { if (Select-String -LiteralPath $Path -Pattern $Pattern -Quiet -ErrorAction SilentlyContinue) { return $true }; if ($Process.HasExited) { return $false }; Start-Sleep -Seconds 2 }; return $false }

$properties = Read-Properties (Join-Path $root 'gradle.properties')
$minecraft = $properties.minecraft_version
$versionId = "fabric-loader-$($properties.fabric_loader_version)-$minecraft"
$launchConfig = Join-Path $root '.gradle\loom-cache\projects\fabric\launch.cfg'
$configLines = @(Get-Content -LiteralPath $launchConfig | ForEach-Object { $_.Trim() })
$assetIndex = $configLines[[array]::IndexOf($configLines, '--assetIndex') + 1]
$assetsDir = $configLines[[array]::IndexOf($configLines, '--assetsDir') + 1]

# ---- game folder: mods, the world copy, options ----
Remove-Item -LiteralPath $gameDir -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $gameDir, (Join-Path $gameDir 'mods'), (Join-Path $gameDir 'config'), (Join-Path $gameDir 'saves'), (Join-Path $gameDir 'shaderpacks') | Out-Null
$mods = @((Join-Path $root "build\libs\$($properties.mod_id)-fabric-$($properties.mod_version).jar"), (Join-Path $downloads "fabric-api-$($properties.fabric_version).jar"), (Join-Path $downloads "sodium-$($properties.sodium_version).jar"))
$irisJar = Get-ChildItem -LiteralPath (Join-Path $env:USERPROFILE '.gradle\caches\modules-2\files-2.1\maven.modrinth\iris') -Recurse -Filter "iris-$($properties.iris_version).jar" | Select-Object -First 1
if ($Shaders) { $mods += $irisJar.FullName }
if ($Variant -ne 'novoxy') { $mods += (Join-Path $downloads "voxy-$($properties.voxy_version).jar") }
foreach ($m in $mods) { Copy-Item -LiteralPath $m -Destination (Join-Path $gameDir 'mods') }
Copy-Item -LiteralPath $World -Destination (Join-Path $gameDir 'saves\repro') -Recurse
Remove-Item -LiteralPath (Join-Path $gameDir 'saves\repro\session.lock') -ErrorAction SilentlyContinue
if ($Shaders) {
    Copy-Item -LiteralPath (Join-Path $root 'fabric\runs\client-sodium\shaderpacks\BSL_v10.1.3.zip') -Destination (Join-Path $gameDir 'shaderpacks')
    [IO.File]::WriteAllText((Join-Path $gameDir 'config\iris.properties'), "enableShaders=true`nshaderPack=BSL_v10.1.3.zip`ndisableUpdateMessage=true`n")
}
[IO.File]::WriteAllText((Join-Path $gameDir 'config\immersive_portals.json'), "{`n  `"initialScreenShown`": true`n}`n")
[IO.File]::WriteAllText((Join-Path $gameDir 'options.txt'), "onboardAccessibility:false`nnarrator:0`nskipMultiplayerWarning:true`npauseOnLostFocus:false`ntutorialStep:none`nrenderDistance:12`nsimulationDistance:8`nmaxFps:60`n")

# ---- launch ----
$version = Resolve-Version $versionId
$classpath = @(); foreach ($l in $version.Libraries) { if (Test-Rules $l.rules) { $classpath += Get-Library $l } }
$jar = Join-Path $launcher "versions\$versionId\$versionId.jar"
if (-not (Test-Path -LiteralPath $jar) -or (Get-Item -LiteralPath $jar).Length -eq 0) { Copy-Item -LiteralPath (Join-Path $launcher "versions\$minecraft\$minecraft.jar") -Destination $jar -Force }
$classpath += $jar
$natives = Join-Path $gameDir 'natives'; New-Item -ItemType Directory -Force $natives | Out-Null
$player = 'ReproTester'
$values = @{ 'natives_directory' = $natives; 'launcher_name' = 'seamlessportals-repro'; 'launcher_version' = '1'; 'classpath' = ($classpath -join ';'); 'classpath_separator' = ';'; 'library_directory' = (Join-Path $launcher 'libraries'); 'version_name' = $versionId; 'auth_player_name' = $player; 'game_directory' = $gameDir; 'assets_root' = $assetsDir; 'assets_index_name' = $assetIndex; 'auth_uuid' = (Get-OfflineUuid $player).Replace('-', ''); 'auth_access_token' = '0'; 'clientid' = '0'; 'auth_xuid' = '0'; 'version_type' = 'release'; 'user_type' = 'legacy' }
$jvm = @('-Xmx6G', "-javaagent:$agentJar", "-Dseamlessportals.releaseTest=$gameDir")
if ($Variant -eq 'tier1') { $jvm += '-Dseamlessportals.disableVoxyPortalLods=true' }
$arguments = $jvm + (Expand-Arguments $version.Jvm) + @($version.MainClass) + (Expand-Arguments $version.Game) + @('--quickPlaySingleplayer', 'repro')
$lines = foreach ($a in $arguments) { $t = [regex]::Replace([string]$a, '\$\{(\w+)\}', { param($m); $n = $m.Groups[1].Value; if ($values.ContainsKey($n)) { $values[$n] } else { $m.Value } }); '"' + $t.Replace('\', '\\').Replace('"', '\"') + '"' }
$argumentFile = Join-Path $gameDir 'launch-arguments.txt'
[IO.File]::WriteAllLines($argumentFile, [string[]]$lines, (New-Object Text.UTF8Encoding $false))
$stdout = Join-Path $gameDir 'stdout.log'
$process = Start-Process -FilePath $java -ArgumentList "@`"$argumentFile`"" -WorkingDirectory $gameDir -RedirectStandardOutput $stdout -RedirectStandardError (Join-Path $gameDir 'stderr.log') -NoNewWindow -PassThru
$null = $process.Handle
Say "variant $Variant$(if ($Shaders) { ' + shaders' }): mods $(($mods | ForEach-Object { Split-Path -Leaf $_ }) -join ', ')"
$latest = Join-Path $gameDir 'logs\latest.log'
if (-not (Wait-ForLog $stdout "$player joined the game" 300 $process)) { throw "the world never opened; see $stdout" }
Say 'world open at render distance 12; waiting 25 s'
Start-Sleep -Seconds 25
Send-ClientCommands $gameDir @('option renderDistance 32', 'state')
Say 'render distance 32; waiting 40 s for chunks'
Start-Sleep -Seconds 40
Send-ClientCommands $gameDir @('option renderDistance 2', 'state')
Say "render distance 2; holding $HoldSeconds s"
$deadline = (Get-Date).AddSeconds($HoldSeconds)
while ((Get-Date) -lt $deadline -and -not $process.HasExited) { Start-Sleep -Seconds 3 }
$overflow = @(Select-String -LiteralPath $stdout -Pattern 'Overflowed the mesh time buffer' -ErrorAction SilentlyContinue).Count
$crash = Get-ChildItem -LiteralPath (Join-Path $gameDir 'crash-reports') -ErrorAction SilentlyContinue | Select-Object -First 1
$verdict = if ($process.HasExited) { "EXITED (code $($process.ExitCode))" } else { 'ALIVE' }
Say "result: $verdict; 'Overflowed the mesh time buffer' lines: $overflow; crash report: $(if ($crash) { $crash.Name } else { 'none' })"
if (-not $process.HasExited) { Send-ClientCommands $gameDir @('quit'); $process.WaitForExit(60000) | Out-Null; if (-not $process.HasExited) { Stop-Process -Id $process.Id -Force } }
"$Variant$(if ($Shaders) { '+shaders' }): $verdict, overflow=$overflow, crash=$(if ($crash) { 'yes' } else { 'no' })"
