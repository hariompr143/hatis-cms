package com.hatis.platform.identity.adapter.persistence;
import com.hatis.platform.identity.domain.MfaEnrolment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Second-factor enrolment persistence. */
public interface MfaEnrolmentRepository extends JpaRepository<MfaEnrolment, UUID> {

    Optional<MfaEnrolment> findFirstByUserIdAndTypeAndVerifiedAtIsNotNull(UUID userId,
                                                                          MfaEnrolment.Type type);

    List<MfaEnrolment> findByUserId(UUID userId);

    void deleteByUserId(UUID userId);
}
