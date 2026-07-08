package io.forgetdm.provision;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ValueListRepository extends JpaRepository<ValueListEntity, Long> {
    Optional<ValueListEntity> findByNameIgnoreCase(String name);
}
