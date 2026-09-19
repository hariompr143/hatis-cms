package com.hatis.platform.cms.adapter.persistence;

import com.hatis.platform.cms.domain.ContentItem;
import com.hatis.platform.cms.domain.ContentType;
import com.hatis.platform.cms.domain.ContentVersion;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ContentRepositories {

    interface ContentTypeRepository extends JpaRepository<ContentType, UUID> {
        Optional<ContentType> findByIdAndOrganizationId(UUID id, UUID organizationId);

        Optional<ContentType> findByOrganizationIdAndProjectIdAndSlug(UUID organizationId,
                                                                      UUID projectId,
                                                                      String slug);

        boolean existsByOrganizationIdAndProjectIdAndSlug(UUID organizationId, UUID projectId, String slug);

        List<ContentType> findByOrganizationIdAndProjectId(UUID organizationId, UUID projectId);
    }

    interface ContentItemRepository extends JpaRepository<ContentItem, UUID> {
        Optional<ContentItem> findByIdAndOrganizationId(UUID id, UUID organizationId);

        Optional<ContentItem> findByOrganizationIdAndProjectIdAndLocaleAndSlug(UUID organizationId,
                                                                               UUID projectId,
                                                                               String locale,
                                                                               String slug);

        boolean existsByOrganizationIdAndProjectIdAndLocaleAndSlug(UUID organizationId,
                                                                   UUID projectId,
                                                                   String locale,
                                                                   String slug);

        Page<ContentItem> findByOrganizationIdAndProjectIdAndStatusNot(UUID organizationId,
                                                                       UUID projectId,
                                                                       ContentItem.Status status,
                                                                       Pageable pageable);

        Page<ContentItem> findByOrganizationIdAndProjectIdAndStatus(UUID organizationId,
                                                                    UUID projectId,
                                                                    ContentItem.Status status,
                                                                    Pageable pageable);

        long countByOrganizationId(UUID organizationId);

        @Query("""
                select i from ContentItem i
                where i.organizationId = :organizationId
                  and i.projectId = :projectId
                  and i.status = com.hatis.platform.cms.domain.ContentItem$Status.PUBLISHED
                  and i.publishedVersionId is not null
                """)
        Page<ContentItem> findPublished(@Param("organizationId") UUID organizationId,
                                        @Param("projectId") UUID projectId,
                                        Pageable pageable);
    }

    interface ContentVersionRepository extends JpaRepository<ContentVersion, UUID> {
        Optional<ContentVersion> findByIdAndOrganizationId(UUID id, UUID organizationId);

        List<ContentVersion> findByContentItemIdAndOrganizationIdOrderByVersionNumberDesc(UUID contentItemId,
                                                                                          UUID organizationId);

        @Query("""
                select coalesce(max(v.versionNumber), 0) from ContentVersion v
                where v.contentItemId = :contentItemId and v.organizationId = :organizationId
                """)
        int findMaxVersionNumber(@Param("contentItemId") UUID contentItemId,
                                 @Param("organizationId") UUID organizationId);
    }
}
