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
import java.util.LinkedHashMap;
import java.util.Map;
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

    /**
     * 复制试卷：沿用组卷规则与固定题目清单，生成新草稿（版本 1），只替换名称。
     * 与场次复制同一条原则——“复制”沿用设置，微调在新卷上做。
     */
    @Transactional
    public ExamPaper clone(Long id, String name) {
        ExamPaper source = getPaper(id);
        ExamPaper paper = new ExamPaper();
        paper.setProjectId(source.getProjectId());
        paper.setSubjectId(source.getSubjectId());
        paper.setName(name == null || name.isBlank() ? source.getName() + "-副本" : name.trim());
        paper.setMode(source.getMode());
        paper.setQuestionJson(source.getQuestionJson());
        paper.setTotalScore(source.getTotalScore());
        paper.setStatus(ExamPaper.STATUS_DRAFT);
        paper.setVersion(1);
        Entities.forInsert(paper);
        paperMapper.insert(paper);
        for (ExamPaperRule rule : listRules(id)) {
            ExamPaperRule copy = new ExamPaperRule();
            copy.setKnowledgePointId(rule.getKnowledgePointId());
            copy.setDifficultyMix(rule.getDifficultyMix());
            copy.setType(rule.getType());
            copy.setCount(rule.getCount());
            copy.setScore(rule.getScore());
            copy.setDifficulty(rule.getDifficulty());
            copy.setPaperId(paper.getId());
            copy.setSortNo(rule.getSortNo());
            Entities.forInsert(copy);
            ruleMapper.insert(copy);
        }
        return paper;
    }

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
    /** 试卷版本历史：快照列表（新→旧），供管理端追溯发布过的每一版不可变试卷。 */
    public List<Map<String, Object>> listSnapshots(Long paperId) {
        getPaper(paperId);
        List<ExamPaperSnapshot> snapshots = snapshotMapper.selectList(Wrappers.<ExamPaperSnapshot>lambdaQuery()
                .eq(ExamPaperSnapshot::getPaperId, paperId)
                .orderByDesc(ExamPaperSnapshot::getId));
        List<Map<String, Object>> result = new java.util.ArrayList<>();
        for (ExamPaperSnapshot snapshot : snapshots) {
            Map<String, Object> item = new java.util.LinkedHashMap<>();
            item.put("id", snapshot.getId());
            item.put("paperVersion", snapshot.getPaperVersion());
            item.put("totalScore", snapshot.getTotalScore());
            item.put("contentHash", snapshot.getContentHash() == null || snapshot.getContentHash().length() < 12
                    ? snapshot.getContentHash() : snapshot.getContentHash().substring(0, 12));
            item.put("createdAt", snapshot.getCreatedAt());
            result.add(item);
        }
        return result;
    }

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
            if (rule.getDifficultyMix() != null && !rule.getDifficultyMix().isBlank()) {
                Map<String, Integer> mix = new LinkedHashMap<>();
                for (String part : rule.getDifficultyMix().split(",")) {
                    String[] kv = part.split(":");
                    if (kv.length == 2) {
                        mix.put(kv[0].trim(), Integer.parseInt(kv[1].trim()));
                    }
                }
                int weightSum = mix.values().stream().mapToInt(Integer::intValue).sum();
                if (weightSum <= 0) {
                    throw BizException.badRequest("难度配比权重必须大于 0");
                }
                Map<String, Integer> quota = new LinkedHashMap<>();
                int assigned = 0;
                for (Map.Entry<String, Integer> entry : mix.entrySet()) {
                    int n = (int) Math.round((double) rule.getCount() * entry.getValue() / weightSum);
                    quota.put(entry.getKey(), n);
                    assigned += n;
                }
                // 四舍五入后与 count 的差值补给 NORMAL（没有 NORMAL 就补给权重最大的那档）。
                // 不按余数从高到低补：那种算法依赖运行时的题库状态，
                // 同样的规则不同时间抽出的题数会飘，考试场景不可接受。
                int gap = rule.getCount() - assigned;
                if (gap != 0) {
                    String adjust = quota.containsKey("NORMAL") ? "NORMAL"
                            : mix.entrySet().stream().max(Map.Entry.comparingByValue())
                                    .map(Map.Entry::getKey).orElse("NORMAL");
                    quota.merge(adjust, gap, Integer::sum);
                }
                for (Map.Entry<String, Integer> entry : quota.entrySet()) {
                    int need = entry.getValue() == null ? 0 : entry.getValue();
                    if (need <= 0) {
                        continue;
                    }
                    long mixAvailable = questionService.countAvailable(paper.getSubjectId(),
                            rule.getType(), entry.getKey(), rule.getKnowledgePointId(), used);
                    if (mixAvailable < need) {
                        throw new BizException("EXAM_PAPER_RULE_INSUFFICIENT",
                                "难度 " + entry.getKey() + " 下可用题目不足：需要 " + need
                                        + " 道，实际 " + mixAvailable + " 道");
                    }
                    for (QbQuestion question : questionService.randomByRule(paper.getSubjectId(),
                            rule.getType(), entry.getKey(), rule.getKnowledgePointId(), used, need)) {
                        used.add(question.getId());
                        items.add(new PaperQuestionItem(question.getId(), rule.getScore()));
                    }
                }
                continue;
            }
            long available = questionService.countAvailable(paper.getSubjectId(), rule.getType(),
                    rule.getDifficulty(), rule.getKnowledgePointId(), used);
            if (available < rule.getCount() && rule.getKnowledgePointId() != null) {
                // 知识点范围内没题，与「题库整体没题」是两回事：
                // 前者要去补标注，后者要去加题，共用一句文案会把运营引向错误方向
                throw new BizException("EXAM_PAPER_RULE_INSUFFICIENT",
                        "知识点 " + rule.getKnowledgePointId() + " 下可用题目不足：需要 "
                                + rule.getCount() + " 道，实际 " + available
                                + " 道；请确认题目是否已标注该知识点");
            }
            if (available < rule.getCount()) {
                throw new BizException("EXAM_PAPER_RULE_INSUFFICIENT", String.format(
                        "规则「%s%s」需要 %d 道题，题库当前可用 %d 道",
                        rule.getType(), rule.getDifficulty() == null ? "" : "/" + rule.getDifficulty(),
                        rule.getCount(), available));
            }
            List<QbQuestion> picked = questionService.randomByRule(paper.getSubjectId(), rule.getType(),
                    rule.getDifficulty(), rule.getKnowledgePointId(), used, rule.getCount());
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
            if (item.difficulty() != null && item.difficultyMix() != null && !item.difficultyMix().isEmpty()) {
                // 同时给出时行为未定义，直接拒绝而不是替运营猜优先级
                throw BizException.badRequest("规则的 difficulty 与 difficultyMix 只能二选一");
            }
            String key = item.type() + "|" + (item.difficulty() == null ? "" : item.difficulty())
                    + "|" + (item.knowledgePointId() == null ? "" : item.knowledgePointId())
                    + "|" + (item.difficultyMix() == null
                            ? "" : new java.util.TreeMap<>(item.difficultyMix()).toString());
            if (!seen.add(key)) {
                throw BizException.badRequest("存在重复规则：" + key);
            }
            ExamPaperRule rule = new ExamPaperRule();
            rule.setType(item.type());
            rule.setCount(item.count());
            rule.setScore(item.score());
            rule.setDifficulty(item.difficulty());
            rule.setKnowledgePointId(item.knowledgePointId());
            rule.setDifficultyMix(item.difficultyMix() == null || item.difficultyMix().isEmpty()
                    ? null
                    : new java.util.TreeMap<>(item.difficultyMix()).entrySet().stream()
                            .map(e -> e.getKey() + ":" + e.getValue())
                            .collect(java.util.stream.Collectors.joining(",")));
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
