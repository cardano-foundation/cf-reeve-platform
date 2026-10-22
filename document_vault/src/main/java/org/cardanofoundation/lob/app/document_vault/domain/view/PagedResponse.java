package org.cardanofoundation.lob.app.document_vault.domain.view;

import java.util.List;
import java.util.function.Function;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Paged list response — platform shape (mirror of funding's PagedResponse, minus the ErrorAware
 * mix-in: vault services return Either and controllers fold it). Used by every list endpoint.
 */
public record PagedResponse<T>(List<T> content, long total, int totalPages, int page, int size) {

    public static <E, V> PagedResponse<V> of(Page<E> page, Function<E, V> mapper) {
        return new PagedResponse<>(page.getContent().stream().map(mapper).toList(),
                page.getTotalElements(), page.getTotalPages(), page.getNumber(), page.getSize());
    }

    /** Page an in-memory list. Used by the addressbook, which loads the whole (small) org directory. */
    public static <V> PagedResponse<V> ofList(List<V> all, Pageable pageable) {
        if (pageable.isUnpaged()) {
            return new PagedResponse<>(all, all.size(), 1, 0, all.size());
        }
        int from = (int) Math.min(pageable.getOffset(), all.size());
        int to = Math.min(from + pageable.getPageSize(), all.size());
        int totalPages = (int) Math.ceil((double) all.size() / pageable.getPageSize());
        return new PagedResponse<>(all.subList(from, to), all.size(), totalPages,
                pageable.getPageNumber(), pageable.getPageSize());
    }
}
