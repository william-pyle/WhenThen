param(
    [string[]]$Tasks = @('assembleDebug','testDebugUnitTest','lintDebug'),
    [string]$ToolsDirectory = ''
)
$ErrorActionPreference = 'Stop'
$whenRoot = Split-Path $PSScriptRoot -Parent
$whenTools = if ($ToolsDirectory) { [System.IO.Path]::GetFullPath($ToolsDirectory) } else { Join-Path $whenRoot '.local/tools' }
$whenLocalJdk = Get-ChildItem -LiteralPath "$whenTools/java" -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
if ($whenLocalJdk) { $env:JAVA_HOME = $whenLocalJdk.FullName }
if (Test-Path -LiteralPath "$whenTools/sdk/platforms/android-36") { $env:ANDROID_HOME = "$whenTools/sdk" }
if (-not $env:ANDROID_HOME -and $env:ANDROID_SDK_ROOT) { $env:ANDROID_HOME = $env:ANDROID_SDK_ROOT }
if (-not $env:JAVA_HOME -or -not (Test-Path -LiteralPath "$env:JAVA_HOME/bin/java.exe")) {
    throw 'Set JAVA_HOME to JDK 17, or run setup-tools.py to install the isolated toolchain.'
}
if (-not $env:ANDROID_HOME -or -not (Test-Path -LiteralPath "$env:ANDROID_HOME/platforms/android-36")) {
    throw 'Set ANDROID_HOME to an Android SDK containing platforms;android-36 and build-tools;35.0.0.'
}
if (-not $env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME = "$whenTools/gradle-cache" }
$env:Path = "$env:JAVA_HOME/bin;$env:ANDROID_HOME/platform-tools;$env:Path"
$whenSdkProperty = $env:ANDROID_HOME.Replace('\','/').Replace(':','\:')
[System.IO.File]::WriteAllText("$whenRoot/local.properties", "sdk.dir=$whenSdkProperty`n", [System.Text.Encoding]::ASCII)
$whenGradle = if (Test-Path -LiteralPath "$whenTools/gradle/gradle-8.13/bin/gradle.bat") { "$whenTools/gradle/gradle-8.13/bin/gradle.bat" } else { "$whenRoot/gradlew.bat" }
Push-Location $whenRoot
try { & $whenGradle @Tasks --console=plain; if ($LASTEXITCODE -ne 0) { throw "Android build failed: $LASTEXITCODE" } }
finally { Pop-Location }
