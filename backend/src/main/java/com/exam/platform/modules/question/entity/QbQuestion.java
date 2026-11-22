package com.exam.platform.modules.question.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.exam.platform.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 题目主表。
 *
 * <p>optionsJson / answerJson 均为 JSON 字符串：
 * <pre>optionsJson: [{"key":"A","text":"..."}]</pre>
 * <pre>answerJson : ["A","C"]</pre>
 * 已被试卷快照引用的题目修改时递增 version，历史快照不受影响。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("qb_question")
public class QbQuestion extends BaseEntity {

    /** 知识点ID：为空表示未标注，按知识点范围组卷时不会被抽中。 */
    private Long knowledgePointId;

    public static final String TYPE_SINGLE = "SINGLE";
    public static final String TYPE_MULTIPLE = "MULTIPLE";
    public static final String TYPE_JUDGE = "JUDGE";
    /** 主观题：需要人工阅卷，answerJson 存参考答案文本（可空） */
    public static final String TYPE_ESSAY = "ESSAY";

    private Long subjectId;
    private String type;
    private String stem;
    private String optionsJson;
    private String answerJson;
    private String analysis;
    /** EASY / NORMAL / HARD */
    private String difficulty;
    private String tags;
    private String source;
    /** ENABLED / DISABLED */
    private String status;
    private Integer version;
}
