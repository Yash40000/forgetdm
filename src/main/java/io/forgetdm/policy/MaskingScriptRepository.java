package io.forgetdm.policy;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MaskingScriptRepository extends JpaRepository<MaskingScriptEntity, Long> {
    Optional<MaskingScriptEntity> findByNameIgnoreCase(String name);
}
