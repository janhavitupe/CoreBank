package com.corebank.account;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Read-through balance cache keyed by account number. Redis failures fall back to PostgreSQL,
 * because the cache is an optimisation and the database is the source of truth.
 */
@Component
public class BalanceCache {

    private static final Logger log = LoggerFactory.getLogger(BalanceCache.class);

    private final StringRedisTemplate redis;
    private final Duration ttl;
    private final Counter hits;
    private final Counter misses;

    public BalanceCache(StringRedisTemplate redis, MeterRegistry meters,
                        @Value("${corebank.balance-cache.ttl-seconds}") long ttlSeconds) {
        this.redis = redis;
        this.ttl = Duration.ofSeconds(ttlSeconds);
        this.hits = meters.counter("corebank.balance.cache", "result", "hit");
        this.misses = meters.counter("corebank.balance.cache", "result", "miss");
    }

    public BigDecimal get(String accountNumber, Supplier<BigDecimal> loader) {
        Optional<BigDecimal> cached = read(accountNumber);
        if (cached.isPresent()) {
            hits.increment();
            return cached.get();
        }
        misses.increment();
        BigDecimal balance = loader.get();
        try {
            redis.opsForValue().set(key(accountNumber), balance.toPlainString(), ttl);
        } catch (RuntimeException e) {
            log.warn("Redis write failed: {}", e.getMessage());
        }
        return balance;
    }

    public void evict(String accountNumber) {
        try {
            redis.delete(key(accountNumber));
        } catch (RuntimeException e) {
            log.warn("Redis eviction failed for {}: {}", accountNumber, e.getMessage());
        }
    }

    private Optional<BigDecimal> read(String accountNumber) {
        try {
            return Optional.ofNullable(redis.opsForValue().get(key(accountNumber))).map(BigDecimal::new);
        } catch (RuntimeException e) {
            log.warn("Redis read failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private static String key(String accountNumber) {
        return "balance:" + accountNumber;
    }
}
