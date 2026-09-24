# dog.ps1 - AI agent (Aider + DeepSeek): 自主检查后端日志错误并修复代码
# 用法：挂 Windows 计划任务，建议每 10 分钟跑一次
# 日志/签名写到 _runtime/data/app（挂到 app-api 容器 /data，管理平台可读）
$ErrorActionPreference = 'SilentlyContinue'
$Repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$LogFile = Join-Path $Repo '_runtime\data\app\dog.log'
$SigFile = Join-Path $PSScriptRoot 'dog.sig'
$AiderPy = 'C:\Users\mayuq\AppData\Local\Programs\Python\Python312\python.exe'

function DogLog([string]$m) {
    $line = "[$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')] $m"
    Write-Output $line
    Add-Content -Path $LogFile -Value $line -Encoding UTF8
}

# 从 secret.env 读 DeepSeek key
$secret = Join-Path $Repo 'secret.env'
if (Test-Path $secret) {
    $ln = (Select-String -Path $secret -Pattern '^DEEPSEEK_API_KEY=' | Select-Object -First 1).Line
    if ($ln) { $env:DEEPSEEK_API_KEY = $ln.Split('=', 2)[1].Trim() }
}

# 1) 抓最近 10 分钟的错误日志
$logs = @()
$logs += docker logs --since 10m mit-app-api 2>&1
$logs += docker logs --since 10m mit-engine 2>&1
$errLines = @($logs | Where-Object { $_ -match '(?i)Traceback|ERROR|FATAL|Exception|RuntimeError' })
if ($errLines.Count -eq 0) { exit 0 }

$errText = ($errLines | Select-Object -Last 40) -join "`n"
if ($errText.Length -gt 4000) { $errText = $errText.Substring($errText.Length - 4000) }

# 2) 去重：同一个错误 3 轮内不重复修（避免死循环）
$sig = (($errText.Substring(0, [Math]::Min(200, $errText.Length))).GetHashCode()).ToString()
if ((Test-Path $SigFile) -and ((Get-Content $SigFile -Raw).Trim() -eq $sig)) { exit 0 }
Set-Content -Path $SigFile -Value $sig -Encoding UTF8

DogLog "检测到后端错误，调用 agent 修复 ..."

# 3) 调 Aider（DeepSeek）修代码
$prompt = "请修复下面这个后端运行错误。只改最小必要的代码，保持现有架构不变。`n`n错误日志：`n$errText"
$out = & $AiderPy -m aider --model deepseek/deepseek-chat --yes-always --message $prompt 2>&1 | Out-String

# 4) 提取 token/成本消耗
$tokLine = @($out -split "`n" | Where-Object { $_ -match '(?i)tokens|cost' } | Select-Object -Last 1)
if ($tokLine) { DogLog "agent 消耗: $($tokLine.Trim())" }
$tail = (($out -split "`n") | Select-Object -Last 4) -join ' | '
DogLog "agent 结果: $tail"

# 5) 记录是否改了文件（不自动提交，留给你 review）
Push-Location $Repo
$changed = @(git status --porcelain)
if ($changed.Count -gt 0) {
    DogLog "agent 修改了 $($changed.Count) 个文件（未提交，请 review）"
} else {
    DogLog "agent 未修改文件"
}
Pop-Location
