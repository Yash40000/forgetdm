package io.forgetdm.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {
    @Bean
    OpenAPI forgeTdmOpenApi() {
        String scheme = "bearerAuth";
        return new OpenAPI()
                .info(new Info().title("ForgeTDM Automation API").version("1.0.0")
                        .description("Discovery, masking, DataScope, synthetic generation, governance, self-service, validation, and operations APIs."))
                .components(new Components().addSecuritySchemes(scheme,
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("ForgeTDM API token")))
                .addSecurityItem(new SecurityRequirement().addList(scheme));
    }
}
