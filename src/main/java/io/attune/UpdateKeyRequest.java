package io.attune;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Fields to update on an existing key. Null fields are omitted from the request. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record UpdateKeyRequest(Boolean encrypted, String name, Object value) {}
