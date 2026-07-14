package io.forgetdm.mapping;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface MappingVersionRepository extends JpaRepository<MappingVersionEntity, Long> {
    List<MappingVersionEntity> findByMappingIdOrderByVersionNoDesc(Long mappingId);
    long countByMappingId(Long mappingId);
    void deleteByMappingId(Long mappingId);
}
