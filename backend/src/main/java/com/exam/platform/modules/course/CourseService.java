package com.exam.platform.modules.course;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.exam.platform.common.api.PageQuery;
import com.exam.platform.common.api.PageResult;
import com.exam.platform.common.exception.BizException;
import com.exam.platform.common.util.Entities;
import com.exam.platform.modules.content.ContentService;
import com.exam.platform.modules.content.entity.ExamSubject;
import com.exam.platform.modules.course.dto.CourseDetailView;
import com.exam.platform.modules.course.dto.CourseSaveRequest;
import com.exam.platform.modules.course.dto.LessonSaveRequest;
import com.exam.platform.modules.course.dto.LessonView;
import com.exam.platform.modules.course.entity.CourseInfo;
import com.exam.platform.modules.course.entity.CourseLesson;
import com.exam.platform.modules.course.entity.CourseProgress;
import com.exam.platform.modules.course.mapper.CourseInfoMapper;
import com.exam.platform.modules.course.mapper.CourseLessonMapper;
import com.exam.platform.modules.course.mapper.CourseProgressMapper;
import com.exam.platform.modules.entitlement.EntitlementService;
import com.exam.platform.modules.entitlement.entity.EntitlementAccount;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 课程服务。
 *
 * <p>观看权限与商城打通：付费课程购买成功后由支付回调发放
 * {@code COURSE} 类型、{@code resourceId = 课程 ID} 的权益，本服务只做校验。
 * 免费课程与试看课时不校验权益。</p>
 */
@Service
@RequiredArgsConstructor
public class CourseService {

    private final CourseInfoMapper courseMapper;
    private final CourseLessonMapper lessonMapper;
    private final CourseProgressMapper progressMapper;
    private final ContentService contentService;
    private final EntitlementService entitlementService;

    // ------------------------------------------------------------------ 管理端

    public PageResult<CourseInfo> page(String keyword, String status, Long projectId, PageQuery pageQuery) {
        Page<CourseInfo> page = courseMapper.selectPage(pageQuery.toPage(), Wrappers.<CourseInfo>lambdaQuery()
                .like(keyword != null && !keyword.isBlank(), CourseInfo::getTitle, keyword)
                .eq(status != null && !status.isBlank(), CourseInfo::getStatus, status)
                .eq(projectId != null, CourseInfo::getProjectId, projectId)
                .orderByAsc(CourseInfo::getSortNo)
                .orderByDesc(CourseInfo::getId));
        return PageResult.of(page.getRecords(), page.getTotal(), page.getCurrent(), page.getSize());
    }

    public CourseInfo getCourse(Long id) {
        CourseInfo course = courseMapper.selectById(id);
        if (course == null) {
            throw BizException.notFound("课程");
        }
        return course;
    }

    public List<CourseLesson> listLessons(Long courseId) {
        return lessonMapper.selectList(Wrappers.<CourseLesson>lambdaQuery()
                .eq(CourseLesson::getCourseId, courseId)
                .orderByAsc(CourseLesson::getSortNo)
                .orderByAsc(CourseLesson::getId));
    }

    @Transactional
    public CourseInfo save(CourseSaveRequest request) {
        validate(request);
        assertCodeAvailable(request.code(), null);
        CourseInfo course = new CourseInfo();
        apply(course, request);
        course.setLessonCount(0);
        Entities.forInsert(course);
        courseMapper.insert(course);
        if (request.lessons() != null && !request.lessons().isEmpty()) {
            saveLessons(course.getId(), request.lessons());
        }
        return getCourse(course.getId());
    }

    @Transactional
    public CourseInfo update(Long id, CourseSaveRequest request) {
        validate(request);
        CourseInfo exist = getCourse(id);
        assertCodeAvailable(request.code(), id);
        // 已发布课程只允许下架：课时改动需通过独立接口，避免误改已售内容。
        // 但「下架」这个动作本身不能吞掉本次请求里的其它字段——原实现只写 status 就返回，
        // 前端照样提示「保存成功」，运营改的标题/讲师/价格全部丢失，且没有任何迹象。
        if (CourseInfo.STATUS_PUBLISHED.equals(exist.getStatus())
                && !CourseInfo.STATUS_PUBLISHED.equals(request.status())) {
            CourseInfo offline = new CourseInfo();
            offline.setId(id);
            offline.setStatus(request.status());
            courseMapper.updateById(offline);
        }
        CourseInfo update = new CourseInfo();
        apply(update, request);
        Entities.forUpdate(update, id);
        courseMapper.updateById(update);
        return getCourse(id);
    }

