#!/usr/bin/env bash
# 前端一致性自检（Linux / macOS / CI 版本）
# 与 tools/check-frontend-consistency.ps1 等价：覆盖三件 vue-tsc 抓不到、却会在运行时静默出错的事：
#   1) 页面调用的 `xxxApi.method` 在 api/index.ts 里不存在（拼错方法名，运行时才 404/undefined）
#   2) 后端 DTO 字段与前端 interface 漂移（前端多出的字段恒为 undefined，少的字段永远不会被展示）
#   3) router 引用的 view 文件缺失、菜单项指向不存在的路由（点进去白屏）
#
# 字段比对按 `Foo` ↔ `FooView` 映射：后端 DTO 习惯带 View 后缀，前端 interface 通常不带。
# 在后端找不到对应 record 的 interface 会跳过（例如直接返回实体的接口），不算失败。
#
# 用法：bash tools/check-frontend-consistency.sh        （失败时退出码 1）
#
# CI 里两个版本都可用；为什么还要 bash 版：`pwsh` 不是 ubuntu runner 的硬保证，
# 一旦镜像变化（或改用精简镜像）就会整个 Job 挂掉，而这两个检查本身不依赖任何运行时。
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT" || exit 2

API_FILE="admin-web/src/api/index.ts"
VIEW_DIR="admin-web/src/views"
ROUTER_FILE="admin-web/src/router/index.ts"
LAYOUT_FILE="admin-web/src/layout/AppLayout.vue"
JAVA_DIR="backend/src/main/java"

for required in "$API_FILE" "$VIEW_DIR" "$ROUTER_FILE" "$LAYOUT_FILE" "$JAVA_DIR"; do
  if [ ! -e "$required" ]; then
    echo "缺少自检所需路径：$required（脚本根目录推导为 $ROOT）" >&2
    exit 2
  fi
done

workdir="$(mktemp -d)"
trap 'rm -rf "$workdir"' EXIT

problems="$workdir/problems.txt"
notes="$workdir/notes.txt"
: > "$problems"
: > "$notes"

