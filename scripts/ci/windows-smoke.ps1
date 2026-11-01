$ErrorActionPreference = 'Stop'
$root = Resolve-Path "$PSScriptRoot/../.."
Set-Location $root
$evidence = Join-Path $root '.cache/ci-evidence/windows'
$installer = @(Get-ChildItem 'target/release/bundle/nsis/*.exe')
if ($installer.Count -ne 1) { throw 'Expected one fresh NSIS installer' }
$destination = Join-Path $env:RUNNER_TEMP 'NARADA-CI-Manual-Test'
if (Test-Path $destination) { throw 'Refusing to overwrite an existing install' }
function Get-NativeBuildExpectation([string]$repository, [string]$evidenceDirectory, [string]$commit) {
    $report = Get-Content -LiteralPath (Join-Path $evidenceDirectory 'windows-build-report.json') -Raw | ConvertFrom-Json
    if ($report.commit -ne $commit) { throw 'Windows build report identifies another commit' }
    $apps = @($report.native_binaries | Where-Object { $_.name -eq 'narada-desktop.exe' })
    $agents = @($report.native_binaries | Where-Object { $_.name -eq 'narada-agent.exe' })
    if ($apps.Count -ne 1 -or $agents.Count -ne 1) { throw 'Ambiguous native build inventory' }
    $compiled = Get-FileHash -LiteralPath (Join-Path $repository 'target/release/narada-desktop.exe') -Algorithm SHA256
    $packaged = Get-FileHash -LiteralPath (Join-Path $repository '.cache/ci-native-audit/windows/narada-desktop.exe') -Algorithm SHA256
    $agent = Get-FileHash -LiteralPath (Join-Path $repository 'target/release/narada-agent.exe') -Algorithm SHA256
    $patch = $report.nsis_bundle_transformation
    if ($patch.cli_version -ne '2.12.4' -or $patch.changed_bytes -ne 3) { throw 'Unreviewed NSIS binary transformation' }
    if ($compiled.Hash -ne $report.compiler_application.sha256 -or $compiled.Hash -ne $patch.input_sha256) { throw 'Compiler output changed after native audit' }
    if ($packaged.Hash -ne $apps[0].sha256 -or $packaged.Hash -ne $patch.packaged_sha256) { throw 'Reproduced NSIS image changed after native audit' }
    if ($agent.Hash -ne $agents[0].sha256) { throw 'Agent changed after native audit' }
    return [pscustomobject]@{
        compiled_application_sha256 = $compiled.Hash.ToLowerInvariant()
        application_sha256 = $packaged.Hash.ToLowerInvariant()
        agent_sha256 = $agent.Hash.ToLowerInvariant()
        transformation = $patch
    }
}
$expected = Get-NativeBuildExpectation $root $evidence $env:GITHUB_SHA
$buildHash = $expected.application_sha256
$agentHash = $expected.agent_sha256

# Bounded, synthetic hosted-runner diagnostics. Never search the whole disk or
# substitute a different installed binary when exact bytes are missing.
function Get-NativeInventory([string]$directory) {
    if (-not (Test-Path -LiteralPath $directory)) { return }
    $files = @(Get-ChildItem -LiteralPath $directory -File -Filter '*.exe' | Select-Object -First 16)
    foreach ($file in $files) {
        $sha = $null
        $failure = $null
        try { $sha = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash }
        catch { $failure = $_.Exception.GetType().Name }
        [pscustomobject]@{ name = $file.Name; bytes = $file.Length; sha256 = $sha; hash_error = $failure }
    }
}
$runtimeBefore = @(
    foreach ($key in @(
        'HKLM:\SOFTWARE\WOW6432Node\Microsoft\EdgeUpdate\Clients\{F3017226-FE2A-4295-8BDF-00C3A9A7E4C5}',
        'HKLM:\SOFTWARE\Microsoft\EdgeUpdate\Clients\{F3017226-FE2A-4295-8BDF-00C3A9A7E4C5}',
        'HKCU:\SOFTWARE\Microsoft\EdgeUpdate\Clients\{F3017226-FE2A-4295-8BDF-00C3A9A7E4C5}'
    )) {
        $value = Get-ItemProperty -LiteralPath $key -Name 'pv' -ErrorAction SilentlyContinue
        if ($null -ne $value) { [pscustomobject]@{ registry_key = $key; version = $value.pv } }
    }
)
$install = Start-Process -FilePath $installer[0].FullName -ArgumentList @('/S', "/D=$destination") -PassThru
if (-not $install.WaitForExit(240000)) { Stop-Process -Id $install.Id -Force; throw 'Silent install timeout' }
if ($install.ExitCode -ne 0) { throw "Installer exited $($install.ExitCode)" }

