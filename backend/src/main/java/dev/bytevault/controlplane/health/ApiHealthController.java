package dev.bytevault.controlplane.health;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.actuate.health.CompositeHealth;
import org.springframework.boot.actuate.health.HealthComponent;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.info.BuildProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public, versioned health summary built from the actuator's liveness and readiness groups. It
 * exposes only component names and statuses: no hosts, versions of dependencies, or error text.
 */
@RestController
@RequestMapping("/api/v1/health")
public class ApiHealthController {

    private final HealthEndpoint healthEndpoint;
    private final String version;

    public ApiHealthController(HealthEndpoint healthEndpoint, ObjectProvider<BuildProperties> build) {
        this.healthEndpoint = healthEndpoint;
        BuildProperties props = build.getIfAvailable();
        this.version = props != null ? props.getVersion() : "dev";
    }

    @GetMapping
    public ResponseEntity<HealthSummary> health() {
        HealthComponent liveness = healthEndpoint.healthForPath("liveness");
        HealthComponent readiness = healthEndpoint.healthForPath("readiness");

        Map<String, String> components = new LinkedHashMap<>();
        if (readiness instanceof CompositeHealth composite) {
            composite.getComponents().forEach((name, c) -> components.put(name, c.getStatus().getCode()));
        }
        String live = statusOf(liveness);
        String ready = statusOf(readiness);
        HealthSummary body = new HealthSummary(
                Status.UP.getCode().equals(live) && Status.UP.getCode().equals(ready) ? "UP" : "DOWN",
                live, ready, components, version, Instant.now());
        HttpStatus http = "UP".equals(body.status()) ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(http).body(body);
    }

    private static String statusOf(HealthComponent component) {
        return component == null ? Status.UNKNOWN.getCode() : component.getStatus().getCode();
    }

    public record HealthSummary(
            String status, String liveness, String readiness, Map<String, String> components,
            String version, Instant checkedAt) {}
}
