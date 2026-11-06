# 一键启动本地原型：起静态服务器 + 打开浏览器
$ErrorActionPreference = 'Stop'
$dir = $PSScriptRoot
$port = 8765

$py = (Get-Command python -ErrorAction SilentlyContinue).Source
if (-not $py) { $py = (Get-Command py -ErrorAction SilentlyContinue).Source }
if (-not $py) {
  Write-Host '没有找到 Python，请手动起服务器，例如：python -m http.server 8765 --bind 127.0.0.1' -ForegroundColor Yellow
  exit 1
}

Write-Host "目录：$dir"
Write-Host "地址：http://127.0.0.1:$port/index.html"
Write-Host '关闭本窗口即可停止服务器。' -ForegroundColor DarkGray

Start-Process "http://127.0.0.1:$port/index.html" -ErrorAction SilentlyContinue | Out-Null
& $py -m http.server $port --bind 127.0.0.1 --directory $dir
