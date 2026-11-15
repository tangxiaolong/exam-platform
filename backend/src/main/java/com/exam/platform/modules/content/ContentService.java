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
        return subjectMapper.selectList(Wrappers.<ExamSubject>lambdaQuery()
                .eq(ExamSubject::getProjectId, projectId)
                .eq(onlyEnabled, ExamSubject::getStatus, STATUS_ENABLED)
                .orderByAsc(ExamSubject::getSortNo)
                .orderByAsc(ExamSubject::getId));
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
        knowledgePointMapper.insert(point);
        return point;
    }

    @Transactional
    public ExamKnowledgePoint updateKnowledgePoint(Long id, ExamKnowledgePoint point) {
        Entities.forUpdate(point, id);
        knowledgePointMapper.updateById(point);
        return knowledgePointMapper.selectById(id);
    }

    @Transactional
    public void deleteKnowledgePoint(Long id) {
        knowledgePointMapper.deleteById(id);
    }
}
