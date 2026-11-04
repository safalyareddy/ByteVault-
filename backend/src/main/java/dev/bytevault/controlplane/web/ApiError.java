package dev.bytevault.controlplane.web;

import java.time.Instant;
import java.util.List;

/** The single error envelope returned by every /api/v1 endpoint. */
public record ApiError(
        Instant timestamp,
        int status,
        String code,
        String message,
        String path,
        String correlationId,
        List<FieldViolation> details) {

    public record FieldViolation(String field, String message) {}
}
