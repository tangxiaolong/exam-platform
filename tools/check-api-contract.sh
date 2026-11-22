#!/usr/bin/env bash
# 接口契约校验（Linux / macOS / CI 版本）
# 与 tools/check-api-contract.ps1 等价：校验前端声明的每个接口路径都能在后端 Controller 找到映射。
# 用法：bash tools/check-api-contract.sh    （失败时退出码为 1）
#
# 两个实现必须给出同一组数字（backend mappings / admin-web calls / miniprogram calls）。
# 曾出现过的情况：本脚本把 while 循环的输出直接打到 stdout，而后续步骤读的是一个刚被
# `: >` 清空的文件 → `backend mappings: 0`，于是所有路径都报 UNMATCHED。CI 走的就是这个
# 脚本，所以它必须是「真跑过」的，不能只是 .ps1 的等价物。
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT" || exit 2

JAVA_DIR="backend/src/main/java"
API_FILE="admin-web/src/api/index.ts"
MP_DIR="miniprogram"

for required in "$JAVA_DIR" "$API_FILE" "$MP_DIR"; do
  if [ ! -e "$required" ]; then
    echo "缺少契约校验所需的路径：$required（脚本根目录推导为 $ROOT）" >&2
    exit 2
  fi
done

workdir="$(mktemp -d)"
trap 'rm -rf "$workdir"' EXIT

# 路径归一化：去查询串、把占位符统一成 {p}、去掉 /v1 前缀（axios baseURL 已带 /api/v1）
#
# 占位符必须泛指而不是只认 ${...}：后端写 @GetMapping("/attempts/{attemptId}/resume")，
# 前端写 `url: \`/attempts/${attemptId}/resume\``，归一化后后端是 {attemptId}、前端是 {id}，
# 逐字比较必然全不匹配。校验的是「路径结构是否一致」，参数名不一致不属于契约漂移。
normalize_awk='
function norm(p,   q) {
  q = p
  sub(/\?.*$/, "", q)
  gsub(/\$\{[^}]*\}/, "{p}", q)
  gsub(/\{[^}]*\}/, "{p}", q)
  sub(/^\/v1/, "", q)
  if (q == "") q = "/"
  return q
}
'

# ---------------------------------------------------------------- 1) 后端映射
# 类级 @RequestMapping("前缀") + 各方法的 @Get/Post/Put/DeleteMapping("子路径")
find "$JAVA_DIR" -name '*Controller.java' -print0 \
  | while IFS= read -r -d '' f; do
      awk "$normalize_awk"'
        BEGIN { base = "" }
        {
          line = $0
          if (base == "" && match(line, /@RequestMapping\("[^"]*"\)/)) {
            s = substr(line, RSTART, RLENGTH)
            sub(/^@RequestMapping\("/, "", s)
            sub(/"\)$/, "", s)
            base = s
          }
          # 一行里可能有多个映射注解，逐个消费，避免只抓到第一个
          rest = line
          while (match(rest, /@(Get|Post|Put|Delete)Mapping/)) {
            after = substr(rest, RSTART + RLENGTH)
            sub_path = ""
            if (after ~ /^[ \t]*\(/) {
              if (match(after, /^[ \t]*\("[^"]*"\)/)) {
                s = substr(after, RSTART, RLENGTH)
                gsub(/^[ \t]*\(|\)$/, "", s)
                gsub(/"/, "", s)
                sub_path = s
              }
            }
            print norm(base sub_path)
            rest = after
          }
        }
      ' "$f"
    done > "$workdir/back_raw.txt"

grep -E '^/' "$workdir/back_raw.txt" | sort -u > "$workdir/back.txt" || true

# ---------------------------------------------------------------- 2) 前端声明的 url
# url: '...' / url: "..." / url: `...` 三种引号都要认；引号后取到同字符为止
extract_urls_awk='
function norm(p,   q) {
  q = p
  sub(/\?.*$/, "", q)
  gsub(/\$\{[^}]*\}/, "{p}", q)
  gsub(/\{[^}]*\}/, "{p}", q)
  sub(/^\/v1/, "", q)
  if (q == "") q = "/"
  return q
}
{
  line = $0
  while (match(line, /url:[ \t]*./)) {
    quote = substr(line, RSTART + RLENGTH - 1, 1)
    after = substr(line, RSTART + RLENGTH)
    closing = index(after, quote)
    if (closing == 0) break
    print norm(substr(after, 1, closing - 1))
    line = substr(after, closing + 1)
  }
}
'

awk "$extract_urls_awk" "$API_FILE" | sort -u > "$workdir/front.txt"

# 小程序：只看以 / 开头的接口路径，排除页面跳转（/pages/...）
find "$MP_DIR" -name '*.js' -print0 \
  | while IFS= read -r -d '' f; do awk "$extract_urls_awk" "$f"; done \
  | grep -E '^/' | grep -v '^/pages' | sort -u > "$workdir/mp.txt" || true

# ---------------------------------------------------------------- 3) 比对
matched=0
unmatched=0
: > "$workdir/bad.txt"

compare() {
  local label="$1" file="$2" p
  while IFS= read -r p; do
    [ -z "$p" ] && continue
    if [ "${p%/}" != "$p" ]; then
      # 以 / 结尾表示「以它为前缀」，与 .ps1 的 StartsWith 语义一致
      if grep -qF -- "$p" "$workdir/back.txt"; then
        matched=$((matched + 1))
      else
        echo "  [$label UNMATCHED] $p" >> "$workdir/bad.txt"
        unmatched=$((unmatched + 1))
      fi
    else
      if grep -qxF -- "$p" "$workdir/back.txt"; then
        matched=$((matched + 1))
      else
        echo "  [$label UNMATCHED] $p" >> "$workdir/bad.txt"
        unmatched=$((unmatched + 1))
      fi
    fi
  done < "$file"
}

back_count=$(grep -c . "$workdir/back.txt" || true)
front_count=$(grep -c . "$workdir/front.txt" || true)

echo "backend mappings: ${back_count:-0}"

matched=0
unmatched=0
compare admin-web "$workdir/front.txt"
echo "admin-web calls: ${front_count:-0}, matched: $matched, unmatched: $unmatched"
front_bad="$unmatched"

mp_count=$(grep -c . "$workdir/mp.txt" || true)

matched=0
unmatched=0
compare miniprogram "$workdir/mp.txt"
echo "miniprogram calls: ${mp_count:-0}, matched: $matched, unmatched: $unmatched"
mp_bad="$unmatched"

if [ -s "$workdir/bad.txt" ]; then
  cat "$workdir/bad.txt"
fi

if [ "$front_bad" -eq 0 ] && [ "$mp_bad" -eq 0 ]; then
  echo "ALL PATHS MATCHED"
  exit 0
fi

echo "CONTRACT CHECK FAILED"
exit 1
