# mocktest 双端自动化测试编排脚本
#
# 用法：
#   ./scripts/run-tests.ps1                 # 双端全部套件（suite=all）
#   ./scripts/run-tests.ps1 -Suite api-smoke  # 双端指定套件
#   ./scripts/run-tests.ps1 -Loader fabric    # 只跑 fabric
#   ./scripts/run-tests.ps1 -Loader neoforge  # 只跑 neoforge
#
# 每个 loader 跑完在 fabric|neoforge/runs/client/test-results/<suite>.json 写结果，
# 全部跑完自动退出。任一端失败脚本返回非 0。

param(
    [string]$Suite = "all",
    [string]$Loader = "all"   # all | fabric | neoforge
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

foreach ($m in $loaders) {
    if (-not (Invoke-Test $m $Suite)) { $ok = $false }
}

if ($ok) {
    Write-Host "`n全部套件通过" -ForegroundColor Green
    exit 0
} else {
    Write-Host "`n有套件失败，见上方日志" -ForegroundColor Red
    exit 1
}
