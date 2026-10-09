<#
.SYNOPSIS
    Tests the release jars from build\libs in real installs: a dedicated server, a client joining it, and singleplayer.
.DESCRIPTION
    For each loader this installs a real dedicated server and a real client profile the way a player would (the loader's
    own installer or launcher profile; the vanilla jar from Mojang), puts the release jar from build\libs into the mods
    folder with the RELEASED dependency jars (Fabric API, and on Fabric also Sodium + Voxy from Modrinth, so the Voxy
    compat runs the way players get it), and runs two checks:
      1. server + client   the dedicated server starts, the client joins it over a real socket, a portal pair
                           (overworld <-> nether, 3x3, bi-way) is created with Immersive Portals' own command, the client
                           looks through it, walks through it into the nether and back, screenshots along the way
      2. singleplayer      the client opens the server's world in singleplayer and does the same look + crossing
    Verdicts come from the logs: the join, the crossing cutover lines the mod writes on every dimension change, the
    Voxy compat lines on Fabric (present, installed, the LOD system moved at each crossing), and NO line that points
    at a broken jar (missing classes or members, mixin failures, crash reports).
    Nothing of the development environment is involved: the client starts from the launcher profile the same way the
    Minecraft launcher starts it (offline, access token 0), the server the way the loader's own start script does.
    The clients carry a Java agent (tools\testclient, never part of the mod) that opens the game window minimized and
    unfocused and lets this script run commands, walk the player, screenshot and quit without keyboard or mouse input.
    Everything this downloads or writes stays under build\release-test. Downloads come from Mojang, the loaders' own
    sites and Modrinth. The servers accept the Minecraft EULA (https://aka.ms/MinecraftEULA), as the project owner has
    done for all test servers, and listen on 127.0.0.1 only.
.EXAMPLE
    tools\release-test.ps1
    tools\release-test.ps1 -Loaders fabric -SkipBuild
#>
param(
    [ValidateSet('fabric', 'neoforge')]
    [string[]]$Loaders = @('fabric', 'neoforge'),
    [switch]$SkipBuild,
    [int]$Port = 25572,
    [string[]]$ClientJvmArgs = @(),
    [int]$ClientTimeoutSeconds = 600,
    [int]$ServerStartTimeoutSeconds = 600,
    # The NeoForge version to install for the client and the server (both must match). The project builds against
    # neo_version from gradle.properties; the release range is [26.2,), so the newest 26.2 line is what players run.
    [string]$NeoForgeVersion = '26.2.0.88',
    [string]$FabricApiVersion = '',
    [string]$SodiumVersion = '',
    [string]$VoxyVersion = '',
    # Where this run's server and client folders go (relative to the project root). The launcher install and
    # the downloads always stay in build\release-test, so a second work folder reuses them — use one when the
    # default folder's server or client is still in use (tools\play-client.ps1 plays on them).
    [string]$Work = 'build\release-test'
)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$root = Split-Path -Parent $PSScriptRoot
$work = Join-Path $root $Work
$launcher = Join-Path $root 'build\release-test\launcher'
$downloads = Join-Path $root 'build\release-test\downloads'
$playerName = 'SelfTester'
$javaHome = 'C:\Program Files\Zulu\zulu-25'
$java = Join-Path $javaHome 'bin\java.exe'
function Say([string]$Text) {
    Write-Host "[release-test] $Text"
}

# ---------------------------------------------------------------------------------------------------------------
# Small tools
# ---------------------------------------------------------------------------------------------------------------

function Read-Properties([string]$Path) {
    $properties = @{}
    foreach ($line in Get-Content -LiteralPath $Path) {
        if ($line -match '^\s*([^#=\s][^=]*?)\s*=\s*(.*)$') {
            $properties[$Matches[1]] = $Matches[2].Trim()
        }
    }
    return $properties
}
function Read-Json([string]$Path) {
    return Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json
}
function Get-Text([string]$Url) {
    $response = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 120
    if ($response.Content -is [byte[]]) {
        return [Text.Encoding]::UTF8.GetString($response.Content)
    }
    return [string]$response.Content
}

# Downloads a file unless it is already there. $Sha1 is the checksum to hold it to; '?' fetches the .sha1 file the
# maven repository publishes next to the artifact.
function Get-File([string]$Url, [string]$Path, [string]$Sha1) {
    if (Test-Path -LiteralPath $Path) {
        return
    }
    New-Item -ItemType Directory -Force (Split-Path -Parent $Path) | Out-Null
    $partial = "$Path.part"
    Invoke-WebRequest -Uri $Url -OutFile $partial -UseBasicParsing -TimeoutSec 900
    if ($Sha1 -eq '?') {
        $Sha1 = (Get-Text "$Url.sha1").Trim().Split(' ')[0]
    }
    if ($Sha1) {
        $actual = (Get-FileHash -LiteralPath $partial -Algorithm SHA1).Hash
        if ($actual -ne $Sha1) {
            Remove-Item -LiteralPath $partial
            throw "Checksum mismatch for $Url (expected $Sha1, got $actual)"
        }
    }
    Move-Item -LiteralPath $partial -Destination $Path
}

function Join-Arguments([string[]]$Arguments) {
    $quoted = foreach ($argument in $Arguments) {
        if ($argument -match '[\s"]' -or $argument -eq '') { '"' + $argument.Replace('"', '\"') + '"' } else { $argument }
    }
    return $quoted -join ' '
}

# Starts a program with its output going to "$LogBase.log" (and stderr to "$LogBase.err.log").
function Start-Logged([string]$File, [string[]]$Arguments, [string]$WorkDir, [string]$LogBase) {
    Remove-Item -LiteralPath "$LogBase.log", "$LogBase.err.log" -ErrorAction SilentlyContinue
    $process = Start-Process -FilePath $File -ArgumentList (Join-Arguments $Arguments) -WorkingDirectory $WorkDir `
        -RedirectStandardOutput "$LogBase.log" -RedirectStandardError "$LogBase.err.log" -NoNewWindow -PassThru
    $null = $process.Handle   # without this, PowerShell cannot report the exit code later
    return $process
}

function Invoke-Logged([string]$File, [string[]]$Arguments, [string]$WorkDir, [string]$LogBase, [int]$TimeoutSeconds = 1800) {
    $process = Start-Logged $File $Arguments $WorkDir $LogBase
    if (-not $process.WaitForExit($TimeoutSeconds * 1000)) {
        Stop-Own $process
        throw "$File did not finish within $TimeoutSeconds s; see $LogBase.log"
    }
    return $process.ExitCode
}

# Ends a process this script started, and the java processes it started, but only processes whose command line
# shows they run out of this test's folder (machine rule: never kill a process not positively identified).
function Stop-Own($Process) {
    if ($null -eq $Process) {
        return
    }
    $all = @(Get-CimInstance Win32_Process -ErrorAction SilentlyContinue)
    $parents = @($Process.Id)
    $descendants = @()
    while ($parents.Count -gt 0) {
        $children = @($all | Where-Object { $parents -contains $_.ParentProcessId })
        $descendants += $children
        $parents = @($children | ForEach-Object { $_.ProcessId })
    }
    foreach ($child in $descendants) {
        if ($child.CommandLine -and $child.CommandLine.Contains($work)) {
            Stop-Process -Id $child.ProcessId -Force -ErrorAction SilentlyContinue
        }
    }
    if ($Process.HasExited) {
        return
    }
    $info = Get-CimInstance Win32_Process -Filter "ProcessId=$($Process.Id)"
    if ($info -and $info.CommandLine -and $info.CommandLine.Contains($work)) {
        Stop-Process -Id $Process.Id -Force
        $Process.WaitForExit(15000) | Out-Null
    } else {
        Say "Process $($Process.Id) does not look like one of this test's; leaving it alone."
    }
}

# What Java's UUID.nameUUIDFromBytes gives for "OfflinePlayer:<name>": the id a server in offline mode gives a player.
function Get-OfflineUuid([string]$Name) {
    $md5 = [Security.Cryptography.MD5]::Create().ComputeHash([Text.Encoding]::UTF8.GetBytes("OfflinePlayer:$Name"))
    $md5[6] = ($md5[6] -band 0x0f) -bor 0x30
    $md5[8] = ($md5[8] -band 0x3f) -bor 0x80
    $hex = ($md5 | ForEach-Object { $_.ToString('x2') }) -join ''
    return ($hex.Substring(0, 8), $hex.Substring(8, 4), $hex.Substring(12, 4), $hex.Substring(16, 4), $hex.Substring(20, 12)) -join '-'
}

# Waits until a line matching $Pattern shows up in one of $Paths. Gives up early when $Process has exited.
function Wait-ForLog([string[]]$Paths, [string]$Pattern, [int]$TimeoutSeconds, $Process) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        foreach ($path in $Paths) {
            if (Select-String -LiteralPath $path -Pattern $Pattern -Quiet -ErrorAction SilentlyContinue) {
                return $true
            }
        }
        if ($Process -and $Process.HasExited) {
            return $false
        }
        Start-Sleep -Seconds 2
    }
    return $false
}

# Waits until $Pattern has at least $Count matches across $Paths.
function Wait-ForCount([string[]]$Paths, [string]$Pattern, [int]$Count, [int]$TimeoutSeconds, $Process) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        if ((Count-Lines $Paths $Pattern) -ge $Count) {
            return $true
        }
        if ($Process -and $Process.HasExited) {
            return $false
        }
        Start-Sleep -Seconds 2
    }
    return (Count-Lines $Paths $Pattern) -ge $Count
}

function Count-Lines([string[]]$Paths, [string]$Pattern) {
    $count = 0
    foreach ($path in $Paths) {
        $count += @(Select-String -LiteralPath $path -Pattern $Pattern -ErrorAction SilentlyContinue).Count
    }
    return $count
}

# ---------------------------------------------------------------------------------------------------------------
# The test client's Java agent
# ---------------------------------------------------------------------------------------------------------------

function Build-Agent {
    $out = Join-Path $work 'agent'
    $jar = Join-Path $out 'testclient-agent.jar'
    $sources = @(Get-ChildItem -LiteralPath (Join-Path $root 'tools\testclient\src\testclient') -Filter *.java)
    $newest = ($sources | Measure-Object -Property LastWriteTime -Maximum).Maximum
    if ((Test-Path -LiteralPath $jar) -and (Get-Item -LiteralPath $jar).LastWriteTime -gt $newest) {
        return $jar
    }
    Remove-Item -LiteralPath $out -Recurse -Force -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force (Join-Path $out 'classes') | Out-Null
    $code = Invoke-Logged (Join-Path $javaHome 'bin\javac.exe') (@('--release', '25', '-d', (Join-Path $out 'classes')) + ($sources | ForEach-Object { $_.FullName })) $out (Join-Path $out 'javac')
    if ($code -ne 0) { throw "The test client agent did not compile; see $out\javac.err.log" }
    [IO.File]::WriteAllText((Join-Path $out 'MANIFEST.MF'), "Premain-Class: testclient.MinimizedWindowAgent`n")
    $code = Invoke-Logged (Join-Path $javaHome 'bin\jar.exe') @('--create', '--file', $jar, '--manifest', (Join-Path $out 'MANIFEST.MF'), '-C', (Join-Path $out 'classes'), '.') $out (Join-Path $out 'jar')
    if ($code -ne 0) { throw "The test client agent could not be packaged; see $out\jar.err.log" }
    return $jar
}

# ---------------------------------------------------------------------------------------------------------------
# Starting a client from a launcher profile, the way the Minecraft launcher does
# ---------------------------------------------------------------------------------------------------------------

function Test-Rules($Rules) {
    if (-not $Rules) {
        return $true
    }
    $allowed = $false
    foreach ($rule in $Rules) {
        $applies = $true
        if ($rule.features) { $applies = $false }   # demo mode, custom resolution, the launcher's quick play: none in use
        if ($rule.os -and $rule.os.name -and $rule.os.name -ne 'windows') { $applies = $false }
        if ($rule.os -and $rule.os.arch -and $rule.os.arch -ne 'x86_64') { $applies = $false }
        if ($applies) { $allowed = ($rule.action -eq 'allow') }
    }
    return $allowed
}

function Expand-Arguments($List) {
    $result = @()
    foreach ($entry in $List) {
        if ($entry -is [string]) {
            $result += $entry
        } elseif (Test-Rules $entry.rules) {
            $result += @($entry.value)
        }
    }
    return $result
}

# group:artifact:version[:classifier][@extension] -> its path in a maven repository
function Get-MavenPath([string]$Name) {
    $extension = 'jar'
    if ($Name -match '^(.*)@(.+)$') {
        $Name = $Matches[1]
        $extension = $Matches[2]
    }
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

# A version json together with everything it inherits, merged the way the launcher merges them.
function Resolve-Version([string]$Id) {
    $json = Read-Json (Join-Path $launcher "versions\$Id\$Id.json")
    $version = @{
        Id        = $Id
        MainClass = $json.mainClass
        Libraries = @($json.libraries | Where-Object { $_ })
        Jvm       = @($json.arguments.jvm | Where-Object { $_ })
        Game      = @($json.arguments.game | Where-Object { $_ })
    }
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
    if (-not (Test-Path -LiteralPath $file)) {
        if ($artifact -and $artifact.url) {
            Get-File $artifact.url $file $artifact.sha1
        } elseif ($Library.url) {
            $sha1 = if ($Library.sha1) { $Library.sha1 } else { '?' }
            Get-File ($Library.url.TrimEnd('/') + '/' + $relative) $file $sha1
        } else {
            throw "Library $($Library.name) is neither installed nor downloadable"
        }
    }
    return $file
}

# Writes the java @argument file for a client of the given launcher profile and returns its path.
function New-ClientArguments([string]$VersionId, [string]$GameDir, [string[]]$ExtraJvmArgs, [string[]]$ExtraGameArgs) {
    $version = Resolve-Version $VersionId
    $classpath = @()
    foreach ($library in $version.Libraries) {
        if (Test-Rules $library.rules) { $classpath += Get-Library $library }
    }

    # Like the launcher, give the profile a client jar of its own name: a copy of the vanilla one it inherits.
    $jar = Join-Path $launcher "versions\$VersionId\$VersionId.jar"
    if (-not (Test-Path -LiteralPath $jar) -or (Get-Item -LiteralPath $jar).Length -eq 0) {
        Copy-Item -LiteralPath (Join-Path $launcher "versions\$minecraft\$minecraft.jar") -Destination $jar -Force
    }
    $classpath += $jar

    $natives = Join-Path $GameDir 'natives'
    New-Item -ItemType Directory -Force $natives | Out-Null
    $values = @{
        'natives_directory'   = $natives
        'launcher_name'       = 'seamlessportals-release-test'
        'launcher_version'    = '1'
        'classpath'           = ($classpath -join ';')
        'classpath_separator' = ';'
        'library_directory'   = (Join-Path $launcher 'libraries')
        'version_name'        = $VersionId
        'auth_player_name'    = $playerName
        'game_directory'      = $GameDir
        'assets_root'         = $assetsDir
        'assets_index_name'   = $assetIndex
        'auth_uuid'           = (Get-OfflineUuid $playerName).Replace('-', '')
        'auth_access_token'   = '0'
        'clientid'            = '0'
        'auth_xuid'           = '0'
        'version_type'        = 'release'
        'user_type'           = 'legacy'
    }

    $arguments = @('-Xmx4G') + $ExtraJvmArgs + (Expand-Arguments $version.Jvm) + @($version.MainClass) + (Expand-Arguments $version.Game) + $ExtraGameArgs
    $lines = foreach ($argument in $arguments) {
        $text = [regex]::Replace([string]$argument, '\$\{(\w+)\}', {
            param($match)
            $name = $match.Groups[1].Value
            if ($values.ContainsKey($name)) { $values[$name] } else { $match.Value }
        })
        # In a java @argument file a quoted string takes backslash as an escape character.
        '"' + $text.Replace('\', '\\').Replace('"', '\"') + '"'
    }

    $file = Join-Path $GameDir 'launch-arguments.txt'
    # No byte order mark: java would read it as part of the first argument.
    [IO.File]::WriteAllLines($file, [string[]]$lines, (New-Object Text.UTF8Encoding $false))
    return $file
}

# ---------------------------------------------------------------------------------------------------------------
# Installing: what a player would download and run for each loader
# ---------------------------------------------------------------------------------------------------------------

function Install-Vanilla {
    $json = Join-Path $launcher "versions\$minecraft\$minecraft.json"
    if (-not (Test-Path -LiteralPath $json)) {
        $manifest = Get-Text 'https://piston-meta.mojang.com/mc/game/version_manifest_v2.json' | ConvertFrom-Json
        $entry = $manifest.versions | Where-Object { $_.id -eq $minecraft }
        if (-not $entry) { throw "Mojang's version manifest does not list Minecraft $minecraft" }
        Get-File $entry.url $json $entry.sha1
    }
    $client = (Read-Json $json).downloads.client
    Get-File $client.url (Join-Path $launcher "versions\$minecraft\$minecraft.jar") $client.sha1

    $profiles = Join-Path $launcher 'launcher_profiles.json'
    if (-not (Test-Path -LiteralPath $profiles)) {
        # The NeoForge installer only installs into a folder the launcher has been run in.
        [IO.File]::WriteAllText($profiles, '{"profiles":{},"settings":{},"version":3}')
    }
}

# The launcher profile for a loader; returns the id of the version to start.
function Install-Client([string]$Loader) {
    switch ($Loader) {
        'fabric' {
            $id = "fabric-loader-$($properties.fabric_loader_version)-$minecraft"
            Get-File "https://meta.fabricmc.net/v2/versions/loader/$minecraft/$($properties.fabric_loader_version)/profile/json" (Join-Path $launcher "versions\$id\$id.json") $null
            return $id
        }
        'neoforge' {
            $id = "neoforge-$NeoForgeVersion"
            if (-not (Test-Path -LiteralPath (Join-Path $launcher "versions\$id\$id.json"))) {
                $installer = Join-Path $downloads "neoforge-$NeoForgeVersion-installer.jar"
                Get-File "https://maven.neoforged.net/releases/net/neoforged/neoforge/$NeoForgeVersion/neoforge-$NeoForgeVersion-installer.jar" $installer '?'
                Say 'Installing the NeoForge client (its installer patches the game; this takes a few minutes the first time)...'
                $code = Invoke-Logged $java @('-jar', $installer, '--install-client', $launcher) $downloads (Join-Path $work 'neoforge\install-client')
                if ($code -ne 0) { throw "The NeoForge client installer failed with code $code; see $work\neoforge\install-client.log" }
            }
            return $id
        }
    }
}

# The dedicated server for a loader; returns the java arguments that start it from its folder.
function Install-Server([string]$Loader, [string]$Dir) {
    New-Item -ItemType Directory -Force $Dir | Out-Null
    switch ($Loader) {
        'fabric' {
            $jar = Join-Path $Dir 'fabric-server-launch.jar'
            if (-not (Test-Path -LiteralPath $jar)) {
                $installerVersion = ((Get-Text 'https://meta.fabricmc.net/v2/versions/installer' | ConvertFrom-Json) | Where-Object { $_.stable } | Select-Object -First 1).version
                # The official server launcher: it fetches the vanilla server and the loader's libraries on its first start.
                Get-File "https://meta.fabricmc.net/v2/versions/loader/$minecraft/$($properties.fabric_loader_version)/$installerVersion/server/jar" $jar $null
            }
            return @('-jar', 'fabric-server-launch.jar', 'nogui')
        }
        'neoforge' {
            $arguments = "libraries/net/neoforged/neoforge/$NeoForgeVersion/win_args.txt"
            if (-not (Test-Path -LiteralPath (Join-Path $Dir $arguments))) {
                $installer = Join-Path $downloads "neoforge-$NeoForgeVersion-installer.jar"
                Get-File "https://maven.neoforged.net/releases/net/neoforged/neoforge/$NeoForgeVersion/neoforge-$NeoForgeVersion-installer.jar" $installer '?'
                Say 'Installing the NeoForge server (this takes a few minutes the first time)...'
                $code = Invoke-Logged $java @('-jar', $installer, '--install-server', $Dir) $downloads (Join-Path $work 'neoforge\install-server')
                if ($code -ne 0) { throw "The NeoForge server installer failed with code $code; see $work\neoforge\install-server.log" }
            }
            # What the run.bat the installer leaves does.
            return @('@user_jvm_args.txt', "@$arguments", 'nogui')
        }
    }
}

# ---------------------------------------------------------------------------------------------------------------
# Preparing and running the test installs
# ---------------------------------------------------------------------------------------------------------------

# The mods folder of a test install: the release jar (which bundles Cloth Config), Fabric API on Fabric, and on a Fabric
# CLIENT the released Sodium + Voxy (the server never loads them: Voxy is a client renderer, and a server does not
# need Sodium).
function Set-Mods([string]$Loader, [string]$Dir, [bool]$IsClient) {
    $mods = Join-Path $Dir 'mods'
    New-Item -ItemType Directory -Force $mods | Out-Null
    Get-ChildItem -LiteralPath $mods -Filter *.jar | Remove-Item
    if ($Loader -eq 'fabric') {
        Copy-Item -LiteralPath $fabricApi -Destination $mods
        if ($IsClient) {
            Copy-Item -LiteralPath $sodiumJar -Destination $mods
            Copy-Item -LiteralPath $voxyJar -Destination $mods
        }
    }
    Copy-Item -LiteralPath $releaseJars[$Loader] -Destination $mods
}

function Set-ClientDir([string]$Loader, [string]$Dir) {
    New-Item -ItemType Directory -Force $Dir | Out-Null
    # An unattended run: nobody is there to click through the first-start screens, the narrator stays off, and the
    # window is never in front, where the game would otherwise pause itself.
    [IO.File]::WriteAllText((Join-Path $Dir 'options.txt'), (@(
        'onboardAccessibility:false',
        'narrator:0',
        'skipMultiplayerWarning:true',
        'pauseOnLostFocus:false',
        'tutorialStep:none',
        'joinedFirstServer:true',
        'renderDistance:8',
        'simulationDistance:5',
        'maxFps:60'
    ) -join "`n") + "`n")
    $config = Join-Path $Dir 'config'
    New-Item -ItemType Directory -Force $config | Out-Null
    # Immersive Portals' first-launch information screen ("I Know") would stall an unattended client.
    [IO.File]::WriteAllText((Join-Path $config 'immersive_portals.json'), "{`n  `"initialScreenShown`": true`n}`n")
    if ($Loader -eq 'neoforge') {
        # No early loading window: it would open in front, outside the agent's reach.
        $fml = Join-Path $config 'fml.toml'
        $text = if (Test-Path -LiteralPath $fml) { [IO.File]::ReadAllText($fml) } else { '' }
        if ($text -match '(?m)^\s*earlyWindowControl\s*=') {
            $text = [regex]::Replace($text, '(?m)^(\s*earlyWindowControl\s*=\s*).*$', '${1}false')
        } else {
            $text = "earlyWindowControl = false`n" + $text
        }
        [IO.File]::WriteAllText($fml, $text)
    }
    Set-Mods $Loader $Dir $true
    foreach ($leftover in @('logs', 'crash-reports', 'screenshots', 'testclient')) {
        Remove-Item -LiteralPath (Join-Path $Dir $leftover) -Recurse -Force -ErrorAction SilentlyContinue
    }
}

function Set-ServerDir([string]$Loader, [string]$Dir) {
    Set-Mods $Loader $Dir $false
    foreach ($leftover in @('world', 'logs', 'crash-reports', 'stop.flag')) {
        Remove-Item -LiteralPath (Join-Path $Dir $leftover) -Recurse -Force -ErrorAction SilentlyContinue
    }
    # The project owner accepts the Minecraft EULA for every test server.
    [IO.File]::WriteAllText((Join-Path $Dir 'eula.txt'), "# Accepted for the project owner by tools/release-test.ps1 (https://aka.ms/MinecraftEULA)`neula=true`n")
    [IO.File]::WriteAllText((Join-Path $Dir 'server.properties'), (@(
        'server-ip=127.0.0.1',
        "server-port=$Port",
        'online-mode=false',
        'enforce-secure-profile=false',
        'white-list=false',
        'enable-code-of-conduct=false',
        'level-type=minecraft\:flat',
        'generator-settings={"biome":"minecraft:plains","layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},{"block":"minecraft:grass_block","height":1}]}',
        'generate-structures=false',
        'spawn-protection=0',
        'difficulty=peaceful',
        'gamemode=creative',
        'function-permission-level=2',
        'max-players=2',
        'view-distance=8',
        'simulation-distance=5',
        'pause-when-empty-seconds=0',
        'motd=Seamless Portals release test (local only)'
    ) -join "`n") + "`n")
    [IO.File]::WriteAllText((Join-Path $Dir 'ops.json'),
        "[{`"uuid`":`"$(Get-OfflineUuid $playerName)`",`"name`":`"$playerName`",`"level`":4,`"bypassesPlayerLimit`":false}]")
}

# Starts a dedicated server and returns its process once it accepts players. It reads its console from a helper that
# waits for stop.flag, so Stop-TestServer can stop it cleanly (piping "stop" from .NET adds a byte order mark).
function Start-TestServer([string]$Dir, [string[]]$Arguments, [string]$LogBase) {
    [IO.File]::WriteAllText((Join-Path $Dir 'wait-for-stop.bat'), (@(
        '@echo off',
        ':loop',
        'if exist "%~dp0stop.flag" goto stop',
        'ping -n 3 127.0.0.1 >nul',
        'goto loop',
        ':stop',
        'del "%~dp0stop.flag"',
        'echo stop'
    ) -join "`r`n") + "`r`n")
    # The -D property does nothing in the game; it puts this folder on the java command line, which is how Stop-Own
    # recognizes the process as one of this test's.
    $command = (@("`"$java`"", '-Xmx3G', "`"-Dseamlessportals.releaseTest=$Dir`"") + $Arguments) -join ' '
    [IO.File]::WriteAllText((Join-Path $Dir 'serve.bat'), (@(
        '@echo off',
        'cd /d "%~dp0"',
        "cmd /c `"%~dp0wait-for-stop.bat`" | $command"
    ) -join "`r`n") + "`r`n")

    $server = Start-Logged 'cmd.exe' @('/c', (Join-Path $Dir 'serve.bat')) $Dir $LogBase
    if (-not (Wait-ForLog @("$LogBase.log") 'Done \(' $ServerStartTimeoutSeconds $server)) {
        $why = if ($server.HasExited) { "exited with code $($server.ExitCode) before it was ready" } else { "was not ready within $ServerStartTimeoutSeconds s" }
        Stop-Own $server
        throw "The server $why; see $LogBase.log"
    }
    return $server
}

function Stop-TestServer($Server, [string]$Dir) {
    if ($Server.HasExited) {
        return 'the server had already exited'
    }
    [IO.File]::WriteAllText((Join-Path $Dir 'stop.flag'), 'stop')
    if ($Server.WaitForExit(120000)) {
        return 'stopped cleanly'
    }
    Stop-Own $Server
    return 'did not stop on "stop" and was ended'
}

function Start-TestClient([string]$VersionId, [string]$Dir, [string[]]$GameArgs, [string]$LogBase) {
    $jvm = @("-javaagent:$agentJar", "-Dseamlessportals.releaseTest=$Dir") + $ClientJvmArgs
    $argumentFile = New-ClientArguments $VersionId $Dir $jvm $GameArgs
    return Start-Logged $java @("@$argumentFile") $Dir $LogBase
}

# Hands the client's remote control (the test agent) a list of commands.
function Send-ClientCommands([string]$Dir, [string[]]$Commands) {
    $inbox = Join-Path $Dir 'testclient\inbox'
    New-Item -ItemType Directory -Force $inbox | Out-Null
    $name = 'cmd-' + (Get-Date -Format 'HHmmssfff') + '.txt'
    [IO.File]::WriteAllLines((Join-Path $inbox "$name.tmp"), [string[]]$Commands, (New-Object Text.UTF8Encoding $false))
    Move-Item -LiteralPath (Join-Path $inbox "$name.tmp") -Destination (Join-Path $inbox $name)
}

# Asks the client to quit and waits for it; ends it if it does not.
function Stop-TestClient($Client, [string]$Dir) {
    if ($Client.HasExited) {
        return "the client had already exited (code $($Client.ExitCode))"
    }
    Send-ClientCommands $Dir @('quit')
    if ($Client.WaitForExit(120000)) {
        return "quit cleanly (code $($Client.ExitCode))"
    }
    Stop-Own $Client
    return 'did not quit and was ended'
}

Add-Type -Namespace ReleaseTest -Name Win32 -MemberDefinition @'
[DllImport("user32.dll")] public static extern bool IsIconic(IntPtr hWnd);
[DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
[DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr hWnd);
[DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint pid);
public delegate bool EnumProc(IntPtr hWnd, IntPtr lParam);
[DllImport("user32.dll")] public static extern bool EnumWindows(EnumProc proc, IntPtr lParam);
[DllImport("user32.dll")] public static extern int GetWindowText(IntPtr hWnd, System.Text.StringBuilder text, int count);
'@

# How the client's game window sits: the test agent should have it minimized and never in front.
function Get-WindowState($Client) {
    $found = @()
    $callback = [ReleaseTest.Win32+EnumProc] {
        param($hWnd, $lParam)
        $windowPid = 0
        [void][ReleaseTest.Win32]::GetWindowThreadProcessId($hWnd, [ref]$windowPid)
        if ($windowPid -eq $Client.Id -and [ReleaseTest.Win32]::IsWindowVisible($hWnd)) {
            $text = New-Object Text.StringBuilder 256
            [void][ReleaseTest.Win32]::GetWindowText($hWnd, $text, 256)
            if ($text.ToString() -like 'Minecraft*') {
                $script:found += "minimized=$([ReleaseTest.Win32]::IsIconic($hWnd)), in front=$([ReleaseTest.Win32]::GetForegroundWindow() -eq $hWnd)"
            }
        }
        return $true
    }
    $script:found = @()
    [void][ReleaseTest.Win32]::EnumWindows($callback, [IntPtr]::Zero)
    if ($script:found.Count -eq 0) { return 'no window found' }
    return ($script:found -join '; ')
}

# Lines that point at a broken release jar, from the logs given.
$problemPattern = 'NoClassDefFoundError|ClassNotFoundException|NoSuchMethodError|NoSuchFieldError|IllegalAccessError|AbstractMethodError|IncompatibleClassChangeError|LinkageError|ExceptionInInitializerError|InvalidInjectionException|InjectionError|MixinApplyError|MixinTransformerError|Mixin apply.* failed|refmap|has failed to load correctly|Failed to load|Missing or unsupported mandatory dependencies|Crash report saved|---- Minecraft Crash Report|Cannot have multiple renderers'
# Netty's kqueue and epoll natives only exist on macOS and Linux; on Windows log4j's debug appender trips over them.
# Voxy's optional-mod mixins (Flashback, Nvidium, Chunky, Iris when absent) log their missing targets as warnings.
$noisePattern = 'io\.netty\.channel\.(kqueue|epoll)|Only supported on (OSX/BSD|Linux)|@Mixin target .* was not found|Error loading class: (com/moulberry|me/cortex/nvidium|org/popcraft|net/irisshaders)|Failed to load (Realms|texture|sound)'
function Find-Problems([string[]]$Paths) {
    $found = @()
    foreach ($path in $Paths) {
        foreach ($match in @(Select-String -LiteralPath $path -Pattern $problemPattern -ErrorAction SilentlyContinue | Where-Object { $_.Line -notmatch $noisePattern })) {
            $found += "$(Split-Path -Leaf $path):$($match.LineNumber): $($match.Line.Trim())"
        }
    }
    return $found
}

# Turns cheats on in a world's level.dat (gzipped NBT): the byte tag "allowCommands" is type 1, a 2-byte name length,
# the name, then the value, so the value sits right after the name. No NBT library is needed for that one byte.
function Enable-Cheats([string]$LevelDat) {
    $inStream = New-Object IO.FileStream($LevelDat, [IO.FileMode]::Open)
    $gzip = New-Object IO.Compression.GZipStream($inStream, [IO.Compression.CompressionMode]::Decompress)
    $buffer = New-Object IO.MemoryStream
    $gzip.CopyTo($buffer)
    $gzip.Dispose(); $inStream.Dispose()
    $bytes = $buffer.ToArray()
    $name = [Text.Encoding]::ASCII.GetBytes('allowCommands')
    $patched = $false
    for ($i = 0; $i -le $bytes.Length - $name.Length - 1; $i++) {
        $match = $true
        for ($j = 0; $j -lt $name.Length; $j++) { if ($bytes[$i + $j] -ne $name[$j]) { $match = $false; break } }
        if ($match -and $i -ge 3 -and $bytes[$i - 3] -eq 1 -and $bytes[$i - 2] -eq 0 -and $bytes[$i - 1] -eq $name.Length) {
            $bytes[$i + $name.Length] = 1
            $patched = $true
            break
        }
    }
    if (-not $patched) { throw "allowCommands not found in $LevelDat" }
    $output = New-Object IO.FileStream($LevelDat, [IO.FileMode]::Create)
    $gzipOut = New-Object IO.Compression.GZipStream($output, [IO.Compression.CompressionMode]::Compress)
    $gzipOut.Write($bytes, 0, $bytes.Length)
    $gzipOut.Dispose(); $output.Dispose()
}

# Keeps a run's screenshots next to its logs (a fresh folder each run: Copy-Item -Recurse into an existing folder
# would nest the source folder inside it and leave the previous run's files in place).
function Save-Screenshots([string]$ClientDir, [string]$Target) {
    Remove-Item -LiteralPath $Target -Recurse -Force -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force $Target | Out-Null
    Get-ChildItem -LiteralPath (Join-Path $ClientDir 'screenshots') -Filter *.png -ErrorAction SilentlyContinue | Copy-Item -Destination $Target -Force
}

# Every WARN/ERROR line, for reading through (written to a file per run, not judged here).
function Save-Warnings([string[]]$Paths, [string]$Target) {
    $lines = @()
    foreach ($path in $Paths) {
        foreach ($match in @(Select-String -LiteralPath $path -Pattern '/(WARN|ERROR|FATAL)\]|\[(WARN|ERROR|FATAL)\]|Exception|Caused by' -ErrorAction SilentlyContinue)) {
            $lines += "$(Split-Path -Leaf $path):$($match.LineNumber): $($match.Line.Trim())"
        }
    }
    [IO.File]::WriteAllLines($Target, [string[]]$lines)
    return $lines.Count
}

# ---------------------------------------------------------------------------------------------------------------
# The portal legs (chat commands as the opped player, through the agent; the same on a server and in singleplayer)
# ---------------------------------------------------------------------------------------------------------------

# The mod logs this on the client at every seamless dimension change (ClientWorldLoader's promote/demote cutover).
$crossingPattern = '\[S14 crossing cutover\] extract driver promoted'
$toNetherPattern = 'extract driver promoted minecraft:overworld -> minecraft:the_nether'
$toOverworldPattern = 'extract driver promoted minecraft:the_nether -> minecraft:overworld'

# Stages the portal pair around the player's spawn: a stone pad, a 3x3 bi-way pair overworld <-> nether (above the
# nether roof), anchored on two armor stands so the orientation is exact and no aiming is needed.
function Set-PortalStage([string]$Dir) {
    Send-ClientCommands $Dir @(
        'cmd gamemode creative',
        'cmd gamerule doDaylightCycle false',
        'cmd gamerule doWeatherCycle false',
        'cmd gamerule doMobSpawning false',
        'cmd time set 1000',
        'cmd weather clear',
        'cmd tp @s 0.5 -60 0.5 180 0',
        'cmd fill -12 -61 -12 12 -61 8 minecraft:stone',
        'cmd fill -12 -60 -12 12 -52 8 minecraft:air',
        'cmd execute in minecraft:the_nether run forceload add -16 -16 16 16',
        'cmd execute in minecraft:the_nether run fill -8 128 -8 8 134 8 minecraft:air',
        'cmd execute in minecraft:the_nether run fill -8 127 -8 8 127 8 minecraft:netherrack',
        'cmd kill @e[type=armor_stand,tag=sp_anchor]',
        # Overworld anchor. cb_make_portal takes the anchor's view vector as the portal normal, but the portal is entered
        # from the side OPPOSITE to that vector (observed 2026-10-09: an anchor facing south made a portal entered from
        # the north). So this anchor faces NORTH (yaw 180): the player standing south of it walks north into it.
        'cmd summon armor_stand 0.5 -58.5 -4.5 {Tags:["sp_anchor","sp_ow"],NoGravity:1b,Invisible:1b,Rotation:[180f,0f]}',
        # Nether anchor at the destination faces SOUTH (yaw 0): the return portal is entered walking south.
        'cmd execute in minecraft:the_nether run summon armor_stand 0.5 129.5 0.5 {Tags:["sp_anchor","sp_nether"],NoGravity:1b,Invisible:1b,Rotation:[0f,0f]}'
    )
    Start-Sleep -Seconds 6
    Send-ClientCommands $Dir @(
        'cmd portal cb_make_portal 3 3 @e[tag=sp_ow,limit=1] @e[tag=sp_nether,limit=1] "sp_ow_to_nether"',
        'cmd portal cb_make_portal 3 3 @e[tag=sp_nether,limit=1] @e[tag=sp_ow,limit=1] "sp_nether_to_ow"'
    )
    Start-Sleep -Seconds 4
    # Vantage: 3 blocks south of the portal, looking straight at it.
    Send-ClientCommands $Dir @('cmd tp @s 0.5 -60 -1.0 180 0')
}

# Looks through the portal, walks into the nether, looks back, walks home; returns the counts the verdict needs.
# $Stage builds the portal pair (the server leg); the singleplayer leg reuses the pair saved in the server's world.
function Invoke-PortalLegs($Client, [string]$Dir, [string]$StdoutLog, [string]$Tag, [bool]$Stage) {
    # Counted in the game's own log only: the stdout capture repeats every line, which would double every count.
    $logs = @((Join-Path $Dir 'logs\latest.log'))
    if ($Stage) {
        Set-PortalStage $Dir
    } else {
        Send-ClientCommands $Dir @('cmd gamemode creative', 'cmd time set 1000', 'cmd tp @s 0.5 -60 -1.0 180 0')
    }
    Start-Sleep -Seconds 15   # the nether view's chunks arrive and the dest renderer draws
    Send-ClientCommands $Dir @('state', "screenshot $Tag-1-portal-view")
    Start-Sleep -Seconds 3
    $before = Count-Lines $logs $crossingPattern
    # Walk north through the portal (3.5 blocks away): 0.15 blocks per tick for 40 ticks = 6 blocks.
    Send-ClientCommands $Dir @('nudge 0 -0.15 40')
    $arrivedNether = Wait-ForCount $logs $toNetherPattern ($(Count-Lines $logs $toNetherPattern) + 1) 60 $Client
    Start-Sleep -Seconds 8
    Send-ClientCommands $Dir @('state', "screenshot $Tag-2-nether-arrived")
    Start-Sleep -Seconds 3
    # Turn round (the server sets the rotation) and look at the return portal, then walk back south through it.
    Send-ClientCommands $Dir @('cmd tp @s ~ ~ ~ 0 0')
    Start-Sleep -Seconds 4
    Send-ClientCommands $Dir @("screenshot $Tag-3-nether-looking-back")
    Start-Sleep -Seconds 3
    Send-ClientCommands $Dir @('nudge 0 0.15 40')
    $arrivedHome = Wait-ForCount $logs $toOverworldPattern ($(Count-Lines $logs $toOverworldPattern) + 1) 60 $Client
    Start-Sleep -Seconds 8
    Send-ClientCommands $Dir @('state', "screenshot $Tag-4-back-home")
    Start-Sleep -Seconds 4
    return [ordered]@{
        ArrivedNether = $arrivedNether
        ArrivedHome   = $arrivedHome
        Crossings     = (Count-Lines $logs $crossingPattern) - $before
    }
}

# ---------------------------------------------------------------------------------------------------------------
# One loader
# ---------------------------------------------------------------------------------------------------------------

function Test-Loader([string]$Loader) {
    $dir = Join-Path $work $Loader
    New-Item -ItemType Directory -Force $dir | Out-Null
    $result = [ordered]@{ Loader = $Loader; Server = 'not run'; Singleplayer = 'not run'; Notes = @() }

    Say "=== $Loader ==="
    $versionId = Install-Client $Loader
    $clientDir = Join-Path $dir 'client'
    $serverDir = Join-Path $dir 'server'
    $serverArguments = Install-Server $Loader $serverDir
    $voxyLeg = ($Loader -eq 'fabric')

    # 1. A real dedicated server with the release jar, and a real client with it joining and crossing portals.
    Say "$Loader dedicated server and client ($versionId)..."
    $server = $null
    $client = $null
    $serverLog = Join-Path $dir 'server-stdout'
    $clientLog = Join-Path $dir 'client-mp-stdout'
    try {
        Set-ServerDir $Loader $serverDir
        $server = Start-TestServer $serverDir $serverArguments $serverLog
        $ready = Select-String -LiteralPath "$serverLog.log" -Pattern 'Done \(' | Select-Object -First 1
        Say ('   server ready: ' + $ready.Line.Trim())
        Set-ClientDir $Loader $clientDir
        $client = Start-TestClient $versionId $clientDir @('--quickPlayMultiplayer', "127.0.0.1:$Port") $clientLog
        $clientLatest = Join-Path $clientDir 'logs\latest.log'
        if (-not (Wait-ForLog @("$serverLog.log") "$playerName joined the game" $ClientTimeoutSeconds $client)) {
            throw "the client never joined; see $clientLog.log, $clientLatest and $serverLog.log"
        }
        Say "   client joined; game window: $(Get-WindowState $client)"
        Start-Sleep -Seconds 10
        $legs = Invoke-PortalLegs $client $clientDir "$clientLog.log" 'mp' $true
        $clientLogs = @("$clientLog.log", $clientLatest)
        $serverLogs = @("$serverLog.log", (Join-Path $serverDir 'logs\latest.log'))
        $checks = [ordered]@{
            'walked into the nether (crossing cutover logged)'      = $legs.ArrivedNether
            'walked back to the overworld'                           = $legs.ArrivedHome
            'crossings the client logged (expect 2)'                 = $legs.Crossings
        }
        if ($voxyLeg) {
            $checks['Voxy present + compat installed (expect 1 each)']     = "$(Count-Lines @($clientLatest) 'Voxy is present') + $(Count-Lines @($clientLatest) 'Voxy compat installed')"
            $checks['Voxy LOD systems created (expect 2 or more)']          = (Count-Lines @($clientLatest) 'Creating Voxy render system')
            $checks['LOD system moved at crossings (expect 2)']             = (Count-Lines @($clientLatest) 'LOD render system moved to the promoted')
            $checks['dest LOD system created in a portal pass (expect 1+)'] = (Count-Lines @($clientLatest) 'inside its first portal pass')
        }
        $checks.Keys | ForEach-Object { Say ("   {0,-55} {1}" -f $_, $checks[$_]) }
        $outcome = Stop-TestClient $client $clientDir
        Say "   client: $outcome"
        $outcome = Stop-TestServer $server $serverDir
        Say "   server: $outcome"
        $problems = @(Find-Problems ($serverLogs + $clientLogs + @("$serverLog.err.log", "$clientLog.err.log")))
        $problems += @(Get-ChildItem -LiteralPath (Join-Path $serverDir 'crash-reports'), (Join-Path $clientDir 'crash-reports') -ErrorAction SilentlyContinue | ForEach-Object { "crash report: $($_.FullName)" })
        $warnings = Save-Warnings ($serverLogs + $clientLogs) (Join-Path $dir 'server-and-client-warnings.txt')
        Say "   $warnings WARN/ERROR lines saved to $dir\server-and-client-warnings.txt"
        $passed = $legs.ArrivedNether -and $legs.ArrivedHome -and $legs.Crossings -ge 2 -and $problems.Count -eq 0
        if ($voxyLeg) {
            $passed = $passed -and (Count-Lines @($clientLatest) 'Voxy compat installed') -eq 1 -and
                (Count-Lines @($clientLatest) 'LOD render system moved to the promoted') -ge 2 -and
                (Count-Lines @($clientLatest) 'inside its first portal pass') -ge 1
        }
        $result.Server = if ($passed) { 'PASSED' } else { 'FAILED' }
        $result.Notes += @($problems | ForEach-Object { "server + client: $_" })
        Save-Screenshots $clientDir (Join-Path $dir 'screenshots-mp')
    } catch {
        $result.Server = 'ERROR'
        $result.Notes += "server + client: $($_.Exception.Message)"
    } finally {
        if ($client) { Stop-Own $client }
        if ($server) { Stop-Own $server }
    }

    # 2. The world the server made, opened in singleplayer on the same client, with the same portal legs.
    Say "$Loader singleplayer..."
    $client = $null
    $clientLog = Join-Path $dir 'client-sp-stdout'
    try {
        $world = Join-Path $serverDir 'world'
        if (-not (Test-Path -LiteralPath (Join-Path $world 'level.dat'))) {
            throw 'the server left no world to open'
        }
        Set-ClientDir $Loader $clientDir
        $saves = Join-Path $clientDir 'saves'
        Remove-Item -LiteralPath (Join-Path $saves 'sp_release_test') -Recurse -Force -ErrorAction SilentlyContinue
        New-Item -ItemType Directory -Force $saves | Out-Null
        Copy-Item -LiteralPath $world -Destination (Join-Path $saves 'sp_release_test') -Recurse
        Remove-Item -LiteralPath (Join-Path $saves 'sp_release_test\session.lock') -ErrorAction SilentlyContinue
        # A dedicated server's world has cheats off; in singleplayer that means no /tp for the legs.
        Enable-Cheats (Join-Path $saves 'sp_release_test\level.dat')
        $client = Start-TestClient $versionId $clientDir @('--quickPlaySingleplayer', 'sp_release_test') $clientLog
        $clientLatest = Join-Path $clientDir 'logs\latest.log'
        if (-not (Wait-ForLog @("$clientLog.log", $clientLatest) "$playerName joined the game" $ClientTimeoutSeconds $client)) {
            throw "the world never opened; see $clientLog.log and $clientLatest"
        }
        Say "   world open; game window: $(Get-WindowState $client)"
        Start-Sleep -Seconds 12
        $legs = Invoke-PortalLegs $client $clientDir "$clientLog.log" 'sp' $false
        $clientLogs = @("$clientLog.log", $clientLatest)
        $checks = [ordered]@{
            'walked into the nether (crossing cutover logged)'      = $legs.ArrivedNether
            'walked back to the overworld'                           = $legs.ArrivedHome
            'crossings the client logged (expect 2)'                 = $legs.Crossings
        }
        if ($voxyLeg) {
            $checks['Voxy present + compat installed (expect 1 each)']     = "$(Count-Lines @($clientLatest) 'Voxy is present') + $(Count-Lines @($clientLatest) 'Voxy compat installed')"
            $checks['Voxy LOD systems created (expect 2 or more)']          = (Count-Lines @($clientLatest) 'Creating Voxy render system')
            $checks['LOD system moved at crossings (expect 2)']             = (Count-Lines @($clientLatest) 'LOD render system moved to the promoted')
            $checks['dest LOD system created in a portal pass (expect 1+)'] = (Count-Lines @($clientLatest) 'inside its first portal pass')
        }
        $checks.Keys | ForEach-Object { Say ("   {0,-55} {1}" -f $_, $checks[$_]) }
        $outcome = Stop-TestClient $client $clientDir
        Say "   client: $outcome"
        $problems = @(Find-Problems ($clientLogs + @("$clientLog.err.log")))
        $problems += @(Get-ChildItem -LiteralPath (Join-Path $clientDir 'crash-reports') -ErrorAction SilentlyContinue | ForEach-Object { "crash report: $($_.FullName)" })
        $warnings = Save-Warnings $clientLogs (Join-Path $dir 'singleplayer-warnings.txt')
        Say "   $warnings WARN/ERROR lines saved to $dir\singleplayer-warnings.txt"
        $passed = $legs.ArrivedNether -and $legs.ArrivedHome -and $legs.Crossings -ge 2 -and $problems.Count -eq 0
        if ($voxyLeg) {
            $passed = $passed -and (Count-Lines @($clientLatest) 'Voxy compat installed') -eq 1 -and
                (Count-Lines @($clientLatest) 'LOD render system moved to the promoted') -ge 2 -and
                (Count-Lines @($clientLatest) 'inside its first portal pass') -ge 1
        }
        $result.Singleplayer = if ($passed) { 'PASSED' } else { 'FAILED' }
        $result.Notes += @($problems | ForEach-Object { "singleplayer: $_" })
        Save-Screenshots $clientDir (Join-Path $dir 'screenshots-sp')
    } catch {
        $result.Singleplayer = 'ERROR'
        $result.Notes += "singleplayer: $($_.Exception.Message)"
    } finally {
        if ($client) { Stop-Own $client }
    }
    return $result
}

# ---------------------------------------------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------------------------------------------

$properties = Read-Properties (Join-Path $root 'gradle.properties')
$minecraft = $properties.minecraft_version
if (-not $FabricApiVersion) { $FabricApiVersion = $properties.fabric_version }
if (-not $SodiumVersion) { $SodiumVersion = $properties.sodium_version }
if (-not $VoxyVersion) { $VoxyVersion = $properties.voxy_version }

# The game's assets are already on this machine: the Fabric dev environment keeps them.
$launchConfig = Join-Path $root '.gradle\loom-cache\projects\fabric\launch.cfg'
if (-not (Test-Path -LiteralPath $launchConfig)) {
    throw "$launchConfig is missing; run 'gradlew :fabric:runClient' once so the game's assets are downloaded"
}
$configLines = @(Get-Content -LiteralPath $launchConfig | ForEach-Object { $_.Trim() })
$assetIndex = $configLines[[array]::IndexOf($configLines, '--assetIndex') + 1]
$assetsDir = $configLines[[array]::IndexOf($configLines, '--assetsDir') + 1]

New-Item -ItemType Directory -Force $work, $launcher, $downloads | Out-Null

if (-not $SkipBuild) {
    Say 'Building the release jars...'
    $env:JAVA_HOME = $javaHome
    $code = Invoke-Logged (Join-Path $root 'gradlew.bat') @('assemble', '--console=plain') $root (Join-Path $work 'build')
    if ($code -ne 0) { throw "The build failed with code $code; see $work\build.log" }
}

$releaseJars = @{}
foreach ($loader in $Loaders) {
    $jar = Join-Path $root "build\libs\$($properties.mod_id)-$loader-$($properties.mod_version).jar"
    if (-not (Test-Path -LiteralPath $jar)) { throw "$jar is missing; build first" }
    $releaseJars[$loader] = $jar
    Say ("Testing {0}  ({1:n0} bytes, built {2})" -f $jar, (Get-Item -LiteralPath $jar).Length, (Get-Item -LiteralPath $jar).LastWriteTime)
}

$agentJar = Build-Agent
Install-Vanilla
$fabricApi = Join-Path $downloads "fabric-api-$FabricApiVersion.jar"
$sodiumJar = Join-Path $downloads "sodium-$SodiumVersion.jar"
$voxyJar = Join-Path $downloads "voxy-$VoxyVersion.jar"
if ($Loaders -contains 'fabric') {
    Get-File "https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/$FabricApiVersion/fabric-api-$FabricApiVersion.jar" $fabricApi '?'
    # The released Sodium and Voxy jars, from Modrinth's maven (the same artifacts the dev runs resolve).
    Get-File "https://api.modrinth.com/maven/maven/modrinth/sodium/$SodiumVersion/sodium-$SodiumVersion.jar" $sodiumJar $null
    Get-File "https://api.modrinth.com/maven/maven/modrinth/voxy/$VoxyVersion/voxy-$VoxyVersion.jar" $voxyJar $null
}

$results = @()
foreach ($loader in $Loaders) {
    try {
        $results += Test-Loader $loader
    } catch {
        Say "$loader could not be tested: $($_.Exception.Message)"
        $results += [ordered]@{ Loader = $loader; Server = 'ERROR'; Singleplayer = 'ERROR'; Notes = @($_.Exception.Message) }
    }
}

$summary = @("Release test of $(Get-Date -Format 'yyyy-MM-dd HH:mm')")
foreach ($loader in $Loaders) {
    $jar = Get-Item -LiteralPath $releaseJars[$loader]
    $summary += ("  {0}  ({1:n0} bytes, built {2}, SHA-256 {3})" -f $jar.Name, $jar.Length, $jar.LastWriteTime, (Get-FileHash -LiteralPath $jar.FullName -Algorithm SHA256).Hash.ToLower())
}
$summary += "  Fabric: loader $($properties.fabric_loader_version), Fabric API $FabricApiVersion, Sodium $SodiumVersion, Voxy $VoxyVersion (client only)"
$summary += "  NeoForge: $NeoForgeVersion"
$summary += ''
$summary += 'Summary      server + client   singleplayer'
foreach ($result in $results) {
    $summary += ("  {0,-10} {1,-17} {2}" -f $result.Loader, $result.Server, $result.Singleplayer)
    foreach ($note in $result.Notes) { $summary += "      $note" }
}
Say ''
$summary | ForEach-Object { Say $_ }
[IO.File]::WriteAllLines((Join-Path $work 'summary.txt'), [string[]]$summary)
Say "Logs and screenshots are under $work; this summary is in summary.txt there"

$failed = @($results | Where-Object { $_.Server -ne 'PASSED' -or $_.Singleplayer -ne 'PASSED' })
if ($failed.Count -gt 0) {
    exit 1
}
