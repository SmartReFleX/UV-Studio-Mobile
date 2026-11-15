#!/usr/bin/env pwsh
# Downloads the Android build toolchain into %LOCALAPPDATA%\uvk-build\android.
# No installer, no system changes: everything stays inside this folder.
$ErrorActionPreference = 'Stop'
$root = Join-Path $env:LOCALAPPDATA 'uvk-build\android'
$dl   = Join-Path $root 'dl'
New-Item -ItemType Directory -Force -Path $dl | Out-Null

$files = @(
    @{ n = 'jdk.zip';       u = 'https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse' },
    @{ n = 'platform.zip';  u = 'https://dl.google.com/android/repository/platform-35_r02.zip' },
    @{ n = 'buildtools.zip';u = 'https://dl.google.com/android/repository/build-tools_r34-windows.zip' }
)

foreach ($f in $files) {
    $out = Join-Path $dl $f.n
    if (Test-Path $out) { Write-Output "have $($f.n)"; continue }
    Write-Output "downloading $($f.n) ..."
    & curl.exe -L --fail --retry 3 --silent --show-error -o $out $f.u
    if ($LASTEXITCODE -ne 0) { throw "download failed: $($f.u)" }
    Write-Output ("  {0:N1} MB" -f ((Get-Item $out).Length / 1MB))
}

function Expand-Strip([string]$zip, [string]$dest, [string]$inner) {
    if (Test-Path $dest) { Remove-Item -Recurse -Force $dest }
    $tmp = "$dest.tmp"
    if (Test-Path $tmp) { Remove-Item -Recurse -Force $tmp }
    Expand-Archive -Path $zip -DestinationPath $tmp -Force
    if ($inner) { Move-Item (Join-Path $tmp $inner) $dest } else { Move-Item $tmp $dest; $tmp = $null }
    if ($tmp -and (Test-Path $tmp)) { Remove-Item -Recurse -Force $tmp }
}

Write-Output 'extracting jdk ...'
$jdkDir = Join-Path $root 'jdk'
if (-not (Test-Path (Join-Path $jdkDir 'bin\javac.exe'))) {
    if (Test-Path $jdkDir) { Remove-Item -Recurse -Force $jdkDir }
    $tmp = "$jdkDir.tmp"
    if (Test-Path $tmp) { Remove-Item -Recurse -Force $tmp }
    Expand-Archive -Path (Join-Path $dl 'jdk.zip') -DestinationPath $tmp -Force
    $inner = Get-ChildItem $tmp -Directory | Select-Object -First 1
    Move-Item $inner.FullName $jdkDir
    Remove-Item -Recurse -Force $tmp
}

Write-Output 'extracting platform + build-tools ...'
Expand-Strip (Join-Path $dl 'platform.zip')   (Join-Path $root 'platform')   'android-35'
Expand-Strip (Join-Path $dl 'buildtools.zip') (Join-Path $root 'build-tools') 'android-14'

$java  = Join-Path $root 'jdk\bin\java.exe'
$aapt2 = Join-Path $root 'build-tools\aapt2.exe'
Write-Host ''
Write-Host "java:        $java"
Write-Host "android.jar: $(Join-Path $root 'platform\android.jar')"
Write-Host "aapt2:       $aapt2"

# java writes its version banner to stderr; keep the exit code clean
$previous = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
& $java -version 2>&1 | ForEach-Object { "  $_" }
& $aapt2 version 2>&1 | ForEach-Object { "  $_" }
$ErrorActionPreference = $previous

Write-Host 'TOOLCHAIN READY'
exit 0
