$ErrorActionPreference = "Stop"

$sdkRoot = if ($env:ANDROID_SDK_ROOT) {
    $env:ANDROID_SDK_ROOT
} elseif ($env:ANDROID_HOME) {
    $env:ANDROID_HOME
} else {
    Join-Path $env:LOCALAPPDATA "Android\Sdk"
}

if (-not (Test-Path -LiteralPath $sdkRoot)) {
    throw "Android SDK not found. Set ANDROID_SDK_ROOT or ANDROID_HOME."
}

$platform = Get-ChildItem (Join-Path $sdkRoot "platforms") -Directory |
    Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName "android.jar") } |
    Sort-Object Name -Descending |
    Select-Object -First 1
$buildTools = Get-ChildItem (Join-Path $sdkRoot "build-tools") -Directory |
    Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName "d8.bat") } |
    Sort-Object Name -Descending |
    Select-Object -First 1

if (-not $platform -or -not $buildTools) {
    throw "Install an Android SDK platform and build-tools package first."
}

$classes = Join-Path $PSScriptRoot ".helper-build"
$output = Join-Path $PSScriptRoot "set-cover.jar"
New-Item -ItemType Directory -Force -Path $classes | Out-Null

$javac = (Get-Command javac -ErrorAction Stop).Source
& $javac -cp (Join-Path $platform.FullName "android.jar") -d $classes (Join-Path $PSScriptRoot "SetCoverWallpaper.java")
if ($LASTEXITCODE -ne 0) {
    throw "javac failed with exit code $LASTEXITCODE"
}

if (Test-Path -LiteralPath $output) {
    Remove-Item -LiteralPath $output -Force
}

$d8 = Join-Path $buildTools.FullName "d8.bat"
& $d8 --min-api 28 --output $output `
    (Join-Path $classes "SetCoverWallpaper.class") `
    (Join-Path $classes 'SetCoverWallpaper$1.class')
if ($LASTEXITCODE -ne 0) {
    throw "d8 failed with exit code $LASTEXITCODE"
}

Write-Host "Built $output"
