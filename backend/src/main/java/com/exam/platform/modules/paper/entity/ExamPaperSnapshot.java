package com.exam.platform.modules.paper.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.exam.platform.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 试卷快照：题面、选项、标准答案、分值在生成后不可变。
 * contentHash 用于完整性校验，考试作答只读取快照，不回读可变题库。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("exam_paper_snapshot")
public class ExamPaperSnapshot extends BaseEntity {

    private Long paperId;
    private Integer paperVersion;
    private Integer totalScore;
    private String contentJson;
    private String contentHash;
}
