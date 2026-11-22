# ============================================================================
# 启动考试平台后端服务（默认 8090 端口，h2 内存库）
#
# 乱码成因与对策（Windows 控制台默认 GBK 与 UTF-8 输出错位）：
#   1) chcp 65001              —— 控制台代码页切到 UTF-8
#   2) Console.OutputEncoding  —— PowerShell 解码 Java 输出流用 UTF-8
#   3) JAVA_TOOL_OPTIONS       —— Maven 派生的 Java 子进程 stdout/stderr 按 UTF-8
#   4) Start-Transcript        —— 全程日志自动录制到 logs\backend-时间戳.log
#
# 用法（在项目根目录的终端里执行）：
#   powershell -ExecutionPolicy Bypass -File tools\start-backend.ps1
#   powershell -ExecutionPolicy Bypass -File tools\start-backend.ps1 -Port 8091
#   powershell -ExecutionPolicy Bypass -File tools\start-backend.ps1 -Profile mysql   # 持久化 MySQL 库
#      （mysql 模式需先设置环境变量：$env:MYSQL_PASSWORD='你的密码'；库/账号默认见 application-mysql.yml）
#   powershell -ExecutionPolicy Bypass -File tools\start-backend.ps1 -NoLog
#   或直接双击 tools\start-backend.bat
# 停止：Ctrl+C
# ============================================================================
param(
    [int]$Port = 8090,
    [switch]$NoLog,
    # h2 = 内存库演示（重启重种）；mysql = 持久库，需先设置环境变量 MYSQL_PASSWORD
    [string]$Profile = 'h2'
)

# 注意：不要设 $ErrorActionPreference = 'Stop'——
# PowerShell 5.1 会把 Java 子进程写到 stderr 的行（Picked up JAVA_TOOL_OPTIONS）
# 当作 NativeCommandError 直接终止管道。

# —— 编码统一 ——
chcp 65001 | Out-Null
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
[Console]::InputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8
$env:MAVEN_OPTS = '-Dfile.encoding=UTF-8'
$env:JAVA_TOOL_OPTIONS = '-Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8'

$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$logDir = Join-Path $root 'logs'
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
$logFile = Join-Path $logDir ("backend-{0}.log" -f (Get-Date -Format 'yyyyMMdd-HHmmss'))

if ($Profile -eq 'mysql' -and -not $env:MYSQL_PASSWORD) {
    Write-Host "!! 警告：mysql 模式需要环境变量 MYSQL_PASSWORD（如 `$env:MYSQL_PASSWORD='你的密码'）" -ForegroundColor Yellow
}

Write-Host "== 启动后端服务  http://localhost:$Port/api  （profile: $Profile）" -ForegroundColor Cyan
if ($NoLog) {
    Write-Host "== 日志录制     已关闭（-NoLog）" -ForegroundColor DarkGray
} else {
    Write-Host "== 日志文件     $logFile" -ForegroundColor DarkGray
    Start-Transcript -Path $logFile -Force | Out-Null
}
Write-Host "== 停止方式     Ctrl+C" -ForegroundColor DarkGray
Write-Host ''

Push-Location (Join-Path $root 'backend')
try {
    & mvn -B -ntp spring-boot:run "-Dspring-boot.run.profiles=$Profile" "-Dspring-boot.run.arguments=--server.port=$Port"
}
finally {
    Pop-Location
    if (-not $NoLog) {
        Stop-Transcript | Out-Null
        Write-Host "== 日志已保存   $logFile" -ForegroundColor DarkGray
    }
}
