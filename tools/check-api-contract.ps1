$ErrorActionPreference = 'Stop'

# 路径必须从脚本位置推导，不能写死：CI 在 ubuntu-latest 上以 pwsh 跑本脚本，
# 写死 Windows 盘符会让 Get-ChildItem 直接抛错，job 必红
$root = Split-Path -Parent $PSScriptRoot
$javaDir = [System.IO.Path]::Combine($root, 'backend', 'src', 'main', 'java')
$apiTsPath = [System.IO.Path]::Combine($root, 'admin-web', 'src', 'api', 'index.ts')
$mpDir = [System.IO.Path]::Combine($root, 'miniprogram')

foreach ($required in @($javaDir, $apiTsPath, $mpDir)) {
    if (-not (Test-Path $required)) {
        Write-Error "缺少契约校验所需的路径：$required（脚本根目录推导为 $root）"
    }
}

function Strip-Path([string]$p) {
    $p = $p -split '\?' | Select-Object -First 1
    $p = [regex]::Replace($p, '\$\{[^}]+\}', '{id}')
    $p = $p -replace '\{\d+\}', '{id}'
    return $p
}

# ---------- backend mappings ----------
$back = @()
Get-ChildItem -Path $javaDir -Recurse -Filter *Controller.java | ForEach-Object {
    $text = Get-Content $_.FullName -Raw
    $cm = [regex]::Match($text, '@RequestMapping\("([^"]+)"\)')
    $base = ''
    if ($cm.Success) { $base = $cm.Groups[1].Value }
    foreach ($m in [regex]::Matches($text, '@(?:Get|Post|Put|Delete)Mapping(?:\("([^"]*)"\))?')) {
        $path = $base + $m.Groups[1].Value
        $path = Strip-Path $path
        if ($path.StartsWith('/v1')) { $path = $path.Substring(3) }
        if ($path -eq '') { $path = '/' }
        $back += $path
    }
}
$back = $back | Sort-Object -Unique

# ---------- admin-web ----------
$apiTs = Get-Content $apiTsPath -Raw
$front = @()
foreach ($m in [regex]::Matches($apiTs, "url:\s*[`"'']([^`"'']+)[`"'']")) {
    $front += Strip-Path $m.Groups[1].Value
}
$front = $front | Sort-Object -Unique

# ---------- miniprogram ----------
$mp = @()
Get-ChildItem -Path $mpDir -Recurse -Filter *.js | ForEach-Object {
    $text = Get-Content $_.FullName -Raw
    foreach ($m in [regex]::Matches($text, "url:\s*[`"'']([^`"'']+)[`"'']")) {
        $p = Strip-Path $m.Groups[1].Value
        if ($p.StartsWith('/pages')) { continue }
        if (-not $p.StartsWith('/')) { continue }
        $mp += $p
    }
}
$mp = $mp | Sort-Object -Unique

function Resolve-Paths($paths) {
    $ok = @(); $bad = @()
    foreach ($p in $paths) {
        if ($p.EndsWith('/')) {
            $hit = $back | Where-Object { $_.StartsWith($p) }
            if ($hit) { $ok += $p } else { $bad += $p }
        }
        elseif ($back -contains $p) { $ok += $p }
        else { $bad += $p }
    }
    return [pscustomobject]@{ Ok = $ok; Bad = $bad }
}

$frontResult = Resolve-Paths $front
$mpResult = Resolve-Paths $mp

$lines = @()
$lines += "backend mappings: $($back.Count)"
$lines += "admin-web calls: $($front.Count), matched: $($frontResult.Ok.Count), unmatched: $($frontResult.Bad.Count)"
$frontResult.Bad | ForEach-Object { $lines += "  [admin-web UNMATCHED] $_" }
$lines += "miniprogram calls: $($mp.Count), matched: $($mpResult.Ok.Count), unmatched: $($mpResult.Bad.Count)"
$mpResult.Bad | ForEach-Object { $lines += "  [miniprogram UNMATCHED] $_" }

$lines | ForEach-Object { Write-Output $_ }
$exit = if ($frontResult.Bad.Count -eq 0 -and $mpResult.Bad.Count -eq 0) { 0 } else { 1 }
exit $exit
