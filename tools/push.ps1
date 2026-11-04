#requires -version 3
<#
    一键把本仓库推送到 GitHub（自己执行，token 不经过任何第三方）。

    用法（在 enterprise-rag 目录下）：
        powershell -NoProfile -ExecutionPolicy Bypass -File tools\push.ps1
    或者直接双击 tools\push.bat

    需要：GitHub Personal Access Token
      - classic：勾选 repo
      - fine-grained：勾选 Contents = Read and write
    token 只在这次运行的进程内存里，不写进 .git/config；成功后存入 Windows 凭据管理器，
    以后 git push 不用再输。想清掉：cmdkey /delete:git:https://github.com
#>
param(
    [string]$RepoName = "enterprise-rag-demo",
    [string]$Owner = "",
    [switch]$Private
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $repoRoot

function Find-Python {
    $cands = @(
        (Join-Path $env:USERPROFILE ".dsh\dsh-runtimes\dsh-primary-runtime\dependencies\python\python.exe"),
        "python.exe",
        "py.exe"
    )
    foreach ($c in $cands) {
        $cmd = Get-Command $c -ErrorAction SilentlyContinue
        if ($cmd) { return $cmd.Source }
    }
    return $null
}

Write-Host ""
Write-Host "=== 推送到 GitHub ===" -ForegroundColor Cyan
Write-Host "本地仓库：$repoRoot"
Write-Host ""

if (-not (Test-Path (Join-Path $repoRoot ".git"))) {
    Write-Host "[ERROR] 这个目录不是 git 仓库。" -ForegroundColor Red
    exit 1
}
$branch = (git rev-parse --abbrev-ref HEAD).Trim()
if (-not $branch) { $branch = "main" }

$secure = Read-Host -Prompt "粘贴 GitHub Personal Access Token（输入时不显示）" -AsSecureString
$bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
$pat = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr)
[Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)
if ([string]::IsNullOrWhiteSpace($pat)) {
    Write-Host "[ERROR] 没有输入 token。" -ForegroundColor Red
    exit 1
}
$pat = $pat.Trim()

$py = Find-Python
if (-not $py) {
    Write-Host "[ERROR] 找不到 python。你可以在网页上手动建空仓库 $RepoName，然后执行：" -ForegroundColor Red
    Write-Host "  git remote add origin https://github.com/<你的用户名>/$RepoName.git"
    Write-Host "  git push -u origin $branch"
    exit 1
}

$bootstrap = Join-Path $PSScriptRoot "gh_bootstrap.py"
$env:GH_PAT = $pat
$env:GH_REPO = $RepoName
$env:GH_OWNER = $Owner
$env:GH_PRIVATE = if ($Private) { "1" } else { "0" }
try {
    $out = & $py $bootstrap $RepoName 2>&1
} finally {
    Remove-Item Env:GH_PAT, Env:GH_REPO, Env:GH_OWNER, Env:GH_PRIVATE -Force -ErrorAction SilentlyContinue
}
foreach ($line in $out) { Write-Host "  $line" }
if ($LASTEXITCODE -ne 0) {
    Write-Host "[ERROR] GitHub API 调用失败（token 无效 / 权限不够 / 网络不通）。" -ForegroundColor Red
    exit $LASTEXITCODE
}

$login = ($out | Where-Object { $_ -like "USER *" } | Select-Object -First 1) -replace "^USER ", ""
$clone = ($out | Where-Object { $_ -like "CLONE *" } | Select-Object -First 1) -replace "^CLONE ", ""
if (-not $login) {
    Write-Host "[ERROR] 没能解析出用户名。" -ForegroundColor Red
    exit 1
}
if (-not $clone) { $clone = "https://github.com/$login/$RepoName.git" }

git remote remove origin 2>$null | Out-Null
$ErrorActionPreference = "Continue"
git remote add origin $clone
$ErrorActionPreference = "Stop"

# 用 GIT_ASKPASS 传凭证：token 不会写进 .git/config
$askpass = Join-Path $env:TEMP ("rag_askpass_" + [guid]::NewGuid().ToString("N") + ".bat")
$batLines = @(
    '@echo off',
    'echo %~1 | findstr /I "username" >nul',
    'if errorlevel 1 (echo %GH_ASK_PASS%) else (echo %GH_ASK_USER%)'
)
try {
    Set-Content -Path $askpass -Value $batLines -Encoding ASCII
    $env:GIT_ASKPASS = $askpass
    $env:GH_ASK_USER = $login
    $env:GH_ASK_PASS = $pat
    Write-Host ""
    Write-Host "推送 $branch -> $clone" -ForegroundColor Cyan
    git push -u origin $branch
    $pushOk = ($LASTEXITCODE -eq 0)
} finally {
    Remove-Item $askpass -Force -ErrorAction SilentlyContinue
    Remove-Item Env:GIT_ASKPASS, Env:GH_ASK_USER, Env:GH_ASK_PASS -Force -ErrorAction SilentlyContinue
}

if ($pushOk) {
    cmdkey /generic:git:https://github.com /user:$login /pass:$pat | Out-Null
    Write-Host ""
    Write-Host "完成 → https://github.com/$login/$RepoName" -ForegroundColor Green
    Write-Host "凭证已存入 Windows 凭据管理器；清除：cmdkey /delete:git:https://github.com" -ForegroundColor DarkGray
    exit 0
}

Write-Host ""
Write-Host "[FAILED] push 失败。常见原因：建仓库时勾了 README/LICENSE，远端已有提交。" -ForegroundColor Red
Write-Host "  解决 A：删掉远端仓库重建，一个文件都别勾，然后重跑本脚本" -ForegroundColor Yellow
Write-Host "  解决 B：git pull --rebase origin $branch --allow-unrelated-histories 后重跑" -ForegroundColor Yellow
exit 1
