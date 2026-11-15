package com.exam.platform.modules.content.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.exam.platform.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 考试项目。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("exam_project")
public class ExamProject extends BaseEntity {

    private String code;
    private String name;
    private String intro;
    private String coverUrl;
    private String targetAudience;
    /** DRAFT / PUBLISHED / OFFLINE */
    private String status;
    private Integer sortNo;
}
