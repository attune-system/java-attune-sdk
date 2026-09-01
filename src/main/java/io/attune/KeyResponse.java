package io.attune;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Full key response returned by create, get, and update operations. */
public record KeyResponse(
        long id,
        String ref,
        @JsonProperty("local_ref") String localRef,
        @JsonProperty("owner_type") OwnerType ownerType,
        String owner,
        @JsonProperty("owner_identity") Long ownerIdentity,
        @JsonProperty("owner_pack") Long ownerPack,
        @JsonProperty("owner_action") Long ownerAction,
        @JsonProperty("owner_sensor") Long ownerSensor,
        @JsonProperty("owner_pack_ref") String ownerPackRef,
        @JsonProperty("owner_action_ref") String ownerActionRef,
        @JsonProperty("owner_sensor_ref") String ownerSensorRef,
        String name,
        boolean encrypted,
        Object value,
        String created,
        String updated
) {}
