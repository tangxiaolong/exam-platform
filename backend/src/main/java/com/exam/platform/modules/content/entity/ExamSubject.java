package com.exam.platform.modules.content.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.exam.platform.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 项目下的科目。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("exam_subject")
public class ExamSubject extends BaseEntity {

    private Long projectId;
    private String code;
    private String name;
    private String intro;
    /** ENABLED / DISABLED */
    private String status;
    private Integer sortNo;
}
