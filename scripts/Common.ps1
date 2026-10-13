Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$LabRoot = Split-Path -Parent $PSScriptRoot

function Save-Json($Value, [string]$Path) {
    [IO.File]::WriteAllText($Path, ($Value | ConvertTo-Json -Depth 100), [Text.UTF8Encoding]::new($false))
}
function Invoke-Lab([string]$Url, [string]$Method = 'GET') {
    Invoke-RestMethod -Uri $Url -Method $Method -TimeoutSec 10
}
function New-Run([string]$Kind, $Parameters) {
    $id = [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssfffZ') + '-' + [Guid]::NewGuid().ToString('N').Substring(0, 8)
    $directory = Join-Path $LabRoot "results/runs/$id"
    [void][IO.Directory]::CreateDirectory($directory)
    $hashes = @()
    foreach ($folder in @('src', 'scripts', 'k6', 'infra')) {
        foreach ($file in (Get-ChildItem -LiteralPath (Join-Path $LabRoot $folder) -Recurse -File)) {
            $hashes += @{ path = $file.FullName.Substring($LabRoot.Length + 1).Replace('\', '/'); sha256 = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash }
        }
    }
    foreach ($name in @('build.gradle', 'settings.gradle', 'compose.yaml', 'Dockerfile', 'gradle/wrapper/gradle-wrapper.properties')) {
        $file = Join-Path $LabRoot $name
        $hashes += @{ path = $name; sha256 = (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash }
    }
    $manifest = [ordered]@{
        run_id = $id; kind = $Kind; started_at_utc = [DateTime]::UtcNow.ToString('o')
        parameters = $Parameters; source_sha256 = $hashes
        powershell_version = $PSVersionTable.PSVersion.ToString()
        os = [Environment]::OSVersion.VersionString
        artifacts = @(); status = 'running'
    }
    Save-Json $manifest (Join-Path $directory 'manifest.json')
    return @{ Directory = $directory; Manifest = $manifest }
}
function Complete-Run($Run, [string]$Status) {
    $Run.Manifest.status = $Status
    $Run.Manifest['finished_at_utc'] = [DateTime]::UtcNow.ToString('o')
    $Run.Manifest.artifacts = @(Get-ChildItem -LiteralPath $Run.Directory -File |
        Where-Object { $_.Name -ne 'manifest.json' } | ForEach-Object {
            @{ path = $_.Name; sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash }
        })
    Save-Json $Run.Manifest (Join-Path $Run.Directory 'manifest.json')
    Write-Host "Results: $($Run.Directory) [$Status]"
}
function Assert-Instances([string]$Writer, [string]$Reader) {
    $a = Invoke-Lab "$Writer/experiment/metrics"
    $b = Invoke-Lab "$Reader/experiment/metrics"
    if ($a.instanceId -eq $b.instanceId -or !$a.subscriberListening -or !$b.subscriberListening) {
        throw 'Use two distinct, subscribed instances.'
    }
    return @($a, $b)
}
function Assert-K6Environment {
    foreach ($name in @('K6_DURATION', 'K6_VUS', 'K6_ITERATIONS', 'K6_STAGES', 'K6_SCENARIOS')) {
        if (Test-Path "Env:$name") { throw "Native option $name is set. Remove it before this experiment." }
    }
}
function Invoke-Load($Run, [int]$Rps, [double]$WriteRatio, [string]$Duration, [string]$Writer, [string]$Reader, [int]$PreAllocatedVUs = 0, [int]$MaxVUs = 0) {
    Assert-K6Environment
    $command = Get-Command k6 -ErrorAction Stop
    $Run.Manifest['k6_version'] = (& $command.Source version | Out-String).Trim()
    $output = Join-Path $Run.Directory 'k6-summary.json'
    $log = Join-Path $Run.Directory 'k6.log'
    $savedErrorPreference = $ErrorActionPreference
    try {
        # Windows PowerShell can turn native stderr warnings into terminating errors.
        $ErrorActionPreference = 'Continue'
        $k6Arguments = @('run', '-e', "LAB_RPS=$Rps", '-e', "LAB_WRITE_RATIO=$WriteRatio", '-e', "LAB_DURATION=$Duration", '-e', "LAB_URLS=$Writer,$Reader", '-e', "LAB_OUTPUT=$output")
        if ($PreAllocatedVUs -gt 0) { $k6Arguments += @('-e', "LAB_PRE_ALLOCATED_VUS=$PreAllocatedVUs", '-e', "LAB_MAX_VUS=$MaxVUs") }
        $k6Arguments += (Join-Path $LabRoot 'k6/load.js')
        & $command.Source @k6Arguments *> $log
        $exitCode = $LASTEXITCODE
    } finally { $ErrorActionPreference = $savedErrorPreference }
    $Run.Manifest['k6_exit_code'] = $exitCode
    if (!(Test-Path -LiteralPath $output)) { throw "k6 produced no summary. Inspect $log." }
    if ($exitCode -ne 0) { throw "k6 exited with code $exitCode. The run is retained but marked failed." }
    $summary = Get-Content -LiteralPath $output -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($summary.measured.dropped_iterations -gt 0 -or $summary.measured.http_failure_ratio -gt 0 -or $summary.measured.actual_http_rps -lt ($Rps * 0.95)) {
        throw 'Load validity gate failed: require zero dropped iterations, zero HTTP failures, and >=95% target HTTP RPS. Inspect k6-summary.json.'
    }
}
function Get-Window($Before, $After) {
    if ($Before.bootId -ne $After.bootId) { throw 'Process restarted; counters are not comparable.' }
    if ($Before.discardedEvents -ne $After.discardedEvents) { throw 'Event buffer overflow; message matching is incomplete.' }
}
function Get-ErrorSummary($Before, $After) {
    $instances = @()
    $total = 0.0
    for ($i = 0; $i -lt 2; $i++) {
        if ($Before[$i].bootId -ne $After[$i].bootId -or $Before[$i].instanceId -ne $After[$i].instanceId) {
            throw 'Process changed; error counters are not comparable.'
        }
        $redis = @{}
        foreach ($operation in @('get', 'generation', 'put', 'delete', 'publish')) {
            if ($null -eq $Before[$i].redisErrors -or $null -eq $After[$i].redisErrors) {
                throw 'Redis error counters are absent from the metrics snapshot.'
            }
            if ($null -eq $Before[$i].redisErrors.$operation -or $null -eq $After[$i].redisErrors.$operation) {
                throw "Redis error counter '$operation' is absent from the metrics snapshot."
            }
            $delta = [double]$After[$i].redisErrors.$operation - [double]$Before[$i].redisErrors.$operation
            if ($delta -lt 0) { throw 'Redis error counter decreased.' }
            $redis[$operation] = $delta
            $total += $delta
        }
        $invalid = [double]$After[$i].invalidMessages - [double]$Before[$i].invalidMessages
        $negative = [double]$After[$i].negativeClockDelays - [double]$Before[$i].negativeClockDelays
        if ($invalid -lt 0 -or $negative -lt 0) { throw 'Listener or clock error counter decreased.' }
        $total += $invalid + $negative
        $instances += @{ instance_id = $After[$i].instanceId; redis_errors = $redis;
                         invalid_messages = $invalid; negative_clock_delays = $negative }
    }
    return @{ instances = $instances; total_errors = $total; zero_errors = ($total -eq 0) }
}
# Wire timestamps are not IDs. Reject ambiguous tokens instead of claiming exact matching.
function Get-MessageToken($Event) {
    if ($null -eq $Event.key -or $null -eq $Event.sentAtMicros -or [long]$Event.sentAtMicros -le 0) {
        throw 'Key/timestamp message fields are missing or invalid.'
    }
    return ([string]$Event.key + '|' + [string]$Event.sentAtMicros)
}
function Get-MessageIndex($Events) {
    $index = @{}
    foreach ($event in $Events) {
        $token = Get-MessageToken $event
        if ($index.ContainsKey($token)) { throw 'Ambiguous duplicate key/timestamp; message matching is invalid.' }
        $index[$token] = $event
    }
    return $index
}
function Get-MessageMatchWindow($BeforeWriter, $AfterWriter, $BeforeReader, $AfterReader) {
    Get-Window $BeforeWriter $AfterWriter
    Get-Window $BeforeReader $AfterReader
    if ($AfterReader.publishedTotal -ne $BeforeReader.publishedTotal) {
        throw 'Reader published messages; origin cannot be distinguished with key/timestamp protocol.'
    }
    $old = Get-MessageIndex $BeforeWriter.published
    $all = Get-MessageIndex $AfterWriter.published
    $published = @($AfterWriter.published | Where-Object { !$old.ContainsKey((Get-MessageToken $_)) })
    if ($published.Count -ne ($AfterWriter.publishedTotal - $BeforeWriter.publishedTotal)) {
        throw 'Incomplete publication window.'
    }
    $oldReceived = Get-MessageIndex $BeforeReader.received
    $allReceived = Get-MessageIndex $AfterReader.received
    $received = @($AfterReader.received | Where-Object { !$oldReceived.ContainsKey((Get-MessageToken $_)) })
    if ($received.Count -ne ($AfterReader.receivedTotal - $BeforeReader.receivedTotal)) {
        throw 'Incomplete reception window or untimed messages.'
    }
    $receivedIndex = Get-MessageIndex $received
    $publishedIndex = Get-MessageIndex $published
    foreach ($event in $received) {
        if (!$publishedIndex.ContainsKey((Get-MessageToken $event))) {
            throw 'Unmatched reception; other publishers or window contamination may exist.'
        }
    }
    return @{ published = $published; received = $received; receivedIndex = $receivedIndex }
}
function Get-DelaySummary($BeforeWriter, $AfterWriter, $BeforeReader, $AfterReader) {
    $window = Get-MessageMatchWindow $BeforeWriter $AfterWriter $BeforeReader $AfterReader
    $samples = @($window.received | Where-Object { $null -ne $_.delayMicros })
    $sorted = @($samples | ForEach-Object { [double]$_.delayMicros / 1000 } | Sort-Object)
    $mean = $null; $p95 = $null
    if ($sorted.Count -gt 0) {
        $mean = ($sorted | Measure-Object -Average).Average
        $p95 = $sorted[[Math]::Ceiling($sorted.Count * 0.95) - 1]
    }
    return @{ metric = 'publish_timestamp_to_reader_listener_entry'; matching = 'unique-key-timestamp-single-writer'; sample_count = $sorted.Count; mean_ms = $mean; p95_ms = $p95; p95_method = 'nearest-rank'; samples = $samples }
}

function Get-MeasurementWindow($Before, $After) {
    return [pscustomobject]@{
        bootId = $After.bootId
        writeTotal = $After.writeTotal
        readTotal = $After.readTotal
        discardedWrites = $After.discardedWrites
        discardedReads = $After.discardedReads
        writes = @($After.writes | Where-Object { $_.sequence -gt $Before.writeTotal })
        reads = @($After.reads | Where-Object { $_.sequence -gt $Before.readTotal })
    }
}

function Get-StaleSummary($BeforeWriter, $AfterWriter, $BeforeReader, $AfterReader,
                          [long]$BaselineVersion, [long]$FinalVersion,
                          [long]$ExpectedWrites, [long]$ExpectedReads) {
    if ($BeforeWriter.bootId -ne $AfterWriter.bootId -or $BeforeReader.bootId -ne $AfterReader.bootId) {
        throw 'Measurement process restarted.'
    }
    if ($BeforeWriter.discardedWrites -ne $AfterWriter.discardedWrites -or
        $BeforeReader.discardedReads -ne $AfterReader.discardedReads) {
        throw 'Measurement event buffer overflow.'
    }
    $writes = @($AfterWriter.writes | Where-Object { $_.sequence -gt $BeforeWriter.writeTotal } | Sort-Object version)
    $reads = @($AfterReader.reads | Where-Object { $_.sequence -gt $BeforeReader.readTotal })
    if ($writes.Count -ne $ExpectedWrites -or $reads.Count -ne $ExpectedReads -or
        $AfterWriter.writeTotal - $BeforeWriter.writeTotal -ne $ExpectedWrites -or
        $AfterReader.readTotal - $BeforeReader.readTotal -ne $ExpectedReads) {
        throw "Incomplete or contaminated measurements: writes $($writes.Count)/$ExpectedWrites, reads $($reads.Count)/$ExpectedReads."
    }
    if ($FinalVersion -ne $BaselineVersion + $ExpectedWrites) {
        throw 'DB version delta differs from recorded writes; another writer or missing records may exist.'
    }
    $byVersion = @{}
    $expectedVersion = $BaselineVersion + 1
    foreach ($write in $writes) {
        if ([long]$write.version -ne $expectedVersion -or
            [long]$write.beforeCommitMicros -le 0 -or
            [long]$write.afterCommitMicros -le 0 -or
            [long]$write.beforeCommitMicros -gt [long]$write.afterCommitMicros) {
            throw 'Write versions or commit intervals are invalid.'
        }
        $byVersion[$expectedVersion] = $write
        $expectedVersion++
    }
    $observations = [Collections.Generic.List[object]]::new()
    $counts = @{ stale = 0; fresh = 0; uncertain = 0 }
    foreach ($read in $reads) {
        $version = [long]$read.version
        $start = [long]$read.startedMicros
        $end = [long]$read.endedMicros
        if ($start -le 0 -or $start -gt $end -or $version -gt $FinalVersion) { throw 'Invalid read version or interval.' }
        $classification = 'fresh'
        $nextWrite = $null
        if ($version -lt $BaselineVersion) {
            $classification = 'stale'
        } elseif ($version -lt $FinalVersion) {
            $nextWrite = $byVersion[$version + 1]
            if ($null -eq $nextWrite) { throw 'Missing next write for read version.' }
            if ([long]$nextWrite.afterCommitMicros -le $start) {
                $classification = 'stale'
            } elseif ([long]$nextWrite.beforeCommitMicros -le $end) {
                $classification = 'uncertain'
            }
        }
        $counts[$classification]++
        $observations.Add([pscustomobject]@{
            sequence = $read.sequence; version = $version; source = $read.source
            started_micros = $start; ended_micros = $end
            next_version = $(if ($null -eq $nextWrite) { $null } else { $nextWrite.version })
            classification = $classification
        })
    }
    return @{ definition = 'read-version-versus-committed-version-at-cache-lookup';
        baseline_version = $BaselineVersion; final_version = $FinalVersion
        observed_reads = $reads.Count; observed_writes = $writes.Count
        stale = $counts.stale; fresh = $counts.fresh; uncertain = $counts.uncertain
        stale_percent_min_of_all = $(if ($reads.Count -gt 0) { 100.0 * $counts.stale / $reads.Count } else { $null })
        stale_percent_max_of_all = $(if ($reads.Count -gt 0) { 100.0 * ($counts.stale + $counts.uncertain) / $reads.Count } else { $null })
        stale_percent_of_classifiable = $(if ($reads.Count - $counts.uncertain -gt 0) {
            100.0 * $counts.stale / ($reads.Count - $counts.uncertain)
        } else { $null }); observations = @($observations.ToArray()) }
}
