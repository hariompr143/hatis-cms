package com.hatis.platform.cms.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hatis.platform.authorization.application.AuthorizationService;
import com.hatis.platform.authorization.domain.ScopeType;
import com.hatis.platform.cms.adapter.persistence.ContentRepositories;
import com.hatis.platform.cms.domain.ContentItem;
import com.hatis.platform.cms.domain.ContentType;
import com.hatis.platform.cms.domain.ContentVersion;
import com.hatis.platform.shared.api.PageResponse;
import com.hatis.platform.shared.error.PlatformExceptions;
import com.hatis.platform.shared.event.EventPublisher;
import com.hatis.platform.shared.event.PlatformEvent;
import com.hatis.platform.shared.quota.QuotaKey;
import com.hatis.platform.shared.quota.QuotaService;
import com.hatis.platform.shared.tenant.TenantContextHolder;
import com.hatis.platform.shared.tenant.TenantTransactional;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Content authoring and publishing.
 *
 * <p>Every method resolves the tenant from the bound context and every repository
 * lookup includes it, so a caller cannot reach another tenant's content by guessing
 * an id — and if that check were ever bypassed, row level security would still not
 * return the row.
 *
 * <p>Writes create a new immutable version and repoint the item; the published
 * pointer moves only on {@link #publish}. Quota is checked before the version is
 * stored, not after.
 */
@Service
public class ContentService {

    private final ContentRepositories.ContentTypeRepository types;
    private final ContentRepositories.ContentItemRepository items;
    private final ContentRepositories.ContentVersionRepository versions;
    private final AuthorizationService authorization;
    private final QuotaService quotas;
    private final EventPublisher events;
    private final ObjectMapper mapper;

    public ContentService(ContentRepositories.ContentTypeRepository types,
                          ContentRepositories.ContentItemRepository items,
                          ContentRepositories.ContentVersionRepository versions,
                          AuthorizationService authorization,
                          QuotaService quotas,
                          EventPublisher events,
                          ObjectMapper mapper) {
        this.types = types;
        this.items = items;
        this.versions = versions;
        this.authorization = authorization;
        this.quotas = quotas;
        this.events = events;
        this.mapper = mapper;
    }

    // ---------------------------------------------------------------- types

    @TenantTransactional(readOnly = true)
    public List<ContentType> listTypes(UUID projectId) {
        UUID organizationId = requireTenant(ScopeType.PROJECT, projectId,
                "content_type:read");
        return types.findByOrganizationIdAndProjectId(organizationId, projectId);
    }

    @TenantTransactional(readOnly = true)
    public ContentType type(UUID typeId) {
        UUID organizationId = requireTenant(ScopeType.PROJECT, null, "content_type:read");
        return types.findByIdAndOrganizationId(typeId, organizationId)
                .orElseThrow(() -> new PlatformExceptions.NotFound("Content type", typeId));
    }

    @TenantTransactional
    public ContentType createType(UUID projectId, String name, String slug, String description,
                                  String schema, String titleField) {
        UUID organizationId = requireTenant(ScopeType.PROJECT, projectId,
                "content_type:write");
        if (types.existsByOrganizationIdAndProjectIdAndSlug(organizationId, projectId, slug)) {
            throw new PlatformExceptions.AlreadyExists("A content type with slug '" + slug + "' already exists");
        }
        parseSchema(schema);
        ContentType created = types.save(new ContentType(organizationId, projectId, name, slug,
                description, schema, titleField, TenantContextHolder.require().principalId()));
        events.publish(PlatformEvent.of("cms.content_type.created", organizationId)
                .resource("content_type", created.getId())
                .data(Map.of("projectId", projectId.toString(), "slug", created.getSlug()))
                .build());
        return created;
    }

    /** Publishes a new schema version without touching existing content. */
    @TenantTransactional
    public ContentType reviseSchema(UUID typeId, String schema) {
        UUID organizationId = requireTenant(ScopeType.PROJECT, null, "content_type:write");
        ContentType type = types.findByIdAndOrganizationId(typeId, organizationId)
                .orElseThrow(() -> new PlatformExceptions.NotFound("Content type", typeId));
        parseSchema(schema);
        type.reviseSchema(schema);
        return types.save(type);
    }

    // ---------------------------------------------------------------- items

    @TenantTransactional(readOnly = true)
    public PageResponse<ContentSummary> list(UUID projectId, String status, int page, int size) {
        UUID organizationId = requireTenant(ScopeType.PROJECT, projectId, "content:read");
        var pageable = com.hatis.platform.shared.api.PageResponse.pageable(page, size,
                org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC,
                        "updatedAt"));
        var result = status == null || status.isBlank()
                ? items.findByOrganizationIdAndProjectIdAndStatusNot(organizationId, projectId,
                        ContentItem.Status.DELETED, pageable)
                : items.findByOrganizationIdAndProjectIdAndStatus(organizationId, projectId,
                        ContentItem.Status.valueOf(status.toUpperCase(java.util.Locale.ROOT)), pageable);
        return PageResponse.from(result, this::toSummary);
    }

    @TenantTransactional(readOnly = true)
    public ContentDetail get(UUID itemId) {
        UUID organizationId = requireTenant(ScopeType.PROJECT, null, "content:read");
        ContentItem item = requireItem(itemId, organizationId);
        ContentVersion version = item.getCurrentVersionId() == null
                ? null
                : versions.findByIdAndOrganizationId(item.getCurrentVersionId(), organizationId).orElse(null);
        return toDetail(item, version);
    }

    @TenantTransactional
    public ContentDetail create(UUID projectId, UUID contentTypeId, String slug, String locale,
                                JsonNode body, String changeNote) {
        UUID organizationId = requireTenant(ScopeType.PROJECT, projectId, "content:write");
        quotas.check(organizationId, QuotaKey.CONTENT_ITEMS, 1);

        ContentType type = types.findByIdAndOrganizationId(contentTypeId, organizationId)
                .orElseThrow(() -> new PlatformExceptions.NotFound("Content type", contentTypeId));
        if (!type.getProjectId().equals(projectId)) {
            throw new PlatformExceptions.Validation("The content type belongs to another project",
                    Map.of("field", "contentTypeId"));
        }
        if (items.existsByOrganizationIdAndProjectIdAndLocaleAndSlug(organizationId, projectId,
                locale == null ? "en" : locale, ContentItem.normalizeSlug(slug))) {
            throw new PlatformExceptions.AlreadyExists(
                    "Content with slug '" + slug + "' already exists in locale '" + locale + "'");
        }

        Map<String, Object> normalised = ContentBodyValidator.validate(body, parseSchema(type.getSchema()));
        UUID actor = TenantContextHolder.require().principalId();

        ContentItem item = items.save(new ContentItem(organizationId, projectId, contentTypeId,
                slug, locale, actor));
        int versionNumber = versions.findMaxVersionNumber(item.getId(), organizationId) + 1;
        ContentVersion version = versions.save(new ContentVersion(organizationId, item.getId(), versionNumber,
                writeJson(normalised), searchIndex(normalised), changeNote, actor));
        item.applyDraft(version.getId(), actor);
        items.save(item);
        quotas.record(organizationId, QuotaKey.CONTENT_ITEMS, 1);

        events.publish(PlatformEvent.of("cms.content.created", organizationId)
                .resource("content_item", item.getId())
                .data(Map.of("projectId", projectId.toString(), "slug", item.getSlug()))
                .build());
        return toDetail(item, version);
    }

    /** Saves a new draft version. Never changes what the delivery API serves. */
    @TenantTransactional
    public ContentDetail update(UUID itemId, JsonNode body, String changeNote) {
        UUID organizationId = requireTenant(ScopeType.PROJECT, null, "content:write");
        ContentItem item = requireItem(itemId, organizationId);
        ContentType type = types.findByIdAndOrganizationId(item.getContentTypeId(), organizationId)
                .orElseThrow(() -> new PlatformExceptions.NotFound("Content type", item.getContentTypeId()));

        Map<String, Object> normalised = ContentBodyValidator.validate(body, parseSchema(type.getSchema()));
        UUID actor = TenantContextHolder.require().principalId();
        int versionNumber = versions.findMaxVersionNumber(item.getId(), organizationId) + 1;
        ContentVersion version = versions.save(new ContentVersion(organizationId, item.getId(), versionNumber,
                writeJson(normalised), searchIndex(normalised), changeNote, actor));
        item.applyDraft(version.getId(), actor);
        items.save(item);
        return toDetail(item, version);
    }

    @TenantTransactional
    public ContentDetail publish(UUID itemId) {
        UUID organizationId = requireTenant(ScopeType.PROJECT, null, "content:publish");
        ContentItem item = requireItem(itemId, organizationId);
        item.publish();
        items.save(item);
        events.publish(PlatformEvent.of("cms.content.published", organizationId)
                .resource("content_item", item.getId())
                .data(Map.of("slug", item.getSlug(), "version", item.getPublishedVersionId().toString()))
                .build());
        return toDetail(item, versions.findByIdAndOrganizationId(item.getPublishedVersionId(), organizationId)
                .orElse(null));
    }

    @TenantTransactional
    public ContentDetail unpublish(UUID itemId) {
        UUID organizationId = requireTenant(ScopeType.PROJECT, null, "content:publish");
        ContentItem item = requireItem(itemId, organizationId);
        item.unpublish();
        items.save(item);
        events.publish(PlatformEvent.of("cms.content.unpublished", organizationId)
                .resource("content_item", item.getId())
                .data(Map.of("slug", item.getSlug()))
                .build());
        return toDetail(item, null);
    }

    /** Rolls the published pointer back to an earlier version without deleting history. */
    @TenantTransactional
    public ContentDetail rollback(UUID itemId, int versionNumber) {
        UUID organizationId = requireTenant(ScopeType.PROJECT, null, "content:publish");
        ContentItem item = requireItem(itemId, organizationId);
        ContentVersion target = versions
                .findByContentItemIdAndOrganizationIdOrderByVersionNumberDesc(item.getId(), organizationId)
                .stream()
                .filter(v -> v.getVersionNumber() == versionNumber)
                .findFirst()
                .orElseThrow(() -> new PlatformExceptions.NotFound("Content version", versionNumber));
        item.rollbackTo(target.getId());
        items.save(item);
        events.publish(PlatformEvent.of("cms.content.rolled_back", organizationId)
                .resource("content_item", item.getId())
                .data(Map.of("version", versionNumber))
                .build());
        return toDetail(item, target);
    }

    @TenantTransactional(readOnly = true)
    public List<VersionSummary> history(UUID itemId) {
        UUID organizationId = requireTenant(ScopeType.PROJECT, null, "content:read");
        ContentItem item = requireItem(itemId, organizationId);
        return versions.findByContentItemIdAndOrganizationIdOrderByVersionNumberDesc(item.getId(), organizationId)
                .stream()
                .map(v -> new VersionSummary(v.getId(), v.getVersionNumber(), v.getChangeNote(),
                        v.getCreatedBy(), v.getCreatedAt()))
                .toList();
    }

    /** Soft delete: the row remains for export and audit until the retention job runs. */
    @TenantTransactional
    public void delete(UUID itemId) {
        UUID organizationId = requireTenant(ScopeType.PROJECT, null, "content:write");
        ContentItem item = requireItem(itemId, organizationId);
        item.delete();
        items.save(item);
        quotas.record(organizationId, QuotaKey.CONTENT_ITEMS, -1);
        events.publish(PlatformEvent.of("cms.content.deleted", organizationId)
                .resource("content_item", item.getId())
                .build());
    }

    // -------------------------------------------------------- delivery read

    /**
     * Read path used by the delivery API.
     *
     * <p>Returns only published content, and only the published version — never the
     * current draft. This is the boundary between the authoring plane and the data
     * plane, and it is deliberately the narrowest query in the module.
     */
    @TenantTransactional(readOnly = true)
    public Optional<ContentDetail> published(UUID projectId, String locale, String slug) {
        UUID organizationId = TenantContextHolder.require().requireOrganizationId();
        return items.findByOrganizationIdAndProjectIdAndLocaleAndSlug(organizationId, projectId,
                        locale == null ? "en" : locale, slug)
                .filter(ContentItem::isPublished)
                .flatMap(item -> versions.findByIdAndOrganizationId(item.getPublishedVersionId(), organizationId)
                        .map(version -> toDetail(item, version)));
    }

    @TenantTransactional(readOnly = true)
    public PageResponse<ContentSummary> publishedList(UUID projectId, int page, int size) {
        UUID organizationId = TenantContextHolder.require().requireOrganizationId();
        var pageable = com.hatis.platform.shared.api.PageResponse.pageable(page, size,
                org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC,
                        "publishedAt"));
        var result = items.findPublished(organizationId, projectId, pageable);
        return PageResponse.from(result, this::toSummary);
    }

    // ------------------------------------------------------------- helpers

    private UUID requireTenant(ScopeType scope, UUID scopeId, String permission) {
        UUID organizationId = TenantContextHolder.require().requireOrganizationId();
        authorization.require(permission, scope, scopeId == null ? organizationId : scopeId);
        return organizationId;
    }

    private ContentItem requireItem(UUID itemId, UUID organizationId) {
        return items.findByIdAndOrganizationId(itemId, organizationId)
                .filter(item -> item.getStatus() != ContentItem.Status.DELETED)
                .orElseThrow(() -> new PlatformExceptions.NotFound("Content", itemId));
    }

    private JsonNode parseSchema(String schema) {
        try {
            return mapper.readTree(schema);
        } catch (Exception e) {
            throw new PlatformExceptions.BusinessRuleViolation("The content type schema is not valid JSON");
        }
    }

    private String writeJson(Map<String, Object> value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new PlatformExceptions.Validation("Content body could not be serialised", Map.of());
        }
    }

    private String searchIndex(Map<String, Object> body) {
        StringBuilder text = new StringBuilder();
        body.forEach((key, value) -> {
            if (value instanceof String string) {
                text.append(RichTextSanitizer.toPlainText(string)).append(' ');
            } else if (value instanceof List<?> list) {
                list.forEach(element -> text.append(element).append(' '));
            }
        });
        return text.toString().trim();
    }

    private ContentSummary toSummary(ContentItem item) {
        return new ContentSummary(item.getId(), item.getSlug(), item.getLocale(), item.getStatus().name(),
                item.getProjectId(), item.getContentTypeId(), item.getPublishedAt(), item.getUpdatedAt());
    }

    private ContentDetail toDetail(ContentItem item, ContentVersion version) {
        JsonNode body = null;
        if (version != null) {
            try {
                body = mapper.readTree(version.getBody());
            } catch (Exception e) {
                throw new PlatformExceptions.OperationFailed("Stored content body could not be read");
            }
        }
        return new ContentDetail(item.getId(), item.getProjectId(), item.getContentTypeId(), item.getSlug(),
                item.getLocale(), item.getStatus().name(), item.getCurrentVersionId(),
                item.getPublishedVersionId(), version == null ? null : version.getVersionNumber(), body,
                item.getPublishedAt(), item.getCreatedAt(), item.getUpdatedAt());
    }

    public record ContentSummary(UUID id, String slug, String locale, String status, UUID projectId,
                                 UUID contentTypeId, java.time.Instant publishedAt,
                                 java.time.Instant updatedAt) {
    }

    public record ContentDetail(UUID id, UUID projectId, UUID contentTypeId, String slug, String locale,
                                String status, UUID currentVersionId, UUID publishedVersionId,
                                Integer versionNumber, JsonNode body, java.time.Instant publishedAt,
                                java.time.Instant createdAt, java.time.Instant updatedAt) {
    }

    public record VersionSummary(UUID id, int versionNumber, String changeNote, UUID createdBy,
                                 java.time.Instant createdAt) {
    }
}
