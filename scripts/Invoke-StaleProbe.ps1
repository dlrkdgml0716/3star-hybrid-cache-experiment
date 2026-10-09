param(
    [ValidateRange(1, 10000)][int]$Samples = 100,
    [long[]]$DelaysMs = @(0, 5, 10, 20, 50, 100),
    [ValidateRange(0, 60000)][int]$IntervalMs = 200,
    [switch]$Polling,
    [ValidateRange(1, 60000)][int]$PollIntervalMs = 50,
    [ValidateRange(1, 300000)][int]$PollTimeoutMs = 5000,
    [string]$Writer = 'http://127.0.0.1:8084',
    [string]$Reader = 'http://127.0.0.1:18084'
)
. "$PSScriptRoot/Common.ps1"
$ItemId = 1
if (@($DelaysMs | Where-Object { $_ -lt 0 -or $_ -gt 60000 }).Count -gt 0) { throw 'Delay must be 0..60000 ms.' }
$run = New-Run 'stale-probe' @{ samples_per_delay = $Samples; delays_ms = $DelaysMs; interval_ms = $IntervalMs; key = $ItemId; writer = $Writer; reader = $Reader; polling = [bool]$Polling; poll_interval_ms = $PollIntervalMs; poll_timeout_ms = $PollTimeoutMs }
$observations = [Collections.Generic.List[object]]::new()
try {
    $before = @(Assert-Instances $Writer $Reader)
    Save-Json $before (Join-Path $run.Directory 'metrics-before.json')
    foreach ($delay in $DelaysMs) {
        for ($i = 0; $i -lt $Samples; $i++) {
            $row = [ordered]@{ requested_delay_ms = $delay; sample = $i; reference_version = $null; observed_version = $null; actual_dispatch_delay_ms = $null; get_rtt_ms = $null; stale = $null; status = 'error'; error = $null }
            try {
                # Prewarm reader so stale observations do not require a cold cache.
                $null = Invoke-Lab "$Reader/items/$ItemId"
                $written = Invoke-Lab "$Writer/items/$ItemId/touch" 'POST'
                $row.reference_version = $written.version
                $clock = [Diagnostics.Stopwatch]::StartNew() # after response received/deserialized, not DB commit
                if ($delay -gt 0) { Start-Sleep -Milliseconds $delay }
                $row.actual_dispatch_delay_ms = $clock.Elapsed.TotalMilliseconds
                $getClock = [Diagnostics.Stopwatch]::StartNew()
                $seen = Invoke-Lab "$Reader/items/$ItemId"
                $row.get_rtt_ms = $getClock.Elapsed.TotalMilliseconds
                $row.observed_version = $seen.version
                $row.stale = $seen.version -lt $written.version
                $row.status = 'observed'
                if ($Polling) {
                    $fresh = $seen.version -ge $written.version
                    while (!$fresh -and $clock.ElapsedMilliseconds -lt $PollTimeoutMs) {
                        Start-Sleep -Milliseconds $PollIntervalMs
                        $seen = Invoke-Lab "$Reader/items/$ItemId"
                        $fresh = $seen.version -ge $written.version
                    }
                    $row['polling'] = @{ fresh_observed = $fresh; elapsed_ms = $clock.Elapsed.TotalMilliseconds; timed_out = !$fresh }
                }
            } catch { $row.error = $_.Exception.Message; $row.status = 'error'; $row.stale = $null }
            $observations.Add([pscustomobject]$row)
            Save-Json @($observations.ToArray()) (Join-Path $run.Directory 'observations.json')
            if ($IntervalMs -gt 0) { Start-Sleep -Milliseconds $IntervalMs }
        }
    }
    $summary = @()
    foreach ($delay in $DelaysMs) {
        $group = @($observations | Where-Object { $_.requested_delay_ms -eq $delay })
        $ok = @($group | Where-Object { $_.status -eq 'observed' })
        $stale = @($ok | Where-Object { $_.stale }).Count
        $ratio = $null
        if ($ok.Count -gt 0) { $ratio = 100.0 * $stale / $ok.Count }
        $summary += @{ delay_ms = $delay; attempted = $group.Count; observed = $ok.Count; errors = $group.Count - $ok.Count; stale_reads = $stale; stale_ratio_percent = $ratio }
    }
    Save-Json $summary (Join-Path $run.Directory 'stale-summary.json')
    Save-Json @((Invoke-Lab "$Writer/experiment/metrics"), (Invoke-Lab "$Reader/experiment/metrics")) (Join-Path $run.Directory 'metrics-after.json')
    $failed = @($observations | Where-Object { $_.status -ne 'observed' }).Count -gt 0
    Complete-Run $run $(if ($failed) { 'partial' } else { 'completed' })
} catch {
    $run.Manifest['error'] = $_.Exception.Message
    Complete-Run $run 'failed'
    throw
}
