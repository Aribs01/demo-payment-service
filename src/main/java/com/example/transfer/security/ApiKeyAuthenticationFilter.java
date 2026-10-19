package com.example.transfer.security;

import java.io.IOException;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import com.example.transfer.client.ClientRepository;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public class ApiKeyAuthenticationFilter
        extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthenticationFilter.class);

    private final ClientRepository clientRepository;

    public ApiKeyAuthenticationFilter(
            ClientRepository clientRepository
    ) {
        this.clientRepository = clientRepository;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        String apiKey = request.getHeader("X-API-Key");

        if (apiKey != null && !apiKey.isBlank()) {
            String hash = ApiKeyHasher.hash(apiKey);

            clientRepository.findByApiKeyHash(hash)
                    .ifPresentOrElse(client -> {
                        AuthenticatedClient principal
                                = new AuthenticatedClient(
                                        client.getId(),
                                        client.getName()
                                );

                        var authentication
                                = new UsernamePasswordAuthenticationToken(
                                        principal,
                                        null,
                                        List.of()
                                );

                        SecurityContextHolder.getContext()
                                .setAuthentication(authentication);
                        log.debug("Authenticated client {}", client.getId());
                    }, () -> log.warn("Rejected an unknown API key"));
        }

        filterChain.doFilter(request, response);
    }
}
