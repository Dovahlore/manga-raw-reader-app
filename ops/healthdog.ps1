# healthdog.ps1 - self-healing watchdog for the mit docker project
# usage: run via Windows Task Scheduler every 5 minutes
$ErrorActionPreference = 'SilentlyContinue'
$LogFile = Join-Path $PSScriptRoot 'healthdog.log'

function Log([string]$m) {
    $line = "[$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')] $m"
    Write-Output $line
    Add-Content -Path $LogFile -Value $line -Encoding UTF8
}

# 1) container health: only mit-* containers, restart anything not "Up"
$rows = docker ps -a --format '{{.Names}}|{{.Status}}'
foreach ($r in $rows) {
    if ($r) {
        $parts = $r.Split('|')
        if ($parts.Count -ge 2) {
            $name = $parts[0].Trim()
            $status = $parts[1].Trim()
            if ($name.StartsWith('mit-') -and (-not $status.StartsWith('Up'))) {
                Log "container [$name] unhealthy ($status), restarting ..."
                $out = docker restart $name 2>&1
                Log "  -> $out"
            }
        }
    }
}

# 2) engine OOM fallback: restart mit-engine if recent logs show OOM
$engineLog = docker logs --since 5m mit-engine 2>&1 | Out-String
if ($engineLog -match 'out of memory|CUDA out of memory|Killed') {
    Log 'engine OOM detected, restarting mit-engine ...'
    docker restart mit-engine 2>&1 | Out-Null
}

# 3) low disk (< 5GB): prune docker build cache and dangling images
$free = (Get-PSDrive -Name C).Free
if ($free -lt 5GB) {
    $gb = [math]::Round($free / 1GB, 2)
    Log "disk free $gb GB < 5GB, pruning docker ..."
    docker builder prune -af 2>&1 | Out-Null
    docker image prune -af 2>&1 | Out-Null
}

# 4) token consumption: fetch /v1/usage and log cumulative usage (throttled to every 6h)
$usageMark = Join-Path $PSScriptRoot 'usage.mark'
$shouldLogUsage = $true
if (Test-Path $usageMark) {
    $lastMark = (Get-Item $usageMark).LastWriteTime
    if ((Get-Date) - $lastMark -lt [timespan]::FromHours(6)) { $shouldLogUsage = $false }
}
if ($shouldLogUsage) {
    $envFile = Join-Path $PSScriptRoot '..\app.env'
    $apiToken = ''
    if (Test-Path $envFile) {
        $line = (Select-String -Path $envFile -Pattern '^MIT_API_TOKEN=' | Select-Object -First 1).Line
        if ($line) { $apiToken = $line.Split('=', 2)[1].Trim() }
    }
    if ($apiToken) {
        try {
            $u = Invoke-RestMethod -Uri 'http://127.0.0.1:8020/v1/usage' -Headers @{'X-API-Token' = $apiToken} -TimeoutSec 10
            $usg = $u.usage
            Log "token usage: token_used=$($usg.token_used) pages=$($usg.page_count) last_active=$($usg.last_active_at)"
            Set-Content -Path $usageMark -Value (Get-Date -Format 'yyyy-MM-dd HH:mm:ss') -Encoding UTF8
        } catch {
            Log "fetch token usage failed: $_"
        }
    }
}
