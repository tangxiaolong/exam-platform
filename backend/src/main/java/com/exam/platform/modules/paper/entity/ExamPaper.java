package com.exam.platform.modules.paper.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.exam.platform.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 试卷定义。questionJson 保存题目与分值配置：
 * <pre>[{"questionId":1,"score":5}]</pre>
 * 发布场次时由 {@code PaperService} 生成不可变快照。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("exam_paper")
public class ExamPaper extends BaseEntity {

    public static final String MODE_FIXED = "FIXED";
    public static final String MODE_RULE = "RULE";
    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_PUBLISHED = "PUBLISHED";

    private Long projectId;
    private Long subjectId;
    private String name;
    private String mode;
    private Integer totalScore;
    private String questionJson;
    private String status;
    private Integer version;
}
