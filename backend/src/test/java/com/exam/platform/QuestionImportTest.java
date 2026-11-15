package com.exam.platform;

import com.exam.platform.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 题目批量导入：对齐 4.2 验收「题目导入成功/失败行均有结果报告」。
 *
 * <p>导入采用校验式：只要有失败行就整批不落库，因此失败场景必须验证
 * 「没有写入任何数据」，否则运营会面对半截题库。</p>
 */
@DisplayName("题目批量导入：逐行校验 + 失败原因报告")
class QuestionImportTest extends ApiTestSupport {

    private static final String HEADER = "科目编码,题型,题干,选项,答案,解析,难度";

    @Test
    @DisplayName("全部合法时整批落库，逐行返回题目 ID")
    void shouldImportAllRows() {
        String admin = adminToken();
        long before = countQuestions(admin);

        String csv = HEADER + "\n"
                + "PMP-BASE,SINGLE,导入题目一,A:选项甲|B:选项乙,A,解析一,EASY\n"
                + "PMP-BASE,MULTIPLE,导入题目二,A:甲|B:乙|C:丙,\"A,C\",解析二,HARD\n"
                + "PMP-BASE,JUDGE,导入题目三,A:正确|B:错误,对,解析三,NORMAL\n";

        JsonNode report = upload(admin, "questions.csv", csv.getBytes(StandardCharsets.UTF_8));
        assertThat(report.path("totalRows").asInt()).isEqualTo(3);
        assertThat(report.path("successCount").asInt()).isEqualTo(3);
        assertThat(report.path("failureCount").asInt()).isZero();
        assertThat(report.path("committed").asBoolean()).isTrue();
        assertThat(report.path("rows").size()).isEqualTo(3);
        for (JsonNode row : report.path("rows")) {
            assertThat(row.path("status").asText()).isEqualTo("SUCCESS");
            assertThat(row.path("questionId").asLong()).isPositive();
            assertThat(row.path("lineNo").asInt()).isGreaterThan(1);
        }
        assertThat(countQuestions(admin)).isEqualTo(before + 3);

        // 判断题的「对」应被规范化为选项 A
        long judgeId = report.path("rows").get(2).path("questionId").asLong();
        JsonNode detail = data("/admin/questions/" + judgeId, admin);
        assertThat(detail.path("answer").get(0).asText()).isEqualTo("A");
        assertThat(detail.path("type").asText()).isEqualTo("JUDGE");
        assertThat(detail.path("options").size()).isEqualTo(2);
    }

    @Test
    @DisplayName("存在失败行时整批不落库，并逐行给出错误原因")
    void shouldRejectWholeBatchOnFailure() {
        String admin = adminToken();
        long before = countQuestions(admin);

        String csv = HEADER + "\n"
                + "PMP-BASE,SINGLE,合法题目,A:甲|B:乙,A,解析,NORMAL\n"
                + "PMP-BASE,SINGLE,缺答案题目,A:甲|B:乙,,解析,NORMAL\n"
                + "NOT-EXIST,SINGLE,科目不存在,A:甲|B:乙,A,解析,NORMAL\n"
                + "PMP-BASE,UNKNOWN,题型非法,A:甲|B:乙,A,解析,NORMAL\n"
                + "PMP-BASE,SINGLE,答案不在选项,A:甲|B:乙,Z,解析,NORMAL\n"
                + "PMP-BASE,SINGLE,单选多答案,A:甲|B:乙|C:丙,\"A,B\",解析,NORMAL\n";

        JsonNode report = upload(admin, "questions.csv", csv.getBytes(StandardCharsets.UTF_8));
        assertThat(report.path("totalRows").asInt()).isEqualTo(6);
        assertThat(report.path("successCount").asInt()).isEqualTo(1);
        assertThat(report.path("failureCount").asInt()).isEqualTo(5);
        assertThat(report.path("committed").asBoolean()).as("有失败行则整批回滚").isFalse();

        JsonNode okRow = report.path("rows").get(0);
        assertThat(okRow.path("status").asText()).isEqualTo("SUCCESS");
        // 响应体按 non_null 序列化，未落库的行不会带 questionId 字段
        assertThat(okRow.hasNonNull("questionId")).as("未落库不应有 ID").isFalse();

        for (int i = 1; i < report.path("rows").size(); i++) {
            JsonNode row = report.path("rows").get(i);
            assertThat(row.path("status").asText()).isEqualTo("FAILED");
            assertThat(row.path("errorCode").asText()).startsWith("QUESTION_IMPORT_");
            assertThat(row.path("errorMessage").asText()).isNotBlank();
            // 第 1 行是表头，首条数据在第 2 行：行号 = 数据下标 + 2
            assertThat(row.path("lineNo").asInt()).as("行号应与 Excel 一致").isEqualTo(i + 2);
        }
        assertThat(report.path("rows").get(2).path("errorMessage").asText()).contains("NOT-EXIST");
        assertThat(report.path("rows").get(4).path("errorMessage").asText()).contains("Z");

        assertThat(countQuestions(admin)).as("整批回滚，题目数不变").isEqualTo(before);
    }

