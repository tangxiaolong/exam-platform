# 前端一致性自检（无需 Node，CI 与本地都能跑）
#
# 覆盖三件 vue-tsc 抓不到、却会在运行时静默出错的事：
#   1) 页面调用的 `xxxApi.method` 在 api/index.ts 里不存在（拼错方法名，运行时才 404/undefined）
#   2) 后端 DTO 字段与前端 interface 漂移（前端多出的字段恒为 undefined，少的字段永远不会被展示）
#   3) router 引用的 view 文件缺失、菜单项指向不存在的路由（点进去白屏）
#
# 字段比对按 `Foo` ↔ `FooView` 映射：后端 DTO 习惯带 View 后缀，前端 interface 通常不带。
# 在后端找不到对应 record 的 interface 会跳过（例如直接返回实体的接口），不算失败。
#
# 用法：powershell -File tools/check-frontend-consistency.ps1
#       pwsh -File tools/check-frontend-consistency.ps1      （失败时退出码 1）

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$apiDir = [System.IO.Path]::Combine($root, 'admin-web', 'src', 'api')
$apiFile = [System.IO.Path]::Combine($apiDir, 'index.ts')
$viewDir = [System.IO.Path]::Combine($root, 'admin-web', 'src', 'views')
$routerFile = [System.IO.Path]::Combine($root, 'admin-web', 'src', 'router', 'index.ts')
$layoutFile = [System.IO.Path]::Combine($root, 'admin-web', 'src', 'layout', 'AppLayout.vue')
$javaDir = [System.IO.Path]::Combine($root, 'backend', 'src', 'main', 'java')

foreach ($required in @($apiFile, $viewDir, $routerFile, $layoutFile, $javaDir)) {
    if (-not (Test-Path $required)) {
        Write-Error "缺少自检所需路径：$required（脚本根目录推导为 $root）"
    }
}

function Read-Text([string]$path) {
    # PowerShell 5.1 读无 BOM 的 UTF-8 会按本地代码页解码，中文会变乱码并打乱正则
    return Get-Content -LiteralPath $path -Raw -Encoding UTF8
}

$problems = @()
$notes = @()

# ---------------------------------------------------------------- 1) api 方法引用
$apiText = Read-Text $apiFile
$apiObjects = @{}
foreach ($m in [regex]::Matches($apiText, '(?m)^export const (\w+Api) = \{([\s\S]*?)^\}', 'Multiline')) {
    $name = $m.Groups[1].Value
    $body = $m.Groups[2].Value
    $methods = @()
    foreach ($f in [regex]::Matches($body, '(?m)^\s{2}(\w+):')) {
        $methods += $f.Groups[1].Value
    }
    $apiObjects[$name] = $methods
}

$vueFiles = Get-ChildItem -Path ([System.IO.Path]::Combine($root, 'admin-web', 'src')) -Recurse -Filter *.vue
foreach ($file in $vueFiles) {
    $text = Read-Text $file.FullName
    foreach ($m in [regex]::Matches($text, '(\w+Api)\.(\w+)\(')) {
        $obj = $m.Groups[1].Value
        $method = $m.Groups[2].Value
        if (-not $apiObjects.ContainsKey($obj)) {
            $problems += "$($file.Name): 引用了未导出的 api 对象 $obj"
        }
        elseif ($apiObjects[$obj] -notcontains $method) {
            $problems += "$($file.Name): $obj.$method 不存在（$obj 提供：$($apiObjects[$obj] -join ', ')）"
        }
    }
}

