param(
    [ValidateRange(1, 120)][int]$DowntimeSeconds = 10,
    [ValidateRange(1, 100000)][int]$Rps = 100,
    [ValidateRange(0.001, 1.0)][double]$WriteRatio = 0.3,
    [ValidateRange(1, 300000)][int]$PersistenceTimeoutMs = 120000,
    [string]$Writer = 'http://127.0.0.1:8084',
    [string]$Reader = 'http://127.0.0.1:18084'
)
. "$PSScriptRoot/Common.ps1"
$run = New-Run 'subscriber-disconnect' @{ requested_downtime_seconds = $DowntimeSeconds; rps = $Rps; write_ratio = $WriteRatio; writer = $Writer; reader = $Reader; persistence_timeout_ms = $PersistenceTimeoutMs }
$restore = $false
try {
    $null = Assert-Instances $Writer $Reader
    # Remove prior lab entries, then preload key 1 on reader. This does not restart the JVM.
    $null = Invoke-Lab "$Writer/experiment/cache" 'DELETE'
    $null = Invoke-Lab "$Reader/experiment/cache" 'DELETE'
    $preloaded = Invoke-Lab "$Reader/items/1"
    Save-Json $preloaded (Join-Path $run.Directory 'reader-preloaded.json')
    $restore = $true # also restore if stopping reports an error after actually stopping
    $stopped = Invoke-Lab "$Reader/experiment/subscription?enabled=false" 'POST'
    if ($stopped.running -or $stopped.listening) { throw 'Subscriber did not stop.' }
    $clock = [Diagnostics.Stopwatch]::StartNew()
    $before = @((Invoke-Lab "$Writer/experiment/metrics"), (Invoke-Lab "$Reader/experiment/metrics"))
    Save-Json $before (Join-Path $run.Directory 'metrics-before-window.json')
    Invoke-Load $run $Rps $WriteRatio ("$DowntimeSeconds" + 's') $Writer $Reader
    $after = @((Invoke-Lab "$Writer/experiment/metrics"), (Invoke-Lab "$Reader/experiment/metrics"))
    Save-Json $after (Join-Path $run.Directory 'metrics-after-window.json')
    Get-Window $before[0] $after[0]
    Get-Window $before[1] $after[1]
    $window = Get-MessageMatchWindow $before[0] $after[0] $before[1] $after[1]
    $published = @($window.published)
    $received = $window.receivedIndex
    $missing = @($published | Where-Object { !$received.ContainsKey((Get-MessageToken $_)) })
    if ($published.Count -eq 0) { throw 'No accepted publications; loss ratio is undefined.' }
    Save-Json @{ accepted_publications = $published.Count; received_matching = $published.Count - $missing.Count; missing_tokens = @($missing | ForEach-Object { (Get-MessageToken $_) }); missing_ratio_percent = 100.0 * $missing.Count / $published.Count; measurement_window_ms = $clock.Elapsed.TotalMilliseconds } (Join-Path $run.Directory 'message-loss.json')
    $null = Invoke-Lab "$Reader/experiment/subscription?enabled=true" 'POST'
    for ($i = 0; $i -lt 50; $i++) {
        $ready = Invoke-Lab "$Reader/experiment/metrics"
        if ($ready.subscriberListening) { break }
        Start-Sleep -Milliseconds 100
    }
    if (!$ready.subscriberListening) { throw 'Subscriber reconnection not confirmed.' }
    $run.Manifest['actual_subscription_outage_ms'] = $clock.Elapsed.TotalMilliseconds
    $restore = $false
    # k6 is finished; no later writes should occur. Use direct DB read as reference.
    $reference = Invoke-Lab "$Writer/items/1/db"
    Save-Json $reference (Join-Path $run.Directory 'db-reference.json')
    $observations = [Collections.Generic.List[object]]::new()
    $persistence = [Diagnostics.Stopwatch]::StartNew()
    $fresh = $false
    do {
        try {
            $seen = Invoke-Lab "$Reader/items/1"
            $fresh = $seen.version -ge $reference.version
            $observations.Add(@{ elapsed_ms = $persistence.Elapsed.TotalMilliseconds; version = $seen.version; stale = !$fresh; status = 'observed' })
        } catch { $observations.Add(@{ elapsed_ms = $persistence.Elapsed.TotalMilliseconds; stale = $null; status = 'error'; error = $_.Exception.Message }) }
        Save-Json @($observations.ToArray()) (Join-Path $run.Directory 'persistence-observations.json')
        if (!$fresh) { Start-Sleep -Milliseconds 500 }
    } while (!$fresh -and $persistence.ElapsedMilliseconds -lt $PersistenceTimeoutMs)
    Save-Json @{ fresh_observed = $fresh; timed_out = !$fresh; elapsed_ms = $persistence.Elapsed.TotalMilliseconds; reference_version = $reference.version; baseline = 'after subscription restoration and DB reference request'; poll_interval_ms = 500 } (Join-Path $run.Directory 'persistence-summary.json')
    $end = @((Invoke-Lab "$Writer/experiment/metrics"), (Invoke-Lab "$Reader/experiment/metrics"))
    Save-Json $end (Join-Path $run.Directory 'metrics-after-recovery.json')
    $errorSummary = Get-ErrorSummary $before $end
    Save-Json $errorSummary (Join-Path $run.Directory 'error-summary.json')
    if (!$errorSummary.zero_errors) { throw 'Redis, listener or clock errors occurred; inspect error-summary.json.' }
    Get-Window $after[0] $end[0]
    Get-Window $after[1] $end[1]
    $finalReference = Invoke-Lab "$Writer/items/1/db"
    if ($end[0].publishedTotal -ne $after[0].publishedTotal -or $end[1].publishedTotal -ne $after[1].publishedTotal -or $finalReference.version -ne $reference.version) {
        throw 'Additional writes occurred after the window; persistence/replay interpretation is invalid.'
    }
    $null = Get-MessageIndex $end[1].received
    $replayed = @($end[1].received | Where-Object { (Get-MessageToken $_) -in @($missing | ForEach-Object { (Get-MessageToken $_) }) })
    Save-Json @{ matching_missing_tokens_received_after_recovery = @($replayed | ForEach-Object { (Get-MessageToken $_) }); received_count = $replayed.Count } (Join-Path $run.Directory 'replay-check.json')
    $errors = @($observations | Where-Object { $_.status -eq 'error' }).Count
    Complete-Run $run $(if ($errors -gt 0) { 'partial' } else { 'completed' })
} catch {
    $run.Manifest['error'] = $_.Exception.Message
    Complete-Run $run 'failed'
    throw
} finally {
    if ($restore) {
        try { $null = Invoke-Lab "$Reader/experiment/subscription?enabled=true" 'POST' }
        catch { Write-Warning 'Subscriber restoration failed. POST /experiment/subscription?enabled=true manually.' }
    }
}