# Some bootstrapper/process trees can settle after the initial process exits.
# Bounded waiting does not weaken the final SHA-256 and process requirements.
$settle = [System.Diagnostics.Stopwatch]::StartNew()
do {
    $inventory = @(Get-NativeInventory $destination)
    $installedApps = @($inventory | Where-Object { $_.sha256 -eq $buildHash })
    $installedAgents = @($inventory | Where-Object { $_.name -eq 'narada-agent.exe' -and $_.sha256 -eq $agentHash })
    if ($installedApps.Count -eq 1 -and $installedAgents.Count -eq 1) { break }
    Start-Sleep -Seconds 2
} while ($settle.Elapsed.TotalSeconds -lt 60)
$installRecord = @{
    commit = $env:GITHUB_SHA
    installer_exit_code = $install.ExitCode
    expected_application_sha256 = $buildHash
    compiled_application_sha256 = $expected.compiled_application_sha256
    nsis_bundle_transformation = $expected.transformation
    expected_agent_sha256 = $agentHash
    installed_files = $inventory
    default_location_files = @(Get-NativeInventory (Join-Path $env:LOCALAPPDATA 'NARADA'))
    webview_runtime_before_install = $runtimeBefore
    settlement_seconds = $settle.Elapsed.TotalSeconds
}
$installRecord | ConvertTo-Json -Depth 6 | Out-File (Join-Path $evidence 'windows-install-inventory.json') -Encoding utf8
Write-Output ('NARADA install inventory: ' + ($installRecord | ConvertTo-Json -Depth 6 -Compress))
if ($installedApps.Count -ne 1) { throw 'Installed desktop executable is missing, duplicated or differs from the build' }
if ($installedAgents.Count -ne 1) { throw 'Installed agent is missing or differs from the build' }
# Tauri/NSIS may use productName; identify the exact installed bytes, not a name guess.
$exe = Join-Path $destination $installedApps[0].name
$agent = Join-Path $destination 'narada-agent.exe'
if ((Get-FileHash $exe -Algorithm SHA256).Hash -ne $buildHash) { throw 'Installed application differs from build' }
if ((Get-FileHash $agent -Algorithm SHA256).Hash -ne $agentHash) { throw 'Installed agent differs from build' }
# This is a native bootstrap smoke, not proof of rendered pixels, pairing or tray behavior.
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
        compiled_application_sha256 = $expected.compiled_application_sha256
        nsis_bundle_transformation = $expected.transformation
        installed_agent_sha256 = (Get-FileHash $agent -Algorithm SHA256).Hash.ToLower()
        application_working_set_sample_bytes = $app.WorkingSet64
        scope = 'Fresh Windows hosted runner: silent NSIS installation and native bootstrap only; memory sample excludes WebView/agent and is not a performance qualification.'
    }
    $record | ConvertTo-Json -Depth 5 | Out-File (Join-Path $evidence 'windows-native-smoke.json') -Encoding utf8
} finally {
    if (-not $app.HasExited) { Stop-Process -Id $app.Id -Force -ErrorAction SilentlyContinue }
    Get-CimInstance Win32_Process -Filter "Name = 'narada-agent.exe'" | Where-Object { $_.ExecutablePath -eq $agent } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
}
