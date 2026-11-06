package dev.bytevault.controlplane.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** OpenAPI 3 description served at /v3/api-docs and committed as docs/openapi.json. */
@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI byteVaultOpenApi() {
        return new OpenAPI()
                .info(new Info().title("ByteVault API").version("v1")
                        .description("Public API of the ByteVault control plane. Authenticate with POST /api/v1/auth/login "
                                + "and send the token as 'Authorization: Bearer <token>'. Errors use the ApiError envelope; "
                                + "every response carries X-Correlation-Id. Node-internal endpoints (/internal/v1) are "
                                + "documented in docs/storage-node-protocol.md, not here."))
                .components(new Components().addSecuritySchemes("bearer",
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer")))
                .addSecurityItem(new SecurityRequirement().addList("bearer"));
    }
}
