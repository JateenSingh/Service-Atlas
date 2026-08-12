package com.serviceatlas.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ScanRepoRepository extends JpaRepository<ScanRepoEntity, Long> {

    List<ScanRepoEntity> findByScanIdOrderByRepoPathAsc(Long scanId);

    Optional<ScanRepoEntity> findByScanIdAndRepoPath(Long scanId, String repoPath);
}
