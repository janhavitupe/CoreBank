package com.corebank.customer;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Signs RS256 access tokens. Only the public key leaves this service, through the JWKS endpoint.
 * The key is generated at startup, so a restart signs existing users out; that is acceptable for one
 * replica, while several replicas would need a shared key from a Kubernetes Secret.
 */
@Service
public class TokenService {

    private final RSAKey key;
    private final JdbcTemplate jdbc;
    private final Duration accessTtl;
    private final Duration refreshTtl;
    private final SecureRandom random = new SecureRandom();

    public TokenService(JdbcTemplate jdbc,
                        @Value("${corebank.jwt.access-minutes}") long accessMinutes,
                        @Value("${corebank.jwt.refresh-days}") long refreshDays) throws JOSEException {
        this.jdbc = jdbc;
        this.accessTtl = Duration.ofMinutes(accessMinutes);
        this.refreshTtl = Duration.ofDays(refreshDays);
        this.key = new RSAKeyGenerator(2048).keyID(UUID.randomUUID().toString()).generate();
    }

    public Map<String, Object> publicJwks() {
        return new JWKSet(key.toPublicJWK()).toJSONObject();
    }

    public String issueAccessToken(UUID customerId, String role) {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(customerId.toString())
                .claim("role", role)
                .issuer("corebank")
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(accessTtl)))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
        try {
            jwt.sign(new RSASSASigner(key));
        } catch (JOSEException e) {
            throw new IllegalStateException("Could not sign token", e);
        }
        return jwt.serialize();
    }

    public long accessTtlSeconds() {
        return accessTtl.toSeconds();
    }

    public String issueRefreshToken(UUID customerId) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        jdbc.update("INSERT INTO refresh_tokens (token_hash, customer_id, expires_at) VALUES (?, ?, ?)",
                hash(token), customerId, Timestamp.from(Instant.now().plus(refreshTtl)));
        return token;
    }

    /** Refresh tokens are single use: consuming one deletes it, and the caller issues a replacement. */
    public Optional<UUID> consumeRefreshToken(String token) {
        return jdbc.query("DELETE FROM refresh_tokens WHERE token_hash = ? AND expires_at > now() RETURNING customer_id",
                (rs, i) -> rs.getObject(1, UUID.class), hash(token)).stream().findFirst();
    }

    private static String hash(String token) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
