package io.attune.api_client.model;

/** Marker for anonymous union values emitted by OpenAPI Generator. */
public interface OneOf {
    default String toUrlQueryString(String prefix) {
        return "";
    }
}
