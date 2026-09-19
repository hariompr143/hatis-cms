package com.hatis.platform.authorization.application;

import com.hatis.platform.authorization.domain.AuthorizationEntities;
import com.hatis.platform.shared.error.PlatformExceptions;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AuthorizationRepository {

    interface PermissionRepository extends JpaRepository<AuthorizationEntities.Permission, UUID> {
        Optional<AuthorizationEntities.Permission> findByCode(String code);
    }

    interface RoleRepository extends JpaRepository<AuthorizationEntities.Role, UUID> {
        Optional<AuthorizationEntities.Role> findByCodeAndSystemTrue(String code);

        List<AuthorizationEntities.Role> findByOrganizationId(UUID organizationId);
    }

    interface RoleBindingRepository extends JpaRepository<AuthorizationEntities.RoleBinding, UUID> {
        List<AuthorizationEntities.RoleBinding> findByOrganizationIdAndPrincipalId(UUID organizationId,
                                                                                  UUID principalId);

        List<AuthorizationEntities.RoleBinding> findByOrganizationId(UUID organizationId);
    }
}
