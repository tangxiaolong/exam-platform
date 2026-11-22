package com.exam.platform.modules.question.imports;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.exam.platform.common.exception.BizException;
import com.exam.platform.common.util.Entities;
import com.exam.platform.common.util.Json;
import com.exam.platform.modules.content.entity.ExamSubject;
import com.exam.platform.modules.content.mapper.ExamSubjectMapper;
import com.exam.platform.modules.question.QuestionService;
import com.exam.platform.modules.question.entity.QbQuestion;
import com.exam.platform.modules.question.model.QuestionSaveRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 题目批量导入。
 *
 * <p>对齐 4.2 验收：<b>成功行与失败行都要有结果报告</b>。因此：</p>
 * <ol>
 *   <li>先整表逐行校验，收集所有失败原因（行号 + 错误码 + 可读信息）；</li>
 *   <li>存在任意失败行则整批不落库，避免「导入了一半」这种只能人工清洗的状态；</li>
 *   <li>全部通过才在一个事务内写库，任一异常整体回滚。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuestionImportService {

    private static final Set<String> TYPES = Set.of("SINGLE", "MULTIPLE", "JUDGE", "ESSAY");
    private static final Set<String> DIFFICULTIES = Set.of("EASY", "NORMAL", "HARD");
    private static final Set<String> STATUSES = Set.of("ENABLED", "DISABLED");
    /** 单次导入行数上限，防止超大批量拖垮事务。 */
    private static final int MAX_ROWS = 1000;

    private final ExamSubjectMapper subjectMapper;
    private final QuestionService questionService;

    /**
     * 导入模板示例行用的科目编码：取当前租户的第一个科目。
     *
     * <p>用真实科目编码而不是占位符，是为了让运营下载模板后能先原样导一遍、
     * 确认「模板—导入」这条链路本身是通的，再去填自己的数据。</p>
     */
    public String firstSubjectCode() {
        ExamSubject subject = subjectMapper.selectOne(Wrappers.<ExamSubject>lambdaQuery()
                .orderByAsc(ExamSubject::getId)
                .last("LIMIT 1"));
        return subject == null ? "SUBJECT_CODE" : subject.getCode();
    }

    /**
     * 解析并导入。
     *
     * @param fileName 原始文件名，用于判定 csv / xlsx
     * @param content  文件内容
     */
    @Transactional
    public ImportReport importQuestions(String fileName, byte[] content) {
        String extension = extensionOf(fileName);
        List<String[]> rows;
        if ("csv".equals(extension)) {
            rows = SheetParser.parseCsv(content);
        } else if ("xlsx".equals(extension)) {
            try {
                rows = SheetParser.parseXlsx(content);
            } catch (IOException e) {
                throw new BizException("QUESTION_IMPORT_PARSE_FAILED", "Excel 文件解析失败，请确认格式");
            }
        } else {
            throw new BizException("QUESTION_IMPORT_TYPE_UNSUPPORTED",
                    "仅支持 csv / xlsx 导入，实际：" + extension);
        }

        if (rows.isEmpty()) {
            throw new BizException("QUESTION_IMPORT_EMPTY", "导入文件没有内容");
        }
        Map<String, Integer> indexes = QuestionImportParser.headerIndexes(rows.get(0));
        Map<String, Long> subjectCache = new HashMap<>();
        List<ImportReport.RowResult> results = new ArrayList<>();
        List<QbQuestion> pending = new ArrayList<>();
        int totalRows = 0;

        for (int i = 1; i < rows.size(); i++) {
            String[] values = rows.get(i);
            int lineNo = i + 1;
            if (QuestionImportParser.isBlankRow(values)) {
                continue;
            }
            totalRows++;
            if (totalRows > MAX_ROWS) {
                throw new BizException("QUESTION_IMPORT_TOO_MANY_ROWS",
                        "单次最多导入 " + MAX_ROWS + " 行，请拆分后再导入");
            }
            String rawStem = values.length > 0 && indexes.containsKey("stem")
                    && indexes.get("stem") < values.length ? safe(values[indexes.get("stem")]) : "";
            try {
                QuestionImportParser.ParsedRow parsed =
                        QuestionImportParser.parse(values, indexes);
                Long subjectId = resolveSubjectId(parsed.subjectCode(), subjectCache);
                QbQuestion entity = toEntity(parsed, subjectId);
                pending.add(entity);
                results.add(ImportReport.RowResult.success(lineNo, null, parsed.stem()));
            } catch (BizException e) {
                results.add(ImportReport.RowResult.failure(lineNo, e.getCode(), e.getMessage(), rawStem));
            } catch (Exception e) {
                log.warn("题目导入行解析异常 lineNo={}", lineNo, e);
                results.add(ImportReport.RowResult.failure(lineNo, "QUESTION_IMPORT_ROW_INVALID",
                        "该行内容无法解析", rawStem));
            }
        }

        if (totalRows == 0) {
            throw new BizException("QUESTION_IMPORT_EMPTY", "导入文件没有有效数据行");
        }
        boolean hasFailure = results.stream().anyMatch(r -> r.status() == ImportReport.RowStatus.FAILED);
        if (hasFailure) {
            // 校验式导入：有失败行就整批回滚，不产生半截数据
            return ImportReport.rejected(totalRows, results);
        }

        for (int i = 0; i < pending.size(); i++) {
            QbQuestion saved = questionService.save(toRequest(pending.get(i)));
            ImportReport.RowResult original = results.get(i);
            results.set(i, ImportReport.RowResult.success(original.lineNo(), saved.getId(), original.stem()));
        }
        log.info("题目导入完成 rows={} operator={}", totalRows, com.exam.platform.common.security.UserContext.userId());
        return ImportReport.committed(totalRows, results);
    }

    private Long resolveSubjectId(String code, Map<String, Long> cache) {
        Long cached = cache.get(code);
        if (cached != null) {
            return cached;
        }
        ExamSubject subject = subjectMapper.selectOne(Wrappers.<ExamSubject>lambdaQuery()
                .eq(ExamSubject::getCode, code)
                .last("LIMIT 1"));
        if (subject == null) {
            throw new BizException("QUESTION_IMPORT_SUBJECT_NOT_FOUND", "科目编码不存在：" + code);
        }
        cache.put(code, subject.getId());
        return subject.getId();
    }

    private QbQuestion toEntity(QuestionImportParser.ParsedRow row, Long subjectId) {
        validateRow(row);
        QbQuestion question = new QbQuestion();
        question.setSubjectId(subjectId);
        question.setType(row.type());
        question.setStem(row.stem());
        question.setOptionsJson(Json.write(row.options()));
        question.setAnswerJson(Json.write(row.answer()));
        question.setAnalysis(row.analysis());
        question.setDifficulty(row.difficulty());
        question.setTags(row.tags());
        question.setSource(row.source());
        question.setStatus(row.status());
        question.setVersion(1);
        Entities.forInsert(question);
        return question;
    }

    private QuestionSaveRequest toRequest(QbQuestion entity) {
        return new QuestionSaveRequest(entity.getSubjectId(), entity.getType(), entity.getStem(),
                Json.readList(entity.getOptionsJson(), com.exam.platform.modules.question.model.OptionItem.class),
                Json.readList(entity.getAnswerJson(), String.class), entity.getAnalysis(),
                entity.getDifficulty(), entity.getTags(), entity.getStatus(), null, entity.getSource());
    }

    /** 逐行业务校验，错误信息面向运营人员而非开发。 */
    private void validateRow(QuestionImportParser.ParsedRow row) {
        if (!TYPES.contains(row.type())) {
            throw new BizException("QUESTION_IMPORT_ROW_INVALID",
                    "题型不合法：" + row.type() + "，支持 SINGLE/MULTIPLE/JUDGE/ESSAY");
        }
        if (!DIFFICULTIES.contains(row.difficulty())) {
            throw new BizException("QUESTION_IMPORT_ROW_INVALID",
                    "难度不合法：" + row.difficulty() + "，支持 EASY/NORMAL/HARD");
        }
        if (!STATUSES.contains(row.status())) {
            throw new BizException("QUESTION_IMPORT_ROW_INVALID",
                    "状态不合法：" + row.status() + "，支持 ENABLED/DISABLED");
        }
        boolean choice = "SINGLE".equals(row.type()) || "MULTIPLE".equals(row.type());
        if (choice && row.options().size() < 2) {
            throw new BizException("QUESTION_IMPORT_ROW_INVALID", "选择题至少需要 2 个选项");
        }
        if ("ESSAY".equals(row.type())) {
            // 主观题由人工阅卷，参考答案可留空
            return;
        }
        if (row.answer().isEmpty()) {
            throw new BizException("QUESTION_IMPORT_ROW_INVALID", "标准答案不能为空");
        }
        if (("SINGLE".equals(row.type()) || "JUDGE".equals(row.type())) && row.answer().size() != 1) {
            throw new BizException("QUESTION_IMPORT_ROW_INVALID",
                    ("SINGLE".equals(row.type()) ? "单选题" : "判断题") + "答案只能有一个");
        }
        if (choice) {
            Set<String> optionKeys = row.options().stream()
                    .map(o -> o.key().toUpperCase(Locale.ROOT))
                    .collect(java.util.stream.Collectors.toSet());
            for (String answer : row.answer()) {
                if (!optionKeys.contains(answer.toUpperCase(Locale.ROOT))) {
                    throw new BizException("QUESTION_IMPORT_ROW_INVALID",
                            "答案 " + answer + " 不在选项中：" + String.join(",", optionKeys));
                }
            }
        }
    }

    private String extensionOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private String safe(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.length() > 100 ? trimmed.substring(0, 100) : trimmed;
    }
}
