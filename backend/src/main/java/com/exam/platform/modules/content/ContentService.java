package com.exam.platform.modules.content;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.exam.platform.common.api.PageQuery;
import com.exam.platform.common.api.PageResult;
import com.exam.platform.common.exception.BizException;
import com.exam.platform.common.util.Entities;
import com.exam.platform.modules.content.entity.ExamKnowledgePoint;
import com.exam.platform.modules.content.entity.ExamProject;
import com.exam.platform.modules.content.entity.ExamSubject;
import com.exam.platform.modules.content.mapper.ExamKnowledgePointMapper;
import com.exam.platform.modules.content.mapper.ExamProjectMapper;
import com.exam.platform.modules.content.mapper.ExamSubjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 考试项目 / 科目 / 知识点。 */
@Service
@RequiredArgsConstructor
public class ContentService {

    public static final String PROJECT_PUBLISHED = "PUBLISHED";
    private static final String STATUS_ENABLED = "ENABLED";

    private final ExamProjectMapper projectMapper;
    private final ExamSubjectMapper subjectMapper;
    private final ExamKnowledgePointMapper knowledgePointMapper;

    /** 引用检查与父子链校验需要跨表查询，直接走 SQL。 */
    private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    public PageResult<ExamProject> pageProjects(String keyword, String status, PageQuery pageQuery,
                                                boolean onlyPublished) {
        Page<ExamProject> page = projectMapper.selectPage(pageQuery.toPage(),
                Wrappers.<ExamProject>lambdaQuery()
                        .eq(onlyPublished, ExamProject::getStatus, PROJECT_PUBLISHED)
                        .eq(!onlyPublished && status != null && !status.isBlank(), ExamProject::getStatus, status)
                        .and(keyword != null && !keyword.isBlank(),
                                w -> w.like(ExamProject::getName, keyword).or().like(ExamProject::getCode, keyword))
                        .orderByAsc(ExamProject::getSortNo)
                        .orderByDesc(ExamProject::getId));
        return PageResult.of(page.getRecords(), page.getTotal(), page.getCurrent(), page.getSize());
    }

    public ExamProject getProject(Long id) {
        ExamProject project = projectMapper.selectById(id);
        if (project == null) {
            throw BizException.notFound("考试项目");
        }
        return project;
    }

    public List<ExamSubject> listSubjects(Long projectId, boolean onlyEnabled) {
        List<ExamSubject> subjects = subjectMapper.selectList(Wrappers.<ExamSubject>lambdaQuery()
                .eq(ExamSubject::getProjectId, projectId)
                .eq(onlyEnabled, ExamSubject::getStatus, STATUS_ENABLED)
                .orderByAsc(ExamSubject::getSortNo)
                .orderByAsc(ExamSubject::getId));
        if (!subjects.isEmpty()) {
            var counts = new java.util.HashMap<Long, Integer>();
            jdbcTemplate.queryForList(
                    "SELECT subject_id, COUNT(*) AS cnt FROM qb_question"
                            + " WHERE tenant_id = ? AND deleted = 0 AND status = 'ENABLED'"
                            + " GROUP BY subject_id",
                    com.exam.platform.common.security.UserContext.tenantId())
                    .forEach(row -> counts.put(
                            ((Number) row.get("subject_id")).longValue(),
                            ((Number) row.get("cnt")).intValue()));
            subjects.forEach(subject ->
                    subject.setQuestionCount(counts.getOrDefault(subject.getId(), 0)));
        }
        return subjects;
    }

    public ExamSubject getSubject(Long id) {
        ExamSubject subject = subjectMapper.selectById(id);
        if (subject == null) {
            throw BizException.notFound("科目");
        }
        return subject;
    }

    public List<ExamKnowledgePoint> listKnowledgePoints(Long subjectId) {
        return knowledgePointMapper.selectList(Wrappers.<ExamKnowledgePoint>lambdaQuery()
                .eq(subjectId != null, ExamKnowledgePoint::getSubjectId, subjectId)
                .orderByAsc(ExamKnowledgePoint::getSortNo)
                .orderByAsc(ExamKnowledgePoint::getId));
    }

