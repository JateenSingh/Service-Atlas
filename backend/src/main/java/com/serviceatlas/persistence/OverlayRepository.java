package com.serviceatlas.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OverlayRepository extends JpaRepository<OverlayEntity, Long> {

    List<OverlayEntity> findByWorkspaceId(Long workspaceId);

    List<OverlayEntity> findByWorkspaceIdAndKind(Long workspaceId, OverlayKind kind);

    Optional<OverlayEntity> findByWorkspaceIdAndKindAndTargetKey(
            Long workspaceId, OverlayKind kind, String targetKey);

    void deleteByWorkspaceIdAndKindAndTargetKey(Long workspaceId, OverlayKind kind, String targetKey);
}
