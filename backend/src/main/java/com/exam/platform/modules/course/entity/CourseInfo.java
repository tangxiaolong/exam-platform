package com.exam.platform.modules.course.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.exam.platform.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 课程。
 *
 * <p>accessType 决定观看门槛：</p>
 * <ul>
 *   <li>FREE —— 免费课程，登录即可看；</li>
 *   <li>ENTITLED —— 需要持有 COURSE 类型、resourceId 指向本课程的权益（购买课程商品后发放）。</li>
 * </ul>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("course_info")
public class CourseInfo extends BaseEntity {

    public static final String ACCESS_FREE = "FREE";
    public static final String ACCESS_ENTITLED = "ENTITLED";

    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_PUBLISHED = "PUBLISHED";
    public static final String STATUS_OFFLINE = "OFFLINE";

    private Long projectId;
    private Long subjectId;
    private String code;
    private String title;
    private String summary;
    private String coverUrl;
    private String lecturer;
    private Integer lessonCount;
    /** 展示价（单位：分），实际成交价以商城商品为准 */
    private Long priceFen;
    private String accessType;
    private String status;
    private Integer sortNo;
}
