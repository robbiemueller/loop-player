# Builds Loop Player without Gradle on Windows: javac -> d8 -> aapt2 -> zipalign -> apksigner.
# Uses the Android SDK and JDK that Android Studio installs. Output: build\LoopPlayer.apk
#   .\build.ps1                         build (creates local.keystore here on first run)
#   .\build.ps1 -Install                build, install over ADB and start it
#   .\build.ps1 -Keystore my.keystore -StorePass secret
# Keep your keystore: Android only installs an update signed with the same key as the installed app.
param([switch]$Install, [string]$Keystore, [string]$StorePass = 'loopplayer')
$ErrorActionPreference = 'Stop'
$versionCode = 7
$versionName = '1.5.1'
$targetSdk = 35

$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:LOCALAPPDATA\Android\Sdk" }
$bt = Get-ChildItem "$sdk\build-tools" -Directory | Sort-Object { [version]$_.Name } | Select-Object -Last 1 -ExpandProperty FullName
$androidJar = Get-ChildItem "$sdk\platforms" -Directory | Sort-Object { [int]($_.Name -replace '\D') } |
    Select-Object -Last 1 | ForEach-Object { Join-Path $_.FullName 'android.jar' }
if (-not $env:JAVA_HOME) { $env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr' }  # d8 and apksigner need it
$jdk = "$env:JAVA_HOME\bin"

$here = $PSScriptRoot
$out = Join-Path $here 'build'
if (-not $Keystore) { $Keystore = Join-Path $here 'local.keystore' }
Remove-Item -Recurse -Force $out -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force "$out\classes", "$out\dex" | Out-Null

function Assert-Ok([string]$step) {
    if ($LASTEXITCODE -ne 0) { throw "$step failed ($LASTEXITCODE)" }
}

$sources = Get-ChildItem "$here\src" -Recurse -Filter *.java | ForEach-Object FullName
& "$jdk\javac.exe" -source 8 -target 8 -Xlint:-options -encoding UTF-8 -bootclasspath $androidJar -d "$out\classes" @sources
Assert-Ok 'javac'

$classes = Get-ChildItem "$out\classes" -Recurse -Filter *.class | ForEach-Object FullName
& "$bt\d8.bat" --release --min-api 21 --lib $androidJar --output "$out\dex" @classes
Assert-Ok 'd8'

# The icon (res\mipmap-*, drawn by tools\make_icon.py) is compiled first, then linked in with the manifest.
& "$bt\aapt2.exe" compile --dir "$here\res" -o "$out\res.zip"
Assert-Ok 'aapt2 compile'
& "$bt\aapt2.exe" link -o "$out\base.apk" -I $androidJar --manifest "$here\AndroidManifest.xml" -A "$here\assets" `
    --min-sdk-version 21 --target-sdk-version $targetSdk --version-code $versionCode --version-name $versionName `
    "$out\res.zip"
Assert-Ok 'aapt2'
Push-Location "$out\dex"
& "$jdk\jar.exe" uf "$out\base.apk" classes.dex
Assert-Ok 'jar'
Pop-Location
& "$bt\zipalign.exe" -f -p 4 "$out\base.apk" "$out\aligned.apk"
Assert-Ok 'zipalign'

if (-not (Test-Path $Keystore)) {
    & "$jdk\keytool.exe" -genkeypair -keystore $Keystore -alias loopplayer -keyalg RSA -keysize 2048 -validity 36500 `
        -storepass $StorePass -keypass $StorePass -dname 'CN=Loop Player'
    Assert-Ok 'keytool'
}
& "$bt\apksigner.bat" sign --ks $Keystore --ks-pass "pass:$StorePass" --key-pass "pass:$StorePass" `
    --min-sdk-version 21 --out "$out\LoopPlayer.apk" "$out\aligned.apk"
Assert-Ok 'apksigner'
Write-Host ("Built {0} ({1:N0} KB)" -f "$out\LoopPlayer.apk", ((Get-Item "$out\LoopPlayer.apk").Length / 1KB))

if ($Install) {
    $adb = (Get-Command adb -ErrorAction SilentlyContinue).Source
    if (-not $adb) { $adb = "$sdk\platform-tools\adb.exe" }
    & $adb install -r "$out\LoopPlayer.apk"
    Assert-Ok 'adb install'
    & $adb shell mkdir -p /sdcard/Movies/loop
    & $adb shell am start -n io.github.robbiemueller.loopplayer/.PlayerActivity --ez reload true | Out-Null
    Write-Host 'Installed and started.'
}
