package com.hatis.platform.workflow.adapter.rest;

import com.hatis.platform.shared.api.PageResponse;
import com.hatis.platform.workflow.application.WorkflowService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
 * The workflow API: definitions, instances, transitions and the caller's task queue.
 *
 * <p>Permissions are named after what a person does rather than which rows they touch —
 * {@code workflow:read}, {@code workflow:start}, {@code workflow:transition},
 * {@code workflow:admin} — and the service checks them again for every request. The controller
 * adds no authorization of its own on purpose: a check that exists in two places is a check
 * that can disagree with itself, and the service is the one that background jobs also reach.
 */
@RestController
@RequestMapping("/v1/workflows")
@Tag(name = "Workflows", description = "Workflow definitions, instances, transitions and tasks")
public class WorkflowController {

    private final WorkflowService workflows;

    public WorkflowController(WorkflowService workflows) {
        this.workflows = workflows;
    }

    @GetMapping("/definitions")
    @Operation(summary = "List the workflow definitions this organization may start")
    public List<WorkflowService.DefinitionView> definitions(@RequestParam(required = false) UUID projectId) {
        return workflows.listDefinitions(projectId);
    }

    @PostMapping("/instances")
    @Operation(summary = "Start a workflow on a subject")
    public ResponseEntity<WorkflowService.InstanceDetail> start(@Valid @RequestBody StartRequest request) {
        var started = workflows.start(new WorkflowService.StartCommand(request.projectId(),
                request.subjectType(), request.subjectId(), request.definitionKey()));
        return ResponseEntity.status(HttpStatus.CREATED).body(started);
    }

    @GetMapping("/instances")
    @Operation(summary = "List workflow instances")
    public PageResponse<WorkflowService.InstanceSummary> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String subjectType,
            @RequestParam(required = false) UUID subjectId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return workflows.list(status, subjectType, subjectId, page, size);
    }

    @GetMapping("/instances/{instanceId}")
    @Operation(summary = "Read one instance with its tasks, history and available actions")
    public WorkflowService.InstanceDetail get(@PathVariable UUID instanceId) {
        return workflows.instance(instanceId);
    }

    @PostMapping("/instances/{instanceId}/transitions")
    @Operation(summary = "Move an instance along a named action")
    public WorkflowService.InstanceDetail transition(@PathVariable UUID instanceId,
                                                     @Valid @RequestBody TransitionRequest request) {
        return workflows.transition(new WorkflowService.TransitionCommand(instanceId,
                request.action(), request.comment()));
    }

    @PostMapping("/instances/{instanceId}/cancel")
    @Operation(summary = "Withdraw a running workflow")
    public WorkflowService.InstanceDetail cancel(@PathVariable UUID instanceId,
                                                 @Valid @RequestBody(required = false) CancelRequest request) {
        return workflows.cancel(instanceId, request == null ? null : request.reason());
    }

    @GetMapping("/tasks")
    @Operation(summary = "Tasks waiting on the caller, by name or by role")
    public PageResponse<WorkflowService.TaskView> myTasks(@RequestParam(defaultValue = "0") int page,
                                                          @RequestParam(defaultValue = "25") int size) {
        return workflows.myTasks(page, size);
    }

    public record StartRequest(
            UUID projectId,
            @NotBlank @Size(max = 64) String subjectType,
            @NotNull UUID subjectId,
            @Size(max = 120) String definitionKey) {
    }

    public record TransitionRequest(@NotBlank @Size(max = 120) String action,
                                    @Size(max = 2000) String comment) {
    }

    public record CancelRequest(@Size(max = 2000) String reason) {
    }
}
