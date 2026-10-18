package com.paas.deployra.paas;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EnvVariableRepository extends JpaRepository<EnvVariable, Long> {

    List<EnvVariable> findByServiceId(Long serviceId);

    Optional<EnvVariable> findByServiceIdAndVarKey(Long serviceId, String varKey);
}
