package com.serviceatlas.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DependencyEdgeRepository extends JpaRepository<DependencyEdgeEntity, Long> {

    List<DependencyEdgeEntity> findByScanId(Long scanId);
}
