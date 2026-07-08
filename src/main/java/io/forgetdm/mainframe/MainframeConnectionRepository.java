package io.forgetdm.mainframe;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface MainframeConnectionRepository extends JpaRepository<MainframeConnectionEntity, Long> {
    Optional<MainframeConnectionEntity> findByName(String name);
}
