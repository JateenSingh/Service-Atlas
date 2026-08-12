package com.serviceatlas.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkspaceRepository extends JpaRepository<WorkspaceEntity, Long> {

    Optional<WorkspaceEntity> findByName(String name);

    boolean existsByName(String name);
}
