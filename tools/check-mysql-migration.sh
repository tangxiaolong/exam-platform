#!/usr/bin/env bash
# MySQL 8 迁移演练（本地，Linux / macOS）
#
# 日常测试跑 H2 的 MySQL 兼容模式，抹掉的正是生产首次迁移最容易翻车的差异
# （索引长度上限、字符集/排序规则、时间精度、ALTER 回填）。这个脚本用 Docker 起一台
# 真 MySQL 8，把 V1..Vn 完整跑一遍，验完即销毁。
#
# 用法：bash tools/check-mysql-migration.sh
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
container="exam-platform-mysql-check"
port="33061"
image="mysql:8.0"

if ! command -v docker >/dev/null 2>&1; then
  echo "未找到 docker，无法执行 MySQL 迁移演练。请安装 Docker 后重试。" >&2
  exit 1
fi

cleanup() {
  echo "==> 销毁演练容器"
  docker rm -f "$container" >/dev/null 2>&1 || true
}
trap cleanup EXIT

echo "==> 清理可能残留的演练容器"
docker rm -f "$container" >/dev/null 2>&1 || true

echo "==> 启动 ${image}（端口 ${port}）"
docker run -d --name "$container" \
  -e MYSQL_ROOT_PASSWORD=root \
  -e MYSQL_DATABASE=exam_platform \
  -p "${port}:3306" \
  "$image" >/dev/null

echo "==> 等待 MySQL 就绪"
ready=""
for _ in $(seq 1 60); do
  if docker logs "$container" 2>&1 | grep -q "ready for connections"; then
    ready="yes"
    break
  fi
  sleep 2
done
if [ -z "$ready" ]; then
  echo "MySQL 在 120 秒内未就绪，容器日志：" >&2
  docker logs "$container" >&2
  exit 1
fi

echo "==> 执行迁移演练（Flyway 在真实 MySQL 8 上跑全部迁移脚本）"
cd "$root/backend"
mvn -B -ntp test \
  -Dtest=MySqlMigrationSmokeTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  "-Dmysql.test.url=jdbc:mysql://127.0.0.1:${port}/exam_platform?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&characterEncoding=utf8" \
  -Dmysql.test.user=root \
  -Dmysql.test.password=root

echo "==> 迁移后的表结构（抽查）"
docker exec "$container" mysql -uroot -proot exam_platform \
  -e "SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank; SHOW TABLES;"

echo "OK：全部迁移脚本在 MySQL 8 上迁移通过"
