package com.hatis.platform.identity.adapter.persistence;
import com.hatis.platform.identity.domain.ServiceAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Machine principal persistence. */
public interface ServiceAccountRepository extends JpaRepository<ServiceAccount, UUID> {

    Optional<ServiceAccount> findByIdAndOrganizationId(UUID id, UUID organizationId);

    List<ServiceAccount> findByOrganizationId(UUID organizationId);
}
