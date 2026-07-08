package io.forgetdm.mainframe;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface CopybookDefRepository extends JpaRepository<CopybookDefEntity, Long> {
    Optional<CopybookDefEntity> findByName(String name);
}
