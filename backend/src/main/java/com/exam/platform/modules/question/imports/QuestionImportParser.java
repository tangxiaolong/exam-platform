package com.exam.platform.modules.question.imports;

import com.exam.platform.common.exception.BizException;
import com.exam.platform.modules.question.model.OptionItem;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 导入模板的行解析。
 *
 * <p>表头支持中英文别名，列顺序无关；缺列直接整批拒绝，
 * 因为缺列时逐行报错只会重复刷同一句提示。</p>
 */
final class QuestionImportParser {

    /** 列名 -> 别名（小写、去空格后比较）。 */
    private static final Map<String, List<String>> COLUMNS = new LinkedHashMap<>();

    static {
        COLUMNS.put("subjectCode", List.of("subjectcode", "subject_code", "科目编码", "科目代码"));
        COLUMNS.put("type", List.of("type", "题型"));
        COLUMNS.put("stem", List.of("stem", "题干", "题目"));
        COLUMNS.put("options", List.of("options", "选项", "选项列表"));
        COLUMNS.put("answer", List.of("answer", "答案", "标准答案"));
        COLUMNS.put("analysis", List.of("analysis", "解析", "答案解析"));
        COLUMNS.put("difficulty", List.of("difficulty", "难度"));
        COLUMNS.put("tags", List.of("tags", "标签"));
        COLUMNS.put("source", List.of("source", "来源"));
        COLUMNS.put("status", List.of("status", "状态"));
    }

    private QuestionImportParser() {
    }

    /** 解析表头，返回「字段 -> 列下标」。 */
    static Map<String, Integer> headerIndexes(String[] header) {
        Map<String, Integer> indexes = new LinkedHashMap<>();
        if (header == null || header.length == 0) {
            throw new BizException("QUESTION_IMPORT_HEADER_MISSING", "导入文件缺少表头");
        }
        for (int i = 0; i < header.length; i++) {
            String cell = normalize(header[i]);
            if (cell.isEmpty()) {
                continue;
            }
            for (Map.Entry<String, List<String>> column : COLUMNS.entrySet()) {
                if (column.getValue().contains(cell)) {
                    int columnIndex = i;
                    indexes.putIfAbsent(column.getKey(), columnIndex);
                    break;
                }
            }
        }
        for (String required : List.of("subjectCode", "type", "stem", "answer")) {
            if (!indexes.containsKey(required)) {
                throw new BizException("QUESTION_IMPORT_HEADER_MISSING",
                        "表头缺少必填列：" + displayName(required));
            }
        }
        return indexes;
    }

    static boolean isBlankRow(String[] values) {
        if (values == null) {
            return true;
        }
        return Arrays.stream(values).allMatch(v -> v == null || v.isBlank());
    }

    /**
     * 把一行原始单元格解析成结构化数据。
     *
     * @throws BizException 单行校验失败，错误码前缀 QUESTION_IMPORT_
     */
    static ParsedRow parse(String[] values, Map<String, Integer> idx) {
        String subjectCode = cell(values, idx.get("subjectCode"));
        String type = cell(values, idx.get("type")).toUpperCase(Locale.ROOT);
        String stem = cell(values, idx.get("stem"));
        String optionsRaw = cell(values, idx.get("options"));
        String answerRaw = cell(values, idx.get("answer"));
        String analysis = cell(values, idx.get("analysis"));
        String difficulty = cell(values, idx.get("difficulty")).toUpperCase(Locale.ROOT);
        String tags = cell(values, idx.get("tags"));
        String source = cell(values, idx.get("source"));
        String status = cell(values, idx.get("status")).toUpperCase(Locale.ROOT);

        if (subjectCode.isEmpty()) {
            throw fail("科目编码不能为空");
        }
        if (type.isEmpty()) {
            throw fail("题型不能为空");
        }
        if (stem.isEmpty()) {
            throw fail("题干不能为空");
        }
        if (stem.length() > 2000) {
            throw fail("题干长度不能超过 2000 字");
        }
        return new ParsedRow(subjectCode, type, stem, parseOptions(optionsRaw), parseAnswer(answerRaw),
                analysis.isEmpty() ? null : analysis,
                difficulty.isEmpty() ? "NORMAL" : difficulty,
                tags.isEmpty() ? null : tags,
                status.isEmpty() ? "ENABLED" : status,
                source.isEmpty() ? null : source);
    }

    /** 选项格式：{@code A:选项一|B:选项二}，也支持中文冒号与逗号分隔。 */
    private static List<OptionItem> parseOptions(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        String normalized = raw.replace('；', '|').replace(';', '|');
        List<OptionItem> options = new ArrayList<>();
        for (String piece : normalized.split("\\|")) {
            String item = piece.trim();
            if (item.isEmpty()) {
                continue;
            }
            int separator = indexOfSeparator(item);
            if (separator <= 0) {
                throw fail("选项格式应为「A:选项内容|B:选项内容」，实际：" + item);
            }
            String key = item.substring(0, separator).trim().toUpperCase(Locale.ROOT);
            String text = item.substring(separator + 1).trim();
            if (key.isEmpty() || text.isEmpty()) {
                throw fail("选项缺少选项号或内容：" + item);
            }
            if (options.stream().anyMatch(o -> o.key().equals(key))) {
                throw fail("选项号重复：" + key);
            }
            options.add(new OptionItem(key, text));
        }
        return options;
    }

    /** 答案格式：{@code A} 或 {@code A,C}；判断题额外接受 对/错、T/F。 */
    private static List<String> parseAnswer(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        String normalized = raw.replace('，', ',').replace('、', ',').replace('|', ',');
        List<String> answers = new ArrayList<>();
        for (String piece : normalized.split(",")) {
            String key = piece.trim().toUpperCase(Locale.ROOT);
            if (key.isEmpty()) {
                continue;
            }
            String mapped = switch (key) {
                case "对", "正确", "TRUE", "T", "是", "√" -> "A";
                case "错", "错误", "FALSE", "F", "否", "×" -> "B";
                default -> key;
            };
            if (!answers.contains(mapped)) {
                answers.add(mapped);
            }
        }
        return answers;
    }

    private static int indexOfSeparator(String item) {
        int colon = item.indexOf(':');
        int fullWidth = item.indexOf('：');
        int dot = item.indexOf('.');
        int candidate = colon >= 0 ? colon : fullWidth >= 0 ? fullWidth : dot;
        return candidate;
    }

    private static String cell(String[] values, Integer index) {
        if (index == null || values == null || index < 0 || index >= values.length) {
            return "";
        }
        String value = values[index];
        return value == null ? "" : value.trim();
    }

    private static String normalize(String header) {
        return header == null ? "" : header.replace(" ", "").replace("\uFEFF", "").trim().toLowerCase(Locale.ROOT);
    }

    private static String displayName(String field) {
        return switch (field) {
            case "subjectCode" -> "科目编码";
            case "type" -> "题型";
            case "stem" -> "题干";
            case "answer" -> "答案";
            default -> field;
        };
    }

    private static BizException fail(String message) {
        return new BizException("QUESTION_IMPORT_ROW_INVALID", message);
    }

    /** 解析后的单行数据。 */
    record ParsedRow(String subjectCode, String type, String stem, List<OptionItem> options,
                     List<String> answer, String analysis, String difficulty, String tags, String status,
                     String source) {
    }
}
