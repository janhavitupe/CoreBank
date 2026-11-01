package com.corebank.gateway;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.util.Optional;
import java.security.Principal;

@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Bean
    SecurityWebFilterChain security(ServerHttpSecurity http) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(auth -> auth
                        .pathMatchers(HttpMethod.POST, "/api/customers").permitAll()
                        .pathMatchers("/api/auth/**").permitAll()
                        .pathMatchers("/", "/index.html", "/favicon.ico").permitAll()
                        .pathMatchers("/actuator/health/**", "/actuator/prometheus").permitAll()
                        .anyExchange().authenticated())
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> {
                }))
                .build();
    }

    /** Rate limits by customer once authenticated, and by client address on the public login routes. */
    @Bean
    KeyResolver clientKeyResolver() {
        return exchange -> exchange.getPrincipal()
                .map(Principal::getName)
                .switchIfEmpty(Mono.fromSupplier(() -> Optional.ofNullable(exchange.getRequest().getRemoteAddress())
                        .map(InetSocketAddress::getHostString)
                        .orElse("unknown")));
    }
}
