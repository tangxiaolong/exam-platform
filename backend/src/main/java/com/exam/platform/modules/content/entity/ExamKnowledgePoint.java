package com.exam.platform.modules.content.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.exam.platform.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 知识点树（parentId 为空表示一级节点）。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("exam_knowledge_point")
public class ExamKnowledgePoint extends BaseEntity {

    private Long subjectId;
    private Long parentId;
    private String name;
    private Integer sortNo;
}
