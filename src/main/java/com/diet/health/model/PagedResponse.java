package com.diet.health.model;

import java.util.List;

/** 统一分页响应（规格 6.2：page/size，size 最大 50）。 */
public record PagedResponse<T>(List<T> items, int page, int size, int total, int totalPages) {

    public static <T> PagedResponse<T> of(List<T> items, int page, int size, int total) {
        int totalPages = total == 0 ? 0 : (total + size - 1) / size;
        return new PagedResponse<>(items, page, size, total, totalPages);
    }
}
