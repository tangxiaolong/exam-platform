package com.exam.platform.modules.question;

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
 * 题库导入模板下载。
 *
 * <p>核心断言是「下载下来的模板能原样导进去」：模板与解析器各自维护一份，
 * 早晚会漂移——运营照着模板填完却报「表头缺列」，那种问题只有把模板真的喂给
 * 导入接口才能提前发现。</p>
 */
@DisplayName("题库导入：模板可下载且与解析器一致")
class QuestionImportTemplateTest extends ApiTestSupport {

    @Test
    @DisplayName("下载的模板原样导入即可成功，示例行不会报错")
    void templateShouldBeImportableAsIs() {
        String admin = adminToken();

        ResponseEntity<byte[]> response = restTemplate.exchange(api("/admin/questions/imports/template"),
                HttpMethod.GET, new HttpEntity<>(bearerHeaders(admin)), byte[].class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getContentType().toString())
                .as("要能被 Excel 直接打开，必须是 text/csv")
                .contains("text/csv");
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .as("下载要有文件名，否则浏览器只会存成 download")
                .contains("question-import-template.csv");

        byte[] body = response.getBody();
        assertThat(body).isNotNull();
        String csv = new String(body, StandardCharsets.UTF_8);
        assertThat(csv).as("BOM + 中文表头，运营打开就能看懂").startsWith("\uFEFF科目编码,题型,题干");

        // 把模板原样喂回导入接口：能导进去才说明模板与解析器没有漂移
        JsonNode report = upload(admin, "question-import-template.csv", body);
        assertThat(report.path("totalRows").asInt()).isEqualTo(3);
        assertThat(report.path("successCount").asInt()).isEqualTo(3);
        assertThat(report.path("failureCount").asInt()).isZero();
        assertThat(report.path("committed").asBoolean()).isTrue();
    }

    // ------------------------------------------------------------------ helpers

    private JsonNode upload(String token, String fileName, byte[] content) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.setBearerAuth(token);
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return fileName;
            }
        });
        ResponseEntity<String> response = restTemplate.exchange(api("/admin/questions/imports"),
                HttpMethod.POST, new HttpEntity<>(form, headers), String.class);
        try {
            JsonNode body = objectMapper.readTree(response.getBody());
            assertThat(body.path("code").asText()).as("导入应成功，实际：%s", body).isEqualTo("0");
            return body.path("data");
        } catch (Exception e) {
            throw new IllegalStateException("响应不是合法 JSON：" + response.getBody(), e);
        }
    }

    private HttpHeaders bearerHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }
}
