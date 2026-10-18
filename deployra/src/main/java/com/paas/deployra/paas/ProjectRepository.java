package com.paas.deployra.paas;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProjectRepository extends JpaRepository<Project, Long> {

    List<Project> findByOwnerIdAndDeletedAtIsNullOrderByCreatedAtDesc(Long ownerId);

    Optional<Project> findByIdAndOwnerIdAndDeletedAtIsNull(Long id, Long ownerId);

    long countByOwnerIdAndDeletedAtIsNull(Long ownerId);
}
