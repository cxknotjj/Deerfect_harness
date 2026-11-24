param(
    [int]$Port = 8888
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $root

if (-not (Test-Path '.venv')) {
    Write-Host '[plugin-mem0-local] 创建 Python 虚拟环境 .venv ...'
    python -m venv .venv
    if ($LASTEXITCODE -ne 0) { throw 'python -m venv 失败：请确认已安装 Python 3.10+' }
}

$python = Join-Path $root '.venv\Scripts\python.exe'

Write-Host '[plugin-mem0-local] 安装依赖 ...'
& $python -m pip install --upgrade pip --quiet
& $python -m pip install -r requirements.txt --quiet
if ($LASTEXITCODE -ne 0) { throw '依赖安装失败' }

if (-not (Test-Path '.env')) {
    Copy-Item '.env.example' '.env'
    Write-Host '[plugin-mem0-local] 已生成 .env：请填写 MEM0_LLM_API_KEY / MEM0_EMBEDDER_API_KEY 后重新运行' -ForegroundColor Yellow
    exit 1
}

Write-Host "[plugin-mem0-local] 启动 http://127.0.0.1:$Port（Ctrl+C 停止）"
& $python -m uvicorn main:app --host 127.0.0.1 --port $Port

