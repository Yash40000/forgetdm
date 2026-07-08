package io.forgetdm.provision;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

public interface ProvisionJobRepository extends JpaRepository<ProvisionJobEntity, Long> {

    /** Delete all terminal jobs whose finishedAt is before the given cutoff. */
    @Modifying
    @Transactional
    @Query("DELETE FROM ProvisionJobEntity j WHERE j.finishedAt < :cutoff " +
           "AND j.status NOT IN ('RUNNING', 'PENDING', 'CANCEL_REQUESTED')")
    int deleteFinishedBefore(@Param("cutoff") Instant cutoff);
}
