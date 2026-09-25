package com.hatis.platform.workflow.adapter.persistence;

import com.hatis.platform.workflow.domain.WorkflowDefinition;
import com.hatis.platform.workflow.domain.WorkflowDefinitionSpec;
import com.hatis.platform.workflow.domain.WorkflowInstance;
import com.hatis.platform.workflow.domain.WorkflowTask;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for the workflow aggregates.
 *
 * <p>Every tenant-owned query states its {@code organizationId} even though row level
 * security would already refuse a foreign row. The two are not redundant: the predicate is
 * the application's own guarantee and works on a replica or a database where the policy has
 * been misconfigured, and the policy is what still holds when a developer forgets.
 */
public interface WorkflowRepositories {

    interface DefinitionRepository extends JpaRepository<WorkflowDefinition, UUID> {

        /**
         * Definitions a tenant may use: its own, plus the platform templates.
         *
         * <p>Templates are rows with no organization. {@code V1_013}'s policy widens reads on
         * this table to include them and keeps writes strictly tenant-scoped, so the
         * predicate here mirrors the policy rather than working around it.
         */
        @Query("""
                select d from WorkflowDefinition d
                where d.organizationId = :organizationId or d.organizationId is null
                order by d.key asc, d.version desc
                """)
        List<WorkflowDefinition> findVisibleToTenant(@Param("organizationId") UUID organizationId);

        @Query("""
                select d from WorkflowDefinition d
                where d.id = :id and (d.organizationId = :organizationId or d.organizationId is null)
                """)
        Optional<WorkflowDefinition> findVisibleToTenant(@Param("id") UUID id,
                                                        @Param("organizationId") UUID organizationId);

        List<WorkflowDefinition> findByOrganizationIdAndProjectIdAndKeyAndStatusOrderByVersionDesc(
                UUID organizationId, UUID projectId, String key, WorkflowDefinition.Status status);

        List<WorkflowDefinition> findByOrganizationIdAndProjectIdIsNullAndKeyAndStatusOrderByVersionDesc(
                UUID organizationId, String key, WorkflowDefinition.Status status);

        List<WorkflowDefinition> findByOrganizationIdIsNullAndProjectIdIsNullAndKeyAndStatusOrderByVersionDesc(
                String key, WorkflowDefinition.Status status);
    }

    interface InstanceRepository extends JpaRepository<WorkflowInstance, UUID> {

        Optional<WorkflowInstance> findByIdAndOrganizationId(UUID id, UUID organizationId);

        Optional<WorkflowInstance> findFirstByOrganizationIdAndSubjectTypeAndSubjectIdAndStatus(
                UUID organizationId, String subjectType, UUID subjectId, WorkflowInstance.Status status);

        Page<WorkflowInstance> findByOrganizationId(UUID organizationId, Pageable pageable);

        Page<WorkflowInstance> findByOrganizationIdAndStatus(UUID organizationId,
                                                             WorkflowInstance.Status status,
                                                             Pageable pageable);

        Page<WorkflowInstance> findByOrganizationIdAndSubjectTypeAndSubjectId(UUID organizationId,
                                                                              String subjectType,
                                                                              UUID subjectId,
                                                                              Pageable pageable);
    }

    interface TaskRepository extends JpaRepository<WorkflowTask, UUID> {

        Optional<WorkflowTask> findByIdAndOrganizationId(UUID id, UUID organizationId);

        List<WorkflowTask> findByOrganizationIdAndInstanceIdOrderByCreatedAtAsc(UUID organizationId,
                                                                                UUID instanceId);

        List<WorkflowTask> findByOrganizationIdAndInstanceIdAndStatusIn(UUID organizationId,
                                                                        UUID instanceId,
                                                                        Collection<WorkflowTask.Status> statuses);

        /**
         * Open tasks this principal can act on, including the ones assigned to a role they hold.
         *
         * <p>Two queries rather than one with an {@code in} clause: a principal with no roles
         * would send an empty collection to the database, and an empty {@code in} list is
         * either an error or a silently-different query depending on the dialect and version.
         * {@code roleCodes} must be non-empty here, which both callers guarantee.
         */
        @Query("""
                select t from WorkflowTask t
                where t.organizationId = :organizationId
                  and t.status in :statuses
                  and (t.assigneeType = :anyType
                       or (t.assigneeType = :userType and t.assigneeId = :principalId)
                       or (t.assigneeType = :roleType and t.assigneeId in :roleCodes))
                """)
        Page<WorkflowTask> findAssignedToWithRoles(@Param("organizationId") UUID organizationId,
                                                   @Param("statuses") Collection<WorkflowTask.Status> statuses,
                                                   @Param("anyType") WorkflowDefinitionSpec.AssigneeType anyType,
                                                   @Param("userType") WorkflowDefinitionSpec.AssigneeType userType,
                                                   @Param("roleType") WorkflowDefinitionSpec.AssigneeType roleType,
                                                   @Param("principalId") String principalId,
                                                   @Param("roleCodes") Collection<String> roleCodes,
                                                   Pageable pageable);

        @Query("""
                select t from WorkflowTask t
                where t.organizationId = :organizationId
                  and t.status in :statuses
                  and (t.assigneeType = :anyType
                       or (t.assigneeType = :userType and t.assigneeId = :principalId))
                """)
        Page<WorkflowTask> findAssignedToWithoutRoles(@Param("organizationId") UUID organizationId,
                                                      @Param("statuses") Collection<WorkflowTask.Status> statuses,
                                                      @Param("anyType") WorkflowDefinitionSpec.AssigneeType anyType,
                                                      @Param("userType") WorkflowDefinitionSpec.AssigneeType userType,
                                                      @Param("principalId") String principalId,
                                                      Pageable pageable);
    }
}
