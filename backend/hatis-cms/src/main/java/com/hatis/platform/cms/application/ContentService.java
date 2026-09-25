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
import com.hatis.platform.workflow.application.WorkflowService;
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

    /**
     * The catalogue's codes for this module's capabilities.
     *
     * <p>They are constants rather than literals because a check against a code the catalogue
     * does not define denies everybody: {@code AuthorizationService} matches the code against
     * what a role was granted, so a typo is not a weaker check, it is a permanently closed
     * door. The names come from {@code V1_003} and are spelled the way the rest of the
     * platform spells them ({@code cms:content:publish}, not {@code content:publish}).
     */
    public static final String READ_PERMISSION = "cms:content:read";
    public static final String WRITE_PERMISSION = "cms:content:write";
    public static final String SUBMIT_PERMISSION = "cms:content:submit";
    public static final String APPROVE_PERMISSION = "cms:content:approve";
    public static final String PUBLISH_PERMISSION = "cms:content:publish";
    public static final String TYPE_WRITE_PERMISSION = "cms:type:write";

    /** What a workflow instance is started against when an item goes to review. */
    public static final String REVIEW_SUBJECT_TYPE = "content_item";

    /** The state a rejection leaves the seeded editorial flow in, until the author reworks. */
    private static final String REJECTED_STATE = "rejected";

    private final ContentRepositories.ContentTypeRepository types;
    private final ContentRepositories.ContentItemRepository items;
    private final ContentRepositories.ContentVersionRepository versions;
    private final AuthorizationService authorization;
    private final QuotaService quotas;
    private final EventPublisher events;
    private final ObjectMapper mapper;
    private final WorkflowService workflows;

    public ContentService(ContentRepositories.ContentTypeRepository types,
                          ContentRepositories.ContentItemRepository items,
                          ContentRepositories.ContentVersionRepository versions,
                          AuthorizationService authorization,
                          QuotaService quotas,
                          EventPublisher events,
                          ObjectMapper mapper,
                          WorkflowService workflows) {
        this.types = types;
        this.items = items;
        this.versions = versions;
        this.authorization = authorization;
        this.quotas = quotas;
        this.events = events;
        this.mapper = mapper;
        this.workflows = workflows;
    }

    // ---------------------------------------------------------------- types

    @TenantTransactional(readOnly = true)
    public List<ContentType> listTypes(UUID projectId) {
        UUID organizationId = requireTenant(ScopeType.PROJECT, projectId,
                READ_PERMISSION);
        return types.findByOrganizationIdAndProjectId(organizationId, projectId);
    }

    @TenantTransactional(readOnly = true)
    public ContentType type(UUID typeId) {
        UUID organizationId = TenantContextHolder.require().requireOrganizationId();
        ContentType type = types.findByIdAndOrganizationId(typeId, organizationId)
                .orElseThrow(() -> new PlatformExceptions.NotFound("Content type", typeId));
        authorization.require(READ_PERMISSION, ScopeType.PROJECT, type.getProjectId());
        return type;
    }

    @TenantTransactional
    public ContentType createType(UUID projectId, String name, String slug, String description,
                                  String schema, String titleField) {
        UUID organizationId = requireTenant(ScopeType.PROJECT, projectId,
                TYPE_WRITE_PERMISSION);
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
        UUID organizationId = TenantContextHolder.require().requireOrganizationId();
        ContentType type = types.findByIdAndOrganizationId(typeId, organizationId)
                .orElseThrow(() -> new PlatformExceptions.NotFound("Content type", typeId));
        authorization.require(TYPE_WRITE_PERMISSION, ScopeType.PROJECT, type.getProjectId());
        parseSchema(schema);
        type.reviseSchema(schema);
        return types.save(type);
    }

    // ---------------------------------------------------------------- items

    @TenantTransactional(readOnly = true)
    public PageResponse<ContentSummary> list(UUID projectId, String status, int page, int size) {
        UUID organizationId = requireTenant(ScopeType.PROJECT, projectId, READ_PERMISSION);
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
        ContentItem item = requireItemFor(itemId, READ_PERMISSION);
        ContentVersion version = currentVersion(item, item.getOrganizationId());
        return toDetail(item, version);
    }

    @TenantTransactional
    public ContentDetail create(UUID projectId, UUID contentTypeId, String slug, String locale,
                                JsonNode body, String changeNote) {
        UUID organizationId = requireTenant(ScopeType.PROJECT, projectId, WRITE_PERMISSION);
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
        ContentItem item = requireItemFor(itemId, WRITE_PERMISSION);
        UUID organizationId = item.getOrganizationId();
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

    // --------------------------------------------------------------- review

    /**
     * Starts the editorial review of an item's current version.
     *
     * <p>The workflow engine owns the states and this method owns the item. Starting a review
     * creates one instance for the item and immediately walks it along the definition's first
     * transition, so "in review" means a reviewer has a task rather than that a row exists. A
     * second submission while a review is running is refused by the engine — the thing that
     * knows — rather than by a second status check here.
     */
    @TenantTransactional
    public ContentDetail submitForReview(UUID itemId) {
        ContentItem item = requireItemFor(itemId, SUBMIT_PERMISSION);
        UUID organizationId = item.getOrganizationId();

        UUID instanceId = item.getWorkflowInstanceId();
        if (instanceId == null) {
            instanceId = workflows.start(new WorkflowService.StartCommand(item.getProjectId(),
                            REVIEW_SUBJECT_TYPE, item.getId(), WorkflowService.DEFAULT_DEFINITION_KEY))
                    .instance().id();
        } else if (REJECTED_STATE.equals(workflows.instance(instanceId).instance().currentState())) {
            // A rejection leaves the instance running: the seeded definition expects the author
            // to rework it, and only then can it be submitted again.
            workflows.transition(new WorkflowService.TransitionCommand(instanceId, "rework", null));
        }

        // The engine moves first and the item follows, the same way a decision does. A
        // transition the definition does not allow is refused there, before the item's status
        // has been changed to something the workflow disagrees with.
        workflows.transition(new WorkflowService.TransitionCommand(instanceId, "submit", null));
        item.submitForReview(instanceId);
        items.save(item);

        events.publish(PlatformEvent.of("cms.content.submitted", organizationId)
                .resource("content_item", item.getId())
                .data(Map.of("slug", item.getSlug(), "workflowInstanceId", instanceId.toString()))
                .build());
        return toDetail(item, currentVersion(item, organizationId));
    }

    /** Approves an item under review, moving the item and its workflow instance together. */
    @TenantTransactional
    public ContentDetail approve(UUID itemId, String comment) {
        ContentItem item = requireItemFor(itemId, APPROVE_PERMISSION);
        UUID organizationId = item.getOrganizationId();
        UUID instanceId = requireRunningReview(item);

        workflows.transition(new WorkflowService.TransitionCommand(instanceId, "approve", comment));
        item.approve();
        items.save(item);

        events.publish(PlatformEvent.of("cms.content.approved", organizationId)
                .resource("content_item", item.getId())
                .build());
        return toDetail(item, currentVersion(item, organizationId));
    }

    /** Rejects an item under review. The item returns to draft; the review keeps its history. */
    @TenantTransactional
    public ContentDetail reject(UUID itemId, String comment) {
        ContentItem item = requireItemFor(itemId, APPROVE_PERMISSION);
        UUID organizationId = item.getOrganizationId();
        UUID instanceId = requireRunningReview(item);

        workflows.transition(new WorkflowService.TransitionCommand(instanceId, "reject", comment));
        item.reject();
        items.save(item);

        events.publish(PlatformEvent.of("cms.content.rejected", organizationId)
                .resource("content_item", item.getId())
                .build());
        return toDetail(item, currentVersion(item, organizationId));
    }

    @TenantTransactional
    public ContentDetail publish(UUID itemId) {
        ContentItem item = requireItemFor(itemId, PUBLISH_PERMISSION);
        UUID organizationId = item.getOrganizationId();
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
        ContentItem item = requireItemFor(itemId, PUBLISH_PERMISSION);
        UUID organizationId = item.getOrganizationId();
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
        ContentItem item = requireItemFor(itemId, PUBLISH_PERMISSION);
        UUID organizationId = item.getOrganizationId();
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
        ContentItem item = requireItemFor(itemId, READ_PERMISSION);
        UUID organizationId = item.getOrganizationId();
        return versions.findByContentItemIdAndOrganizationIdOrderByVersionNumberDesc(item.getId(), organizationId)
                .stream()
                .map(v -> new VersionSummary(v.getId(), v.getVersionNumber(), v.getChangeNote(),
                        v.getCreatedBy(), v.getCreatedAt()))
                .toList();
    }

    /** Soft delete: the row remains for export and audit until the retention job runs. */
    @TenantTransactional
    public void delete(UUID itemId) {
        ContentItem item = requireItemFor(itemId, WRITE_PERMISSION);
        UUID organizationId = item.getOrganizationId();
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

    /**
     * The item's running review, or a refusal.
     *
     * <p>Checked before the workflow moves. A decision taken against an item that is not in
     * review would otherwise move the workflow and then fail on the item; both would roll
     * back, but the message a caller sees should name what is actually wrong.
     */
    private UUID requireRunningReview(ContentItem item) {
        if (item.getStatus() != ContentItem.Status.IN_REVIEW || item.getWorkflowInstanceId() == null) {
            throw new PlatformExceptions.StateConflict(
                    "Content in state " + item.getStatus() + " has no review to decide");
        }
        return item.getWorkflowInstanceId();
    }

    private ContentVersion currentVersion(ContentItem item, UUID organizationId) {
        return item.getCurrentVersionId() == null ? null
                : versions.findByIdAndOrganizationId(item.getCurrentVersionId(), organizationId).orElse(null);
    }

    /**
     * Loads one item and authorizes the operation at the scope the item lives in.
     *
     * <p>The project is resolved from the item rather than assumed, for the same reason
     * {@code list} takes a project id: a role bound to a single project must be able to do the
     * work that belongs to that project. {@code AuthorizationService.covers} treats an
     * organization binding as covering every narrower scope, so checking at the project costs
     * nothing for the common case and is the difference between a project-scoped grant working
     * and being silently ignored.
     *
     * <p>The item is read before the check because its project is what the check is about. The
     * read is tenant-scoped, and the decision happens before anything is returned or changed.
     */
    private ContentItem requireItemFor(UUID itemId, String permission) {
        UUID organizationId = TenantContextHolder.require().requireOrganizationId();
        ContentItem item = requireItem(itemId, organizationId);
        authorization.require(permission, ScopeType.PROJECT, item.getProjectId());
        return item;
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
                item.getPublishedVersionId(), item.getWorkflowInstanceId(),
                version == null ? null : version.getVersionNumber(), body,
                item.getPublishedAt(), item.getCreatedAt(), item.getUpdatedAt());
    }

    public record ContentSummary(UUID id, String slug, String locale, String status, UUID projectId,
                                 UUID contentTypeId, java.time.Instant publishedAt,
                                 java.time.Instant updatedAt) {
    }

    public record ContentDetail(UUID id, UUID projectId, UUID contentTypeId, String slug, String locale,
                                String status, UUID currentVersionId, UUID publishedVersionId,
                                UUID workflowInstanceId,
                                Integer versionNumber, JsonNode body, java.time.Instant publishedAt,
                                java.time.Instant createdAt, java.time.Instant updatedAt) {
    }

    public record VersionSummary(UUID id, int versionNumber, String changeNote, UUID createdBy,
                                 java.time.Instant createdAt) {
    }
}
