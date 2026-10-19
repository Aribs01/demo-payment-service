package com.example.transfer.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;

@Configuration
public class OpenApiConfig {

    public static final String API_KEY = "apiKey";
    public static final String PROVIDER_SECRET = "providerSecret";
    public static final String MOCK_ADMIN_KEY = "mockAdminKey";

    @Bean
    OpenAPI transferOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Transfer Service")
                        .version("v2")
                        .description("NGN transfer API, provider callbacks, and local mock services."))
                .components(new Components()
                        .addSecuritySchemes(API_KEY, headerScheme("X-API-Key"))
                        .addSecuritySchemes(PROVIDER_SECRET, headerScheme("X-Provider-Secret"))
                        .addSecuritySchemes(MOCK_ADMIN_KEY, headerScheme("X-Mock-Admin-Key")));
    }

    private static SecurityScheme headerScheme(String headerName) {
        return new SecurityScheme()
                .type(SecurityScheme.Type.APIKEY)
                .in(SecurityScheme.In.HEADER)
                .name(headerName);
    }
}
