package org.cardanofoundation.lob.app.funding.domain.view;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import org.springframework.data.domain.Page;
import org.springframework.http.ProblemDetail;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Structured response for paginated list endpoints — carries the page content together with the
 * pagination metadata a client needs, mirroring the list-response DTOs used in the reporting module
 * (e.g. {@code ReportTemplateListResponseDto}).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Paginated list response with pagination metadata")
public class PagedResponse<T> implements ErrorAware {

    @Schema(description = "Page content")
    private List<T> content;

    @Schema(description = "Total number of elements across all pages", example = "100")
    private long total;

    @Schema(description = "Total number of pages available", example = "10")
    private int totalPages;

    @Schema(description = "Current page number (0-based)", example = "0")
    private int page;

    @Schema(description = "Page size", example = "20")
    private int size;

    @Builder.Default
    @Schema(description = "Problem detail describing the failure; absent on success")
    private Optional<ProblemDetail> error = Optional.empty();

    /** Builds a {@code PagedResponse} from a Spring Data {@link Page}, mapping each element to a view. */
    public static <E, V> PagedResponse<V> of(Page<E> page, Function<E, V> mapper) {
        return PagedResponse.<V>builder()
                .content(page.getContent().stream().map(mapper).toList())
                .total(page.getTotalElements())
                .totalPages(page.getTotalPages())
                .page(page.getNumber())
                .size(page.getSize())
                .build();
    }

    /** Builds a failed {@code PagedResponse} carrying only the problem detail. */
    public static <V> PagedResponse<V> error(ProblemDetail error) {
        return PagedResponse.<V>builder().error(Optional.of(error)).build();
    }
}
