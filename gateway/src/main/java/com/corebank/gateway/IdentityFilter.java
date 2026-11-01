package com.corebank.gateway;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.security.Principal;
import java.util.Optional;
import java.util.UUID;

/**
 * Replaces client-supplied identity headers with values from the verified JWT. Removing them first is
 * what stops a caller claiming to be someone else, because services trust these headers.
 */
@Component
public class IdentityFilter implements GlobalFilter, Ordered {

    private static final String CUSTOMER_HEADER = "X-Customer-Id";
    private static final String ROLE_HEADER = "X-Role";
    private static final String CORRELATION_HEADER = "X-Correlation-Id";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String correlationId = UUID.randomUUID().toString();
        exchange.getResponse().getHeaders().set(CORRELATION_HEADER, correlationId);

        return exchange.getPrincipal()
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .flatMap(principal -> {
                    ServerHttpRequest request = exchange.getRequest().mutate()
                            .headers(headers -> {
                                headers.remove(CUSTOMER_HEADER);
                                headers.remove(ROLE_HEADER);
                                headers.set(CORRELATION_HEADER, correlationId);
                                principal.ifPresent(p -> addIdentity(headers, p));
                            })
                            .build();
                    return chain.filter(exchange.mutate().request(request).build());
                });
    }

    private static void addIdentity(org.springframework.http.HttpHeaders headers, Principal principal) {
        if (principal instanceof JwtAuthenticationToken token) {
            headers.set(CUSTOMER_HEADER, token.getToken().getSubject());
            headers.set(ROLE_HEADER, token.getToken().getClaimAsString("role"));
        }
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
