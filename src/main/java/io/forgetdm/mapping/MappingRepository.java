package io.forgetdm.mapping;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface MappingRepository extends JpaRepository<MappingEntity, Long> {
    Optional<MappingEntity> findByNameIgnoreCase(String name);
}
