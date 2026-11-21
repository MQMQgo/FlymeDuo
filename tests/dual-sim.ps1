$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$source = Join-Path $root 'app/src/main/java/io/github/yixing233/hyperduo'
$classes = Join-Path $root 'work/dual-sim/classes'
New-Item -ItemType Directory -Force $classes | Out-Null
$sources = @(Get-ChildItem -Recurse -Filter *.java (Join-Path $PSScriptRoot 'shims') | ForEach-Object FullName)
$sources += @('Prefs.java', 'TrioSettings.java', 'TrioAppearance.java', 'TrioGeometry.java', 'TrioZones.java', 'TrioRenderer.java') | ForEach-Object { Join-Path $source $_ }
$sources += Join-Path $PSScriptRoot 'DualSimTest.java'
& javac -encoding UTF-8 -d $classes $sources
if ($LASTEXITCODE) { throw 'Dual-SIM test compilation failed' }
& java '-Djava.awt.headless=true' -cp $classes io.github.yixing233.hyperduo.DualSimTest
if ($LASTEXITCODE) { throw 'Dual-SIM tests failed' }
