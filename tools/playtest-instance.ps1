# Runs the playthrough test (a bot plays a survival and makes the film) in a copy of a real modpack instance in Prism
# Launcher, with the production jars: the way players will run the mod.
#
#   powershell -File tools\playtest-instance.ps1 [-Source "Fabulously Optimized(1)"] [-NoLaunch]
#
# The source instance is never changed: its mods, configs and options are copied into a separate instance
# "SurvivalCinemaTest" (made again every time; its worlds and recordings are test data). Build first:
#   gradlew build remapGametestJar
# The result lands in <instance>\minecraft\playtest-result.txt, screenshots in <instance>\minecraft\screenshots.
param(
	[string]$Source = "Fabulously Optimized(1)",
	[string]$Name = "SurvivalCinemaTest",
	[string]$Prism = "D:\games\prism launcher\PrismLauncher\prismlauncher.exe",
	[switch]$NoLaunch
)
$ErrorActionPreference = "Stop"
$project = Split-Path -Parent $PSScriptRoot
$instances = Join-Path $env:APPDATA "PrismLauncher\instances"
$from = Join-Path $instances $Source
$to = Join-Path $instances $Name
if (-not (Test-Path (Join-Path $from "instance.cfg"))) { throw "No Prism instance $from" }
if ($Name -eq $Source) { throw "The test instance must be a copy" }

$libs = Join-Path $project "build\libs"
$mod = Get-ChildItem $libs -Filter "survival-cinema-*.jar" | Where-Object { $_.Name -notmatch "sources|gametest" } | Sort-Object LastWriteTime | Select-Object -Last 1
$test = Get-ChildItem $libs -Filter "survival-cinema-*-gametest.jar" | Sort-Object LastWriteTime | Select-Object -Last 1
if (-not $mod -or -not $test) { throw "Build first: gradlew build remapGametestJar" }
$cache = Join-Path $env:USERPROFILE ".gradle\caches\modules-2\files-2.1\maven.modrinth"
$flashback = Get-ChildItem (Join-Path $cache "flashback") -Recurse -Filter "*.jar" | Select-Object -First 1
$voicechat = Get-ChildItem (Join-Path $cache "simple-voice-chat") -Recurse -Filter "*.jar" | Select-Object -First 1

# a fresh copy: the pack's mods, configs, options and resource packs, none of its worlds
if (Test-Path $to) {
	if (-not (Test-Path (Join-Path $to "minecraft\survivalcinema-playtest.txt"))) { throw "$to exists and is not a test instance" }
	# empty it but keep the folder: a running Prism Launcher forgets an instance whose folder disappears
	Get-ChildItem $to -Force | Remove-Item -Recurse -Force
}
New-Item -ItemType Directory -Force (Join-Path $to "minecraft") | Out-Null
Copy-Item (Join-Path $from "mmc-pack.json") $to
foreach ($part in "mods", "config", "resourcepacks", "options.txt") {
	$path = Join-Path $from "minecraft\$part"
	if (Test-Path $path) { Copy-Item $path (Join-Path $to "minecraft") -Recurse }
}
$mods = Join-Path $to "minecraft\mods"
Copy-Item $mod.FullName, $test.FullName $mods
Copy-Item $flashback.FullName (Join-Path $mods "Flashback-0.39.10-for-MC1.21.11.jar")
Copy-Item $voicechat.FullName (Join-Path $mods "voicechat-fabric-1.21.11-2.6.24.jar")
Set-Content (Join-Path $to "minecraft\survivalcinema-playtest.txt") "Test instance made by tools\playtest-instance.ps1 from $Source. Deleted and made again by every run."

# the source's settings, detached from the pack updater, with the test switched on and a smaller window
$cfg = Get-Content (Join-Path $from "instance.cfg") | Where-Object { $_ -notmatch "^(name|ManagedPack[A-Za-z]*|JvmArgs|OverrideJavaArgs|LaunchMaximized|MinecraftWinWidth|MinecraftWinHeight|OverrideWindow|totalTimePlayed|lastLaunchTime|lastTimePlayed|CloseAfterLaunch)=" }
$cfg += "name=Survival Cinema Test ($Source)"
$cfg += "ManagedPack=false"
$cfg += "OverrideJavaArgs=true"
$cfg += "JvmArgs=-Dsurvivalcinema.playtest=true"
$cfg += "OverrideWindow=true"
$cfg += "LaunchMaximized=false"
$cfg += "MinecraftWinWidth=1280"
$cfg += "MinecraftWinHeight=720"
$cfg += "CloseAfterLaunch=false"
Set-Content (Join-Path $to "instance.cfg") $cfg -Encoding UTF8
Write-Output "Test instance: $to"
Write-Output ("Mods: " + ((Get-ChildItem $mods -Filter *.jar).Count))

if (-not $NoLaunch) {
	Start-Process -FilePath $Prism -ArgumentList "--launch", $Name
	Write-Output "Launched; the result will be in $to\minecraft\playtest-result.txt"
}
