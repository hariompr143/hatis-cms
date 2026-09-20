package com.hatis.platform.integration.adapter.persistence;

import com.hatis.platform.shared.persistence.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.Array;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * A customer URL the platform posts events to.
 *
 * <p>Maps to {@code int_webhook_endpoints}. The signing secret is held as ciphertext
 * plus the identifier of the data key that protects it, exactly like the other
 * encrypted columns on the platform; the plaintext is generated once at registration
 * and never persisted anywhere.
 *
 * <h2>Why this sits in the persistence adapter rather than {@code domain}</h2>
 *
 * Every other aggregate in the platform lives in its {@code domain} package, and this
 * one cannot: {@code events} is a PostgreSQL {@code text[]} column, and mapping a real
 * SQL array requires {@code org.hibernate.annotations.Array}, which the architecture
 * rules forbid in {@code ..domain..} on purpose. The domain has to stay portable to
 * another framework or process, and a Hibernate array annotation is precisely the kind
 * of vendor detail that rule exists to keep out of it. Rather than weaken the rule for
 * one entity, the mapping lives where vendor detail belongs.
 *
 * <p>The array type is deliberate and worth defending. {@code text[]} lets a delivery
 * ask "does this endpoint subscribe to this event?" with
 * {@code events @> array['content.published']}, which is indexable with GIN. A CSV in a
 * varchar column would need a {@code like} match that cannot use an index and would
 * match {@code content.published} against {@code content.published.v2}.
 */
@Entity
@Table(name = "int_webhook_endpoints")
public class WebhookEndpoint extends TenantScopedEntity {

    @Column(name = "url", nullable = false, length = 512)
    private String url;

    @Column(name = "description", length = 512)
    private String description;

    /**
     * Event types this endpoint subscribes to. Empty means "everything".
     *
     * <p>Mapped as a native array. Hibernate sends this as a {@code varchar[]}
     * parameter, which PostgreSQL assignment-casts to {@code text[]} — verified against
     * a real server rather than assumed, because the two array types are distinct and
     * there is no implicit cast between arbitrary array types.
     */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Array(length = 64)
    @Column(name = "events", nullable = false, columnDefinition = "text[]")
    private String[] events = new String[0];

    @Column(name = "secret_ciphertext", nullable = false, columnDefinition = "text")
    private String secretCiphertext;

    @Column(name = "dek_id", nullable = false, length = 64)
    private String dekId;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    protected WebhookEndpoint() {
        super();
    }

    private WebhookEndpoint(UUID organizationId, String url, String description,
                            List<String> events, String secretCiphertext, String dekId) {
        super(organizationId);
        this.url = url;
        this.description = description;
        this.events = normalise(events);
        this.secretCiphertext = secretCiphertext;
        this.dekId = dekId;
    }

    /**
     * Registers a new endpoint.
     *
     * @param secretCiphertext the signing secret, already encrypted under {@code dekId}
     * @param dekId            identifier of the data key that protects the ciphertext
     */
    public static WebhookEndpoint register(UUID organizationId, String url, String description,
                                           List<String> events, String secretCiphertext,
                                           String dekId) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("url is required for a webhook endpoint");
        }
        if (secretCiphertext == null || secretCiphertext.isBlank()) {
            throw new IllegalArgumentException("secretCiphertext is required for a webhook endpoint");
        }
        if (dekId == null || dekId.isBlank()) {
            throw new IllegalArgumentException("dekId is required for a webhook endpoint");
        }
        return new WebhookEndpoint(organizationId, url, description, events,
                secretCiphertext, dekId);
    }

    /**
     * Sorted and de-duplicated, so that two registrations of the same subscription are
     * stored identically and equality does not depend on the order a client sent them in.
     */
    private static String[] normalise(List<String> events) {
        if (events == null || events.isEmpty()) {
            return new String[0];
        }
        return events.stream()
                .filter(e -> e != null && !e.isBlank())
                .map(String::trim)
                .distinct()
                .sorted()
                .toArray(String[]::new);
    }

    public String getUrl() {
        return url;
    }

    public String getDescription() {
        return description;
    }

    /** An unmodifiable copy; the array itself must stay under Hibernate's control. */
    public List<String> getEvents() {
        return List.of(events == null ? new String[0] : events);
    }

    public String getSecretCiphertext() {
        return secretCiphertext;
    }

    public String getDekId() {
        return dekId;
    }

    public boolean isActive() {
        return active;
    }

    public void pause() {
        this.active = false;
    }

    public void resume() {
        this.active = true;
    }

    public void changeSubscription(String newUrl, String newDescription, List<String> newEvents) {
        this.url = newUrl;
        this.description = newDescription;
        this.events = normalise(newEvents);
    }

    /**
     * Replaces the signing secret in place.
     *
     * <p>The old secret stops working the moment this commits: there is no dual-accept
     * window, because a window during which both secrets are honoured is a window in
     * which a leaked secret still works.
     */
    public void rotateSecret(String newCiphertext, String newDekId) {
        if (newCiphertext == null || newCiphertext.isBlank()) {
            throw new IllegalArgumentException("newCiphertext is required");
        }
        if (newDekId == null || newDekId.isBlank()) {
            throw new IllegalArgumentException("newDekId is required");
        }
        this.secretCiphertext = newCiphertext;
        this.dekId = newDekId;
    }

    @Override
    public String toString() {
        // The ciphertext and the data key identifier are both excluded: an endpoint is
        // logged whenever a delivery succeeds or fails, and neither belongs in a log.
        return "WebhookEndpoint{id=" + getId() + ", url=" + url
                + ", events=" + Arrays.toString(events) + ", active=" + active + "}";
    }
}
