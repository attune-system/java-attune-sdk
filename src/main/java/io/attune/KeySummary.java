package io.attune;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Redacted key entry returned by list operations. */
public record KeySummary(
        long id,
        String ref,
        @JsonProperty("local_ref") String localRef,
        @JsonProperty("owner_type") OwnerType ownerType,
        String owner,
        String name,
        boolean encrypted,
        String created
) {}
