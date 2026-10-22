<#
.SYNOPSIS
    Installs an APK on a MIUI/HyperOS device, auto-approving the "Install via USB" dialog.

.DESCRIPTION
    MIUI's Security Center intercepts `adb install` with an "Install via USB" confirmation
    activity (com.miui.permcenter.install.AdbInstallActivity) that auto-denies after a few
    seconds unless something taps it. Gradle's connectedAndroidTest then fails with
    INSTALL_FAILED_USER_RESTRICTED. This helper starts the install in the background and taps
    "Remember my choice" + "Install" on that dialog before the countdown expires.

.EXAMPLE
    .\install_apk.ps1 -Apk "app\build\outputs\apk\debug\app-debug.apk"
#>
param(
    [Parameter(Mandatory = $true)][string]$Apk,
    [int]$Attempts = 3
)

$ErrorActionPreference = 'Continue'
$adb = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
$apkPath = (Resolve-Path $Apk).Path

function Get-InstallButton {
    & $adb shell uiautomator dump /sdcard/_install_ui.xml 2>&1 | Out-Null
    & $adb pull /sdcard/_install_ui.xml "$env:TEMP\_install_ui.xml" 2>&1 | Out-Null
    if (-not (Test-Path "$env:TEMP\_install_ui.xml")) { return $null }
    $xml = Get-Content "$env:TEMP\_install_ui.xml" -Raw
    $m = [regex]::Match($xml, 'text="Install"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
    if (-not $m.Success) { return $null }
    $x1 = [int]$m.Groups[1].Value
    $y1 = [int]$m.Groups[2].Value
    $x2 = [int]$m.Groups[3].Value
    $y2 = [int]$m.Groups[4].Value

    # The "Remember my choice" radio sits just left of its label, one row above the buttons.
    $radio = $null
    $r = [regex]::Match($xml, 'text="Remember my choice"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
    if ($r.Success) {
        $rx1 = [int]$r.Groups[1].Value
        $ry1 = [int]$r.Groups[2].Value
        $ry2 = [int]$r.Groups[4].Value
        $radio = @{ X = $rx1 + 26; Y = [int](($ry1 + $ry2) / 2) }
    }

    return @{ X = [int](($x1 + $x2) / 2)
             Y = [int](($y1 + $y2) / 2)
             Radio = $radio
             Bounds = $m.Value }
}

for ($attempt = 1; $attempt -le $Attempts; $attempt++) {
    Write-Host "Install attempt $attempt/$Attempts for $apkPath"
    $job = Start-Job -ScriptBlock {
        param($adbExe, $path)
        & $adbExe install -r -t $path 2>&1
    } -ArgumentList $adb, $apkPath

    # The dialog appears a couple of seconds into the install; poll for it and tap
    # "Remember my choice" then "Install" before the deny countdown runs out.
    $button = $null
    for ($poll = 0; $poll -lt 8; $poll++) {
        Start-Sleep -Milliseconds 900
        $button = Get-InstallButton
        if ($button) { break }
    }

    if ($button) {
        Write-Host "  Dialog found (Install at $($button.X),$($button.Y)) - approving"
        if ($button.Radio) {
            & $adb shell input tap $button.Radio.X $button.Radio.Y | Out-Null
            Start-Sleep -Milliseconds 400
        }
        & $adb shell input tap $button.X $button.Y | Out-Null
    } else {
        Write-Host "  No dialog detected"
    }

    $result = Receive-Job $job -Wait -ErrorAction SilentlyContinue
    Remove-Job $job -Force -ErrorAction SilentlyContinue

    $out = ($result | Out-String)
    if ($out -match 'Success') {
        Write-Host "  SUCCESS"
        exit 0
    }
    Write-Host "  FAILED: $($out.Trim())"
    Start-Sleep -Seconds 2
}

Write-Error "Install failed after $Attempts attempts"
exit 1
