# Build a development APK with the same dependency and native-library packaging as Gradle.
param([string]$OutputApk = '')
$ErrorActionPreference = 'Stop'
$projectRoot = $PSScriptRoot
& (Join-Path $projectRoot 'gradlew.bat') -p $projectRoot :app:assembleDebug
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
$versionLine = Get-Content -LiteralPath (Join-Path $projectRoot 'gradle.properties') |
    Where-Object { $_ -match '^MODULE_VERSION_NAME=' } | Select-Object -First 1
$version = $versionLine.Split('=', 2)[1].Trim()
$destination = if ($OutputApk) { [IO.Path]::GetFullPath($OutputApk) } else {
    Join-Path $projectRoot "build/NagramTimer-v$version-debug.apk"
}
$parent = Split-Path -Parent $destination
New-Item -ItemType Directory -Path $parent -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $projectRoot 'app/build/outputs/apk/debug/app-debug.apk') -Destination $destination -Force
Write-Host "已输出 Gradle Debug 安装包：$destination"
