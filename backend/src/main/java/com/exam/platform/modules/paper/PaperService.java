package com.exam.platform.modules.paper;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.exam.platform.common.api.PageQuery;
import com.exam.platform.common.api.PageResult;
import com.exam.platform.common.exception.BizException;
import com.exam.platform.common.util.Entities;
import com.exam.platform.common.util.Json;
import com.exam.platform.modules.content.ContentService;
import com.exam.platform.modules.paper.entity.ExamPaper;
import com.exam.platform.modules.paper.entity.ExamPaperRule;
import com.exam.platform.modules.paper.entity.ExamPaperSnapshot;
import com.exam.platform.modules.paper.mapper.ExamPaperMapper;
import com.exam.platform.modules.paper.mapper.ExamPaperRuleMapper;
import com.exam.platform.modules.paper.mapper.ExamPaperSnapshotMapper;
import com.exam.platform.modules.paper.model.PaperQuestionItem;
import com.exam.platform.modules.paper.model.PaperRuleItem;
import com.exam.platform.modules.paper.model.PaperSaveRequest;
import com.exam.platform.modules.paper.model.SnapshotContent;
import com.exam.platform.modules.paper.model.SnapshotQuestion;
import com.exam.platform.modules.question.QuestionService;
import com.exam.platform.modules.question.entity.QbQuestion;
import com.exam.platform.modules.question.model.OptionItem;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 试卷与试卷快照服务。 */
@Service
@RequiredArgsConstructor
public class PaperService {

    private final ExamPaperMapper paperMapper;
    private final ExamPaperRuleMapper ruleMapper;
    private final ExamPaperSnapshotMapper snapshotMapper;
    private final QuestionService questionService;
    private final ContentService contentService;

    public PageResult<ExamPaper> page(String keyword, PageQuery pageQuery) {
        Page<ExamPaper> page = paperMapper.selectPage(pageQuery.toPage(), Wrappers.<ExamPaper>lambdaQuery()
                .like(keyword != null && !keyword.isBlank(), ExamPaper::getName, keyword)
                .orderByDesc(ExamPaper::getId));
        return PageResult.of(page.getRecords(), page.getTotal(), page.getCurrent(), page.getSize());
    }

    public ExamPaper getPaper(Long id) {
        ExamPaper paper = paperMapper.selectById(id);
        if (paper == null) {
            throw BizException.notFound("试卷");
        }
        return paper;
    }

    @Transactional
    public ExamPaper save(PaperSaveRequest request) {
        contentService.getProject(request.projectId());
        String mode = request.mode() == null ? ExamPaper.MODE_FIXED : request.mode();
        ExamPaper paper = new ExamPaper();
        paper.setProjectId(request.projectId());
        paper.setSubjectId(request.subjectId());
        paper.setName(request.name());
        paper.setMode(mode);
        if (ExamPaper.MODE_RULE.equals(mode)) {
            List<ExamPaperRule> rules = normalizeRules(request.rules());
            paper.setQuestionJson("[]");
            paper.setTotalScore(rules.stream().mapToInt(r -> r.getCount() * r.getScore()).sum());
            paper.setStatus(ExamPaper.STATUS_DRAFT);
            paper.setVersion(1);
            Entities.forInsert(paper);
            paperMapper.insert(paper);
            saveRules(paper.getId(), rules);
            return paper;
        }
        List<PaperQuestionItem> items = normalizeItems(request.questions());
        paper.setQuestionJson(Json.write(items));
        paper.setTotalScore(items.stream().mapToInt(PaperQuestionItem::score).sum());
        paper.setStatus(ExamPaper.STATUS_DRAFT);
        paper.setVersion(1);
        Entities.forInsert(paper);
        paperMapper.insert(paper);
        return paper;
    }

