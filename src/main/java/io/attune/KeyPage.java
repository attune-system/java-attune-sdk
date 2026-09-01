package io.attune;

import java.util.List;

/** One page of redacted key summaries. */
public record KeyPage(List<KeySummary> items, PaginationMeta pagination) {}
