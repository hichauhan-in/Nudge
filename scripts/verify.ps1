[CmdletBinding()]
param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$SdkPath = $env:ANDROID_HOME
)

$ErrorActionPreference = 'Stop'
$sourceRoot = Split-Path -Parent $PSScriptRoot
if (-not $JavaHome -or -not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin\java.exe'))) {
    throw 'Set JAVA_HOME to JDK 17, or pass -JavaHome.'
}
if (-not $SdkPath -or -not (Test-Path -LiteralPath $SdkPath)) {
    throw 'Set ANDROID_HOME to the Android SDK, or pass -SdkPath.'
}

$previousJavaHome = $env:JAVA_HOME
$previousAndroidHome = $env:ANDROID_HOME
$env:JAVA_HOME = $JavaHome
$env:ANDROID_HOME = $SdkPath
$buildRoot = $sourceRoot
$result = 1

try {
    if ($sourceRoot.Contains('!')) {
        $buildRoot = Join-Path $env:LOCALAPPDATA 'NudgeBuildTools\validation'
        & robocopy $sourceRoot $buildRoot /E /PURGE /XD .git .gradle .kotlin build .idea /XF .env *.jks *.keystore local.properties /NFL /NDL /NJH /NJS /NP
        if ($LASTEXITCODE -ge 8) { throw 'Could not prepare the validation source copy.' }
    }

    Push-Location $buildRoot
    try {
        & .\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:bundleRelease :app:lintDebug :app:lintRelease '-Proborazzi.test.record=true' --console=plain --no-configuration-cache
        $result = $LASTEXITCODE
    } finally {
        Pop-Location
    }

    if ($buildRoot -ne $sourceRoot) {
        $artifacts = Join-Path $sourceRoot 'build\verification'
        foreach ($folder in @('reports', 'test-results', 'outputs', 'ui-previews')) {
            $origin = Join-Path $buildRoot "app\build\$folder"
            if (Test-Path -LiteralPath $origin) {
                & robocopy $origin (Join-Path $artifacts $folder) /E /NFL /NDL /NJH /NJS /NP
                if ($LASTEXITCODE -ge 8) { throw "Could not collect $folder." }
            }
        }
        Write-Output "Verification artifacts: $artifacts"
    }
} finally {
    $env:JAVA_HOME = $previousJavaHome
    $env:ANDROID_HOME = $previousAndroidHome
}

if ($result -ne 0) { throw "Verification failed with Gradle exit code $result." }