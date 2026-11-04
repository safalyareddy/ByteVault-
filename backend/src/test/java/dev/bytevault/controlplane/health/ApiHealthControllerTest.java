package dev.bytevault.controlplane.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.CompositeHealth;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.info.BuildProperties;

class ApiHealthControllerTest {

    @SuppressWarnings("unchecked")
    private final ObjectProvider<BuildProperties> noBuildInfo = mock(ObjectProvider.class);

    private static CompositeHealth composite(Status overall, Map<String, Status> parts) {
        // CompositeHealth has no public constructor; obtain one through a mocked instance.
        CompositeHealth c = mock(CompositeHealth.class);
        when(c.getStatus()).thenReturn(overall);
        var components = new java.util.LinkedHashMap<String, org.springframework.boot.actuate.health.HealthComponent>();
        parts.forEach((k, v) -> components.put(k, Health.status(v).build()));
        when(c.getComponents()).thenReturn(components);
        return c;
    }

    @Test
    void upWhenLivenessAndReadinessUp() {
        HealthEndpoint endpoint = mock(HealthEndpoint.class);
        when(endpoint.healthForPath("liveness")).thenReturn(Health.up().build());
        CompositeHealth readiness = composite(Status.UP, Map.of("db", Status.UP, "kafka", Status.UP));
        when(endpoint.healthForPath("readiness")).thenReturn(readiness);

        var res = new ApiHealthController(endpoint, noBuildInfo).health();

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody().status()).isEqualTo("UP");
        assertThat(res.getBody().components()).containsEntry("db", "UP").containsEntry("kafka", "UP");
    }

    @Test
    void returns503AndNamesFailingComponentWhenKafkaDown() {
        HealthEndpoint endpoint = mock(HealthEndpoint.class);
        when(endpoint.healthForPath("liveness")).thenReturn(Health.up().build());
        CompositeHealth readiness = composite(Status.DOWN, Map.of("db", Status.UP, "kafka", Status.DOWN));
        when(endpoint.healthForPath("readiness")).thenReturn(readiness);

        var res = new ApiHealthController(endpoint, noBuildInfo).health();

        assertThat(res.getStatusCode().value()).isEqualTo(503);
        assertThat(res.getBody().status()).isEqualTo("DOWN");
        assertThat(res.getBody().readiness()).isEqualTo("DOWN");
        assertThat(res.getBody().components()).containsEntry("kafka", "DOWN");
    }
}
