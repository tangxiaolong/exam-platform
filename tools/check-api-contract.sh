#!/usr/bin/env bash
# 接口契约校验（Linux / macOS 版本）
# 与 tools/check-api-contract.ps1 等价：校验前端声明的每个接口路径都能在后端 Controller 找到映射。
# 用法：bash tools/check-api-contract.sh    （失败时退出码为 1）
#
# 说明：CI 默认跑 check-api-contract.ps1（pwsh），本脚本为本地 Linux/macOS 备用实现。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

workdir="$(mktemp -d)"
trap 'rm -rf "$workdir"' EXIT

# 1) 后端映射：@RequestMapping 类级前缀 + 各方法的 @Get/Post/Put/DeleteMapping
: > "$workdir/back_raw.txt"
while IFS= read -r -d '' f; do
  # 类级前缀（取第一条）
  base=$(sed -n 's/.*@RequestMapping("\([^"]*\)").*/\1/p' "$f" | head -n 1)
  # 方法级路径（可能不存在参数，此时 sub 为空）
  sed -n 's/.*@\{1\}\(Get\|Post\|Put\|Delete\)Mapping("\([^"]*\)").*/\2/p' "$f" \
    | while IFS= read -r sub; do
        printf '%s%s\n' "$base" "$sub"
      done
  # 无参数形式：@GetMapping 后紧跟换行或空格（避免与带参数重复计入）
  grep -oE '@(Get|Post|Put|Delete)Mapping[[:space:]]*$' "$f" >/dev/null 2>&1 || true
done < <(find backend/src/main/java -name '*Controller.java' -print0)

sed -E 's/\{[^}]+\}/{id}/g; s#^/v1##' "$workdir/back_raw.txt" \
  | awk '{ print ($0 == "") ? "/" : $0 }' | sort -u > "$workdir/back.txt"

# 2) 管理端声明的 url（axios baseURL 已带 /api/v1，路径不带 /v1）
grep -oE "url:[[:space:]]*['\"\`][^'\"\`]+" admin-web/src/api/index.ts \
  | sed -E "s/url:[[:space:]]*['\"\`]//; s/\\\$\{[^}]+\}/{id}/g" | sort -u > "$workdir/front.txt"

# 3) 小程序 request({ url }) 调用（排除页面跳转路径与查询串）
: > "$workdir/mp.txt"
while IFS= read -r -d '' f; do
  grep -oE "url:[[:space:]]*['\"\`][^'\"\`]+" "$f" || true
done < <(find miniprogram -name '*.js' -print0) \
  | sed -E "s/url:[[:space:]]*['\"\`]//; s/\\\$\{[^}]+\}/{id}/g; s/\?.*$//" \
  | grep -E '^/' | grep -v '^/pages' | sort -u > "$workdir/mp.txt"

check() {
  local label="$1" file="$2" bad=0
  while IFS= read -r p; do
    [ -z "$p" ] && continue
    if [[ "$p" == */ ]]; then
      grep -q "^${p}" "$workdir/back.txt" || { echo "  [$label UNMATCHED] $p"; bad=$((bad+1)); }
    else
      grep -qxF "$p" "$workdir/back.txt" || { echo "  [$label UNMATCHED] $p"; bad=$((bad+1)); }
    fi
  done < "$file"
  return "$bad"
}

fail=0
echo "backend mappings: $(wc -l < "$workdir/back.txt" | tr -d ' ')"
echo "admin-web calls: $(wc -l < "$workdir/front.txt" | tr -d ' ')"
if ! check admin-web "$workdir/front.txt"; then fail=1; fi
echo "miniprogram calls: $(wc -l < "$workdir/mp.txt" | tr -d ' ')"
if ! check miniprogram "$workdir/mp.txt"; then fail=1; fi

if [ "$fail" -eq 0 ]; then
  echo "ALL PATHS MATCHED"
else
  echo "CONTRACT CHECK FAILED"
fi
exit "$fail"
