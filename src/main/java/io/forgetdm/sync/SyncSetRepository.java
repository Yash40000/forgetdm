package io.forgetdm.sync;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SyncSetRepository extends JpaRepository<SyncSetEntity, Long> {
    Optional<SyncSetEntity> findByName(String name);
}
