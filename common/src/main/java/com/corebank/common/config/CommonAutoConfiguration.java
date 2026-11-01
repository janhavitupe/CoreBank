package com.corebank.common.config;

import com.corebank.common.outbox.OutboxRelay;
import com.corebank.common.outbox.OutboxWriter;
import com.corebank.common.web.CorrelationIdFilter;
import com.corebank.common.web.GlobalExceptionHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.scheduling.annotation.EnableScheduling;

@AutoConfiguration
@EnableScheduling
@Import(GlobalExceptionHandler.class)
public class CommonAutoConfiguration {

    @Bean
    public FilterRegistrationBean<CorrelationIdFilter> correlationIdFilter() {
        FilterRegistrationBean<CorrelationIdFilter> bean = new FilterRegistrationBean<>(new CorrelationIdFilter());
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return bean;
    }

    /** Carries the correlation ID on service-to-service calls. */
    @Bean
    public RestClientCustomizer correlationIdPropagation() {
        return builder -> builder.requestInterceptor((request, body, execution) -> {
            String id = MDC.get(CorrelationIdFilter.MDC_KEY);
            if (id != null) {
                request.getHeaders().set(CorrelationIdFilter.HEADER, id);
            }
            return execution.execute(request, body);
        });
    }

    /** Retries a failed record 3 times with exponential backoff, then moves it to the topic's .DLT. */
    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<Object, Object> kafka) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafka,
                (record, e) -> new TopicPartition(record.topic() + ".DLT", -1));
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(3);
        backOff.setInitialInterval(500);
        backOff.setMultiplier(2);
        return new DefaultErrorHandler(recoverer, backOff);
    }

    @Bean
    public OutboxWriter outboxWriter(JdbcTemplate jdbc, ObjectMapper mapper) {
        return new OutboxWriter(jdbc, mapper);
    }

    @Bean
    @ConditionalOnProperty(name = "corebank.outbox.enabled", havingValue = "true", matchIfMissing = true)
    public OutboxRelay outboxRelay(JdbcTemplate jdbc, KafkaTemplate<String, String> kafka) {
        return new OutboxRelay(jdbc, kafka);
    }
}
