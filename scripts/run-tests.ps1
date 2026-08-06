# mocktest 双端自动化测试编排脚本
#
# 用法：
#   ./scripts/run-tests.ps1                   # 双端全部套件跑 1 次（suite=all）
#   ./scripts/run-tests.ps1 -Runs 3           # 全部套件跑 3 次（验证稳定性/flaky）
#   ./scripts/run-tests.ps1 -Suite api-smoke   # 双端指定套件
#   ./scripts/run-tests.ps1 -Loader fabric     # 只跑 fabric
#   ./scripts/run-tests.ps1 -Loader neoforge -Runs 2  # 只跑 neoforge 2 次
#
# 每个 loader 跑完在 fabric|neoforge/runs/client/test-results/<suite>.json 写结果，
# 全部跑完自动退出。任一轮任一端失败脚本返回非 0。

param(
    [string]$Suite = "all",
    [string]$Loader = "all",  # all | fabric | neoforge
    [int]$Runs = 1            # 每端跑的轮数（默认 1 次；>1 用于回归/flaky 验证）
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot

function Invoke-Test([string]$module, [string]$suite) {
    Write-Host "`n=== [$module] runTestClient -Psuite=$suite ===" -ForegroundColor Cyan
    & "$root\gradlew.bat" ":$module`:runTestClient" "-Psuite=$suite"
    if ($LASTEXITCODE -ne 0) {
        Write-Host "[$module] FAILED (exit $LASTEXITCODE)" -ForegroundColor Red
        return $false
    }
    Write-Host "[$module] done" -ForegroundColor Green
    return $true
}

$ok = $true
$loaders = @()
if ($Loader -eq "all") { $loaders = @("fabric", "neoforge") }
elseif ($Loader -eq "fabric" -or $Loader -eq "neoforge") { $loaders = @($Loader) }
else { Write-Error "未知 Loader: $Loader（可选 all/fabric/neoforge）" }

foreach ($run in 1..$Runs) {
    Write-Host "`n===== Run $run/$Runs =====" -ForegroundColor Yellow
    foreach ($m in $loaders) {
        if (-not (Invoke-Test $m $Suite)) { $ok = $false }
    }
}

if ($ok) {
    Write-Host "`n全部 $Runs 轮套件通过" -ForegroundColor Green
    exit 0
} else {
    Write-Host "`n有套件失败，见上方日志（可能 flaky，用 -Runs N 复跑验证）" -ForegroundColor Red
    exit 1
}
