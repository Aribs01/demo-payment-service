package com.example.transfer.mock.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@ConditionalOnProperty(
        prefix = "app.mocks",
        name = "enabled",
        havingValue = "true"
)
public class MockSecurityConfig {

    @Bean
    @Order(1)
    SecurityFilterChain mockSecurityFilterChain(
            HttpSecurity http,
            @Value("${app.mocks.admin-key}") String adminKey
    ) throws Exception {

        return http
                .securityMatcher("/mock/**")
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth
                        -> auth.anyRequest().access(
                                (authentication, context) -> {
                                    String supplied = context.getRequest()
                                            .getHeader("X-Mock-Admin-Key");

                                    boolean allowed = adminKey.equals(supplied);

                                    return new org.springframework.security.authorization.AuthorizationDecision(
                                            allowed
                                    );
                                }
                        )
                )
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint(
                                (request, response, exception)
                                -> response.sendError(
                                        HttpStatus.UNAUTHORIZED.value()
                                )
                        )
                )
                .build();
    }
}
