package com.iacross.flowpilot.identity.controller;

import com.iacross.flowpilot.identity.service.AuthException;
import com.iacross.flowpilot.identity.service.AuthService;
import com.iacross.flowpilot.shared.tenant.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * REST endpoints for authentication.
 *
 * POST /api/v1/auth/register  — create workspace + owner, issue tokens
 * POST /api/v1/auth/login     — issue tokens
 * POST /api/v1/auth/refresh   — rotate refresh token
 * POST /api/v1/auth/logout    — revoke all refresh tokens
 * GET  /api/v1/me             — tenant-scoped probe (proves auth + RLS)
 */
@RestController
@RequestMapping("/api/v1")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    // ---- Request / Response records ----

    record RegisterRequest(
            @NotBlank String tenantName,
            @NotBlank @Email String email,
            @NotBlank @Size(min = 8) String password,
            String displayName
    ) {}

    record LoginRequest(
            @NotBlank @Email String email,
            @NotBlank String password
    ) {}

    record RefreshRequest(@NotBlank String refreshToken) {}

    record TokenResponse(String accessToken, String refreshToken) {}

    record MeResponse(String userId, String tenantId) {}

    // ---- Endpoints ----

    @PostMapping("/auth/register")
    @ResponseStatus(HttpStatus.CREATED)
    public TokenResponse register(@Valid @RequestBody RegisterRequest req) {
        var tokens = authService.register(
                new AuthService.RegisterCommand(req.tenantName(), req.email(), req.password(), req.displayName()));
        return new TokenResponse(tokens.accessToken(), tokens.rawRefreshToken());
    }

    @PostMapping("/auth/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest req) {
        var tokens = authService.login(new AuthService.LoginCommand(req.email(), req.password()));
        return new TokenResponse(tokens.accessToken(), tokens.rawRefreshToken());
    }

    @PostMapping("/auth/refresh")
    public TokenResponse refresh(@Valid @RequestBody RefreshRequest req) {
        var tokens = authService.refresh(new AuthService.RefreshCommand(req.refreshToken()));
        return new TokenResponse(tokens.accessToken(), tokens.rawRefreshToken());
    }

    @PostMapping("/auth/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@AuthenticationPrincipal String userId) {
        authService.logout(UUID.fromString(userId));
    }

    /**
     * Tenant-scoped probe: returns authenticated user's IDs.
     * Used by tests to verify bearer auth + RLS context are both established.
     */
    @GetMapping("/me")
    public MeResponse me(@AuthenticationPrincipal String userId) {
        return new MeResponse(userId, TenantContext.get() != null ? TenantContext.get().toString() : null);
    }

    // ---- Exception handler ----

    @ExceptionHandler(AuthException.class)
    public ResponseEntity<ErrorBody> handleAuthException(AuthException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ErrorBody(ex.getMessage()));
    }

    record ErrorBody(String error) {}
}
