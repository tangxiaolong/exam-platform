package com.exam.platform.modules.paper.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.exam.platform.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 规则组卷规则：按题型（可选难度）从题库抽取固定数量题目。
 *
 * <p>规则只影响“发布时如何选题”，一旦生成试卷快照，题目即固定，不受后续题库变动影响。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("exam_paper_rule")
public class ExamPaperRule extends BaseEntity {

    /** 知识点范围：为空表示不限；指定后只从该知识点下抽题。 */
    private Long knowledgePointId;

    /** 难度配比 JSON（按权重，如 {"EASY":2,"NORMAL":5,"HARD":3}）；与 difficulty 互斥。 */
    private String difficultyMix;

    private Long paperId;
    /** SINGLE / MULTIPLE / JUDGE */
    private String type;
    private Integer count;
    /** 每题分值 */
    private Integer score;
    /** EASY / NORMAL / HARD，为空表示不限 */
    private String difficulty;
    private Integer sortNo;
}
