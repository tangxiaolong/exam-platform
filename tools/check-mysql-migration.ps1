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

Write-Host "==> 清理可能残留的演练容器" -ForegroundColor Cyan
docker rm -f $container 2>$null | Out-Null

Write-Host "==> 启动 $image（端口 $port）" -ForegroundColor Cyan
docker run -d --name $container `
    -e MYSQL_ROOT_PASSWORD=root `
    -e MYSQL_DATABASE=exam_platform `
    -p "$port:3306" `
    $image | Out-Null

try {
    Write-Host "==> 等待 MySQL 就绪" -ForegroundColor Cyan
    $ready = $false
    for ($i = 0; $i -lt 60; $i++) {
        $log = docker logs $container 2>&1 | Out-String
        if ($log -match 'ready for connections') {
            $ready = $true
            break
        }
        Start-Sleep -Seconds 2
    }
    if (-not $ready) {
        throw "MySQL 在 120 秒内未就绪，容器日志：`n$(docker logs $container 2>&1 | Out-String)"
    }

    Write-Host "==> 执行迁移演练（Flyway 在真实 MySQL 8 上跑全部迁移脚本）" -ForegroundColor Cyan
    Push-Location (Join-Path $root 'backend')
    try {
        mvn -B -ntp test `
            -Dtest=MySqlMigrationSmokeTest `
            -Dsurefire.failIfNoSpecifiedTests=false `
            "-Dmysql.test.url=jdbc:mysql://127.0.0.1:$port/exam_platform?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&characterEncoding=utf8" `
            -Dmysql.test.user=root `
            -Dmysql.test.password=root
        if ($LASTEXITCODE -ne 0) {
            throw "迁移演练失败（退出码 $LASTEXITCODE），迁移脚本无法在 MySQL 8 上执行"
        }
    }
    finally {
        Pop-Location
    }

    Write-Host "==> 迁移后的表结构（抽查）" -ForegroundColor Cyan
    docker exec $container mysql -uroot -proot exam_platform -e `
        "SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank; SHOW TABLES;"

    Write-Host "OK：全部迁移脚本在 MySQL 8 上迁移通过" -ForegroundColor Green
}
finally {
    Write-Host "==> 销毁演练容器" -ForegroundColor Cyan
    docker rm -f $container 2>$null | Out-Null
}
