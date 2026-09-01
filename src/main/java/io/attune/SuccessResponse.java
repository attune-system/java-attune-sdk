package io.attune;

/** Response returned after a successful key deletion. */
public record SuccessResponse(boolean success, String message) {}
