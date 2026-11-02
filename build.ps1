# Builds build\upkeep-<version>.jar with only a JDK (21+). Usage: .\build.ps1 [-SkipTests] [-Package]
# -Package also builds the Windows app (build\dist\Upkeep\Upkeep.exe with its own Java) and zips it.
param([switch]$SkipTests, [switch]$Package)
$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot

function Find-Jdk {
    $cands = @()
    if ($env:JAVA_HOME) { $cands += Join-Path $env:JAVA_HOME 'bin' }
    $onPath = Get-Command javac -ErrorAction SilentlyContinue
    if ($onPath) { $cands += Split-Path $onPath.Source }
    $cands += Get-ChildItem "$env:APPDATA\PrismLauncher\java" -Directory -ErrorAction SilentlyContinue | ForEach-Object { Join-Path $_.FullName 'bin' }
    foreach ($c in $cands) { if (Test-Path (Join-Path $c 'javac.exe')) { return $c } }
    throw 'No JDK found (need javac 21+). Set JAVA_HOME.'
}

$bin = Find-Jdk
$version = (Get-Content "$root\VERSION" -Raw).Trim()
$build = Join-Path $root 'build'
foreach ($d in 'classes', 'test-classes') { if (Test-Path "$build\$d") { Remove-Item "$build\$d" -Recurse -Force } }
New-Item -ItemType Directory "$build\classes", "$build\test-classes" -Force | Out-Null

$src = Get-ChildItem "$root\src\main\java" -Recurse -Filter *.java | ForEach-Object { $_.FullName }
& "$bin\javac.exe" --release 21 -encoding UTF-8 -Xlint:deprecation -d "$build\classes" $src
if ($LASTEXITCODE -ne 0) { throw 'compile failed' }
Copy-Item "$root\src\main\resources\*" "$build\classes" -Recurse -Force

$manifest = "Main-Class: io.github.estebansa19.upkeep.Main`nImplementation-Title: Upkeep`nImplementation-Version: $version`n"
[IO.File]::WriteAllText("$build\MANIFEST.MF", $manifest)
$jar = "$build\upkeep-$version.jar"
& "$bin\jar.exe" --create --file $jar --manifest "$build\MANIFEST.MF" -C "$build\classes" .
if ($LASTEXITCODE -ne 0) { throw 'jar failed' }

if (-not $SkipTests) {
    $tests = Get-ChildItem "$root\src\test\java" -Recurse -Filter *.java | ForEach-Object { $_.FullName }
    & "$bin\javac.exe" --release 21 -encoding UTF-8 -cp "$build\classes" -d "$build\test-classes" $tests
    if ($LASTEXITCODE -ne 0) { throw 'test compile failed' }
    & "$bin\java.exe" -cp "$build\classes;$build\test-classes" io.github.estebansa19.upkeep.TestRunner
    if ($LASTEXITCODE -ne 0) { throw 'tests failed' }
}
"built $jar"

if ($Package) {
    $dist = "$build\dist"
    $appInput = "$build\package-input"
    if (Get-Process Upkeep -ErrorAction SilentlyContinue | Where-Object { $_.Path -like "$dist\*" }) { throw 'Upkeep is running from build\dist: quit it first.' }
    foreach ($d in $dist, $appInput, "$build\link-probe") { if (Test-Path $d) { Remove-Item $d -Recurse -Force } }
    New-Item -ItemType Directory $appInput | Out-Null
    Copy-Item $jar $appInput
    & "$bin\java.exe" -cp "$build\classes" io.github.estebansa19.upkeep.ui.AppIcon "$build\upkeep.ico"
    if ($LASTEXITCODE -ne 0) { throw 'icon failed' }

    $opts = @('--type', 'app-image', '--name', 'Upkeep', '--app-version', $version, '--vendor', 'Esteban Alzate',
        '--description', 'Upkeep - keep your Minecraft builds, regenerate the rest', '--copyright', 'Copyright (c) 2026 Esteban Alzate',
        '--icon', "$build\upkeep.ico", '--input', $appInput, '--main-jar', "upkeep-$version.jar",
        '--main-class', 'io.github.estebansa19.upkeep.Main', '--dest', $dist)
    # A JDK that can link (jmods, or a linkable runtime such as Temurin) gets a small Java with just the modules Upkeep
    # uses. Others (like the Java Prism Launcher installs) can only be copied whole: fine for a local test, not a release.
    $ErrorActionPreference = 'Continue' # the probe may print an error; that is the answer, not a failure
    & "$bin\jlink.exe" --add-modules java.base --output "$build\link-probe" *> $null
    $canLink = $LASTEXITCODE -eq 0
    $ErrorActionPreference = 'Stop'
    if ($canLink) {
        # Before Java 22 the elliptic-curve crypto lives in jdk.crypto.ec; without it HTTPS to Mojang fails (handshake_failure)
        $jdkMajor = [int](((Get-Content (Join-Path (Split-Path $bin) 'release') | Where-Object { $_ -like 'JAVA_VERSION=*' }) -replace '\D*(\d+).*', '$1'))
        $modules = 'java.base,java.desktop,java.net.http,jdk.httpserver' + $(if ($jdkMajor -lt 22) { ',jdk.crypto.ec' } else { '' })
        $opts += @('--add-modules', $modules,
            '--jlink-options', '--strip-debug --no-header-files --no-man-pages --strip-native-commands --compress=zip-6')
    } else {
        Write-Warning 'This JDK cannot link a small Java runtime, so the whole JDK goes into the app (local test build only).'
        $opts += @('--runtime-image', (Split-Path $bin))
    }
    & "$bin\jpackage.exe" @opts
    if ($LASTEXITCODE -ne 0) { throw 'jpackage failed' }
    $zip = "$build\Upkeep-$version-windows-x64.zip"
    if (Test-Path $zip) { Remove-Item $zip -Force }
    Compress-Archive -Path "$dist\Upkeep" -DestinationPath $zip
    "built $dist\Upkeep\Upkeep.exe and $zip"
}