    @Test
    @DisplayName("表头缺少必填列时整批拒绝")
    void shouldRejectMissingHeaderColumn() {
        String admin = adminToken();
        String csv = "科目编码,题干,答案\nPMP-BASE,缺题型列,A\n";
        ResponseEntity<String> response = uploadRaw(admin, "questions.csv",
                csv.getBytes(StandardCharsets.UTF_8));
        JsonNode body = parse(response);
        assertThat(body.path("code").asText()).isEqualTo("QUESTION_IMPORT_HEADER_MISSING");
        assertThat(body.path("message").asText()).contains("题型");
    }

    @Test
    @DisplayName("不支持的格式与空文件被拒绝")
    void shouldRejectUnsupportedFormat() {
        String admin = adminToken();
        JsonNode unsupported = parse(uploadRaw(admin, "questions.json",
                "{}".getBytes(StandardCharsets.UTF_8)));
        assertThat(unsupported.path("code").asText()).isEqualTo("QUESTION_IMPORT_TYPE_UNSUPPORTED");

        JsonNode empty = parse(uploadRaw(admin, "questions.csv",
                (HEADER + "\n\n\n").getBytes(StandardCharsets.UTF_8)));
        assertThat(empty.path("code").asText()).isEqualTo("QUESTION_IMPORT_EMPTY");
    }

    @Test
    @DisplayName("考生无权导入")
    void shouldForbidStudentImport() {
        String student = wechatToken("import-student", "导入越权考生");
        ResponseEntity<String> response = uploadRaw(student, "questions.csv",
                (HEADER + "\nPMP-BASE,SINGLE,越权,A:甲|B:乙,A,,NORMAL\n").getBytes(StandardCharsets.UTF_8));
        assertThat(parse(response).path("code").asText()).isEqualTo("AUTH_FORBIDDEN");
    }

    // ------------------------------------------------------------------ helpers

    private JsonNode upload(String admin, String fileName, byte[] content) {
        JsonNode body = parse(uploadRaw(admin, fileName, content));
        assertThat(body.path("code").asText()).as("导入接口应成功，实际：%s", body).isEqualTo("0");
        return body.path("data");
    }

    private ResponseEntity<String> uploadRaw(String token, String fileName, byte[] content) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return fileName;
            }
        });
        return restTemplate.exchange(api("/admin/questions/imports"), HttpMethod.POST,
                new HttpEntity<>(form, headers), String.class);
    }

    private long countQuestions(String admin) {
        return data("/admin/questions?pageNo=1&pageSize=1", admin).path("total").asLong();
    }

    private JsonNode parse(ResponseEntity<String> response) {
        try {
            return objectMapper.readTree(response.getBody());
        } catch (Exception e) {
            throw new IllegalStateException("响应不是合法 JSON：" + response.getBody(), e);
        }
    }
}
