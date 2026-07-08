package io.forgetdm.virtualization;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TargetEnvironmentRepository extends JpaRepository<TargetEnvironmentEntity, Long> {
    Optional<TargetEnvironmentEntity> findByName(String name);
}
