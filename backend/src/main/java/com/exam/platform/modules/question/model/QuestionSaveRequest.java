package com.exam.platform.modules.question.model;

import com.exam.platform.modules.question.entity.QbQuestion;

import java.util.List;

/** 管理端新增/编辑题目入参（选项和答案用数组表达，服务端转 JSON 存储）。 */
public record QuestionSaveRequest(
        Long subjectId,
        String type,
        String stem,
        List<OptionItem> options,
        List<String> answer,
        String analysis,
        String difficulty,
        String tags,
        String status
) {

    public QbQuestion toEntity() {
        QbQuestion question = new QbQuestion();
        question.setSubjectId(subjectId);
        question.setType(type);
        question.setStem(stem);
        question.setAnalysis(analysis);
        question.setDifficulty(difficulty == null ? "NORMAL" : difficulty);
        question.setTags(tags);
        question.setStatus(status == null ? "ENABLED" : status);
        return question;
    }
}
