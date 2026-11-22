package com.exam.platform;

import com.exam.platform.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 内容运营模块的删除与录入约束。
 *
 * <p>这些约束不是「好不好用」的问题：项目 / 科目被删掉后，其下的科目、题目、知识点、
 * 课程全部指向一个不存在的父级——题库页按科目还能查到，项目下拉里却找不到，
 * 运营只能靠改库收拾。所以约束必须落在服务端，前端提示只是提前告知。</p>
 */
@DisplayName("内容运营：删除引用与录入校验")
class ContentGuardTest extends ApiTestSupport {

    @Test
    @DisplayName("科目下还有题目时不允许删除")
    void subjectWithQuestionsShouldNotBeDeleted() {
        String admin = adminToken();
        long projectId = firstProjectId(admin);
        long subjectId = createSubject(admin, projectId, "GUARD-SUBJ-Q");

        createQuestion(admin, subjectId, "占位题", List.of("A", "B"), List.of("A"));

        assertThat(errorCode(HttpMethod.DELETE, "/admin/subjects/" + subjectId, admin, null))
                .as("有题目的科目删掉后，题目会指向不存在的科目")
                .isEqualTo("COMMON_BAD_REQUEST");

        // 把题目挪走后科目才能删
        long otherSubjectId = createSubject(admin, projectId, "GUARD-SUBJ-Q2");
        long questionId = firstQuestionId(admin, subjectId);
        data(HttpMethod.PUT, "/admin/questions/batch-subject", admin,
                Map.of("ids", List.of(questionId), "subjectId", otherSubjectId));
        data(HttpMethod.DELETE, "/admin/subjects/" + subjectId, admin, null);
    }

    @Test
    @DisplayName("项目下还有科目时不允许删除")
    void projectWithSubjectsShouldNotBeDeleted() {
        String admin = adminToken();
        JsonNode project = data(HttpMethod.POST, "/admin/projects", admin, Map.of(
                "code", "GUARD-PROJECT-" + System.nanoTime(),
                "name", "待删项目",
                "status", "DRAFT"));
        long projectId = project.path("id").asLong();
        long subjectId = createSubject(admin, projectId, "GUARD-PROJECT-SUBJ");

        assertThat(errorCode(HttpMethod.DELETE, "/admin/projects/" + projectId, admin, null))
                .as("科目还挂在项目下，项目不能删")
                .isEqualTo("COMMON_BAD_REQUEST");

        data(HttpMethod.DELETE, "/admin/subjects/" + subjectId, admin, null);
        data(HttpMethod.DELETE, "/admin/projects/" + projectId, admin, null);
    }

    @Test
    @DisplayName("项目编码重复时给出可读提示，而不是数据库错误")
    void duplicateProjectCodeShouldBeRejected() {
        String admin = adminToken();
        String code = "GUARD-DUP-" + System.nanoTime();
        data(HttpMethod.POST, "/admin/projects", admin,
                Map.of("code", code, "name", "编码占用者"));

        assertThat(errorCode(HttpMethod.POST, "/admin/projects", admin,
                Map.of("code", code, "name", "同编码项目")))
                .as("编码唯一是库约束，但错误信息要能指导运营换一个")
                .isEqualTo("COMMON_BAD_REQUEST");
    }

    @Test
    @DisplayName("科目编码在项目内唯一")
    void duplicateSubjectCodeShouldBeRejected() {
        String admin = adminToken();
        long projectId = firstProjectId(admin);
        createSubject(admin, projectId, "GUARD-DUP-SUBJ");

        assertThat(errorCode(HttpMethod.POST, "/admin/subjects", admin, Map.of(
                "projectId", projectId,
                "code", "GUARD-DUP-SUBJ",
                "name", "同编码科目")))
                .isEqualTo("COMMON_BAD_REQUEST");
    }

    @Test
    @DisplayName("知识点有子节点时不允许删除，避免层级被静默压平")
    void knowledgePointWithChildrenShouldNotBeDeleted() {
        String admin = adminToken();
        long projectId = firstProjectId(admin);
        long subjectId = createSubject(admin, projectId, "GUARD-KP");
        long rootId = createPoint(admin, subjectId, null, "父知识点");
        long childId = createPoint(admin, subjectId, rootId, "子知识点");

        assertThat(errorCode(HttpMethod.DELETE, "/admin/knowledge-points/" + rootId, admin, null))
                .as("子节点的 parent_id 会指向已删除的行")
                .isEqualTo("COMMON_BAD_REQUEST");

        data(HttpMethod.DELETE, "/admin/knowledge-points/" + childId, admin, null);
        data(HttpMethod.DELETE, "/admin/knowledge-points/" + rootId, admin, null);
    }

