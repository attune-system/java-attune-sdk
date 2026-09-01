package io.attune;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Pagination metadata returned by the Keys API. */
public record PaginationMeta(
        int page,
        @JsonProperty("page_size") int pageSize,
        @JsonProperty("has_previous") boolean hasPrevious,
        @JsonProperty("has_next") boolean hasNext,
        @JsonProperty("total_items") Long totalItems,
        @JsonProperty("total_pages") Integer totalPages
) {}
