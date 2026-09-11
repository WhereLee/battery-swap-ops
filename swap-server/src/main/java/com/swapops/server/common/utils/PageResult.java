package com.swapops.server.common.utils;

import com.baomidou.mybatisplus.core.metadata.IPage;
import lombok.Data;

import java.util.List;

/**
 * 分页响应：{list,total,page,limit}。
 */
@Data
public class PageResult<T> {

    private List<T> list;
    private long total;
    private long page;
    private long limit;

    public static <T> PageResult<T> of(IPage<T> page) {
        PageResult<T> result = new PageResult<>();
        result.list = page.getRecords();
        result.total = page.getTotal();
        result.page = page.getCurrent();
        result.limit = page.getSize();
        return result;
    }

    public static <T> PageResult<T> of(List<T> list, long total, long page, long limit) {
        PageResult<T> result = new PageResult<>();
        result.list = list;
        result.total = total;
        result.page = page;
        result.limit = limit;
        return result;
    }
}
