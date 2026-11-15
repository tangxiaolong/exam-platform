package com.exam.platform.modules.demo;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.exam.platform.common.util.Json;
import com.exam.platform.modules.content.entity.ExamProject;
import com.exam.platform.modules.content.entity.ExamSubject;
import com.exam.platform.modules.content.mapper.ExamProjectMapper;
import com.exam.platform.modules.content.mapper.ExamSubjectMapper;
import com.exam.platform.modules.course.entity.CourseInfo;
import com.exam.platform.modules.course.entity.CourseLesson;
import com.exam.platform.modules.course.mapper.CourseInfoMapper;
import com.exam.platform.modules.course.mapper.CourseLessonMapper;
import com.exam.platform.modules.exam.ExamSessionService;
import com.exam.platform.modules.exam.dto.SessionSaveRequest;
import com.exam.platform.modules.paper.entity.ExamPaper;
import com.exam.platform.modules.paper.mapper.ExamPaperMapper;
import com.exam.platform.modules.paper.model.PaperQuestionItem;
import com.exam.platform.modules.question.entity.QbQuestion;
import com.exam.platform.modules.question.mapper.QbQuestionMapper;
import com.exam.platform.modules.question.model.OptionItem;
import com.exam.platform.modules.system.entity.SysTenant;
import com.exam.platform.modules.system.entity.SysUser;
import com.exam.platform.modules.system.mapper.SysTenantMapper;
import com.exam.platform.modules.system.mapper.SysUserMapper;
import com.exam.platform.modules.trade.entity.MallProduct;
import com.exam.platform.modules.trade.mapper.MallProductMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 演示数据初始化：幂等，仅在库中没有对应数据时写入。
 * 生产环境通过 {@code app.demo-data.enabled=false} 关闭。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.demo-data.enabled", havingValue = "true", matchIfMissing = true)
public class DemoDataInitializer implements ApplicationRunner {

