package com.exam.platform.modules.exam;

import com.exam.platform.common.api.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 运营后台：场次成绩分析。 */
@RestController
@RequestMapping("/v1/admin/sessions")
@RequiredArgsConstructor
public class ExamReportController {

    private final ExamReportService reportService;

    /**
     * 场次成绩分析：参考人数、平均/最高/最低分、及格率（≥满分的 60%）、五段分布。
     *
     * <p>只统计已判分（GRADED）与已发布（PUBLISHED）的成绩；阅卷中的答卷客观题分不完整，
     * 混入会把均值与分布都带偏。</p>
     */
    @GetMapping("/{id}/analytics")
    public Result<ExamReportView> analytics(@PathVariable Long id) {
        return Result.ok(reportService.analytics(id));
    }

    /**
     * 逐题正确率：从 detail_json 汇总每题作答与答对次数，正确率最低的排最前——
     * 「哪些题该回头讲」就是这个顺序，供讲评与题库优化参考。
     */
    @GetMapping("/{id}/question-accuracy")
    public Result<java.util.List<ExamReportView.QuestionAccuracy>> questionAccuracy(@PathVariable Long id) {
        return Result.ok(reportService.questionAccuracy(id));
    }

    /**
     * 场次成绩排行（前 N 名，默认 50，上限 100）：同分并列、后续跳号。
     */
    @GetMapping("/{id}/leaderboard")
    public Result<java.util.List<ExamReportView.LeaderboardEntry>> leaderboard(
            @PathVariable Long id,
            @RequestParam(defaultValue = "50") int limit) {
        return Result.ok(reportService.leaderboard(id, limit));
    }

    /**
     * 多场次成绩横向对比：ids 逗号分隔（1-10 个场次），每个请求场次一行（无成绩也返回，count=0）。
     */
    @GetMapping("/compare")
    public Result<java.util.List<java.util.Map<String, Object>>> compare(@RequestParam String ids) {
        return Result.ok(reportService.compare(parseIds(ids)));
    }

    /**
     * 场次对比 CSV 导出：同步下载，与对比抽屉同源同口径（1-10 个场次）。
     */
    @GetMapping("/compare/export")
    public org.springframework.http.ResponseEntity<byte[]> exportCompare(@RequestParam String ids) {
        var rows = reportService.compare(parseIds(ids));
        StringBuilder csv = new StringBuilder("session_name,result_count,avg_score,max_score,min_score,full_score\n");
        for (var row : rows) {
            csv.append(csvCell(row.get("session_name"))).append(',')
                    .append(row.get("result_count")).append(',')
                    .append(row.get("avg_score")).append(',')
                    .append(row.get("max_score")).append(',')
                    .append(row.get("min_score")).append(',')
                    .append(row.get("full_score")).append('\n');
        }
        byte[] bytes = csv.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return org.springframework.http.ResponseEntity.ok()
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=session-compare.csv")
                .contentType(org.springframework.http.MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .body(bytes);
    }

    private static String csvCell(Object value) {
        if (value == null) {
            return "";
        }
        String text = String.valueOf(value);
        if (text.contains(",") || text.contains("\"") || text.contains("\n")) {
            return "\"" + text.replace("\"", "\"\"") + "\"";
        }
        return text;
    }

    private static java.util.List<Long> parseIds(String ids) {
        return java.util.Arrays.stream(ids.split(","))
                .map(String::trim)
                .filter(item -> !item.isEmpty())
                .map(Long::valueOf)
                .toList();
    }
}
