package com.iacross.flowpilot.shared.web;

import com.iacross.flowpilot.shared.tenant.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Populates SLF4J MDC with per-request correlation IDs (TRD §11, NFR-OBS-1..3).
 *
 * Keys populated:
 *   requestId  — random UUID per request (from X-Request-ID header or generated)
 *   tenantId   — from TenantContext (set downstream by JwtAuthFilter)
 *
 * Runs early (Ordered.HIGHEST_PRECEDENCE) so all subsequent filters/handlers inherit MDC.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestLoggingFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain)
            throws ServletException, IOException {
        String requestId = request.getHeader("X-Request-ID");
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
        }
        MDC.put("requestId", requestId);
        response.setHeader("X-Request-ID", requestId);

        try {
            chain.doFilter(request, response);
            // Update tenantId after JwtAuthFilter has run
            if (TenantContext.isPresent()) {
                MDC.put("tenantId", TenantContext.get().toString());
            }
        } finally {
            MDC.clear();
        }
    }
}
