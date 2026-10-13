. "$PSScriptRoot/Common.ps1"
# Execute the actual probe control flow with synthetic HTTP/metrics and in-memory output.
# No servers, Docker, k6, sleeps or result files are used.
$probeText = Get-Content -LiteralPath "$PSScriptRoot/Invoke-StaleProbe.ps1" -Raw -Encoding UTF8
$probeText = $probeText.Replace('. "$PSScriptRoot/Common.ps1"', '')
$probe = [scriptblock]::Create($probeText)

function New-SyntheticMetrics([string]$Name, [string]$Failure = '') {
    $redis = @{ get = 0; generation = 3; put = 0; delete = 0; publish = 0 }
    $invalid = 0; $negative = 0
    if ($redis.ContainsKey($Failure)) { $redis[$Failure]++ }
    if ($Failure -eq 'listener') { $invalid++ }
    if ($Failure -eq 'clock') { $negative++ }
    return [pscustomobject]@{ instanceId = $Name; bootId = $Name; redisErrors = $redis;
        invalidMessages = $invalid; negativeClockDelays = $negative }
}

function Test-SyntheticProbe([string]$Failure = '') {
    $state = @{ saved = @{}; status = ''; version = 1 }
    function New-Run { return @{ Directory = 'synthetic'; Manifest = @{} } }
    function Save-Json($Value, [string]$Path) { $state.saved[(Split-Path -Leaf $Path)] = $Value }
    function Complete-Run($Run, [string]$Status) { $state.status = $Status }
    function Assert-Instances { return @((New-SyntheticMetrics 'writer'), (New-SyntheticMetrics 'reader')) }
    function Invoke-Lab([string]$Url, [string]$Method = 'GET') {
        if ($Url.EndsWith('/experiment/metrics')) {
            if ($Url.StartsWith('http://writer')) { return New-SyntheticMetrics 'writer' $Failure }
            return New-SyntheticMetrics 'reader'
        }
        if ($Method -eq 'POST') { $state.version++ }
        return [pscustomobject]@{ id = 1; version = $state.version; value = 'synthetic' }
    }
    $thrown = $false
    try { & $probe -Samples 1 -DelaysMs @(0) -IntervalMs 0 -Writer 'http://writer' -Reader 'http://reader' }
    catch { $thrown = $true }
    $summary = $state.saved['error-summary.json']
    if ($null -eq $summary) { throw 'Probe did not preserve its error summary.' }
    if ($Failure -eq '') {
        if ($thrown -or $state.status -ne 'completed' -or !$summary.zero_errors) {
            throw 'Clean HTTP observations with no new Redis errors were rejected.'
        }
    } else {
        if (!$thrown -or $state.status -ne 'failed' -or $summary.zero_errors -or $summary.total_errors -ne 1) {
            throw "HTTP-success probe did not fail for $Failure error."
        }
        if ($state.saved['observations.json'][0].status -ne 'observed') {
            throw 'Expected HTTP-success observations to be retained despite the internal error.'
        }
    }
}

foreach ($failure in @('', 'get', 'generation', 'put', 'delete', 'publish', 'listener', 'clock')) {
    Test-SyntheticProbe $failure
}
$before = @((New-SyntheticMetrics 'writer'), (New-SyntheticMetrics 'reader'))
$after = @((New-SyntheticMetrics 'writer'), (New-SyntheticMetrics 'reader'))
$after[0].redisErrors.Remove('generation')
$rejected = $false
try { Get-ErrorSummary $before $after | Out-Null } catch { $rejected = $true }
if (!$rejected) { throw 'Missing generation counter was accepted.' }
$after = @((New-SyntheticMetrics 'writer'), (New-SyntheticMetrics 'reader'))
$after[0].redisErrors.generation = 2
$rejected = $false
try { Get-ErrorSummary $before $after | Out-Null } catch { $rejected = $true }
if (!$rejected) { throw 'Decreasing generation counter was accepted.' }
Write-Output 'Probe internal-error checks passed (synthetic HTTP only).'
