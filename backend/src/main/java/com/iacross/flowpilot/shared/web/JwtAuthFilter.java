package com.iacross.flowpilot.shared.web;

import com.iacross.flowpilot.shared.security.JwtUtil;
import com.iacross.flowpilot.shared.tenant.TenantContext;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * Extracts and validates the Bearer JWT from {@code Authorization} header.
 * On success: populates Spring SecurityContext and {@link TenantContext}.
 * Always clears TenantContext in finally — prevents leakage across virtual-thread reuse.
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;

    public JwtAuthFilter(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain)
            throws ServletException, IOException {
        try {
            String authHeader = request.getHeader("Authorization");
            if (authHeader != null && authHeader.startsWith("Bearer ")) {
                String token = authHeader.substring(7);
                try {
                    Claims claims = jwtUtil.parse(token);
                    UUID userId   = jwtUtil.extractUserId(claims);
                    UUID tenantId = jwtUtil.extractTenantId(claims);

                    TenantContext.set(tenantId);

                    String role = claims.get("role", String.class);
                    var auth = new UsernamePasswordAuthenticationToken(
                            userId.toString(),
                            null,
                            role != null
                                ? List.of(new SimpleGrantedAuthority("ROLE_" + role.toUpperCase()))
                                : List.of(new SimpleGrantedAuthority("ROLE_OWNER"))
                    );
                    SecurityContextHolder.getContext().setAuthentication(auth);
                } catch (JwtException ex) {
                    // Invalid / expired token — let the request proceed unauthenticated;
                    // Spring Security will reject it on protected routes.
                    SecurityContextHolder.clearContext();
                }
            }
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }
}
