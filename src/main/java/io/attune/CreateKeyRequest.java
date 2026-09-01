package io.attune;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/** Request to create a key. The server derives the canonical ref from its owner and local ref. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CreateKeyRequest(
        @JsonProperty("local_ref") String localRef,
        @JsonProperty("owner_type") OwnerType ownerType,
        String name,
        Object value,
        Boolean encrypted,
        @JsonProperty("owner_identity_login") String ownerIdentityLogin,
        @JsonProperty("owner_pack_ref") String ownerPackRef,
        @JsonProperty("owner_action_ref") String ownerActionRef,
        @JsonProperty("owner_sensor_ref") String ownerSensorRef
) {
    public CreateKeyRequest {
        Objects.requireNonNull(localRef, "localRef cannot be null");
        Objects.requireNonNull(ownerType, "ownerType cannot be null");
        Objects.requireNonNull(name, "name cannot be null");
        Objects.requireNonNull(value, "value cannot be null");

        requireNonBlank(ownerIdentityLogin, "ownerIdentityLogin");
        requireNonBlank(ownerPackRef, "ownerPackRef");
        requireNonBlank(ownerActionRef, "ownerActionRef");
        requireNonBlank(ownerSensorRef, "ownerSensorRef");

        int selectorCount = countPresent(
                ownerIdentityLogin,
                ownerPackRef,
                ownerActionRef,
                ownerSensorRef
        );
        boolean validOwner = switch (ownerType) {
            case SYSTEM -> selectorCount == 0;
            case IDENTITY -> ownerIdentityLogin != null && selectorCount == 1;
            case PACK -> ownerPackRef != null && selectorCount == 1;
            case ACTION -> ownerActionRef != null && selectorCount == 1;
            case SENSOR -> ownerSensorRef != null && selectorCount == 1;
        };
        if (!validOwner) {
            throw new IllegalArgumentException("Owner selector does not match ownerType " + ownerType.value());
        }
    }

    public static CreateKeyRequest system(String localRef, String name, Object value) {
        return new CreateKeyRequest(localRef, OwnerType.SYSTEM, name, value, null, null, null, null, null);
    }

    public static CreateKeyRequest identity(String localRef, String name, Object value, String identityLogin) {
        return new CreateKeyRequest(
                localRef, OwnerType.IDENTITY, name, value, null, identityLogin, null, null, null
        );
    }

    public static CreateKeyRequest pack(String localRef, String name, Object value, String packRef) {
        return new CreateKeyRequest(localRef, OwnerType.PACK, name, value, null, null, packRef, null, null);
    }

    public static CreateKeyRequest action(String localRef, String name, Object value, String actionRef) {
        return new CreateKeyRequest(localRef, OwnerType.ACTION, name, value, null, null, null, actionRef, null);
    }

    public static CreateKeyRequest sensor(String localRef, String name, Object value, String sensorRef) {
        return new CreateKeyRequest(localRef, OwnerType.SENSOR, name, value, null, null, null, null, sensorRef);
    }

    public CreateKeyRequest withEncryption(boolean encrypted) {
        return new CreateKeyRequest(
                localRef,
                ownerType,
                name,
                value,
                encrypted,
                ownerIdentityLogin,
                ownerPackRef,
                ownerActionRef,
                ownerSensorRef
        );
    }

    private static int countPresent(String... values) {
        int count = 0;
        for (String candidate : values) {
            if (candidate != null) count++;
        }
        return count;
    }

    private static void requireNonBlank(String value, String field) {
        if (value != null && value.isBlank()) {
            throw new IllegalArgumentException(field + " cannot be blank");
        }
    }
}
