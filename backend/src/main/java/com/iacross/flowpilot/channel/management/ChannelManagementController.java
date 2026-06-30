package com.iacross.flowpilot.channel.management;

import com.iacross.flowpilot.channel.domain.ChannelConnection;
import com.iacross.flowpilot.shared.tenant.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/channels")
public class ChannelManagementController {

    private final ChannelManagementService service;

    public ChannelManagementController(ChannelManagementService service) {
        this.service = service;
    }

    record ConnectTelegramRequest(
        @NotBlank String botToken,
        @NotBlank String name,
        @NotNull  UUID flowId
    ) {}

    record ChannelView(UUID id, String type, String name, String status, UUID flowId) {
        static ChannelView from(ChannelConnection c) {
            return new ChannelView(c.getId(), c.getType(), c.getName(), c.getStatus(), c.getFlowId());
        }
    }

    @PostMapping("/telegram")
    @ResponseStatus(HttpStatus.CREATED)
    public ChannelView connectTelegram(
            @Valid @RequestBody ConnectTelegramRequest req,
            @AuthenticationPrincipal String userId) {
        UUID tenantId = TenantContext.get();
        var cmd = new ChannelManagementService.ConnectTelegramCommand(
            req.botToken(), req.name(), req.flowId());
        ChannelConnection conn = service.connectTelegram(cmd, tenantId);
        return ChannelView.from(conn);
    }

    @GetMapping
    public ResponseEntity<List<ChannelView>> listChannels(@AuthenticationPrincipal String userId) {
        UUID tenantId = TenantContext.get();
        List<ChannelView> channels = service.listForTenant(tenantId)
            .stream().map(ChannelView::from).toList();
        return ResponseEntity.ok(channels);
    }
}
