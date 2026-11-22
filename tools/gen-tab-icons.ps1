 # 生成小程序 tabBar 图标（81x81 PNG，线条风，灰色/选中蓝两套）。
 # 运行：powershell -ExecutionPolicy Bypass -File tools\gen-tab-icons.ps1
Add-Type -AssemblyName System.Drawing

$out = Join-Path (Split-Path -Parent $PSScriptRoot) 'miniprogram\images\tab'
New-Item -ItemType Directory -Force -Path $out | Out-Null

function Invoke-Draw([string]$name, [string]$color, [scriptblock]$draw) {
    $bmp = New-Object System.Drawing.Bitmap(81, 81)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.Clear([System.Drawing.Color]::Transparent)
    $pen = New-Object System.Drawing.Pen([System.Drawing.ColorTranslator]::FromHtml($color), 5)
    $pen.StartCap = [System.Drawing.Drawing2D.LineCap]::Round
    $pen.EndCap = [System.Drawing.Drawing2D.LineCap]::Round
    $pen.LineJoin = [System.Drawing.Drawing2D.LineJoin]::Round
    & $draw $g $pen
    $bmp.Save((Join-Path $out ($name + '.png')), [System.Drawing.Imaging.ImageFormat]::Png)
    $g.Dispose()
    $bmp.Dispose()
}

$iconHome = {
    param($g, $pen)
    $g.DrawLines($pen, @(
            [System.Drawing.Point]::new(14, 42),
            [System.Drawing.Point]::new(40, 17),
            [System.Drawing.Point]::new(67, 42)))
    $g.DrawLines($pen, @(
            [System.Drawing.Point]::new(22, 38),
            [System.Drawing.Point]::new(22, 66),
            [System.Drawing.Point]::new(59, 66),
            [System.Drawing.Point]::new(59, 38)))
    $g.DrawLine($pen, 33, 66, 33, 50)
    $g.DrawLine($pen, 33, 50, 47, 50)
    $g.DrawLine($pen, 47, 50, 47, 66)
}

$exam = {
    param($g, $pen)
    $g.DrawRectangle($pen, 19, 14, 44, 54)
    $g.DrawLine($pen, 29, 30, 53, 30)
    $g.DrawLine($pen, 29, 42, 53, 42)
    $g.DrawLine($pen, 29, 54, 43, 54)
}

$mall = {
    param($g, $pen)
    $g.DrawLine($pen, 16, 31, 66, 31)
    $g.DrawLine($pen, 16, 31, 23, 67)
    $g.DrawLine($pen, 66, 31, 59, 67)
    $g.DrawLine($pen, 23, 67, 59, 67)
    $g.DrawArc($pen, 28, 15, 26, 28, 180, 180)
}

$my = {
    param($g, $pen)
    $g.DrawEllipse($pen, 27, 13, 27, 27)
    $g.DrawArc($pen, 15, 43, 51, 46, 180, 180)
    $g.DrawLine($pen, 15, 66, 15, 58)
    $g.DrawLine($pen, 66, 66, 66, 58)
    $g.DrawLine($pen, 15, 66, 66, 66)
}

$normal = '#8a94a6'
$active = '#1d4ed8'
Invoke-Draw 'tab-home' $normal $iconHome
Invoke-Draw 'tab-home-active' $active $iconHome
Invoke-Draw 'tab-exam' $normal $exam
Invoke-Draw 'tab-exam-active' $active $exam
Invoke-Draw 'tab-mall' $normal $mall
Invoke-Draw 'tab-mall-active' $active $mall
Invoke-Draw 'tab-my' $normal $my
Invoke-Draw 'tab-my-active' $active $my

Get-ChildItem $out -Filter '*.png' | ForEach-Object { $_.Name + '  ' + $_.Length + 'B' }

