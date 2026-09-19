package com.hatis.platform.integration.adapter.rest;

import com.hatis.platform.integration.application.WebhookEndpointService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Outbound webhook endpoint management.
 *
 * <p>Authenticated like the rest of {@code /v1/integrations}: an operator in the console
 * calls these, and the authorization checks live in the service rather than here, so they
 * cannot be bypassed by reaching the service from somewhere else.
 *
 * <p>{@code POST} and {@code rotate} are the only two responses in the platform that carry
 * a plaintext outbound signing secret. Both return it exactly once. There is deliberately
 * no endpoint that reads it back — the platform keeps only ciphertext, so an operator who
 * loses the secret rotates rather than recovers.
 */
@RestController
@RequestMapping("/v1/integrations/webhooks")
@Tag(name = "Outbound webhooks", description = "URLs the platform posts events to")
public class WebhookEndpointController {

    private final WebhookEndpointService endpoints;

    public WebhookEndpointController(WebhookEndpointService endpoints) {
        this.endpoints = endpoints;
    }

    @PostMapping
    @Operation(summary = "Register an endpoint and issue its signing secret")
    public ResponseEntity<WebhookEndpointService.CreatedEndpoint> register(
            @Valid @RequestBody WebhookEndpointService.RegisterCommand command) {
        return ResponseEntity.status(HttpStatus.CREATED).body(endpoints.register(command));
    }

    @GetMapping
    @Operation(summary = "List endpoints")
    public List<WebhookEndpointService.EndpointView> list() {
        return endpoints.list();
    }

    @GetMapping("/{endpointId}")
    @Operation(summary = "Read one endpoint")
    public WebhookEndpointService.EndpointView get(@PathVariable UUID endpointId) {
        return endpoints.get(endpointId);
    }

    @PatchMapping("/{endpointId}")
    @Operation(summary = "Change the URL, description or event subscription")
    public WebhookEndpointService.EndpointView update(
            @PathVariable UUID endpointId,
            @Valid @RequestBody WebhookEndpointService.UpdateCommand command) {
        return endpoints.update(endpointId, command);
    }

    @PostMapping("/{endpointId}/pause")
    @Operation(summary = "Stop deliveries without discarding the subscription")
    public WebhookEndpointService.EndpointView pause(@PathVariable UUID endpointId) {
        return endpoints.pause(endpointId);
    }

    @PostMapping("/{endpointId}/resume")
    @Operation(summary = "Resume deliveries")
    public WebhookEndpointService.EndpointView resume(@PathVariable UUID endpointId) {
        return endpoints.resume(endpointId);
    }

    @PostMapping("/{endpointId}/rotate")
    @Operation(summary = "Replace the signing secret; the old one stops verifying immediately")
    public WebhookEndpointService.CreatedEndpoint rotate(@PathVariable UUID endpointId) {
        return endpoints.rotateSecret(endpointId);
    }

    @DeleteMapping("/{endpointId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete an endpoint")
    public void remove(@PathVariable UUID endpointId) {
        endpoints.remove(endpointId);
    }
}