    @Transactional
    public void delete(Long id) {
        getCourse(id);
        courseMapper.deleteById(id);
    }

    @Transactional
    public CourseLesson saveLesson(Long courseId, LessonSaveRequest request) {
        getCourse(courseId);
        if (request.title() == null || request.title().isBlank()) {
            throw BizException.badRequest("课时标题不能为空");
        }
        CourseLesson lesson = new CourseLesson();
        lesson.setCourseId(courseId);
        apply(lesson, request);
        Entities.forInsert(lesson);
        lessonMapper.insert(lesson);
        refreshLessonCount(courseId);
        return lesson;
    }

    @Transactional
    public CourseLesson updateLesson(Long lessonId, LessonSaveRequest request) {
        CourseLesson exist = lessonMapper.selectById(lessonId);
        if (exist == null) {
            throw BizException.notFound("课时");
        }
        CourseLesson update = new CourseLesson();
        apply(update, request);
        Entities.forUpdate(update, lessonId);
        lessonMapper.updateById(update);
        return lessonMapper.selectById(lessonId);
    }

    @Transactional
    public void deleteLesson(Long lessonId) {
        CourseLesson exist = lessonMapper.selectById(lessonId);
        if (exist == null) {
            throw BizException.notFound("课时");
        }
        lessonMapper.deleteById(lessonId);
        refreshLessonCount(exist.getCourseId());
    }

    // ------------------------------------------------------------------ 考生端

    public PageResult<CourseInfo> pagePublished(Long projectId, String keyword, PageQuery pageQuery) {
        Page<CourseInfo> page = courseMapper.selectPage(pageQuery.toPage(), Wrappers.<CourseInfo>lambdaQuery()
                .eq(CourseInfo::getStatus, CourseInfo.STATUS_PUBLISHED)
                .eq(projectId != null, CourseInfo::getProjectId, projectId)
                .like(keyword != null && !keyword.isBlank(), CourseInfo::getTitle, keyword)
                .orderByAsc(CourseInfo::getSortNo)
                .orderByDesc(CourseInfo::getId));
        return PageResult.of(page.getRecords(), page.getTotal(), page.getCurrent(), page.getSize());
    }

    /** 课程详情：按权益决定是否下发付费课时内容。 */
    public CourseDetailView detail(Long courseId, Long userId) {
        CourseInfo course = getCourse(courseId);
        boolean accessible = hasAccess(course, userId);
        List<CourseLesson> lessons = listLessons(courseId);
        Map<Long, CourseProgress> progressMap = loadProgress(userId, courseId);

        List<LessonView> views = lessons.stream()
                .map(lesson -> toView(lesson, accessible, progressMap.get(lesson.getId())))
                .toList();
        int finished = (int) views.stream().filter(LessonView::finished).count();
        return new CourseDetailView(course.getId(), course.getCode(), course.getTitle(), course.getSummary(),
                course.getCoverUrl(), course.getLecturer(), course.getProjectId(), course.getSubjectId(),
                course.getPriceFen(), course.getAccessType(), course.getStatus(), accessible,
                views.size(), finished, views);
    }

    /** 单个课时的内容获取：仍会再校验一次权限，防止前端绕过列表直接请求。 */
    public LessonView lesson(Long courseId, Long lessonId, Long userId) {
        CourseInfo course = getCourse(courseId);
        CourseLesson lesson = lessonMapper.selectById(lessonId);
        if (lesson == null || !lesson.getCourseId().equals(courseId)) {
            throw BizException.notFound("课时");
        }
        Map<Long, CourseProgress> progressMap = loadProgress(userId, courseId);
        return toView(lesson, hasAccess(course, userId), progressMap.get(lessonId));
    }

