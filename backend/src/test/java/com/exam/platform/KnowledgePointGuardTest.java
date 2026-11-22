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
 * 知识点的维护约束。
 *
 * <p>既有实现只有增删改查，没有约束。缺约束的后果不是「不好用」，而是线上事故：
 * 同层级重名让运营无法在下拉框里区分；删除被引用的知识点后，
 * 题目指向一个不存在的 ID，组卷只报「可用 0 道」，根因不可见。</p>
 */
@DisplayName("知识点：重名与引用约束")
class KnowledgePointGuardTest extends ApiTestSupport {

    @Test
    @DisplayName("同层级重名被拒，不同父节点下可以同名")
    void duplicateNameShouldBeRejected() {
        String admin = adminToken();
        long projectId = firstProjectId(admin);
        long subjectId = firstSubjectId(admin, projectId);

        long rootId = create(admin, subjectId, null, "知识点-重名");
        assertThat(rootId).isPositive();

        assertThat(errorCode(HttpMethod.POST, "/admin/knowledge-points", admin,
                body(subjectId, null, "知识点-重名"))).isEqualTo("COMMON_BAD_REQUEST");

        // 挂在别的父节点下同名是允许的
        long childId = create(admin, subjectId, rootId, "知识点-重名");
        assertThat(childId).isPositive();
    }

    @Test
    @DisplayName("被题目引用的知识点不允许删除")
    void referencedKnowledgePointShouldNotBeDeleted() {
        String admin = adminToken();
        long projectId = firstProjectId(admin);
        long subjectId = firstSubjectId(admin, projectId);
        long knowledgePointId = create(admin, subjectId, null, "知识点-被引用");

        data(HttpMethod.POST, "/admin/questions", admin, Map.of(
                "subjectId", subjectId,
                "type", "SINGLE",
                "stem", "引用了知识点的题",
                "options", List.of(Map.of("key", "A", "text", "选项 A"),
                        Map.of("key", "B", "text", "选项 B")),
                "answer", List.of("A"),
                "difficulty", "NORMAL",
                "knowledgePointId", knowledgePointId));

        assertThat(errorCode(HttpMethod.DELETE, "/admin/knowledge-points/" + knowledgePointId, admin, null))
                .isEqualTo("COMMON_BAD_REQUEST");

        // 未被引用的知识点仍可正常删除
        long spareId = create(admin, subjectId, null, "知识点-可删除");
        data(HttpMethod.DELETE, "/admin/knowledge-points/" + spareId, admin, null);
    }

    @Test
    @DisplayName("父子关系不能成环，合法的移动仍然允许")
    void cycleShouldBeRejected() {
        String admin = adminToken();
        long projectId = firstProjectId(admin);
        long subjectId = firstSubjectId(admin, projectId);

        long rootId = create(admin, subjectId, null, "环-根");
        long childId = create(admin, subjectId, rootId, "环-子");

        // 把根挂到自己的子节点下
        assertThat(errorCode(HttpMethod.PUT, "/admin/knowledge-points/" + rootId, admin,
                body(subjectId, childId, "环-根"))).isEqualTo("COMMON_BAD_REQUEST");

        // 挂到自己下面
        assertThat(errorCode(HttpMethod.PUT, "/admin/knowledge-points/" + rootId, admin,
                body(subjectId, rootId, "环-根"))).isEqualTo("COMMON_BAD_REQUEST");

        // 合法操作不能误伤：给子节点换个名字仍应成功
        data(HttpMethod.PUT, "/admin/knowledge-points/" + childId, admin,
                body(subjectId, rootId, "环-子-改名"));
    }

    // ---------------------------------------------------------------- helpers

    private long create(String admin, long subjectId, Long parentId, String name) {
        return data(HttpMethod.POST, "/admin/knowledge-points", admin,
                body(subjectId, parentId, name)).path("id").asLong();
    }

    private Map<String, Object> body(long subjectId, Long parentId, String name) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("subjectId", subjectId);
        body.put("name", name);
        body.put("sortNo", 0);
        if (parentId != null) {
            body.put("parentId", parentId);
        }
        return body;
    }

    private long firstSubjectId(String admin, long projectId) {
        JsonNode subjects = data("/projects/" + projectId + "/subjects", admin);
        assertThat(subjects.size()).isPositive();
        return subjects.get(0).path("id").asLong();
    }
}
