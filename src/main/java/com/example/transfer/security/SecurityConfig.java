package com.example.transfer.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import com.example.transfer.client.ClientRepository;

@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            ClientRepository clientRepository,
            @Value("${app.provider.callback-secret}") String callbackSecret
    ) throws Exception {

        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session
                        -> session.sessionCreationPolicy(
                                SessionCreationPolicy.STATELESS
                        )
                )
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v2/provider/events")
                        .access((authentication, context) -> {
                            String supplied = context.getRequest()
                                    .getHeader("X-Provider-Secret");
                            return new AuthorizationDecision(
                                    callbackSecret.equals(supplied)
                            );
                        })
                        .requestMatchers("/api/v2/transfers/**")
                        .authenticated()
                        .anyRequest().denyAll()
                )
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint(
                                (request, response, exception)
                                -> response.sendError(
                                        HttpStatus.UNAUTHORIZED.value()
                                )
                        )
                )
                .addFilterBefore(
                        new ApiKeyAuthenticationFilter(clientRepository),
                        UsernamePasswordAuthenticationFilter.class
                )
                .build();
    }
}