    /** 上报学习进度：以「用户 + 课时」唯一，重复上报取较大值避免回退。 */
    @Transactional
    public CourseProgress reportProgress(Long courseId, Long lessonId, Long userId,
                                         Integer progressSeconds, Boolean finished) {
        CourseInfo course = getCourse(courseId);
        CourseLesson lesson = lessonMapper.selectById(lessonId);
        if (lesson == null || !lesson.getCourseId().equals(courseId)) {
            throw BizException.notFound("课时");
        }
        if (!hasAccess(course, userId) && lesson.getFreePreview() != 1) {
            throw new BizException("COURSE_NOT_ENTITLED", "尚未开通该课程，请先购买");
        }

        int seconds = progressSeconds == null ? 0 : Math.max(progressSeconds, 0);
        boolean done = Boolean.TRUE.equals(finished);
        LocalDateTime now = LocalDateTime.now();

        CourseProgress progress = progressMapper.selectOne(Wrappers.<CourseProgress>lambdaQuery()
                .eq(CourseProgress::getUserId, userId)
                .eq(CourseProgress::getLessonId, lessonId));
        if (progress == null) {
            progress = new CourseProgress();
            progress.setUserId(userId);
            progress.setCourseId(courseId);
            progress.setLessonId(lessonId);
            progress.setProgressSeconds(seconds);
            progress.setFinished(done ? 1 : 0);
            progress.setLastViewedAt(now);
            Entities.forInsert(progress);
            try {
                progressMapper.insert(progress);
            } catch (DuplicateKeyException e) {
                progress = progressMapper.selectOne(Wrappers.<CourseProgress>lambdaQuery()
                        .eq(CourseProgress::getUserId, userId)
                        .eq(CourseProgress::getLessonId, lessonId));
                if (progress == null) {
                    throw e;
                }
                return mergeProgress(progress, seconds, done, now);
            }
            return progress;
        }
        return mergeProgress(progress, seconds, done, now);
    }

    private CourseProgress mergeProgress(CourseProgress progress, int seconds, boolean done, LocalDateTime now) {
        progress.setProgressSeconds(Math.max(progress.getProgressSeconds() == null ? 0 : progress.getProgressSeconds(),
                seconds));
        progress.setFinished(done ? 1 : progress.getFinished());
        progress.setLastViewedAt(now);
        progressMapper.updateById(progress);
        return progress;
    }

    /** 是否可观看付费课时：免费课程 / 持有有效 COURSE 权益。 */
    public boolean hasAccess(CourseInfo course, Long userId) {
        if (CourseInfo.ACCESS_FREE.equals(course.getAccessType())) {
            return true;
        }
        if (userId == null) {
            return false;
        }
        return entitlementService.hasActive(userId, EntitlementAccount.TYPE_COURSE, course.getId());
    }

    private Map<Long, CourseProgress> loadProgress(Long userId, Long courseId) {
        Map<Long, CourseProgress> map = new HashMap<>();
        if (userId == null) {
            return map;
        }
        List<CourseProgress> list = progressMapper.selectList(Wrappers.<CourseProgress>lambdaQuery()
                .eq(CourseProgress::getUserId, userId)
                .eq(CourseProgress::getCourseId, courseId));
        list.forEach(p -> map.put(p.getLessonId(), p));
        return map;
    }

    private LessonView toView(CourseLesson lesson, boolean accessible, CourseProgress progress) {
        boolean unlocked = lesson.getFreePreview() != null && lesson.getFreePreview() == 1 || accessible;
        return new LessonView(lesson.getId(), lesson.getTitle(), lesson.getDurationSeconds(),
                lesson.getFreePreview() != null && lesson.getFreePreview() == 1,
                lesson.getSortNo(), lesson.getStatus(), !unlocked,
                unlocked ? lesson.getVideoUrl() : null,
                unlocked ? lesson.getContent() : null,
                progress == null ? 0 : progress.getProgressSeconds(),
                progress != null && progress.getFinished() != null && progress.getFinished() == 1);
    }

    private void apply(CourseInfo course, CourseSaveRequest request) {
        course.setProjectId(request.projectId());
        course.setSubjectId(request.subjectId());
        course.setCode(request.code());
        course.setTitle(request.title());
        course.setSummary(request.summary());
        course.setCoverUrl(request.coverUrl());
        course.setLecturer(request.lecturer());
        course.setPriceFen(request.priceFen() == null ? 0L : request.priceFen());
        course.setAccessType(request.accessType() == null ? CourseInfo.ACCESS_FREE : request.accessType());
        course.setStatus(request.status() == null ? CourseInfo.STATUS_DRAFT : request.status());
        course.setSortNo(request.sortNo() == null ? 0 : request.sortNo());
    }

