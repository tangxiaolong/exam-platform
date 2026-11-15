package com.exam.platform;

import com.exam.platform.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("课程：免费可看 / 付费需权益 / 进度断点续学")
class CourseFlowTest extends ApiTestSupport {

    @Test
    @DisplayName("免费课程无需权益即可拿到课时内容")
    void freeCourseShouldBeAccessible() {
        String admin = adminToken();
        String student = wechatToken("course-free-student", "免费课考生");

        long courseId = createCourse(admin, "COURSE-FREE-TEST", "免费测试课", "FREE",
                List.of(lesson("第一节", true, "第一节课内容"),
                        lesson("第二节", false, "第二节课内容")));

        JsonNode detail = data("/courses/" + courseId, student);
        assertThat(detail.path("accessible").asBoolean()).isTrue();
        assertThat(detail.path("lessonCount").asInt()).isEqualTo(2);

        JsonNode lessons = detail.path("lessons");
        assertThat(lessons.get(0).path("locked").asBoolean()).isFalse();
        assertThat(lessons.get(0).path("content").asText()).isEqualTo("第一节课内容");
        assertThat(lessons.get(1).path("content").asText()).isEqualTo("第二节课内容");
    }

    @Test
    @DisplayName("付费课程未购买时锁定内容，购买后解锁")
    void paidCourseShouldRequireEntitlement() {
        String admin = adminToken();
        String student = wechatToken("course-paid-student", "付费课考生");

        long courseId = createCourse(admin, "COURSE-PAID-TEST", "付费测试课", "ENTITLED",
                List.of(lesson("试看课时", true, "试看内容"),
                        lesson("正课课时", false, "正课内容")));

        // 未购买：试看课时可见，正课课时内容不下发
        JsonNode locked = data("/courses/" + courseId, student);
        assertThat(locked.path("accessible").asBoolean()).isFalse();
        JsonNode lockedLessons = locked.path("lessons");
        assertThat(lockedLessons.get(0).path("locked").asBoolean()).isFalse();
        assertThat(lockedLessons.get(0).path("content").asText()).isEqualTo("试看内容");
        assertThat(lockedLessons.get(1).path("locked").asBoolean()).isTrue();
        // 全局序列化策略为 non_null，未解锁时 content 字段直接不下发（MissingNode）
        assertThat(hasContent(lockedLessons.get(1))).isFalse();

        // 直接请求锁定课时同样拿不到内容
        long lockedLessonId = lockedLessons.get(1).path("id").asLong();
        assertThat(hasContent(data("/courses/" + courseId + "/lessons/" + lockedLessonId, student))).isFalse();

        // 未购买时不能上报正课进度
        assertThat(errorCode(HttpMethod.POST,
                "/courses/" + courseId + "/lessons/" + lockedLessonId + "/progress?progressSeconds=30",
                student, null)).isEqualTo("COURSE_NOT_ENTITLED");

        // 购买课程商品 → 支付回调发放 COURSE 权益 → 解锁
        long productId = createCourseProduct(admin, courseId, "COURSE-PAID-TEST-P", 9900L);
        JsonNode order = data(HttpMethod.POST, "/orders", student,
                Map.of("productId", productId, "quantity", 1));
        JsonNode payment = data(HttpMethod.POST, "/orders/" + order.path("id").asLong() + "/payments",
                student, null);
        dataUrl(payment.path("mockNotifyUrl").asText(), HttpMethod.POST, null, Map.of());

        JsonNode unlocked = data("/courses/" + courseId, student);
        assertThat(unlocked.path("accessible").asBoolean()).isTrue();
        assertThat(unlocked.path("lessons").get(1).path("content").asText()).isEqualTo("正课内容");
    }

    @Test
    @DisplayName("学习进度以较大值合并，重复上报不回退")
    void progressShouldNotRegress() {
        String admin = adminToken();
        String student = wechatToken("course-progress-student", "进度考生");

        long courseId = createCourse(admin, "COURSE-PROGRESS-TEST", "进度测试课", "FREE",
                List.of(lesson("进度课时", true, "内容")));
        long lessonId = data("/courses/" + courseId, student).path("lessons").get(0).path("id").asLong();

        JsonNode first = data(HttpMethod.POST,
                "/courses/" + courseId + "/lessons/" + lessonId + "/progress?progressSeconds=120",
                student, null);
        assertThat(first.path("progressSeconds").asInt()).isEqualTo(120);

        // 回看更早位置不应把进度改小
        JsonNode back = data(HttpMethod.POST,
                "/courses/" + courseId + "/lessons/" + lessonId + "/progress?progressSeconds=30",
                student, null);
        assertThat(back.path("progressSeconds").asInt()).isEqualTo(120);

        JsonNode finished = data(HttpMethod.POST,
                "/courses/" + courseId + "/lessons/" + lessonId + "/progress?progressSeconds=300&finished=true",
                student, null);
        assertThat(finished.path("finished").asInt()).isEqualTo(1);

        // 详情聚合出已完成课时数
        JsonNode detail = data("/courses/" + courseId, student);
        assertThat(detail.path("finishedLessonCount").asInt()).isEqualTo(1);
        assertThat(detail.path("lessons").get(0).path("finished").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("考生只看到已发布课程，草稿课程不可见")
    void studentShouldOnlySeePublishedCourses() {
        String admin = adminToken();
        createCourse(admin, "COURSE-DRAFT-TEST", "草稿课程", "FREE",
                List.of(lesson("草稿课时", true, "x")), "DRAFT");

        String student = wechatToken("course-list-student", "列表考生");
        JsonNode page = data("/courses?pageNo=1&pageSize=100", student);
        for (JsonNode course : page.path("list")) {
            assertThat(course.path("status").asText()).isEqualTo("PUBLISHED");
        }
        assertThat(data("/admin/courses?status=DRAFT&pageNo=1&pageSize=100", admin)
                .path("total").asLong()).isPositive();
    }

    private Map<String, Object> lesson(String title, boolean freePreview, String content) {
        return Map.of("title", title, "content", content, "freePreview", freePreview,
                "durationSeconds", 600, "status", "ENABLED");
    }

    /** 全局序列化策略为 non_null，判定“未下发内容”需同时兼容字段缺失与 null。 */
    private boolean hasContent(JsonNode lesson) {
        JsonNode content = lesson.get("content");
        return content != null && content.isTextual() && !content.asText().isBlank();
    }

    private long createCourse(String admin, String code, String title, String accessType,
                              List<Map<String, Object>> lessons) {
        return createCourse(admin, code, title, accessType, lessons, "PUBLISHED");
    }

    private long createCourse(String admin, String code, String title, String accessType,
                              List<Map<String, Object>> lessons, String status) {
        JsonNode created = data(HttpMethod.POST, "/admin/courses", admin, Map.of(
                "code", code,
                "title", title,
                "summary", "自动化测试课程",
                "accessType", accessType,
                "priceFen", 0,
                "status", status,
                "lessons", lessons));
        return created.path("id").asLong();
    }

    private long createCourseProduct(String admin, long courseId, String code, long priceFen) {
        return data(HttpMethod.POST, "/admin/products", admin, Map.of(
                "code", code,
                "name", "课程商品-" + code,
                "type", "COURSE",
                "priceFen", priceFen,
                "validityDays", 180,
                "resourceType", "COURSE",
                "resourceId", courseId,
                "status", "ON_SALE")).path("id").asLong();
    }
}