    private final SysTenantMapper tenantMapper;
    private final SysUserMapper userMapper;
    private final ExamProjectMapper projectMapper;
    private final ExamSubjectMapper subjectMapper;
    private final QbQuestionMapper questionMapper;
    private final ExamPaperMapper paperMapper;
    private final MallProductMapper productMapper;
    private final CourseInfoMapper courseMapper;
    private final CourseLessonMapper lessonMapper;
    private final ExamSessionService sessionService;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        seedTenant();
        seedUsers();
        Long projectId = seedContent();
        if (projectId != null) {
            seedProducts(projectId);
            seedCourses(projectId);
        }
        log.info("演示数据初始化完成");
    }

    private void seedTenant() {
        if (tenantMapper.selectCount(Wrappers.<SysTenant>lambdaQuery()
                .eq(SysTenant::getCode, "PLATFORM")) > 0) {
            return;
        }
        SysTenant tenant = new SysTenant();
        tenant.setCode("PLATFORM");
        tenant.setName("平台运营方");
        tenant.setStatus("ENABLED");
        tenantMapper.insert(tenant);
    }

    private void seedUsers() {
        if (userMapper.selectCount(Wrappers.<SysUser>lambdaQuery()
                .eq(SysUser::getUsername, "admin")) == 0) {
            SysUser admin = new SysUser();
            admin.setTenantId(1L);
            admin.setUsername("admin");
            admin.setPassword(passwordEncoder.encode("admin123"));
            admin.setNickname("平台管理员");
            admin.setRoleCode("ADMIN");
            admin.setStatus("ENABLED");
            userMapper.insert(admin);
            log.info("已创建管理员账号 admin / admin123");
        }
        if (userMapper.selectCount(Wrappers.<SysUser>lambdaQuery()
                .eq(SysUser::getUsername, "student")) == 0) {
            SysUser student = new SysUser();
            student.setTenantId(1L);
            student.setUsername("student");
            student.setPassword(passwordEncoder.encode("123456"));
            student.setNickname("体验考生");
            student.setRoleCode("STUDENT");
            student.setStatus("ENABLED");
            userMapper.insert(student);
        }
    }

    private Long seedContent() {
        ExamProject project = projectMapper.selectOne(Wrappers.<ExamProject>lambdaQuery()
                .eq(ExamProject::getCode, "PMP").last("LIMIT 1"));
        if (project == null) {
            project = new ExamProject();
            project.setTenantId(1L);
            project.setCode("PMP");
            project.setName("PMP 项目管理认证");
            project.setIntro("覆盖项目管理基础、敏捷与混合方法论的综合认证考试。");
            project.setTargetAudience("项目管理人员、PMO、想转岗项目管理的工程师");
            project.setStatus("PUBLISHED");
            project.setSortNo(1);
            projectMapper.insert(project);
        }

        ExamSubject subject = subjectMapper.selectOne(Wrappers.<ExamSubject>lambdaQuery()
                .eq(ExamSubject::getProjectId, project.getId())
                .eq(ExamSubject::getCode, "PMP-BASE").last("LIMIT 1"));
        if (subject == null) {
            subject = new ExamSubject();
            subject.setTenantId(1L);
            subject.setProjectId(project.getId());
            subject.setCode("PMP-BASE");
            subject.setName("项目管理基础");
            subject.setIntro("项目管理五大过程组与十大知识领域");
            subject.setStatus("ENABLED");
            subject.setSortNo(1);
            subjectMapper.insert(subject);
        }

        if (questionMapper.selectCount(Wrappers.<QbQuestion>lambdaQuery()
                .eq(QbQuestion::getSubjectId, subject.getId())) == 0) {
            seedQuestions(subject.getId());
        }

        Long projectId = project.getId();
        Long subjectId = subject.getId();
        ExamPaper paper = paperMapper.selectOne(Wrappers.<ExamPaper>lambdaQuery()
                .eq(ExamPaper::getProjectId, projectId).last("LIMIT 1"));
        if (paper == null) {
            List<QbQuestion> questions = questionMapper.selectList(Wrappers.<QbQuestion>lambdaQuery()
                    .eq(QbQuestion::getSubjectId, subjectId)
                    .orderByAsc(QbQuestion::getId));
            List<PaperQuestionItem> items = questions.stream()
                    .map(q -> new PaperQuestionItem(q.getId(), 10))
                    .toList();
            paper = new ExamPaper();
            paper.setTenantId(1L);
            paper.setProjectId(projectId);
            paper.setSubjectId(subjectId);
            paper.setName("PMP 模拟试卷 A");
            paper.setMode(ExamPaper.MODE_FIXED);
            paper.setQuestionJson(Json.write(items));
            paper.setTotalScore(items.size() * 10);
            paper.setStatus(ExamPaper.STATUS_DRAFT);
            paper.setVersion(1);
            paperMapper.insert(paper);
        }

        if (sessionService.listParticipable(projectId).isEmpty()) {
            LocalDateTime now = LocalDateTime.now();
            SessionSaveRequest request = new SessionSaveRequest(projectId, paper.getId(),
                    "PMP 模拟考试（体验场）",
                    now.minusDays(1), now.plusHours(2),
                    now, now.plusHours(3),
                    30, 60, 0, null, 100, "v1",
                    "1. 开考后 30 分钟内可进入；2. 交卷后立即出成绩；3. 请勿刷新页面导致错过交卷。");
            var session = sessionService.save(request);
            sessionService.publish(session.getId());
        }
        return projectId;
    }

    private void seedQuestions(Long subjectId) {
        insertQuestion(subjectId, QbQuestion.TYPE_SINGLE, "项目管理的三大基本要素不包括以下哪一项？",
                List.of(new OptionItem("A", "范围"), new OptionItem("B", "时间"),
                        new OptionItem("C", "成本"), new OptionItem("D", "股权")),
                List.of("D"), "项目管理铁三角是范围、时间、成本（质量），不包含股权。", "EASY");
        insertQuestion(subjectId, QbQuestion.TYPE_SINGLE, "WBS 的主要作用是？",
                List.of(new OptionItem("A", "定义项目范围并分解可交付成果"),
                        new OptionItem("B", "记录项目风险"),
                        new OptionItem("C", "编制财务报表"),
                        new OptionItem("D", "进行绩效评估")),
                List.of("A"), "WBS（工作分解结构）用于把项目范围逐层分解为可管理的工作包。", "NORMAL");
        insertQuestion(subjectId, QbQuestion.TYPE_MULTIPLE, "以下属于敏捷宣言价值观的是？",
                List.of(new OptionItem("A", "个体与互动高于流程和工具"),
                        new OptionItem("B", "可工作的软件高于详尽的文档"),
                        new OptionItem("C", "完善的合同高于客户合作"),
                        new OptionItem("D", "响应变化高于遵循计划")),
                List.of("A", "B", "D"), "敏捷宣言四项价值观：个体与互动、可工作的软件、客户合作、响应变化。",
                "NORMAL");
        insertQuestion(subjectId, QbQuestion.TYPE_MULTIPLE, "项目风险应对的常见策略包括？",
                List.of(new OptionItem("A", "规避"), new OptionItem("B", "转移"),
                        new OptionItem("C", "减轻"), new OptionItem("D", "接受")),
                List.of("A", "B", "C", "D"), "消极风险应对策略：规避、转移、减轻、接受。", "EASY");
        insertQuestion(subjectId, QbQuestion.TYPE_JUDGE, "项目章程由项目经理签发。",
                List.of(new OptionItem("A", "正确"), new OptionItem("B", "错误")),
                List.of("B"), "项目章程通常由项目发起人或高级管理层签发，项目经理负责编写。", "EASY");
        insertQuestion(subjectId, QbQuestion.TYPE_JUDGE, "关键路径上的活动总浮动时间为零。",
                List.of(new OptionItem("A", "正确"), new OptionItem("B", "错误")),
                List.of("A"), "关键路径是项目最长路径，其上活动总浮动时间为零。", "NORMAL");
    }

    private void insertQuestion(Long subjectId, String type, String stem, List<OptionItem> options,
                                List<String> answer, String analysis, String difficulty) {
        QbQuestion question = new QbQuestion();
        question.setTenantId(1L);
        question.setSubjectId(subjectId);
        question.setType(type);
        question.setStem(stem);
        question.setOptionsJson(Json.write(options));
        question.setAnswerJson(Json.write(answer));
        question.setAnalysis(analysis);
        question.setDifficulty(difficulty);
        question.setStatus("ENABLED");
        question.setVersion(1);
        questionMapper.insert(question);
    }

    private void seedProducts(Long projectId) {
        if (productMapper.selectCount(Wrappers.<MallProduct>lambdaQuery()
                .eq(MallProduct::getCode, "QB-PMP")) == 0) {
            MallProduct bank = new MallProduct();
            bank.setTenantId(1L);
            bank.setCode("QB-PMP");
            bank.setName("PMP 题库包（30 天）");
            bank.setType(MallProduct.TYPE_QUESTION_BANK);
            bank.setDescription("解锁 PMP 项目全部练习题与解析，有效期 30 天。");
            bank.setPriceFen(9900L);
            bank.setOriginalPriceFen(19900L);
            bank.setValidityDays(30);
            bank.setResourceType("PROJECT");
            bank.setResourceId(projectId);
            bank.setStatus(MallProduct.STATUS_ON_SALE);
            bank.setSortNo(1);
            productMapper.insert(bank);
        }
        if (productMapper.selectCount(Wrappers.<MallProduct>lambdaQuery()
                .eq(MallProduct::getCode, "VIP-30")) == 0) {
            MallProduct member = new MallProduct();
            member.setTenantId(1L);
            member.setCode("VIP-30");
            member.setName("平台会员 30 天");
            member.setType(MallProduct.TYPE_MEMBER);
            member.setDescription("会员期内可使用会员范围内的题库、课程与模拟考试。");
            member.setPriceFen(19900L);
            member.setOriginalPriceFen(29900L);
            member.setValidityDays(30);
            member.setStatus(MallProduct.STATUS_ON_SALE);
            member.setSortNo(2);
            productMapper.insert(member);
        }
    }

    /**
     * 演示课程：一门免费课（直接可看）+ 一门付费课（需购买或持有 COURSE 权益）。
     * 付费课同时上架一个 COURSE 类型商品，购买成功后权益 resourceId 指向该课程。
     */
    private void seedCourses(Long projectId) {
        CourseInfo free = courseMapper.selectOne(Wrappers.<CourseInfo>lambdaQuery()
                .eq(CourseInfo::getCode, "COURSE-PMP-FREE").last("LIMIT 1"));
        if (free == null) {
            free = new CourseInfo();
            free.setTenantId(1L);
            free.setProjectId(projectId);
            free.setCode("COURSE-PMP-FREE");
            free.setTitle("项目管理入门（免费）");
            free.setSummary("用 3 节课快速建立项目管理的整体认知，适合零基础考生。");
            free.setLecturer("平台教研组");
            free.setPriceFen(0L);
            free.setAccessType(CourseInfo.ACCESS_FREE);
            free.setStatus(CourseInfo.STATUS_PUBLISHED);
            free.setSortNo(1);
            courseMapper.insert(free);
            seedLessons(free.getId(), List.of(
                    new DemoLesson("什么是项目管理", "从启动到收尾，项目生命周期全貌。", false),
                    new DemoLesson("项目铁三角", "范围、时间、成本三者如何相互制约。", false),
                    new DemoLesson("项目经理的角色", "项目经理到底该做什么、不该做什么。", false)));
        }

        CourseInfo paid = courseMapper.selectOne(Wrappers.<CourseInfo>lambdaQuery()
                .eq(CourseInfo::getCode, "COURSE-PMP-PRO").last("LIMIT 1"));
        if (paid == null) {
            paid = new CourseInfo();
            paid.setTenantId(1L);
            paid.setProjectId(projectId);
            paid.setCode("COURSE-PMP-PRO");
            paid.setTitle("PMP 冲刺精讲（付费）");
            paid.setSummary("按考试大纲逐章精讲，含真题拆解与易错点串讲。");
            paid.setLecturer("资深 PMP 讲师");
            paid.setPriceFen(29900L);
            paid.setAccessType(CourseInfo.ACCESS_ENTITLED);
            paid.setStatus(CourseInfo.STATUS_PUBLISHED);
            paid.setSortNo(2);
            courseMapper.insert(paid);
            seedLessons(paid.getId(), List.of(
                    new DemoLesson("课程导学（试看）", "整门课的学习路径与备考建议。", true),
                    new DemoLesson("项目启动过程组", "章程、干系人登记册与启动会。", false),
                    new DemoLesson("项目规划过程组", "范围、进度、成本三大基准的编制。", false),
                    new DemoLesson("项目执行与监控", "变更控制、绩效测量与纠偏。", false)));

            if (productMapper.selectCount(Wrappers.<MallProduct>lambdaQuery()
                    .eq(MallProduct::getCode, "COURSE-PMP-PRO")) == 0) {
                MallProduct product = new MallProduct();
                product.setTenantId(1L);
                product.setCode("COURSE-PMP-PRO");
                product.setName("PMP 冲刺精讲课程");
                product.setType(MallProduct.TYPE_COURSE);
                product.setDescription("购买后解锁全部课时，有效期 180 天。");
                product.setPriceFen(29900L);
                product.setOriginalPriceFen(49900L);
                product.setValidityDays(180);
                product.setResourceType("COURSE");
                product.setResourceId(paid.getId());
                product.setStatus(MallProduct.STATUS_ON_SALE);
                product.setSortNo(3);
                productMapper.insert(product);
            }
        }
    }

    /** 演示课时：标题 + 内容 + 是否可试看。 */
    private record DemoLesson(String title, String content, boolean freePreview) {
    }

    private void seedLessons(Long courseId, List<DemoLesson> lessons) {
        int sortNo = 0;
        for (DemoLesson item : lessons) {
            CourseLesson lesson = new CourseLesson();
            lesson.setTenantId(1L);
            lesson.setCourseId(courseId);
            lesson.setTitle(item.title());
            lesson.setContent(item.content());
            lesson.setDurationSeconds(15 * 60);
            lesson.setFreePreview(item.freePreview() ? 1 : 0);
            lesson.setSortNo(sortNo++);
            lesson.setStatus("ENABLED");
            lessonMapper.insert(lesson);
        }
        CourseInfo update = new CourseInfo();
        update.setId(courseId);
        update.setLessonCount(lessons.size());
        courseMapper.updateById(update);
    }
}
