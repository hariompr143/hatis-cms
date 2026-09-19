package com.hatis.platform.domains.adapter.rest;

import com.hatis.platform.domains.application.DomainService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Custom domain API.
 *
 * <p>{@code POST /{id}/verify} is the only way a domain becomes active. There is no
 * request that makes a domain serve traffic without a successful DNS check.
 */
@RestController
@RequestMapping("/v1/domains")
@Tag(name = "Domains", description = "Custom domains, DNS verification and TLS")
public class DomainController {

    private final DomainService domains;

    public DomainController(DomainService domains) {
        this.domains = domains;
    }

    @PostMapping
    @Operation(summary = "Bind a custom domain and receive the DNS challenge")
    public ResponseEntity<DomainService.DomainRegistration> register(
            @Valid @RequestBody RegisterDomainRequest request) {
        var registration = domains.register(request.projectId(), request.environmentId(), request.hostname());
        return ResponseEntity.status(HttpStatus.CREATED).body(registration);
    }

    @GetMapping
    @Operation(summary = "List the domains bound to a project")
    public List<DomainService.DomainView> list(@RequestParam UUID projectId) {
        return domains.list(projectId);
    }

    @GetMapping("/{domainId}")
    @Operation(summary = "Get a domain")
    public DomainService.DomainView get(@PathVariable UUID domainId) {
        return domains.get(domainId);
    }

    @PostMapping("/{domainId}/verify")
    @Operation(summary = "Verify the DNS challenge and begin provisioning TLS")
    public DomainService.DomainView verify(@PathVariable UUID domainId) {
        return DomainService.DomainView.from(domains.check(domainId));
    }

    @PostMapping("/{domainId}/refresh")
    @Operation(summary = "Re-check DNS and certificate health")
    public DomainService.DomainView refresh(@PathVariable UUID domainId) {
        return DomainService.DomainView.from(domains.refresh(domainId));
    }

    @DeleteMapping("/{domainId}")
    @Operation(summary = "Unbind a domain and clean up its DNS record and certificate")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID domainId) {
        domains.delete(domainId);
    }

    public record RegisterDomainRequest(
            @NotNull UUID projectId,
            @NotNull UUID environmentId,
            @NotBlank @Size(max = 253) String hostname) {
    }
}