    @Test
    @DisplayName("编辑知识点时同层级重名同样被拒")
    void renameToDuplicateShouldBeRejected() {
        String admin = adminToken();
        long projectId = firstProjectId(admin);
        long subjectId = createSubject(admin, projectId, "GUARD-KP-RENAME");
        long first = createPoint(admin, subjectId, null, "重名-A");
        long second = createPoint(admin, subjectId, null, "重名-B");

        assertThat(errorCode(HttpMethod.PUT, "/admin/knowledge-points/" + second, admin, Map.of(
                "name", "重名-A",
                "sortNo", 0)))
                .as("新增拦住了，编辑也必须拦住，否则换个名字就能造出重名")
                .isEqualTo("COMMON_BAD_REQUEST");

        // 改成自己的名字不算重名
        data(HttpMethod.PUT, "/admin/knowledge-points/" + first, admin, Map.of(
                "name", "重名-A",
                "sortNo", 0));
    }

    @Test
    @DisplayName("知识点不能挂到别的科目的节点下")
    void crossSubjectParentShouldBeRejected() {
        String admin = adminToken();
        long projectId = firstProjectId(admin);
        long subjectA = createSubject(admin, projectId, "GUARD-KP-A");
        long subjectB = createSubject(admin, projectId, "GUARD-KP-B");
        long parentInA = createPoint(admin, subjectA, null, "A 的根");

        assertThat(errorCode(HttpMethod.POST, "/admin/knowledge-points", admin, Map.of(
                "subjectId", subjectB,
                "parentId", parentInA,
                "name", "跨科目子节点")))
                .as("跨科目挂载会形成一棵在任何科目视图里都不完整的树")
                .isEqualTo("COMMON_BAD_REQUEST");
    }

    @Test
    @DisplayName("选择题答案必须落在选项里")
    void answerOutsideOptionsShouldBeRejected() {
        String admin = adminToken();
        long projectId = firstProjectId(admin);
        long subjectId = createSubject(admin, projectId, "GUARD-ANSWER");

        assertThat(errorCode(HttpMethod.POST, "/admin/questions", admin, Map.of(
                "subjectId", subjectId,
                "type", "SINGLE",
                "stem", "答案填了不存在的选项",
                "options", List.of(Map.of("key", "A", "text", "选项 A"),
                        Map.of("key", "B", "text", "选项 B")),
                "answer", List.of("E"))))
                .as("答案不在选项里，这道题永远判错，而列表看不出异常")
                .isEqualTo("COMMON_BAD_REQUEST");
    }

    @Test
    @DisplayName("选项字段兼容 key/text 与 label/content 两种写法")
    void optionAliasShouldBeAccepted() {
        String admin = adminToken();
        long projectId = firstProjectId(admin);
        long subjectId = createSubject(admin, projectId, "GUARD-ALIAS");

        // 基线 DDL 的注释用的是 label/content，历史调用方也这么传；
        // 不兼容的话选项会变成一串空对象，而题目「保存成功」
        JsonNode created = data(HttpMethod.POST, "/admin/questions", admin, Map.of(
                "subjectId", subjectId,
                "type", "SINGLE",
                "stem", "别名选项题",
                "options", List.of(Map.of("label", "A", "content", "选项 A"),
                        Map.of("label", "B", "content", "选项 B")),
                "answer", List.of("A")));
        JsonNode options = created.path("options");
        assertThat(options.size()).isEqualTo(2);
        assertThat(options.get(0).path("key").asText()).isEqualTo("A");
        assertThat(options.get(0).path("text").asText()).isEqualTo("选项 A");
    }

    @Test
    @DisplayName("题目详情回显知识点，编辑不会把它清空")
    void detailShouldCarryKnowledgePoint() {
        String admin = adminToken();
        long projectId = firstProjectId(admin);
        long subjectId = createSubject(admin, projectId, "GUARD-KP-ECHO");
        long pointId = createPoint(admin, subjectId, null, "回显知识点");

        JsonNode created = data(HttpMethod.POST, "/admin/questions", admin, Map.of(
                "subjectId", subjectId,
                "type", "SINGLE",
                "stem", "带知识点的题",
                "options", List.of(Map.of("key", "A", "text", "选项 A"),
                        Map.of("key", "B", "text", "选项 B")),
                "answer", List.of("A"),
                "knowledgePointId", pointId));

        assertThat(created.path("knowledgePointId").asLong())
                .as("不回显知识点，编辑保存就会把它改成未标注")
                .isEqualTo(pointId);

        JsonNode fetched = data("/admin/questions/" + created.path("id").asLong(), admin);
        assertThat(fetched.path("knowledgePointId").asLong()).isEqualTo(pointId);
    }