    private void apply(CourseLesson lesson, LessonSaveRequest request) {
        lesson.setTitle(request.title());
        lesson.setContent(request.content());
        lesson.setVideoUrl(request.videoUrl());
        lesson.setDurationSeconds(request.durationSeconds() == null ? 0 : request.durationSeconds());
        lesson.setFreePreview(Boolean.TRUE.equals(request.freePreview()) ? 1 : 0);
        lesson.setSortNo(request.sortNo() == null ? 0 : request.sortNo());
        lesson.setStatus(request.status() == null ? "ENABLED" : request.status());
    }

    private void saveLessons(Long courseId, List<LessonSaveRequest> lessons) {
        int autoSortNo = 0;
        for (LessonSaveRequest item : lessons) {
            CourseLesson lesson = new CourseLesson();
            lesson.setCourseId(courseId);
            apply(lesson, item);
            if (item.sortNo() == null) {
                // 序号只在「没显式给」时按顺序补；原来的写法无论给没给都会自增，
                // 显式 sortNo 与自动序号混用时会出现重号，课时顺序变成随机
                lesson.setSortNo(autoSortNo);
            }
            autoSortNo++;
            Entities.forInsert(lesson);
            lessonMapper.insert(lesson);
        }
        refreshLessonCount(courseId);
    }

    private void refreshLessonCount(Long courseId) {
        Long count = lessonMapper.selectCount(Wrappers.<CourseLesson>lambdaQuery()
                .eq(CourseLesson::getCourseId, courseId));
        CourseInfo update = new CourseInfo();
        update.setId(courseId);
        update.setLessonCount(count.intValue());
        courseMapper.updateById(update);
    }

    /** 课程编码租户内唯一：先查一次给出可读提示，避免只看到数据库唯一键冲突。 */
    private void assertCodeAvailable(String code, Long excludeId) {
        if (code == null || code.isBlank()) {
            return;
        }
        Long count = courseMapper.selectCount(Wrappers.<CourseInfo>lambdaQuery()
                .eq(CourseInfo::getCode, code.trim())
                .ne(excludeId != null, CourseInfo::getId, excludeId));
        if (count != null && count > 0) {
            throw BizException.badRequest("课程编码「" + code.trim() + "」已存在，请换一个");
        }
    }

    private void validate(CourseSaveRequest request) {
        if (request.code() == null || request.code().isBlank()) {
            throw BizException.badRequest("课程编码不能为空");
        }
        if (request.title() == null || request.title().isBlank()) {
            throw BizException.badRequest("课程标题不能为空");
        }
        if (request.accessType() != null
                && !CourseInfo.ACCESS_FREE.equals(request.accessType())
                && !CourseInfo.ACCESS_ENTITLED.equals(request.accessType())) {
            throw BizException.badRequest("观看条件只能是 FREE 或 ENTITLED");
        }
        if (request.status() != null
                && !CourseInfo.STATUS_DRAFT.equals(request.status())
                && !CourseInfo.STATUS_PUBLISHED.equals(request.status())
                && !CourseInfo.STATUS_OFFLINE.equals(request.status())) {
            throw BizException.badRequest("课程状态只能是 DRAFT / PUBLISHED / OFFLINE");
        }
        if (request.priceFen() != null && request.priceFen() < 0) {
            throw BizException.badRequest("课程价格必须为非负整数（单位：分）");
        }
        if (request.projectId() != null) {
            contentService.getProject(request.projectId());
        }
        // 科目必须属于所选项目：挂错项目的课程在「项目 → 科目」任一层筛选里都查不到，
        // 而列表只显示项目名，看起来一切正常
        if (request.subjectId() != null) {
            var subject = contentService.getSubject(request.subjectId());
            if (request.projectId() != null && !request.projectId().equals(subject.getProjectId())) {
                throw BizException.badRequest("所选科目不属于该项目，请重新选择");
            }
        }
    }
}
