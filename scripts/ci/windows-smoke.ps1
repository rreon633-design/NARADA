$ErrorActionPreference = 'Stop'
$root = Resolve-Path "$PSScriptRoot/../.."
Set-Location $root
$evidence = Join-Path $root '.cache/ci-evidence/windows'
$installer = @(Get-ChildItem 'target/release/bundle/nsis/*.exe')
if ($installer.Count -ne 1) { throw 'Expected one fresh NSIS installer' }
$destination = Join-Path $env:RUNNER_TEMP 'NARADA-CI-Manual-Test'
if (Test-Path $destination) { throw 'Refusing to overwrite an existing install' }
$install = Start-Process -FilePath $installer[0].FullName -ArgumentList @('/S', "/D=$destination") -PassThru
if (-not $install.WaitForExit(240000)) { Stop-Process -Id $install.Id -Force; throw 'Silent install timeout' }
if ($install.ExitCode -ne 0) { throw "Installer exited $($install.ExitCode)" }
$buildHash = (Get-FileHash 'target/release/narada-desktop.exe' -Algorithm SHA256).Hash
$installedApps = @(Get-ChildItem $destination -Filter '*.exe' | Where-Object { (Get-FileHash $_.FullName -Algorithm SHA256).Hash -eq $buildHash })
if ($installedApps.Count -ne 1) { throw 'Installed desktop executable is missing or differs from the build' }
# Tauri/NSIS may use productName for the installed executable; identify exact bytes.
$exe = $installedApps[0].FullName
$agent = Join-Path $destination 'narada-agent.exe'
if (-not (Test-Path $agent)) { throw 'Installed agent sidecar is missing' }
if ((Get-FileHash $exe -Algorithm SHA256).Hash -ne (Get-FileHash 'target/release/narada-desktop.exe' -Algorithm SHA256).Hash) { throw 'Installed application differs from build' }
if ((Get-FileHash $agent -Algorithm SHA256).Hash -ne (Get-FileHash 'target/release/narada-agent.exe' -Algorithm SHA256).Hash) { throw 'Installed agent differs from build' }
# This is a native bootstrap smoke, not a proof of rendered pixels, pairing or tray behavior.
$app = Start-Process -FilePath $exe -PassThru
try {
    Start-Sleep -Seconds 15
    $app.Refresh()
    if ($app.HasExited) { throw "Desktop exited during native launch smoke: $($app.ExitCode)" }
    $sidecars = @(Get-CimInstance Win32_Process -Filter "Name = 'narada-agent.exe'" | Where-Object { $_.ExecutablePath -eq $agent })
    if ($sidecars.Count -lt 1) { throw 'Native application did not start the installed agent' }
    $record = @{
        commit = $env:GITHUB_SHA
        installer_exit_code = $install.ExitCode
        application_stayed_alive_seconds = 15
        installed_application_sha256 = (Get-FileHash $exe -Algorithm SHA256).Hash.ToLower()
        installed_agent_sha256 = (Get-FileHash $agent -Algorithm SHA256).Hash.ToLower()
        application_working_set_sample_bytes = $app.WorkingSet64
        scope = 'Fresh Windows hosted runner: silent NSIS installation and native bootstrap only; memory sample excludes WebView/agent and is not a performance qualification.'
    }
    $record | ConvertTo-Json -Depth 5 | Out-File (Join-Path $evidence 'windows-native-smoke.json') -Encoding utf8
} finally {
    if (-not $app.HasExited) { Stop-Process -Id $app.Id -Force -ErrorAction SilentlyContinue }
    Get-CimInstance Win32_Process -Filter "Name = 'narada-agent.exe'" | Where-Object { $_.ExecutablePath -eq $agent } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
}
