package dev.pbmlab.claims.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Metadata shown at the top of Swagger UI (/swagger-ui.html). */
@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI claimsOpenApi() {
        return new OpenAPI().info(new Info()
                .title("PBM Claims Lab API")
                .version("0.1.0")
                .description("""
                        Simplified pharmacy (PBM) claims adjudication: bill (B1), reverse (B2), and inspect \
                        accumulators, outbox events and seeded reference data. All data is fictional and in memory; \
                        restarting the app resets it. Seeded IDs: member M1001 (active) / M1002 (terminated), \
                        pharmacy PH-001 (in network) / PH-999 (out of network), NDCs 11111-2222-01 (generic), \
                        22222-3333-01 (brand), 33333-4444-01 (specialty, PA required), 99999-0000-01 (not covered)."""));
    }
}