    @Transactional
    public ExamPaper update(Long id, PaperSaveRequest request) {
        ExamPaper exist = getPaper(id);
        String mode = request.mode() == null ? exist.getMode() : request.mode();
        ExamPaper update = new ExamPaper();
        update.setProjectId(request.projectId());
        update.setSubjectId(request.subjectId());
        update.setName(request.name());
        update.setMode(mode);
        update.setVersion(exist.getVersion() == null ? 2 : exist.getVersion() + 1);

        if (ExamPaper.MODE_RULE.equals(mode)) {
            List<ExamPaperRule> rules = normalizeRules(request.rules());
            update.setQuestionJson("[]");
            update.setTotalScore(rules.stream().mapToInt(r -> r.getCount() * r.getScore()).sum());
            Entities.forUpdate(update, id);
            paperMapper.updateById(update);
            ruleMapper.delete(Wrappers.<ExamPaperRule>lambdaQuery().eq(ExamPaperRule::getPaperId, id));
            saveRules(id, rules);
            return getPaper(id);
        }

        List<PaperQuestionItem> items = normalizeItems(request.questions());
        update.setQuestionJson(Json.write(items));
        update.setTotalScore(items.stream().mapToInt(PaperQuestionItem::score).sum());
        Entities.forUpdate(update, id);
        paperMapper.updateById(update);
        return getPaper(id);
    }

    public List<ExamPaperRule> listRules(Long paperId) {
        return ruleMapper.selectList(Wrappers.<ExamPaperRule>lambdaQuery()
                .eq(ExamPaperRule::getPaperId, paperId)
                .orderByAsc(ExamPaperRule::getSortNo)
                .orderByAsc(ExamPaperRule::getId));
    }

    /** 试组卷：按规则抽题但不落库，用于发布前校验题库是否够用。 */
    public SnapshotContent preview(Long paperId) {
        ExamPaper paper = getPaper(paperId);
        List<PaperQuestionItem> items = resolveItems(paper);
        return buildContent(paper, items);
    }

    @Transactional
    public void delete(Long id) {
        getPaper(id);
        paperMapper.deleteById(id);
    }

    /**
     * 生成不可变试卷快照：复制题干、选项、标准答案、解析与分值，并保存内容哈希。
     * 场次发布时调用一次，此后考试只读快照。规则组卷在此刻完成抽题。
     */
    @Transactional
    public ExamPaperSnapshot createSnapshot(Long paperId) {
        ExamPaper paper = getPaper(paperId);
        List<PaperQuestionItem> items = resolveItems(paper);
        SnapshotContent content = buildContent(paper, items);
        String contentJson = Json.write(content);

        ExamPaperSnapshot snapshot = new ExamPaperSnapshot();
        snapshot.setPaperId(paper.getId());
        snapshot.setPaperVersion(paper.getVersion());
        snapshot.setTotalScore(content.totalScore());
        snapshot.setContentJson(contentJson);
        snapshot.setContentHash(sha256(contentJson));
        Entities.forInsert(snapshot);
        snapshotMapper.insert(snapshot);

        ExamPaper published = new ExamPaper();
        published.setId(paper.getId());
        published.setStatus(ExamPaper.STATUS_PUBLISHED);
        paperMapper.updateById(published);
        return snapshot;
    }

    private List<PaperQuestionItem> resolveItems(ExamPaper paper) {
        if (ExamPaper.MODE_RULE.equals(paper.getMode())) {
            return generateItems(paper);
        }
        List<PaperQuestionItem> items = Json.readList(paper.getQuestionJson(), PaperQuestionItem.class);
        if (items == null || items.isEmpty()) {
            throw new BizException("EXAM_PAPER_EMPTY", "试卷没有题目，无法发布");
        }
        return items;
    }

    /** 规则组卷：逐条规则抽题并保证同一份试卷内不重复。 */
    private List<PaperQuestionItem> generateItems(ExamPaper paper) {
        List<ExamPaperRule> rules = listRules(paper.getId());
        if (rules.isEmpty()) {
            throw new BizException("EXAM_PAPER_EMPTY", "规则组卷试卷没有配置规则");
        }
        Set<Long> used = new HashSet<>();
        List<PaperQuestionItem> items = new ArrayList<>();
        for (ExamPaperRule rule : rules) {
            long available = questionService.countAvailable(paper.getSubjectId(), rule.getType(),
                    rule.getDifficulty(), used);
            if (available < rule.getCount()) {
                throw new BizException("EXAM_PAPER_RULE_INSUFFICIENT", String.format(
                        "规则「%s%s」需要 %d 道题，题库当前可用 %d 道",
                        rule.getType(), rule.getDifficulty() == null ? "" : "/" + rule.getDifficulty(),
                        rule.getCount(), available));
            }
            List<QbQuestion> picked = questionService.randomByRule(paper.getSubjectId(), rule.getType(),
                    rule.getDifficulty(), used, rule.getCount());
            for (QbQuestion question : picked) {
                used.add(question.getId());
                items.add(new PaperQuestionItem(question.getId(), rule.getScore()));
            }
        }
        return items;
    }

