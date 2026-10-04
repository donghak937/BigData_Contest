param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^emulator-\d+$')]
    [string]$Serial,
    [string]$SdkPath = (Join-Path $env:LOCALAPPDATA 'Android\Sdk'),
    [switch]$Offline
)

$ErrorActionPreference = 'Stop'
$taskProjectRoot = Split-Path -Parent $PSScriptRoot
$taskAdb = Join-Path $SdkPath 'platform-tools\adb.exe'
Push-Location $taskProjectRoot
try {
    $taskGradleArgs = @('-g', '.gradle-user-home', ':app:assembleDebug', ':app:assembleDebugAndroidTest', ':app:testDebugUnitTest', ':app:lintDebug', '--console=plain', '--no-daemon')
    if ($Offline) { $taskGradleArgs += '--offline' }
    & .\gradlew.bat @taskGradleArgs
    if ($LASTEXITCODE -ne 0) { throw 'Build or JVM/lint verification failed.' }
    & $taskAdb -s $Serial install -r app\build\outputs\apk\debug\app-debug.apk
    if ($LASTEXITCODE -ne 0) { throw 'App installation failed.' }
    & $taskAdb -s $Serial install -r app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk
    if ($LASTEXITCODE -ne 0) { throw 'Test installation failed.' }
    $taskTestOutput = (& $taskAdb -s $Serial shell am instrument -w kr.heureum.app.test/androidx.test.runner.AndroidJUnitRunner) -join "`n"
    $taskTestOutput
    New-Item -ItemType Directory -Force -Path app\build\reports\device | Out-Null
    $taskTestOutput | Set-Content -LiteralPath app\build\reports\device\instrumentation.txt -Encoding utf8
    if ($taskTestOutput -notmatch 'OK \(\d+ tests?\)' -or $taskTestOutput -match 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed') {
        throw 'Device verification failed. See app/build/reports/device/instrumentation.txt.'
    }
} finally {
    Pop-Location
}
