# UV Studio Mobile - APK build without Gradle.
#
# Uses only the command line tools in %LOCALAPPDATA%\uvk-build\android:
#   aapt2      compile/link resources + manifest
#   javac      compile the Java sources against android.jar
#   d8         dex the classes
#   zipalign   align the archive
#   apksigner  sign with the local debug keystore
#
# Usage:  powershell -NoProfile -File .\android-port\build.ps1 [-Clean]

param([switch]$Clean)

$ErrorActionPreference = 'Stop'

# python (icon generator) lives in the local build environment
$envFile = Join-Path $env:LOCALAPPDATA 'uvk-build\env.ps1'
if (Test-Path $envFile) { . $envFile }

$sdk      = Join-Path $env:LOCALAPPDATA 'uvk-build\android'
$jdk      = Join-Path $sdk 'jdk'
$bt       = Join-Path $sdk 'build-tools'
$androidJar = Join-Path $sdk 'platform\android.jar'

if (-not (Test-Path $androidJar)) {
    throw "Android toolchain missing. Run android-port\tools\get-toolchain.ps1 first."
}

$proj  = $PSScriptRoot
$build = Join-Path $proj 'build'
$apkDir = Join-Path $proj 'apk'

# The NOTICE shipped inside the APK (assets/NOTICE.txt) must match the one at the
# repository root. Refuse to build rather than ship stale attribution text.
$rootNotice = Join-Path (Split-Path $proj -Parent) 'NOTICE'
if (Test-Path $rootNotice) {
    $a = (Get-FileHash $rootNotice -Algorithm SHA256).Hash
    $b = (Get-FileHash (Join-Path $proj 'assets\NOTICE.txt') -Algorithm SHA256).Hash
    if ($a -ne $b) {
        throw "assets/NOTICE.txt differs from ..\NOTICE. Copy the root NOTICE over it, then build again."
    }
}

# Signing: keystore.jks is private and never committed. The password is read
# from UVSTUDIO_KS_PASS (or UVSTUDIO_KS_FILE / UVSTUDIO_KS_ALIAS); the values
# below are only defaults for a throwaway keystore generated on first build.
$ksFile = if ($env:UVSTUDIO_KS_FILE)  { $env:UVSTUDIO_KS_FILE }  else { Join-Path $proj 'keystore.jks' }
$ksAlias = if ($env:UVSTUDIO_KS_ALIAS) { $env:UVSTUDIO_KS_ALIAS } else { 'uvstudio' }
$ksPass = if ($env:UVSTUDIO_KS_PASS)  { $env:UVSTUDIO_KS_PASS }  else { 'uvstudio' }

if ($Clean -and (Test-Path $build)) { Remove-Item -Recurse -Force $build }
New-Item -ItemType Directory -Force -Path $build, $apkDir | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $build 'classes'), (Join-Path $build 'dex') | Out-Null

$env:JAVA_HOME = $jdk
$javac   = Join-Path $jdk 'bin\javac.exe'
$jardump = Join-Path $jdk 'bin\jar.exe'
$keytool = Join-Path $jdk 'bin\keytool.exe'

function Step($text) { Write-Host ("  [{0}] {1}" -f (Get-Date -Format 'HH:mm:ss'), $text) }

# ---- 1. icon ---------------------------------------------------------------
# The launcher icon is a real asset now: keep it unless it is missing.
$icon = Join-Path $proj 'res\mipmap-xxxhdpi\ic_launcher.png'
$python = Get-Command python -ErrorAction SilentlyContinue
if (Test-Path $icon) {
    Step 'icon (custom)'
} elseif ($python -and (Test-Path (Join-Path $proj 'tools\gen_icon.py'))) {
    Step 'icon (generated)'
    & python (Join-Path $proj 'tools\gen_icon.py') | Out-Null
} else {
    throw 'Launcher icon missing and python not available.'
}

# aapt2 needs the assets folder to exist even when it is empty.
$assetsDir = Join-Path $proj 'assets'
if (-not (Test-Path $assetsDir)) { New-Item -ItemType Directory -Force -Path $assetsDir | Out-Null }

# ---- 2. resources + manifest ----------------------------------------------
Step 'resources'
& "$bt\aapt2.exe" compile --dir (Join-Path $proj 'res') -o (Join-Path $build 'res.zip')
if ($LASTEXITCODE -ne 0) { throw 'aapt2 compile failed' }

Step 'link'
& "$bt\aapt2.exe" link `
    -o (Join-Path $build 'base.apk') `
    -I $androidJar `
    --manifest (Join-Path $proj 'AndroidManifest.xml') `
    -A $assetsDir `
    --min-sdk-version 24 --target-sdk-version 34 `
    --version-code 16 --version-name '1.1.0' `
    (Join-Path $build 'res.zip')
if ($LASTEXITCODE -ne 0) { throw 'aapt2 link failed' }

# ---- 3. java ---------------------------------------------------------------
Step 'compile'
$sources = Get-ChildItem (Join-Path $proj 'src') -Recurse -Filter *.java | ForEach-Object { $_.FullName }
$bootJars = "$androidJar;$bt\core-lambda-stubs.jar"
& $javac -source 8 -target 8 -bootclasspath $bootJars -encoding UTF-8 `
    -nowarn -d (Join-Path $build 'classes') @sources
if ($LASTEXITCODE -ne 0) { throw 'javac failed' }

# ---- 4. dex ----------------------------------------------------------------
Step 'd8'
$classes = Get-ChildItem (Join-Path $build 'classes') -Recurse -Filter *.class | ForEach-Object { $_.FullName }
& "$bt\d8.bat" --min-api 24 --lib $androidJar --output (Join-Path $build 'dex') @classes
if ($LASTEXITCODE -ne 0) { throw 'd8 failed' }

Step 'package'
& $jardump uf (Join-Path $build 'base.apk') -C (Join-Path $build 'dex') classes.dex
if ($LASTEXITCODE -ne 0) { throw 'jar update failed' }

Step 'align'
& "$bt\zipalign.exe" -f -p 4 (Join-Path $build 'base.apk') (Join-Path $build 'aligned.apk')
if ($LASTEXITCODE -ne 0) { throw 'zipalign failed' }

# ---- 5. sign ---------------------------------------------------------------
$ks = $ksFile
if (-not (Test-Path $ks)) {
    Step 'keystore'
    & $keytool -genkeypair -keystore $ks -alias $ksAlias -keyalg RSA -keysize 2048 `
        -validity 10000 -storepass $ksPass -keypass $ksPass `
        -dname 'CN=UV Studio Mobile, O=SmartReFleX, C=RO'
    if ($LASTEXITCODE -ne 0) { throw 'keytool failed' }
}

$outApk = Join-Path $apkDir 'UV-Studio-Mobile-1.1.0.apk'
Step 'sign'
& "$bt\apksigner.bat" sign --ks $ks --ks-pass "pass:$ksPass" --key-pass "pass:$ksPass" `
    --min-sdk-version 24 --out $outApk (Join-Path $build 'aligned.apk')
if ($LASTEXITCODE -ne 0) { throw 'apksigner failed' }

& "$bt\apksigner.bat" verify --min-sdk-version 24 $outApk | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'signature verification failed' }

$size = (Get-Item $outApk).Length
Write-Host ''
Write-Host ("  OK  {0}  ({1:N0} KB)" -f $outApk, ($size / 1KB))



