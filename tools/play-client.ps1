<#
.SYNOPSIS
    Starts a real (launcher-style) Fabric client with the release jar, Sodium, Iris + shaderpacks and Voxy, for a person
    to play and test in. A normal, visible, focused window: this is NOT the unattended test client.
.DESCRIPTION
    Reuses the launcher install tools\release-test.ps1 made under build\release-test\launcher (the vanilla jar from
    Mojang, the Fabric loader profile, its libraries) and the dependency jars it downloaded. The game folder is
    build\play-client: mods = the release jar from build\libs + Fabric API + Sodium + Iris + Voxy (all the released
    jars), shaderpacks = the packs kept in fabric\runs\client-sodium\shaderpacks, shaders ON with BSL by default.
    Run tools\release-test.ps1 -Loaders fabric once first if build\release-test\launcher does not exist yet.
.EXAMPLE
    tools\play-client.ps1                          # title screen; make a world or join a server
    tools\play-client.ps1 -Server 127.0.0.1:25572  # straight into the local test server
    tools\play-client.ps1 -NoShaders               # Iris installed, shaders off
#>
param(
    [string]$Username = 'warwa',
    [string]$Server = '',
    [string]$ShaderPack = 'BSL_v10.1.3.zip',
    [switch]$NoShaders,
    [switch]$NoVoxy,
    [string]$Memory = '6G',
    # The game folder (relative to the project root). Use another one while a previous play client is
    # still open: its mods folder cannot be replaced under a running game.
    [string]$GameDir = 'build\play-client'
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$work = Join-Path $root 'build\release-test'
$launcher = Join-Path $work 'launcher'
$downloads = Join-Path $work 'downloads'
$gameDir = Join-Path $root $GameDir
$javaHome = 'C:\Program Files\Zulu\zulu-25'
$java = Join-Path $javaHome 'bin\java.exe'
function Say([string]$Text) { Write-Host "[play-client] $Text" }

function Read-Properties([string]$Path) {
    $properties = @{}
    foreach ($line in Get-Content -LiteralPath $Path) {
        if ($line -match '^\s*([^#=\s][^=]*?)\s*=\s*(.*)$') { $properties[$Matches[1]] = $Matches[2].Trim() }
    }
    return $properties
}
function Read-Json([string]$Path) { return Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json }
function Get-OfflineUuid([string]$Name) {
    $md5 = [Security.Cryptography.MD5]::Create().ComputeHash([Text.Encoding]::UTF8.GetBytes("OfflinePlayer:$Name"))
    $md5[6] = ($md5[6] -band 0x0f) -bor 0x30
    $md5[8] = ($md5[8] -band 0x3f) -bor 0x80
    $hex = ($md5 | ForEach-Object { $_.ToString('x2') }) -join ''
    return ($hex.Substring(0, 8), $hex.Substring(8, 4), $hex.Substring(12, 4), $hex.Substring(16, 4), $hex.Substring(20, 12)) -join '-'
}
function Test-Rules($Rules) {
    if (-not $Rules) { return $true }
    $allowed = $false
    foreach ($rule in $Rules) {
        $applies = $true
        if ($rule.features) { $applies = $false }
        if ($rule.os -and $rule.os.name -and $rule.os.name -ne 'windows') { $applies = $false }
        if ($rule.os -and $rule.os.arch -and $rule.os.arch -ne 'x86_64') { $applies = $false }
        if ($applies) { $allowed = ($rule.action -eq 'allow') }
    }
    return $allowed
}
function Expand-Arguments($List) {
    $result = @()
    foreach ($entry in $List) {
        if ($entry -is [string]) { $result += $entry } elseif (Test-Rules $entry.rules) { $result += @($entry.value) }
    }
    return $result
}
function Get-MavenPath([string]$Name) {
    $extension = 'jar'
    if ($Name -match '^(.*)@(.+)$') { $Name = $Matches[1]; $extension = $Matches[2] }
    $parts = $Name.Split(':')
    $file = "$($parts[1])-$($parts[2])"
    if ($parts.Count -gt 3) { $file += "-$($parts[3])" }
    return ($parts[0].Replace('.', '/') + "/$($parts[1])/$($parts[2])/$file.$extension")
}
function Get-LibraryKey([string]$Name) {
    $parts = ($Name -replace '@.*$', '').Split(':')
    $key = "$($parts[0]):$($parts[1])"
    if ($parts.Count -gt 3) { $key += ":$($parts[3])" }
    return $key
}
function Resolve-Version([string]$Id) {
    $json = Read-Json (Join-Path $launcher "versions\$Id\$Id.json")
    $version = @{ Id = $Id; MainClass = $json.mainClass; Libraries = @($json.libraries | Where-Object { $_ }); Jvm = @($json.arguments.jvm | Where-Object { $_ }); Game = @($json.arguments.game | Where-Object { $_ }) }
    if ($json.inheritsFrom) {
        $parent = Resolve-Version $json.inheritsFrom
        $own = @{}
        foreach ($library in $version.Libraries) { $own[(Get-LibraryKey $library.name)] = $true }
        $version.Libraries += @($parent.Libraries | Where-Object { -not $own.ContainsKey((Get-LibraryKey $_.name)) })
        $version.Jvm = @($parent.Jvm) + $version.Jvm
        $version.Game = @($parent.Game) + $version.Game
        if (-not $version.MainClass) { $version.MainClass = $parent.MainClass }
    }
    return $version
}
function Get-Library($Library) {
    $artifact = $null
    if ($Library.downloads -and $Library.downloads.artifact) { $artifact = $Library.downloads.artifact }
    $relative = if ($artifact -and $artifact.path) { $artifact.path } else { Get-MavenPath $Library.name }
    $file = Join-Path $launcher ('libraries\' + $relative.Replace('/', '\'))
    if (-not (Test-Path -LiteralPath $file)) { throw "Library $($Library.name) is missing from $launcher; run tools\release-test.ps1 -Loaders fabric once" }
    return $file
}

# ---- what to run ----
$properties = Read-Properties (Join-Path $root 'gradle.properties')
$minecraft = $properties.minecraft_version
$versionId = "fabric-loader-$($properties.fabric_loader_version)-$minecraft"
if (-not (Test-Path -LiteralPath (Join-Path $launcher "versions\$versionId\$versionId.json"))) {
    throw "No launcher install at $launcher; run tools\release-test.ps1 -Loaders fabric once first"
}
$launchConfig = Join-Path $root '.gradle\loom-cache\projects\fabric\launch.cfg'
$configLines = @(Get-Content -LiteralPath $launchConfig | ForEach-Object { $_.Trim() })
$assetIndex = $configLines[[array]::IndexOf($configLines, '--assetIndex') + 1]
$assetsDir = $configLines[[array]::IndexOf($configLines, '--assetsDir') + 1]

$releaseJar = Join-Path $root "build\libs\$($properties.mod_id)-fabric-$($properties.mod_version).jar"
$irisJar = Get-ChildItem -LiteralPath (Join-Path $env:USERPROFILE '.gradle\caches\modules-2\files-2.1\maven.modrinth\iris') -Recurse -Filter "iris-$($properties.iris_version).jar" | Select-Object -First 1
if (-not $irisJar) { throw "Iris $($properties.iris_version) is not in the Gradle cache; run a build once" }
$mods = @(
    $releaseJar,
    (Join-Path $downloads "fabric-api-$($properties.fabric_version).jar"),
    (Join-Path $downloads "sodium-$($properties.sodium_version).jar"),
    $irisJar.FullName
)
if (-not $NoVoxy) { $mods += (Join-Path $downloads "voxy-$($properties.voxy_version).jar") }
foreach ($mod in $mods) { if (-not (Test-Path -LiteralPath $mod)) { throw "Missing $mod" } }

# ---- the game folder ----
New-Item -ItemType Directory -Force $gameDir, (Join-Path $gameDir 'mods'), (Join-Path $gameDir 'config'), (Join-Path $gameDir 'shaderpacks') | Out-Null
Get-ChildItem -LiteralPath (Join-Path $gameDir 'mods') -Filter *.jar | Remove-Item
foreach ($mod in $mods) { Copy-Item -LiteralPath $mod -Destination (Join-Path $gameDir 'mods') }
foreach ($pack in Get-ChildItem -LiteralPath (Join-Path $root 'fabric\runs\client-sodium\shaderpacks') -Filter *.zip) {
    if (-not (Test-Path -LiteralPath (Join-Path $gameDir "shaderpacks\$($pack.Name)"))) { Copy-Item -LiteralPath $pack.FullName -Destination (Join-Path $gameDir 'shaderpacks') }
}
$enable = if ($NoShaders) { 'false' } else { 'true' }
[IO.File]::WriteAllText((Join-Path $gameDir 'config\iris.properties'), "enableShaders=$enable`nshaderPack=$ShaderPack`ndisableUpdateMessage=true`n")
if (-not (Test-Path -LiteralPath (Join-Path $gameDir 'config\immersive_portals.json'))) {
    [IO.File]::WriteAllText((Join-Path $gameDir 'config\immersive_portals.json'), "{`n  `"initialScreenShown`": true`n}`n")
}
if (-not (Test-Path -LiteralPath (Join-Path $gameDir 'options.txt'))) {
    [IO.File]::WriteAllText((Join-Path $gameDir 'options.txt'), "onboardAccessibility:false`nnarrator:0`nskipMultiplayerWarning:true`ntutorialStep:none`njoinedFirstServer:true`nrenderDistance:12`nsimulationDistance:8`n")
}

# ---- the launch arguments, the way the launcher builds them ----
$version = Resolve-Version $versionId
$classpath = @()
foreach ($library in $version.Libraries) { if (Test-Rules $library.rules) { $classpath += Get-Library $library } }
$jar = Join-Path $launcher "versions\$versionId\$versionId.jar"
if (-not (Test-Path -LiteralPath $jar) -or (Get-Item -LiteralPath $jar).Length -eq 0) {
    Copy-Item -LiteralPath (Join-Path $launcher "versions\$minecraft\$minecraft.jar") -Destination $jar -Force
}
$classpath += $jar
$natives = Join-Path $gameDir 'natives'
New-Item -ItemType Directory -Force $natives | Out-Null
$values = @{
    'natives_directory' = $natives; 'launcher_name' = 'seamlessportals-play'; 'launcher_version' = '1'
    'classpath' = ($classpath -join ';'); 'classpath_separator' = ';'; 'library_directory' = (Join-Path $launcher 'libraries')
    'version_name' = $versionId; 'auth_player_name' = $Username; 'game_directory' = $gameDir
    'assets_root' = $assetsDir; 'assets_index_name' = $assetIndex
    'auth_uuid' = (Get-OfflineUuid $Username).Replace('-', ''); 'auth_access_token' = '0'; 'clientid' = '0'; 'auth_xuid' = '0'
    'version_type' = 'release'; 'user_type' = 'legacy'
}
$gameArgs = @()
if ($Server) { $gameArgs += @('--quickPlayMultiplayer', $Server) }
$arguments = @("-Xmx$Memory") + (Expand-Arguments $version.Jvm) + @($version.MainClass) + (Expand-Arguments $version.Game) + $gameArgs
$lines = foreach ($argument in $arguments) {
    $text = [regex]::Replace([string]$argument, '\$\{(\w+)\}', { param($match); $name = $match.Groups[1].Value; if ($values.ContainsKey($name)) { $values[$name] } else { $match.Value } })
    '"' + $text.Replace('\', '\\').Replace('"', '\"') + '"'
}
$argumentFile = Join-Path $gameDir 'launch-arguments.txt'
[IO.File]::WriteAllLines($argumentFile, [string[]]$lines, (New-Object Text.UTF8Encoding $false))

Say "mods: " + (($mods | ForEach-Object { Split-Path -Leaf $_ }) -join ', ')
Say "shaders: $(if ($NoShaders) { 'off' } else { "on ($ShaderPack)" }); packs in $gameDir\shaderpacks"
Say "player: $Username (offline)$(if ($Server) { "; joining $Server" })"
$process = Start-Process -FilePath $java -ArgumentList "@`"$argumentFile`"" -WorkingDirectory $gameDir -PassThru
Say "started java pid $($process.Id); log: $gameDir\logs\latest.log"
