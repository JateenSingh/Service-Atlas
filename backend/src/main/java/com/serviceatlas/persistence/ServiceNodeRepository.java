package com.serviceatlas.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ServiceNodeRepository extends JpaRepository<ServiceNodeEntity, Long> {

    List<ServiceNodeEntity> findByScanId(Long scanId);
}
