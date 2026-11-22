# 外部压测：对**已运行**的真实环境压答题保存，产出 p50/p95/p99。
#
# 与 LoadBaselineTest 的区别：
#   - LoadBaselineTest 在进程内跑（H2 内存库），用于「改动前后」的同一台机器对比；
#   - 这个脚本对真实部署（MySQL + 真实的 Tomcat/连接池）压，得到的才是能对外承诺的数字。
#
# 交卷为什么不在这个脚本里：交卷需要每考生一份未交卷的 attempt，脚本无法凭空造；
# 交卷压测请用 LoadBaselineTest 在预发库上跑，或直接对预发环境用 k6/wrk 编排。
#
# 用法（PowerShell 7+）：
#   pwsh tools/load-baseline.ps1 -Token "<考生token>" -AttemptId 123 -QuestionId 456
#   pwsh tools/load-baseline.ps1 -BaseUrl http://pre.example.com -Token "..." -AttemptId 123 -QuestionId 456 -Threads 16 -Total 400
#
# 前置：目标考生必须已开考（attempt 处于 IN_PROGRESS），否则保存接口会返回 EXAM_NOT_IN_PROGRESS。
param(
    [string]$BaseUrl = 'http://localhost:8080',
    [Parameter(Mandatory = $true)][string]$Token,
    [Parameter(Mandatory = $true)][long]$AttemptId,
    [Parameter(Mandatory = $true)][long]$QuestionId,
    [int]$Threads = 8,
    [int]$Total = 200
)

if ($PSVersionTable.PSVersion.Major -lt 7) {
    Write-Error "需要 PowerShell 7+（ForEach-Object -Parallel）。当前版本：$($PSVersionTable.PSVersion)"
}

$url = "$BaseUrl/api/v1/attempts/$AttemptId/answers/$QuestionId"
$headers = @{ Authorization = "Bearer $Token"; 'Content-Type' = 'application/json' }
$body = @{ answer = @('A') } | ConvertTo-Json

$perThread = [math]::Floor($Total / $Threads)
$start = [System.Diagnostics.Stopwatch]::StartNew()

$samples = 1..$Threads | ForEach-Object -Parallel {
    $local = @()
    for ($i = 0; $i -lt $using:perThread; $i++) {
        $sw = [System.Diagnostics.Stopwatch]::StartNew()
        try {
            # 参数用哈希表 splat：反引号续行在纯 LF 文件里对 Windows PowerShell 5.1 会静默失效
            $reqUrl = $using:url
            $reqHeaders = $using:headers
            $reqBody = $using:body
            $requestArgs = @{
                Uri        = $reqUrl
                Method     = 'Put'
                Headers    = $reqHeaders
                Body       = $reqBody
                TimeoutSec = 30
            }
            Invoke-RestMethod @requestArgs | Out-Null
        }
        catch {
            Write-Host "请求失败：$($_.Exception.Message)" -ForegroundColor Red
        }
        $sw.Stop()
        $local += $sw.ElapsedMilliseconds
    }
    $local
} -ThrottleLimit $Threads

$elapsed = $start.ElapsedMilliseconds
$sorted = $samples | Sort-Object
if ($sorted.Count -eq 0) {
    Write-Error "没有采集到样本，检查 token / attemptId 是否有效"
}

function Get-Percentile($values, [int]$p) {
    $index = [math]::Ceiling($p / 100.0 * $values.Count) - 1
    if ($index -lt 0) { $index = 0 }
    return $values[$index]
}

$p50 = Get-Percentile $sorted 50
$p95 = Get-Percentile $sorted 95
$p99 = Get-Percentile $sorted 99
$max = $sorted[-1]
$throughput = if ($elapsed -gt 0) { [math]::Round($sorted.Count * 1000.0 / $elapsed, 1) } else { 0 }

Write-Host ""
Write-Host "答题保存压测：$($sorted.Count) 样本 / $Threads 并发 / 耗时 ${elapsed}ms" -ForegroundColor Cyan
Write-Host "p50=${p50}ms  p95=${p95}ms  p99=${p99}ms  max=${max}ms  吞吐=${throughput} req/s" -ForegroundColor Green
Write-Host ""
Write-Host "解读：p99 是承诺给考生的那个数字（几百人同时刷新时最慢的那 1% 的体验）；" -ForegroundColor DarkGray
Write-Host "吞吐要对照告警规则里的「答题保存 p99 > 1s」阈值，以及连接池 pending 是否持续 > 0。" -ForegroundColor DarkGray
