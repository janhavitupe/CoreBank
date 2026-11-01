package com.corebank.ai;

import com.google.adk.agents.LlmAgent;
import com.google.adk.events.Event;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Runs one Google ADK agent turn, and logs latency and token usage for every Gemini call. */
@Component
public class GeminiAgent {

    private static final Logger log = LoggerFactory.getLogger(GeminiAgent.class);

    private final boolean configured;
    private final String model;
    private final long timeoutSeconds;
    private final MeterRegistry meters;

    public GeminiAgent(@Value("${corebank.gemini.api-key}") String apiKey,
                       @Value("${corebank.gemini.model}") String model,
                       @Value("${corebank.gemini.timeout-seconds}") long timeoutSeconds,
                       MeterRegistry meters) {
        this.configured = StringUtils.hasText(apiKey);
        this.model = model;
        this.timeoutSeconds = timeoutSeconds;
        this.meters = meters;
    }

    public boolean isConfigured() {
        return configured;
    }

    public String run(String agentName, String instruction, List<?> tools, String userId, String prompt) {
        LlmAgent agent = LlmAgent.builder()
                .name(agentName)
                .model(model)
                .instruction(instruction)
                .tools(tools)
                .build();
        InMemoryRunner runner = new InMemoryRunner(agent);
        Session session = runner.sessionService().createSession(runner.appName(), userId).blockingGet();
        Content message = Content.fromParts(Part.fromText(prompt));

        long start = System.nanoTime();
        AtomicInteger tokens = new AtomicInteger();
        StringBuilder answer = new StringBuilder();
        try {
            for (Event event : runner.runAsync(userId, session.id(), message)
                    .timeout(timeoutSeconds, TimeUnit.SECONDS)
                    .blockingIterable()) {
                event.usageMetadata().flatMap(u -> u.totalTokenCount()).ifPresent(tokens::addAndGet);
                if (event.finalResponse()) {
                    answer.append(event.stringifyContent());
                }
            }
        } finally {
            long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            meters.timer("corebank.gemini.calls", "agent", agentName).record(millis, TimeUnit.MILLISECONDS);
            meters.counter("corebank.gemini.tokens", "agent", agentName).increment(tokens.get());
            log.info("Gemini call agent={} model={} latencyMs={} tokens={}", agentName, model, millis, tokens.get());
        }
        return answer.toString().trim();
    }
}
