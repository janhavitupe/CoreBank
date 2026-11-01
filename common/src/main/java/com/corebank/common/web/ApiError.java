package com.corebank.common.web;

import java.time.Instant;

public record ApiError(String code, String message, String correlationId, Instant timestamp) {
}
