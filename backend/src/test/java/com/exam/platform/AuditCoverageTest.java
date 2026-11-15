package com.exam.platform;

import com.exam.platform.modules.system.support.Audited;
import com.exam.platform.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.http.HttpMethod;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 审计覆盖率与生效性验证。
 *
 * <p>这组用例存在的理由：原来审计靠手写，管理端实际漏了 5 个写接口而无人察觉。
 * 改造为 {@link Audited} 注解后，把「漏记」变成可被 CI 捕获的失败——</p>
 * <ul>
 *   <li>{@link #everyAdminWriteEndpointMustBeAudited()} 静态扫描，新增接口忘加注解直接红；</li>
 *   <li>其余用例真实调用接口，确认注解确实落了库、表达式解析正确、失败操作不写审计。</li>
 * </ul>
 */
@DisplayName("管理端审计：注解覆盖率 + 实际落库")
class AuditCoverageTest extends ApiTestSupport {

    /**
     * 只读的 POST 接口白名单：试组卷只做抽题预览、不落库，不属于「敏感写操作」。
     * 新增条目必须能说明「这个 POST 不改变任何业务数据」。
     */
    private static final Set<String> READ_ONLY_POST_ALLOWLIST = Set.of(
            "PaperAdminController#preview"
    );

    @Test
    @DisplayName("所有管理端写接口都必须带 @Audited")
    void everyAdminWriteEndpointMustBeAudited() {
        // 直接扫类路径而不是从容器取 bean：控制器被 AOP 代理后是 CGLIB 子类，
        // 用 getBeansWithAnnotation 可能扫不到而让用例「假通过」。
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));

        List<String> missing = new ArrayList<>();
        List<Class<?>> scanned = new ArrayList<>();
        for (BeanDefinition candidate : scanner.findCandidateComponents("com.exam.platform")) {
            try {
                scanned.add(Class.forName(candidate.getBeanClassName()));
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e);
            }
        }
        assertThat(scanned).as("扫描到管理端控制器才算有效检查").hasSizeGreaterThan(5);

        for (Class<?> targetClass : scanned) {
            for (Method method : targetClass.getDeclaredMethods()) {
                if (!isWriteMapping(method)) {
                    continue;
                }
                String path = pathOf(targetClass, method);
                if (!path.startsWith("/v1/admin")) {
                    continue;
                }
                if (READ_ONLY_POST_ALLOWLIST.contains(targetClass.getSimpleName() + "#" + method.getName())) {
                    continue;
                }
                if (AnnotatedElementUtils.findMergedAnnotation(method, Audited.class) == null) {
                    missing.add(targetClass.getSimpleName() + "#" + method.getName() + " (" + path + ")");
                }
            }
        }

        assertThat(missing)
                .as("以下管理端写接口缺少 @Audited，请补注解或说明为何只读")
                .isEmpty();
    }

    @Test
    @DisplayName("科目/知识点写操作的审计真正落库，且 target 解析出主键")
    void contentWriteShouldBeAudited() {
        String admin = adminToken();
        long projectId = firstProjectId(admin);

        JsonNode subject = data(HttpMethod.POST, "/admin/subjects", admin, Map.of(
                "projectId", projectId,
                "code", "AUDIT-SUBJECT-" + System.nanoTime(),
                "name", "审计验证科目",
                "status", "ENABLED"));
        long subjectId = subject.path("id").asLong();

        assertThat(auditCount("SUBJECT_CREATE", "exam_subject:" + subjectId))
                .as("新建科目应留下审计，且 target 带主键")
                .isEqualTo(1);

        data(HttpMethod.PUT, "/admin/subjects/" + subjectId, admin, Map.of(
                "name", "审计验证科目-改", "status", "DISABLED"));
        assertThat(lastAuditDetail("SUBJECT_UPDATE", "exam_subject:" + subjectId))
                .as("审计 detail 应解析出改后的名称，验证 SpEL 表达式生效")
                .contains("审计验证科目-改");

        JsonNode point = data(HttpMethod.POST, "/admin/knowledge-points", admin, Map.of(
                "subjectId", subjectId,
                "name", "审计验证知识点"));
        assertThat(auditCount("KNOWLEDGE_POINT_CREATE", "exam_knowledge_point:" + point.path("id").asLong()))
                .isEqualTo(1);

        data(HttpMethod.DELETE, "/admin/knowledge-points/" + point.path("id").asLong(), admin, null);
        data(HttpMethod.DELETE, "/admin/subjects/" + subjectId, admin, null);
        assertThat(auditCount("SUBJECT_DELETE", "exam_subject:" + subjectId)).isEqualTo(1);
    }

    @Test
    @DisplayName("失败的操作不写审计：审计只反映已生效的变更")
    void failedOperationShouldNotBeAudited() {
        String admin = adminToken();
        // 同类其它用例也会产生 SUBJECT_CREATE 审计，因此用「前后差值」而不是绝对数量判断，
        // 否则用例顺序一变就误报。
        int before = auditCount("SUBJECT_CREATE", null, true);

        String code = errorCode(HttpMethod.POST, "/admin/subjects", admin, Map.of(
                "projectId", 999999L,
                "code", "AUDIT-FAIL-" + System.nanoTime(),
                "name", "不该被审计的科目"));

        assertThat(code).isEqualTo("COMMON_NOT_FOUND");
        assertThat(auditCount("SUBJECT_CREATE", null, true))
                .as("项目不存在导致创建失败，不应产生 SUBJECT_CREATE 审计")
                .isEqualTo(before);
    }

    private boolean isWriteMapping(Method method) {
        if (method.isAnnotationPresent(PostMapping.class)
                || method.isAnnotationPresent(PutMapping.class)
                || method.isAnnotationPresent(DeleteMapping.class)
                || method.isAnnotationPresent(PatchMapping.class)) {
            return true;
        }
        RequestMapping mapping = method.getAnnotation(RequestMapping.class);
        return mapping != null && mapping.method().length > 0
                && Arrays.stream(mapping.method())
                .noneMatch(m -> m == org.springframework.web.bind.annotation.RequestMethod.GET);
    }

    private String pathOf(Class<?> targetClass, Method method) {
        String prefix = "";
        RequestMapping classMapping = AnnotatedElementUtils.findMergedAnnotation(targetClass, RequestMapping.class);
        if (classMapping != null && classMapping.value().length > 0) {
            prefix = classMapping.value()[0];
        }
        String suffix = "";
        for (String candidate : mappingPaths(method)) {
            suffix = candidate;
            break;
        }
        return prefix + suffix;
    }

    private String[] mappingPaths(Method method) {
        PostMapping post = method.getAnnotation(PostMapping.class);
        if (post != null) {
            return post.value();
        }
        PutMapping put = method.getAnnotation(PutMapping.class);
        if (put != null) {
            return put.value();
        }
        DeleteMapping delete = method.getAnnotation(DeleteMapping.class);
        if (delete != null) {
            return delete.value();
        }
        PatchMapping patch = method.getAnnotation(PatchMapping.class);
        if (patch != null) {
            return patch.value();
        }
        RequestMapping mapping = method.getAnnotation(RequestMapping.class);
        return mapping == null ? new String[0] : mapping.value();
    }

    private int auditCount(String action, String target) {
        return auditCount(action, target, false);
    }

    private int auditCount(String action, String target, boolean anyTarget) {
        Integer count = anyTarget
                ? jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_audit_log WHERE action = ? AND deleted = 0",
                Integer.class, action)
                : jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_audit_log WHERE action = ? AND target = ? AND deleted = 0",
                Integer.class, action, target);
        return count == null ? 0 : count;
    }

    private String lastAuditDetail(String action, String target) {
        return jdbcTemplate.queryForList(
                "SELECT detail FROM sys_audit_log WHERE action = ? AND target = ? AND deleted = 0 "
                        + "ORDER BY id DESC LIMIT 1", String.class, action, target)
                .stream().findFirst().orElse(null);
    }
}