    @Transactional
    public ExamProject saveProject(ExamProject project) {
        Entities.forInsert(project);
        if (project.getStatus() == null) {
            project.setStatus("DRAFT");
        }
        projectMapper.insert(project);
        return project;
    }

    @Transactional
    public ExamProject updateProject(Long id, ExamProject project) {
        getProject(id);
        Entities.forUpdate(project, id);
        projectMapper.updateById(project);
        return getProject(id);
    }

    @Transactional
    public void deleteProject(Long id) {
        getProject(id);
        projectMapper.deleteById(id);
    }

    @Transactional
    public ExamSubject saveSubject(ExamSubject subject) {
        getProject(subject.getProjectId());
        Entities.forInsert(subject);
        if (subject.getStatus() == null) {
            subject.setStatus(STATUS_ENABLED);
        }
        subjectMapper.insert(subject);
        return subject;
    }

    @Transactional
    public ExamSubject updateSubject(Long id, ExamSubject subject) {
        getSubject(id);
        Entities.forUpdate(subject, id);
        subjectMapper.updateById(subject);
        return getSubject(id);
    }

    @Transactional
    public void deleteSubject(Long id) {
        getSubject(id);
        subjectMapper.deleteById(id);
    }

    @Transactional
    public ExamKnowledgePoint saveKnowledgePoint(ExamKnowledgePoint point) {
        getSubject(point.getSubjectId());
        Entities.forInsert(point);
        // 同层级重名会让下拉框出现两个无法区分的选项
        Long duplicated = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM exam_knowledge_point WHERE tenant_id = ? AND subject_id = ? "
                        + "AND name = ? AND deleted = 0 "
                        + "AND (parent_id = ? OR (parent_id IS NULL AND ? IS NULL))",
                Long.class, com.exam.platform.common.security.UserContext.tenantId(),
                point.getSubjectId(), point.getName(),
                point.getParentId(), point.getParentId());
        if (duplicated != null && duplicated > 0) {
            throw BizException.badRequest("同层级已存在同名知识点：" + point.getName());
        }
        knowledgePointMapper.insert(point);
        return point;
    }

    @Transactional
    public ExamKnowledgePoint updateKnowledgePoint(Long id, ExamKnowledgePoint point) {
        Entities.forUpdate(point, id);
        // 沿父链向上走：新父节点若出现在自己下面，挂上去就成环，前端树形渲染会死循环
        Long cursor = point.getParentId();
        int guard = 0;
        while (cursor != null && guard++ < 200) {
            if (cursor.equals(id)) {
                throw BizException.badRequest("不能把知识点挂到它自己或它的子节点下");
            }
            List<Long> rows = jdbcTemplate.queryForList(
                    "SELECT parent_id FROM exam_knowledge_point "
                            + "WHERE tenant_id = ? AND id = ? AND deleted = 0",
                    Long.class, com.exam.platform.common.security.UserContext.tenantId(), cursor);
            cursor = rows.isEmpty() ? null : rows.get(0);
        }
        knowledgePointMapper.updateById(point);
        return knowledgePointMapper.selectById(id);
    }

    @Transactional
    public void deleteKnowledgePoint(Long id) {
        // 逻辑删除后题目仍指向这个 ID：组卷时永远抽不到，只报「可用 0 道」，看不出根因
        Long questionRefs = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM qb_question WHERE tenant_id = ? AND knowledge_point_id = ? "
                        + "AND deleted = 0",
                Long.class, com.exam.platform.common.security.UserContext.tenantId(), id);
        if (questionRefs != null && questionRefs > 0) {
            throw BizException.badRequest("该知识点下还有 " + questionRefs + " 道题，请先调整题目归属");
        }
        Long ruleRefs = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM exam_paper_rule WHERE tenant_id = ? AND knowledge_point_id = ? "
                        + "AND deleted = 0",
                Long.class, com.exam.platform.common.security.UserContext.tenantId(), id);
        if (ruleRefs != null && ruleRefs > 0) {
            throw BizException.badRequest("有 " + ruleRefs + " 条组卷规则引用了该知识点，请先调整规则");
        }
        knowledgePointMapper.deleteById(id);
    }
}
