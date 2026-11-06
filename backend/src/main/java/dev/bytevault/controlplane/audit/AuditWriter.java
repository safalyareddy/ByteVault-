package dev.bytevault.controlplane.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.bytevault.controlplane.web.CorrelationIdFilter;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Appends to the append-only {@code audit_events} table. */
@Component
public class AuditWriter {

    /** Actor recorded for actions the control plane takes on its own. */
    public static final String SYSTEM = "system";

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public AuditWriter(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void record(String actor, String action, String resourceType, String resourceId, Map<String, ?> details) {
        String body;
        try {
            body = json.writeValueAsString(details);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(e);
        }
        jdbc.sql("""
                INSERT INTO audit_events (actor, action, resource_type, resource_id, correlation_id, details)
                VALUES (:actor, :action, :rt, :rid, :cid, CAST(:details AS jsonb))
                """).param("actor", actor).param("action", action).param("rt", resourceType)
                .param("rid", resourceId).param("cid", CorrelationIdFilter.current()).param("details", body).update();
    }
}
