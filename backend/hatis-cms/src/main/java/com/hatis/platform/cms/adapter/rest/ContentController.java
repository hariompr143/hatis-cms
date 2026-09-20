package com.hatis.platform.cms.adapter.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.hatis.platform.cms.application.ContentService;
import com.hatis.platform.shared.api.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
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
 * Content authoring API.
 *
 * <p>Authorization is checked here and again in the service; the service check is
 * the authoritative one because background jobs and other contexts reach the same
 * code without going through HTTP.
 */
@RestController
@RequestMapping("/v1/content")
@Tag(name = "Content", description = "Content types, items, versions and publishing")
public class ContentController {

    private final ContentService content;

    public ContentController(ContentService content) {
        this.content = content;
    }

    // ------------------------------------------------------------------ types

    @GetMapping("/types")
    @Operation(summary = "List content types in a project")
    public List<ContentTypeView> listTypes(@RequestParam UUID projectId) {
        return content.listTypes(projectId).stream().map(ContentTypeView::from).toList();
    }

    @PostMapping("/types")
    @Operation(summary = "Create a content type")
    public ResponseEntity<ContentTypeView> createType(@Valid @RequestBody CreateContentTypeRequest request) {
        var created = content.createType(request.projectId(), request.name(), request.slug(),
                request.description(), request.schema(), request.titleField());
        return ResponseEntity.status(HttpStatus.CREATED).body(ContentTypeView.from(created));
    }

    @PostMapping("/types/{typeId}/schema")
    @Operation(summary = "Publish a new schema version for a content type")
    public ContentTypeView reviseSchema(@PathVariable UUID typeId,
                                        @Valid @RequestBody ReviseSchemaRequest request) {
        return ContentTypeView.from(content.reviseSchema(typeId, request.schema()));
    }

    // ------------------------------------------------------------------ items

    @GetMapping("/items")
    @Operation(summary = "List content items")
    public PageResponse<ContentService.ContentSummary> list(@RequestParam UUID projectId,
                                                            @RequestParam(required = false) String status,
                                                            @RequestParam(defaultValue = "0") int page,
                                                            @RequestParam(defaultValue = "25") int size) {
        return content.list(projectId, status, page, size);
    }

    @GetMapping("/items/{itemId}")
    @Operation(summary = "Get a content item with its current version")
    public ContentService.ContentDetail get(@PathVariable UUID itemId) {
        return content.get(itemId);
    }

    @PostMapping("/items")
    @Operation(summary = "Create a content item and its first version")
    public ResponseEntity<ContentService.ContentDetail> create(@Valid @RequestBody CreateContentRequest request) {
        var created = content.create(request.projectId(), request.contentTypeId(), request.slug(),
                request.locale(), request.body(), request.changeNote());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PostMapping("/items/{itemId}/versions")
    @Operation(summary = "Save a new draft version")
    public ContentService.ContentDetail update(@PathVariable UUID itemId,
                                               @Valid @RequestBody UpdateContentRequest request) {
        return content.update(itemId, request.body(), request.changeNote());
    }

    @PostMapping("/items/{itemId}/publish")
    @Operation(summary = "Publish the current version")
    public ContentService.ContentDetail publish(@PathVariable UUID itemId) {
        return content.publish(itemId);
    }

    @PostMapping("/items/{itemId}/unpublish")
    @Operation(summary = "Remove content from the delivery API without deleting the draft")
    public ContentService.ContentDetail unpublish(@PathVariable UUID itemId) {
        return content.unpublish(itemId);
    }

    @PostMapping("/items/{itemId}/rollback")
    @Operation(summary = "Roll the published version back to an earlier version")
    public ContentService.ContentDetail rollback(@PathVariable UUID itemId,
                                                 @Valid @RequestBody RollbackRequest request) {
        return content.rollback(itemId, request.versionNumber());
    }

    @GetMapping("/items/{itemId}/versions")
    @Operation(summary = "List the version history of a content item")
    public List<ContentService.VersionSummary> history(@PathVariable UUID itemId) {
        return content.history(itemId);
    }

    @DeleteMapping("/items/{itemId}")
    @Operation(summary = "Soft delete a content item")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID itemId) {
        content.delete(itemId);
    }

    // ---------------------------------------------------------------- records

    public record CreateContentTypeRequest(
            @NotNull UUID projectId,
            @NotBlank @Size(max = 120) String name,
            @NotBlank @Size(max = 63) @Pattern(regexp = "[a-z0-9][a-z0-9-]*") String slug,
            @Size(max = 2000) String description,
            @NotBlank String schema,
            @Size(max = 120) String titleField) {
    }

    public record ReviseSchemaRequest(@NotBlank String schema) {
    }

    public record CreateContentRequest(
            @NotNull UUID projectId,
            @NotNull UUID contentTypeId,
            @NotBlank @Size(max = 255) String slug,
            @Size(max = 16) String locale,
            @NotNull JsonNode body,
            @Size(max = 512) String changeNote) {
    }

    public record UpdateContentRequest(@NotNull JsonNode body, @Size(max = 512) String changeNote) {
    }

    public record RollbackRequest(@NotNull Integer versionNumber) {
    }

    public record ContentTypeView(UUID id, UUID projectId, String name, String slug, String description,
                                  String schema, String titleField, String status, int schemaVersion) {

        public static ContentTypeView from(com.hatis.platform.cms.domain.ContentType type) {
            return new ContentTypeView(type.getId(), type.getProjectId(), type.getName(), type.getSlug(),
                    type.getDescription(), type.getSchema(), type.getTitleField(),
                    type.getStatus().name(), type.getSchemaVersion());
        }
    }
}
