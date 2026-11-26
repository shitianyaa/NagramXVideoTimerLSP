$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$moduleRoot = Join-Path $projectRoot 'module'
$testClasses = Join-Path $moduleRoot 'build/test-classes'
$androidJar = if ($env:AJ_W) { $env:AJ_W } else { 'D:\AndroidSDK\platforms\android-34\android.jar' }
$jdkBin = if ($env:JDK_W) { $env:JDK_W } else { 'D:\JAVA\bin' }
$apiJar = Join-Path $moduleRoot 'lib/api-102.jar'
& (Join-Path $projectRoot 'gradlew.bat') -p $projectRoot :app:prepareHookTestLibraries --quiet
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
$testLibraries = Join-Path $moduleRoot 'build/test-libs/*'
New-Item -ItemType Directory -Path $testClasses -Force | Out-Null
$sources = @(Get-ChildItem (Join-Path $moduleRoot 'src'), (Join-Path $moduleRoot 'test') -Recurse -Filter *.java | ForEach-Object FullName)
& (Join-Path $jdkBin 'javac.exe') '-J-Duser.language=en' -encoding UTF-8 -source 8 -target 8 -nowarn -cp "$androidJar;$apiJar;$testLibraries" -d $testClasses $sources
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& (Join-Path $jdkBin 'java.exe') '-Dsun.stdout.encoding=UTF-8' '-Dsun.stderr.encoding=UTF-8' "-Dtest.output=$moduleRoot/build" "-cp" "$testClasses;$androidJar;$apiJar;$testLibraries" com.shitianyaa.nagramx.videotimer.TestMain
exit $LASTEXITCODE
