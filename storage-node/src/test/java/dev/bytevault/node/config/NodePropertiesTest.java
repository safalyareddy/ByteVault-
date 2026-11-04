package dev.bytevault.node.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class NodePropertiesTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private static NodeProperties withSecretAndId(String secret, String id) {
        return new NodeProperties(id, Path.of("/tmp/x"), secret, "http://n:9100", "http://cp:8090",
                Duration.ofSeconds(5), 1024);
    }

    @Test
    void acceptsStrongSecretAndValidId() {
        assertThat(validator.validate(withSecretAndId("a".repeat(64), "node-1"))).isEmpty();
    }

    @Test
    void rejectsShortSecretWithoutEchoingIt() {
        var violations = validator.validate(withSecretAndId("short-secret-value", "node-1"));
        assertThat(violations).singleElement().satisfies(v -> {
            assertThat(v.getPropertyPath().toString()).isEqualTo("authSecretStrongEnough");
            assertThat(v.getMessage()).doesNotContain("short-secret-value");
        });
    }

    @Test
    void rejectsMissingSecret() {
        assertThat(validator.validate(withSecretAndId(null, "node-1")))
                .extracting(v -> v.getPropertyPath().toString())
                .containsExactly("authSecretStrongEnough");
    }

    @Test
    void rejectsNodeIdsThatCouldEscapePathsOrLabels() {
        for (String bad : new String[] {"Node-1", "../node", "node_1", "-node", ""}) {
            assertThat(validator.validate(withSecretAndId("a".repeat(64), bad)))
                    .as("id %s", bad)
                    .extracting(v -> v.getPropertyPath().toString())
                    .contains("id");
        }
    }
}
