. "$PSScriptRoot/Common.ps1"
# Synthetic fixtures for aggregation logic only. No HTTP, k6, Docker or result files.
function Assert-Equal($Expected, $Actual) {
    if ($Expected -ne $Actual) { throw "Expected $Expected but got $Actual." }
}
$old = [pscustomobject]@{ key = 1; sentAtMicros = 100; delayMicros = 999999 }
$new = [pscustomobject]@{ key = 1; sentAtMicros = 200; delayMicros = 2500 }
$beforeWriter = [pscustomobject]@{ bootId = 'writer'; discardedEvents = 0; publishedTotal = 1; published = @($old) }
$afterWriter = [pscustomobject]@{ bootId = 'writer'; discardedEvents = 0; publishedTotal = 2; published = @($old, $new) }
$beforeReader = [pscustomobject]@{ bootId = 'reader'; discardedEvents = 0; publishedTotal = 0; receivedTotal = 1; received = @($old) }
$afterReader = [pscustomobject]@{ bootId = 'reader'; discardedEvents = 0; publishedTotal = 0; receivedTotal = 2; received = @($old, $new) }
$result = Get-DelaySummary $beforeWriter $afterWriter $beforeReader $afterReader
Assert-Equal 1 $result.sample_count
Assert-Equal 2.5 $result.mean_ms
Assert-Equal 2.5 $result.p95_ms
$afterWriter.published = @($old, $new, $new)
$rejected = $false
try { Get-DelaySummary $beforeWriter $afterWriter $beforeReader $afterReader | Out-Null } catch { $rejected = $true }
if (!$rejected) { throw 'Duplicate tokens were accepted.' }
$afterWriter.published = @($old, $new)
$afterReader.publishedTotal = 1
$rejected = $false
try { Get-DelaySummary $beforeWriter $afterWriter $beforeReader $afterReader | Out-Null } catch { $rejected = $true }
if (!$rejected) { throw 'Reader publications were accepted.' }
$afterReader.publishedTotal = 0
$afterReader.received = @($old)
$afterReader.receivedTotal = 1
$result = Get-DelaySummary $beforeWriter $afterWriter $beforeReader $afterReader
if ($null -ne $result.mean_ms -or $null -ne $result.p95_ms) { throw 'No samples must not become zero delay.' }
$afterReader.bootId = 'restarted'
$rejected = $false
try { Get-Window $beforeReader $afterReader } catch { $rejected = $true }
if (!$rejected) { throw 'Restarted process was accepted.' }
$afterReader.bootId = 'reader'
$afterReader.discardedEvents = 1
$rejected = $false
try { Get-Window $beforeReader $afterReader } catch { $rejected = $true }
if (!$rejected) { throw 'Overflow was accepted.' }
$measurementBeforeWriter = [pscustomobject]@{
    bootId = 'writer'; writeTotal = 0; readTotal = 0; discardedWrites = 0; discardedReads = 0
}
$measurementAfterWriter = [pscustomobject]@{
    bootId = 'writer'; writeTotal = 1; readTotal = 0; discardedWrites = 0; discardedReads = 0; reads = @()
    writes = @([pscustomobject]@{ sequence = 1; version = 2; beforeCommitMicros = 100; afterCommitMicros = 110 })
}
$measurementBeforeReader = [pscustomobject]@{
    bootId = 'reader'; writeTotal = 0; readTotal = 0; discardedWrites = 0; discardedReads = 0
}
$measurementAfterReader = [pscustomobject]@{
    bootId = 'reader'; writeTotal = 0; readTotal = 4; discardedWrites = 0; discardedReads = 0; writes = @()
    reads = @(
        [pscustomobject]@{ sequence = 1; version = 1; source = 'L1'; startedMicros = 120; endedMicros = 125 },
        [pscustomobject]@{ sequence = 2; version = 1; source = 'L1'; startedMicros = 95; endedMicros = 105 },
        [pscustomobject]@{ sequence = 3; version = 1; source = 'L1'; startedMicros = 80; endedMicros = 90 },
        [pscustomobject]@{ sequence = 4; version = 2; source = 'DB'; startedMicros = 120; endedMicros = 125 }
    )
}
$stale = Get-StaleSummary $measurementBeforeWriter $measurementAfterWriter `
    $measurementBeforeReader $measurementAfterReader 1 2 1 4
$window = Get-MeasurementWindow $measurementBeforeReader $measurementAfterReader
Assert-Equal 4 $window.reads.Count
Assert-Equal 0 $window.writes.Count
Assert-Equal 1 $stale.stale
Assert-Equal 2 $stale.fresh
Assert-Equal 1 $stale.uncertain
Assert-Equal 25 $stale.stale_percent_min_of_all
Assert-Equal 50 $stale.stale_percent_max_of_all
$rejected = $false
try {
    Get-StaleSummary $measurementBeforeWriter $measurementAfterWriter `
        $measurementBeforeReader $measurementAfterReader 1 2 1 5 | Out-Null
} catch { $rejected = $true }
if (!$rejected) { throw 'Incomplete read events were accepted.' }
Write-Output 'Measurement aggregation checks passed.'
