param([string]$ToolsZip)

$ErrorActionPreference = 'Stop'
$project = $PSScriptRoot
$workspaceTools = Join-Path $project '.toolchain'
$sdk = Join-Path $workspaceTools 'android-sdk'
$manager = Join-Path $sdk 'cmdline-tools\latest\bin\sdkmanager.bat'

if (-not (Test-Path -LiteralPath $manager)) {
    New-Item -ItemType Directory -Path $workspaceTools -Force | Out-Null
    if (-not $ToolsZip) {
        $ToolsZip = Join-Path $workspaceTools 'commandlinetools-win-15859902_latest.zip'
        if (-not (Test-Path -LiteralPath $ToolsZip)) {
            $partial = "$ToolsZip.part"
            & curl.exe --fail --location --connect-timeout 15 --output $partial 'https://dl.google.com/android/repository/commandlinetools-win-15859902_latest.zip'
            if ($LASTEXITCODE -ne 0) { throw 'Could not download Android Command-line Tools from dl.google.com.' }
            Move-Item -LiteralPath $partial -Destination $ToolsZip
        }
    }
    $expected = '90ae805d20434428bffcb699c290860f19bb5f66a67e6b330067e3de801fb04a'
    if ((Get-FileHash -LiteralPath $ToolsZip -Algorithm SHA256).Hash -ne $expected) {
        throw 'The ZIP does not match the official commandlinetools-win-15859902_latest.zip checksum.'
    }

    $staging = Join-Path $workspaceTools 'sdk-extract'
    Expand-Archive -LiteralPath $ToolsZip -DestinationPath $staging -Force
    $parent = Join-Path $sdk 'cmdline-tools'
    New-Item -ItemType Directory -Path $parent -Force | Out-Null
    Move-Item -LiteralPath (Join-Path $staging 'cmdline-tools') -Destination (Join-Path $parent 'latest')
}

& $manager "--sdk_root=$sdk" 'platforms;android-37.0' 'build-tools;36.0.0' 'platform-tools'
if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath (Join-Path $sdk 'platforms\android-37.0\android.jar')) -or
    -not (Test-Path -LiteralPath (Join-Path $sdk 'build-tools\36.0.0\apksigner.bat')) -or
    -not (Test-Path -LiteralPath (Join-Path $sdk 'platform-tools\adb.exe'))) {
    throw 'The required Android SDK packages were not installed.'
}

$properties = Join-Path $project 'local.properties'
$existing = if (Test-Path -LiteralPath $properties) { Get-Content -LiteralPath $properties }
$lines = @($existing | Where-Object { $_ -notmatch '^\s*sdk\.dir\s*[:=]' })
$lines += 'sdk.dir=' + $sdk.Replace('\', '/')
[System.IO.File]::WriteAllLines($properties, [string[]]$lines, [System.Text.UTF8Encoding]::new($false))

Push-Location -LiteralPath $project
try {
    & .\gradlew.bat assembleRelease '-PgameRecTestSigning=true'
    if ($LASTEXITCODE -ne 0) { throw 'GameRec release build failed.' }
    $apk = Join-Path $project 'app\build\outputs\apk\release\app-release.apk'
    & (Join-Path $sdk 'build-tools\36.0.0\apksigner.bat') verify $apk
    if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
    Write-Output $apk
} finally {
    Pop-Location
}
