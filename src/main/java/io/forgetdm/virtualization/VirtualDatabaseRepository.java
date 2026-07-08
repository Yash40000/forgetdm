package io.forgetdm.virtualization;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface VirtualDatabaseRepository extends JpaRepository<VirtualDatabaseEntity, Long> {
    Optional<VirtualDatabaseEntity> findByName(String name);
}