    private SnapshotContent buildContent(ExamPaper paper, List<PaperQuestionItem> items) {
        List<Long> ids = items.stream().map(PaperQuestionItem::questionId).toList();
        Map<Long, QbQuestion> questions = questionService.listByIds(ids).stream()
                .collect(Collectors.toMap(QbQuestion::getId, Function.identity()));

        List<SnapshotQuestion> snapshotQuestions = items.stream().map(item -> {
            QbQuestion q = questions.get(item.questionId());
            if (q == null) {
                throw new BizException("EXAM_QUESTION_MISSING", "试卷中的题目已不存在：" + item.questionId());
            }
            return new SnapshotQuestion(q.getId(), q.getSubjectId(), q.getType(), q.getStem(),
                    Json.readList(q.getOptionsJson(), OptionItem.class),
                    Json.readList(q.getAnswerJson(), String.class), q.getAnalysis(),
                    item.score(), q.getDifficulty());
        }).toList();

        return new SnapshotContent(paper.getId(), paper.getVersion(), paper.getName(),
                snapshotQuestions.stream().mapToInt(SnapshotQuestion::score).sum(), snapshotQuestions);
    }

    private List<ExamPaperRule> normalizeRules(List<PaperRuleItem> rules) {
        if (rules == null || rules.isEmpty()) {
            throw BizException.badRequest("规则组卷至少需要一条规则");
        }
        List<ExamPaperRule> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (PaperRuleItem item : rules) {
            if (item.type() == null || item.type().isBlank()) {
                throw BizException.badRequest("规则缺少题型");
            }
            if (item.count() == null || item.count() <= 0) {
                throw BizException.badRequest("规则题量必须大于 0");
            }
            if (item.score() == null || item.score() <= 0) {
                throw BizException.badRequest("规则分值必须大于 0");
            }
            String key = item.type() + "|" + (item.difficulty() == null ? "" : item.difficulty());
            if (!seen.add(key)) {
                throw BizException.badRequest("存在重复规则：" + key);
            }
            ExamPaperRule rule = new ExamPaperRule();
            rule.setType(item.type());
            rule.setCount(item.count());
            rule.setScore(item.score());
            rule.setDifficulty(item.difficulty());
            result.add(rule);
        }
        return result;
    }

    private void saveRules(Long paperId, List<ExamPaperRule> rules) {
        int sortNo = 0;
        for (ExamPaperRule rule : rules) {
            rule.setPaperId(paperId);
            rule.setSortNo(sortNo++);
            Entities.forInsert(rule);
            ruleMapper.insert(rule);
        }
    }

    public ExamPaperSnapshot getSnapshot(Long snapshotId) {
        ExamPaperSnapshot snapshot = snapshotMapper.selectById(snapshotId);
        if (snapshot == null) {
            throw BizException.notFound("试卷快照");
        }
        String actual = sha256(snapshot.getContentJson());
        if (!actual.equals(snapshot.getContentHash())) {
            throw new BizException("EXAM_SNAPSHOT_BROKEN", "试卷快照完整性校验失败");
        }
        return snapshot;
    }

    public SnapshotContent parseSnapshot(Long snapshotId) {
        return Json.read(getSnapshot(snapshotId).getContentJson(), SnapshotContent.class);
    }

    private List<PaperQuestionItem> normalizeItems(List<PaperQuestionItem> items) {
        if (items == null || items.isEmpty()) {
            throw BizException.badRequest("试卷题目不能为空");
        }
        Set<Long> seen = new HashSet<>();
        for (PaperQuestionItem item : items) {
            if (item.questionId() == null) {
                throw BizException.badRequest("题目 ID 不能为空");
            }
            if (!seen.add(item.questionId())) {
                throw BizException.badRequest("试卷存在重复题目：" + item.questionId());
            }
            if (item.score() == null || item.score() <= 0) {
                throw BizException.badRequest("题目分值必须大于 0");
            }
        }
        return items;
    }

    private String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("快照哈希计算失败", e);
        }
    }
}
