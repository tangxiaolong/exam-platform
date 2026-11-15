package com.exam.platform.modules.question.imports;

import java.nio.charset.StandardCharsets;

/**
 * 题库导入模板。
 *
 * <p>模板不是「随手写一份表头」，它必须与 {@link QuestionImportParser} 严格对齐——
 * 模板和解析器各写一份、谁也不认谁，是这类功能最常见的坑：运营照着模板填完，
 * 导入却报「表头缺少必填列」。所以这份模板的示例行会被测试原样喂回给导入接口，
 * 能导进去才算模板是对的。</p>
 *
 * <p>示例行的科目编码取当前租户的第一个科目，让运营下载下来就能先跑通一遍，
 * 再把自己的题目批量贴进去。</p>
 */
public final class QuestionImportTemplate {

    /** 与解析器的中文别名对齐；列顺序无关，但按「填写顺序」排更顺手。 */
    public static final String HEADER = "科目编码,题型,题干,选项,答案,解析,难度,标签,状态";

    private QuestionImportTemplate() {
    }

    /** 生成 CSV 模板（带 BOM，Excel 直接打开不乱码）。 */
    public static byte[] csv(String subjectCode) {
        String code = subjectCode == null || subjectCode.isBlank() ? "SUBJECT_CODE" : subjectCode;
        StringBuilder sb = new StringBuilder();
        sb.append('\uFEFF').append(HEADER).append("\r\n");
        // 单选题：答案单个选项号
        sb.append(code).append(",SINGLE,以下哪个是 Python 的保留字？,")
                .append("A:class|B:function|C:def|D:var,")
                .append("C,class 与 def 都是保留字。,EASY,示例,ENABLED\r\n");
        // 多选题：答案含逗号，必须用引号包起来，否则会被 CSV 拆成两列
        sb.append(code).append(",MULTIPLE,以下哪些属于关系型数据库？,")
                .append("A:MySQL|B:Redis|C:PostgreSQL|D:MongoDB,")
                .append("\"A,C\",Redis 与 MongoDB 属于 NoSQL。,NORMAL,示例,ENABLED\r\n");
        // 判断题：答案可写 A（对）/ B（错），也接受「对/错、T/F」
        sb.append(code).append(",JUDGE,HTTP 是无状态协议。,")
                .append("A:正确|B:错误,")
                .append("A,HTTP 本身不保存会话状态。,EASY,,ENABLED\r\n");
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }
}
