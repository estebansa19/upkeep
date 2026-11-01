# Builds build\chunk-keeper-<version>.jar with only a JDK (21+). Usage: .\build.ps1 [-SkipTests]
param([switch]$SkipTests)
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

$manifest = "Main-Class: io.github.estebansa19.chunkkeeper.Main`nImplementation-Title: Chunk Keeper`nImplementation-Version: $version`n"
[IO.File]::WriteAllText("$build\MANIFEST.MF", $manifest)
$jar = "$build\chunk-keeper-$version.jar"
& "$bin\jar.exe" --create --file $jar --manifest "$build\MANIFEST.MF" -C "$build\classes" .
if ($LASTEXITCODE -ne 0) { throw 'jar failed' }

if (-not $SkipTests) {
    $tests = Get-ChildItem "$root\src\test\java" -Recurse -Filter *.java | ForEach-Object { $_.FullName }
    & "$bin\javac.exe" --release 21 -encoding UTF-8 -cp "$build\classes" -d "$build\test-classes" $tests
    if ($LASTEXITCODE -ne 0) { throw 'test compile failed' }
    & "$bin\java.exe" -cp "$build\classes;$build\test-classes" io.github.estebansa19.chunkkeeper.TestRunner
    if ($LASTEXITCODE -ne 0) { throw 'tests failed' }
}
"built $jar"
