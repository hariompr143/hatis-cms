package com.hatis.platform.domains.adapter.dns;

import com.hatis.platform.domains.port.out.DnsProvider;
import com.hatis.platform.shared.error.PlatformExceptions;
import com.hatis.platform.shared.secret.Secret;
import com.hatis.platform.shared.secret.SecretStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;

/**
 * Cloudflare DNS adapter for zones the platform manages on a customer's behalf.
 *
 * <p>The API token is read from the secret manager at request time, not held in a
 * field, so a heap dump or an actuator dump cannot recover it. It is scoped by the
 * operator to the specific zones the platform is allowed to touch — least
 * privilege is a configuration choice, and this adapter does not weaken it by
 * caching or reusing a broader credential.
 */
@Component
@ConditionalOnProperty(name = "hatis.domains.dns.mode", havingValue = "cloudflare")
public class CloudflareDnsProvider implements DnsProvider {

    private static final String TOKEN_PATH = "hatis/integrations/cloudflare/api-token";

    private final WebClient client;
    private final SecretStore secrets;
    private final List<String> managedZones;

    public CloudflareDnsProvider(SecretStore secrets, org.springframework.core.env.Environment environment) {
        this.secrets = secrets;
        this.client = WebClient.builder()
                .baseUrl(environment.getProperty("hatis.domains.dns.cloudflare.base-url",
                        "https://api.cloudflare.com/client/v4"))
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(512 * 1024))
                .build();
        String zones = environment.getProperty("hatis.domains.dns.cloudflare.zones", "");
        this.managedZones = java.util.Arrays.stream(zones.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toList();
    }

    @Override
    public String name() {
        return "cloudflare";
    }

    @Override
    public boolean manages(String apexDomain) {
        return managedZones.contains(apexDomain);
    }

    @Override
    public void upsertRecord(String apexDomain, String recordName, RecordType type, String value, long ttl) {
        String zoneId = zoneId(apexDomain);
        String existing = findRecordId(zoneId, recordName, type);
        Map<String, Object> body = Map.of(
                "type", type.name(),
                "name", recordName,
                "content", value,
                "ttl", ttl,
                "proxied", false);
        try (Secret token = token()) {
            if (existing == null) {
                post("/zones/{zone}/dns_records", zoneId, body, token);
            } else {
                patch("/zones/{zone}/dns_records/{record}", zoneId, existing, body, token);
            }
        } catch (PlatformExceptions.DependencyUnavailable e) {
            throw e;
        } catch (Exception e) {
            throw new PlatformExceptions.DependencyUnavailable("dns",
                    "Unable to write the record for " + recordName);
        }
    }

    @Override
    public void deleteRecord(String apexDomain, String recordName, RecordType type) {
        String zoneId = zoneId(apexDomain);
        String recordId = findRecordId(zoneId, recordName, type);
        if (recordId == null) {
            return;
        }
        try (Secret token = token()) {
            client.delete()
                    .uri("/zones/{zone}/dns_records/{record}", zoneId, recordId)
                    .header("Authorization", "Bearer " + token.reveal())
                    .retrieve()
                    .toBodilessEntity()
                    .block();
        } catch (Exception e) {
            throw new PlatformExceptions.DependencyUnavailable("dns",
                    "Unable to delete the record for " + recordName);
        }
    }

    /**
     * Looks records up through the Cloudflare API rather than the resolver.
     *
     * <p>For a zone the platform manages, the authoritative answer is what matters;
     * a resolver could still be serving a cached record and cause the platform to
     * report a healthy domain as degraded.
     */
    @Override
    public List<String> lookup(String recordName, RecordType type) {
        String apexDomain = com.hatis.platform.domains.domain.Domain.apexOf(recordName);
        if (!manages(apexDomain)) {
            return List.of();
        }
        try (Secret token = token()) {
            Map<?, ?> response = client.get()
                    .uri(uri -> uri.path("/zones/{zone}/dns_records")
                            .queryParam("name", recordName)
                            .queryParam("type", type.name())
                            .build(zoneId(apexDomain)))
                    .header("Authorization", "Bearer " + token.reveal())
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();
            return records(response).stream().map(record -> String.valueOf(record.get("content"))).toList();
        } catch (Exception e) {
            throw new PlatformExceptions.DependencyUnavailable("dns",
                    "Unable to read the records for " + recordName);
        }
    }

    private String findRecordId(String zoneId, String recordName, RecordType type) {
        try (Secret token = token()) {
            Map<?, ?> response = client.get()
                    .uri(uri -> uri.path("/zones/{zone}/dns_records")
                            .queryParam("name", recordName)
                            .queryParam("type", type.name())
                            .build(zoneId))
                    .header("Authorization", "Bearer " + token.reveal())
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();
            return records(response).stream()
                    .map(record -> String.valueOf(record.get("id")))
                    .findFirst()
                    .orElse(null);
        } catch (Exception e) {
            throw new PlatformExceptions.DependencyUnavailable("dns",
                    "Unable to look up the record for " + recordName);
        }
    }

    private String zoneId(String apexDomain) {
        try (Secret token = token()) {
            Map<?, ?> response = client.get()
                    .uri(uri -> uri.path("/zones").queryParam("name", apexDomain).build())
                    .header("Authorization", "Bearer " + token.reveal())
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();
            return records(response).stream()
                    .map(zone -> String.valueOf(zone.get("id")))
                    .findFirst()
                    .orElseThrow(() -> new PlatformExceptions.BusinessRuleViolation(
                            "No Cloudflare zone exists for " + apexDomain));
        } catch (PlatformExceptions.BusinessRuleViolation e) {
            throw e;
        } catch (Exception e) {
            throw new PlatformExceptions.DependencyUnavailable("dns",
                    "Unable to resolve the zone for " + apexDomain);
        }
    }

    private void post(String uriTemplate, String zoneId, Map<String, Object> body, Secret token) {
        client.post()
                .uri(uriTemplate, zoneId)
                .header("Authorization", "Bearer " + token.reveal())
                .bodyValue(body)
                .retrieve()
                .toBodilessEntity()
                .block();
    }

    private void patch(String uriTemplate, String zoneId, String recordId, Map<String, Object> body,
                       Secret token) {
        client.patch()
                .uri(uriTemplate, zoneId, recordId)
                .header("Authorization", "Bearer " + token.reveal())
                .bodyValue(body)
                .retrieve()
                .toBodilessEntity()
                .block();
    }

    private static List<Map<?, ?>> records(Map<?, ?> response) {
        Object result = response == null ? null : response.get("result");
        if (!(result instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .filter(element -> element instanceof Map)
                .map(element -> (Map<?, ?>) element)
                .toList();
    }

    private Secret token() {
        Secret token = secrets.get(TOKEN_PATH);
        if (token.isEmpty()) {
            throw new PlatformExceptions.DependencyUnavailable("dns",
                    "The Cloudflare API token is not configured in the secret manager");
        }
        return token;
    }
}
