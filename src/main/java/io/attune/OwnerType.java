package io.attune;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Owner scopes supported by the Attune Keys API. */
public enum OwnerType {
    SYSTEM("system"),
    IDENTITY("identity"),
    PACK("pack"),
    ACTION("action"),
    SENSOR("sensor");

    private final String value;

    OwnerType(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static OwnerType fromValue(String value) {
        for (OwnerType ownerType : values()) {
            if (ownerType.value.equals(value)) {
                return ownerType;
            }
        }
        throw new IllegalArgumentException("Unknown key owner type: " + value);
    }
}
