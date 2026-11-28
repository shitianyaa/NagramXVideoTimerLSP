param(
    [switch]$Release,
    [string]$OutputApk = ''
)

$ErrorActionPreference = 'Stop'
$projectRoot = $PSScriptRoot

function Resolve-JdkBin {
    if ($env:JDK_W -and (Test-Path -LiteralPath (Join-Path $env:JDK_W 'javac.exe'))) { return $env:JDK_W }
    if ($env:JAVA_HOME -and (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin/javac.exe'))) { return (Join-Path $env:JAVA_HOME 'bin') }
    $fallback = 'D:\JAVA\bin'
    if (Test-Path -LiteralPath (Join-Path $fallback 'javac.exe')) { return $fallback }
    $cmd = Get-Command javac -ErrorAction SilentlyContinue
    if ($cmd) { return (Split-Path -Parent $cmd.Source) }
    throw 'Missing JDK javac (set JDK_W or JAVA_HOME)'
}

$jdkBin = Resolve-JdkBin
$toolSrc = Join-Path $projectRoot 'module/tools/ModuleBuild.java'
$toolsOut = Join-Path $projectRoot 'module/build/tools'
$toolClass = Join-Path $toolsOut 'tool/ModuleBuild.class'
if (-not (Test-Path -LiteralPath $toolClass) -or ((Get-Item -LiteralPath $toolSrc).LastWriteTimeUtc -gt (Get-Item -LiteralPath $toolClass).LastWriteTimeUtc)) {
    New-Item -ItemType Directory -Path $toolsOut -Force | Out-Null
    & (Join-Path $jdkBin 'javac.exe') '-J-Duser.language=en' -encoding UTF-8 -d $toolsOut $toolSrc
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}

$toolArgs = @('build')
if ($Release) { $toolArgs += '--release' }
if ($OutputApk) { $toolArgs += @('--output', $OutputApk) }
Push-Location $projectRoot
try {
    & (Join-Path $jdkBin 'java.exe') '-Dsun.stdout.encoding=UTF-8' '-Dsun.stderr.encoding=UTF-8' -cp $toolsOut tool.ModuleBuild @toolArgs
    exit $LASTEXITCODE
} finally {
    Pop-Location
}
