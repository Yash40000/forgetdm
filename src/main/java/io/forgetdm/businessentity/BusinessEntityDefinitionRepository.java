package io.forgetdm.businessentity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface BusinessEntityDefinitionRepository extends JpaRepository<BusinessEntityDefinitionEntity, Long> {
    Optional<BusinessEntityDefinitionEntity> findByName(String name);
}