# ---------------------------------------------------------------- 2) DTO 字段漂移
$recordFields = @{}
Get-ChildItem -Path $javaDir -Recurse -Filter *.java | Where-Object { $_.FullName -match '\\dto\\' } | ForEach-Object {
    $text = Read-Text $_.FullName
    foreach ($m in [regex]::Matches($text, 'public\s+record\s+(\w+)\s*\(([\s\S]*?)\)\s*(?:implements|\{)', 'Multiline')) {
        $recordName = $m.Groups[1].Value
        $params = $m.Groups[2].Value
        $fields = @()
        foreach ($p in ($params -split ',')) {
            $clean = [regex]::Replace($p, '<[^>]*>', '')      # 去掉泛型
            $clean = [regex]::Replace($clean, '\(.*?\)', '')   # 去掉注解参数
            $clean = ($clean -replace '\s+', ' ').Trim()
            if ($clean -match '(\w+)$') {
                $fields += $Matches[1]
            }
        }
        $recordFields[$recordName] = $fields
    }
}

foreach ($m in [regex]::Matches($apiText, '(?m)^export interface (\w+) \{([\s\S]*?)^\}', 'Multiline')) {
    $iface = $m.Groups[1].Value
    $body = $m.Groups[2].Value
    $ifaceFields = @()
    foreach ($f in [regex]::Matches($body, '(?m)^\s{2}(\w+)\??:')) {
        $ifaceFields += $f.Groups[1].Value
    }

    $record = $null
    if ($recordFields.ContainsKey($iface)) {
        $record = $iface
    }
    elseif ($recordFields.ContainsKey($iface + 'View')) {
        $record = $iface + 'View'
    }
    else {
        continue   # 后端没有同名 DTO（直接返回实体 / 纯前端结构），跳过
    }

    $backend = $recordFields[$record]

    $ghosts = $ifaceFields | Where-Object { $backend -notcontains $_ }
    if ($ghosts) {
        $problems += "接口 $iface ↔ 后端 $record：前端有后端没有的字段 [$(($ghosts | Sort-Object) -join ', ')]（运行时恒为 undefined）"
    }

    $missing = $backend | Where-Object { $ifaceFields -notcontains $_ }
    if ($missing) {
        $notes += "接口 $iface ↔ 后端 $record：后端有前端未接入的字段 [$(($missing | Sort-Object) -join ', ')]"
    }
}

# ---------------------------------------------------------------- 3) 路由与菜单
$routerText = Read-Text $routerFile
$existingViews = (Get-ChildItem -Path $viewDir -Filter *.vue).BaseName

foreach ($m in [regex]::Matches($routerText, "import\('@/views/(\w+)\.vue'\)")) {
    $view = $m.Groups[1].Value
    if ($existingViews -notcontains $view) {
        $problems += "router: 引用了不存在的视图 $view.vue（点进去白屏）"
    }
}

$routerPaths = @()
foreach ($m in [regex]::Matches($routerText, "(?m)^\s{8}path: '([\w-]+)',")) {
    $routerPaths += $m.Groups[1].Value
}

$layoutText = Read-Text $layoutFile
foreach ($m in [regex]::Matches($layoutText, 'index="/([\w-]+)"')) {
    $menuPath = $m.Groups[1].Value
    if ($routerPaths -notcontains $menuPath) {
        $problems += "菜单项 /$menuPath 没有对应路由（点了会落到 404 重定向）"
    }
}

$orphanViews = $existingViews | Where-Object {
    $view = $_
    $referenced = $routerText -match ("@/views/" + [regex]::Escape($view) + "\.vue")
    -not $referenced
}
if ($orphanViews) {
    $notes += "存在未被路由引用的视图：$($orphanViews -join ', ')"
}

# ---------------------------------------------------------------- 输出
Write-Output "api 对象: $($apiObjects.Keys.Count) 个（$((($apiObjects.Keys | Sort-Object) -join ', '))）"
Write-Output "后端 DTO record: $($recordFields.Keys.Count) 个"
Write-Output "视图: $($existingViews.Count) 个，路由条目: $($routerPaths.Count) 个"

foreach ($n in $notes) {
    Write-Output "  [提示] $n"
}

if ($problems.Count -eq 0) {
    Write-Output "FRONTEND CONSISTENCY OK"
    exit 0
}

foreach ($p in $problems) {
    Write-Output "  [不一致] $p"
}
Write-Output "FRONTEND CONSISTENCY FAILED（$($problems.Count) 处）"
exit 1