    @Test
    @DisplayName("按知识点筛选题库")
    void questionsShouldBeFilterableByKnowledgePoint() {
        String admin = adminToken();
        long projectId = firstProjectId(admin);
        long subjectId = createSubject(admin, projectId, "GUARD-KP-FILTER");
        long pointId = createPoint(admin, subjectId, null, "筛选知识点");

        JsonNode created = data(HttpMethod.POST, "/admin/questions", admin, Map.of(
                "subjectId", subjectId,
                "type", "SINGLE",
                "stem", "可被知识点筛出的题",
                "options", List.of(Map.of("key", "A", "text", "选项 A"),
                        Map.of("key", "B", "text", "选项 B")),
                "answer", List.of("A"),
                "knowledgePointId", pointId));

        JsonNode page = data("/admin/questions?knowledgePointId=" + pointId
                + "&pageNo=1&pageSize=50", admin);
        boolean found = false;
        for (JsonNode row : page.path("list")) {
            if (row.path("id").asLong() == created.path("id").asLong()) {
                found = true;
                assertThat(row.path("knowledgePointId").asLong()).isEqualTo(pointId);
            }
        }
        assertThat(found).as("知识点范围应能筛出挂在该知识点下的题").isTrue();
    }

    @Test
    @DisplayName("未选科目时按项目收敛题库，不掺入其它项目的题")
    void projectScopeShouldNotLeakOtherProjects() {
        String admin = adminToken();
        // 两个都新建，避免依赖演示数据的排序：列表按 sort_no 升序、id 倒序，
        // 直接取「第一个项目」拿到的可能是刚建的那个
        long projectA = createProject(admin, "GUARD-SCOPE-A");
        long projectB = createProject(admin, "GUARD-SCOPE-B");
        long subjectA = createSubject(admin, projectA, "GUARD-SCOPE-A-SUBJ");
        long subjectB = createSubject(admin, projectB, "GUARD-SCOPE-B-SUBJ");

        long questionInA = createQuestion(admin, subjectA, "A 项目的题", List.of("A", "B"), List.of("A"));
        long questionInB = createQuestion(admin, subjectB, "B 项目的题", List.of("A", "B"), List.of("A"));

        JsonNode page = data("/admin/questions?projectId=" + projectA + "&pageNo=1&pageSize=100", admin);
        boolean sawA = false;
        for (JsonNode row : page.path("list")) {
            long id = row.path("id").asLong();
            assertThat(id).as("A 项目的题库里不应出现 B 项目的题").isNotEqualTo(questionInB);
            if (id == questionInA) {
                sawA = true;
            }
        }
        assertThat(sawA).as("A 项目自己的题必须能查到").isTrue();
    }

    // ---------------------------------------------------------------- helpers

    private long createProject(String admin, String codePrefix) {
        return data(HttpMethod.POST, "/admin/projects", admin, Map.of(
                "code", codePrefix + "-" + System.nanoTime(),
                "name", "守卫项目-" + codePrefix,
                "status", "DRAFT")).path("id").asLong();
    }

    private long createSubject(String admin, long projectId, String code) {
        return data(HttpMethod.POST, "/admin/subjects", admin, Map.of(
                "projectId", projectId,
                "code", code,
                "name", "守卫科目",
                "status", "ENABLED")).path("id").asLong();
    }

    private long createPoint(String admin, long subjectId, Long parentId, String name) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("subjectId", subjectId);
        body.put("name", name);
        body.put("sortNo", 0);
        if (parentId != null) {
            body.put("parentId", parentId);
        }
        return data(HttpMethod.POST, "/admin/knowledge-points", admin, body).path("id").asLong();
    }

    private long createQuestion(String admin, long subjectId, String stem,
                                List<String> optionKeys, List<String> answer) {
        List<Map<String, String>> options = optionKeys.stream()
                .map(key -> Map.of("key", key, "text", "选项 " + key))
                .toList();
        return data(HttpMethod.POST, "/admin/questions", admin, Map.of(
                "subjectId", subjectId,
                "type", "SINGLE",
                "stem", stem,
                "options", options,
                "answer", answer)).path("id").asLong();
    }

    private long firstQuestionId(String admin, long subjectId) {
        return data("/admin/questions?subjectId=" + subjectId + "&pageNo=1&pageSize=10", admin)
                .path("list").get(0).path("id").asLong();
    }
}
