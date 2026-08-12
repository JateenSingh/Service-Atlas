package com.serviceatlas.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ScanRepository extends JpaRepository<ScanEntity, Long> {

    List<ScanEntity> findByWorkspaceIdOrderByIdDesc(Long workspaceId);

    /** The newest scan that actually produced a graph — what {@code GET /graph} serves. */
    Optional<ScanEntity> findFirstByWorkspaceIdAndStatusOrderByIdDesc(Long workspaceId, ScanStatus status);

    Optional<ScanEntity> findFirstByWorkspaceIdOrderByIdDesc(Long workspaceId);
}
