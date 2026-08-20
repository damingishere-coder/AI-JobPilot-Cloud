package com.getjobs.cloud.web;

import java.util.List;

public record PageResult<T>(
        List<T> items,
        int page,
        int size,
        long total,
        boolean hasNext
) {
    public static <T> PageResult<T> of(List<T> items, int page, int size, long total) {
        return new PageResult<>(List.copyOf(items), page, size, total, (long) page * size < total);
    }
}
