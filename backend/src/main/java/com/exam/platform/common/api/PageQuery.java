package com.exam.platform.common.api;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.Data;

/** 通用分页入参：pageNo 从 1 开始，pageSize 上限 100。 */
@Data
public class PageQuery {

    private static final long MAX_PAGE_SIZE = 100L;

    private Long pageNo = 1L;
    private Long pageSize = 10L;

    public <T> Page<T> toPage() {
        long no = pageNo == null || pageNo < 1 ? 1L : pageNo;
        long size = pageSize == null || pageSize < 1 ? 10L : Math.min(pageSize, MAX_PAGE_SIZE);
        return new Page<>(no, size);
    }
}
