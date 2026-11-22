# MySQL 8 迁移演练（本地）
#
# 日常测试跑 H2 的 MySQL 兼容模式，抹掉的正是生产首次迁移最容易翻车的差异
# （索引长度上限、字符集/排序规则、时间精度、ALTER 回填）。这个脚本用 Docker 起一台
# 真 MySQL 8，把 V1..Vn 完整跑一遍，验完即销毁。
#
# 用法：pwsh tools/check-mysql-migration.ps1
# 前置：本机可用 docker。没有 Docker 时直接报错退出，不做「静默跳过」——
#       跳过会让人误以为演练过了。
$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$container = 'exam-platform-mysql-check'
$port = 33061
$image = 'mysql:8.0'

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    Write-Error "未找到 docker，无法执行 MySQL 迁移演练。请安装 Docker Desktop 后重试。"
}

# docker 把容器日志、mysql 客户端的密码警告都写到 stderr。脚本顶部是 'Stop'，
# 而 Windows PowerShell 5.1 会把原生命令的 stderr 当终止性错误（NativeCommandError），
# 于是「读日志等就绪」这一步永远等不到就绪、直接退出。统一走这个包装：
#   1) 期间把 ErrorActionPreference 放宽到 Continue；
#   2) 用退出码而不是 stderr 有无内容来判断成败；
#   3) 需要容忍失败（rm -f 清理残留容器）时显式传 -AllowFailure。
function Invoke-Docker {
    param(
        [Parameter(Mandatory = $true)][string[]]$DockerArgs,
        [switch]$AllowFailure
    )
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = & docker @DockerArgs 2>&1 | Out-String
        $exitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previous
    }
    if ($exitCode -ne 0 -and -not $AllowFailure) {
        throw "docker $($DockerArgs -join ' ') 失败（退出码 $exitCode）：`n$output"
    }
    return $output
}

Write-Host "==> 清理可能残留的演练容器" -ForegroundColor Cyan
Invoke-Docker -DockerArgs @('rm', '-f', $container) -AllowFailure | Out-Null

Write-Host "==> 启动 $image（端口 $port）" -ForegroundColor Cyan
# 参数写成数组而不是反引号续行：Windows PowerShell 5.1 不认「反引号 + LF」，
# 仓库里这些脚本是纯 LF（git 规范化后的结果），续行会静默失效 → docker 只收到 `run` 就报参数不足
$runArgs = @(
    'run', '-d', '--name', $container,
    '-e', 'MYSQL_ROOT_PASSWORD=root',
    '-e', 'MYSQL_DATABASE=exam_platform',
    '-p', "${port}:3306",
    $image
)
Invoke-Docker -DockerArgs $runArgs | Out-Null

try {
    Write-Host "==> 等待 MySQL 就绪" -ForegroundColor Cyan
    $ready = $false
    for ($i = 0; $i -lt 60; $i++) {
        $log = Invoke-Docker -DockerArgs @('logs', $container) -AllowFailure
        if ($log -match 'ready for connections') {
            $ready = $true
            break
        }
        Start-Sleep -Seconds 2
    }
    if (-not $ready) {
        $tail = Invoke-Docker -DockerArgs @('logs', '--tail', '50', $container) -AllowFailure
        throw "MySQL 在 120 秒内未就绪，容器日志：`n$tail"
    }

    Write-Host "==> 执行迁移演练（Flyway 在真实 MySQL 8 上跑全部迁移脚本）" -ForegroundColor Cyan
    Push-Location (Join-Path $root 'backend')
    try {
        # JDBC URL 里的 `&` 是 PowerShell → cmd 这条链路上的命令分隔符：
        # PowerShell 5.1 调 .cmd（mvn.cmd）时会把参数重新拼成命令行且不再加引号，
        # `useSSL=false&allowPublicKeyRetrieval=true` 会被 cmd 切成两条命令，
        # 报 "'allowPublicKeyRetrieval' 不是内部或外部命令"，Maven 只能拿到半截 URL。
        # 所以这里自己拼一条带双引号的命令行，交给 cmd 解析（bash 那边单引号本身安全，无需处理）。
        $jdbcUrl = "jdbc:mysql://127.0.0.1:$port/exam_platform?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&characterEncoding=utf8"
        $mvnCommand = 'mvn -B -ntp test -Dtest=MySqlMigrationSmokeTest -Dsurefire.failIfNoSpecifiedTests=false ' +
            '"-Dmysql.test.url=' + $jdbcUrl + '" -Dmysql.test.user=root -Dmysql.test.password=root'
        & cmd.exe /c $mvnCommand
        if ($LASTEXITCODE -ne 0) {
            throw "迁移演练失败（退出码 $LASTEXITCODE），迁移脚本无法在 MySQL 8 上执行"
        }
    }
    finally {
        Pop-Location
    }

    Write-Host "==> 迁移后的表结构（抽查）" -ForegroundColor Cyan
    $sql = 'SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank; SHOW TABLES;'
    Invoke-Docker -DockerArgs @('exec', $container, 'mysql', '-uroot', '-proot', 'exam_platform', '-e', $sql) |
        Write-Host

    Write-Host "OK：全部迁移脚本在 MySQL 8 上迁移通过" -ForegroundColor Green
}
finally {
    Write-Host "==> 销毁演练容器" -ForegroundColor Cyan
    Invoke-Docker -DockerArgs @('rm', '-f', $container) -AllowFailure | Out-Null
}
