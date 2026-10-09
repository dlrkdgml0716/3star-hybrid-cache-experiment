param(
    [ValidateRange(1, 100000)][int]$Rps = 100,
    [ValidateRange(0.0, 1.0)][double]$WriteRatio = 0.1,
    [ValidatePattern('^\d+[smh]$')][string]$Duration = '30s',
    [ValidateRange(0, 100000)][int]$PreAllocatedVUs = 0,
    [ValidateRange(0, 100000)][int]$MaxVUs = 0,
    [string]$Writer = 'http://127.0.0.1:8084',
    [string]$Reader = 'http://127.0.0.1:18084'
)
if (($PreAllocatedVUs -eq 0) -ne ($MaxVUs -eq 0)) { throw 'Set both PreAllocatedVUs and MaxVUs, or neither.' }
if ($MaxVUs -gt 0 -and $MaxVUs -lt $PreAllocatedVUs) { throw 'MaxVUs must be at least PreAllocatedVUs.' }
. "$PSScriptRoot/Common.ps1"
$run = New-Run 'normal-load' @{ rps = $Rps; write_ratio = $WriteRatio; duration = $Duration; pre_allocated_vus_override = $PreAllocatedVUs; max_vus_override = $MaxVUs; writer = $Writer; reader = $Reader }
try {
    $before = @(Assert-Instances $Writer $Reader)
    Save-Json $before (Join-Path $run.Directory 'metrics-before.json')
    $null = Invoke-Lab "$Reader/items/1" # warm reader L1 before the measured window
    $baseline = Invoke-Lab "$Writer/items/1/db"
    $measureBefore = @((Invoke-Lab "$Writer/experiment/measurements"),
                       (Invoke-Lab "$Reader/experiment/measurements"))
    Save-Json @((Get-MeasurementWindow $measureBefore[0] $measureBefore[0]),
                (Get-MeasurementWindow $measureBefore[1] $measureBefore[1])) `
              (Join-Path $run.Directory 'measurements-before.json')
    $loadError = $null
    try { Invoke-Load $run $Rps $WriteRatio $Duration $Writer $Reader $PreAllocatedVUs $MaxVUs }
    catch { $loadError = $_ }
    # Snapshot collection is outside k6 load; record any still-in-flight messages honestly.
    Start-Sleep -Milliseconds 500
    $after = @((Invoke-Lab "$Writer/experiment/metrics"), (Invoke-Lab "$Reader/experiment/metrics"))
    Save-Json $after (Join-Path $run.Directory 'metrics-after.json')
    $errors = Get-ErrorSummary $before $after
    Save-Json $errors (Join-Path $run.Directory 'error-summary.json')
    if (!$errors.zero_errors) { throw 'Redis, listener or clock errors occurred; inspect error-summary.json.' }
    $delay = Get-DelaySummary $before[0] $after[0] $before[1] $after[1]
    Save-Json $delay (Join-Path $run.Directory 'message-delay.json')
    $final = Invoke-Lab "$Writer/items/1/db"
    $measureAfter = @((Invoke-Lab "$Writer/experiment/measurements"),
                      (Invoke-Lab "$Reader/experiment/measurements"))
    $measureWindow = @((Get-MeasurementWindow $measureBefore[0] $measureAfter[0]),
                       (Get-MeasurementWindow $measureBefore[1] $measureAfter[1]))
    Save-Json $measureWindow (Join-Path $run.Directory 'measurements-after.json')
    if (Test-Path (Join-Path $run.Directory 'k6-summary.json')) {
        $k6 = Get-Content (Join-Path $run.Directory 'k6-summary.json') -Raw -Encoding UTF8 | ConvertFrom-Json
        if ($null -ne $loadError) { throw $loadError }
        $stale = Get-StaleSummary $measureBefore[0] $measureWindow[0] $measureBefore[1] $measureWindow[1] `
                    $baseline.version $final.version $k6.measured.writes $k6.measured.reads
        Save-Json $stale (Join-Path $run.Directory 'stale-summary.json')
    }
    if ($null -ne $loadError) { throw $loadError }
    Complete-Run $run 'completed'
} catch {
    $run.Manifest['error'] = $_.Exception.Message
    Complete-Run $run 'failed'
    throw
}
