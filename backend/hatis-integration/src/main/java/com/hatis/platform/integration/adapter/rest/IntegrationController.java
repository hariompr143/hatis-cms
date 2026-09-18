package com.hatis.platform.integration.adapter.rest;

import com.hatis.platform.integration.application.IntegrationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Integration management API.
 *
 * <p>Ordinary authenticated endpoints, unlike the webhook receiver: these are called by
 * an operator in the console, so they go through the JWT filter and the authorization
 * checks live in the service.
 *
 * <p>{@code connect} and {@code rotate} are the only responses in the platform that
 * carry a plaintext credential. Both return it exactly once; there is no endpoint that
 * reads it back, because the platform stores it in the secret manager and never in this
 * database.
 */
@RestController
@RequestMapping("/v1/integrations")
@Tag(name = "Integrations", description = "Connections to external systems")
public class IntegrationController {

    private final IntegrationService integrations;

    public IntegrationController(IntegrationService integrations) {
        this.integrations = integrations;
    }

    @PostMapping
    @Operation(summary = "Create an integration")
    public ResponseEntity<IntegrationService.IntegrationView> create(
            @Valid @RequestBody IntegrationService.CreateIntegrationCommand command) {
        return ResponseEntity.status(HttpStatus.CREATED).body(integrations.create(command));
    }

    @GetMapping
    @Operation(summary = "List GitHub integrations")
    public List<IntegrationService.IntegrationView> list() {
        return integrations.list();
    }

    @PostMapping("/{integrationId}/connect")
    @Operation(summary = "Connect an integration and issue its signing secret")
    public IntegrationService.ConnectedIntegration connect(@PathVariable UUID integrationId) {
        return integrations.connect(integrationId);
    }

    @PostMapping("/{integrationId}/rotate")
    @Operation(summary = "Replace the signing secret; the old one stops working immediately")
    public IntegrationService.ConnectedIntegration rotate(@PathVariable UUID integrationId) {
        return integrations.rotate(integrationId);
    }

    @DeleteMapping("/{integrationId}")
    @Operation(summary = "Disconnect an integration and delete its signing secret")
    public ResponseEntity<Void> disconnect(@PathVariable UUID integrationId) {
        integrations.disconnect(integrationId);
        return ResponseEntity.noContent().build();
    }
}
