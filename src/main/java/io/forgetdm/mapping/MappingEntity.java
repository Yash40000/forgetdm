package io.forgetdm.mapping;

import jakarta.persistence.*;
import java.time.Instant;

/** A saved transformation mapping. {@code specJson} holds sources, joins, the transform pipeline, and the target. */
@Entity
@Table(name = "mapping_definitions")
public class MappingEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, unique = true) private String name;
    @Column(columnDefinition = "text") private String description;
    @Column(name = "spec_json", columnDefinition = "text", nullable = false) private String specJson;
    @Column(name = "created_at") private Instant createdAt = Instant.now();
    @Column(name = "updated_at") private Instant updatedAt = Instant.now();

    public Long getId() { return id; }
    public void setId(Long v) { id = v; }
    public String getName() { return name; }
    public void setName(String v) { name = v; }
    public String getDescription() { return description; }
    public void setDescription(String v) { description = v; }
    public String getSpecJson() { return specJson; }
    public void setSpecJson(String v) { specJson = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { createdAt = v; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { updatedAt = v; }
}
