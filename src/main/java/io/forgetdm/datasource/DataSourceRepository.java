package io.forgetdm.datasource;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DataSourceRepository extends JpaRepository<DataSourceEntity, Long> {
    Optional<DataSourceEntity> findByName(String name);
}
