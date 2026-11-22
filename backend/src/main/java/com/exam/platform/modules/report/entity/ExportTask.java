package com.exam.platform.modules.report.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.exam.platform.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 导出任务（文档第 9 节：批量导入导出使用异步任务并提供任务状态）。
 *
 * <p>状态机：PENDING → RUNNING → SUCCESS / FAILED。
 * 任务先落库再由后台线程执行，前端轮询 {@code status} 即可。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("export_task")
public class ExportTask extends BaseEntity {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED = "FAILED";

    /** 订单报表 */
    public static final String TYPE_ORDER = "ORDER";
    /** 成绩报表 */
    public static final String TYPE_RESULT = "RESULT";
    /** 报名报表 */
    public static final String TYPE_REGISTRATION = "REGISTRATION";
    /** 场次成绩分析报表（概览 + 分数段分布 + 逐题正确率） */
    public static final String TYPE_SESSION_ANALYTICS = "SESSION_ANALYTICS";

    private String taskNo;
    private String type;
    private String paramsJson;
    private String status;
    private String fileName;
    private String filePath;
    private Integer rowCount;
    private String errorMsg;
    private Long operatorId;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
}
