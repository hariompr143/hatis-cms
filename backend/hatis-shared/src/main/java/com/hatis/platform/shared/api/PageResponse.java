package com.hatis.platform.shared.api;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.List;
import java.util.function.Function;

/**
 * Uniform cursor-free pagination envelope.
 *
 * <p>Offset pagination is used deliberately in Phase 1: every list endpoint is
 * tenant-scoped and index-backed, and customers expect page numbers. Endpoints
 * that must stream large datasets (exports, analytics) use the export API
 * instead of unbounded paging — {@link #MAX_PAGE_SIZE} exists to make "fetch
 * everything" an explicit, audited operation rather than an accident.
 */
public record PageResponse<T>(
        List<T> items,
        int page,
        int size,
        long totalItems,
        int totalPages,
        boolean hasMore) {

    public static final int DEFAULT_PAGE_SIZE = 25;
    public static final int MAX_PAGE_SIZE = 200;

    public static <E, T> PageResponse<T> from(Page<E> page, Function<E, T> mapper) {
        return new PageResponse<>(
                page.getContent().stream().map(mapper).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.hasNext());
    }

    public static <T> PageResponse<T> of(List<T> items, Pageable pageable, long total) {
        int size = pageable.getPageSize() == 0 ? items.size() : pageable.getPageSize();
        int totalPages = size == 0 ? 1 : (int) Math.ceil((double) total / size);
        return new PageResponse<>(
                items,
                pageable.getPageNumber(),
                size,
                total,
                totalPages,
                (long) (pageable.getPageNumber() + 1) * size < total);
    }

    /** Builds a bounded, validated {@link Pageable} from raw request parameters. */
    public static Pageable pageable(Integer page, Integer size, Sort sort) {
        int p = page == null || page < 0 ? 0 : page;
        int s = size == null || size < 1 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        return PageRequest.of(p, s, sort == null ? Sort.unsorted() : sort);
    }
}
