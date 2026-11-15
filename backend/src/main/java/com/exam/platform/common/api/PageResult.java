package com.exam.platform.common.api;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** 统一分页返回结构。 */
@Data
public class PageResult<T> {

    private List<T> list = new ArrayList<>();
    private long total;
    private long pageNo;
    private long pageSize;

    public static <T> PageResult<T> of(List<T> list, long total, long pageNo, long pageSize) {
        PageResult<T> result = new PageResult<>();
        result.list = list == null ? new ArrayList<>() : list;
        result.total = total;
        result.pageNo = pageNo;
        result.pageSize = pageSize;
        return result;
    }
}