# ---------------------------------------------------------------- 1) api 方法引用
awk '
  /^export const [A-Za-z0-9_]+Api = \{/ {
    obj = $0
    sub(/^export const /, "", obj)
    sub(/ = \{.*$/, "", obj)
    inblock = 1
    next
  }
  inblock == 1 && /^\}/ { inblock = 0; next }
  inblock == 1 {
    if (match($0, /^[ \t][ \t][A-Za-z0-9_]+:/)) {
      m = substr($0, RSTART, RLENGTH)
      gsub(/[ \t:]/, "", m)
      print obj "\t" m
    }
  }
' "$API_FILE" > "$workdir/api_methods.tsv"

api_objects=$(cut -f1 "$workdir/api_methods.tsv" | sort -u)

while IFS= read -r -d '' f; do
  base="${f##*/}"
  grep -oE '[A-Za-z0-9_]+Api\.[A-Za-z0-9_]+\(' "$f" 2>/dev/null \
    | sed 's/($//' \
    | while IFS= read -r ref; do
        obj="${ref%%.*}"
        method="${ref#*.}"
        if ! grep -qxF "$obj" <<< "$api_objects"; then
          echo "$base: 引用了未导出的 api 对象 $obj" >> "$problems"
        elif ! awk -F'\t' -v o="$obj" -v m="$method" '$1 == o && $2 == m { hit = 1 } END { exit !hit }' "$workdir/api_methods.tsv"; then
          provided=$(awk -F'\t' -v o="$obj" '$1 == o { print $2 }' "$workdir/api_methods.tsv" | paste -sd ',' - | sed 's/,/, /g')
          echo "$base: $obj.$method 不存在（$obj 提供：$provided）" >> "$problems"
        fi
      done
done < <(find admin-web/src -name '*.vue' -print0)

# ---------------------------------------------------------------- 2) DTO 字段漂移
# 只认 `public record`（后端 DTO 全是 record）；参数里可能带注解参数（@NotBlank(message = "...")），
# 所以先按「括号深度为 0 的逗号」切分参数，再逐段剥掉注解与泛型，取最后一个标识符当字段名。
find "$JAVA_DIR" -path '*/dto/*' -name '*.java' -print0 \
  | while IFS= read -r -d '' f; do
      awk '
        function emit(tok,    t) {
          t = tok
          while (match(t, /@[A-Za-z0-9_]+\([^()]*\)/)) {
            t = substr(t, 1, RSTART - 1) substr(t, RSTART + RLENGTH)
          }
          gsub(/@[A-Za-z0-9_]+/, "", t)
          gsub(/<[^<>]*>/, "", t)
          gsub(/\[\]/, "", t)
          gsub(/^[ \t]+/, "", t)
          gsub(/[ \t]+$/, "", t)
          if (t == "") return
          if (match(t, /[A-Za-z0-9_]+$/)) print name "\t" substr(t, RSTART, RLENGTH)
        }
        function close_record(   i, n, c, depth, cur) {
          sub(/\)[ \t]*(implements[^{]*)?\{?[ \t]*$/, "", body)
          n = length(body)
          depth = 0
          cur = ""
          for (i = 1; i <= n; i++) {
            c = substr(body, i, 1)
            if (c == "(") { depth++ }
            else if (c == ")") { if (depth > 0) depth-- }
            if (c == "," && depth == 0) { emit(cur); cur = ""; continue }
            cur = cur c
          }
          emit(cur)
          inrec = 0
          body = ""
        }
        BEGIN { inrec = 0; name = ""; body = "" }
        inrec == 0 && match($0, /public[ \t]+record[ \t]+[A-Za-z0-9_]+[ \t]*\(/) {
          name = substr($0, RSTART, RLENGTH)
          sub(/^public[ \t]+record[ \t]+/, "", name)
          sub(/[ \t]*\($/, "", name)
          body = substr($0, RSTART + RLENGTH)
          inrec = 1
          if (body ~ /\)[ \t]*(implements[^{]*)?\{?[ \t]*$/) close_record()
          next
        }
        inrec == 1 {
          body = body " " $0
          if ($0 ~ /^[ \t]*\)[ \t]*(implements[^{]*)?\{?[ \t]*$/) close_record()
        }
      ' "$f"
    done > "$workdir/dto_fields.tsv"

awk '
  /^export interface [A-Za-z0-9_]+ \{/ {
    iface = $0
    sub(/^export interface /, "", iface)
    sub(/ \{.*$/, "", iface)
    inb = 1
    next
  }
  inb == 1 && /^\}/ { inb = 0; next }
  inb == 1 {
    if (match($0, /^[ \t][ \t][A-Za-z0-9_]+\??:/)) {
      f = substr($0, RSTART, RLENGTH)
      sub(/[ \t:?]+$/, "", f)
      gsub(/^[ \t]+/, "", f)
      print iface "\t" f
    }
  }
' "$API_FILE" > "$workdir/iface_fields.tsv"

dto_records=$(cut -f1 "$workdir/dto_fields.tsv" | sort -u)

cut -f1 "$workdir/iface_fields.tsv" | awk '!seen[$0]++' \
  | while IFS= read -r iface; do
      record=""
      if grep -qxF "$iface" <<< "$dto_records"; then
        record="$iface"
      elif grep -qxF "${iface}View" <<< "$dto_records"; then
        record="${iface}View"
      else
        continue   # 后端没有同名 DTO（直接返回实体 / 纯前端结构），跳过
      fi

      awk -F'\t' -v r="$record" '$1 == r { print $2 }' "$workdir/dto_fields.tsv" | sort -u > "$workdir/backend_fields.txt"
      awk -F'\t' -v i="$iface" '$1 == i { print $2 }' "$workdir/iface_fields.tsv" | sort -u > "$workdir/frontend_fields.txt"

      ghosts=$(comm -23 "$workdir/frontend_fields.txt" "$workdir/backend_fields.txt" | paste -sd ',' - | sed 's/,/, /g')
      missing=$(comm -13 "$workdir/frontend_fields.txt" "$workdir/backend_fields.txt" | paste -sd ',' - | sed 's/,/, /g')

      if [ -n "$ghosts" ]; then
        echo "接口 $iface ↔ 后端 $record：前端有后端没有的字段 [$ghosts]（运行时恒为 undefined）" >> "$problems"
      fi
      if [ -n "$missing" ]; then
        echo "接口 $iface ↔ 后端 $record：后端有前端未接入的字段 [$missing]" >> "$notes"
      fi
    done

# ---------------------------------------------------------------- 3) 路由与菜单
existing_views=$(find "$VIEW_DIR" -maxdepth 1 -name '*.vue' -exec basename {} .vue \; | sort)

grep -oE "import\('@/views/[A-Za-z0-9_]+\.vue'\)" "$ROUTER_FILE" 2>/dev/null \
  | sed -E "s#.*/views/([A-Za-z0-9_]+)\.vue.*#\1#" \
  | while IFS= read -r view; do
      if ! grep -qxF "$view" <<< "$existing_views"; then
        echo "router: 引用了不存在的视图 $view.vue（点进去白屏）" >> "$problems"
      fi
    done

router_paths=$(grep -oE "^[[:space:]]{8}path: '[A-Za-z0-9_-]+'," "$ROUTER_FILE" 2>/dev/null \
  | sed -E "s#.*path: '([^']+)',#\1#" | sort -u)

grep -oE 'index="/[A-Za-z0-9_-]+"' "$LAYOUT_FILE" 2>/dev/null \
  | sed -E 's#index="/([^"]+)"#\1#' \
  | while IFS= read -r menu_path; do
      if ! grep -qxF "$menu_path" <<< "$router_paths"; then
        echo "菜单项 /$menu_path 没有对应路由（点了会落到 404 重定向）" >> "$problems"
      fi
    done

orphan_views=""
for view in $existing_views; do
  if ! grep -qF "@/views/${view}.vue" "$ROUTER_FILE"; then
    orphan_views="$orphan_views${orphan_views:+, }${view}"
  fi
done
if [ -n "$orphan_views" ]; then
  echo "存在未被路由引用的视图：$orphan_views" >> "$notes"
fi

# ---------------------------------------------------------------- 输出
api_object_list=$(echo "$api_objects" | paste -sd ',' - | sed 's/,/, /g')
view_count=$(echo "$existing_views" | grep -c . )
route_count=$(echo "$router_paths" | grep -c . )

echo "api 对象: $(echo "$api_objects" | grep -c .) 个（$api_object_list）"
echo "后端 DTO record: $(echo "$dto_records" | grep -c .) 个"
echo "视图: $view_count 个，路由条目: $route_count 个"

while IFS= read -r n; do
  [ -n "$n" ] && echo "  [提示] $n"
done < "$notes"

if [ -s "$problems" ]; then
  while IFS= read -r p; do
    echo "  [不一致] $p"
  done < "$problems"
  echo "FRONTEND CONSISTENCY FAILED（$(grep -c . "$problems") 处）"
  exit 1
fi

echo "FRONTEND CONSISTENCY OK"
exit 0
