package com.corebank.common.web;

import org.springframework.http.HttpStatus;

/** Identity headers set by the gateway after it verifies the JWT. */
public final class Caller {

    public static final String CUSTOMER_HEADER = "X-Customer-Id";
    public static final String ROLE_HEADER = "X-Role";
    public static final String ADMIN = "ADMIN";

    private Caller() {
    }

    public static void requireAdmin(String role) {
        if (!ADMIN.equals(role)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ADMIN_REQUIRED", "This action requires the admin role");
        }
    }
}
