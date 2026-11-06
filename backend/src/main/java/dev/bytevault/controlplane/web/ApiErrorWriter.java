package dev.bytevault.controlplane.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

/** Writes the standard {@link ApiError} envelope from filters, outside Spring MVC. */
@Component
public class ApiErrorWriter {

    private final ObjectMapper json;

    public ApiErrorWriter(ObjectMapper json) {
        this.json = json;
    }

    public void write(HttpServletRequest req, HttpServletResponse res, int status, String code, String message)
            throws IOException {
        res.setStatus(status);
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        json.writeValue(res.getOutputStream(), new ApiError(Instant.now(), status, code, message, req.getRequestURI(),
                CorrelationIdFilter.current(), List.of()));
    }
}
