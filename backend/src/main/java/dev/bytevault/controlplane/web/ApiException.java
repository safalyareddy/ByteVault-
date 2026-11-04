package dev.bytevault.controlplane.web;

import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;

/** A client-visible failure with a stable machine-readable code. Rendered as {@link ApiError}. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final Map<String, ?> context;

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, Map.of());
    }

    public ApiException(HttpStatus status, String code, String message, Map<String, ?> context) {
        super(message);
        this.status = status;
        this.code = code;
        this.context = context;
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }
    public Map<String, ?> context() { return context; }

    public static ApiException notFound(String what) {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", what + " not found");
    }

    List<ApiError.FieldViolation> asDetails() {
        return context.entrySet().stream()
                .map(e -> new ApiError.FieldViolation(e.getKey(), String.valueOf(e.getValue())))
                .toList();
    }
}
