package io.forgetdm.unstructured;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface UnstructuredProfileRepository extends JpaRepository<UnstructuredProfileEntity, Long> {
    Optional<UnstructuredProfileEntity> findByNameIgnoreCase(String name);
}
